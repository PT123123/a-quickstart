package com.likpia.quickstartpro;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.likpia.quickstartpro.adapter.AppListAdapter;
import com.likpia.quickstartpro.model.AppEntry;
import com.likpia.quickstartpro.util.AppLoader;
import com.likpia.quickstartpro.util.T9Matcher;

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

    private static final String[] KEY_LETTERS = {
        "A", "", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sortLabel  = findViewById(R.id.sort_label);
        t9Hint     = findViewById(R.id.t9_hint);
        t9Display  = findViewById(R.id.t9_display);
        emptyHint  = findViewById(R.id.empty_hint);
        recycler   = findViewById(R.id.app_list);
        keypad     = findViewById(R.id.keypad);

        adapter = new AppListAdapter();
        adapter.setOnAppClickListener(this::launchApp);
        adapter.setOnAppLongClickListener(this::showAppMenu);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        sortLabel.setOnClickListener(v -> showSortMenu());
        findViewById(R.id.btn_clear_top).setOnClickListener(v -> clearQuery());

        setupKeypad();
        setupCategoryChips();
        loadAppsAsync();
    }

    private void setupKeypad() {
        // 数字键 1~9（文字已在 XML 写好，只需绑定点击）
        int[] keyIds = {R.id.key_1, R.id.key_2, R.id.key_3, R.id.key_4, R.id.key_5,
                        R.id.key_6, R.id.key_7, R.id.key_8, R.id.key_9};
        for (int i = 0; i < keyIds.length; i++) {
            int digit = i + 1;
            keypad.findViewById(keyIds[i]).setOnClickListener(v -> onDigitPressed(digit));
        }
        keypad.findViewById(R.id.key_0).setOnClickListener(v -> onDigitPressed(0));
        keypad.findViewById(R.id.key_clear).setOnClickListener(v -> clearQuery());
        keypad.findViewById(R.id.key_back).setOnClickListener(v -> onBackspace());
    }

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
        doFilter();
        // 唯一匹配时自动启动
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
        PopupMenu popup = new PopupMenu(this, sortLabel);
        popup.getMenu().add(0, 1, 0, "智能排序");
        popup.getMenu().add(0, 2, 1, "最近安装");
        popup.getMenu().add(0, 3, 2, "字母顺序");
        popup.getMenu().add(0, 4, 3, "使用频率");
        popup.getMenu().add(0, 5, 4, "切换主题");
        popup.setOnMenuItemClickListener(item -> {
            String[] labels = {"", "智能排序", "最近安装", "字母顺序", "使用频率"};
            if (item.getItemId() >= 1 && item.getItemId() <= 4) {
                sortLabel.setText(labels[item.getItemId()]);
                return true;
            }
            if (item.getItemId() == 5) {
                toggleTheme();
                return true;
            }
            return false;
        });
        popup.show();
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

    private void loadAppsAsync() {
        io.execute(() -> {
            final List<AppEntry> loaded = AppLoader.loadLaunchableApps(this);
            main.post(() -> {
                allApps = loaded;
                doFilter();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}
