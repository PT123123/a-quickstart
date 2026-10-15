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
 * 背景统一管理：壁纸库（归档）、预生成模糊、读取。
 *
 * 设计原则：
 *  1. **选过的壁纸一律留档**：每次选图裁剪后存进 filesDir/wallpapers/，文件名即 id
 *     （毫秒时间戳，天然按时间排序），用户可在设置里一键切回任意一张，也能手动删除；
 *  2. 当前壁纸 id 记在 settings 的 `bg_wallpaper_id`，为空 = 纯色背景；
 *  3. 模糊图**只在此处离线预生成**（选图后 / 切换模糊档位时），主界面运行时绝不计算模糊；
 *  4. 模糊图**只为当前壁纸缓存**（filesDir/bg/blur_*.jpg），换壁纸时作废重生成——
 *     每张都存 3 份要占 4 倍空间，而模糊本身是毫秒级的，没必要；
 *  5. 所有产物放在 filesDir 下，避免被系统清理缓存后失效。
 *
 * 目录布局：
 *   filesDir/wallpapers/&lt;id&gt;.jpg 壁纸库：裁剪后的原图（永久保留，除非手动删）
 *   filesDir/wallpapers/&lt;id&gt;.thumb.jpg  列表缩略图（惰性生成）
 *   filesDir/bg/source.jpg             旧版单张背景（仅迁移期读取）
 *   filesDir/bg/blur_*.jpg             当前壁纸的各档模糊图
 */
public final class BackgroundManager {

    public static final String PREFS = "settings";

    public static final String PREF_IMAGE = "background_image_path";
    public static final String PREF_BLUR = "background_blur";
    /** 当前壁纸库条目 id；为空表示纯色背景 */
    public static final String PREF_WALLPAPER_ID = "bg_wallpaper_id";
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

    /** 壁纸库容量上限：超出后删最旧的（永不删当前正在用的那张） */
    public static final int MAX_WALLPAPERS = 60;

    private static final String DIR_NAME = "bg";
    private static final String SOURCE_NAME = "source.jpg";
    private static final String BLUR_PREFIX = "blur_";
    private static final String LEGACY_NAME = "bg_original.jpg";
    private static final String LIB_DIR_NAME = "wallpapers";
    private static final String EXT_JPG = ".jpg";
    private static final String THUMB_SUFFIX = ".thumb.jpg";
    private static final String PREF_MIGRATED = "bg_layout_v3";
    /** 壁纸库迁移标记：把旧版单张 bg/source.jpg 收进库里 */
    private static final String PREF_LIB_MIGRATED = "bg_library_v1";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private BackgroundManager() {
    }

    // ==================== 路径 ====================

    /** 壁纸库目录（存放所有选过的壁纸） */
    public static File getLibraryDir(Context ctx) {
        return new File(ctx.getFilesDir(), LIB_DIR_NAME);
    }

    private static File getDir(Context ctx) {
        return new File(ctx.getFilesDir(), DIR_NAME);
    }

    /** 壁纸库中某条目的原图文件 */
    public static File getWallpaperFile(Context ctx, String id) {
        return new File(getLibraryDir(ctx), id + EXT_JPG);
    }

    /** 壁纸库中某条目的缩略图文件 */
    public static File getThumbFile(Context ctx, String id) {
        return new File(getLibraryDir(ctx), id + THUMB_SUFFIX);
    }

    /** 旧版单张原图（迁移期读，新逻辑不再写入） */
    private static File getLegacySourceFile(Context ctx) {
        return new File(getDir(ctx), SOURCE_NAME);
    }

    // ==================== 当前壁纸 id ====================

    /** 当前壁纸 id；空串 / null = 纯色背景 */
    public static String getCurrentId(Context ctx) {
        if (ctx == null) return "";
        String id = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_WALLPAPER_ID, "");
        if (id == null || id.isEmpty()) return "";
        // 文件被手动删掉后不能留悬空 id，否则主界面会以为有背景却是空图
        return getWallpaperFile(ctx, id).exists() ? id : "";
    }

    private static void setCurrentId(Context ctx, String id) {
        android.content.SharedPreferences.Editor ed =
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (id == null || id.isEmpty()) {
            ed.remove(PREF_WALLPAPER_ID);
            ed.remove(PREF_IMAGE);
        } else {
            ed.putString(PREF_WALLPAPER_ID, id);
            ed.putString(PREF_IMAGE, getWallpaperFile(ctx, id).getAbsolutePath());
        }
        ed.apply();
    }

    // ==================== 壁纸库 ====================

    /** 壁纸库条目 */
    public static final class Wallpaper {
        public final String id;
        public final File file;
        public final long addedAt;
        public final boolean current;

        Wallpaper(String id, File file, long addedAt, boolean current) {
            this.id = id;
            this.file = file;
            this.addedAt = addedAt;
            this.current = current;
        }
    }

    /** 列出全部壁纸，按加入时间从新到旧 */
    public static java.util.List<Wallpaper> listWallpapers(Context ctx) {
        java.util.List<Wallpaper> out = new java.util.ArrayList<>();
        File dir = getLibraryDir(ctx);
        File[] files = dir.listFiles();
        if (files == null) return out;
        String current = getCurrentId(ctx);
        for (File f : files) {
            String name = f.getName();
            if (!f.isFile() || !name.endsWith(EXT_JPG) || name.endsWith(THUMB_SUFFIX)) continue;
            String id = name.substring(0, name.length() - EXT_JPG.length());
            if (id.isEmpty()) continue;
            out.add(new Wallpaper(id, f, f.lastModified(), id.equals(current)));
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(b.addedAt, a.addedAt));
        return out;
    }

    /**
     * 把裁剪好的图存进壁纸库并设为当前壁纸，返回新条目 id。
     * 库满时自动清掉最旧的一张（永不删当前这张）。
     */
    public static String saveWallpaper(Context ctx, Bitmap bmp) {
        File dir = getLibraryDir(ctx);
        if (!dir.exists() && !dir.mkdirs()) return null;
        String id = newId(ctx);
        if (!saveJpeg(bmp, getWallpaperFile(ctx, id), 92)) return null;
        // 先设为当前再裁剪：这样刚存的这张被标为 current，裁剪时绝不会被当成「最旧的」删掉
        setCurrentId(ctx, id);
        pruneLibrary(ctx);
        return id;
    }

    /** 生成不冲突的时间戳 id（同一毫秒连选两张时避免覆盖） */
    private static String newId(Context ctx) {
        long now = System.currentTimeMillis();
        String id = Long.toString(now);
        File dir = getLibraryDir(ctx);
        int n = 0;
        while (new File(dir, id + EXT_JPG).exists() && n < 1000) {
            id = Long.toString(now + (++n));
        }
        return id;
    }

    /** 切换到库中某张壁纸；不存在则返回 false。模糊图作废，由调用方按需重新预生成 */
    public static boolean switchWallpaper(Context ctx, String id) {
        if (id == null || id.isEmpty()) return false;
        File f = getWallpaperFile(ctx, id);
        if (!f.exists()) return false;
        deleteGenerated(ctx);       // 旧壁纸的模糊图对新壁纸无效
        setCurrentId(ctx, id);
        return true;
    }

    /** 删除一张壁纸；若删的是当前壁纸则一并切回纯色背景 */
    public static void deleteWallpaper(Context ctx, String id) {
        if (id == null || id.isEmpty()) return;
        if (id.equals(getCurrentId(ctx))) {
            deleteGenerated(ctx);
            setCurrentId(ctx, null);
        }
        deleteQuietly(getWallpaperFile(ctx, id));
        deleteQuietly(getThumbFile(ctx, id));
        // 该壁纸专属的避让区域一并清掉
        ZoneStore.clearFor(ctx, id);
    }

    /** 容量控制：超上限时删最旧的（跳过当前壁纸）。
     *  listWallpapers 是「新→旧」排序，所以必须**从末尾往前**删，
     *  否则会先把刚存进去的那张（最新）删掉。 */
    private static void pruneLibrary(Context ctx) {
        java.util.List<Wallpaper> all = listWallpapers(ctx);
        int extra = all.size() - MAX_WALLPAPERS;
        for (int i = all.size() - 1; i >= 0 && extra > 0; i--) {
            Wallpaper w = all.get(i);
            if (w.current) continue;
            deleteWallpaper(ctx, w.id);
            extra--;
        }
    }

    /** 壁纸库总占用（字节），用于设置页展示 */
    public static long getLibrarySize(Context ctx) {
        long total = 0;
        File dir = getLibraryDir(ctx);
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) total += f.length();
        return total;
    }

    /**
     * 取壁纸缩略图（长边约THUMB_EDGE）；没有就现生成一张并缓存。
     * 返回的 Bitmap 由调用方持有，调用方负责 recycle。
     */
    public static Bitmap loadThumb(Context ctx, String id) {
        File thumb = getThumbFile(ctx, id);
        if (thumb.exists()) {
            Bitmap cached = decodeSampled(thumb, THUMB_EDGE, THUMB_EDGE);
            if (cached != null) return cached;
        }
        File src = getWallpaperFile(ctx, id);
        Bitmap full = decodeSampled(src, THUMB_EDGE, THUMB_EDGE);
        if (full == null) return null;
        try {
            saveJpeg(full, thumb, 80);
        } catch (Throwable ignored) {
        }
        return full;
    }

    private static final int THUMB_EDGE = 240;

    // ==================== 读取当前背景 ====================

    /** 是否存在可用的背景原图 */
    public static boolean hasSource(Context ctx) {
        return !getCurrentId(ctx).isEmpty();
    }

    /**
     * 取得当前应该显示的背景文件。
     * 指定档位的模糊图尚未生成时回退到原图（绝不在调用处现算模糊）。
     */
    public static File getDisplayFile(Context ctx, String mode) {
        String id = getCurrentId(ctx);
        if (id.isEmpty()) return null;
        File src = getWallpaperFile(ctx, id);
        if (mode == null || NONE.equals(mode)) return src;
        File blurred = getBlurredFile(ctx, mode);
        return blurred.exists() ? blurred : src;
    }

    public static File getBlurredFile(Context ctx, String mode) {
        return new File(getDir(ctx), BLUR_PREFIX + mode + EXT_JPG);
    }

    /** 清除图片背景：切回纯色并删掉全部壁纸留档（用户手动清空） */
    public static void clearAll(Context ctx) {
        deleteGenerated(ctx);
        deleteQuietly(getDir(ctx));
        deleteQuietly(new File(ctx.getFilesDir(), LEGACY_NAME));
        deleteLegacyCache(ctx);
        deleteQuietly(getLibraryDir(ctx));
        setCurrentId(ctx, null);
    }

    /** 只切回纯色，壁纸库保留（供「清除图片背景」使用，用户还能切回来） */
    public static void detachCurrent(Context ctx) {
        deleteGenerated(ctx);
        setCurrentId(ctx, null);
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

    /** 由当前壁纸原图生成全部模糊档位；返回是否全部成功 */
    public static boolean generateAll(Context ctx) {
        File srcFile = currentSourceFile(ctx);
        if (srcFile == null) return false;
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

        File srcFile = currentSourceFile(ctx);
        if (srcFile == null) return false;
        Bitmap base = BitmapFactory.decodeFile(srcFile.getAbsolutePath());
        if (base == null) return false;
        boolean ok = generateOne(ctx, base, mode);
        base.recycle();
        return ok;
    }

    /** 当前壁纸的原图文件；未选中壁纸返回 null */
    private static File currentSourceFile(Context ctx) {
        String id = getCurrentId(ctx);
        if (id.isEmpty()) return null;
        File f = getWallpaperFile(ctx, id);
        return f.exists() ? f : null;
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
     * 一次性迁移（旧版单张背景 → 壁纸库）：
     *  1) 把 filesDir/bg/source.jpg 或更早的 bg_original.jpg 收进壁纸库，并设为当前壁纸；
     *  2) 清掉旧的运行时缓存。
     * 迁移后如缺少模糊图，返回 true 提示调用方后台补生成。
     */
    public static boolean prepare(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean needBlur = false;

        if (!sp.getBoolean(PREF_MIGRATED, false)) {
            sp.edit().putBoolean(PREF_MIGRATED, true).apply();
            File dir = getDir(ctx);
            if (!dir.exists()) dir.mkdirs();
            deleteLegacyCache(ctx);
            needBlur = true;
        }

        // 壁纸库迁移：老版本只有一张 source.jpg，把它收编成库里的第一张
        if (!sp.getBoolean(PREF_LIB_MIGRATED, false)) {
            sp.edit().putBoolean(PREF_LIB_MIGRATED, true).apply();
            if (getCurrentId(ctx).isEmpty()) {
                File legacy = getLegacySourceFile(ctx);
                File older = new File(ctx.getFilesDir(), LEGACY_NAME);
                File src = legacy.exists() ? legacy : older;
                if (src.exists()) {
                    File dir = getLibraryDir(ctx);
                    if (!dir.exists()) dir.mkdirs();
                    String id = newId(ctx);
                    // 移动而非复制：旧文件已无人引用，留着只会重复占空间
                    if (!src.renameTo(getWallpaperFile(ctx, id))) {
                        copyFile(src, getWallpaperFile(ctx, id));
                    }
                    setCurrentId(ctx, id);
                    needBlur = true;
                }
            }
        }

        // 选过壁纸但模糊档位还没生成（首启 / 换壁纸后 / 升级版本重生成）
        String mode = sp.getString(PREF_BLUR, NONE);
        if (!NONE.equals(mode) && !getBlurredFile(ctx, mode).exists()) {
            needBlur = true;
        }
        return needBlur && hasSource(ctx);
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

    /** 复制文件（renameTo 跨分区失败时的兜底） */
    private static boolean copyFile(File from, File to) {
        if (from == null || to == null || !from.exists()) return false;
        java.io.FileInputStream in = null;
        java.io.FileOutputStream out = null;
        try {
            File parent = to.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            in = new java.io.FileInputStream(from);
            out = new java.io.FileOutputStream(to);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }
}
