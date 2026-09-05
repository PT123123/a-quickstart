package com.quickstart.util;

import android.content.Context;
import android.graphics.Bitmap;
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
 */
public final class IconCache {

    private IconCache() {}

    /** 内存缓存（最大 4MB） */
    private static final LruCache<String, Drawable> memoryCache = new LruCache<>(4 * 1024 * 1024) {
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
                Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(iconFile.getAbsolutePath());
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

    /** 保存图标到缓存（内存 + 磁盘） */
    public static void put(Context ctx, String pkg, Drawable drawable) {
        if (drawable == null) return;
        memoryCache.put(pkg, drawable);
        saveToDisk(ctx, pkg, drawable);
    }

    /**
     * 批量预加载图标到内存缓存。
     * 在后台线程中从磁盘或 PackageManager 加载所有图标，
     * 加载完成后调用 callback.run() 回到主线程更新 UI。
     */
    public static void preloadAll(Context ctx, List<String> packages, Runnable callback) {
        preloadExecutor.execute(() -> {
            for (String pkg : packages) {
                if (memoryCache.get(pkg) != null) continue;

                // 先尝试磁盘
                File iconFile = getIconFile(ctx, pkg);
                if (iconFile.exists()) {
                    try {
                        Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(iconFile.getAbsolutePath());
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
                    memoryCache.put(pkg, icon);
                    saveToDisk(ctx, pkg, icon);
                } catch (Throwable ignored) {
                }
            }
            // 回到主线程回调
            new android.os.Handler(android.os.Looper.getMainLooper()).post(callback);
        });
    }

    private static File getIconFile(Context ctx, String pkg) {
        File dir = new File(ctx.getCacheDir(), "icons");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, pkg.replace('.', '_') + ".png");
    }

    private static void saveToDisk(Context ctx, String pkg, Drawable drawable) {
        try {
            Bitmap bitmap = drawableToBitmap(drawable);
            if (bitmap == null) return;
            File file = getIconFile(ctx, pkg);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            Bitmap bmp = ((BitmapDrawable) drawable).getBitmap();
            if (bmp != null) return bmp;
        }
        int w = drawable.getIntrinsicWidth();
        int h = drawable.getIntrinsicHeight();
        if (w <= 0 || h <= 0) w = h = 96;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, w, h);
        drawable.draw(canvas);
        return bitmap;
    }
}
