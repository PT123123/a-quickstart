package com.quickstart.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import com.quickstart.model.AppEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * 用 PackageManager.queryIntentActivities 加载所有可启动应用，
 * 并为每条预计算 T9 指纹 + 构造启动 Intent。
 *
 * 图标从 IconCache 获取（磁盘+内存缓存），缓存没有则不加载（由 Adapter 延迟加载）。
 */
public final class AppLoader {

    private AppLoader() {}

    /**
     * 加载应用元数据（不含图标加载阻塞）。
     * 图标尝试从缓存瞬时获取，没有则留空由 Adapter 延迟加载。
     */
    public static List<AppEntry> loadLaunchableApps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(intent, 0);

        List<AppEntry> out = new ArrayList<>(resolved.size());
        for (ResolveInfo ri : resolved) {
            String label = ri.loadLabel(pm).toString();
            String pkg  = ri.activityInfo.packageName;
            String act  = ri.activityInfo.name;
            List<String> fp = T9Matcher.buildFingerprints(label);
            AppEntry entry = new AppEntry(label, pkg, act, fp);

            // 构造启动 Intent
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                entry.launchIntent = launch;
            }

            // 尝试从缓存瞬时获取图标
            entry.icon = IconCache.get(ctx, pkg);

            // 标记最近更新的应用（时间范围从设置读取）
            try {
                long lastUpdate = pm.getPackageInfo(pkg, 0).lastUpdateTime;
                long timeRange = Long.parseLong(ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
                        .getString("recent_time_range", "604800000")); // 默认7天
                entry.recentlyUpdated = (System.currentTimeMillis() - lastUpdate) <= timeRange;
            } catch (Throwable ignored) {
                entry.recentlyUpdated = false;
            }

            out.add(entry);
        }
        return out;
    }

    /**
     * 为单个条目加载图标（后台线程调用）。
     */
    public static void loadIconForEntry(Context ctx, AppEntry entry) {
        if (entry.icon != null) return;
        entry.icon = IconCache.get(ctx, entry.packageName);
        if (entry.icon != null) return;

        // 缓存没有，从 PackageManager 加载
        PackageManager pm = ctx.getPackageManager();
        try {
            Drawable icon = pm.getApplicationIcon(entry.packageName);
            IconCache.put(ctx, entry.packageName, icon);
            entry.icon = icon;
        } catch (Throwable ignored) {
            try {
                entry.icon = pm.getDefaultActivityIcon();
            } catch (Throwable ignored2) {
            }
        }
    }
}
