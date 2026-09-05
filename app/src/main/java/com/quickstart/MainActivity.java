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
import android.view.View;
import android.view.WindowManager;
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
import com.quickstart.util.AppCache;
import com.quickstart.util.AppLoader;
import com.quickstart.util.FastCache;
import com.quickstart.util.IconCache;
import com.quickstart.util.KeyBindingHelper;
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

    private static final String[] KEY_LETTERS = {
        "A", "", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyWindowSize();
        applyBackgroundColor();
        applyFontColor();

        sortLabel  = findViewById(R.id.sort_label);
        t9Hint     = findViewById(R.id.t9_hint);
        t9Display  = findViewById(R.id.t9_display);
        emptyHint  = findViewById(R.id.empty_hint);
        recycler   = findViewById(R.id.app_list);
        keypad     = findViewById(R.id.keypad);

        // 加载状态遮罩
        View loadingOverlay = findViewById(R.id.loading_overlay);

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
        filtered = new ArrayList<>();
        if (q.isEmpty() && currentCategory == null) {
            for (AppEntry e : allApps) {
                if (!hidden.contains(e.packageName)) filtered.add(e);
            }
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
        emptyHint.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        recycler.setVisibility(filtered.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** 搜索权重排序：综合使用频率、最近使用时间、是否精确匹配 */
    private void sortBySearchWeight(List<AppEntry> list, String query) {
        long now = System.currentTimeMillis();
        final long ONE_DAY = 24 * 60 * 60 * 1000L;

        java.util.Collections.sort(list, (a, b) -> {
            int scoreA = calcSearchScore(a, query, now, ONE_DAY);
            int scoreB = calcSearchScore(b, query, now, ONE_DAY);
            return Integer.compare(scoreB, scoreA); // 降序
        });
    }

    /** 计算搜索权重分数 */
    private int calcSearchScore(AppEntry e, String query, long now, long oneDay) {
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
        String lowerQuery = query.toLowerCase();
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
            default: return true;
        }
    }

    private boolean containsAny(String s, String... keywords) {
        for (String k : keywords) if (s.contains(k)) return true;
        return false;
    }

    private void launchApp(AppEntry entry) {
        recordLaunch(entry.packageName); // 记录启动次数（用于使用频率排序）
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

    /** 预加载排序相关数据到内存缓存（后台线程调用） */
    private void preloadSortData() {
        installTimeCache.clear();
        launchCountCache.clear();
        lastLaunchTimeCache.clear();
        SharedPreferences countSp = getSharedPreferences("app_launch_count", MODE_PRIVATE);
        SharedPreferences timeSp = getSharedPreferences("app_launch_time", MODE_PRIVATE);
        for (AppEntry e : allApps) {
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

    /** 对 allApps 排序（必须在预加载数据后调用） */
    private void sortAllApps() {
        String sortMode = getSharedPreferences("settings", MODE_PRIVATE)
                .getString("sort_mode", "智能排序");
        switch (sortMode) {
            case "字母顺序":
                java.util.Collections.sort(allApps, (a, b) ->
                        a.label.compareToIgnoreCase(b.label));
                break;
            case "最近安装":
                java.util.Collections.sort(allApps, (a, b) ->
                        Long.compare(installTimeCache.getOrDefault(b.packageName, 0L),
                                installTimeCache.getOrDefault(a.packageName, 0L)));
                break;
            case "使用频率":
                java.util.Collections.sort(allApps, (a, b) ->
                        Integer.compare(launchCountCache.getOrDefault(b.packageName, 0),
                                launchCountCache.getOrDefault(a.packageName, 0)));
                break;
            default: // 智能排序：启动过的应用按频率降序排前面，未启动的按字母排序
                java.util.Collections.sort(allApps, (a, b) -> {
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
            preloadSortData(); // 预加载排序数据
            sortAllApps();
            main.post(this::doFilter);
        });
    }

    /** 切换深色/浅色主题 */
    private void toggleTheme() {
        int current = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode();
        if (current == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO);
        } else {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
        }
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

    /** 设置列数并刷新列表 */
    public void setColumnCount(int count) {
        getSharedPreferences("settings", MODE_PRIVATE)
                .edit().putString("columns", String.valueOf(count)).apply();
        GridLayoutManager layoutManager = (GridLayoutManager) recycler.getLayoutManager();
        if (layoutManager != null) {
            layoutManager.setSpanCount(count);
        }
        adapter.setColumnCount(count);
    }

    /** 弹出列数选择对话框 */
    private void showColumnCountDialog() {
        int current = getColumnCount();
        String[] options = {"2 列", "3 列", "4 列", "5 列"};
        int[] values = {2, 3, 4, 5};
        int checked = 1; // default 3
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) checked = i;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("设置列数")
                .setSingleChoiceItems(options, checked, (dialog, which) -> {
                    setColumnCount(values[which]);
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
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
                doFilter();
            });
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
                preloadSortData(); // 预加载安装时间和启动次数
                sortAllApps();     // 排序

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
            IconCache.preloadAll(MainActivity.this, packages, () -> {
                FastCache.save(MainActivity.this, finalLoaded); // 保存到二进制缓存
                preloadSortData(); // 重新预加载排序数据
                sortAllApps();     // 重新排序

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
            AppCache.save(this, loaded);
            main.post(() -> {
                allApps = loaded;
                doFilter();
            });
        });
    }

    /** 为缓存条目补齐启动 Intent + 占位图标（轻量操作，不触发图标/拼音重算） */
    private void fillCachedEntries(List<AppEntry> list) {
        PackageManager pm = getPackageManager();
        for (AppEntry e : list) {
            try {
                Intent intent = pm.getLaunchIntentForPackage(e.packageName);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    e.launchIntent = intent;
                }
            } catch (Throwable ignored) {}
            if (e.icon == null) {
                try {
                    e.icon = pm.getDefaultActivityIcon();
                } catch (Throwable ignored) {}
            }
        }
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
