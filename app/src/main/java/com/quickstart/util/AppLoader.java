package com.quickstart.util;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;

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
     * 统一的条目构造：T9 数字指纹 + 增强指纹（混合输入匹配用）一次性预计算。
     */
    private static AppEntry createEntry(String label, String pkg, String act) {
        AppEntry e = new AppEntry(label, pkg, act, T9Matcher.buildFingerprints(label));
        e.enhancedPatterns = T9Matcher.buildEnhancedPatterns(label);
        return e;
    }

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
            AppEntry entry = createEntry(label, pkg, act);

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

        // 添加微信快捷功能（扫一扫、付款码）
        addWeChatShortcuts(ctx, out);

        // 添加支付宝快捷功能（扫一扫、付款码）
        addAlipayShortcuts(ctx, out);

        return out;
    }

    /**
     * 添加微信快捷功能：扫一扫、付款码
     * 这些是微信内的特定 Activity，通过 Intent 直接启动
     */
    private static void addWeChatShortcuts(Context ctx, List<AppEntry> out) {
        String wechatPkg = "com.tencent.mm";
        PackageManager pm = ctx.getPackageManager();
        try {
            // 检查微信是否安装
            pm.getPackageInfo(wechatPkg, 0);
        } catch (Throwable e) {
            return; // 微信未安装，跳过
        }

        // 微信图标（复用微信主图标）
        Drawable wechatIcon = IconCache.get(ctx, wechatPkg);
        if (wechatIcon == null) {
            try {
                wechatIcon = pm.getApplicationIcon(wechatPkg);
                IconCache.put(ctx, wechatPkg, wechatIcon);
            } catch (Throwable ignored) {}
        }

        // 微信扫一扫（T9 指纹由 buildFingerprints 从标签自动生成：全拼/首字母/原始字母）
        AppEntry scanEntry = new AppEntry("微信扫一扫", wechatPkg, "",
                T9Matcher.buildFingerprints("微信扫一扫"));
        scanEntry.icon = wechatIcon;
        scanEntry.recentlyUpdated = false;
        // 微信扫一扫：通过 getLaunchIntentForPackage + Extra 触发
        Intent scanIntent = pm.getLaunchIntentForPackage(wechatPkg);
        if (scanIntent != null) {
            scanIntent.putExtra("LauncherUI.From.Scaner.Shortcut", true);
            scanIntent.setAction(Intent.ACTION_VIEW);
            scanIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        scanEntry.launchIntent = scanIntent;
        out.add(scanEntry);

        // 微信付款码（T9 指纹由 buildFingerprints 从标签自动生成：全拼/首字母/原始字母）
        AppEntry payEntry = new AppEntry("微信付款码", wechatPkg, "",
                T9Matcher.buildFingerprints("微信付款码"));
        payEntry.icon = wechatIcon;
        payEntry.recentlyUpdated = false;
        // 微信付款码：通过 getLaunchIntentForPackage + Extra 触发
        Intent payIntent = pm.getLaunchIntentForPackage(wechatPkg);
        if (payIntent != null) {
            payIntent.putExtra("LauncherUI.Shortcut.LaunchType", "launch_type_offline_wallet");
            payIntent.setAction(Intent.ACTION_VIEW);
            payIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        payEntry.launchIntent = payIntent;
        out.add(payEntry);
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

    /**
     * 添加支付宝快捷功能：扫一扫、付款码
     * 支付宝官方支持 URL Scheme，可直接跳转到指定页面
     */
    private static void addAlipayShortcuts(Context ctx, List<AppEntry> out) {
        SharedPreferences prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("alipay_shortcuts", true)) {
            return; // 用户已关闭支付宝快捷方式
        }

        String alipayPkg = "com.eg.android.AlipayGphone";
        PackageManager pm = ctx.getPackageManager();
        try {
            // 检查支付宝是否安装
            pm.getPackageInfo(alipayPkg, 0);
        } catch (Throwable e) {
            return; // 支付宝未安装，跳过
        }

        // 支付宝图标
        Drawable alipayIcon = IconCache.get(ctx, alipayPkg);
        if (alipayIcon == null) {
            try {
                alipayIcon = pm.getApplicationIcon(alipayPkg);
                IconCache.put(ctx, alipayPkg, alipayIcon);
            } catch (Throwable ignored) {}
        }

        // 支付宝扫一扫（URL Scheme: alipayqr://platformapi/startapp?saId=10000007）
        AppEntry scanEntry = new AppEntry("支付宝扫一扫", alipayPkg, "",
                T9Matcher.buildFingerprints("支付宝扫一扫"));
        scanEntry.icon = alipayIcon;
        scanEntry.recentlyUpdated = false;
        Intent scanIntent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("alipayqr://platformapi/startapp?saId=10000007"));
        scanIntent.setPackage(alipayPkg);
        scanIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        scanEntry.launchIntent = scanIntent;
        out.add(scanEntry);

        // 支付宝付款码（URL Scheme: alipayqr://platformapi/startapp?saId=20000056）
        AppEntry payEntry = createEntry("支付宝付款码", alipayPkg, "");
        payEntry.icon = alipayIcon;
        payEntry.recentlyUpdated = false;
        Intent payIntent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("alipayqr://platformapi/startapp?saId=20000056"));
        payIntent.setPackage(alipayPkg);
        payIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        payEntry.launchIntent = payIntent;
        out.add(payEntry);
    }
}
