package com.quickstart.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 背景统一管理：存放、预生成模糊、读取。
 *
 * 设计原则：
 *  1. 模糊图**只在此处离线预生成**（选图后 / 切换模糊档位时），主界面运行时绝不计算模糊；
 *  2. 主界面只做「挑一个已经存在的文件 → 解码 → 显示」；
 *  3. 所有产物放在 filesDir/bg/ 下，避免被系统清理缓存后失效：
 *       bg/source.jpg       裁剪后的原图（按应用列表比例）
 *       bg/blur_light.jpg   轻微模糊
 *       bg/blur_medium.jpg  中等模糊
 *       bg/blur_heavy.jpg   强模糊（玻璃效果）
 */
public final class BackgroundManager {

    public static final String PREFS = "settings";

    public static final String PREF_IMAGE = "background_image_path";
    public static final String PREF_BLUR = "background_blur";
    /** 应用列表显示区宽高比（w/h），由 MainActivity 实测写入，裁剪界面据此固定裁剪框 */
    public static final String PREF_ASPECT = "bg_aspect_ratio";
    public static final String PREF_AREA_W = "bg_area_width";
    public static final String PREF_AREA_H = "bg_area_height";

    public static final String NONE = "none";
    public static final String LIGHT = "light";
    public static final String MEDIUM = "medium";
    public static final String HEAVY = "heavy";
    public static final String[] BLUR_LEVELS = {LIGHT, MEDIUM, HEAVY};

    /** 输出图最长边上限，控制内存与体积 */
    public static final int MAX_EDGE = 1080;

    private static final String DIR_NAME = "bg";
    private static final String SOURCE_NAME = "source.jpg";
    private static final String BLUR_PREFIX = "blur_";
    private static final String LEGACY_NAME = "bg_original.jpg";
    private static final String PREF_MIGRATED = "bg_layout_v3";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private BackgroundManager() {
    }

    // ==================== 路径 ====================

    public static File getDir(Context ctx) {
        return new File(ctx.getFilesDir(), DIR_NAME);
    }

    public static File getSourceFile(Context ctx) {
        return new File(getDir(ctx), SOURCE_NAME);
    }

    public static File getBlurredFile(Context ctx, String mode) {
        return new File(getDir(ctx), BLUR_PREFIX + mode + ".jpg");
    }

    /** 是否存在可用的背景原图 */
    public static boolean hasSource(Context ctx) {
        return getSourceFile(ctx).exists();
    }

    /**
     * 取得当前应该显示的背景文件。
     * 指定档位的模糊图尚未生成时回退到原图（绝不在调用处现算模糊）。
     */
    public static File getDisplayFile(Context ctx, String mode) {
        File src = getSourceFile(ctx);
        if (!src.exists()) return null;
        if (mode == null || NONE.equals(mode)) return src;
        File blurred = getBlurredFile(ctx, mode);
        return blurred.exists() ? blurred : src;
    }

    /** 删除全部背景产物（原图 + 各档模糊图） */
    public static void clearAll(Context ctx) {
        deleteQuietly(getDir(ctx));
        deleteQuietly(new File(ctx.getFilesDir(), LEGACY_NAME));
        deleteLegacyCache(ctx);
    }

    /** 删除已生成的模糊图（换图时使用） */
    public static void deleteGenerated(Context ctx) {
        File dir = getDir(ctx);
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().startsWith(BLUR_PREFIX)) deleteQuietly(f);
        }
    }

    // ==================== 模糊算法 ====================

    /** 模糊半径（像素）：按图短边取比例，保证不同分辨率观感一致。
     *  实际观感约为该半径的 1.1 倍（含降采样带来的平滑），调档直接改这三个系数。 */
    public static int radiusForMode(int w, int h, String mode) {
        float factor;
        switch (mode) {
            case LIGHT:  factor = 0.006f; break;  // 1080 宽 ≈ 6px：轻微柔化
            case MEDIUM: factor = 0.018f; break;  // ≈ 19px：明显模糊
            case HEAVY:  factor = 0.045f; break;  // ≈ 49px：玻璃效果
            default:     return 0;
        }
        int min = Math.min(w, h);
        return Math.max(1, Math.round(min * factor));
    }

    /**
     * 快速模糊：先按半径降采样，在小图上做 3 次盒式模糊（逼近高斯），再放大回原尺寸。
     * 复杂度与半径无关，1080p 图在毫秒级完成。
     */
    public static Bitmap blur(Bitmap src, int radius) {
        if (src == null || radius <= 0) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w < 2 || h < 2) return src;

        int minEdge = Math.min(w, h);
        int down = Math.max(1, Math.round(radius / 3f));
        // 工作图至少保留 4px，避免降得过小
        down = Math.min(down, Math.max(1, minEdge / 4));

        int sw = Math.max(2, Math.round(w / (float) down));
        int sh = Math.max(2, Math.round(h / (float) down));

        // 注意：源图不可变且目标尺寸相同时，createScaledBitmap 可能直接返回源对象，
        // 此时不能 recycle，否则会把调用方的 Bitmap 一起回收掉
        Bitmap small = Bitmap.createScaledBitmap(src, sw, sh, true);
        if (small == null) return src;
        boolean ownsSmall = (small != src);

        int r = Math.max(1, Math.round(radius / (float) down));
        int[] px = new int[sw * sh];
        small.getPixels(px, 0, sw, 0, 0, sw, sh);
        if (ownsSmall && !small.isRecycled()) small.recycle();

        boxBlur(px, sw, sh, r);
        boxBlur(px, sw, sh, r);
        boxBlur(px, sw, sh, r);

        Bitmap outSmall = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        outSmall.setPixels(px, 0, sw, 0, 0, sw, sh);
        Bitmap result = Bitmap.createScaledBitmap(outSmall, w, h, true);
        outSmall.recycle();
        return result != null ? result : src;
    }

    /** 分离式盒式模糊（水平 + 垂直各一趟），边缘按夹取处理 */
    private static void boxBlur(int[] px, int w, int h, int r) {
        if (r <= 0) return;
        int[] tmp = new int[px.length];
        int win = r * 2 + 1;

        // 水平
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int dx = -r; dx <= r; dx++) {
                int c = px[row + clamp(dx, 0, w - 1)];
                sa += (c >>> 24);
                sr += (c >> 16) & 0xff;
                sg += (c >> 8) & 0xff;
                sb += c & 0xff;
            }
            for (int x = 0; x < w; x++) {
                tmp[row + x] = (sa / win) << 24 | (sr / win) << 16 | (sg / win) << 8 | (sb / win);
                int co = px[row + clamp(x - r, 0, w - 1)];
                int ci = px[row + clamp(x + r + 1, 0, w - 1)];
                sa += (ci >>> 24) - (co >>> 24);
                sr += ((ci >> 16) & 0xff) - ((co >> 16) & 0xff);
                sg += ((ci >> 8) & 0xff) - ((co >> 8) & 0xff);
                sb += (ci & 0xff) - (co & 0xff);
            }
        }

        // 垂直
        for (int x = 0; x < w; x++) {
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int dy = -r; dy <= r; dy++) {
                int c = tmp[clamp(dy, 0, h - 1) * w + x];
                sa += (c >>> 24);
                sr += (c >> 16) & 0xff;
                sg += (c >> 8) & 0xff;
                sb += c & 0xff;
            }
            for (int y = 0; y < h; y++) {
                px[y * w + x] = (sa / win) << 24 | (sr / win) << 16 | (sg / win) << 8 | (sb / win);
                int co = tmp[clamp(y - r, 0, h - 1) * w + x];
                int ci = tmp[clamp(y + r + 1, 0, h - 1) * w + x];
                sa += (ci >>> 24) - (co >>> 24);
                sr += ((ci >> 16) & 0xff) - ((co >> 16) & 0xff);
                sg += ((ci >> 8) & 0xff) - ((co >> 8) & 0xff);
                sb += (ci & 0xff) - (co & 0xff);
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ==================== 预生成（同步，必须在后台线程调用） ====================

    /** 由原图生成全部模糊档位；返回是否全部成功 */
    public static boolean generateAll(Context ctx) {
        File srcFile = getSourceFile(ctx);
        if (!srcFile.exists()) return false;
        Bitmap base = BitmapFactory.decodeFile(srcFile.getAbsolutePath());
        if (base == null) return false;

        boolean ok = true;
        for (String mode : BLUR_LEVELS) {
            ok &= generateOne(ctx, base, mode);
        }
        base.recycle();
        return ok;
    }

    /** 生成指定档位；已存在则跳过 */
    public static boolean ensureBlurred(Context ctx, String mode) {
        if (mode == null || NONE.equals(mode)) return true;
        File out = getBlurredFile(ctx, mode);
        if (out.exists()) return true;

        File srcFile = getSourceFile(ctx);
        if (!srcFile.exists()) return false;
        Bitmap base = BitmapFactory.decodeFile(srcFile.getAbsolutePath());
        if (base == null) return false;
        boolean ok = generateOne(ctx, base, mode);
        base.recycle();
        return ok;
    }

    private static boolean generateOne(Context ctx, Bitmap base, String mode) {
        int radius = radiusForMode(base.getWidth(), base.getHeight(), mode);
        Bitmap blurred = blur(base, radius);
        if (blurred == null) return false;
        boolean ok = saveJpeg(blurred, getBlurredFile(ctx, mode), 88);
        if (blurred != base) blurred.recycle();
        return ok;
    }

    public static boolean saveJpeg(Bitmap bmp, File out, int quality) {
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        OutputStream os = null;
        try {
            os = new FileOutputStream(out);
            boolean ok = bmp.compress(Bitmap.CompressFormat.JPEG, quality, os);
            os.flush();
            return ok;
        } catch (Exception e) {
            return false;
        } finally {
            closeQuietly(os);
        }
    }

    // ==================== 预生成（异步） ====================

    public interface GenerateCallback {
        void onFinished(boolean success, String error);
    }

    /** 后台生成全部模糊档位，回调回到主线程 */
    public static void generateAllAsync(Context ctx, GenerateCallback cb) {
        submit(ctx, app -> {
            if (!generateAll(app)) throw new IllegalStateException("生成模糊图失败");
        }, cb);
    }

    /** 后台确保指定档位存在（不存在才生成），回调回到主线程 */
    public static void ensureBlurAsync(Context ctx, String mode, GenerateCallback cb) {
        submit(ctx, app -> {
            if (!ensureBlurred(app, mode)) throw new IllegalStateException("生成模糊图失败");
        }, cb);
    }

    private interface Job {
        void run(Context ctx) throws Exception;
    }

    private static void submit(Context ctx, Job job, GenerateCallback cb) {
        Context app = ctx.getApplicationContext();
        WORKER.execute(() -> {
            String err = null;
            try {
                job.run(app);
            } catch (Throwable t) {
                err = t.getMessage() == null ? t.toString() : t.getMessage();
            }
            final String finalErr = err;
            if (cb != null) {
                MAIN.post(() -> cb.onFinished(finalErr == null, finalErr));
            }
        });
    }

    // ==================== 解码辅助 ====================

    /** 按目标尺寸采样解码，避免整图进内存 */
    public static Bitmap decodeSampled(File file, int reqW, int reqH) {
        if (file == null || !file.exists()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (reqW > 0 && reqH > 0) {
            while (w / sample > reqW * 2 && h / sample > reqH * 2) {
                sample <<= 1;
            }
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        // 模糊背景多为平滑渐变，用 ARGB_8888 避免 RGB_565 的色带
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    // ==================== 旧版本迁移 ====================

    /**
     * 一次性迁移：把旧版 filesDir/bg_original.jpg 挪到新版目录，并清掉旧的运行时缓存。
     * 迁移后如缺少模糊图，返回 true 提示调用方后台补生成。
     */
    public static boolean prepare(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (sp.getBoolean(PREF_MIGRATED, false)) return false;
        sp.edit().putBoolean(PREF_MIGRATED, true).apply();

        File dir = getDir(ctx);
        if (!dir.exists()) dir.mkdirs();
        File src = getSourceFile(ctx);
        File legacy = new File(ctx.getFilesDir(), LEGACY_NAME);
        if (!src.exists() && legacy.exists()) {
            legacy.renameTo(src);
        }
        deleteLegacyCache(ctx);

        if (src.exists()) {
            sp.edit().putString(PREF_IMAGE, src.getAbsolutePath()).apply();
            // 每次升级版本号都会作废旧的模糊图，用新参数重生成
            deleteGenerated(ctx);
            return true;
        }
        return false;
    }

    private static void deleteLegacyCache(Context ctx) {
        try {
            File cacheDir = ctx.getCacheDir();
            File[] files = cacheDir == null ? null : cacheDir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (f.getName().startsWith("bg_cached_")) deleteQuietly(f);
            }
        } catch (Exception ignored) {
        }
    }

    private static void deleteQuietly(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteQuietly(c);
            }
        }
        f.delete();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }
}
