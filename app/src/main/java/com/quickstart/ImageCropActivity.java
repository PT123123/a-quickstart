package com.quickstart;

import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.quickstart.util.BackgroundManager;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 背景图裁剪界面。
 *
 * 规则：
 *  - 裁剪框比例固定为「应用列表显示区」的宽高比（MainActivity 实测后写入 settings），
 *    这样裁出来的图贴到列表背景上不需要再被裁掉一块；
 *  - 裁剪框大小固定（在图片内按该比例取最大值），不可拖拽缩放，只能整体平移；
 *  - 输出分辨率按应用列表的实际像素宽度归一化，避免存一张超大图；
 *  - 保存原图后立即**预生成**三档模糊图，主界面运行时不再计算模糊。
 */
public class ImageCropActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_URI = "image_uri";
    public static final String EXTRA_OUTPUT_PATH = "output_path";

    /** 默认宽高比（应用列表区域估算值，实测值写入后以此为准） */
    private static final float DEFAULT_ASPECT = 0.75f;
    /** 解码上限，防止超大图 OOM */
    private static final int MAX_DECODE_EDGE = 2560;

    private CropView cropView;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private ProgressDialog progress;
    /** 是否正在保存，防止连点触发并发任务 */
    private boolean saving;
    /** 裁剪方式：true = 锁定应用列表比例，false = 自由裁剪 */
    private boolean lockAspect = true;
    private Button btnRatioList, btnRatioFree;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop);

        cropView = findViewById(R.id.crop_view);
        Button btnCancel = findViewById(R.id.btn_cancel);
        Button btnConfirm = findViewById(R.id.btn_confirm);

        // 点提示文字恢复默认比例
        View cropHint = findViewById(R.id.crop_hint);
        if (cropHint != null) {
            cropHint.setOnClickListener(v -> cropView.resetCropRect());
        }

        // 裁剪方式：按列表比例（锁定）/ 自由裁剪
        btnRatioList = findViewById(R.id.btn_ratio_list);
        btnRatioFree = findViewById(R.id.btn_ratio_free);
        if (btnRatioList != null) btnRatioList.setOnClickListener(v -> {
            lockAspect = true;
            applyRatioMode();
        });
        if (btnRatioFree != null) btnRatioFree.setOnClickListener(v -> {
            lockAspect = false;
            applyRatioMode();
        });
        applyRatioMode();

        float aspect = getSharedPreferences(BackgroundManager.PREFS, MODE_PRIVATE)
                .getFloat(BackgroundManager.PREF_ASPECT, DEFAULT_ASPECT);
        if (!(aspect > 0.1f && aspect < 10f)) aspect = DEFAULT_ASPECT;
        cropView.setAspect(aspect);

        String uriStr = getIntent().getStringExtra(EXTRA_IMAGE_URI);
        if (uriStr == null) {
            toast("未获取到图片");
            finish();
            return;
        }

        loadAsync(Uri.parse(uriStr));

        btnCancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        btnConfirm.setOnClickListener(v -> {
            if (saving) return;               // 防连点，避免两个保存任务并发写同一个文件
            if (cropView.getBitmap() == null) {
                toast("图片还没加载好");
                return;
            }
            Bitmap cropped = cropView.getCroppedBitmap(targetWidth());
            if (cropped == null) {
                toast("裁剪失败");
                return;
            }
            saving = true;
            saveAndGenerate(cropped);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        dismissProgress();
    }

    /** 切换裁剪方式：锁定列表比例 / 自由裁剪 */
    private void applyRatioMode() {
        cropView.setLockAspect(lockAspect);
        if (btnRatioList != null) {
            btnRatioList.setAlpha(lockAspect ? 1f : 0.45f);
            btnRatioList.setSelected(lockAspect);
        }
        if (btnRatioFree != null) {
            btnRatioFree.setAlpha(lockAspect ? 0.45f : 1f);
            btnRatioFree.setSelected(!lockAspect);
        }
    }

    /** 输出宽度：应用列表实际像素宽度，封顶 1080 */
    private int targetWidth() {
        int areaW = getSharedPreferences(BackgroundManager.PREFS, MODE_PRIVATE)
                .getInt(BackgroundManager.PREF_AREA_W, 0);
        if (areaW <= 0) {
            areaW = getResources().getDisplayMetrics().widthPixels;
        }
        return Math.min(areaW, BackgroundManager.MAX_EDGE);
    }

    private void loadAsync(Uri uri) {
        showProgress("正在加载图片…");
        worker.execute(() -> {
            Bitmap bmp = decodeWithExif(uri);
            main.post(() -> {
                dismissProgress();
                if (isFinishing() || isDestroyed()) return;
                if (bmp == null) {
                    toast("无法加载图片");
                    finish();
                    return;
                }
                cropView.setBitmap(bmp);
            });
        });
    }

    /** 采样 + EXIF 方向纠正 */
    private Bitmap decodeWithExif(Uri uri) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (Exception ignored) {
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        int maxEdge = Math.max(bounds.outWidth, bounds.outHeight);
        while (maxEdge / sample > MAX_DECODE_EDGE) {
            sample <<= 1;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap src = null;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            src = BitmapFactory.decodeStream(in, null, opts);
        } catch (Exception ignored) {
        }
        if (src == null) return null;

        Matrix m = orientationMatrix(readOrientation(uri));
        if (m.isIdentity()) return src;
        try {
            Bitmap rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            if (rotated != src) src.recycle();
            return rotated;
        } catch (Throwable t) {
            return src;
        }
    }

    private int readOrientation(Uri uri) {
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) return ExifInterface.ORIENTATION_NORMAL;
            ExifInterface exif = new ExifInterface(pfd.getFileDescriptor());
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        } catch (Throwable t) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Matrix orientationMatrix(int orientation) {
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.setScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                m.setRotate(180);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.setScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                m.setRotate(90);
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                m.setRotate(90);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                m.setRotate(-90);
                m.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                m.setRotate(270);
                break;
            default:
                break;
        }
        return m;
    }

    private void saveAndGenerate(Bitmap cropped) {
        showProgress("正在生成背景与模糊效果…");
        worker.execute(() -> {
            String error = null;
            Bitmap source = cropped;
            try {
                // 1) 写原图（尺寸已在 getCroppedBitmap 里归一化）
                if (!BackgroundManager.saveJpeg(source, BackgroundManager.getSourceFile(this), 92)) {
                    throw new IllegalStateException("保存图片失败");
                }
                // 2) 旧模糊图作废，重新预生成三档
                BackgroundManager.deleteGenerated(this);
                BackgroundManager.generateAll(this);
            } catch (Throwable t) {
                error = t.getMessage() == null ? t.toString() : t.getMessage();
            } finally {
                if (source != null && !source.isRecycled()) source.recycle();
            }

            final String err = error;
            main.post(() -> {
                dismissProgress();
                if (isFinishing() || isDestroyed()) return;
                if (err != null) {
                    saving = false;
                    toast("保存失败: " + err);
                    return;
                }
                Intent data = new Intent();
                data.putExtra(EXTRA_OUTPUT_PATH,
                        BackgroundManager.getSourceFile(this).getAbsolutePath());
                setResult(RESULT_OK, data);
                finish();
            });
        });
    }

    private void showProgress(String msg) {
        dismissProgress();
        progress = new ProgressDialog(this);
        progress.setMessage(msg);
        progress.setCancelable(false);
        progress.show();
    }

    private void dismissProgress() {
        if (progress != null && progress.isShowing()) {
            try {
                progress.dismiss();
            } catch (Exception ignored) {
            }
        }
        progress = null;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}

/**
 * 自由裁剪视图：拖动框内整体移动，拖四条边 / 四个角自由改变大小（不锁比例）。
 * 初始框会按应用列表的比例给出一个建议值，用户可随意调整。
 */
class CropView extends View {

    private Bitmap bitmap;
    private final RectF imageRect = new RectF();
    private final RectF cropRect = new RectF();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint borderPaint = new Paint();
    private final Paint guidePaint = new Paint();
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float aspect = 0.75f; // 仅用于初始框的宽 / 高建议值
    private boolean lockAspect = true;  // true = 锁定该比例，false = 自由裁剪
    private float lastX, lastY;
    private float touchSlop;   // 边角命中范围
    private float minSize;     // 裁剪框最小边长
    private float handleSize;  // 角把手尺寸

    private static final int HANDLE_NONE = -1;
    private static final int HANDLE_MOVE = 0;
    private static final int HANDLE_LEFT = 1;
    private static final int HANDLE_RIGHT = 2;
    private static final int HANDLE_TOP = 3;
    private static final int HANDLE_BOTTOM = 4;
    private static final int HANDLE_LT = 5;
    private static final int HANDLE_RT = 6;
    private static final int HANDLE_LB = 7;
    private static final int HANDLE_RB = 8;

    private int activeHandle = HANDLE_NONE;

    public CropView(android.content.Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        touchSlop = 26 * density;
        minSize = 64 * density;
        handleSize = 12 * density;

        dimPaint.setColor(Color.parseColor("#B0000000"));
        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2f);
        guidePaint.setColor(Color.parseColor("#55FFFFFF"));
        guidePaint.setStrokeWidth(1f);
        handlePaint.setColor(Color.WHITE);
        handlePaint.setStyle(Paint.Style.FILL);
    }

    /** 设置初始裁剪框的建议比例（w/h），之后用户可自由改变 */
    public void setAspect(float aspect) {
        this.aspect = aspect;
        computeRects();
        invalidate();
    }

    /** 是否锁定比例（锁定后拉伸时始终保持 w/h = aspect） */
    public void setLockAspect(boolean lock) {
        this.lockAspect = lock;
        if (lock) normalizeToAspect();
        invalidate();
    }

    /** 锁定模式下把当前框校正到目标比例（保持中心，夹在图片内） */
    private void normalizeToAspect() {
        if (imageRect.width() <= 0 || imageRect.height() <= 0) return;
        float newW, newH;
        if (cropRect.width() / cropRect.height() > aspect) {
            newH = cropRect.height();
            newW = newH * aspect;
        } else {
            newW = cropRect.width();
            newH = newW / aspect;
        }
        newW = Math.min(newW, imageRect.width());
        newH = newW / aspect;
        if (newH > imageRect.height()) {
            newH = imageRect.height();
            newW = newH * aspect;
        }
        float l = clamp(cropRect.centerX() - newW / 2f, imageRect.left, imageRect.right - newW);
        float t = clamp(cropRect.centerY() - newH / 2f, imageRect.top, imageRect.bottom - newH);
        cropRect.set(l, t, l + newW, t + newH);
    }

    public void setBitmap(Bitmap bmp) {
        this.bitmap = bmp;
        computeRects();
        invalidate();
    }

    public Bitmap getBitmap() {
        return bitmap;
    }

    private void computeRects() {
        if (bitmap == null) return;
        int vw = getWidth();
        int vh = getHeight();
        if (vw == 0 || vh == 0) return;

        // 图片在 view 中的显示区域（fitCenter）
        float scale = Math.min((float) vw / bitmap.getWidth(), (float) vh / bitmap.getHeight());
        float dw = bitmap.getWidth() * scale;
        float dh = bitmap.getHeight() * scale;
        float left = (vw - dw) / 2f;
        float top = (vh - dh) / 2f;
        imageRect.set(left, top, left + dw, top + dh);

        // 初始裁剪框：按建议比例取图片内的最大框（用户之后可自由调整）
        float cw, ch;
        if (dw / dh > aspect) {
            ch = dh;
            cw = ch * aspect;
        } else {
            cw = dw;
            ch = cw / aspect;
        }
        float cl = left + (dw - cw) / 2f;
        float ct = top + (dh - ch) / 2f;
        cropRect.set(cl, ct, cl + cw, ct + ch);
    }

    /** 把裁剪框重置为初始的建议比例 */
    public void resetCropRect() {
        computeRects();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeRects();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) return;
        canvas.drawBitmap(bitmap, null, imageRect, bitmapPaint);

        // 框外压暗
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, cropRect.top, dimPaint);
        canvas.drawRect(imageRect.left, cropRect.bottom, imageRect.right, imageRect.bottom, dimPaint);
        canvas.drawRect(imageRect.left, cropRect.top, cropRect.left, cropRect.bottom, dimPaint);
        canvas.drawRect(cropRect.right, cropRect.top, imageRect.right, cropRect.bottom, dimPaint);

        // 三分参考线
        float gw = cropRect.width() / 3f;
        float gh = cropRect.height() / 3f;
        for (int i = 1; i < 3; i++) {
            canvas.drawLine(cropRect.left + gw * i, cropRect.top,
                    cropRect.left + gw * i, cropRect.bottom, guidePaint);
            canvas.drawLine(cropRect.left, cropRect.top + gh * i,
                    cropRect.right, cropRect.top + gh * i, guidePaint);
        }

        canvas.drawRect(cropRect, borderPaint);

        // 四个角的把手，提示可自由拉伸
        float h = handleSize;
        canvas.drawRect(cropRect.left - h / 2, cropRect.top - h / 2,
                cropRect.left + h / 2, cropRect.top + h / 2, handlePaint);
        canvas.drawRect(cropRect.right - h / 2, cropRect.top - h / 2,
                cropRect.right + h / 2, cropRect.top + h / 2, handlePaint);
        canvas.drawRect(cropRect.left - h / 2, cropRect.bottom - h / 2,
                cropRect.left + h / 2, cropRect.bottom + h / 2, handlePaint);
        canvas.drawRect(cropRect.right - h / 2, cropRect.bottom - h / 2,
                cropRect.right + h / 2, cropRect.bottom + h / 2, handlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) return super.onTouchEvent(event);
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                activeHandle = hitTest(x, y);
                if (activeHandle == HANDLE_NONE) return false;
                lastX = x;
                lastY = y;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (activeHandle == HANDLE_NONE) return false;
                if (activeHandle == HANDLE_MOVE) {
                    moveCrop(x - lastX, y - lastY);
                } else if (lockAspect) {
                    resizeCropLocked(x, y);
                } else {
                    resizeCrop(x, y);
                }
                lastX = x;
                lastY = y;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activeHandle = HANDLE_NONE;
                return true;
        }
        return super.onTouchEvent(event);
    }

    /** 命中测试：先判四角，再判四边，最后判内部（移动） */
    private int hitTest(float x, float y) {
        float t = touchSlop;
        boolean nearL = Math.abs(x - cropRect.left) <= t && y >= cropRect.top - t && y <= cropRect.bottom + t;
        boolean nearR = Math.abs(x - cropRect.right) <= t && y >= cropRect.top - t && y <= cropRect.bottom + t;
        boolean nearT = Math.abs(y - cropRect.top) <= t && x >= cropRect.left - t && x <= cropRect.right + t;
        boolean nearB = Math.abs(y - cropRect.bottom) <= t && x >= cropRect.left - t && x <= cropRect.right + t;

        if (nearL && nearT) return HANDLE_LT;
        if (nearR && nearT) return HANDLE_RT;
        if (nearL && nearB) return HANDLE_LB;
        if (nearR && nearB) return HANDLE_RB;
        if (nearL) return HANDLE_LEFT;
        if (nearR) return HANDLE_RIGHT;
        if (nearT) return HANDLE_TOP;
        if (nearB) return HANDLE_BOTTOM;
        if (cropRect.contains(x, y)) return HANDLE_MOVE;
        return HANDLE_NONE;
    }

    /** 锁定比例拉伸：角拖动按对角锚定，边拖动另一轴按中心扩展 */
    private void resizeCropLocked(float x, float y) {
        boolean touchLeft = activeHandle == HANDLE_LEFT || activeHandle == HANDLE_LT || activeHandle == HANDLE_LB;
        boolean touchRight = activeHandle == HANDLE_RIGHT || activeHandle == HANDLE_RT || activeHandle == HANDLE_RB;
        boolean touchTop = activeHandle == HANDLE_TOP || activeHandle == HANDLE_LT || activeHandle == HANDLE_RT;
        boolean touchBottom = activeHandle == HANDLE_BOTTOM || activeHandle == HANDLE_LB || activeHandle == HANDLE_RB;

        float newW = cropRect.width();
        float newH = cropRect.height();
        if (touchLeft) newW = cropRect.right - x;
        else if (touchRight) newW = x - cropRect.left;
        if (touchTop) newH = cropRect.bottom - y;
        else if (touchBottom) newH = y - cropRect.top;

        // 角拖动取变化更大的一方，边拖动另一轴按比例推导
        if ((touchLeft || touchRight) && (touchTop || touchBottom)) {
            if (newW / aspect > newH) newH = newW / aspect;
            else newW = newH * aspect;
        } else if (touchLeft || touchRight) {
            newH = newW / aspect;
        } else {
            newW = newH * aspect;
        }

        // 夹到图片范围
        float maxW = Math.min(imageRect.width(), imageRect.height() * aspect);
        newW = clamp(newW, minSize, maxW);
        newH = newW / aspect;
        if (newH > imageRect.height()) {
            newH = imageRect.height();
            newW = newH * aspect;
        }
        if (newH < minSize) {
            newH = minSize;
            newW = newH * aspect;
        }

        // 定位：角拖动锚定对角，边拖动另一轴保持中心
        float l;
        if (touchLeft) l = cropRect.right - newW;
        else if (touchRight) l = cropRect.left;
        else l = cropRect.centerX() - newW / 2f;

        float t;
        if (touchTop) t = cropRect.bottom - newH;
        else if (touchBottom) t = cropRect.top;
        else t = cropRect.centerY() - newH / 2f;

        l = clamp(l, imageRect.left, imageRect.right - newW);
        t = clamp(t, imageRect.top, imageRect.bottom - newH);
        cropRect.set(l, t, l + newW, t + newH);
    }

    /** 自由拉伸：把手跟手走，最小 minSize，且不超出图片范围 */
    private void resizeCrop(float x, float y) {
        float l = cropRect.left;
        float r = cropRect.right;
        float t = cropRect.top;
        float b = cropRect.bottom;

        switch (activeHandle) {
            case HANDLE_LEFT:
            case HANDLE_LT:
            case HANDLE_LB:
                l = x;
                break;
            case HANDLE_RIGHT:
            case HANDLE_RT:
            case HANDLE_RB:
                r = x;
                break;
            default:
                break;
        }
        switch (activeHandle) {
            case HANDLE_TOP:
            case HANDLE_LT:
            case HANDLE_RT:
                t = y;
                break;
            case HANDLE_BOTTOM:
            case HANDLE_LB:
            case HANDLE_RB:
                b = y;
                break;
            default:
                break;
        }

        l = clamp(l, imageRect.left, imageRect.right - minSize);
        r = clamp(r, l + minSize, imageRect.right);
        t = clamp(t, imageRect.top, imageRect.bottom - minSize);
        b = clamp(b, t + minSize, imageRect.bottom);

        cropRect.set(l, t, r, b);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void moveCrop(float dx, float dy) {
        float maxLeft = imageRect.right - cropRect.width();
        float maxTop = imageRect.bottom - cropRect.height();
        float left = cropRect.left + dx;
        float top = cropRect.top + dy;
        left = Math.max(imageRect.left, Math.min(left, Math.max(imageRect.left, maxLeft)));
        top = Math.max(imageRect.top, Math.min(top, Math.max(imageRect.top, maxTop)));
        cropRect.offsetTo(left, top);
    }

    /** 按裁剪框取出源图区域，并按目标宽度归一化（只缩不放）。
     *  返回的 Bitmap 一定是新对象，归调用方所有，可安全 recycle。 */
    public Bitmap getCroppedBitmap(int targetW) {
        if (bitmap == null || bitmap.isRecycled()) return null;
        if (imageRect.width() <= 0 || imageRect.height() <= 0) return null;

        float sx = bitmap.getWidth() / imageRect.width();
        float sy = bitmap.getHeight() / imageRect.height();

        int left = Math.round((cropRect.left - imageRect.left) * sx);
        int top = Math.round((cropRect.top - imageRect.top) * sy);
        int width = Math.round(cropRect.width() * sx);
        int height = Math.round(cropRect.height() * sy);

        left = Math.max(0, Math.min(left, bitmap.getWidth() - 1));
        top = Math.max(0, Math.min(top, bitmap.getHeight() - 1));
        width = Math.max(1, Math.min(width, bitmap.getWidth() - left));
        height = Math.max(1, Math.min(height, bitmap.getHeight() - top));

        int outW = width;
        int outH = height;
        if (targetW > 0 && width > targetW) {
            outW = targetW;
            outH = Math.max(1, Math.round(targetW * height / (float) width));
        }

        try {
            // 用 Canvas 一次完成「裁剪 + 缩放」：
            // createBitmap()/createScaledBitmap() 在源图不可变且区域正好等于整图时会直接复用源对象，
            // 那样后续 recycle() 会回收掉 CropView 正在显示的 Bitmap，导致 "cannot use a recycled bitmap"
            Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Rect src = new Rect(left, top, left + width, top + height);
            RectF dst = new RectF(0, 0, outW, outH);
            canvas.drawBitmap(bitmap, src, dst, bitmapPaint);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }
}
