package com.quickstart.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 快跳过无障碍服务（借鉴 GKD / 开屏跳过 / SKIP 三种思路，全部本地规则，无订阅）。
 *
 * 三种跳过方法（可在 设置 → 跳过 中分别开关）：
 *  方法一 关键字匹配 —— 点击文本/描述含关键字的节点（内置关键字 + 用户自定义）
 *  方法二 控件匹配   —— 「跳过」文字与按钮分离时，点击其最近的可点击父控件；
 *                      同时点击控件 ID（viewIdResourceName）含 "skip" 的按钮
 *  方法三 坐标兜底   —— 前两种没命中时，模拟点击屏幕指定百分比位置（API 24+）
 *
 * 安全阀：
 *  - 总开关 ad_skip_enabled 关闭时，本服务即使被系统启用也完全不动作
 *  - 一切判断以「当前活动窗口」所属应用为准（事件可能来自通知/输入法等其它包）；
 *    自身界面与系统界面（系统设置、SystemUI 等）永不点击——系统设置的无障碍
 *    列表会显示本服务名「快跳过（自动跳过开屏广告）」，若不排除会反复误点
 *  - 默认仅在应用切换后的时间窗内生效（SKIP 思路：只跳开屏），且单次打开最多
 *    点击 4 次，点击后进入冷却，防止事件风暴导致连环误点
 *  - 跳过成功时在屏幕下方弹出蓝色提示气泡（应用名 + 命中方式），
 *    无需悬浮窗权限、不受 ROM 后台限制，可在设置中关闭
 */
public class AdSkipAccessibilityService extends AccessibilityService {

    /** 内置关键字（大小写不敏感匹配；用户可在设置中追加自定义关键字） */
    public static final String[] BUILTIN_KEYWORDS = {
        "跳过", "跳过广告", "关闭广告", "我知道了", "稍后再说", "不感兴趣", "Skip"
    };

    /** 永不点击的系统界面包名：这些界面会显示「跳过」等字样但绝不是广告 */
    private static final Set<String> SYSTEM_UI_PACKAGES = new HashSet<>(Arrays.asList(
        "android",
        "com.android.settings",
        "com.android.systemui",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller"
    ));

    public static final String PREF_MASTER_ENABLED = "ad_skip_enabled";
    private static final String PREF_KEYWORD_ENABLED = "ad_skip_keyword_enabled";
    private static final String PREF_WIDGET_ENABLED = "ad_skip_widget_enabled";
    private static final String PREF_COORD_ENABLED = "ad_skip_coordinate_enabled";
    private static final String PREF_COORD_X = "ad_skip_coordinate_x";
    private static final String PREF_COORD_Y = "ad_skip_coordinate_y";
    public static final String PREF_TIME_WINDOW = "ad_skip_time_window";
    public static final String PREF_CUSTOM_KEYWORDS = "ad_skip_custom_keywords";
    public static final String PREF_TOAST_ENABLED = "ad_skip_toast_enabled";

    /** 命中方式：searchAndClick 的返回值 */
    private static final int HIT_NONE = -1;
    private static final int HIT_KEYWORD = 0;
    private static final int HIT_VIEW_ID = 1;

    private static final String PREFS_NAME = "settings";

    /** 命中一次后的冷却：防止界面事件风暴导致连续误点 */
    private static final long CLICK_COOLDOWN_MS = 1200;

    /** 单次应用打开内最多点击次数：防止「点击→界面变化→再点击」的循环误点 */
    private static final int MAX_CLICKS_PER_OPEN = 4;

    /** 跳过提示悬浮窗显示时长（延长到 3.5 秒，给用户足够时间点击撤销） */
    private static final long OVERLAY_DURATION_MS = 3500;

    /** 撤销抑制的持续时间（毫秒）：点击撤销后 30 秒内不再对该应用执行关键字跳过 */
    private static final long SUPPRESS_DURATION_MS = 30_000;

    // ---- 配置缓存（设置改动时通过监听器即时刷新） ----
    private volatile boolean masterEnabled = true;
    private volatile boolean keywordEnabled = true;
    private volatile boolean widgetEnabled = true;
    private volatile boolean coordEnabled = false;
    private volatile int coordXPercent = 90;
    private volatile int coordYPercent = 8;
    private volatile long timeWindowMs = 10000;
    private volatile boolean toastEnabled = true;
    private volatile List<String> keywords = Collections.emptyList();

    // ---- 应用切换时间窗跟踪 ----
    private String activePackage = null;
    private long activeSince = 0;
    private long lastClickAt = 0;
    private int clickCount = 0;

    /** 应用显示名缓存（仅主线程访问） */
    private final Map<String, CharSequence> appLabels = new HashMap<>();

    // ---- 跳过提示悬浮窗（TYPE_ACCESSIBILITY_OVERLAY：无障碍服务专属，免权限） ----
    private WindowManager windowManager;
    private LinearLayout overlayView;
    private TextView overlayText;
    private Button undoButton;
    private boolean overlayAttached = false;
    private String lastSkipPkg;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable overlayHideRunnable = this::hideOverlay;

    // ---- 撤销抑制机制 ----
    /** 临时被抑制的应用包名 -> 抑制到期时间戳 */
    private final HashMap<String, Long> suppressedApps = new HashMap<>();
    /** 当前事件中关键字是否被临时抑制（matchSource 使用） */
    private boolean keywordSuppressedThisEvent = false;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (sp, key) -> {
                if (key == null || key.startsWith("ad_skip_")) refreshConfig();
            };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        refreshConfig();
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(prefsListener);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        mainHandler.removeCallbacks(overlayHideRunnable);
        hideOverlay();
        suppressedApps.clear();
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(prefsListener);
        return super.onUnbind(intent);
    }

    /** 从设置刷新配置缓存。所有键都带默认值，用户未打开过设置时行为与 XML 默认一致 */
    private void refreshConfig() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        masterEnabled = sp.getBoolean(PREF_MASTER_ENABLED, true);
        keywordEnabled = sp.getBoolean(PREF_KEYWORD_ENABLED, true);
        widgetEnabled = sp.getBoolean(PREF_WIDGET_ENABLED, true);
        coordEnabled = sp.getBoolean(PREF_COORD_ENABLED, false);
        coordXPercent = sp.getInt(PREF_COORD_X, 90);
        coordYPercent = sp.getInt(PREF_COORD_Y, 8);
        try {
            timeWindowMs = Long.parseLong(sp.getString(PREF_TIME_WINDOW, "10000"));
        } catch (NumberFormatException e) {
            timeWindowMs = 10000;
        }
        toastEnabled = sp.getBoolean(PREF_TOAST_ENABLED, true);

        List<String> list = new ArrayList<>();
        for (String k : BUILTIN_KEYWORDS) {
            list.add(k.toLowerCase(Locale.ROOT));
        }
        Set<String> custom = sp.getStringSet(PREF_CUSTOM_KEYWORDS, Collections.emptySet());
        for (String k : custom) {
            if (k != null && !k.trim().isEmpty()) {
                list.add(k.trim().toLowerCase(Locale.ROOT));
            }
        }
        keywords = list;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !masterEnabled) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastClickAt < CLICK_COOLDOWN_MS) return;

        // 事件级应用切换预判：新窗口出现且包名不同时重新计时。
        // 事件包名可能来自通知/输入法/悬浮窗等，系统界面不参与计时，
        // 避免时间窗被其它包的事件反复刷新而永不失效。
        CharSequence evPkgCs = event.getPackageName();
        String evPkg = evPkgCs != null ? evPkgCs.toString() : "";
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && !evPkg.isEmpty()
                && !evPkg.equals(getPackageName())
                && !SYSTEM_UI_PACKAGES.contains(evPkg)
                && !evPkg.equals(activePackage)) {
            activePackage = evPkg;
            activeSince = now;
            clickCount = 0;
        }

        // 一切点击判断以「当前活动窗口」为准：真正要操作的是活动窗口里的节点树，
        // 而不是事件来源的界面
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence pkgCs = root.getPackageName();
        String pkg = pkgCs != null ? pkgCs.toString() : "";

        // 自身界面（设置里就有「跳过」字样）与系统界面（系统设置的无障碍列表
        // 会显示服务名「快跳过（自动跳过开屏广告）」）绝不点击
        if (pkg.isEmpty() || pkg.equals(getPackageName())
                || SYSTEM_UI_PACKAGES.contains(pkg)) {
            root.recycle();
            return;
        }

        // 活动窗口所属应用变化 → 重新计时（冷启动、后台回切都会刷新时间窗）
        if (!pkg.equals(activePackage)) {
            activePackage = pkg;
            activeSince = now;
            clickCount = 0;
        }
        if (timeWindowMs > 0 && now - activeSince > timeWindowMs) {
            root.recycle();
            return;
        }
        if (clickCount >= MAX_CLICKS_PER_OPEN) {
            root.recycle();
            return;
        }

        // ---- 临时抑制检查（撤销功能） ----
        // 清理过期抑制项，当前包名若在抑制列表中则跳过关键字匹配
        long nowSuppress = SystemClock.elapsedRealtime();
        Iterator<Map.Entry<String, Long>> suppressIt = suppressedApps.entrySet().iterator();
        while (suppressIt.hasNext()) {
            if (nowSuppress >= suppressIt.next().getValue()) suppressIt.remove();
        }
        keywordSuppressedThisEvent = suppressedApps.containsKey(pkg);

        int hit = searchAndClick(root);
        if (hit != HIT_NONE) {
            lastClickAt = now;
            clickCount++;
            if (toastEnabled) {
                showSkipToast(pkg, hit == HIT_KEYWORD ? "已跳过广告（关键字）" : "已跳过广告（控件 ID）");
            }
        } else if (coordEnabled) {
            performCoordinateTap();
            lastClickAt = now;
            clickCount++;
            if (toastEnabled) {
                showSkipToast(pkg, "已模拟点击跳过位置（坐标）");
            }
        }
    }

    /**
     * 迭代 DFS 查找并点击跳过按钮，返回命中方式（HIT_KEYWORD / HIT_VIEW_ID），
     * 未命中返回 HIT_NONE。负责回收栈中剩余节点（与旧实现一致的保守回收策略：
     * 已点击节点不回收）。
     */
    private int searchAndClick(AccessibilityNodeInfo root) {
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            AccessibilityNodeInfo node = stack.pop();
            int source = matchSource(node);
            if (source != HIT_NONE) {
                AccessibilityNodeInfo target = findClickableAncestor(node);
                if (target != null) {
                    try {
                        boolean clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        return clicked ? source : HIT_NONE;
                    } finally {
                        if (target != node) node.recycle();
                        safeRecycle(stack, root, target);
                    }
                }
                // 没找到可点击祖先 → 继续搜索（其子节点照常入栈）
            }
            for (int i = node.getChildCount() - 1; i >= 0; i--) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) stack.push(child);
            }
        }
        root.recycle();
        return HIT_NONE;
    }

    /** 节点是否命中任一开启的匹配方法，返回命中方式 */
    private int matchSource(AccessibilityNodeInfo node) {
        if (keywordEnabled && !keywordSuppressedThisEvent && matchesKeyword(node)) return HIT_KEYWORD;
        if (widgetEnabled && matchesViewId(node)) return HIT_VIEW_ID;
        return HIT_NONE;
    }

    /** 方法一：文本 / 内容描述含关键字 */
    private boolean matchesKeyword(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        if (text != null && containsKeyword(text.toString())) return true;
        CharSequence desc = node.getContentDescription();
        return desc != null && containsKeyword(desc.toString());
    }

    private boolean containsKeyword(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        for (String kw : keywords) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    /** 方法二（ID 特征）：控件 ID 含 skip，如 com.xxx:id/skip、id/count_down_skip */
    private boolean matchesViewId(AccessibilityNodeInfo node) {
        CharSequence id = node.getViewIdResourceName();
        return id != null && id.toString().toLowerCase(Locale.ROOT).contains("skip");
    }

    /**
     * 方法二核心：返回可点击的跳过目标。
     * 自身可点击直接返回自身；否则向上找最近的可点击父控件（中间节点逐个回收）。
     */
    private AccessibilityNodeInfo findClickableAncestor(AccessibilityNodeInfo node) {
        if (node.isClickable()) return node;
        if (!widgetEnabled) return null;
        AccessibilityNodeInfo cur = node.getParent();
        while (cur != null && !cur.isClickable()) {
            AccessibilityNodeInfo parent = cur.getParent();
            cur.recycle();
            cur = parent;
        }
        return cur;
    }

    /** 方法三：坐标兜底 —— 模拟点击屏幕指定百分比位置（dispatchGesture 需 API 24+） */
    private void performCoordinateTap() {
        if (Build.VERSION.SDK_INT < 24) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int x = Math.min(dm.widthPixels - 1, Math.max(1, dm.widthPixels * coordXPercent / 100));
        int y = Math.min(dm.heightPixels - 1, Math.max(1, dm.heightPixels * coordYPercent / 100));
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 50));
        dispatchGesture(builder.build(), null, null);
    }

    /** 跳过成功提示：应用名 + 命中方式 */
    private void showSkipToast(String pkg, String method) {
        showSkipFeedback(pkg, method);
    }

    /**
     * 跳过成功反馈。用无障碍悬浮窗（TYPE_ACCESSIBILITY_OVERLAY）而不是 Toast：
     * Toast 在 MIUI/HyperOS 等系统上会被「后台弹出限制」静默拦截，
     * 而无障碍悬浮窗随无障碍服务天然获得显示能力，无需任何额外权限。
     * TYPE_ACCESSIBILITY_OVERLAY 需 API 22+，更低版本退回 Toast。
     *
     * 悬浮窗内包含「撤销」按钮——用户误触发关键字跳过时，可点击撤销临时屏蔽
     * 该应用的关键字跳过（30 秒）。
     */
    private void showSkipFeedback(String pkg, String method) {
        lastSkipPkg = pkg;
        String text = "快跳过 ·「" + appLabel(pkg) + "」" + method;
        if (Build.VERSION.SDK_INT >= 22) {
            showOverlay(text);
        } else {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        }
    }

    /** 显示/刷新提示悬浮窗，OVERLAY_DURATION_MS 后自动消失 */
    private void showOverlay(String text) {
        try {
            if (windowManager == null) {
                windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            }
            if (overlayView == null) {
                overlayView = buildOverlayView();
            }
            overlayText.setText(text);
            if (!overlayAttached) {
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                lp.y = (int) (64 * getResources().getDisplayMetrics().density);
                windowManager.addView(overlayView, lp);
                overlayAttached = true;
            }
            mainHandler.removeCallbacks(overlayHideRunnable);
            mainHandler.postDelayed(overlayHideRunnable, OVERLAY_DURATION_MS);
        } catch (Throwable t) {
            // 悬浮窗异常时退回 Toast，保证有反馈
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 构建提示悬浮窗：LinearLayout 内含提示文字 + 撤销按钮。
     * 撤销按钮点击后将当前应用的关键字跳过临时屏蔽 30 秒，防止误触。
     */
    private LinearLayout buildOverlayView() {
        float density = getResources().getDisplayMetrics().density;
        int cornerRadius = (int) (22 * density);

        // 外层容器
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);

        GradientDrawable containerBg = new GradientDrawable();
        containerBg.setColor(0xF01565C0);
        containerBg.setCornerRadius(cornerRadius);
        container.setBackground(containerBg);
        container.setPadding((int) (18 * density), (int) (10 * density),
                (int) (10 * density), (int) (10 * density));

        // 提示文字
        overlayText = new TextView(this);
        overlayText.setTextColor(0xFFFFFFFF);
        overlayText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        overlayText.setLayoutParams(textLp);
        container.addView(overlayText);

        // 撤销按钮
        undoButton = new Button(this);
        undoButton.setText("撤销");
        undoButton.setTextColor(0xFFFFFFFF);
        undoButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        undoButton.setAllCaps(false);
        undoButton.setMinWidth(0);
        undoButton.setClickable(true);
        undoButton.setFocusable(true);

        // 撤销按钮样式：半透明白色圆角背景
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0x33FFFFFF);
        btnBg.setCornerRadius(cornerRadius);
        undoButton.setBackground(btnBg);

        int btnPadH = (int) (14 * density);
        int btnPadV = (int) (6 * density);
        undoButton.setPadding(btnPadH, btnPadV, btnPadH, btnPadV);

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.leftMargin = (int) (12 * density);
        undoButton.setLayoutParams(btnLp);

        undoButton.setOnClickListener(v -> undoSkip());
        container.addView(undoButton);

        return container;
    }

    /** 撤销按钮点击处理：临时屏蔽当前应用的关键字跳过 30 秒 */
    private void undoSkip() {
        if (lastSkipPkg != null) {
            suppressedApps.put(lastSkipPkg,
                    SystemClock.elapsedRealtime() + SUPPRESS_DURATION_MS);
            Toast.makeText(this,
                    "已撤销，30 秒内不再对「" + appLabel(lastSkipPkg) + "」执行关键字跳过",
                    Toast.LENGTH_SHORT).show();
        }
        hideOverlay();
    }

    /** 移除提示悬浮窗 */
    private void hideOverlay() {
        if (overlayAttached && overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Throwable ignored) {
            }
            overlayAttached = false;
        }
    }

    /** 应用显示名（带缓存；仅主线程调用） */
    private CharSequence appLabel(String pkg) {
        CharSequence cached = appLabels.get(pkg);
        if (cached != null) return cached;
        CharSequence label = pkg;
        try {
            CharSequence l = getPackageManager()
                    .getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0));
            if (l != null && l.length() > 0) label = l;
        } catch (Exception ignored) {
        }
        appLabels.put(pkg, label);
        return label;
    }

    /** 回收栈中剩余节点 + root；keep 为刚执行点击的节点，跳过不回收 */
    private void safeRecycle(Deque<AccessibilityNodeInfo> stack,
                             AccessibilityNodeInfo root, AccessibilityNodeInfo keep) {
        while (!stack.isEmpty()) {
            AccessibilityNodeInfo n = stack.pop();
            if (n != keep && n != root) n.recycle();
        }
        if (root != keep) root.recycle();
    }

    /** 本服务是否已在系统中启用（设置页展示状态用） */
    public static boolean isServiceEnabled(Context context) {
        AccessibilityManager am = (AccessibilityManager)
                context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        String flat = new ComponentName(context, AdSkipAccessibilityService.class).flattenToString();
        for (AccessibilityServiceInfo info :
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)) {
            if (flat.equals(info.getId())) return true;
        }
        return false;
    }

    @Override
    public void onInterrupt() {}
}
