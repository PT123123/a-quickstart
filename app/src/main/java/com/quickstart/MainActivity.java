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
        // ✕ 单击=删除一位，长按=清空
        View btnClear = findViewById(R.id.btn_clear_top);
        btnClear.setOnClickListener(v -> onBackspace());
        btnClear.setOnLongClickListener(v -> { clearQuery(); return true; });

        // ⚙ 设置按钮
        findViewById(R.id.btn_settings_top).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        // ⌄ 收缩/展开键盘
        View toggleKeypad = findViewById(R.id.btn_toggle_keypad);
        toggleKeypad.setOnClickListener(v -> {
            if (keypad.getVisibility() == View.VISIBLE) {
                keypad.setVisibility(View.GONE);
                ((TextView) v).setText("⌃"); // 展开图标
            } else {
                keypad.setVisibility(View.VISIBLE);
                ((TextView) v).setText("⌄"); // 收起图标
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
    }

    /**
     * 为单个按键设置手势：
     * - 单击（快速按下并释放）：T9 输入数字
     * - 长按（按住超过 400ms 不移动）：如果手势设为"长按"则启动绑定应用
     * - 上滑（按住并向上滑动超过 40px）：如果手势设为"上滑"则启动绑定应用
     *
     * 关键：上滑和单击通过延时区分 — 手指按下后等待 200ms，
     * 如果手指移动了则进入手势模式（取消单击），否则触发单击输入。
     */
    private void setupKeyGesture(View key, int digit) {
        final int[] digitCopy = {digit};
        final boolean[] longPressFired = {false};
        final float[] startY = {0};
        final boolean[] hasMoved = {false};

        // 使用 GestureDetector 处理长按和单击
        final android.view.GestureDetector detector = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(android.view.MotionEvent e) {
                        startY[0] = e.getY();
                        hasMoved[0] = false;
                        longPressFired[0] = false;
                        return true;
                    }

                    @Override
                    public boolean onSingleTapUp(android.view.MotionEvent e) {
                        // 快速单击：T9 输入
                        if (!longPressFired[0] && !hasMoved[0]) {
                            onDigitPressed(digitCopy[0]);
                        }
                        return true;
                    }

                    @Override
                    public void onLongPress(android.view.MotionEvent e) {
                        // 长按：根据全局设置决定是否启动应用
                        longPressFired[0] = true;
                        onKeyLongPress(digitCopy[0]);
                    }

                    @Override
                    public boolean onScroll(android.view.MotionEvent e1, android.view.MotionEvent e2,
                            float distanceX, float distanceY) {
                        // 检测上滑：手指向上移动
                        float dy = e1.getY() - e2.getY();
                        if (dy > 40 && !longPressFired[0]) {
                            hasMoved[0] = true;
                            onKeySwipeUp(digitCopy[0]);
                            return true;
                        }
                        return false;
                    }
                });

        key.setOnTouchListener((v, event) -> detector.onTouchEvent(event));
    }

    /**
     * 长按数字键：根据全局手势设置决定是否启动对应位置的应用。
     * - 全局设为"长按"时，长按启动位置 N 的应用（N=digit-1，0对应位置9）
     * - 全局设为"上滑"时，长按不启动（由上滑启动）
     */
    private void onKeyLongPress(int digit) {
        if (!isKeyLaunchLongPress()) return;
        launchAppAtDigit(digit);
    }

    /**
     * 上滑数字键：根据全局手势设置决定是否启动对应位置的应用。
     * - 全局设为"上滑"时，上滑启动位置 N 的应用
     * - 全局设为"长按"时，上滑不启动（由长按启动）
     */
    private void onKeySwipeUp(int digit) {
        if (isKeyLaunchLongPress()) return;
        launchAppAtDigit(digit);
    }

    /** 根据数字键启动对应位置的应用（digit 0-9 对应位置 9,0,1,2,...,8） */
    private void launchAppAtDigit(int digit) {
        int position = digit == 0 ? 9 : digit - 1;
        if (position < filtered.size()) {
            launchApp(filtered.get(position));
        }
    }

    /** 数字键是否用长按启动（读取全局设置） */
    private boolean isKeyLaunchLongPress() {
        return "long_press".equals(getSharedPreferences("settings", MODE_PRIVATE)
                .getString("key_gesture", "long_press"));
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

    private void doFilter() {
        String q = query.toString();
        filtered = new ArrayList<>();
        if (q.isEmpty() && currentCategory == null) {
            filtered.addAll(allApps);
        } else {
            for (AppEntry e : allApps) {
                boolean matchQuery = q.isEmpty() || T9Matcher.matches(q, e.fingerprints);
                boolean matchCat = currentCategory == null || matchCategory(e, currentCategory);
                if (matchQuery && matchCat) filtered.add(e);
            }
        }
        adapter.setHighlightQuery(q);
        adapter.submit(filtered);
        emptyHint.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
        recycler.setVisibility(filtered.isEmpty() ? View.GONE : View.VISIBLE);
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
        } catch (Throwable t) {
            Toast.makeText(this, "无法启动 " + entry.label, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean showAppMenu(AppEntry entry, View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "卸载");
        popup.getMenu().add(0, 2, 1, "应用详情");
        popup.getMenu().add(0, 3, 2, "分享给朋友");
        popup.getMenu().add(0, 4, 3, "收藏");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: uninstallApp(entry); return true;
                case 2: showAppDetails(entry); return true;
                case 3: shareApp(entry); return true;
                case 4: Toast.makeText(this, "已收藏 " + entry.label, Toast.LENGTH_SHORT).show(); return true;
            }
            return false;
        });
        popup.show();
        return true;
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

    /** 预加载安装时间和启动次数到内存缓存（后台线程调用） */
    private void preloadSortData() {
        installTimeCache.clear();
        launchCountCache.clear();
        SharedPreferences countSp = getSharedPreferences("app_launch_count", MODE_PRIVATE);
        for (AppEntry e : allApps) {
            try {
                installTimeCache.put(e.packageName,
                        getPackageManager().getPackageInfo(e.packageName, 0).firstInstallTime);
            } catch (Throwable ignored) {
                installTimeCache.put(e.packageName, 0L);
            }
            launchCountCache.put(e.packageName, countSp.getInt(e.packageName, 0));
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
            default: // 智能排序：最近更新的在前，然后按字母
                java.util.Collections.sort(allApps, (a, b) -> {
                    if (a.recentlyUpdated != b.recentlyUpdated) return a.recentlyUpdated ? -1 : 1;
                    return a.label.compareToIgnoreCase(b.label);
                });
                break;
        }
    }

    /** 记录应用启动 */
    private void recordLaunch(String pkg) {
        SharedPreferences sp = getSharedPreferences("app_launch_count", MODE_PRIVATE);
        int count = sp.getInt(pkg, 0) + 1;
        sp.edit().putInt(pkg, count).apply();
        launchCountCache.put(pkg, count); // 同步更新缓存
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
        return getSharedPreferences("settings", MODE_PRIVATE)
                .getInt("column_count", 3);
    }

    /** 设置列数并刷新列表 */
    private void setColumnCount(int count) {
        getSharedPreferences("settings", MODE_PRIVATE)
                .edit().putInt("column_count", count).apply();
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

                // 显示已排序的缓存列表
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

                // 更新 UI
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
