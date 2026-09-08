package com.quickstart;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.quickstart.util.ConfigTransfer;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import android.widget.Button;

/**
 * 二维码配置传送。
 * SEND：把配置 JSON 分帧生成二维码循环轮播，供另一台设备扫码。
 * RECEIVE：相机连续扫码拼包，收齐并校验后确认导入（覆盖本机配置）。
 * 全程仅靠屏幕 + 摄像头，不需要网络。
 */
public class QrTransferActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "mode";
    public static final String MODE_SEND = "send";
    public static final String MODE_RECEIVE = "receive";

    private static final int REQUEST_CAMERA_PERMISSION = 1001;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService executor;

    // ===== 发送端状态 =====
    private ImageView qrImage;
    private TextView sendProgress;
    private Bitmap[] frameBitmaps;
    private int frameIndex = 0;

    // ===== 接收端状态 =====
    private SurfaceView previewView;
    private TextView receiveProgress;
    private LinearLayout permissionArea;
    private Camera camera;
    private MultiFormatReader reader;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private ConfigTransfer.Reassembler reassembler;
    private volatile boolean surfaceReady = false;
    private boolean finishedTransfer = false;

    private final Camera.PreviewCallback previewCallback = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(byte[] data, Camera cam) {
            if (cam == null || finishedTransfer) {
                if (cam != null) cam.addCallbackBuffer(data);
                return;
            }
            boolean claimed = busy.compareAndSet(false, true);
            if (claimed) {
                Camera.Size size = cam.getParameters().getPreviewSize();
                // 复制后立即还回 buffer，解码在后台线程对副本进行
                byte[] copy = Arrays.copyOf(data, data.length);
                executor.execute(() -> handlePreviewFrame(copy, size.width, size.height));
            }
            cam.addCallbackBuffer(data);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_transfer);
        executor = Executors.newSingleThreadExecutor();

        String mode = getIntent().getStringExtra(EXTRA_MODE);
        if (MODE_RECEIVE.equals(mode)) {
            if (getSupportActionBar() != null) getSupportActionBar().setTitle("扫码接收配置");
            setupReceiveMode();
        } else {
            if (getSupportActionBar() != null) getSupportActionBar().setTitle("扫码传送配置");
            setupSendMode();
        }
    }

    // ==================== 发送端 ====================

    private void setupSendMode() {
        findViewById(R.id.send_container).setVisibility(View.VISIBLE);
        qrImage = findViewById(R.id.qr_image);
        sendProgress = findViewById(R.id.send_progress);
        TextView sendHint = findViewById(R.id.send_hint);
        sendHint.setText("让另一台设备打开「快开启 → 设置 → 导入/导出配置 → 扫码接收其他设备的配置」，"
                + "摄像头对准此屏幕即可。单帧直接扫码，多帧请手动翻页，漏扫的帧再翻回去补扫。");
        sendProgress.setText("正在生成二维码...");

        executor.execute(() -> {
            try {
                String json = ConfigTransfer.buildConfigJson(this);
                String[] frames = ConfigTransfer.encodeFrames(json);
                int size = (int) (getResources().getDisplayMetrics().widthPixels * 0.8f);
                Bitmap[] bitmaps = new Bitmap[frames.length];
                for (int i = 0; i < frames.length; i++) {
                    bitmaps[i] = createQrBitmap(frames[i], size);
                }
                mainHandler.post(() -> {
                    if (isFinishing()) return;
                    frameBitmaps = bitmaps;
                    showFrame(0);
                    if (bitmaps.length > 1) {
                        findViewById(R.id.frame_nav).setVisibility(View.VISIBLE);
                    }
                });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    if (!isFinishing()) sendProgress.setText("生成二维码失败：" + t.getMessage());
                });
            }
        });

        findViewById(R.id.btn_frame_prev).setOnClickListener(v -> {
            if (frameBitmaps != null && frameBitmaps.length > 1) {
                showFrame(frameIndex - 1 + frameBitmaps.length);
            }
        });
        findViewById(R.id.btn_frame_next).setOnClickListener(v -> {
            if (frameBitmaps != null && frameBitmaps.length > 1) {
                showFrame(frameIndex + 1);
            }
        });
    }

    private void showFrame(int index) {
        if (frameBitmaps == null || frameBitmaps.length == 0) return;
        frameIndex = index % frameBitmaps.length;
        qrImage.setImageBitmap(frameBitmaps[frameIndex]);
        sendProgress.setText("第 " + (frameIndex + 1) + "/" + frameBitmaps.length + " 帧");
    }

    /** 生成二维码位图（黑底白字之外的纯黑白，纠错级别 M） */
    private Bitmap createQrBitmap(String content, int sizePx) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 2);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix matrix = new QRCodeWriter()
                .encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
        int w = matrix.getWidth(), h = matrix.getHeight();
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                pixels[y * w + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.RGB_565);
    }

    // ==================== 接收端 ====================

    private void setupReceiveMode() {
        findViewById(R.id.receive_container).setVisibility(View.VISIBLE);
        previewView = findViewById(R.id.preview);
        receiveProgress = findViewById(R.id.receive_progress);
        permissionArea = findViewById(R.id.permission_area);
        receiveProgress.setText("正在启动相机...");

        Button grantButton = findViewById(R.id.btn_grant_camera);
        grantButton.setOnClickListener(v -> requestCameraPermission());

        if (hasCameraPermission()) {
            initPreviewSurface();
        } else {
            requestCameraPermission();
        }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                tryStartPreview();
            } else {
                permissionArea.setVisibility(View.VISIBLE);
                TextView hint = findViewById(R.id.permission_hint);
                hint.setText("扫码接收配置需要相机权限，用于识别对方屏幕上的二维码。\n"
                        + "相机画面仅在本机解析，不会上传或保存。");
            }
        }
    }

    private void initPreviewSurface() {
        SurfaceHolder holder = previewView.getHolder();
        holder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder h) {
                surfaceReady = true;
                tryStartPreview();
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder h, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder h) {
                surfaceReady = false;
                releaseCamera();
            }
        });
        // 布局已完成时 surface 可能已就绪（如从权限弹窗返回）
        if (holder.getSurface() != null && holder.getSurface().isValid()) {
            surfaceReady = true;
            tryStartPreview();
        }
    }

    /** 相机启动统一入口：权限、surface、状态都满足才真正打开 */
    private void tryStartPreview() {
        if (isFinishing() || finishedTransfer || !surfaceReady || !hasCameraPermission()
                || camera != null) {
            return;
        }
        permissionArea.setVisibility(View.GONE);
        executor.execute(this::openCameraAndPreview);
    }

    private void openCameraAndPreview() {
        if (!surfaceReady || isFinishing() || camera != null) return;
        try {
            Camera cam = openBackCamera();
            if (cam == null) {
                mainHandler.post(() -> {
                    if (!isFinishing()) receiveProgress.setText("本机没有可用的后置摄像头");
                });
                return;
            }
            Camera.Parameters params = cam.getParameters();
            Camera.Size best = choosePreviewSize(params);
            params.setPreviewSize(best.width, best.height);
            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_MACRO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_MACRO);
            }
            cam.setParameters(params);
            cam.setDisplayOrientation(90); // 竖屏预览（解码时会同时尝试两个旋转方向）

            cam.setPreviewDisplay(previewView.getHolder());
            cam.setPreviewCallbackWithBuffer(previewCallback);
            int bufSize = best.width * best.height * 3 / 2;
            cam.addCallbackBuffer(new byte[bufSize]);
            cam.addCallbackBuffer(new byte[bufSize]);
            cam.startPreview();
            camera = cam;
            mainHandler.post(() -> {
                if (!isFinishing()) {
                    receiveProgress.setText("请对准对方屏幕上的二维码");
                }
            });
        } catch (Throwable t) {
            releaseCameraInternal();
            String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            mainHandler.post(() -> {
                if (!isFinishing()) receiveProgress.setText("相机启动失败：" + msg);
            });
        }
    }

    private Camera openBackCamera() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                try {
                    return Camera.open(i);
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            return Camera.open();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 选一块接近 720p 的预览尺寸：太小识别率差，太大解码慢 */
    private Camera.Size choosePreviewSize(Camera.Parameters params) {
        Camera.Size best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Camera.Size s : params.getSupportedPreviewSizes()) {
            if (s.width < 640 || s.height < 480) continue;
            int score = Math.abs(s.width * s.height - 1280 * 720);
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        if (best == null) best = params.getSupportedPreviewSizes().get(0);
        return best;
    }

    /** 解码线程：NV21 → 中心裁剪 → zxing 连续识别（自动尝试旋转方向） */
    private void handlePreviewFrame(byte[] data, int width, int height) {
        try {
            if (reader == null) {
                reader = new MultiFormatReader();
                Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
                hints.put(DecodeHintType.POSSIBLE_FORMATS,
                        Collections.singletonList(BarcodeFormat.QR_CODE));
                hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
                reader.setHints(hints);
            }
            int side = Math.min(width, height);
            int cropSize = side * 3 / 4;
            int left = (width - cropSize) / 2;
            int top = (height - cropSize) / 2;
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    data, width, height, left, top, cropSize, cropSize, false);

            Result result = null;
            try {
                result = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source)));
            } catch (ReaderException e) {
                reader.reset();
                try {
                    result = reader.decodeWithState(
                            new BinaryBitmap(new HybridBinarizer(source.rotateCounterClockwise())));
                } catch (ReaderException ignored) {
                }
            } finally {
                reader.reset();
            }
            if (result != null) {
                onQrDecoded(result.getText());
            }
        } catch (Throwable ignored) {
        } finally {
            busy.set(false);
        }
    }

    private void onQrDecoded(String text) {
        ConfigTransfer.Frame frame = ConfigTransfer.parseFrame(text);
        if (frame == null) return; // 不是本应用的配置帧
        if (reassembler == null) {
            reassembler = new ConfigTransfer.Reassembler(frame);
        } else if (!reassembler.belongsTo(frame)) {
            reassembler = new ConfigTransfer.Reassembler(frame); // 新一轮传送，重置收集
        }
        reassembler.offer(frame);

        final int received = reassembler.getReceived();
        final int total = reassembler.getTotal();
        String payload = null;
        String error = null;
        if (reassembler.isComplete()) {
            try {
                payload = reassembler.assemble();
            } catch (Throwable t) {
                error = t.getMessage();
                reassembler = null; // 校验失败，等下一轮循环重收
            }
        }
        final String done = payload;
        final String err = error;
        mainHandler.post(() -> {
            if (isFinishing()) return;
            if (err != null) {
                receiveProgress.setText("数据校验失败，正在重新接收...");
                return;
            }
            if (done != null) {
                if (!finishedTransfer) {
                    finishedTransfer = true;
                    releaseCamera();
                    showImportConfirm(done);
                }
            } else {
                receiveProgress.setText("已收到 " + received + "/" + total + " 帧，请保持对准...");
            }
        });
    }

    private void showImportConfirm(String payload) {
        int count;
        try {
            count = new org.json.JSONObject(payload).length();
        } catch (Exception e) {
            Toast.makeText(this, "收到的数据异常", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("收到配置")
                .setMessage("已通过二维码收到对方设备的全部配置（共 " + count + " 项，"
                        + "含界面设置、数字键绑定、隐藏应用）。\n\n导入将覆盖本机当前配置，是否继续？")
                .setPositiveButton("导入", (d, w) -> {
                    try {
                        int n = ConfigTransfer.applyConfigJson(this, payload);
                        Toast.makeText(this, "已导入 " + n + " 项配置，重启应用后生效",
                                Toast.LENGTH_LONG).show();
                    } catch (Throwable t) {
                        Toast.makeText(this, "导入失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
                    }
                    finish();
                })
                .setNegativeButton("取消", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    // ==================== 生命周期 ====================

    private void releaseCamera() {
        executor.execute(this::releaseCameraInternal);
    }

    private synchronized void releaseCameraInternal() {
        if (camera != null) {
            try {
                camera.stopPreview();
            } catch (Throwable ignored) {
            }
            try {
                camera.setPreviewCallbackWithBuffer(null);
            } catch (Throwable ignored) {
            }
            try {
                camera.release();
            } catch (Throwable ignored) {
            }
            camera = null;
        }
    }

    @Override
    protected void onPause() {
        // 离开屏幕即释放相机，回来时重新启动
        releaseCamera();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (previewView != null && !finishedTransfer && hasCameraPermission()) {
            tryStartPreview();
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        surfaceReady = false;
        executor.shutdownNow();
        releaseCameraInternal();
        if (frameBitmaps != null) {
            for (Bitmap b : frameBitmaps) {
                if (b != null) b.recycle();
            }
            frameBitmaps = null;
        }
        super.onDestroy();
    }
}
