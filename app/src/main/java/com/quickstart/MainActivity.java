package com.quickstart;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.quickstart.adapter.AppListAdapter;
import com.quickstart.model.AppEntry;
import com.quickstart.util.AppLoader;
import com.quickstart.util.BackgroundManager;
import com.quickstart.util.CategoryConfig;
import com.quickstart.util.FastCache;
import com.quickstart.util.IconCache;
import com.quickstart.util.KeyBindingHelper;
import com.quickstart.util.SearchHistory;
import com.quickstart.util.T9Matcher;

import java.lang.ref.WeakReference;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements CategoryPageFragment.PageHost {

    /** 日志 tag：只在异常/兜底路径上打，不刷日志 */
    private static final String TAG = "KSQ";

    private TextView sortLabel, t9Hint, t9Display, emptyHint;
    private ViewPager2 viewPager;
    private View keypad;
    private AppListAdapter adapter;

    private List<AppEntry> allApps = new ArrayList<>();
    private List<AppEntry> filtered = new ArrayList<>();
    private StringBuilder query = new StringBuilder();
    private String currentCategory = null;
    /** 进程进入后台时置 true，onResume 据此决定是否清空输入 */
    private boolean pendingQueryClear = false;
    /** 当前「最近搜索」分类的历史键集合（包名/Activity），非该分类时为 null */
    private java.util.Set<String> searchHistoryKeys;

    /** 分类 Tab 有序列表（不含「全部应用」；含 主界面/可编辑分类 + 固定智能分类） */
    private List<String> categoryTabs = new ArrayList<>();
    /** 已构建的分类签名，用于检测设置里分类是否变化，从而决定是否重建 Tab */
    private String categoryTabsSignature = "";
    /** 是否开启分类循环滑动（整型最大值分页的下标基准倍数） */
    private boolean loopSwipe = false;
    private static final int LOOP_BASE_MULT = 200;
    /** 下拉悬停功能的滚动偏移量（让顶部应用移到下半屏） */
    private static final int PULL_DOWN_HOVER_OFFSET = 600;
    /** 下拉悬停是否触发 */
    private boolean pullDownHoverActive = false;
    /** 是否正在执行悬停动画（忽略此期间的滚动事件） */
    private boolean isAnimatingHover = false;
    /** 悬停位移动画引用（复位时需要取消） */
    private android.animation.ValueAnimator hoverAnimator;
    /** 已应用的图标透明度百分比；-1 表示尚未应用（用于变化检测，避免 onResume 无谓刷新列表） */
    private int lastIconTransparency = -1;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    /** 主线程 Handler 的弱引用，供静态异步任务使用 */
    private final WeakReference<Handler> mainHandlerRef = new WeakReference<>(main);
    /** Activity 的弱引用，供定时刷新任务使用 */
    private final WeakReference<MainActivity> activityRef = new WeakReference<>(this);

    /**
     * 进程级前后台观察者。必须持有引用并在 onDestroy 反注册：
     * 否则每次 Activity 重建都会往 ProcessLifecycleOwner 上再挂一个匿名观察者，既泄漏，
     * 又会让 pendingQueryClear 写到已销毁的旧实例上（重建后"切后台清空输入"会失效）。
     */
    private final DefaultLifecycleObserver processObserver = new DefaultLifecycleObserver() {
        @Override
        public void onStop(@NonNull LifecycleOwner owner) {
            pendingQueryClear = true;
        }
    };

    /** 启动后定时刷新的间隔：30 分钟 */
    private static final long REFRESH_INTERVAL_MS = 30 * 60 * 1000L;
    private final Runnable periodicRefresh = new Runnable() {
        @Override
        public void run() {
            refreshAppsFullScan();
            main.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    /** 每页的 Adapter 实例（与 Fragment 一一对应），由 FragmentStateAdapter 在创建 Fragment 时注入 */
    private final List<AppListAdapter> pageAdapters = new ArrayList<>();
    /** 当前列表动画设置值，供 Fragment 创建时应用 */
    private String listAnimationValue = "off";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 进程被杀重建时恢复"需要清空输入"标志
        if (savedInstanceState != null) {
            pendingQueryClear = savedInstanceState.getBoolean("pending_query_clear", false);
        }
        setContentView(R.layout.activity_main);
        applyWindowSize();
        applyBackgroundColor();
        measureBackgroundArea();

        sortLabel  = findViewById(R.id.sort_label);
        t9Hint     = findViewById(R.id.t9_hint);
        t9Display  = findViewById(R.id.t9_display);
        emptyHint  = findViewById(R.id.empty_hint);
        viewPager  = findViewById(R.id.app_list);
        keypad     = findViewById(R.id.keypad);

        adapter = new AppListAdapter();
        adapter.setOnAppClickListener(this::launchApp);
        adapter.setOnAppLongClickListener(this::showAppMenu);

        int columnCount = getColumnCount();
        adapter.setColumnCount(columnCount);

        // 初始化每页的 Adapter（与 Fragment 一一对应）
        refreshCategoryTabs();
        for (int i = 0; i < getPageCount(); i++) {
            AppListAdapter pageAdapter = new AppListAdapter();
            pageAdapter.setOnAppClickListener(this::launchApp);
            pageAdapter.setOnAppLongClickListener(this::showAppMenu);
            pageAdapter.setColumnCount(columnCount);
            pageAdapters.add(pageAdapter);
        }

        // 锚定初始位置：若开启了循环滑动，则从基准页出发，保证左右都能无限滑动
        loopSwipe = getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("categories_loop", true);
        categoryTabsSignature = String.join("|", categoryTabs) + "|loop=" + loopSwipe;

        // ViewPager2 使用 FragmentStateAdapter：每个 page 是一个 Fragment（内部放 RecyclerView）
        // 这是 ViewPager2 连续滑动过渡的必要条件：如果 page 直接是 RecyclerView，
        // 内部的 RV 会拦截所有横向滑动事件，导致 ViewPager2 无法切页
        viewPager.setAdapter(new CategoryPagerAdapter(this));
        viewPager.setOffscreenPageLimit(2); // 预加载左右两页，保证连续滑动时相邻页已渲染
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {}
            @Override
            public void onPageSelected(int position) {
                // 页切换：归一化位置后同步分类
                syncPage(nrm(position));
            }
        });

        // 顶部分类 chips（多行自动换行）
        buildCategoryChips();

        // 应用「默认打开分类」设置；否则停在「全部应用」（无动画锚定，避免循环滑动下长距离滚动）
        String defaultCat = getSharedPreferences("settings", MODE_PRIVATE)
                .getString("default_category", null);
        int startIdx = 0;
        if (defaultCat != null && !defaultCat.isEmpty()
                && getCategoryPageIndex(defaultCat) != 0 && categoryTabs.contains(defaultCat)) {
            startIdx = getCategoryPageIndex(defaultCat);
        }
        int n = getPageCount();
        int anchor = loopSwipe ? LOOP_BASE_MULT * n + startIdx : startIdx;
        if (viewPager.getCurrentItem() != anchor) {
            viewPager.setCurrentItem(anchor, false); // 无动画
        }
        syncPage(startIdx);

        // 应用列表动画设置
        applyListAnimationSetting();

        // 应用设置
        boolean showDot = getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("recent_app_dot", true);
        adapter.setShowRecentDot(showDot);
        for (AppListAdapter a : pageAdapters) a.setShowRecentDot(showDot);

        // 依赖 adapter，必须在其初始化之后调用
        applyFontColor();
        applyIconTransparency();

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

        // 监听 APP 前后台切换：进入后台时标记需要清空输入
        ProcessLifecycleOwner.get().getLifecycle().addObserver(processObserver);

        setupKeypad();
        setupPullDownHover();
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

        // 设置角标手势回调（主 adapter + 所有页 adapter）
        AppListAdapter.OnBadgeGestureListener badgeCallback = position -> {
            if (position < filtered.size()) {
                launchApp(filtered.get(position));
            }
        };
        adapter.setOnBadgeGestureListener(badgeCallback);
        for (AppListAdapter a : pageAdapters) {
            a.setOnBadgeGestureListener(badgeCallback);
        }

        // 加载数字键绑定图标
        loadKeyBindingIcons();

        // 底部区域上滑展开键盘
        setupBottomSwipeToExpand();
    }

    /** 底部区域上滑手势：键盘收起时，上滑可展开键盘 */
    private void setupBottomSwipeToExpand() {
        View bottomArea = findViewById(R.id.bottom_swipe_area);
        if (bottomArea == null) return;

        final float[] startY = {0};
        final int SWIPE_THRESHOLD = 80; // 上滑超过80px触发

        bottomArea.setOnTouchListener((v, event) -> {
            // 只在键盘收起时响应
            if (keypad.getVisibility() != View.VISIBLE) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY[0] = event.getY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = startY[0] - event.getY();
                        if (dy > SWIPE_THRESHOLD) {
                            // 上滑展开键盘
                            keypad.setVisibility(View.VISIBLE);
                            TextView toggleBtn = findViewById(R.id.btn_toggle_keypad);
                            if (toggleBtn != null) toggleBtn.setText("⌄");
                            return true;
                        }
                        break;
                }
            }
            return false;
        });
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
     * 应用列表动画设置：读取"list_animation"偏好，配置 RecyclerView 的 ItemAnimator。
     * - off：关闭动画（setItemAnimator(null)）
     * - 其他值（毫秒数）：设置 DefaultItemAnimator 的时长
     */
    private void applyListAnimationSetting() {
        String value = getSharedPreferences("settings", MODE_PRIVATE)
                .getString("list_animation", "off");
        listAnimationValue = value;
        // 应用到所有已创建的 Fragment 中的 RecyclerView
        updateAllFragments(f -> f.applyListAnimation(value));
    }

    /** 遍历所有已创建的 Fragment（用于应用设置变更；按页面下标而非 tag，兼容循环滑动） */
    private void updateAllFragments(java.util.function.Consumer<CategoryPageFragment> action) {
        for (androidx.fragment.app.Fragment f : getSupportFragmentManager().getFragments()) {
            if (f instanceof CategoryPageFragment && f.isAdded()) {
                action.accept((CategoryPageFragment) f);
            }
        }
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
        } else {
            // 绑定键启动成功也要清空输入
            query.setLength(0);
            onQueryChanged();
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
        // T9输入时，让所有分类页的列表跳到顶部
        scrollAllPagesToTop();
    }

    /** 所有分类页列表瞬间滚回顶部 */
    private void scrollAllPagesToTop() {
        for (androidx.fragment.app.Fragment f : getSupportFragmentManager().getFragments()) {
            if (f instanceof CategoryPageFragment && f.isAdded()) {
                ((CategoryPageFragment) f).scrollToTop();
            }
        }
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

    /** 当输入匹配到唯一一个应用时，自动启动（需在设置中开启） */
    private void maybeAutoLaunch() {
        boolean autoLaunch = getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("t9_auto_launch", true);
        if (autoLaunch && filtered.size() == 1 && query.length() > 0) {
            launchApp(filtered.get(0));
        }
    }

    private String buildHint() {
        if (query.length() == 0) return "可以输入 'wx' 或 'weixin' 来搜索 '微信'";
        String q = query.toString();
        for (AppEntry e : allApps) {
            // 使用增强匹配检查是否有应用能被搜索到
            if (T9Matcher.matchesEnhanced(q, e)) {
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
                // 使用增强匹配：支持混合输入、英文分词、中英混合等
                boolean matchQuery = q.isEmpty() || T9Matcher.matchesEnhanced(q, e);
                // query 非空时全局搜索（忽略分类），query 为空时按分类过滤
                boolean matchCat = !q.isEmpty() || currentCategory == null || matchCategory(e, currentCategory);
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
        // 始终保持 RecyclerView 可见，使空列表时仍能接收左右滑动手势切换分类

        // 更新所有页 Adapter（T9 搜索时所有页都显示全局结果，无搜索时按分类过滤）
        for (int page = 0; page < pageAdapters.size(); page++) {
            String category = page == 0 ? null : categoryAt(page - 1);
            filterPage(pageAdapters.get(page), q, category, hidden);
        }

        // 第二道保险：把共享 adapter 补齐给还没有绑定的分类页 Fragment
        // （Activity 重建时 Fragment 由系统还原，createFragment 不会再被调用）
        syncLiveFragments();
    }

    private void filterPage(AppListAdapter pageAdapter, String q, String category, java.util.Set<String> hidden) {
        List<AppEntry> pageFiltered = new ArrayList<>();

        if (q.isEmpty() && category == null) {
            // 无搜索 + 无分类 = 全部应用
            for (AppEntry e : allApps) {
                if (!hidden.contains(e.packageName)) pageFiltered.add(e);
            }
        } else if (q.isEmpty() && "最近搜索".equals(category)) {
            pageFiltered = buildRecentSearched(hidden);
        } else if (q.isEmpty() && "最近使用".equals(category)) {
            pageFiltered = buildRecentlyUsed(hidden);
        } else if (q.isEmpty() && "最近安装".equals(category)) {
            pageFiltered = buildRecentlyInstalled(hidden);
        } else if (!q.isEmpty()) {
            // T9 搜索：全局搜索，忽略分类限制，使用增强匹配
            for (AppEntry e : allApps) {
                if (hidden.contains(e.packageName)) continue;
                if (T9Matcher.matchesEnhanced(q, e)) {
                    pageFiltered.add(e);
                }
            }
            sortBySearchWeight(pageFiltered, q);
        } else {
            // 有分类但无搜索词
            for (AppEntry e : allApps) {
                if (hidden.contains(e.packageName)) continue;
                if (matchCategory(e, category)) pageFiltered.add(e);
            }
        }
        pageAdapter.setHighlightQuery(q);
        pageAdapter.submit(pageFiltered);
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

    /** 默认排序权重优先级（从前到后，优先级递减） */
    private static final String[] DEFAULT_WEIGHT_ORDER = {
            "weight_exact", "weight_prefix", "weight_contains", "weight_freq", "weight_recent"
    };

    /** 搜索权重排序：按「T9 排序权重」列表顺序做词序比较，排在前面的规则优先，盖过后面所有规则 */
    private void sortBySearchWeight(List<AppEntry> list, String query) {
        long now = System.currentTimeMillis();
        final long ONE_DAY = 24 * 60 * 60 * 1000L;
        final String lowerQuery = query.toLowerCase();
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        final List<String> order = readWeightOrder(prefs);

        // 先为每个应用计算一次得分向量，比较器只查表，避免排序过程中 O(n log n) 次重复计算
        final java.util.Map<AppEntry, int[]> scores =
                new java.util.IdentityHashMap<>(list.size() * 2);
        for (AppEntry e : list) {
            scores.put(e, calcSearchScoreVector(e, lowerQuery, now, ONE_DAY, prefs, order));
        }
        java.util.Collections.sort(list, (a, b) ->
                compareScoreVector(scores.get(b), scores.get(a))); // 降序
    }

    /** 按规则优先级顺序计算得分向量（下标越小优先级越高；末位固定追加「最近更新」加分兜底） */
    private int[] calcSearchScoreVector(AppEntry e, String lowerQuery, long now, long oneDay,
                                        SharedPreferences prefs, List<String> order) {
        // 匹配强度：3=完全 2=开头 1=包含 0=不匹配（基于应用名/拼音/首字母/数字指纹判定）
        int strength = T9Matcher.matchStrength(lowerQuery, e);
        int n = order.size();
        int[] v = new int[n + 1];
        for (int i = 0; i < n; i++) {
            v[i] = ruleScore(e, order.get(i), strength, now, oneDay, prefs);
        }
        v[n] = e.recentlyUpdated ? 10 : 0;
        return v;
    }

    /** 计算单条规则的得分（0 表示该规则不命中 / 被设置为 0 忽略） */
    private int ruleScore(AppEntry e, String key, int strength, long now, long oneDay,
                          SharedPreferences prefs) {
        switch (key) {
            case "weight_exact":
                return strength == 3 ? weightInt(prefs, key, 150) : 0;   // 如 77→QQ、wx→微信
            case "weight_prefix":
                return strength == 2 ? weightInt(prefs, key, 50) : 0;    // weixi→微信（全拼前缀）
            case "weight_contains":
                return strength == 1 ? weightInt(prefs, key, 30) : 0;    // 仅包含/跳跃匹配
            case "weight_freq":                              // 使用频率（次数上限 50）
                return Math.min(launchCountCache.getOrDefault(e.packageName, 0), 50)
                        * Math.max(0, weightInt(prefs, key, 2));
            case "weight_recent": {                          // 最近使用时间
                int mult = weightInt(prefs, key, 1);
                if (mult <= 0) return 0;
                long lastLaunch = lastLaunchTimeCache.getOrDefault(e.packageName, 0L);
                if (lastLaunch <= 0) return 0;
                long daysAgo = (now - lastLaunch) / oneDay;
                int base = daysAgo == 0 ? 80 : daysAgo <= 1 ? 60 : daysAgo <= 3 ? 40 : 20;
                return base * mult;
            }
            default:
                return 0;
        }
    }

    /** 词序比较两个得分向量：第一个得分不同的规则即决定先后（靠前规则盖过靠后规则） */
    private int compareScoreVector(int[] a, int[] b) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        }
        return 0;
    }

    /** 读取规则优先级列表（优先取设置里保存的顺序，缺失项按默认顺序补到末尾） */
    private List<String> readWeightOrder(SharedPreferences prefs) {
        List<String> list = new ArrayList<>();
        String s = prefs.getString("weight_order", null);
        if (s != null) {
            for (String key : s.split(",")) {
                key = key.trim();
                if (!key.isEmpty() && !list.contains(key)) list.add(key);
            }
        }
        for (String key : DEFAULT_WEIGHT_ORDER) {
            if (!list.contains(key)) list.add(key);
        }
        return list;
    }

    /** 读取权重设置，非法/越界值回退到默认 */
    private static int weightInt(SharedPreferences prefs, String key, int def) {
        try {
            return Integer.parseInt(prefs.getString(key, String.valueOf(def)));
        } catch (Throwable ignored) {
            return def;
        }
    }

    private boolean matchCategory(AppEntry e, String cat) {
        if (cat == null) return true;
        switch (cat) {
            case "最近搜索": {
                if (searchHistoryKeys == null) searchHistoryKeys = loadHistoryKeys();
                return searchHistoryKeys.contains(e.packageName + "/" + e.activityName);
            }
            case "最近使用": return lastLaunchTimeCache.getOrDefault(e.packageName, 0L) > 0;
            case "最近安装": {
                long t = installTimeCache.getOrDefault(e.packageName, 0L);
                return t > 0 && System.currentTimeMillis() - t <= getRecentTimeRange();
            }
            default: {
                // 可编辑分类：手动类型查归属列表，关键词类型按关键词/包名规则
                if (CategoryConfig.isInManualCategory(this, cat, e)) return true;
                return CategoryConfig.matchesKeyword(this, cat, e);
            }
        }
    }

    private void launchApp(AppEntry entry) {
        recordLaunch(entry.packageName); // 记录启动次数（用于使用频率排序）
        // 记录搜索历史（仅在有搜索词时；T9 自动启动和手动点击都经过这里）
        if (query.length() > 0) {
            SearchHistory.record(this, query.toString(), entry.packageName, entry.activityName);
        }
        try {
            Intent targetIntent;
            if (entry.launchIntent != null) {
                targetIntent = entry.launchIntent;
            } else {
                targetIntent = new Intent();
                targetIntent.setClassName(entry.packageName, entry.activityName);
                targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            // 使用从中心缩放展开的启动动画，替代系统默认的横向滑动切换动画
            android.app.ActivityOptions opts = android.app.ActivityOptions.makeCustomAnimation(
                    this, R.anim.launch_scale_up, R.anim.no_anim);
            startActivity(targetIntent, opts.toBundle());
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

    /** 获取当前排序模式的整型常量（与 FastCache SORT_* 常量对应） */
    private int getCurrentSortModeInt() {
        String sortMode = getSharedPreferences("settings", MODE_PRIVATE)
                .getString("sort_mode", "智能排序");
        switch (sortMode) {
            case "字母顺序": return com.quickstart.util.FastCache.SORT_ALPHA;
            case "最近安装": return com.quickstart.util.FastCache.SORT_INSTALL_TIME;
            case "使用频率": return com.quickstart.util.FastCache.SORT_LAUNCH_COUNT;
            default: return com.quickstart.util.FastCache.SORT_SMART;
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
                if (query.length() > 0) onQueryChanged(); else doFilter();
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

    /**
     * 应用背景：纯色打底 + 图片层。
     *
     * 图片只从「已生成好的文件」里挑一个解码显示，运行时不做任何模糊计算；
     * 模糊图在裁剪完成 / 设置里切换档位时离线预生成。
     */
    private void applyBackgroundColor() {
        SharedPreferences sp = getSharedPreferences(BackgroundManager.PREFS, MODE_PRIVATE);
        View bgContainer = findViewById(R.id.bg_container);
        ImageView bgImage = findViewById(R.id.bg_image);

        // 旧版本背景文件一次性迁移；模糊参数升级后作废旧图并后台重生成，生成完再刷一次
        if (BackgroundManager.prepare(this)) {
            BackgroundManager.generateAllAsync(this, (ok, err) -> {
                if (!isDestroyed()) applyBackgroundColor();
            });
        }

        // 1) 纯色兜底
        int color = Color.TRANSPARENT;
        String colorStr = sp.getString("background_color", "");
        if (!colorStr.isEmpty()) {
            try {
                color = Color.parseColor(colorStr);
            } catch (Exception ignored) {
            }
        }
        if (bgContainer != null) bgContainer.setBackgroundColor(color);
        if (bgImage == null) return;

        // 背景层尺寸固定为参考尺寸，键盘收起时不再被拉伸
        lockBackgroundSize(bgImage);

        // 2) 图片层
        String imagePath = sp.getString(BackgroundManager.PREF_IMAGE, "");
        if (imagePath.isEmpty()) {
            bgImage.setImageDrawable(null);
            return;
        }
        final String mode = sp.getString(BackgroundManager.PREF_BLUR, BackgroundManager.NONE);
        io.execute(() -> {
            File file = BackgroundManager.getDisplayFile(MainActivity.this, mode);
            if (file == null) {
                main.post(() -> {
                    if (!isDestroyed()) bgImage.setImageDrawable(null);
                });
                return;
            }
            int w = bgImage.getWidth();
            int h = bgImage.getHeight();
            if (w <= 0 || h <= 0) {
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                w = dm.widthPixels;
                h = dm.heightPixels;
            }
            Bitmap bmp = BackgroundManager.decodeSampled(file, w, h);
            main.post(() -> {
                if (!isDestroyed()) bgImage.setImageBitmap(bmp);
            });
        });
    }

    /**
     * 实测应用列表区域尺寸并持久化：
     * 取「键盘收起时」的最大高度 = 当前列表高度 + 键盘高度，
     * 背景层按这个尺寸固定住，键盘展开/收起时就不会再缩放。
     */
    private void measureBackgroundArea() {
        if (viewPager == null) return;
        viewPager.post(() -> {
            int w = viewPager.getWidth();
            int listH = viewPager.getHeight();
            if (w <= 0 || listH <= 0) return;

            int keypadH = 0;
            View keypadView = findViewById(R.id.keypad);
            if (keypadView != null && keypadView.getVisibility() == View.VISIBLE) {
                keypadH = keypadView.getHeight();
            }
            // T9 显示条也在背景区内，需一并计入列表区最大高度
            View stripView = findViewById(R.id.bottom_swipe_area);
            int stripH = (stripView != null && stripView.getVisibility() == View.VISIBLE)
                    ? stripView.getHeight() : 0;
            int maxH = listH + stripH + keypadH;   // 键盘收起后列表能占到的最大高度

            SharedPreferences sp = getSharedPreferences(BackgroundManager.PREFS, MODE_PRIVATE);
            float ratio = w / (float) maxH;
            if (Math.abs(sp.getFloat(BackgroundManager.PREF_ASPECT, 0f) - ratio) < 0.005f
                    && sp.getInt(BackgroundManager.PREF_AREA_W, 0) == w
                    && sp.getInt(BackgroundManager.PREF_AREA_H, 0) == maxH) {
                return;
            }
            sp.edit()
                    .putFloat(BackgroundManager.PREF_ASPECT, ratio)
                    .putInt(BackgroundManager.PREF_AREA_W, w)
                    .putInt(BackgroundManager.PREF_AREA_H, maxH)
                    .apply();
            // 参考尺寸变了，重新按新尺寸铺一次背景
            applyBackgroundColor();
        });
    }

    /** 把背景层尺寸固定成参考尺寸，避免键盘收起时被拉伸 */
    private void lockBackgroundSize(ImageView bgImage) {
        if (bgImage == null) return;
        int areaH = getSharedPreferences(BackgroundManager.PREFS, MODE_PRIVATE)
                .getInt(BackgroundManager.PREF_AREA_H, 0);
        if (areaH <= 0) return;
        android.view.ViewGroup.LayoutParams lp = bgImage.getLayoutParams();
        if (lp != null && lp.height != areaH) {
            lp.height = areaH;
            bgImage.setLayoutParams(lp);
        }
    }

    /** 应用字体颜色 */
    private void applyFontColor() {
        String color = getSharedPreferences("settings", MODE_PRIVATE)
                .getString("font_color", "");
        if (!color.isEmpty()) {
            try {
                int fontColor = Color.parseColor(color);
                adapter.setFontColor(fontColor);
                for (AppListAdapter a : pageAdapters) a.setFontColor(fontColor);
                updateAllFragments(f -> f.setFontColor(fontColor));
            } catch (Exception ignored) {}
        }
    }

    /**
     * 应用图标透明度设置。
     * SP 里存的是「透明度百分比」0-100：0 = 完全不透明（alpha 1.0），100 = 完全透明，默认 60。
     */
    private void applyIconTransparency() {
        int v = getSharedPreferences("settings", MODE_PRIVATE)
                .getInt("icon_transparency", 60);
        v = Math.max(0, Math.min(100, v));
        if (v == lastIconTransparency) return; // 值未变，跳过，避免每次 onResume 都刷新列表
        lastIconTransparency = v;
        float alpha = (100 - v) / 100f;
        adapter.setIconAlpha(alpha);
        for (AppListAdapter a : pageAdapters) a.setIconAlpha(alpha);
        updateAllFragments(f -> f.setIconAlpha(alpha));
    }

    // ==================== 分类 Tab 相关 ====================

    /** 刷新分类 Tab 列表：主界面/可编辑分类（来自 CategoryConfig）+ 固定的智能分类 */
    private void refreshCategoryTabs() {
        categoryTabs = new ArrayList<>(CategoryConfig.getUserCategoryNames(this));
        categoryTabs.add(CategoryConfig.CAT_RECENT_SEARCH);
        categoryTabs.add(CategoryConfig.CAT_RECENT_USE);
        categoryTabs.add(CategoryConfig.CAT_RECENT_INSTALL);
    }

    /** 页总数：第 0 页为「全部应用」，其余每分类一页 */
    private int getPageCount() {
        return categoryTabs.size() + 1;
    }

    /** 第 index 个分类（0 起），越界返回 null */
    @Nullable
    private String categoryAt(int index) {
        if (index < 0 || index >= categoryTabs.size()) return null;
        return categoryTabs.get(index);
    }

    /** 分类名对应的页面下标（第 0 页是「全部应用」），未命中返回 0 */
    private int getCategoryPageIndex(String name) {
        if (name == null) return 0;
        for (int i = 0; i < categoryTabs.size(); i++) {
            if (categoryTabs.get(i).equals(name)) return i + 1;
        }
        return 0;
    }

    /** 归一化 ViewPager2 原始位置到页面下标（0..getPageCount()-1） */
    private int nrm(int raw) {
        int n = getPageCount();
        if (n <= 0) return 0;
        return ((raw % n) + n) % n;
    }

    /** 切换到指定页面下标（含循环滑动处理） */
    private void goToPage(int pageIndex) {
        int p = nrm(pageIndex);
        int n = getPageCount();
        int cur = viewPager.getCurrentItem();
        int target;
        if (loopSwipe) {
            // 取距离当前位置最近的方向，避免长距离往返跳动
            int curPage = nrm(cur);
            int delta = p - curPage;
            if (delta > n / 2) delta -= n;
            else if (delta < -n / 2) delta += n;
            target = cur + delta;
        } else {
            target = p;
        }
        if (cur != target) {
            viewPager.setCurrentItem(target, true);
        }
    }

    /** 按页面下标同步分类状态（不触发跳转）：更新选中 chips + 过滤 */
    private void syncPage(int pageIndex) {
        currentCategory = pageIndex == 0 ? null : categoryAt(pageIndex - 1);
        updateChipSelection();
        doFilter();
    }

    /** 应用指定分类（同步 ViewPager2 位置、chips 选中态并过滤） */
    private void applyCategory(String category) {
        int pageIndex = category == null ? 0 : getCategoryPageIndex(category);
        currentCategory = pageIndex == 0 ? null : category;
        updateChipSelection();
        doFilter();
        goToPage(pageIndex);
    }

    /** 构建顶部分类 chips（全部应用 + 各分类），多行自动换行 */
    private void buildCategoryChips() {
        com.google.android.flexbox.FlexboxLayout chips = findViewById(R.id.category_chips);
        if (chips == null) return;
        chips.removeAllViews();
        addChip(chips, "全部应用");
        for (String name : categoryTabs) addChip(chips, name);
        updateChipSelection();
    }

    private void addChip(com.google.android.flexbox.FlexboxLayout chips, String text) {
        TextView chip = new TextView(this);
        chip.setText(text);
        // setTextAppearance 只应用文字样式，背景/内边距/边距需手动设置，
        // 否则 chips 会变成两行紧挨着的裸文字
        chip.setTextAppearance(this, R.style.CategoryChipDrawer);
        chip.setBackgroundResource(R.drawable.bg_category_chip);
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setOnClickListener(v -> applyCategory("全部应用".equals(text) ? null : text));
        chip.setOnLongClickListener(v -> {
            if ("最近搜索".equals(text)) {
                new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("清空搜索历史")
                        .setMessage("确定清空全部搜索历史吗？")
                        .setPositiveButton("清空", (d, w) -> {
                            SearchHistory.clear(MainActivity.this);
                            if ("最近搜索".equals(currentCategory)) doFilter();
                        })
                        .setNegativeButton("取消", null)
                        .show();
                return true;
            }
            return false;
        });

        // 显式设置尺寸与边距：行内间距 8dp、行间间距 8dp，让各分类彼此空开
        float density = getResources().getDisplayMetrics().density;
        com.google.android.flexbox.FlexboxLayout.LayoutParams lp =
                new com.google.android.flexbox.FlexboxLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, (int) (32 * density));
        lp.setMargins(0, 0, (int) (8 * density), (int) (8 * density));
        chips.addView(chip, lp);
    }

    /** 高亮当前分类 chips：全部应用 ↔ 具体分类 */
    private void updateChipSelection() {
        com.google.android.flexbox.FlexboxLayout chips = findViewById(R.id.category_chips);
        if (chips == null) return;
        for (int i = 0; i < chips.getChildCount(); i++) {
            View v = chips.getChildAt(i);
            if (v instanceof TextView) {
                String text = ((TextView) v).getText().toString();
                boolean selected = "全部应用".equals(text)
                        ? currentCategory == null
                        : text.equals(currentCategory);
                v.setSelected(selected);
            }
        }
    }

    /** 从设置返回后检测分类/循环设置是否变化，有变则重建页适配器与 chips */
    private void rebuildTabsIfChanged() {
        refreshCategoryTabs();
        loopSwipe = getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("categories_loop", true);
        String sig = String.join("|", categoryTabs) + "|loop=" + loopSwipe;
        if (sig.equals(categoryTabsSignature)) return;
        categoryTabsSignature = sig;

        // 重建每页 Adapter
        pageAdapters.clear();
        int columnCount = getColumnCount();
        for (int i = 0; i < getPageCount(); i++) {
            AppListAdapter a = new AppListAdapter();
            a.setOnAppClickListener(this::launchApp);
            a.setOnAppLongClickListener(this::showAppMenu);
            a.setColumnCount(columnCount);
            a.setShowRecentDot(getSharedPreferences("settings", MODE_PRIVATE)
                    .getBoolean("recent_app_dot", true));
            pageAdapters.add(a);
        }
        applyFontColor();
        lastIconTransparency = -1; // 新 adapter 实例，复位检测值以强制重新应用
        applyIconTransparency();

        // 重建 ViewPager 分页与 chips
        viewPager.setAdapter(new CategoryPagerAdapter(this));
        buildCategoryChips();

        // 若当前分类已被删除则回到「全部应用」
        if (currentCategory != null && getCategoryPageIndex(currentCategory) == 0) {
            currentCategory = null;
        }
        int p = currentCategory == null ? 0 : getCategoryPageIndex(currentCategory);
        int n2 = getPageCount();
        int a = loopSwipe ? LOOP_BASE_MULT * n2 + p : p;
        if (viewPager.getCurrentItem() != a) {
            viewPager.setCurrentItem(a, false); // 无动画锚定，避免循环滑动下长距离滚动
        }
        syncPage(p);
        syncLiveFragments(); // pageAdapters 已重建，把新实例补发给活着的 Fragment
    }

    /**
     * 把共享 adapter 补齐给所有活着的分类页 Fragment（幂等，已绑定则零成本）。
     *
     * 修复的场景：Activity 被销毁重建后，ViewPager2 的 FragmentStateAdapter 在恢复状态时
     * 直接复用系统还原出来的 Fragment 实例，不会再调 createFragment()，
     * 于是注入点被跳过、Fragment.adapter 保持 null、RecyclerView 没有 adapter → 列表整片空白。
     */
    private void syncLiveFragments() {
        for (androidx.fragment.app.Fragment f : getSupportFragmentManager().getFragments()) {
            if (!(f instanceof CategoryPageFragment) || !f.isAdded()) continue;
            CategoryPageFragment pf = (CategoryPageFragment) f;
            int idx = pf.getPageIndex();
            if (idx < 0 || idx >= pageAdapters.size()) continue;
            pf.bind(pageAdapters.get(idx), this);
        }
    }

    /**
     * ViewPager2 适配器：使用 FragmentStateAdapter，每个 page 是一个 Fragment。
     *
     * 为什么必须用 Fragment 而不是直接放 RecyclerView？
     *  ViewPager2 内部就是一个 RecyclerView，如果 page 也是 RecyclerView，
     *  内部的 RV 会拦截所有横向滑动事件，导致 ViewPager2 无法切页。
     *  用 Fragment 包裹 RV 后，RV 的横向滑动会正确"上抛"给 ViewPager2。
     *
     * 连续滑动过渡（Canvas 效果）：
     *  - offscreenPageLimit = 2 保证左右两页已渲染
     *  - CanvasPageTransformer 只做 translationX，不做 alpha/scale
     *  - ViewPager2 内置的 Fling 速度检测 + 距离判断自动决定松手后吸附到哪一页
     */
    private class CategoryPagerAdapter extends FragmentStateAdapter {

        CategoryPagerAdapter(FragmentActivity fa) {
            super(fa);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            int p = nrm(position);
            CategoryPageFragment f = CategoryPageFragment.newInstance(p);
            f.setCallbacks(MainActivity.this);
            f.setAdapter(pageAdapters.get(p));
            // 应用当前设置
            f.setColumnCount(getColumnCount());
            f.setShowRecentDot(getSharedPreferences("settings", MODE_PRIVATE)
                    .getBoolean("recent_app_dot", true));
            f.applyListAnimation(listAnimationValue);
            return f;
        }

        @Override
        public int getItemCount() {
            // 开启循环滑动时返回整型最大值，配合创建位置的取模，实现无限循环
            return loopSwipe ? Integer.MAX_VALUE : getPageCount();
        }
    }

    /**
     * 设置下拉悬停功能：
     * 当用户在应用列表顶部快速从上往下滑动时，列表内容向下偏移悬停，
     * 使顶部的应用移到下半屏，方便单手操作。
     * 上滑可恢复正常位置。
     */
    private void setupPullDownHover() {
        boolean enabled = getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("pull_down_hover", true);
        if (!enabled) return;

        ViewPager2 vp = viewPager;
        if (vp.getChildAt(0) instanceof RecyclerView) {
            RecyclerView internalRv = (RecyclerView) vp.getChildAt(0);
            internalRv.addOnItemTouchListener(new RecyclerView.OnItemTouchListener() {
                private float startY = 0;
                private boolean isTracking = false;

                @Override
                public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
                    if (isAnimatingHover) return false;
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            startY = e.getY();
                            isTracking = true;
                            break;
                        case MotionEvent.ACTION_MOVE:
                            if (isTracking) {
                                float dy = e.getY() - startY;
                                if (pullDownHoverActive) {
                                    // 悬停状态下：上滑任意距离即归位
                                    if (dy < -20) {
                                        isTracking = false;
                                        cancelHover();
                                        return true;
                                    }
                                } else if (dy > 100 && !rv.canScrollVertically(-1)) {
                                    isTracking = false;
                                    activateHover();
                                    return true;
                                }
                            }
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            isTracking = false;
                            break;
                    }
                    return false;
                }

                @Override
                public void onTouchEvent(RecyclerView rv, MotionEvent e) {}

                @Override
                public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {}
            });
        }
    }

    /** 激活悬停 */
    private void activateHover() {
        if (pullDownHoverActive) return;
        pullDownHoverActive = true;
        animateTranslationY(0, PULL_DOWN_HOVER_OFFSET);
    }

    /** 取消悬停 */
    private void cancelHover() {
        if (!pullDownHoverActive) return;
        pullDownHoverActive = false;
        animateTranslationY(PULL_DOWN_HOVER_OFFSET, 0);
    }

    /**
     * 回到启动器时复位列表状态：立即归位下拉悬停偏移，
     * 并把所有已创建分类页的列表瞬间滚回顶部（第一行可见）。
     */
    private void resetListToTop() {
        // 取消进行中的悬停动画并立即归位
        if (hoverAnimator != null) {
            hoverAnimator.cancel();
            hoverAnimator = null;
        }
        pullDownHoverActive = false;
        isAnimatingHover = false;
        viewPager.setTranslationY(0);

        // 所有已创建的分类页列表滚回顶部
        for (androidx.fragment.app.Fragment f : getSupportFragmentManager().getFragments()) {
            if (f instanceof CategoryPageFragment && f.isAdded()) {
                ((CategoryPageFragment) f).scrollToTop();
            }
        }
    }

    /** 平滑过渡 translationY */
    private void animateTranslationY(float from, float to) {
        isAnimatingHover = true;
        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(from, to);
        hoverAnimator = animator;
        animator.setDuration(300);
        animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            float value = (float) animation.getAnimatedValue();
            viewPager.setTranslationY(value);
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                isAnimatingHover = false;
            }
        });
        animator.start();
    }

    /**
     * 启动加载：
     * 1. 从二进制缓存快速加载（含图标）→ 预加载排序数据 → 排序 → 立即显示
     * 2. 后台扫描最新应用列表 → 排序 → 更新缓存和 UI
     */
    private void loadAppsAsync() {
        View loadingOverlay = findViewById(R.id.loading_overlay);
        if (loadingOverlay != null) loadingOverlay.setVisibility(View.VISIBLE);

        new LoadAppsTask(this, loadingOverlay, main, () -> {
            main.removeCallbacks(periodicRefresh);
            main.postDelayed(periodicRefresh, REFRESH_INTERVAL_MS);
        }).executeOn(io);
    }

    /**
     * 静态异步任务，通过 WeakReference 持有 Activity，避免内存泄露。
     * 原匿名内部类通过 MainActivity.this 强引用 Activity，当 Activity 被销毁时
     * 若后台线程仍在运行（如 IconCache.preloadAll 回调），会导致 Activity 无法被 GC 回收。
     */
    private static class LoadAppsTask {
        private final WeakReference<MainActivity> activityRef;
        private final WeakReference<View> loadingOverlayRef;
        private final WeakReference<Handler> mainHandlerRef;
        private final Runnable onComplete;

        LoadAppsTask(MainActivity activity, View loadingOverlay, Handler mainHandler, Runnable onComplete) {
            this.activityRef = new WeakReference<>(activity);
            this.loadingOverlayRef = new WeakReference<>(loadingOverlay);
            this.mainHandlerRef = new WeakReference<>(mainHandler);
            this.onComplete = onComplete;
        }

        void executeOn(ExecutorService executor) {
            executor.execute(() -> {
                MainActivity activity = activityRef.get();
                if (activity == null || activity.isDestroyed()) return;

                // 1. 从二进制缓存快速加载（含图标数据）
                final com.quickstart.util.FastCache.CacheResult cacheResult =
                        com.quickstart.util.FastCache.load(activity);

                if (cacheResult != null && !cacheResult.apps.isEmpty()) {
                    final List<AppEntry> cached = cacheResult.apps;
                    final int cachedSortMode = cacheResult.sortMode;
                    final int currentSortMode = activity.getCurrentSortModeInt();

                    // 阶段A：立即显示缓存列表（跳过 Intent 回填、排序、过滤，<5ms）
                    Handler mainHandler = mainHandlerRef.get();
                    if (mainHandler != null) {
                        mainHandler.post(() -> {
                            MainActivity a = activityRef.get();
                            if (a == null || a.isDestroyed()) return;
                            a.allApps = cached;
                            if (a.query.length() > 0) a.onQueryChanged(); else a.doFilter();
                            View overlay = loadingOverlayRef.get();
                            if (overlay != null) overlay.setVisibility(View.GONE);
                        });
                    }

                    // 阶段B：后台补齐（Intent 回填 + 排序模式检查 + 过滤）
                    // 回填启动 Intent（ PackageManager 调用，约 50-80ms）
                    for (AppEntry e : cached) {
                        if (e.launchIntent == null) {
                            try {
                                Intent intent = activity.getPackageManager().getLaunchIntentForPackage(e.packageName);
                                if (intent != null) {
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    e.launchIntent = intent;
                                }
                            } catch (Throwable ignored) {}
                        }
                    }

                    // 如果排序模式与缓存不一致，需要重新排序（约 30-50ms）
                    if (cachedSortMode != currentSortMode && currentSortMode != com.quickstart.util.FastCache.SORT_UNKNOWN) {
                        activity.preloadSortData(cached);
                        activity.sortAllApps(cached);
                    }

                    // 过滤隐藏应用
                    java.util.Set<String> hidden = activity.getHiddenPackages();
                    cached.removeIf(e -> hidden.contains(e.packageName));

                    // 回到主线程更新列表（用户此时已看到列表，这次更新几乎无感知）
                    if (mainHandler != null) {
                        mainHandler.post(() -> {
                            MainActivity a = activityRef.get();
                            if (a == null || a.isDestroyed()) return;
                            a.allApps = cached;
                            if (a.query.length() > 0) a.onQueryChanged(); else a.doFilter();
                        });
                    }
                }

                // 2. 后台扫描最新应用列表
                if (activity.isDestroyed()) return;
                final List<AppEntry> loaded = AppLoader.loadLaunchableApps(activity);

                // 扫描结果为空属异常：保留缓存/现有列表，别把界面清空
                if (isScanResultSuspicious(loaded)) {
                    Handler mh = mainHandlerRef.get();
                    if (mh != null) {
                        mh.post(() -> {
                            View overlay = loadingOverlayRef.get();
                            if (overlay != null) overlay.setVisibility(View.GONE);
                            if (onComplete != null) onComplete.run(); // 定时刷新仍要挂上
                        });
                    }
                    return;
                }

                // 3. 预加载图标 + 排序数据 + 排序
                final List<String> packages = new java.util.ArrayList<>();
                for (AppEntry e : loaded) packages.add(e.packageName);
                final List<AppEntry> finalLoaded = loaded;

                if (activity.isDestroyed()) return;
                IconCache.preloadAll(activity, packages, () -> {
                    MainActivity a = activityRef.get();
                    if (a == null || a.isDestroyed()) return;

                    a.preloadSortData(finalLoaded);
                    a.sortAllApps(finalLoaded);

                    // 写入缓存时记录当前排序模式
                    FastCache.save(a, finalLoaded, a.getCurrentSortModeInt());

                    java.util.Set<String> hidden2 = a.getHiddenPackages();
                    finalLoaded.removeIf(e -> hidden2.contains(e.packageName));

                    Handler mainHandler = mainHandlerRef.get();
                    if (mainHandler != null) {
                        mainHandler.post(() -> {
                            MainActivity act = activityRef.get();
                            if (act == null || act.isDestroyed()) return;
                            act.allApps = finalLoaded;
                            if (act.query.length() > 0) act.onQueryChanged(); else act.doFilter();
                            View overlay = loadingOverlayRef.get();
                            if (overlay != null) overlay.setVisibility(View.GONE);
                            if (onComplete != null) onComplete.run();
                        });
                    }
                });
            });
        }
    }

    /** 定时刷新：直接全量扫描（不读缓存），完成后刷新 UI */
    private void refreshAppsFullScan() {
        final WeakReference<Handler> handlerRef = mainHandlerRef;
        io.execute(() -> {
            // 必须先取到存活的 Activity 再扫描：直接把可能为 null 的 Context 传进去
            // 会在 io 线程里抛 NPE 并被静默吞掉，导致 30 分钟定时刷新从此再也不生效
            MainActivity activity = activityRef.get();
            if (activity == null || activity.isDestroyed()) return;

            final List<AppEntry> loaded = AppLoader.loadLaunchableApps(activity);
            if (activity.isDestroyed()) return;
            if (isScanResultSuspicious(loaded)) return;

            java.util.Set<String> hidden = activity.getHiddenPackages();
            loaded.removeIf(e -> hidden.contains(e.packageName));
            Handler h = handlerRef.get();
            if (h != null) {
                h.post(() -> {
                    MainActivity a = activityRef.get();
                    if (a == null || a.isDestroyed()) return;
                    a.allApps = loaded;
                    if (a.query.length() > 0) a.onQueryChanged(); else a.doFilter();
                });
            }
        });
    }

    /**
     * 全量扫描结果为空视为异常：真机上不可能一个可启动应用都查不到，
     * 出现空结果多半是系统限流/查询失败。此时绝不能用空列表覆盖现有列表，
     * 否则界面会整片空白（这正是"有时候列表显示为空"的一个诱因）。
     */
    private static boolean isScanResultSuspicious(List<AppEntry> loaded) {
        if (!loaded.isEmpty()) return false;
        android.util.Log.w(TAG, "全量扫描结果为空，保留现有列表（可能是系统限流或查询失败）");
        return true;
    }

    /** 用户主动切后台（Home 键、最近任务、手势回桌面）：同步触发，比 onStop 更早更可靠（小米兼容） */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        pendingQueryClear = true;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("pending_query_clear", pendingQueryClear);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 设置里可能新增/删除/改名了分类，回来后检测并重建
        rebuildTabsIfChanged();
        // 兜底：重建后 Fragment 可能刚被系统还原、缺 adapter 注入（bind 幂等）
        syncLiveFragments();
        // 从设置页返回即时生效（值未变时内部会直接跳过）
        applyIconTransparency();
        if (pendingQueryClear) {
            pendingQueryClear = false;
            if (query.length() > 0) {
                query.setLength(0);
                onQueryChanged();
            }
            // 回到启动器：应用列表滚回顶部（第一行可见），并复位下拉悬停
            resetListToTop();
        }
        // 角标由 Adapter 根据 position 自动显示，无需手动刷新
        // 检查是否从设置页触发了强制刷新
        boolean forceRefresh = getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("force_refresh_pending", false);
        if (forceRefresh) {
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putBoolean("force_refresh_pending", false).apply();
            refreshAppsFullScanWithCache();
        }
    }

    /** 强制刷新：完整重扫（不读缓存），重新预加载图标并写缓存 */
    private void refreshAppsFullScanWithCache() {
        View loadingOverlay = findViewById(R.id.loading_overlay);
        if (loadingOverlay != null) loadingOverlay.setVisibility(View.VISIBLE);

        new RefreshAppsTask(this, loadingOverlay, mainHandlerRef).executeOn(io);
    }

    /**
     * 强制刷新异步任务（静态类，WeakReference 防泄露）
     */
    private static class RefreshAppsTask {
        private final WeakReference<MainActivity> activityRef;
        private final WeakReference<View> loadingOverlayRef;
        private final WeakReference<Handler> mainHandlerRef;

        RefreshAppsTask(MainActivity activity, View loadingOverlay, WeakReference<Handler> mainHandlerRef) {
            this.activityRef = new WeakReference<>(activity);
            this.loadingOverlayRef = new WeakReference<>(loadingOverlay);
            this.mainHandlerRef = mainHandlerRef;
        }

        void executeOn(ExecutorService executor) {
            executor.execute(() -> {
                MainActivity activity = activityRef.get();
                if (activity == null || activity.isDestroyed()) return;

                final List<AppEntry> loaded = AppLoader.loadLaunchableApps(activity);

                // 扫描结果为空属异常：保留缓存/现有列表，别把界面清空
                if (isScanResultSuspicious(loaded)) {
                    Handler mh = mainHandlerRef.get();
                    if (mh != null) {
                        mh.post(() -> {
                            View overlay = loadingOverlayRef.get();
                            if (overlay != null) overlay.setVisibility(View.GONE);
                        });
                    }
                    return;
                }

                List<String> packages = new ArrayList<>();
                for (AppEntry e : loaded) packages.add(e.packageName);
                final List<AppEntry> finalLoaded = loaded;

                IconCache.preloadAll(activity, packages, () -> {
                    MainActivity a = activityRef.get();
                    if (a == null || a.isDestroyed()) return;

                    a.preloadSortData(finalLoaded);
                    a.sortAllApps(finalLoaded);

                    FastCache.save(a, finalLoaded, a.getCurrentSortModeInt());

                    java.util.Set<String> hidden = a.getHiddenPackages();
                    finalLoaded.removeIf(e -> hidden.contains(e.packageName));

                    Handler mainHandler = mainHandlerRef.get();
                    if (mainHandler != null) {
                        mainHandler.post(() -> {
                            MainActivity act = activityRef.get();
                            if (act == null || act.isDestroyed()) return;
                            act.allApps = finalLoaded;
                            if (act.query.length() > 0) act.onQueryChanged(); else act.doFilter();
                            View overlay = loadingOverlayRef.get();
                            if (overlay != null) overlay.setVisibility(View.GONE);
                        });
                    }
                });
            });
        }
    }

    // ========== CategoryPageFragment.PageHost 实现 ==========

    /**
     * 宿主按页注入共享 adapter、设置与当前页数据。
     *
     * 由 CategoryPageFragment.onViewCreated 调用，是"Activity 重建后 Fragment 拿不到 adapter"
     * 的主修复点。正常路径下 createFragment() 已注入过，bind() 会直接返回，无额外开销。
     */
    @Override
    public void bindPageFragment(CategoryPageFragment f) {
        final int idx = f.getPageIndex();
        if (idx < 0 || idx >= pageAdapters.size()) {
            android.util.Log.w(TAG, "bindPageFragment: 页下标越界 idx=" + idx
                    + " pageAdapters=" + pageAdapters.size());
            return;
        }
        final AppListAdapter pageAdapter = pageAdapters.get(idx);
        f.bind(pageAdapter, this);
        f.setColumnCount(getColumnCount());
        f.setShowRecentDot(getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("recent_app_dot", true));
        f.applyListAnimation(listAnimationValue);

        // 补推一次当前页数据：Fragment 视图创建时共享 adapter 可能还没被 doFilter 填过。
        // 放到下一帧执行，避免在 RecyclerView 布局期触发 adapter 通知
        // （"Cannot call this method while RecyclerView is computing a layout"）。
        main.post(() -> {
            if (isDestroyed()) return;
            int i = f.getPageIndex();
            if (i < 0 || i >= pageAdapters.size() || pageAdapters.get(i) != pageAdapter) return;
            filterPage(pageAdapter, query.toString(),
                    i == 0 ? null : categoryAt(i - 1), getHiddenPackages());
        });
    }

    // ========== CategoryPageFragment.Callbacks 实现 ==========

    @Override
    public void onAppClicked(AppEntry entry) {
        launchApp(entry);
    }

    @Override
    public boolean onAppLongClicked(AppEntry entry, View anchor) {
        return showAppMenu(entry, anchor);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacks(periodicRefresh);
        ProcessLifecycleOwner.get().getLifecycle().removeObserver(processObserver);
        io.shutdownNow();
    }
}
