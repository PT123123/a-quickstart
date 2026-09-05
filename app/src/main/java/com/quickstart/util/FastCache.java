package com.quickstart.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import com.quickstart.model.AppEntry;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 快速二进制缓存：将整个应用列表+图标序列化到单个文件。
 * 比 JSON + 独立 PNG 文件快得多（单次读写 vs 数百次）。
 *
 * 文件格式：
 * [magic: 4 bytes "APPL"]
 * [version: 4 bytes int]
 * [count: 4 bytes int]
 * 每条记录：
 *   [labelLength: 4 bytes][label bytes UTF-8]
 *   [pkgLength: 4 bytes][pkg bytes UTF-8]
 *   [actLength: 4 bytes][act bytes UTF-8]
 *   [fpCount: 4 bytes][fpLength: 4 bytes][fp bytes] * fpCount
 *   [recentlyUpdated: 1 byte]
 *   [hasIcon: 1 byte]
 *   [iconSize: 4 bytes int] (if hasIcon)
 *   [iconBytes: iconSize bytes] (PNG, if hasIcon)
 */
public final class FastCache {

    private static final String CACHE_FILE = "app_list.cache";
    private static final int MAGIC = 0x4150504C; // "APPL"
    private static final int VERSION = 1;

    private FastCache() {}

    /** 保存应用列表到二进制文件（后台线程调用） */
    public static void save(Context ctx, List<AppEntry> apps) {
        File file = getCacheFile(ctx);
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(file))) {
            dos.writeInt(MAGIC);
            dos.writeInt(VERSION);
            dos.writeInt(apps.size());

            for (AppEntry e : apps) {
                // label
                byte[] labelBytes = e.label.getBytes("UTF-8");
                dos.writeInt(labelBytes.length);
                dos.write(labelBytes);

                // packageName
                byte[] pkgBytes = e.packageName.getBytes("UTF-8");
                dos.writeInt(pkgBytes.length);
                dos.write(pkgBytes);

                // activityName
                byte[] actBytes = e.activityName.getBytes("UTF-8");
                dos.writeInt(actBytes.length);
                dos.write(actBytes);

                // fingerprints
                int fpCount = e.fingerprints != null ? e.fingerprints.size() : 0;
                dos.writeInt(fpCount);
                if (e.fingerprints != null) {
                    for (String fp : e.fingerprints) {
                        byte[] fpBytes = fp.getBytes("UTF-8");
                        dos.writeInt(fpBytes.length);
                        dos.write(fpBytes);
                    }
                }

                // recentlyUpdated
                dos.writeByte(e.recentlyUpdated ? 1 : 0);

                // icon
                byte[] iconBytes = iconToBytes(e.icon);
                if (iconBytes != null) {
                    dos.writeByte(1);
                    dos.writeInt(iconBytes.length);
                    dos.write(iconBytes);
                } else {
                    dos.writeByte(0);
                }
            }
        } catch (IOException ignored) {
            file.delete(); // 写入失败删除损坏文件
        }
    }

    /** 从二进制文件加载应用列表 */
    public static List<AppEntry> load(Context ctx) {
        File file = getCacheFile(ctx);
        if (!file.exists()) return null;

        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            int magic = dis.readInt();
            if (magic != MAGIC) return null;

            int version = dis.readInt();
            if (version != VERSION) return null;

            int count = dis.readInt();
            List<AppEntry> out = new ArrayList<>(count);

            for (int i = 0; i < count; i++) {
                // label
                byte[] labelBytes = new byte[dis.readInt()];
                dis.readFully(labelBytes);
                String label = new String(labelBytes, "UTF-8");

                // packageName
                byte[] pkgBytes = new byte[dis.readInt()];
                dis.readFully(pkgBytes);
                String pkg = new String(pkgBytes, "UTF-8");

                // activityName
                byte[] actBytes = new byte[dis.readInt()];
                dis.readFully(actBytes);
                String act = new String(actBytes, "UTF-8");

                // fingerprints
                int fpCount = dis.readInt();
                List<String> fp = new ArrayList<>(fpCount);
                for (int j = 0; j < fpCount; j++) {
                    byte[] fpBytes = new byte[dis.readInt()];
                    dis.readFully(fpBytes);
                    fp.add(new String(fpBytes, "UTF-8"));
                }

                AppEntry entry = new AppEntry(label, pkg, act, fp);

                // recentlyUpdated
                entry.recentlyUpdated = dis.readByte() != 0;

                // icon
                boolean hasIcon = dis.readByte() != 0;
                if (hasIcon) {
                    byte[] iconBytes = new byte[dis.readInt()];
                    dis.readFully(iconBytes);
                    entry.icon = bytesToDrawable(ctx, iconBytes);
                }

                out.add(entry);
            }
            return out;
        } catch (IOException e) {
            file.delete(); // 读取失败删除损坏文件
            return null;
        }
    }

    public static boolean exists(Context ctx) {
        return getCacheFile(ctx).exists();
    }

    private static File getCacheFile(Context ctx) {
        return new File(ctx.getFilesDir(), CACHE_FILE);
    }

    private static byte[] iconToBytes(Drawable drawable) {
        if (drawable == null) return null;
        try {
            Bitmap bitmap = drawableToBitmap(drawable);
            if (bitmap == null) return null;
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
            return baos.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }

    private static Drawable bytesToDrawable(Context ctx, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) return null;
            return new BitmapDrawable(ctx.getResources(), bitmap);
        } catch (Throwable e) {
            return null;
        }
    }

    private static Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            Bitmap bmp = ((BitmapDrawable) drawable).getBitmap();
            if (bmp != null) return bmp.copy(Bitmap.Config.ARGB_8888, false);
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
