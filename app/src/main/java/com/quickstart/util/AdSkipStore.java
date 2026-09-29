package com.quickstart.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 快跳过的名单与统计数据存取（存 "settings"，随 ConfigTransfer 导出/导入）。
 *
 * 名单模式（ad_skip_app_mode）：
 *  all     = 所有应用生效（默认）
 *  white   = 仅名单内生效
 *  black   = 名单内不生效
 * 名单集合 ad_skip_app_set；另有「不再跳过」永久屏蔽集合 ad_skip_app_muted
 * （悬浮条快捷按钮写入，设置 → 应用名单 里可移除），屏蔽优先于名单模式。
 */
public final class AdSkipStore {

    public static final String PREFS_NAME = "settings";
    public static final String KEY_APP_MODE = "ad_skip_app_mode";
    public static final String KEY_APP_SET = "ad_skip_app_set";
    public static final String KEY_MUTED = "ad_skip_app_muted";

    public static final String MODE_ALL = "all";
    public static final String MODE_WHITE = "white";
    public static final String MODE_BLACK = "black";

    // 统计键
    private static final String KEY_DAY = "ad_skip_stats_day";
    private static final String KEY_DAY_COUNT = "ad_skip_stats_day_count";
    private static final String KEY_TOTAL = "ad_skip_stats_total";
    private static final String KEY_APPS = "ad_skip_stats_apps";
    private static final String KEY_LOG = "ad_skip_stats_log";
    private static final int LOG_CAP = 12;
    private static final int APPS_CAP = 30;

    private AdSkipStore() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String getAppMode(Context ctx) {
        return sp(ctx).getString(KEY_APP_MODE, MODE_ALL);
    }

    public static Set<String> getAppSet(Context ctx) {
        return sp(ctx).getStringSet(KEY_APP_SET, java.util.Collections.emptySet());
    }

    public static Set<String> getMuted(Context ctx) {
        return sp(ctx).getStringSet(KEY_MUTED, java.util.Collections.emptySet());
    }

    /** 「不再跳过」开关：mute=true 加入永久屏蔽，false 移除 */
    public static void setMuted(Context ctx, String pkg, boolean mute) {
        SharedPreferences s = sp(ctx);
        Set<String> muted = new java.util.HashSet<>(s.getStringSet(KEY_MUTED,
                java.util.Collections.emptySet()));
        boolean changed = mute ? muted.add(pkg) : muted.remove(pkg);
        if (changed) s.edit().putStringSet(KEY_MUTED, muted).apply();
    }

    /** 该应用当前是否应被跳过（屏蔽/名单模式判定；服务每事件调用，保持轻量） */
    public static boolean allowsPackage(Context ctx, String pkg) {
        SharedPreferences s = sp(ctx);
        if (s.getStringSet(KEY_MUTED, java.util.Collections.emptySet()).contains(pkg)) return false;
        String mode = s.getString(KEY_APP_MODE, MODE_ALL);
        if (MODE_ALL.equals(mode)) return true;
        boolean inList = s.getStringSet(KEY_APP_SET, java.util.Collections.emptySet()).contains(pkg);
        return MODE_WHITE.equals(mode) ? inList : !inList;
    }

    // ==================== 统计 ====================

    /** 记录一次跳过成功（服务主线程调用，写入量小） */
    public static void recordSkip(Context ctx, String pkg, String method) {
        SharedPreferences s = sp(ctx);
        SharedPreferences.Editor e = s.edit();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (!today.equals(s.getString(KEY_DAY, null))) {
            e.putString(KEY_DAY, today).putInt(KEY_DAY_COUNT, 0);
        }
        e.putInt(KEY_DAY_COUNT, s.getInt(KEY_DAY_COUNT, 0) + 1);
        e.putInt(KEY_TOTAL, s.getInt(KEY_TOTAL, 0) + 1);

        // 每应用计数（JSON map，超上限裁掉次数最少的）
        try {
            JSONObject apps = new JSONObject(s.getString(KEY_APPS, "{}"));
            apps.put(pkg, apps.optInt(pkg, 0) + 1);
            if (apps.length() > APPS_CAP) {
                Map<Integer, List<String>> byCount = new TreeMap<>();
                Iterator<String> it = apps.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    int c = apps.optInt(k, 0);
                    byCount.computeIfAbsent(c, x -> new ArrayList<>()).add(k);
                }
                for (List<String> group : byCount.values()) {
                    for (String k : group) {
                        if (apps.length() <= APPS_CAP) break;
                        apps.remove(k);
                    }
                }
            }
            e.putString(KEY_APPS, apps.toString());
        } catch (Exception ignored) {
        }

        // 最近日志（时间倒序，cap LOG_CAP）
        try {
            JSONArray log = new JSONArray(s.getString(KEY_LOG, "[]"));
            JSONObject item = new JSONObject();
            item.put("t", System.currentTimeMillis());
            item.put("p", pkg);
            item.put("m", method);
            JSONArray next = new JSONArray();
            next.put(item);
            for (int i = 0; i < log.length() && next.length() < LOG_CAP; i++) {
                next.put(log.get(i));
            }
            e.putString(KEY_LOG, next.toString());
        } catch (Exception ignored) {
        }
        e.apply();
    }

    /** 统计汇总：今日 / 累计 / 跳过最多的前 5 个应用（labeler 仅对前 5 个包名调用） */
    public static String buildSummary(Context ctx, java.util.function.Function<String, CharSequence> labeler) {
        SharedPreferences s = sp(ctx);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        int dayCount = today.equals(s.getString(KEY_DAY, null)) ? s.getInt(KEY_DAY_COUNT, 0) : 0;
        StringBuilder sb = new StringBuilder();
        sb.append("今日已跳过 ").append(dayCount)
                .append(" 次 · 累计 ").append(s.getInt(KEY_TOTAL, 0)).append(" 次\n");
        try {
            JSONObject apps = new JSONObject(s.getString(KEY_APPS, "{}"));
            List<Map.Entry<String, Integer>> top = new ArrayList<>();
            Iterator<String> it = apps.keys();
            while (it.hasNext()) {
                String k = it.next();
                top.add(new HashMap.SimpleEntry<>(k, apps.optInt(k, 0)));
            }
            top.sort((a, b) -> b.getValue() - a.getValue());
            int n = Math.min(5, top.size());
            if (n > 0) {
                sb.append("\n跳过最多的应用：");
                for (int i = 0; i < n; i++) {
                    String pkg = top.get(i).getKey();
                    CharSequence label = labeler != null ? labeler.apply(pkg) : null;
                    sb.append('\n').append(i + 1).append(". ")
                            .append(label != null ? label : pkg)
                            .append(" ×").append(top.get(i).getValue());
                }
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    /** 最近跳过日志（已格式化为时间 + 应用 + 方式，时间倒序） */
    public static List<String> buildRecentLog(Context ctx) {
        List<String> out = new ArrayList<>();
        SharedPreferences s = sp(ctx);
        try {
            JSONArray log = new JSONArray(s.getString(KEY_LOG, "[]"));
            SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
            for (int i = 0; i < log.length(); i++) {
                JSONObject o = log.optJSONObject(i);
                if (o == null) continue;
                String pkg = o.optString("p", "?");
                out.add(fmt.format(new Date(o.optLong("t", 0)))
                        + "  " + pkg + "  " + o.optString("m", ""));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void clearStats(Context ctx) {
        sp(ctx).edit()
                .remove(KEY_DAY).remove(KEY_DAY_COUNT).remove(KEY_TOTAL)
                .remove(KEY_APPS).remove(KEY_LOG)
                .apply();
    }
}
