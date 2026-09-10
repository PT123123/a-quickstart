package com.quickstart.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.quickstart.model.AppEntry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 分类配置管理。
 *
 * 分类分两类：
 *  - 智能分类（最近搜索 / 最近使用 / 最近安装）：固定、不可编辑，由代码决定归属。
 *  - 用户分类：可在「设置-分类管理」中添加、删除、重命名、编辑。
 *    用户分类又分两种归属方式：
 *       keyword  关键词规则：标签关键词 或 包名关键词，命中即归属（逗号分隔）。
 *       manual   手动分类：显式指定一组应用（"包名/Activity"），默认「主界面」为空、由用户自行放入应用。
 *
 * 配置以 JSON 存进 SharedPreferences("settings") 的 category_config 键，数组顺序即显示顺序。
 */
public final class CategoryConfig {

    public static final String TYPE_KEYWORD = "keyword";
    public static final String TYPE_MANUAL  = "manual";

    /** 智能分类（固定、不可编辑），代码里靠这些名称判断 */
    public static final String CAT_RECENT_SEARCH  = "最近搜索";
    public static final String CAT_RECENT_USE     = "最近使用";
    public static final String CAT_RECENT_INSTALL = "最近安装";

    /** 默认「主界面」分类：手动类型、默认为空，不可删除 */
    public static final String CAT_MAIN = "主界面";

    private static final String PREFS_NAME = "settings";
    private static final String KEY_CONFIG = "category_config";

    /** 内存缓存已解析配置，避免每次按键过滤时重复解析 JSON；save() 时失效 */
    private static volatile List<Category> cache;

    private CategoryConfig() {}

    /** 分类定义 */
    public static final class Category {
        public String name;
        public String type;          // TYPE_KEYWORD / TYPE_MANUAL
        public String labelKeywords = ""; // keyword 类型：标签关键词，逗号分隔
        public String pkgKeywords   = ""; // keyword 类型：包名关键词，逗号分隔
        public List<String> apps;    // manual 类型：归属应用的 "包名/Activity" 列表
        public boolean system;       // 主界面等内置分类，不可删除

        public Category(String name, String type) {
            this.name = name;
            this.type = type;
            this.apps = new ArrayList<>();
        }
    }

    // ==================== 读取 ====================

    public static List<Category> getAll(Context ctx) {
        if (cache != null) return cache;
        List<Category> out = new ArrayList<>();
        String json = prefs(ctx).getString(KEY_CONFIG, null);
        if (json == null || json.isEmpty()) {
            cache = createDefaults();
            return cache;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Category c = new Category(o.getString("name"), o.optString("type", TYPE_KEYWORD));
                c.labelKeywords = o.optString("labels", "");
                c.pkgKeywords   = o.optString("pkgs", "");
                c.system        = o.optBoolean("system", false);
                JSONArray apps = o.optJSONArray("apps");
                if (apps != null) {
                    for (int j = 0; j < apps.length(); j++) c.apps.add(apps.getString(j));
                }
                out.add(c);
            }
        } catch (Exception ignored) {
            cache = createDefaults();
            return cache;
        }
        cache = out;
        return out;
    }

    /** 按名称查找分类，找不到返回 null */
    public static Category find(Context ctx, String name) {
        for (Category c : getAll(ctx)) if (c.name.equals(name)) return c;
        return null;
    }

    /** 是否为固定的智能分类（最近搜索/最近使用/最近安装） */
    public static boolean isSmart(String name) {
        return CAT_RECENT_SEARCH.equals(name)
                || CAT_RECENT_USE.equals(name)
                || CAT_RECENT_INSTALL.equals(name);
    }

    /** 用户可编辑分类名（不含智能分类）的有序列表 */
    public static List<String> getUserCategoryNames(Context ctx) {
        List<String> names = new ArrayList<>();
        for (Category c : getAll(ctx)) names.add(c.name);
        return names;
    }

    /** 某应用是否归入手动分类 */
    public static boolean isInManualCategory(Context ctx, String name, AppEntry e) {
        Category c = find(ctx, name);
        if (c == null || !TYPE_MANUAL.equals(c.type)) return false;
        String key = e.packageName + "/" + (e.activityName == null ? "" : e.activityName);
        return c.apps.contains(key);
    }

    /** 关键词分类：标签关键词或包名关键词命中即归属 */
    public static boolean matchesKeyword(Context ctx, String name, AppEntry e) {
        Category c = find(ctx, name);
        if (c == null || !TYPE_KEYWORD.equals(c.type)) return false;
        String label = e.label.toLowerCase();
        String pkg   = e.packageName.toLowerCase();
        for (String kw : split(c.labelKeywords)) {
            if (label.contains(kw)) return true;
        }
        for (String kw : split(c.pkgKeywords)) {
            if (pkg.contains(kw)) return true;
        }
        return false;
    }

    // ==================== 写入 ====================

    public static void save(Context ctx, List<Category> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Category c : list) {
                JSONObject o = new JSONObject();
                o.put("name", c.name);
                o.put("type", c.type);
                o.put("labels", c.labelKeywords);
                o.put("pkgs", c.pkgKeywords);
                o.put("system", c.system);
                JSONArray apps = new JSONArray();
                for (String a : c.apps) apps.put(a);
                o.put("apps", apps);
                arr.put(o);
            }
        } catch (Exception ignored) { }
        prefs(ctx).edit().putString(KEY_CONFIG, arr.toString()).apply();
        cache = null; // 写后失效缓存
    }

    /** 是否存在同名分类 */
    public static boolean exists(Context ctx, String name) {
        for (Category c : getAll(ctx)) if (c.name.equals(name)) return true;
        return false;
    }

    // ==================== 默认配置 ====================

    private static List<Category> createDefaults() {
        List<Category> list = new ArrayList<>();

        // 默认「主界面」：手动、为空、不可删除
        Category main = new Category(CAT_MAIN, TYPE_MANUAL);
        main.system = true;
        list.add(main);

        // 其余默认分类沿用原有关键词规则（keyword 类型）
        list.add(kw("社交",     "微信,微博,qq,钉钉,飞书",     "com.tencent.mm,com.sina.weibo"));
        list.add(kw("影音",     "抖音,音乐,视频,哔哩",        "com.ss.android.ugc.aweme,com.netease.cloudmusic"));
        list.add(kw("交通出行",  "地图,滴滴,导航",            "com.sdu.didi.psnger,com.autonavi.minimap"));
        list.add(kw("实用工具", "计算器,设置,日历,时钟",      ""));
        list.add(kw("游戏",     "游戏,斗地主",               "game"));
        list.add(kw("购物",     "淘宝,京东,拼多多",          "com.taobao,com.jingdong,com.xunmeng"));
        list.add(kw("理财",     "银行,支付宝,股票",          ""));
        return list;
    }

    private static Category kw(String name, String labels, String pkgs) {
        Category c = new Category(name, TYPE_KEYWORD);
        c.labelKeywords = labels;
        c.pkgKeywords   = pkgs;
        return c;
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String part : s.split("[,，\\s]+")) {
            if (!part.isEmpty()) out.add(part.toLowerCase());
        }
        return out;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}