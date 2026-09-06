package com.quickstart.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 图标缓存：内存 LruCache + 磁盘文件缓存。
 * 支持批量预加载，确保列表显示时图标已在内存中。
 * 所有图标统一降采样到显示所需尺寸，避免全分辨率位图撑爆内存引发 GC 卡顿。
 */
public final class IconCache {

    private IconCache() {}

    /** 图标在内存中的目标尺寸（px）：网格显示足够清晰，同时把单张位图内存控制在 ~64KB 量级 */
    private static final int TARGET_ICON_SIZE = 128;
    /** 图标落盘尺寸（px）：留出高密度屏放大的余量 */
    public static final int DISK_ICON_SIZE = TARGET_ICON_SIZE * 2;

    /** 内存缓存（约 4MB；maxSize 单位为 KB，与 sizeOf 返回值一致） */
    private static final LruCache<String, Drawable> memoryCache = new LruCache<>(4 * 1024) {
        @Override
        protected int sizeOf(String key, Drawable value) {
            if (value instanceof BitmapDrawable) {
                Bitmap bmp = ((BitmapDrawable) value).getBitmap();
                return bmp != null ? bmp.getByteCount() / 1024 : 4;
            }
            return 4;
        }
    };

    /** 预加载线程池 */
    private static final ExecutorService preloadExecutor = Executors.newFixedThreadPool(8);

    /** 从缓存获取图标（先内存后磁盘） */
    public static Drawable get(Context ctx, String pkg) {
        Drawable cached = memoryCache.get(pkg);
        if (cached != null) return cached;

        File iconFile = getIconFile(ctx, pkg);
        if (iconFile.exists()) {
            try {
                Bitmap bitmap = decodeScaled(iconFile);
                if (bitmap != null) {
                    Drawable drawable = new BitmapDrawable(ctx.getResources(), bitmap);
                    memoryCache.put(pkg, drawable);
                    return drawable;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 保存图标到缓存（内存 + 磁盘），存入内存前统一降采样 */
    public static void put(Context ctx, String pkg, Drawable drawable) {
        if (drawable == null) return;
        Bitmap scaled = drawableToScaledBitmap(drawable, TARGET_ICON_SIZE);
        if (scaled != null) {
            memoryCache.put(pkg, new BitmapDrawable(ctx.getResources(), scaled));
        }
        saveToDisk(ctx, pkg, drawable);
    }

    /**
     * 批量预加载图标到内存缓存。
     * 在后台线程中从磁盘或 PackageManager 加载所有图标，
     * 全部完成后在同一个后台线程调用 callback.run()（不切回主线程，
     * 由调用方决定后续工作放在哪个线程，避免重活落到 UI 线程）。
     */
    public static void preloadAll(Context ctx, List<String> packages, Runnable callback) {
        preloadExecutor.execute(() -> {
            for (String pkg : packages) {
                if (memoryCache.get(pkg) != null) continue;

                // 先尝试磁盘
                File iconFile = getIconFile(ctx, pkg);
                if (iconFile.exists()) {
                    try {
                        Bitmap bitmap = decodeScaled(iconFile);
                        if (bitmap != null) {
                            memoryCache.put(pkg, new BitmapDrawable(ctx.getResources(), bitmap));
                            continue;
                        }
                    } catch (Throwable ignored) {
                    }
                }

                // 磁盘没有，从 PackageManager 加载
                try {
                    Drawable icon = ctx.getPackageManager().getApplicationIcon(pkg);
                    saveToDisk(ctx, pkg, icon);
                    Bitmap scaled = drawableToScaledBitmap(icon, TARGET_ICON_SIZE);
                    if (scaled != null) {
                        memoryCache.put(pkg, new BitmapDrawable(ctx.getResources(), scaled));
                    }
                } catch (Throwable ignored) {
                }
            }
            callback.run();
        });
    }

    /** 计算降采样倍数：解码结果边长略大于 TARGET_ICON_SIZE 即可 */
    private static int calcInSampleSize(int width, int height) {
        int sample = 1;
        while (width / (sample * 2) >= TARGET_ICON_SIZE
                && height / (sample * 2) >= TARGET_ICON_SIZE) {
            sample *= 2;
        }
        return sample;
    }

    /** 按目标尺寸降采样解码磁盘图标 */
    public static Bitmap decodeScaled(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight);
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    /** 按目标尺寸降采样解码内存中的图标数据 */
    public static Bitmap decodeScaled(byte[] data) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight);
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }

    /** 把 Drawable 渲染为最长边不超过 targetSize 的位图；不会回收调用方持有的源位图 */
    public static Bitmap drawableToScaledBitmap(Drawable drawable, int targetSize) {
        if (drawable == null) return null;
        if (drawable instanceof BitmapDrawable) {
            Bitmap bmp = ((BitmapDrawable) drawable).getBitmap();
            if (bmp != null && !bmp.isRecycled()) {
                return scaleDown(bmp, targetSize);
            }
        }
        int w = drawable.getIntrinsicWidth();
        int h = drawable.getIntrinsicHeight();
        if (w <= 0 || h <= 0) w = h = targetSize;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, w, h);
        drawable.draw(canvas);
        return scaleDown(bitmap, targetSize);
    }

    /** 等比缩小到最长边不超过 maxSize；已小于则原样返回 */
    private static Bitmap scaleDown(Bitmap src, int maxSize) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= maxSize && h <= maxSize) return src;
        float scale = Math.min((float) maxSize / w, (float) maxSize / h);
        return Bitmap.createScaledBitmap(src, Math.max(1, (int) (w * scale)),
                Math.max(1, (int) (h * scale)), true);
    }

    private static File getIconFile(Context ctx, String pkg) {
        File dir = new File(ctx.getCacheDir(), "icons");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, pkg.replace('.', '_') + ".png");
    }

    private static void saveToDisk(Context ctx, String pkg, Drawable drawable) {
        try {
            Bitmap bitmap = drawableToScaledBitmap(drawable, DISK_ICON_SIZE);
            if (bitmap == null || bitmap.isRecycled()) return;
            File file = getIconFile(ctx, pkg);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
        } catch (Throwable ignored) {
        }
    }
}
