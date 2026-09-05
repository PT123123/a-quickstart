package com.likpia.quickstartpro.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.likpia.quickstartpro.model.AppEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * 用 PackageManager.queryIntentActivities 加载所有可启动应用，
 * 并为每条预计算 T9 指纹 + 构造启动 Intent。
 */
public final class AppLoader {

    private AppLoader() {}

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
            // 构造启动 Intent（用系统给的，最可靠）
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                entry.launchIntent = launch;
            }
            try {
                entry.icon = ri.loadIcon(pm);
            } catch (Throwable ignored) {
                entry.icon = pm.getDefaultActivityIcon();
            }
            out.add(entry);
        }
        return out;
    }
}
