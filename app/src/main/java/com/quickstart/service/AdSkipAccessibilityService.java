package com.quickstart.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
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
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.quickstart.util.AdSkipStore;

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
import java.util.regex.Pattern;

/**
 * 快跳过无障碍服务（借鉴 GKD / 开屏跳过 / SKIP 思路，全部本地规则，无订阅）。
 *
 * 四种跳过方法（可在 设置 → 跳过 中分别开关）：
 *  方法一 关键字匹配 —— 点击文本/描述含关键字的节点（内置 + 自定义；自定义支持 re: 前缀正则）
 *  方法二 控件匹配   —— 「跳过」文字与按钮分离时，点击其最近的可点击父控件；
 *                      同时点击控件 ID（viewIdResourceName）含特征词的按钮
 *  方法三 坐标兜底   —— 前两种没命中时，模拟点击屏幕指定百分比位置（API 24+），可显示落点标记
 *  方法四 弹窗关闭   —— 命中弹窗关闭词（关闭/×/以后再说…）时点击（默认关，防误点）
 *
 * 精细化防误点（对照 GKD/AutoSkip 的做法）：
 *  - 关键字命中要求文本长度 ≤ 上限：长段落/通知里出现「跳过」二字不会误点
 *  - 可见性检查：只点当前可见节点，滚出屏幕的隐藏节点不点
 *  - 关键字排除词：文本同时含排除词（登录/更新…）时不点
 *  - 锁屏/灭屏保护、扫描节流（content-changed 事件风暴不反复全树扫描）、
 *    单次扫描节点数上限（复杂界面不卡顿）
 *  - 「跳过」文字本身不可点击时，用手势点击其屏幕坐标兜底（API 24+）
 *
 * 安全阀：
 *  - 总开关 ad_skip_enabled 关闭时，本服务即使被系统启用也完全不动作
 *  - 一切判断以「当前活动窗口」所属应用为准；自身界面与系统界面永不点击
 *  - 应用名单：所有应用生效 / 仅名单内 / 名单内不生效；悬浮条「不再跳过」可永久屏蔽
 *  - 默认仅在应用切换后的时间窗内生效（只跳开屏），点击有冷却、单次打开有上限，
 *    全部可在设置中调节
 *  - 跳过成功时在屏幕下方弹出蓝色提示气泡（应用名 + 命中方式，可撤销/永久屏蔽），
 *    无需悬浮窗权限，可在设置中关闭
 */
public class AdSkipAccessibilityService extends AccessibilityService {

    /** 内置关键字（大小写不敏感匹配；用户可在设置中追加自定义关键字） */
    public static final String[] BUILTIN_KEYWORDS = {
        "跳过", "跳过广告", "关闭广告", "我知道了", "稍后再说", "不感兴趣", "Skip"
    };

    /** 内置关键字排除词：文本同时含这些词时不做关键字/弹窗/倒计时命中（防误点，可追加） */
    public static final String[] BUILTIN_TEXT_EXCLUDES = {"登录", "更新", "升级"};

    /** 内置弹窗关闭词（方法四，默认关） */
    public static final String[] BUILTIN_POPUP_WORDS = {
        "关闭", "×", "✕", "以后再说", "放弃", "残忍拒绝"
    };

    /** 弹窗词的二次排除：含这些词的「关闭」按钮（关闭应用/退出…）绝不点 */
    private static final String[] POPUP_TEXT_EXCLUDES = {"应用", "退出", "更新", "卸载"};

    /** 方法二 ID 特征词：viewIdResourceName 含任一即命中 */
    private static final String[] ID_PATTERNS = {"skip", "jump_over", "tiaoguo"};
    /** 方法二 ID 排除词：如 skip_login（跳过登录不该自动点） */
    private static final String[] ID_EXCLUDES = {"login", "logon"};

    /** 纯倒计时按钮（方法一附加，默认关）：如「5s」「12S」 */
    private static final Pattern COUNTDOWN_PATTERN =
            Pattern.compile("^\\d{1,3}s?$", Pattern.CASE_INSENSITIVE);

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

    /** 单次全树扫描的节点数上限：复杂界面（聊天列表等）content-changed 时不至于卡顿 */
    private static final int MAX_SCAN_NODES = 800;

    /** 单次应用打开内最多扫描次数（SKIP 的 scanTimes 思路）：时间窗设为「不限制」时防持续扫描耗电 */
    private static final int MAX_SCANS_PER_OPEN = 60;

    /** 点击成功后的连跳重试延迟：部分广告点掉跳过后还会弹二次弹窗（gkd 做法） */
    private static final long FOLLOW_UP_DELAY_MS = 600;

    public static final String PREF_MASTER_ENABLED = "ad_skip_enabled";
    private static final String PREF_KEYWORD_ENABLED = "ad_skip_keyword_enabled";
    private static final String PREF_WIDGET_ENABLED = "ad_skip_widget_enabled";
    private static final String PREF_COORD_ENABLED = "ad_skip_coordinate_enabled";
    private static final String PREF_COORD_X = "ad_skip_coordinate_x";
    private static final String PREF_COORD_Y = "ad_skip_coordinate_y";
    public static final String PREF_TIME_WINDOW = "ad_skip_time_window";
    public static final String PREF_CUSTOM_KEYWORDS = "ad_skip_custom_keywords";
    public static final String PREF_TOAST_ENABLED = "ad_skip_toast_enabled";

    // ---- 精细化设置键 ----
    private static final String PREF_VISIBLE_ONLY = "ad_skip_visible_only";
    private static final String PREF_MAX_TEXT_LEN = "ad_skip_max_text_len";
    private static final String PREF_TEXT_EXCLUDES = "ad_skip_text_excludes";
    private static final String PREF_COUNTDOWN_ENABLED = "ad_skip_countdown_enabled";
    private static final String PREF_ID_EXTENDED = "ad_skip_id_extended";
    private static final String PREF_POPUP_ENABLED = "ad_skip_popup_enabled";
    private static final String PREF_GESTURE_CLICK = "ad_skip_gesture_click";
    private static final String PREF_COORD_PREVIEW = "ad_skip_coord_preview";
    private static final String PREF_KEYGUARD_SAFE = "ad_skip_keyguard_safe";
    private static final String PREF_SCAN_INTERVAL = "ad_skip_scan_interval";
    private static final String PREF_COOLDOWN = "ad_skip_cooldown";
    private static final String PREF_MAX_CLICKS = "ad_skip_max_clicks";
    private static final String PREF_OVERLAY_SECONDS = "ad_skip_overlay_seconds";
    private static final String PREF_VIBRATE = "ad_skip_vibrate";

    /** 命中方式：matchNode 的返回值 */
    private static final int HIT_NONE = -1;
    private static final int HIT_KEYWORD = 0;
    private static final int HIT_VIEW_ID = 1;
    private static final int HIT_COUNTDOWN = 2;
    private static final int HIT_POPUP = 3;

    /** searchAndClick 的命中结果 */
    private static final class Hit {
        final int type;
        final boolean byGesture;
        Hit(int type, boolean byGesture) { this.type = type; this.byGesture = byGesture; }
    }

    private static final String PREFS_NAME = "settings";

    // ---- 配置缓存（设置改动时通过监听器即时刷新） ----
    private volatile boolean masterEnabled = true;
    private volatile boolean keywordEnabled = true;
    private volatile boolean widgetEnabled = true;
    private volatile boolean coordEnabled = false;
    private volatile int coordXPercent = 90;
    private volatile int coordYPercent = 8;
    private volatile long timeWindowMs = 10000;
    private volatile boolean toastEnabled = true;
    private volatile boolean visibleOnly = true;
    private volatile int maxTextLen = 12;
    private volatile boolean countdownEnabled = false;
    private volatile boolean idExtended = true;
    private volatile boolean popupEnabled = false;
    private volatile boolean gestureClick = true;
    private volatile boolean coordPreview = true;
    private volatile boolean keyguardSafe = true;
    private volatile int scanIntervalMs = 150;
    private volatile int cooldownMs = 1200;
    private volatile int maxClicks = 4;
    private volatile int overlaySeconds = 4;
    private volatile boolean vibrateEnabled = false;
    private volatile List<String> keywords = Collections.emptyList();
    private volatile List<String> excludeWords = Collections.emptyList();
    private volatile List<Pattern> customRegexes = Collections.emptyList();

    // ---- 应用切换时间窗跟踪 ----
    private String activePackage = null;
    private long activeSince = 0;
    private long lastClickAt = 0;
    private long lastScanAt = 0;
    private int clickCount = 0;
    /** 单次应用打开内已执行的全树扫描/快路径查询次数（换应用清零） */
    private int scanCount = 0;

    /** 已启用输入法的包名集合：IME 的窗口事件不参与应用切换计时（防时间窗漂移） */
    private volatile Set<String> imePackages = Collections.emptySet();

    /** 应用显示名缓存（仅主线程访问） */
    private final Map<String, CharSequence> appLabels = new HashMap<>();

    // ---- 跳过提示悬浮窗（TYPE_ACCESSIBILITY_OVERLAY：无障碍服务专属，免权限） ----
    private WindowManager windowManager;
    private LinearLayout overlayView;
    private TextView overlayText;
    private boolean overlayAttached = false;
    private String lastSkipPkg;
    /** 坐标落点标记（校准坐标兜底用，短时显示） */
    private View markerView;
    private boolean markerAttached = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable overlayHideRunnable = this::hideOverlay;
    private final Runnable markerHideRunnable = this::hideMarker;

    // ---- 撤销抑制机制 ----
    /** 临时被抑制的应用包名 -> 抑制到期时间戳 */
    private final HashMap<String, Long> suppressedApps = new HashMap<>();
    /** 当前事件中文字类匹配（关键字/弹窗/倒计时）是否被临时抑制（matchNode 使用） */
    private boolean textSuppressedThisEvent = false;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (sp, key) -> {
                if (key == null || key.startsWith("ad_skip_")) refreshConfig();
            };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        refreshConfig();
        refreshImePackages();
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(prefsListener);
    }

    /** 缓存已启用输入法的包名（onServiceConnected / 设置变化时刷新一次，事件里只查集合） */
    private void refreshImePackages() {
        try {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            Set<String> set = new HashSet<>();
            if (imm != null) {
                for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
                    set.add(info.getPackageName());
                }
            }
            imePackages = set;
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        mainHandler.removeCallbacks(overlayHideRunnable);
        mainHandler.removeCallbacks(markerHideRunnable);
        mainHandler.removeCallbacks(followUpScan);
        hideOverlay();
        hideMarker();
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
        visibleOnly = sp.getBoolean(PREF_VISIBLE_ONLY, true);
        maxTextLen = Math.max(5, sp.getInt(PREF_MAX_TEXT_LEN, 12));
        countdownEnabled = sp.getBoolean(PREF_COUNTDOWN_ENABLED, false);
        idExtended = sp.getBoolean(PREF_ID_EXTENDED, true);
        popupEnabled = sp.getBoolean(PREF_POPUP_ENABLED, false);
        gestureClick = sp.getBoolean(PREF_GESTURE_CLICK, true);
        coordPreview = sp.getBoolean(PREF_COORD_PREVIEW, true);
        keyguardSafe = sp.getBoolean(PREF_KEYGUARD_SAFE, true);
        scanIntervalMs = Math.max(0, sp.getInt(PREF_SCAN_INTERVAL, 150));
        cooldownMs = Math.max(0, sp.getInt(PREF_COOLDOWN, 1200));
        maxClicks = Math.max(1, sp.getInt(PREF_MAX_CLICKS, 4));
        overlaySeconds = Math.max(1, sp.getInt(PREF_OVERLAY_SECONDS, 4));
        vibrateEnabled = sp.getBoolean(PREF_VIBRATE, false);

        // 关键字 = 内置 + 自定义；自定义以 re: 开头的按正则编译（忽略大小写，失败回退为普通关键字）
        List<String> list = new ArrayList<>();
        List<Pattern> regexes = new ArrayList<>();
        for (String k : BUILTIN_KEYWORDS) {
            list.add(k.toLowerCase(Locale.ROOT));
        }
        Set<String> custom = sp.getStringSet(PREF_CUSTOM_KEYWORDS, Collections.emptySet());
        for (String k : custom) {
            if (k == null || k.trim().isEmpty()) continue;
            String v = k.trim();
            if (v.startsWith("re:")) {
                try {
                    regexes.add(Pattern.compile(v.substring(3), Pattern.CASE_INSENSITIVE));
                } catch (Exception ignored) {
                    list.add(v.toLowerCase(Locale.ROOT));
                }
            } else {
                list.add(v.toLowerCase(Locale.ROOT));
            }
        }
        keywords = list;
        customRegexes = regexes;

        List<String> excludes = new ArrayList<>();
        for (String w : BUILTIN_TEXT_EXCLUDES) excludes.add(w.toLowerCase(Locale.ROOT));
        for (String w : sp.getStringSet(PREF_TEXT_EXCLUDES, Collections.emptySet())) {
            if (w != null && !w.trim().isEmpty()) excludes.add(w.trim().toLowerCase(Locale.ROOT));
        }
        excludeWords = excludes;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !masterEnabled) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }

        // 副屏/虚拟屏事件不处理（折叠屏、投屏副屏上的广告不做自动点击）
        if (Build.VERSION.SDK_INT >= 30 && event.getDisplayId() != Display.DEFAULT_DISPLAY) {
            return;
        }

        // 锁屏/灭屏保护：锁屏界面、灭屏状态不点击（部分 ROM 锁屏包名不在系统排除表里）
        if (keyguardSafe && isScreenLocked()) return;

        long now = SystemClock.elapsedRealtime();
        if (now - lastClickAt < cooldownMs) return;

        // 事件级应用切换预判：新窗口出现且包名不同时重新计时。
        // 事件包名可能来自通知/输入法/悬浮窗等，系统界面不参与计时，
        // 避免时间窗被其它包的事件反复刷新而永不失效。
        CharSequence evPkgCs = event.getPackageName();
        String evPkg = evPkgCs != null ? evPkgCs.toString() : "";
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && !evPkg.isEmpty()
                && !evPkg.equals(getPackageName())
                && !SYSTEM_UI_PACKAGES.contains(evPkg)
                && !imePackages.contains(evPkg)
                && !evPkg.equals(activePackage)) {
            activePackage = evPkg;
            activeSince = now;
            clickCount = 0;
            scanCount = 0;
        }

        // 扫描节流：content-changed 事件风暴（动画/倒计时/列表刷新）时限制全树扫描频率；
        // window-state-changed（新窗口）不节流，保证开屏第一时间响应
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && scanIntervalMs > 0 && now - lastScanAt < scanIntervalMs) {
            return;
        }
        lastScanAt = now;

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

        // 应用名单 / 「不再跳过」永久屏蔽
        if (!AdSkipStore.allowsPackage(this, pkg)) {
            root.recycle();
            return;
        }

        // 活动窗口所属应用变化 → 重新计时（冷启动、后台回切都会刷新时间窗）
        if (!pkg.equals(activePackage)) {
            activePackage = pkg;
            activeSince = now;
            clickCount = 0;
            scanCount = 0;
        }
        if (timeWindowMs > 0 && now - activeSince > timeWindowMs) {
            root.recycle();
            return;
        }
        if (clickCount >= maxClicks || scanCount >= MAX_SCANS_PER_OPEN) {
            root.recycle();
            return;
        }

        // ---- 临时抑制检查（撤销功能） ----
        // 清理过期抑制项，当前包名若在抑制列表中则跳过文字类匹配
        long nowSuppress = SystemClock.elapsedRealtime();
        Iterator<Map.Entry<String, Long>> suppressIt = suppressedApps.entrySet().iterator();
        while (suppressIt.hasNext()) {
            if (nowSuppress >= suppressIt.next().getValue()) suppressIt.remove();
        }
        textSuppressedThisEvent = suppressedApps.containsKey(pkg);

        scanCount++;
        Hit hit = searchAndClick(root);
        if (hit != null) {
            recordHit(pkg, hit);
            scheduleFollowUp(); // 连跳重试：部分广告点掉跳过后还有二次弹窗
        } else if (coordEnabled) {
            performCoordinateTap();
            lastClickAt = now;
            clickCount++;
            String label = "已模拟点击跳过位置（坐标）";
            AdSkipStore.recordSkip(this, pkg, label);
            if (vibrateEnabled) vibrateBrief();
            if (toastEnabled) showSkipFeedback(pkg, label);
        }
    }

    /** 记录一次命中：冷却、计数、统计、震动、提示 */
    private void recordHit(String pkg, Hit hit) {
        lastClickAt = SystemClock.elapsedRealtime();
        clickCount++;
        String label = hitLabel(hit);
        AdSkipStore.recordSkip(this, pkg, label);
        if (vibrateEnabled) vibrateBrief();
        if (toastEnabled) showSkipFeedback(pkg, label);
    }

    private final Runnable followUpScan = this::runFollowUpScan;

    /** 连跳重试：点击成功 600ms 后主动再扫一轮（点掉跳过后常见的二次弹窗） */
    private void scheduleFollowUp() {
        mainHandler.removeCallbacks(followUpScan);
        mainHandler.postDelayed(followUpScan, FOLLOW_UP_DELAY_MS);
    }

    private void runFollowUpScan() {
        if (!masterEnabled) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence pkgCs = root.getPackageName();
        String pkg = pkgCs != null ? pkgCs.toString() : "";
        if (pkg.isEmpty() || pkg.equals(getPackageName())
                || SYSTEM_UI_PACKAGES.contains(pkg)
                || !AdSkipStore.allowsPackage(this, pkg)) {
            root.recycle();
            return;
        }
        // 用户切走了就不再补点
        if (!pkg.equals(activePackage)) {
            root.recycle();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (timeWindowMs > 0 && now - activeSince > timeWindowMs) {
            root.recycle();
            return;
        }
        // 不检查命中冷却：重试本身就是点击后的主动补扫，次数由 maxClicks 封顶
        if (clickCount >= maxClicks) {
            root.recycle();
            return;
        }
        // 撤销抑制同样生效
        long nowSuppress = SystemClock.elapsedRealtime();
        Iterator<Map.Entry<String, Long>> suppressIt = suppressedApps.entrySet().iterator();
        while (suppressIt.hasNext()) {
            if (nowSuppress >= suppressIt.next().getValue()) suppressIt.remove();
        }
        textSuppressedThisEvent = suppressedApps.containsKey(pkg);

        scanCount++;
        Hit hit = searchAndClick(root);
        if (hit != null) {
            recordHit(pkg, hit);
            scheduleFollowUp();
        }
    }

    /** 锁屏/灭屏检测：键盘锁激活或屏幕未亮时视为锁定 */
    @SuppressWarnings({"deprecation", "RedundantSuppression"})
    private boolean isScreenLocked() {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null && km.inKeyguardRestrictedInputMode()) return true;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && !pm.isInteractive();
    }

    /** 命中结果对应的提示/统计文案 */
    private String hitLabel(Hit hit) {
        String base;
        switch (hit.type) {
            case HIT_VIEW_ID: base = "已跳过广告（控件 ID）"; break;
            case HIT_COUNTDOWN: base = "已跳过广告（倒计时）"; break;
            case HIT_POPUP: base = "已关闭弹窗"; break;
            case HIT_KEYWORD:
            default: base = "已跳过广告（关键字）"; break;
        }
        return hit.byGesture ? base + "·手势" : base;
    }

    /** 轻微震动反馈（约 60ms） */
    @SuppressWarnings({"deprecation", "RedundantSuppression"})
    private void vibrateBrief() {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(60,
                        VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(60);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 查找并点击跳过控件，命中返回 Hit，未命中返回 null（root 在所有路径上都会被回收）。
     *
     * 快路径：普通关键字用框架级 findAccessibilityNodeInfosByText 一次 binder 批量查询
     * （大小写不敏感包含匹配，与 containsKeyword 语义一致），替代全树遍历；
     * 慢路径（DFS）：自定义 re: 正则 / 弹窗词 / 倒计时 / 控件 ID 仍需逐节点匹配。
     */
    private Hit searchAndClick(AccessibilityNodeInfo root) {
        if (keywordEnabled && !textSuppressedThisEvent && !keywords.isEmpty()) {
            Hit hit = searchByTextIndex(root);
            if (hit != null) return hit;
        }
        boolean needDfs = !customRegexes.isEmpty() || popupEnabled || countdownEnabled || widgetEnabled;
        if (!needDfs) {
            root.recycle(); // 只开关键字方法：快路径已查完，无需 DFS
            return null;
        }
        return searchByDfs(root);
    }

    /**
     * 关键字快路径：对每个关键字调 findAccessibilityNodeInfosByText 合并候选，
     * 逐个做可见性/长度/排除词验证后点击。未命中返回 null（root 不回收，交给调用方）。
     */
    private Hit searchByTextIndex(AccessibilityNodeInfo root) {
        Set<AccessibilityNodeInfo> seen = new HashSet<>();
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        try {
            for (String kw : keywords) {
                List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(kw);
                if (found == null) continue;
                for (AccessibilityNodeInfo n : found) {
                    if (n != null && seen.add(n)) candidates.add(n);
                }
            }
        } catch (Throwable t) {
            // 框架查询异常：清空候选退回 DFS
            candidates.clear();
        }
        Hit hit = null;
        for (AccessibilityNodeInfo n : candidates) {
            if (hit == null && validateKeywordCandidate(n)) {
                hit = performClickOn(n, HIT_KEYWORD);
            }
            n.recycle();
        }
        if (hit != null) root.recycle();
        return hit; // 未命中：root 留给调用方走 DFS
    }

    /** 关键字候选验证：可见性 + 文本长度上限 + 排除词 */
    private boolean validateKeywordCandidate(AccessibilityNodeInfo node) {
        if (visibleOnly && !node.isVisibleToUser()) return false;
        String text = nodeText(node);
        if (text == null) return false;
        if (text.length() > maxTextLen) return false;
        return !containsAny(text, excludeWords);
    }

    /**
     * 慢路径 DFS：逐节点匹配（正则/弹窗/倒计时/ID），带节点数预算（MAX_SCAN_NODES），
     * 超预算即停。所有路径（含命中）都回收全部节点。
     */
    private Hit searchByDfs(AccessibilityNodeInfo root) {
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
        stack.push(root);
        int visited = 0;
        AccessibilityNodeInfo node = root;
        while (!stack.isEmpty()) {
            node = stack.pop();
            visited++;
            int source = matchNode(node);
            if (source != HIT_NONE) {
                Hit hit = performClickOn(node, source);
                if (hit != null) {
                    if (node != root) node.recycle();
                    safeRecycle(stack, root, null);
                    return hit;
                }
                // 点击失败：node 保持有效，子节点照常入栈继续搜索
            }
            if (visited >= MAX_SCAN_NODES) break;
            for (int i = node.getChildCount() - 1; i >= 0; i--) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) stack.push(child);
            }
        }
        if (node != root) node.recycle();
        safeRecycle(stack, root, null);
        return null;
    }

    /**
     * 对命中节点执行点击（不负责回收，回收由调用方按需处理）：
     * 优先 ACTION_CLICK（自身可点击，或控件匹配开启时最近的可点击祖先）；
     * 无路可走时按手势兜底设置，用 dispatchGesture 点击节点屏幕坐标中心。
     * 成功返回 Hit，失败返回 null（失败时节点树未变，调用方可继续遍历）。
     */
    private Hit performClickOn(AccessibilityNodeInfo node, int source) {
        AccessibilityNodeInfo target = null;
        if (node.isClickable()) {
            target = node;
        } else if (widgetEnabled) {
            target = findClickableAncestor(node);
        }
        if (target != null) {
            boolean clicked = false;
            try {
                clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } catch (Throwable ignored) {
            }
            if (clicked) return new Hit(source, false);
            if (target != node) target.recycle();
            return null;
        }
        // 手势兜底：很多「跳过」文字未标 clickable，直接点其屏幕坐标中心
        if (gestureClick && Build.VERSION.SDK_INT >= 24 && node.isVisibleToUser()) {
            android.graphics.Rect bounds = new android.graphics.Rect();
            node.getBoundsInScreen(bounds);
            int cx = bounds.centerX(), cy = bounds.centerY();
            DisplayMetrics dm = getResources().getDisplayMetrics();
            if (cx > 0 && cy > 0 && cx < dm.widthPixels && cy < dm.heightPixels
                    && dispatchTap(cx, cy)) {
                return new Hit(source, true);
            }
        }
        return null;
    }

    /**
     * 节点是否命中任一开启的匹配方法，返回命中方式。
     * 优先级：弹窗 > 关键字 > 倒计时 > 控件 ID。
     */
    private int matchNode(AccessibilityNodeInfo node) {
        // 可见性检查：滚出屏幕/隐藏的节点不命中（可在设置中关闭）
        if (visibleOnly && !node.isVisibleToUser()) return HIT_NONE;

        String text = nodeText(node);

        // 方法四：弹窗关闭词（独立开关，默认关；文本需短小且不含危险词）
        if (popupEnabled && !textSuppressedThisEvent && text != null
                && text.length() <= 8
                && !containsAny(text, POPUP_TEXT_EXCLUDES)
                && containsAny(text, BUILTIN_POPUP_WORDS)) {
            return HIT_POPUP;
        }

        // 方法一：关键字（文本/描述，长度上限 + 排除词）
        if (keywordEnabled && !textSuppressedThisEvent && text != null
                && text.length() <= maxTextLen
                && !containsAny(text, excludeWords)
                && (containsKeyword(text) || matchesCustomRegex(text))) {
            return HIT_KEYWORD;
        }

        // 方法一附加：纯倒计时按钮（如「5s」），默认关
        if (countdownEnabled && !textSuppressedThisEvent && text != null
                && COUNTDOWN_PATTERN.matcher(text.trim()).matches()) {
            return HIT_COUNTDOWN;
        }

        // 方法二：控件 ID 特征（整体受方法二开关控制，扩展特征只是扩大词表）
        if (widgetEnabled) {
            CharSequence idCs = node.getViewIdResourceName();
            if (idCs != null) {
                String id = idCs.toString().toLowerCase(Locale.ROOT);
                boolean excluded = false;
                for (String ex : ID_EXCLUDES) {
                    if (id.contains(ex)) { excluded = true; break; }
                }
                if (!excluded) {
                    if (id.contains("skip")) return HIT_VIEW_ID;
                    if (idExtended) {
                        for (String p : ID_PATTERNS) {
                            if (id.contains(p)) return HIT_VIEW_ID;
                        }
                    }
                }
            }
        }
        return HIT_NONE;
    }

    /** 取节点主文本：text 优先，为空时用 contentDescription */
    private static String nodeText(AccessibilityNodeInfo node) {
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) return t.toString();
        CharSequence d = node.getContentDescription();
        return d != null ? d.toString() : null;
    }

    /** 文本是否含任一关键字（小写比较） */
    private boolean containsKeyword(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        for (String kw : keywords) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    /** 自定义 re: 正则是否命中 */
    private boolean matchesCustomRegex(String s) {
        List<Pattern> regexes = customRegexes;
        for (Pattern p : regexes) {
            if (p.matcher(s).find()) return true;
        }
        return false;
    }

    /** 文本（小写化后）是否含任一词 */
    private static boolean containsAny(String text, String[] words) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String w : words) {
            if (lower.contains(w.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** 文本（小写化后）是否含任一词（集合版） */
    private static boolean containsAny(String text, List<String> words) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String w : words) {
            if (lower.contains(w)) return true;
        }
        return false;
    }

    /** 向上找最近的可点击祖先（中间节点逐个回收；自身可点击时返回自身） */
    private AccessibilityNodeInfo findClickableAncestor(AccessibilityNodeInfo node) {
        if (node.isClickable()) return node;
        AccessibilityNodeInfo cur = node.getParent();
        while (cur != null && !cur.isClickable()) {
            AccessibilityNodeInfo parent = cur.getParent();
            cur.recycle();
            cur = parent;
        }
        return cur;
    }

    /** 用无障碍手势点击屏幕坐标（API 24+） */
    private boolean dispatchTap(int x, int y) {
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 50));
            return dispatchGesture(builder.build(), null, null);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 方法三：坐标兜底 —— 模拟点击屏幕指定百分比位置，可显示落点标记便于校准 */
    private void performCoordinateTap() {
        if (Build.VERSION.SDK_INT < 24) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int x = Math.min(dm.widthPixels - 1, Math.max(1, dm.widthPixels * coordXPercent / 100));
        int y = Math.min(dm.heightPixels - 1, Math.max(1, dm.heightPixels * coordYPercent / 100));
        if (dispatchTap(x, y) && coordPreview) showTapMarker(x, y);
    }

    /** 坐标落点标记：圆环短时显示在点击位置（600ms），帮助确认兜底位置是否准确 */
    private void showTapMarker(int x, int y) {
        try {
            if (windowManager == null) {
                windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            }
            if (markerView == null) {
                float density = getResources().getDisplayMetrics().density;
                int size = (int) (44 * density);
                GradientDrawable ring = new GradientDrawable();
                ring.setShape(GradientDrawable.OVAL);
                ring.setColor(0x220F45A7);
                ring.setStroke((int) (3 * density), 0xCC1976D2);
                markerView = new View(this);
                markerView.setBackground(ring);
                markerView.setLayoutParams(new WindowManager.LayoutParams(size, size));
            }
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    markerView.getLayoutParams().width,
                    markerView.getLayoutParams().height,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = x - lp.width / 2;
            lp.y = y - lp.height / 2;
            if (markerAttached) {
                windowManager.updateViewLayout(markerView, lp);
            } else {
                windowManager.addView(markerView, lp);
                markerAttached = true;
            }
            mainHandler.removeCallbacks(markerHideRunnable);
            mainHandler.postDelayed(markerHideRunnable, 600);
        } catch (Throwable ignored) {
        }
    }

    /** 移除坐标落点标记 */
    private void hideMarker() {
        if (markerAttached && markerView != null && windowManager != null) {
            try {
                windowManager.removeView(markerView);
            } catch (Throwable ignored) {
            }
            markerAttached = false;
        }
    }

    /**
     * 跳过成功反馈。用无障碍悬浮窗（TYPE_ACCESSIBILITY_OVERLAY）而不是 Toast：
     * Toast 在 MIUI/HyperOS 等系统上会被「后台弹出限制」静默拦截，
     * 而无障碍悬浮窗随无障碍服务天然获得显示能力，无需任何额外权限。
     * TYPE_ACCESSIBILITY_OVERLAY 需 API 22+，更低版本退回 Toast。
     *
     * 悬浮窗内含两个按钮：
     *  撤销     —— 30 秒内不对该应用做文字类跳过（误触时用）
     *  不再跳过 —— 永久屏蔽该应用（写入名单，设置 → 应用名单 可恢复）
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

    /** 显示/刷新提示悬浮窗，时长由「提示时长」设置决定 */
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
            mainHandler.postDelayed(overlayHideRunnable, overlaySeconds * 1000L);
        } catch (Throwable t) {
            // 悬浮窗异常时退回 Toast，保证有反馈
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 构建提示悬浮窗：LinearLayout 内含提示文字 + 撤销按钮 + 不再跳过按钮。
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

        container.addView(buildOverlayButton("撤销", this::undoSkip, density));
        container.addView(buildOverlayButton("不再跳过", this::muteCurrentApp, density));

        return container;
    }

    /** 悬浮窗内的小圆角按钮 */
    private Button buildOverlayButton(String label, Runnable action, float density) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setAllCaps(false);
        btn.setMinWidth(0);
        btn.setClickable(true);
        btn.setFocusable(true);

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0x33FFFFFF);
        btnBg.setCornerRadius((int) (22 * density));
        btn.setBackground(btnBg);

        int padH = (int) (12 * density);
        int padV = (int) (6 * density);
        btn.setPadding(padH, padV, padH, padV);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = (int) (8 * density);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> action.run());
        return btn;
    }

    /** 撤销按钮点击处理：临时屏蔽当前应用的文字类跳过 30 秒 */
    private void undoSkip() {
        if (lastSkipPkg != null) {
            suppressedApps.put(lastSkipPkg,
                    SystemClock.elapsedRealtime() + SUPPRESS_DURATION_MS);
            Toast.makeText(this,
                    "已撤销，30 秒内不再对「" + appLabel(lastSkipPkg) + "」执行文字跳过",
                    Toast.LENGTH_SHORT).show();
        }
        hideOverlay();
    }

    /** 「不再跳过」按钮：永久屏蔽当前应用（设置 → 应用名单 里可恢复） */
    private void muteCurrentApp() {
        if (lastSkipPkg != null) {
            AdSkipStore.setMuted(this, lastSkipPkg, true);
            Toast.makeText(this,
                    "已不再跳过「" + appLabel(lastSkipPkg)
                            + "」（设置 → 跳过 → 应用名单 中可恢复）",
                    Toast.LENGTH_LONG).show();
        }
        hideOverlay();
    }

    /** 撤销抑制的持续时间（毫秒）：点击撤销后 30 秒内不再对该应用执行文字类跳过 */
    private static final long SUPPRESS_DURATION_MS = 30_000;

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
        if (stack != null) {
            while (!stack.isEmpty()) {
                AccessibilityNodeInfo n = stack.pop();
                if (n != keep && n != root) n.recycle();
            }
        }
        if (root != null && root != keep) root.recycle();
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
