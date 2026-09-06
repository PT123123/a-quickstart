package com.quickstart;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.quickstart.adapter.AppListAdapter;
import com.quickstart.model.AppEntry;
import com.quickstart.util.AppLoader;
import com.quickstart.util.FastCache;
import com.quickstart.util.IconCache;
import com.quickstart.util.KeyBindingHelper;
import com.quickstart.util.SearchHistory;
import com.quickstart.util.T9Matcher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private TextView sortLabel, t9Hint, t9Display, emptyHint;
    private RecyclerView recycler;
    private View keypad;
    private AppListAdapter adapter;

    private List<AppEntry> allApps = new ArrayList<>();
    private List<AppEntry> filtered = new ArrayList<>();
    private StringBuilder query = new StringBuilder();
    private String currentCategory = null;
    /** 当前「最近搜索」分类的历史键集合（包名/Activity），非该分类时为 null */
    private java.util.Set<String> searchHistoryKeys;

    /** 分类标签有序列表（用于左右滑动切换） */
    private final String[] categoryList = {
            "最近搜索", "最近使用", "最近安装", "社交", "影音",
            "交通出行", "实用工具", "游戏", "购物", "理财"
    };
    /** 悬浮吸顶标签栏的滚动阈值（px） */
    private static final int STICKY_BAR_SCROLL_THRESHOLD = 200;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 启动后定时刷新的间隔：30 分钟 */
    private static final long REFRESH_INTERVAL_MS = 30 * 60 * 1000L;
    private final Runnable periodicRefresh = new Runnable() {
        @Override
        public void run() {
            refreshAppsFullScan();
            main.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyWindowSize();
        applyBackgroundColor();

        sortLabel  = findViewById(R.id.sort_label);
        t9Hint     = findViewById(R.id.t9_hint);
        t9Display  = findViewById(R.id.t9_display);
        emptyHint  = findViewById(R.id.empty_hint);
        recycler   = findViewById(R.id.app_list);
        keypad     = findViewById(R.id.keypad);

        adapter = new AppListAdapter();
        adapter.setOnAppClickListener(this::launchApp);
        adapter.setOnAppLongClickListener(this::showAppMenu);

        int columnCount = getColumnCount();
        recycler.setLayoutManager(new GridLayoutManager(this, columnCount));
        recycler.setAdapter(adapter);
        adapter.setColumnCount(columnCount);

        // 应用设置
        boolean showDot = getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("recent_app_dot", true);
        adapter.setShowRecentDot(showDot);

        // 依赖 adapter，必须在其初始化之后调用
        applyFontColor();

        sortLabel.setOnClickListener(v -> showSortMenu());

        // ⚙ 设置按钮
        findViewById(R.id.btn_settings_top).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        // ⌄ 收缩/展开键盘（在键盘上方）
        TextView toggleKeypad = findViewById(R.id.btn_toggle_keypad);
        toggleKeypad.setOnClickListener(v -> {
            if (keypad.getVisibility() == View.VISIBLE) {
                keypad.setVisibility(View.GONE);
                ((TextView) v).setText("⌃");
            } else {
                keypad.setVisibility(View.VISIBLE);
                ((TextView) v).setText("⌄");
            }
        });

        setupKeypad();
        setupCategoryChips();
        setupSwipeToSwitchCategory();
        setupStickyCategoryBar();
        loadAppsAsync();
    }

    private void setupKeypad() {
        // 数字键 1~9
        int[] keyIds = {R.id.key_1, R.id.key_2, R.id.key_3, R.id.key_4, R.id.key_5,
                        R.id.key_6, R.id.key_7, R.id.key_8, R.id.key_9};
        for (int i = 0; i < keyIds.length; i++) {
            int digit = i + 1;
            View key = keypad.findViewById(keyIds[i]);
            setupKeyGesture(key, digit);
        }
        View key0 = keypad.findViewById(R.id.key_0);
        setupKeyGesture(key0, 0);
        keypad.findViewById(R.id.key_clear).setOnClickListener(v -> clearQuery());
        keypad.findViewById(R.id.key_back).setOnClickListener(v -> onBackspace());

        // 设置角标手势回调
        adapter.setOnBadgeGestureListener(position -> {
            if (position < filtered.size()) {
                launchApp(filtered.get(position));
            }
        });

        // 加载数字键绑定图标
        loadKeyBindingIcons();
    }

    /**
     * 加载数字键绑定应用的图标，显示在按键右下角。
     * 从 KeyBindingHelper 读取绑定包名，异步加载图标。
     */
    private void loadKeyBindingIcons() {
        int[] bindViewIds = {R.id.key_1_bind, R.id.key_2_bind, R.id.key_3_bind,
                R.id.key_4_bind, R.id.key_5_bind, R.id.key_6_bind,
                R.id.key_7_bind, R.id.key_8_bind, R.id.key_9_bind};
        for (int i = 0; i < bindViewIds.length; i++) {
            int digit = i + 1;
            ImageView bindView = keypad.findViewById(bindViewIds[i]);
            if (bindView == null) continue;

            String pkg = KeyBindingHelper.getBoundPackage(this, digit);
            if (pkg == null || pkg.isEmpty()) {
                bindView.setVisibility(View.GONE);
                continue;
            }

            // 异步加载图标
            final ImageView target = bindView;
            final int keyDigit = digit;
            io.execute(() -> {
                try {
                    android.graphics.drawable.Drawable icon =
                        getPackageManager().getApplicationIcon(pkg);
                    main.post(() -> {
                        target.setImageDrawable(icon);
                        target.setVisibility(View.VISIBLE);
                    });
                } catch (PackageManager.NameNotFoundException e) {
                    // 应用已卸载，清除失效绑定
                    KeyBindingHelper.unbind(MainActivity.this, keyDigit);
                    main.post(() -> target.setVisibility(View.GONE));
                }
            });
        }
    }

    /** 读取上滑触发距离设置（默认 60px） */
    private int getSwipeDistanceThreshold() {
        return Integer.parseInt(getSharedPreferences("settings", MODE_PRIVATE)
                .getString("swipe_distance", "60"));
    }

    /** 读取长按触发时长设置（默认 400ms） */
    private int getLongPressDuration() {
        return Integer.parseInt(getSharedPreferences("settings", MODE_PRIVATE)
                .getString("long_press_duration", "400"));
    }

    /**
     * 为单个按键设置手势：
     * - 单击（快速按下并释放）：T9 输入数字
     * - 长按（按住超过设定时长不移动）：启动该按键绑定的应用
     * - 上滑（按住并向上滑动超过设定距离）：启动搜索列表对应位置的应用
     *
     * 长按检测使用 Handler.postDelayed 手动管理，以便支持用户自定义时长。
     */
    private void setupKeyGesture(View key, int digit) {
        final int[] digitCopy = {digit};
        final boolean[] longPressFired = {false};
        final boolean[] tapFired = {false};
        final float[] startY = {0};
        final boolean[] hasMoved = {false};

        // 读取用户设置的灵敏度参数
        final int swipeThreshold = getSwipeDistanceThreshold();
        final int longPressTimeout = getLongPressDuration();

        final Handler handler = new Handler(Looper.getMainLooper());

        // 长按检测 Runnable
        final Runnable[] longPressRunnable = new Runnable[1];
        longPressRunnable[0] = () -> {
            if (!hasMoved[0]) {
                longPressFired[0] = true;
                onKeyLongPress(digitCopy[0]);
            }
        };

        key.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();

            if (action == android.view.MotionEvent.ACTION_DOWN) {
                startY[0] = event.getY();
                hasMoved[0] = false;
                longPressFired[0] = false;
                tapFired[0] = false;
                // 按下：高亮按键背景 + 文字变白
                v.setBackgroundColor(0xFF1976D2);
                setKeyTextColor(v, 0xFFFFFFFF);
                // 启动长按定时器
                handler.postDelayed(longPressRunnable[0], longPressTimeout);
                return true;
            }

            if (action == android.view.MotionEvent.ACTION_MOVE) {
                // 检测上滑：手指向上移动超过设定阈值（只触发一次）
                if (!hasMoved[0] && !longPressFired[0]) {
                    float dy = startY[0] - event.getY();
                    if (dy > swipeThreshold) {
                        hasMoved[0] = true;
                        handler.removeCallbacks(longPressRunnable[0]); // 取消长按
                        onKeySwipeUp(digitCopy[0]);
                    }
                }
                return true;
            }

            if (action == android.view.MotionEvent.ACTION_UP) {
                // 取消长按定时器
                handler.removeCallbacks(longPressRunnable[0]);
                // 抬起/取消：恢复默认背景 + 文字颜色
                v.setBackgroundResource(R.drawable.bg_t9_key);
                setKeyTextColor(v, getResources().getColor(R.color.t9_key_text, null));
                // 快速单击（未触发长按也未滑动）
                if (!longPressFired[0] && !hasMoved[0]) {
                    onDigitPressed(digitCopy[0]);
                }
                return true;
            }

            if (action == android.view.MotionEvent.ACTION_CANCEL) {
                handler.removeCallbacks(longPressRunnable[0]);
                v.setBackgroundResource(R.drawable.bg_t9_key);
                setKeyTextColor(v, getResources().getColor(R.color.t9_key_text, null));
                return true;
            }

            return false;
        });
    }

    /** 设置按键内所有文字的颜色（数字 + 字母） */
    private void setKeyTextColor(View key, int color) {
        if (key instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) key;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof TextView) {
                    ((TextView) child).setTextColor(color);
                }
            }
        }
    }

    /**
     * 长按数字键：启动该按键绑定的应用（在设置中绑定）。
     * 如果没有绑定应用，则不执行任何操作。
     */
    private void onKeyLongPress(int digit) {
        boolean launched = KeyBindingHelper.launchBoundApp(this, digit);
        if (!launched) {
            // 未绑定应用时，回退到启动搜索列表对应位置的应用
            launchAppAtDigit(digit);
        }
    }

    /**
     * 上滑数字键：启动搜索列表对应位置的应用。
     * digit 0-9 对应位置 9,0,1,2,...,8
     */
    private void onKeySwipeUp(int digit) {
        launchAppAtDigit(digit);
    }

    /** 根据数字键启动对应位置的应用（digit 0-9 对应位置 9,0,1,2,...,8） */
    private void launchAppAtDigit(int digit) {
        int position = digit == 0 ? 9 : digit - 1;
        if (position < filtered.size()) {
            launchApp(filtered.get(position));
        }
    }

    /** 角标现在由 Adapter 根据 position 自动显示，无需手动刷新 */

    private void onDigitPressed(int digit) {
        query.append(digit);
        onQueryChanged();
    }

    private void onBackspace() {
        if (query.length() > 0) {
            query.deleteCharAt(query.length() - 1);
            onQueryChanged();
        }
    }

    private void clearQuery() {
        query.setLength(0);
        onQueryChanged();
    }

    private void onQueryChanged() {
        t9Display.setText(query.toString());
        t9Hint.setText(buildHint());
        doFilter(); // 只过滤，不排序（allApps 已经排好序了）
        maybeAutoLaunch();
    }

    /** 当输入匹配到唯一一个应用时，自动启动 */
    private void maybeAutoLaunch() {
        if (filtered.size() == 1 && query.length() > 0) {
            launchApp(filtered.get(0));
        }
    }

    private String buildHint() {
        if (query.length() == 0) return "可以输入 '577' 来搜索 '计算器'";
        String q = query.toString();
        for (AppEntry e : allApps) {
            if (T9Matcher.matches(q, e.fingerprints)) {
                return "输入 '" + q + "' 可搜索 '" + e.label + "'";
            }
        }
        return "输入 '" + q + "' 暂无匹配";
    }

    /** 获取当前隐藏的应用包名集合 */
    private java.util.Set<String> getHiddenPackages() {
        return getSharedPreferences("settings", MODE_PRIVATE)
                .getStringSet("hidden_apps", new java.util.HashSet<>());
    }

    private void doFilter() {
        String q = query.toString();
        java.util.Set<String> hidden = getHiddenPackages();
        // 「最近搜索」叠加 T9 输入时需要按 包名/Activity 判断命中，提前构建历史键集合
        searchHistoryKeys = "最近搜索".equals(currentCategory) ? loadHistoryKeys() : null;
        filtered = new ArrayList<>();
        if (q.isEmpty() && currentCategory == null) {
            for (AppEntry e : allApps) {
                if (!hidden.contains(e.packageName)) filtered.add(e);
            }
        } else if (q.isEmpty() && "最近搜索".equals(currentCategory)) {
            filtered = buildRecentSearched(hidden);
        } else if (q.isEmpty() && "最近使用".equals(currentCategory)) {
            filtered = buildRecentlyUsed(hidden);
        } else if (q.isEmpty() && "最近安装".equals(currentCategory)) {
            filtered = buildRecentlyInstalled(hidden);
        } else {
            for (AppEntry e : allApps) {
                if (hidden.contains(e.packageName)) continue;
                boolean matchQuery = q.isEmpty() || T9Matcher.matches(q, e.fingerprints);
                boolean matchCat = currentCategory == null || matchCategory(e, currentCategory);
                if (matchQuery && matchCat) filtered.add(e);
            }
            // 有搜索词时按权重排序（使用频率 + 最近使用时间）
            if (!q.isEmpty()) {
                sortBySearchWeight(filtered, q);
            }
        }
        adapter.setHighlightQuery(q);
        adapter.submit(filtered);
        if (filtered.isEmpty()) {
            emptyHint.setText(emptyHintText());
            emptyHint.setVisibility(View.VISIBLE);
        } else {
            emptyHint.setVisibility(View.GONE);
        }
        recycler.setVisibility(filtered.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** 空态提示文案：按当前筛选模式区分 */
    private String emptyHintText() {
        if ("最近搜索".equals(currentCategory)) return "暂无搜索记录";
        if ("最近使用".equals(currentCategory)) return "暂无使用记录";
        if ("最近安装".equals(currentCategory)) return "最近没有新安装的应用";
        return "没有匹配的应用";
    }

    /** 「最近搜索」：按搜索时间倒序列出历史命中的应用（已卸载/已隐藏的自动跳过） */
    private List<AppEntry> buildRecentSearched(java.util.Set<String> hidden) {
        List<AppEntry> out = new ArrayList<>();
        for (SearchHistory.Entry h : SearchHistory.getAll(this)) {
            AppEntry e = findApp(h.packageName, h.activityName);
            if (e != null && !hidden.contains(e.packageName)) out.add(e);
        }
        return out;
    }

    /** 按 包名+Activity 在 allApps 中查找条目（微信快捷入口的 activityName 为空串） */
    private AppEntry findApp(String pkg, String act) {
        for (AppEntry e : allApps) {
            if (e.packageName.equals(pkg) && e.activityName.equals(act)) return e;
        }
        return null;
    }

    /** 「最近使用」：启动过的应用按最近启动时间倒序 */
    private List<AppEntry> buildRecentlyUsed(java.util.Set<String> hidden) {
        List<AppEntry> out = new ArrayList<>();
        for (AppEntry e : allApps) {
            if (!hidden.contains(e.packageName)
                    && lastLaunchTimeCache.getOrDefault(e.packageName, 0L) > 0) {
                out.add(e);
            }
        }
        out.sort((a, b) -> Long.compare(
                lastLaunchTimeCache.getOrDefault(b.packageName, 0L),
                lastLaunchTimeCache.getOrDefault(a.packageName, 0L)));
        return out;
    }

    /** 「最近安装」：recent_time_range 范围内安装的应用，按安装时间倒序 */
    private List<AppEntry> buildRecentlyInstalled(java.util.Set<String> hidden) {
        long now = System.currentTimeMillis();
        long range = getRecentTimeRange();
        List<AppEntry> out = new ArrayList<>();
        for (AppEntry e : allApps) {
            long t = installTimeCache.getOrDefault(e.packageName, 0L);
            if (!hidden.contains(e.packageName) && t > 0 && now - t <= range) {
                out.add(e);
            }
        }
        out.sort((a, b) -> Long.compare(
                installTimeCache.getOrDefault(b.packageName, 0L),
                installTimeCache.getOrDefault(a.packageName, 0L)));
        return out;
    }

    /** 读取最近更新范围设置（毫秒），默认 7 天，与 AppLoader 一致 */
    private long getRecentTimeRange() {
        try {
            return Long.parseLong(getSharedPreferences("settings", MODE_PRIVATE)
                    .getString("recent_time_range", "604800000"));
        } catch (NumberFormatException e) {
            return 604800000L;
        }
    }

    /** 构建搜索历史的 包名/Activity 键集合（供 matchCategory 用） */
    private java.util.Set<String> loadHistoryKeys() {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (SearchHistory.Entry h : SearchHistory.getAll(this)) {
            keys.add(h.packageName + "/" + h.activityName);
        }
        return keys;
    }

    /** 搜索权重排序：综合使用频率、最近使用时间、是否精确匹配 */
    private void sortBySearchWeight(List<AppEntry> list, String query) {
        long now = System.currentTimeMillis();
        final long ONE_DAY = 24 * 60 * 60 * 1000L;
        final String lowerQuery = query.toLowerCase();

        // 先为每个应用计算一次分数，比较器只查表，避免排序过程中 O(n log n) 次重复计算
        final java.util.Map<AppEntry, Integer> scores =
                new java.util.IdentityHashMap<>(list.size() * 2);
        for (AppEntry e : list) {
            scores.put(e, calcSearchScore(e, lowerQuery, now, ONE_DAY));
        }
        java.util.Collections.sort(list, (a, b) ->
                Integer.compare(scores.get(b), scores.get(a))); // 降序
    }

    /** 计算搜索权重分数（lowerQuery 为已转小写的搜索词） */
    private int calcSearchScore(AppEntry e, String lowerQuery, long now, long oneDay) {
        int score = 0;

        // 1. 使用频率权重（最高 100 分）
        int launchCount = launchCountCache.getOrDefault(e.packageName, 0);
        score += Math.min(launchCount, 50) * 2; // 最多 100 分

        // 2. 最近使用时间权重（最高 80 分）
        long lastLaunch = lastLaunchTimeCache.getOrDefault(e.packageName, 0L);
        if (lastLaunch > 0) {
            long daysAgo = (now - lastLaunch) / oneDay;
            if (daysAgo == 0) score += 80;      // 今天使用过
            else if (daysAgo <= 1) score += 60; // 昨天
            else if (daysAgo <= 3) score += 40; // 3 天内
            else if (daysAgo <= 7) score += 20; // 一周内
        }

        // 3. 精确匹配加分（最高 50 分）
        String lowerLabel = e.label.toLowerCase();
        if (lowerLabel.startsWith(lowerQuery)) score += 50; // 开头匹配
        else if (lowerLabel.contains(lowerQuery)) score += 30; // 包含匹配

        // 4. 最近更新加分
        if (e.recentlyUpdated) score += 10;

        return score;
    }

    private boolean matchCategory(AppEntry e, String cat) {
        String pkg = e.packageName.toLowerCase();
        String label = e.label.toLowerCase();
        switch (cat) {
            case "社交": return containsAny(pkg, "com.tencent.mm", "com.sina.weibo")
                    || containsAny(label, "微信", "微博", "qq", "钉钉", "飞书");
            case "影音": return containsAny(pkg, "com.ss.android.ugc.aweme", "com.netease.cloudmusic")
                    || containsAny(label, "抖音", "音乐", "视频", "哔哩");
            case "交通出行": return containsAny(pkg, "com.sdu.didi.psnger", "com.autonavi.minimap")
                    || containsAny(label, "地图", "滴滴", "导航");
            case "实用工具": return containsAny(label, "计算器", "设置", "日历", "时钟");
            case "游戏": return pkg.contains("game") || containsAny(label, "游戏", "斗地主");
            case "购物": return containsAny(pkg, "com.taobao", "com.jingdong", "com.xunmeng")
                    || containsAny(label, "淘宝", "京东", "拼多多");
            case "理财": return containsAny(label, "银行", "支付宝", "股票");
            case "最近搜索": {
                if (searchHistoryKeys == null) searchHistoryKeys = loadHistoryKeys();
                return searchHistoryKeys.contains(e.packageName + "/" + e.activityName);
            }
            case "最近使用": return lastLaunchTimeCache.getOrDefault(e.packageName, 0L) > 0;
            case "最近安装": {
                long t = installTimeCache.getOrDefault(e.packageName, 0L);
                return t > 0 && System.currentTimeMillis() - t <= getRecentTimeRange();
            }
            default: return true;
        }
    }

    private boolean containsAny(String s, String... keywords) {
        for (String k : keywords) if (s.contains(k)) return true;
        return false;
    }

    private void launchApp(AppEntry entry) {
        recordLaunch(entry.packageName); // 记录启动次数（用于使用频率排序）
        // 记录搜索历史（仅在有搜索词时；T9 自动启动和手动点击都经过这里）
        if (query.length() > 0) {
            SearchHistory.record(this, query.toString(), entry.packageName, entry.activityName);
        }
        try {
            if (entry.launchIntent != null) {
                startActivity(entry.launchIntent);
            } else {
                Intent intent = new Intent();
                intent.setClassName(entry.packageName, entry.activityName);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
            // 启动成功后清空输入并刷新列表（恢复全量显示）
            query.setLength(0);
            onQueryChanged();
        } catch (Throwable t) {
            Toast.makeText(this, "无法启动 " + entry.label, Toast.LENGTH_SHORT).show();
            // 启动失败也要清空输入并恢复列表
            query.setLength(0);
            onQueryChanged();
        }
    }

    private boolean showAppMenu(AppEntry entry, View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "打开");
        popup.getMenu().add(0, 2, 1, "应用信息");
        popup.getMenu().add(0, 3, 2, "卸载");
        popup.getMenu().add(0, 4, 3, "隐藏");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: launchApp(entry); return true;
                case 2: showAppDetails(entry); return true;
                case 3: uninstallApp(entry); return true;
                case 4: hideApp(entry); return true;
            }
            return false;
        });
        popup.show();
        return true;
    }

    /** 隐藏指定应用：写入 SharedPreferences 并从列表中移除 */
    private void hideApp(AppEntry entry) {
        SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        java.util.Set<String> hidden = new java.util.HashSet<>(
                sp.getStringSet("hidden_apps", new java.util.HashSet<>()));
        hidden.add(entry.packageName);
        sp.edit().putStringSet("hidden_apps", hidden).apply();
        Toast.makeText(this, "已隐藏 " + entry.label, Toast.LENGTH_SHORT).show();
        // 从 allApps 中移除并刷新列表
        allApps.removeIf(e -> e.packageName.equals(entry.packageName));
        doFilter();
    }

    private void uninstallApp(AppEntry entry) {
        try {
            startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + entry.packageName)));
        } catch (Throwable t) {
            Toast.makeText(this, "卸载失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void showAppDetails(AppEntry entry) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + entry.packageName)));
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开详情", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareApp(AppEntry entry) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, "推荐应用：" + entry.label);
        startActivity(Intent.createChooser(send, "分享"));
    }

    private void showSortMenu() {
        String[] sortOptions = {"智能排序", "字母顺序", "最近安装", "使用频率"};
        String currentSort = getSharedPreferences("settings", MODE_PRIVATE)
                .getString("sort_mode", "智能排序");
        int checked = 0;
        for (int i = 0; i < sortOptions.length; i++) {
            if (sortOptions[i].equals(currentSort)) { checked = i; break; }
        }

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("排序方式")
                .setSingleChoiceItems(sortOptions, checked, (dialog, which) -> {
                    String selected = sortOptions[which];
                    sortLabel.setText(selected);
                    getSharedPreferences("settings", MODE_PRIVATE)
                            .edit().putString("sort_mode", selected).apply();
                    sortAndFilter(); // 执行排序
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 安装时间缓存（避免排序时重复调用 getPackageInfo） */
    private java.util.Map<String, Long> installTimeCache = new java.util.HashMap<>();
    /** 启动次数缓存 */
    private java.util.Map<String, Integer> launchCountCache = new java.util.HashMap<>();
    /** 最近启动时间缓存 */
    private java.util.Map<String, Long> lastLaunchTimeCache = new java.util.HashMap<>();

    /** 预加载排序相关数据到内存缓存（后台线程调用；必须传入待排序的列表，而不是 allApps） */
    private void preloadSortData(List<AppEntry> list) {
        installTimeCache.clear();
        launchCountCache.clear();
        lastLaunchTimeCache.clear();
        SharedPreferences countSp = getSharedPreferences("app_launch_count", MODE_PRIVATE);
        SharedPreferences timeSp = getSharedPreferences("app_launch_time", MODE_PRIVATE);
        for (AppEntry e : list) {
            try {
                installTimeCache.put(e.packageName,
                        getPackageManager().getPackageInfo(e.packageName, 0).firstInstallTime);
            } catch (Throwable ignored) {
                installTimeCache.put(e.packageName, 0L);
            }
            launchCountCache.put(e.packageName, countSp.getInt(e.packageName, 0));
            lastLaunchTimeCache.put(e.packageName, timeSp.getLong(e.packageName, 0L));
        }
    }

    /** 对指定列表排序（必须在预加载数据后调用） */
    private void sortAllApps(List<AppEntry> list) {
        String sortMode = getSharedPreferences("settings", MODE_PRIVATE)
                .getString("sort_mode", "智能排序");
        switch (sortMode) {
            case "字母顺序":
                java.util.Collections.sort(list, (a, b) ->
                        a.label.compareToIgnoreCase(b.label));
                break;
            case "最近安装":
                java.util.Collections.sort(list, (a, b) ->
                        Long.compare(installTimeCache.getOrDefault(b.packageName, 0L),
                                installTimeCache.getOrDefault(a.packageName, 0L)));
                break;
            case "使用频率":
                java.util.Collections.sort(list, (a, b) ->
                        Integer.compare(launchCountCache.getOrDefault(b.packageName, 0),
                                launchCountCache.getOrDefault(a.packageName, 0)));
                break;
            default: // 智能排序：启动过的应用按频率降序排前面，未启动的按字母排序
                java.util.Collections.sort(list, (a, b) -> {
                    int countA = launchCountCache.getOrDefault(a.packageName, 0);
                    int countB = launchCountCache.getOrDefault(b.packageName, 0);
                    if (countA > 0 && countB > 0) return Integer.compare(countB, countA); // 都启动过，按频率
                    if (countA > 0) return -1;  // a 启动过，排前面
                    if (countB > 0) return 1;   // b 启动过，排前面
                    return a.label.compareToIgnoreCase(b.label); // 都没启动，按字母
                });
                break;
        }
    }

    /** 记录应用启动（次数 + 时间） */
    private void recordLaunch(String pkg) {
        SharedPreferences countSp = getSharedPreferences("app_launch_count", MODE_PRIVATE);
        int count = countSp.getInt(pkg, 0) + 1;
        countSp.edit().putInt(pkg, count).apply();
        launchCountCache.put(pkg, count);

        SharedPreferences timeSp = getSharedPreferences("app_launch_time", MODE_PRIVATE);
        long now = System.currentTimeMillis();
        timeSp.edit().putLong(pkg, now).apply();
        lastLaunchTimeCache.put(pkg, now);
    }

    /** 排序并刷新列表（仅在排序模式改变时调用） */
    private void sortAndFilter() {
        io.execute(() -> {
            // 在快照上排序，排好后再切回主线程替换 allApps，避免与主线程并发读写同一列表
            List<AppEntry> snapshot = new ArrayList<>(allApps);
            preloadSortData(snapshot); // 预加载排序数据
            sortAllApps(snapshot);
            main.post(() -> {
                allApps = snapshot;
                doFilter();
            });
        });
    }

    /** 获取当前列数（默认 3） */
    private int getColumnCount() {
        // 优先读取新 key（string），兼容旧 key（int）
        SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        if (sp.contains("columns")) {
            return Integer.parseInt(sp.getString("columns", "3"));
        }
        return sp.getInt("column_count", 3);
    }

    /** 应用窗口大小设置 */
    private void applyWindowSize() {
        String size = getSharedPreferences("settings", MODE_PRIVATE)
                .getString("window_size", "full");
        if (!"full".equals(size)) {
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int w, h;
            if ("small".equals(size)) {
                w = (int) (screenW * 0.5);
                h = (int) (screenH * 0.5);
            } else { // medium
                w = (int) (screenW * 0.75);
                h = (int) (screenH * 0.75);
            }
            // 使用 setLayout 更可靠地设置窗口大小
            getWindow().setLayout(w, h);
            getWindow().setGravity(android.view.Gravity.CENTER);
        }
    }

    /** 应用背景颜色 */
    private void applyBackgroundColor() {
        String color = getSharedPreferences("settings", MODE_PRIVATE)
                .getString("background_color", "");
        if (!color.isEmpty()) {
            try {
                findViewById(R.id.app_list).setBackgroundColor(Color.parseColor(color));
            } catch (Exception ignored) {}
        }
    }

    /** 应用字体颜色 */
    private void applyFontColor() {
        String color = getSharedPreferences("settings", MODE_PRIVATE)
                .getString("font_color", "");
        if (!color.isEmpty()) {
            try {
                adapter.setFontColor(Color.parseColor(color));
            } catch (Exception ignored) {}
        }
    }

    private void setupCategoryChips() {
        View chipContainer = findViewById(R.id.category_bar);
        for (int i = 0; i < ((LinearLayout) chipContainer).getChildCount(); i++) {
            View chip = ((LinearLayout) chipContainer).getChildAt(i);
            chip.setOnClickListener(v -> {
                boolean wasSelected = v.isSelected();
                for (int j = 0; j < ((LinearLayout) chipContainer).getChildCount(); j++) {
                    ((LinearLayout) chipContainer).getChildAt(j).setSelected(false);
                }
                if (wasSelected) {
                    currentCategory = null;
                } else {
                    v.setSelected(true);
                    currentCategory = ((TextView) v).getText().toString();
                }
                updateStickyBarSelection();
                doFilter();
            });
            // 长按「最近搜索」chip：清空搜索历史
            if (chip instanceof TextView && "最近搜索".equals(((TextView) chip).getText().toString())) {
                chip.setOnLongClickListener(v -> {
                    new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle("清空搜索历史")
                            .setMessage("确定清空全部搜索历史吗？")
                            .setPositiveButton("清空", (d, w) -> {
                                SearchHistory.clear(MainActivity.this);
                                if ("最近搜索".equals(currentCategory)) doFilter();
                                Toast.makeText(MainActivity.this, "搜索历史已清空", Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                    return true;
                });
            }
        }
    }

    /**
     * 设置左右滑动切换分类标签：
     * 在 RecyclerView 上检测水平滑动手势，左滑切换到下一个标签，右滑切换到上一个标签。
     */
    private void setupSwipeToSwitchCategory() {
        GestureDetector gestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    private static final int SWIPE_THRESHOLD = 80;
                    private static final int SWIPE_VELOCITY_THRESHOLD = 100;

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float velocityX, float velocityY) {
                        if (e1 == null || e2 == null) return false;
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        // 水平滑动且速度足够，垂直位移小于水平位移（避免误触滚动）
                        if (Math.abs(dx) > SWIPE_THRESHOLD
                                && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD
                                && Math.abs(dx) > Math.abs(dy)) {
                            if (dx < 0) {
                                switchToNextCategory(); // 左滑 → 下一个
                            } else {
                                switchToPreviousCategory(); // 右滑 → 上一个
                            }
                            return true;
                        }
                        return false;
                    }
                });

        recycler.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
                gestureDetector.onTouchEvent(e);
                return false; // 不拦截，让 RecyclerView 正常处理滚动
            }

            @Override
            public void onTouchEvent(RecyclerView rv, MotionEvent e) {
                gestureDetector.onTouchEvent(e);
            }

            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {}
        });
    }

    /** 切换到下一个分类标签 */
    private void switchToNextCategory() {
        int currentIndex = getCurrentCategoryIndex();
        int nextIndex = (currentIndex + 1) % categoryList.length;
        applyCategory(categoryList[nextIndex]);
    }

    /** 切换到上一个分类标签 */
    private void switchToPreviousCategory() {
        int currentIndex = getCurrentCategoryIndex();
        int prevIndex = (currentIndex - 1 + categoryList.length) % categoryList.length;
        applyCategory(categoryList[prevIndex]);
    }

    /** 获取当前分类在列表中的索引，未选中返回 -1 */
    private int getCurrentCategoryIndex() {
        if (currentCategory == null) return -1;
        for (int i = 0; i < categoryList.length; i++) {
            if (categoryList[i].equals(currentCategory)) return i;
        }
        return -1;
    }

    /** 应用指定分类（更新 chip 选中状态并过滤） */
    private void applyCategory(String category) {
        currentCategory = category;
        // 更新主标签栏选中状态
        View chipContainer = findViewById(R.id.category_bar);
        for (int i = 0; i < ((LinearLayout) chipContainer).getChildCount(); i++) {
            View chip = ((LinearLayout) chipContainer).getChildAt(i);
            chip.setSelected(category.equals(((TextView) chip).getText().toString()));
        }
        // 更新悬浮标签栏选中状态
        updateStickyBarSelection();
        doFilter();
    }

    /**
     * 设置悬浮吸顶标签栏：
     * 复制主标签栏的结构，监听 RecyclerView 滚动，超过阈值时显示，回到顶部时隐藏。
     */
    private void setupStickyCategoryBar() {
        LinearLayout stickyChips = findViewById(R.id.sticky_category_chips);
        View mainBar = findViewById(R.id.category_bar);

        // 复制主标签栏的 chip 到悬浮栏
        for (int i = 0; i < ((LinearLayout) mainBar).getChildCount(); i++) {
            View mainChip = ((LinearLayout) mainBar).getChildAt(i);
            TextView stickyChip = new TextView(this);
            // 复制文字和样式属性
            stickyChip.setText(((TextView) mainChip).getText());
            stickyChip.setTextColor(getResources().getColorStateList(R.color.category_chip_text, null));
            stickyChip.setTextSize(12f);
            stickyChip.setGravity(android.view.Gravity.CENTER);
            stickyChip.setBackgroundResource(R.drawable.bg_category_chip);
            stickyChip.setClickable(true);
            stickyChip.setFocusable(true);
            // 设置内边距（与 CategoryChip 样式一致）
            int paddingH = (int) (12 * getResources().getDisplayMetrics().density);
            int paddingV = (int) (7 * getResources().getDisplayMetrics().density);
            stickyChip.setPadding(paddingH, paddingV, paddingH, paddingV);
            // 设置布局参数（高度 28dp，右边距 6dp）
            int height = (int) (28 * getResources().getDisplayMetrics().density);
            int margin = (int) (6 * getResources().getDisplayMetrics().density);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, height);
            lp.setMargins(0, 0, margin, 0);
            stickyChip.setLayoutParams(lp);
            stickyChip.setSelected(mainChip.isSelected());

            // 点击事件：同步到主标签栏
            final String category = ((TextView) mainChip).getText().toString();
            stickyChip.setOnClickListener(v -> applyCategory(category));

            stickyChips.addView(stickyChip);
        }

        // 监听滚动，控制悬浮栏显示/隐藏
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                int scrollOffset = recyclerView.computeVerticalScrollOffset();
                HorizontalScrollView stickyBar = findViewById(R.id.sticky_category_bar);
                if (scrollOffset > STICKY_BAR_SCROLL_THRESHOLD) {
                    stickyBar.setVisibility(View.VISIBLE);
                } else {
                    stickyBar.setVisibility(View.GONE);
                }
            }
        });
    }

    /** 更新悬浮标签栏的选中状态 */
    private void updateStickyBarSelection() {
        LinearLayout stickyChips = findViewById(R.id.sticky_category_chips);
        if (stickyChips == null) return;
        for (int i = 0; i < stickyChips.getChildCount(); i++) {
            View chip = stickyChips.getChildAt(i);
            if (chip instanceof TextView) {
                chip.setSelected(currentCategory != null
                        && currentCategory.equals(((TextView) chip).getText().toString()));
            }
        }
    }

    /**
     * 启动加载：
     * 1. 从二进制缓存快速加载（含图标）→ 预加载排序数据 → 排序 → 立即显示
     * 2. 后台扫描最新应用列表 → 排序 → 更新缓存和 UI
     */
    private void loadAppsAsync() {
        View loadingOverlay = findViewById(R.id.loading_overlay);
        if (loadingOverlay != null) loadingOverlay.setVisibility(View.VISIBLE);

        io.execute(() -> {
            // 1. 从二进制缓存快速加载（含图标数据）
            final List<AppEntry> cached = FastCache.load(this);

            if (cached != null && !cached.isEmpty()) {
                // 补齐启动 Intent + 预加载排序数据 + 排序（都在后台线程）
                for (AppEntry e : cached) {
                    if (e.launchIntent == null) {
                        try {
                            Intent intent = getPackageManager().getLaunchIntentForPackage(e.packageName);
                            if (intent != null) {
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                e.launchIntent = intent;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                preloadSortData(cached); // 预加载安装时间和启动次数
                sortAllApps(cached);     // 排序

                // 显示已排序的缓存列表（排除隐藏应用）
                java.util.Set<String> hidden = getHiddenPackages();
                cached.removeIf(e -> hidden.contains(e.packageName));
                main.post(() -> {
                    allApps = cached;
                    doFilter();
                    if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
                });
            }

            // 2. 后台扫描最新应用列表
            final List<AppEntry> loaded = AppLoader.loadLaunchableApps(MainActivity.this);

            // 3. 预加载图标 + 排序数据 + 排序
            final List<String> packages = new java.util.ArrayList<>();
            for (AppEntry e : loaded) packages.add(e.packageName);
            final List<AppEntry> finalLoaded = loaded;
            // IconCache.preloadAll 的回调在后台线程执行，这里的重活不会阻塞主线程
            IconCache.preloadAll(MainActivity.this, packages, () -> {
                FastCache.save(MainActivity.this, finalLoaded); // 保存到二进制缓存
                preloadSortData(finalLoaded); // 重新预加载排序数据
                sortAllApps(finalLoaded);     // 重新排序

                // 更新 UI（排除隐藏应用）
                java.util.Set<String> hidden2 = getHiddenPackages();
                finalLoaded.removeIf(e -> hidden2.contains(e.packageName));
                main.post(() -> {
                    allApps = finalLoaded;
                    doFilter();
                    if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
                    main.removeCallbacks(periodicRefresh);
                    main.postDelayed(periodicRefresh, REFRESH_INTERVAL_MS);
                });
            });
        });
    }

    /** 定时刷新：直接全量扫描（不读缓存），完成后写缓存并刷新 UI */
    private void refreshAppsFullScan() {
        io.execute(() -> {
            final List<AppEntry> loaded = AppLoader.loadLaunchableApps(this);
            // 排除隐藏应用
            java.util.Set<String> hidden = getHiddenPackages();
            loaded.removeIf(e -> hidden.contains(e.packageName));
            main.post(() -> {
                allApps = loaded;
                doFilter();
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 角标由 Adapter 根据 position 自动显示，无需手动刷新
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacks(periodicRefresh);
        io.shutdownNow();
    }
}
