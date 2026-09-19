package com.quickstart.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.quickstart.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 按键绑定工具类：读取绑定配置、启动绑定应用。
 * 统一供主界面按键、列表角标、设置页等调用。
 */
public final class KeyBindingHelper {

    private static final String PREFS = "settings";

    /** 默认绑定的记账键：key_bind_default_&lt;digit&gt;，true 表示这个键位已经处理过（成功绑定或用户已自行绑定） */
    private static final String DEFAULT_FLAG_PREFIX = "key_bind_default_";

    /** 一条默认绑定规则：数字键 + 候选包名 + 应用名关键字（依次找第一个已安装的） */
    private static final class DefaultBinding {
        final int digit;
        final String[] packages;
        final String[] keywords;

        DefaultBinding(int digit, String[] packages, String[] keywords) {
            this.digit = digit;
            this.packages = packages;
            this.keywords = keywords;
        }
    }

    /**
     * 内置默认绑定：长按 1 = Clash、7 = 微信、8 = Twitter、9 = 知乎。
     * 先按候选包名精确匹配（最可靠），找不到再按已安装应用的名字关键字兜底
     * （同一应用常有好几个包名，如 Clash 的各种分支）。
     */
    private static final DefaultBinding[] DEFAULT_BINDINGS = {
            new DefaultBinding(1, new String[]{
                    "com.github.kr328.clash",          // Clash for Android
                    "com.github.kr328.clash.foss",     // Clash for Android (FOSS)
                    "com.github.metacubex.clash.meta", // Clash Meta for Android
                    "com.github.metacubex.clash",      // Clash.Meta
                    "io.github.clashmeta.android",
            }, new String[]{"clash"}),
            new DefaultBinding(7, new String[]{
                    "com.tencent.mm",
            }, new String[]{"微信", "wechat"}),
            new DefaultBinding(8, new String[]{
                    "com.twitter.android",
            }, new String[]{"twitter"}),
            new DefaultBinding(9, new String[]{
                    "com.zhihu.android",
            }, new String[]{"知乎", "zhihu"}),
    };

    private KeyBindingHelper() {}

    /** 启动指定数字键绑定的应用 */
    public static boolean launchBoundApp(Context ctx, int digit) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String pkg = sp.getString("key_bind_" + digit, "");
        if (pkg.isEmpty()) return false;

        try {
            Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                // 若 Context 是 Activity，使用从中心缩放展开的启动动画
                if (ctx instanceof Activity) {
                    Activity activity = (Activity) ctx;
                    android.app.ActivityOptions opts = android.app.ActivityOptions.makeCustomAnimation(
                            activity, R.anim.launch_scale_up, R.anim.no_anim);
                    activity.startActivity(intent, opts.toBundle());
                } else {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(intent);
                }
                return true;
            }
        } catch (Throwable ignored) {
        }
        // 启动失败，清除失效绑定
        sp.edit().remove("key_bind_" + digit).remove("key_gesture_" + digit).apply();
        return false;
    }

    /** 获取指定数字键绑定的包名，未绑定返回 null */
    public static String getBoundPackage(Context ctx, int digit) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("key_bind_" + digit, null);
    }

    /** 绑定应用到数字键 */
    public static void bind(Context ctx, int digit, String pkg) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("key_bind_" + digit, pkg)
                .apply();
    }

    /** 清除数字键绑定 */
    public static void unbind(Context ctx, int digit) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove("key_bind_" + digit)
                .remove("key_gesture_" + digit) // 顺带清理旧版本遗留的 per-key 手势数据
                .apply();
    }

    /**
     * 首次运行（或升级到带默认绑定的版本后首次运行）自动补一次内置默认绑定。
     *
     * 规则：
     * - 只补「当前没有绑定任何应用」的键位，绝不覆盖用户自己的绑定；
     * - 该键位的默认应用没安装时保持空位，下次启动再试（只有成功绑定、或用户已自行绑定时才记账）；
     * - 每个键位只补一次（SP 记 key_bind_default_&lt;digit&gt;），用户之后清空绑定也不会被重新填上。
     *
     * 需要在后台线程调用（要查 PackageManager）。
     *
     * @return 是否真的写入了新绑定，调用方据此刷新按键上的应用图标
     */
    public static boolean applyDefaultBindingsIfNeeded(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean changed = false;
        List<ResolveInfo> launcherApps = null; // 需要按应用名兜底时才去查

        for (DefaultBinding binding : DEFAULT_BINDINGS) {
            String flagKey = DEFAULT_FLAG_PREFIX + binding.digit;
            if (sp.getBoolean(flagKey, false)) continue;

            if (!sp.getString("key_bind_" + binding.digit, "").isEmpty()) {
                // 用户已自行绑定，尊重用户选择，只记账不再插手
                sp.edit().putBoolean(flagKey, true).apply();
                continue;
            }

            String pkg = resolveByPackages(ctx, binding.packages);
            if (pkg == null) {
                if (launcherApps == null) launcherApps = queryLauncherApps(ctx);
                pkg = resolveByLabel(ctx, launcherApps, binding.keywords);
            }
            if (pkg == null) continue; // 未安装：不记账，下次启动再试

            sp.edit()
                    .putString("key_bind_" + binding.digit, pkg)
                    .putBoolean(flagKey, true)
                    .apply();
            changed = true;
        }
        return changed;
    }

    /** 候选包名里第一个「已安装且可启动」的；都没有返回 null */
    private static String resolveByPackages(Context ctx, String[] candidates) {
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : candidates) {
            try {
                if (pm.getLaunchIntentForPackage(pkg) != null) return pkg;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 所有可启动应用（按应用名兜底匹配时用） */
    private static List<ResolveInfo> queryLauncherApps(Context ctx) {
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        try {
            List<ResolveInfo> apps = ctx.getPackageManager().queryIntentActivities(intent, 0);
            return apps == null ? new ArrayList<ResolveInfo>() : apps;
        } catch (Throwable e) {
            return new ArrayList<>();
        }
    }

    /** 按应用名（不区分大小写）匹配关键字，返回第一个命中的应用包名 */
    private static String resolveByLabel(Context ctx, List<ResolveInfo> apps, String[] keywords) {
        PackageManager pm = ctx.getPackageManager();
        for (String keyword : keywords) {
            for (ResolveInfo ri : apps) {
                String label;
                try {
                    label = ri.loadLabel(pm).toString();
                } catch (Throwable ignored) {
                    continue;
                }
                if (label.toLowerCase().contains(keyword)) {
                    return ri.activityInfo.packageName;
                }
            }
        }
        return null;
    }
}
