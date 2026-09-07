package com.quickstart.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.quickstart.R;

/**
 * 按键绑定工具类：读取绑定配置、启动绑定应用。
 * 统一供主界面按键、列表角标、设置页等调用。
 */
public final class KeyBindingHelper {

    private static final String PREFS = "settings";

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
}
