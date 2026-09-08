package com.quickstart.util;

import android.content.Context;
import android.graphics.Bitmap;
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
 * 文件格式（v2）：
 * [magic: 4 bytes "APPL"]
 * [version: 4 bytes int] = 2
 * [sortMode: 4 bytes int] 排序模式枚举（0=智能, 1=字母, 2=安装时间, 3=频率）
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

    /** 缓存结果包装：应用列表 + 缓存时的排序模式 */
    public static final class CacheResult {
        public final List<AppEntry> apps;
        /** 缓存时使用的排序模式（SORT_* 常量），未知返回 -1 */
        public final int sortMode;

        public CacheResult(List<AppEntry> apps, int sortMode) {
            this.apps = apps;
            this.sortMode = sortMode;
        }
    }

    public static final int SORT_SMART = 0;
    public static final int SORT_ALPHA = 1;
    public static final int SORT_INSTALL_TIME = 2;
    public static final int SORT_LAUNCH_COUNT = 3;
    public static final int SORT_UNKNOWN = -1;

    private static final String CACHE_FILE = "app_list.cache";
    private static final int MAGIC = 0x4150504C; // "APPL"
    private static final int VERSION = 2;

    private FastCache() {}

    /** 保存应用列表到二进制文件（后台线程调用） */
    public static void save(Context ctx, List<AppEntry> apps) {
        save(ctx, apps, SORT_SMART);
    }

    /** 保存应用列表到二进制文件，同时记录排序模式（后台线程调用） */
    public static void save(Context ctx, List<AppEntry> apps, int sortMode) {
        File file = getCacheFile(ctx);
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(file))) {
            dos.writeInt(MAGIC);
            dos.writeInt(VERSION);
            dos.writeInt(sortMode);
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

    /** 单个字段最大允许长度（防止损坏文件导致 OOM） */
    private static final int MAX_FIELD_LENGTH = 1024;
    /** 单个图标最大允许大小（512KB） */
    private static final int MAX_ICON_SIZE = 512 * 1024;
    /** 应用数量上限 */
    private static final int MAX_APP_COUNT = 2000;

    /** 从二进制文件加载应用列表（兼容旧格式，旧格式 sortMode 返回 SORT_UNKNOWN） */
    public static CacheResult load(Context ctx) {
        File file = getCacheFile(ctx);
        if (!file.exists()) return null;

        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            int magic = dis.readInt();
            if (magic != MAGIC) return null;

            int version = dis.readInt();
            if (version < 1 || version > VERSION) { file.delete(); return null; }

            int sortMode = SORT_UNKNOWN;
            if (version >= 2) {
                sortMode = dis.readInt();
            }

            int count = dis.readInt();
            // 校验数量合理性，防止损坏文件导致 OOM
            if (count <= 0 || count > MAX_APP_COUNT) return null;
            List<AppEntry> out = new ArrayList<>(count);

            for (int i = 0; i < count; i++) {
                // label
                int labelLen = dis.readInt();
                if (labelLen <= 0 || labelLen > MAX_FIELD_LENGTH) { file.delete(); return null; }
                byte[] labelBytes = new byte[labelLen];
                dis.readFully(labelBytes);
                String label = new String(labelBytes, "UTF-8");

                // packageName
                int pkgLen = dis.readInt();
                if (pkgLen <= 0 || pkgLen > MAX_FIELD_LENGTH) { file.delete(); return null; }
                byte[] pkgBytes = new byte[pkgLen];
                dis.readFully(pkgBytes);
                String pkg = new String(pkgBytes, "UTF-8");

                // activityName
                int actLen = dis.readInt();
                if (actLen < 0 || actLen > MAX_FIELD_LENGTH) { file.delete(); return null; }
                byte[] actBytes = new byte[actLen];
                dis.readFully(actBytes);
                String act = new String(actBytes, "UTF-8");

                // fingerprints
                int fpCount = dis.readInt();
                if (fpCount < 0 || fpCount > 100) { file.delete(); return null; }
                List<String> fp = new ArrayList<>(fpCount);
                for (int j = 0; j < fpCount; j++) {
                    int fpLen = dis.readInt();
                    if (fpLen <= 0 || fpLen > MAX_FIELD_LENGTH) { file.delete(); return null; }
                    byte[] fpBytes = new byte[fpLen];
                    dis.readFully(fpBytes);
                    fp.add(new String(fpBytes, "UTF-8"));
                }

                AppEntry entry = new AppEntry(label, pkg, act, fp);

                // recentlyUpdated
                entry.recentlyUpdated = dis.readByte() != 0;

                // icon
                boolean hasIcon = dis.readByte() != 0;
                if (hasIcon) {
                    int iconSize = dis.readInt();
                    if (iconSize <= 0 || iconSize > MAX_ICON_SIZE) { file.delete(); return null; }
                    byte[] iconBytes = new byte[iconSize];
                    dis.readFully(iconBytes);
                    entry.icon = bytesToDrawable(ctx, iconBytes);
                }

                out.add(entry);
            }
            return new CacheResult(out, sortMode);
        } catch (IOException e) {
            file.delete(); // 读取失败删除损坏文件
            return null;
        }
    }

    public static boolean exists(Context ctx) {
        return getCacheFile(ctx).exists();
    }

    /** 清除缓存文件 */
    public static void clear(Context ctx) {
        File file = getCacheFile(ctx);
        if (file.exists()) file.delete();
    }

    private static File getCacheFile(Context ctx) {
        return new File(ctx.getFilesDir(), CACHE_FILE);
    }

    private static byte[] iconToBytes(Drawable drawable) {
        if (drawable == null) return null;
        try {
            // 缩到落盘尺寸再压缩，缓存文件更小、读回时解码也更快
            Bitmap bitmap = IconCache.drawableToScaledBitmap(drawable, IconCache.DISK_ICON_SIZE);
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
            Bitmap bitmap = IconCache.decodeScaled(bytes);
            if (bitmap == null) return null;
            return new BitmapDrawable(ctx.getResources(), bitmap);
        } catch (Throwable e) {
            return null;
        }
    }
}
