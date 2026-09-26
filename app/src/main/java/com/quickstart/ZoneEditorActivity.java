package com.quickstart;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.quickstart.model.AppEntry;
import com.quickstart.util.BackgroundManager;
import com.quickstart.util.FastCache;
import com.quickstart.util.IconCache;
import com.quickstart.util.ZoneStore;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 图标避让区域编辑器。
 *
 * 画布按主界面「背景显示区」的宽高比（BackgroundManager.PREF_ASPECT）固定，
 * 背景图以与主界面一致的 centerCrop 方式铺进去——画布上看到什么，
 * 主界面默认（面板收起）状态下就是什么样，画在哪图标就避开哪。
 *
 * 操作方式（刻意做粗略，不做画笔级采集）：
 *  - 空白处点按：放一个默认大小的当前形状区域；
 *  - 空白处拖动：按拖出的范围画圆形（圆心=起点，半径=拖动距离）或方形；
 *  - 按住已有区域拖动：移动它；
 *  - 点选区域后：底部滑杆调整大小，「删除所选 / 清空」删除；
 *  - 保存后主界面图标网格自动绕开所有区域。
 *
 * 画布上还会铺一层「应用网格预览」（与主界面同源缓存、同隐藏过滤、同排序，
 * 按主界面同款网格与列数排布），并实时应用避让运算——拖动/缩放区域的瞬间
 * 图标即时重排，预览里看到的就是保存后主界面的样子。
 */
public class ZoneEditorActivity extends AppCompatActivity {

    private static final float DEFAULT_ASPECT = 0.75f;
    /** 点按（几乎没拖动）时判定为「放默认大小区域」的位移阈值（px） */
    private static final float TAP_SLOP = 12f;
    /** 抓取已有区域的额外判定余量（px），方便手指点中细边 */
    private static final float GRAB_SLOP = 14f;
    /** 滑杆基准：进度 50 = 原尺寸 */
    private static final float SEEKBAR_NEUTRAL_PROGRESS = 50f;

    private ZoneCanvas canvas;
    private SeekBar sizeSeekBar;
    private View btnCircle, btnRect, btnDelete;
    private int shape = ZoneStore.Zone.TYPE_CIRCLE;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 选中区域当时的尺寸（滑杆以此为基准缩放），选中变化时重置 */
    private float baseSize = -1f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_zone_editor);

        if (!BackgroundManager.hasSource(this)) {
            Toast.makeText(this, "请先在「图片背景」中选择背景图片", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        canvas = new ZoneCanvas(this);
        sizeSeekBar = findViewById(R.id.sb_zone_size);
        btnCircle = findViewById(R.id.btn_shape_circle);
        btnRect = findViewById(R.id.btn_shape_rect);
        btnDelete = findViewById(R.id.btn_zone_delete);
        View btnClear = findViewById(R.id.btn_zone_clear);
        View btnSave = findViewById(R.id.btn_zones_save);
        View btnBack = findViewById(R.id.btn_zones_back);

        // 画布尺寸：按背景显示区宽高比等比放进可用空间
        FrameLayout container = findViewById(R.id.zone_canvas_container);
        int w = measureCanvasWidth();
        int h = Math.round(w / canvasAspect());
        container.addView(canvas, new FrameLayout.LayoutParams(w, h, Gravity.CENTER));

        canvas.setZones(ZoneStore.load(this));

        findViewById(R.id.zone_canvas_loading).setVisibility(View.VISIBLE);
        loadBackgroundBitmap();
        loadPreviewApps(w, h);

        btnCircle.setOnClickListener(v -> setShape(ZoneStore.Zone.TYPE_CIRCLE));
        btnRect.setOnClickListener(v -> setShape(ZoneStore.Zone.TYPE_RECT));
        setShape(shape);

        btnDelete.setOnClickListener(v -> {
            if (canvas.deleteSelected()) {
                onSelectionChanged();
            }
        });
        btnClear.setOnClickListener(v -> {
            canvas.clearAll();
            onSelectionChanged();
        });
        btnBack.setOnClickListener(v -> finish());
        btnSave.setOnClickListener(v -> {
            ZoneStore.save(this, canvas.getZones());
            Toast.makeText(this, "避让区域已保存，返回主界面即生效", Toast.LENGTH_SHORT).show();
            finish();
        });

        sizeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || baseSize <= 0) return;
                // 滑杆中点(50) = 不缩放，两端为 0.25x ~ 4x（指数曲线，手感均匀）
                float factor = (float) Math.pow(4, progress / 50f - 1);
                canvas.scaleSelected(baseSize * factor);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        onSelectionChanged();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }

    /** 加载当前显示的背景图（跟随模糊档位设置，所见即所得），完成后铺到画布 */
    private void loadBackgroundBitmap() {
        final String mode = getSharedPreferences(BackgroundManager.PREFS, MODE_PRIVATE)
                .getString(BackgroundManager.PREF_BLUR, BackgroundManager.NONE);
        worker.execute(() -> {
            File file = BackgroundManager.getDisplayFile(ZoneEditorActivity.this, mode);
            final Bitmap bmp = BackgroundManager.decodeSampled(file, 1080, 1920);
            main.post(() -> {
                View loading = findViewById(R.id.zone_canvas_loading);
                if (loading != null) loading.setVisibility(View.GONE);
                if (!isDestroyed() && canvas != null) canvas.setBitmap(bmp);
            });
        });
    }

    /**
     * 加载应用网格预览：与主界面同源的缓存列表（同隐藏过滤、同排序），
     * 图标按主界面同款网格铺在背景上、避让区域半透明盖在上面——
     * 划区域时能直接看到哪些图标和文字会被挪走。
     * 画布只装得下主界面第一屏，因此只解码前几行应用的图标。
     */
    private void loadPreviewApps(int canvasW, int canvasH) {
        worker.execute(() -> {
            FastCache.CacheResult res = FastCache.load(this);
            if (res == null || res.apps.isEmpty()) return;

            // 隐藏过滤（与主界面一致）。拷贝成新列表，保持 res.apps 与 iconBytes 的对齐关系
            java.util.Set<String> hidden = getSharedPreferences("settings", MODE_PRIVATE)
                    .getStringSet("hidden_apps", new java.util.HashSet<>());
            List<AppEntry> shown = new ArrayList<>();
            for (AppEntry e : res.apps) {
                if (!hidden.contains(e.packageName)) shown.add(e);
            }
            sortPreviewApps(shown, res.installTimes);

            // 与主界面同款网格参数（列数读取逻辑必须与 MainActivity.getColumnCount 一致）
            int columns = MainActivity.readColumnCountPref(this);
            float density = getResources().getDisplayMetrics().density;
            float scale = previewScale(canvasW);
            float cellW = canvasW / (float) columns;
            float cellH = cellW * 1.3f;
            float topInset = 28f * density * scale;
            // 首屏行数 + 一行余量；画布底部 T9 条占位不计
            int rows = Math.max(1, (int) ((canvasH - topInset) / cellH)) + 1;
            int need = Math.min(shown.size(), rows * columns + columns);

            // 只解码需要的图标（串行即可，每张约 1-3ms）
            java.util.Map<String, byte[]> iconByPkg = new java.util.HashMap<>();
            for (int i = 0; i < res.apps.size(); i++) {
                byte[] bytes = res.iconBytes.get(i);
                if (bytes != null) iconByPkg.putIfAbsent(res.apps.get(i).packageName, bytes);
            }
            android.content.Context appCtx = getApplicationContext();
            for (int i = 0; i < need; i++) {
                AppEntry e = shown.get(i);
                byte[] bytes = iconByPkg.get(e.packageName);
                if (bytes == null) continue;
                try {
                    Bitmap bmp = IconCache.decodeScaled(bytes);
                    if (bmp != null) e.icon = new android.graphics.drawable.BitmapDrawable(appCtx.getResources(), bmp);
                } catch (Throwable ignored) {
                }
            }

            main.post(() -> {
                if (!isDestroyed() && canvas != null) canvas.setPreviewApps(shown, columns, scale);
            });
        });
    }

    /** 预览相对主界面的缩放：画布宽 / 背景显示区实际宽（缺省退回屏幕宽） */
    private float previewScale(int canvasW) {
        SharedPreferences bsp = getSharedPreferences(BackgroundManager.PREFS, MODE_PRIVATE);
        int areaW = bsp.getInt(BackgroundManager.PREF_AREA_W, 0);
        float realW = areaW > 0 ? areaW : getResources().getDisplayMetrics().widthPixels;
        return realW > 0 ? canvasW / (float) realW : 1f;
    }

    /** 与主界面 sortAllApps 相同的排序语义（数据全部来自 SP + FastCache v4 快照，零 binder） */
    private void sortPreviewApps(List<AppEntry> list, java.util.Map<String, Long> installTimes) {
        SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        SharedPreferences countSp = getSharedPreferences("app_launch_count", MODE_PRIVATE);
        SharedPreferences timeSp = getSharedPreferences("app_launch_time", MODE_PRIVATE);
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        java.util.Map<String, Long> lasts = new java.util.HashMap<>();
        for (AppEntry e : list) {
            counts.put(e.packageName, countSp.getInt(e.packageName, 0));
            lasts.put(e.packageName, timeSp.getLong(e.packageName, 0L));
        }
        String mode = sp.getString("sort_mode", "智能排序");
        switch (mode) {
            case "字母顺序":
                java.util.Collections.sort(list, (a, b) -> a.label.compareToIgnoreCase(b.label));
                break;
            case "最近安装":
                java.util.Collections.sort(list, (a, b) -> Long.compare(
                        installTimes.getOrDefault(b.packageName, 0L),
                        installTimes.getOrDefault(a.packageName, 0L)));
                break;
            case "使用频率":
                java.util.Collections.sort(list, (a, b) -> Integer.compare(
                        counts.getOrDefault(b.packageName, 0),
                        counts.getOrDefault(a.packageName, 0)));
                break;
            default: // 智能排序：启动过的按频率排前，未启动的按字母
                java.util.Collections.sort(list, (a, b) -> {
                    int ca = counts.getOrDefault(a.packageName, 0);
                    int cb = counts.getOrDefault(b.packageName, 0);
                    if (ca > 0 && cb > 0) return Integer.compare(cb, ca);
                    if (ca > 0) return -1;
                    if (cb > 0) return 1;
                    return a.label.compareToIgnoreCase(b.label);
                });
                break;
        }
    }

    private void setShape(int type) {
        shape = type;
        btnCircle.setSelected(type == ZoneStore.Zone.TYPE_CIRCLE);
        btnRect.setSelected(type == ZoneStore.Zone.TYPE_RECT);
        btnCircle.setAlpha(type == ZoneStore.Zone.TYPE_CIRCLE ? 1f : 0.5f);
        btnRect.setAlpha(type == ZoneStore.Zone.TYPE_RECT ? 1f : 0.5f);
        canvas.setNewShape(type);
    }

    /** 选中状态变化：同步滑杆基准与删除按钮可用性 */
    private void onSelectionChanged() {
        ZoneStore.Zone sel = canvas != null ? canvas.getSelected() : null;
        if (sel != null) {
            baseSize = sel.w; // 圆=半径，方=宽（缩放时高按比例跟随）
            sizeSeekBar.setProgress((int) SEEKBAR_NEUTRAL_PROGRESS);
        } else {
            baseSize = -1f;
        }
        btnDelete.setEnabled(sel != null);
        btnDelete.setAlpha(sel != null ? 1f : 0.4f);
    }

    /** 画布宽度：不超过屏幕宽，也不超过可用高度对应的宽度 */
    private int measureCanvasWidth() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float aspect = canvasAspect();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;
        int maxH = Math.round(screenH * 0.55f);
        int w = Math.min(screenW, Math.round(maxH * aspect));
        return Math.max(200, w);
    }

    private float canvasAspect() {
        android.content.SharedPreferences sp =
                getSharedPreferences(BackgroundManager.PREFS, MODE_PRIVATE);
        float aspect = sp.getFloat(BackgroundManager.PREF_ASPECT, 0f);
        if (!(aspect > 0.1f && aspect < 10f)) {
            int areaW = sp.getInt(BackgroundManager.PREF_AREA_W, 0);
            int areaH = sp.getInt(BackgroundManager.PREF_AREA_H, 0);
            aspect = (areaW > 0 && areaH > 0) ? areaW / (float) areaH : DEFAULT_ASPECT;
        }
        return aspect;
    }

    // ==================== 画布 View ====================

    /** 背景图 + 避让区域叠加层，处理绘制与手势 */
    private final class ZoneCanvas extends View {

        private Bitmap bmp;
        /** 背景图 centerCrop 绘制参数 */
        private final Matrix drawMatrix = new Matrix();

        private final List<ZoneStore.Zone> zones = new ArrayList<>();
        private int selected = -1;
        private int newShape = ZoneStore.Zone.TYPE_CIRCLE;

        /**
         * 应用网格预览：与主界面同源缓存、同隐藏过滤、同排序的列表，按主界面同款
         * 网格与列数铺在背景上。绘制时实时应用避让（hitAnyZone 跳格），随区域编辑即时重排。
         */
        private final List<AppEntry> previewApps = new ArrayList<>();
        private int previewColumns = 3;
        /** 预览相对主界面的缩放（画布宽 / 背景显示区实际宽） */
        private float previewScale = 1f;
        private final android.text.TextPaint previewTextPaint =
                new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);

        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private static final int MODE_IDLE = 0;
        private static final int MODE_CREATE = 1;
        private static final int MODE_MOVE = 2;
        private int mode = MODE_IDLE;
        private float downX, downY, lastX, lastY;
        /** 新建区域在 zones 里的下标（拖动过程中实时改尺寸），未在新建时为 -1 */
        private int creatingIndex = -1;

        ZoneCanvas(android.content.Context context) {
            super(context);
            fillPaint.setStyle(Paint.Style.FILL);
            // 半透明红：盖在预览图标上仍能看清底下内容
            fillPaint.setColor(0x30FF5252);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
            strokePaint.setColor(0xFFFF8A80);
            selStrokePaint.setStyle(Paint.Style.STROKE);
            selStrokePaint.setStrokeWidth(3f * getResources().getDisplayMetrics().density);
            selStrokePaint.setColor(Color.WHITE);
            selStrokePaint.setPathEffect(new DashPathEffect(new float[]{10f, 6f}, 0));
            previewTextPaint.setTextAlign(Paint.Align.CENTER);
            previewTextPaint.setColor(0xE6FFFFFF);
            previewTextPaint.setShadowLayer(3f, 0f, 1f, 0x66000000);
            setFocusable(true);
            setClickable(true);
        }

        /** 设置网格预览数据（后台线程加载完图标后主线程回调） */
        void setPreviewApps(List<AppEntry> apps, int columns, float scale) {
            previewApps.clear();
            if (apps != null) previewApps.addAll(apps);
            previewColumns = Math.max(1, columns);
            previewScale = scale > 0 ? scale : 1f;
            invalidate();
        }

        void setBitmap(Bitmap bmp) {
            this.bmp = bmp;
            updateDrawMatrix();
            invalidate();
        }

        void setZones(List<ZoneStore.Zone> list) {
            zones.clear();
            if (list != null) {
                for (ZoneStore.Zone z : list) {
                    if (zones.size() >= ZoneStore.MAX_ZONES) break;
                    zones.add(z.copy());
                }
            }
            selected = -1;
            invalidate();
        }

        List<ZoneStore.Zone> getZones() {
            return zones;
        }

        void setNewShape(int type) {
            newShape = type;
        }

        ZoneStore.Zone getSelected() {
            return selected >= 0 && selected < zones.size() ? zones.get(selected) : null;
        }

        boolean deleteSelected() {
            if (selected < 0 || selected >= zones.size()) return false;
            zones.remove(selected);
            selected = -1;
            invalidate();
            return true;
        }

        void clearAll() {
            zones.clear();
            selected = -1;
            invalidate();
        }

        /** 按目标尺寸缩放选中区域（圆=半径；方=宽高，等比） */
        void scaleSelected(float targetSize) {
            ZoneStore.Zone z = getSelected();
            if (z == null) return;
            if (z.type == ZoneStore.Zone.TYPE_CIRCLE) {
                z.w = clampNorm(targetSize, 0.01f, 0.45f);
            } else {
                float ratio = z.h > 0.001f ? z.h / z.w : canvasAspect();
                z.w = clampNorm(targetSize, 0.02f, 1f);
                z.h = clampNorm(z.w * ratio, 0.02f, 1f);
                z.x = clampNorm(z.x, 0f, 1f - z.w);
                z.y = clampNorm(z.y, 0f, 1f - z.h);
            }
            invalidate();
        }

        private void updateDrawMatrix() {
            int vw = getWidth(), vh = getHeight();
            if (bmp == null || vw <= 0 || vh <= 0) return;
            float scale = Math.max(vw / (float) bmp.getWidth(), vh / (float) bmp.getHeight());
            drawMatrix.setScale(scale, scale);
            float dx = (vw - bmp.getWidth() * scale) / 2f;
            float dy = (vh - bmp.getHeight() * scale) / 2f;
            drawMatrix.postTranslate(dx, dy);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            updateDrawMatrix();
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            super.onDraw(c);
            if (bmp != null) {
                c.drawBitmap(bmp, drawMatrix, null);
            } else {
                c.drawColor(0xFF262B31);
            }
            drawPreview(c);
            for (int i = 0; i < zones.size(); i++) {
                ZoneStore.Zone z = zones.get(i);
                boolean sel = i == selected;
                if (z.type == ZoneStore.Zone.TYPE_CIRCLE) {
                    float cx = z.x * getWidth(), cy = z.y * getHeight(), r = z.w * getWidth();
                    c.drawCircle(cx, cy, r, fillPaint);
                    c.drawCircle(cx, cy, r, sel ? selStrokePaint : strokePaint);
                } else {
                    float l = z.x * getWidth(), t = z.y * getHeight();
                    float r = l + z.w * getWidth(), b = t + z.h * getHeight();
                    c.drawRect(l, t, r, b, fillPaint);
                    c.drawRect(l, t, r, b, sel ? selStrokePaint : strokePaint);
                }
            }
        }

        /**
         * 按主界面同款网格铺图标和文字，并实时应用避让：与 AvoidGridLayoutManager.buildFreeCells
         * 同一套跳格逻辑，随区域编辑（新建/拖动/缩放/删除）即时重排——
         * 预览里看到的就是保存后主界面将要出现的排布。
         */
        private void drawPreview(android.graphics.Canvas c) {
            if (previewApps.isEmpty() || getWidth() <= 0 || getHeight() <= 0) return;
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            float cellW = getWidth() / (float) previewColumns;
            float cellH = cellW * 1.3f;
            float iconSize = cellW * 0.65f;
            float padV = 6f * dm.density * previewScale;      // item_app 纵向内边距
            float topInset = 28f * dm.density * previewScale; // 主界面顶部拉手让位
            float textSp;
            switch (previewColumns) { // 与 AppListAdapter.updateItemSquareSize 一致
                case 2: textSp = 14f; break;
                case 4: textSp = 11f; break;
                case 5: textSp = 10f; break;
                default: textSp = 12f; break;
            }
            float textPx = textSp * dm.scaledDensity * previewScale;
            previewTextPaint.setTextSize(textPx);

            int appIndex = 0;
            for (int row = 0; appIndex < previewApps.size(); row++) {
                float cellTop = topInset + row * cellH;
                if (cellTop >= getHeight()) break;
                for (int col = 0; col < previewColumns && appIndex < previewApps.size(); col++) {
                    float cellLeft = col * cellW;
                    if (hitAnyZone(cellLeft, cellTop, cellW, cellH)) continue; // 实时避让
                    drawAppCell(c, previewApps.get(appIndex), cellLeft, cellTop,
                            cellW, cellH, iconSize, padV, textPx, dm.density);
                    appIndex++;
                }
            }
        }

        /** 画一个应用格子（图标 + 名称），与 item_app.xml 的排布一致 */
        private void drawAppCell(android.graphics.Canvas c, AppEntry e,
                                 float cellLeft, float cellTop,
                                 float cellW, float cellH, float iconSize,
                                 float padV, float textPx, float density) {
            float cx = cellLeft + cellW / 2f;
            Drawable ic = e.icon;
            if (ic != null) {
                int l = Math.round(cx - iconSize / 2f);
                int t = Math.round(cellTop + padV);
                ic.setBounds(l, t, Math.round(l + iconSize), Math.round(t + iconSize));
                ic.draw(c);
            }
            if (textPx > 0) {
                float textTop = cellTop + padV + iconSize + 4f * density * previewScale;
                CharSequence ell = android.text.TextUtils.ellipsize(e.label, previewTextPaint,
                        cellW - 8f * density * previewScale, android.text.TextUtils.TruncateAt.END);
                c.drawText(ell.toString(), cx, textTop + textPx * 0.85f, previewTextPaint);
            }
        }

        /** 与 AvoidGridLayoutManager.hitAnyZone 相同的判定：格子与任一避让区域相交即跳过 */
        private boolean hitAnyZone(float left, float top, float cw, float ch) {
            for (int i = 0; i < zones.size(); i++) {
                ZoneStore.Zone z = zones.get(i);
                if (z.type == ZoneStore.Zone.TYPE_CIRCLE) {
                    float cx = z.x * getWidth(), cy = z.y * getHeight(), r = z.w * getWidth();
                    float nx = Math.max(left, Math.min(cx, left + cw));
                    float ny = Math.max(top, Math.min(cy, top + ch));
                    float dx = cx - nx, dy = cy - ny;
                    if (dx * dx + dy * dy <= r * r) return true;
                } else {
                    float l = z.x * getWidth(), t = z.y * getHeight();
                    float rr = l + z.w * getWidth(), bb = t + z.h * getHeight();
                    if (left + cw > l && left < rr && top + ch > t && top < bb) return true;
                }
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX(), y = event.getY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = lastX = x;
                    downY = lastY = y;
                    creatingIndex = -1;
                    int hit = hitZone(x, y);
                    if (hit >= 0) {
                        selected = hit;
                        mode = MODE_MOVE;
                        onSelectionChanged();
                    } else {
                        mode = MODE_CREATE;
                    }
                    invalidate();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (mode == MODE_MOVE) {
                        moveSelected(x - lastX, y - lastY);
                    } else if (mode == MODE_CREATE) {
                        if (creatingIndex < 0) {
                            creatingIndex = beginCreate();
                        }
                        if (creatingIndex >= 0) {
                            shapeCreating(x, y);
                        }
                    }
                    lastX = x;
                    lastY = y;
                    invalidate();
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mode == MODE_CREATE) {
                        boolean moved = Math.abs(x - downX) > TAP_SLOP || Math.abs(y - downY) > TAP_SLOP;
                        if (creatingIndex < 0) {
                            creatingIndex = beginCreate();
                            if (creatingIndex >= 0 && !moved) {
                                shapeDefault(downX, downY); // 点按 = 放一个默认大小
                            }
                        } else if (!moved) {
                            zones.remove(creatingIndex); // 拖出又落回原点：当作误触撤销
                            creatingIndex = -1;
                        }
                        if (creatingIndex >= 0) {
                            selected = creatingIndex;
                            onSelectionChanged();
                        }
                    }
                    mode = MODE_IDLE;
                    creatingIndex = -1;
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(event);
        }

        /** 命中检测：从最上层区域往下找（含抓取余量） */
        private int hitZone(float x, float y) {
            float slop = GRAB_SLOP;
            for (int i = zones.size() - 1; i >= 0; i--) {
                ZoneStore.Zone z = zones.get(i);
                if (z.type == ZoneStore.Zone.TYPE_CIRCLE) {
                    float cx = z.x * getWidth(), cy = z.y * getHeight();
                    float r = z.w * getWidth() + slop;
                    float dx = x - cx, dy = y - cy;
                    if (dx * dx + dy * dy <= r * r) return i;
                } else {
                    float l = z.x * getWidth() - slop, t = z.y * getHeight() - slop;
                    float r = (z.x + z.w) * getWidth() + slop, b = (z.y + z.h) * getHeight() + slop;
                    if (x >= l && x <= r && y >= t && y <= b) return i;
                }
            }
            return -1;
        }

        private void moveSelected(float dx, float dy) {
            ZoneStore.Zone z = getSelected();
            if (z == null) return;
            z.x = clampNorm(z.x + dx / getWidth(), 0f, 1f);
            z.y = clampNorm(z.y + dy / getHeight(), 0f, 1f);
        }

        /** 开始新建一个区域；已达上限返回 -1 */
        private int beginCreate() {
            if (zones.size() >= ZoneStore.MAX_ZONES) {
                Toast.makeText(getContext(), "最多 " + ZoneStore.MAX_ZONES + " 个区域", Toast.LENGTH_SHORT).show();
                return -1;
            }
            zones.add(new ZoneStore.Zone(newShape, downX / getWidth(), downY / getHeight(), 0.001f, 0.001f));
            return zones.size() - 1;
        }

        /** 拖动新建：圆 = 圆心在起点、半径为拖动距离；方 = 拖出外接矩形 */
        private void shapeCreating(float x, float y) {
            ZoneStore.Zone z = zones.get(creatingIndex);
            if (z.type == ZoneStore.Zone.TYPE_CIRCLE) {
                z.x = downX / getWidth();
                z.y = downY / getHeight();
                float r = (float) Math.hypot(x - downX, y - downY) / getWidth();
                z.w = clampNorm(r, 0.03f, 0.45f);
                z.h = 0;
            } else {
                float l = Math.min(downX, x) / getWidth();
                float t = Math.min(downY, y) / getHeight();
                z.x = clampNorm(l, 0f, 1f);
                z.y = clampNorm(t, 0f, 1f);
                z.w = clampNorm(Math.abs(x - downX) / getWidth(), 0.03f, 1f - z.x);
                z.h = clampNorm(Math.abs(y - downY) / getHeight(), 0.03f, 1f - z.y);
            }
        }

        /** 点按新建：默认大小（圆形半径 11% 宽；方形约 24% 宽、屏幕等比高） */
        private void shapeDefault(float x, float y) {
            ZoneStore.Zone z = zones.get(creatingIndex);
            if (z.type == ZoneStore.Zone.TYPE_CIRCLE) {
                z.x = x / getWidth();
                z.y = y / getHeight();
                z.w = 0.11f;
                z.h = 0;
            } else {
                float w = 0.24f;
                float h = Math.min(0.5f, w * canvasAspect());
                z.x = clampNorm(x / getWidth() - w / 2, 0f, 1f - w);
                z.y = clampNorm(y / getHeight() - h / 2, 0f, 1f - h);
                z.w = w;
                z.h = h;
            }
        }

        private float clampNorm(float v, float lo, float hi) {
            if (hi < lo) hi = lo;
            return v < lo ? lo : (v > hi ? hi : v);
        }
    }
}
