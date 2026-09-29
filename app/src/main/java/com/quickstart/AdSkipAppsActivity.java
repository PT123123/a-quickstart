package com.quickstart;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.quickstart.util.AdSkipStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 快跳过 · 应用名单管理。
 *
 *  1) 名单应用：配合「应用名单模式」（所有生效 / 仅名单内生效 / 名单内不生效）勾选；
 *  2) 「不再跳过」：悬浮条点「不再跳过」加入的永久屏蔽应用，点 ✕ 恢复。
 */
public class AdSkipAppsActivity extends AppCompatActivity {

    /** 行类型：分区标题 / 名单应用（勾选）/ 不再跳过（✕ 移除） */
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_APP = 1;
    private static final int TYPE_MUTED = 2;

    private static class Row {
        final int type;
        final String header;
        final String label;
        final String pkg;
        final Drawable icon;

        Row(int type, String header, String label, String pkg, Drawable icon) {
            this.type = type;
            this.header = header;
            this.label = label;
            this.pkg = pkg;
            this.icon = icon;
        }
    }

    private RecyclerView recycler;
    private EditText searchBox;
    private Adapter adapter;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /** 全量可启动应用（label/pkg/icon），后台加载一次 */
    private List<Row> allApps = new ArrayList<>();
    private Set<String> appSet = new HashSet<>();
    private Set<String> mutedSet = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_adskip_apps);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("应用名单管理");
        }

        recycler = findViewById(R.id.adskip_apps_list);
        searchBox = findViewById(R.id.adskip_apps_search);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        recycler.setAdapter(adapter);

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { rebuildRows(); }
        });

        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从设置返回或名单被悬浮条修改后刷新勾选/屏蔽状态
        refreshSets();
    }

    /** 后台加载全部可启动应用（图标/名称涉及 binder IPC） */
    private void loadApps() {
        io.execute(() -> {
            PackageManager pm = getPackageManager();
            List<ResolveInfo> infos = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    0);
            Set<String> seen = new HashSet<>();
            List<Row> list = new ArrayList<>();
            for (ResolveInfo info : infos) {
                String pkg = info.activityInfo != null ? info.activityInfo.packageName : null;
                if (pkg == null || !seen.add(pkg)) continue;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    String label = pm.getApplicationLabel(ai).toString();
                    Drawable icon = pm.getApplicationIcon(ai);
                    list.add(new Row(TYPE_APP, null, label, pkg, icon));
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
            list.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
            runOnUiThread(() -> {
                if (isFinishing()) return;
                allApps = list;
                refreshSets();
            });
        });
    }

    /** 重新读取名单/屏蔽集合并重建行 */
    private void refreshSets() {
        SharedPreferences sp = getSharedPreferences(AdSkipStore.PREFS_NAME, MODE_PRIVATE);
        appSet = new HashSet<>(sp.getStringSet(AdSkipStore.KEY_APP_SET, new HashSet<>()));
        mutedSet = new HashSet<>(sp.getStringSet(AdSkipStore.KEY_MUTED, new HashSet<>()));
        rebuildRows();
    }

    /** 按搜索词 + 当前集合状态组装展示行（搜索只过滤名单应用；屏蔽区始终完整展示） */
    private void rebuildRows() {
        String q = searchBox.getText().toString().trim().toLowerCase();
        List<Row> rows = new ArrayList<>();

        rows.add(new Row(TYPE_HEADER, "名单应用（配合「应用名单模式」生效）", null, null, null));
        int appCount = 0;
        for (Row app : allApps) {
            if (!q.isEmpty()
                    && !app.label.toLowerCase().contains(q)
                    && !app.pkg.toLowerCase().contains(q)) {
                continue;
            }
            rows.add(app);
            appCount++;
        }
        if (appCount == 0 && !q.isEmpty()) {
            rows.add(new Row(TYPE_HEADER, "（无匹配应用）", null, null, null));
        }

        if (!mutedSet.isEmpty()) {
            rows.add(new Row(TYPE_HEADER,
                    "不再跳过的应用（悬浮条点「不再跳过」加入，点 ✕ 恢复）", null, null, null));
            for (String pkg : sortedPkgs(mutedSet)) {
                Row match = findApp(pkg);
                rows.add(new Row(TYPE_MUTED, null,
                        match != null ? match.label : pkg + "（已卸载）", pkg,
                        match != null ? match.icon : null));
            }
        }

        adapter.setRows(rows);
    }

    /** 大小写不敏感的有序包名列表（保证屏蔽区顺序稳定） */
    private static List<String> sortedPkgs(Set<String> set) {
        java.util.TreeSet<String> ts = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        ts.addAll(set);
        return new ArrayList<>(ts);
    }

    private Row findApp(String pkg) {
        for (Row r : allApps) {
            if (r.pkg.equals(pkg)) return r;
        }
        return null;
    }

    /** 勾选/取消名单应用 */
    private void toggleInSet(String pkg, boolean checked) {
        SharedPreferences sp = getSharedPreferences(AdSkipStore.PREFS_NAME, MODE_PRIVATE);
        Set<String> set = new HashSet<>(sp.getStringSet(AdSkipStore.KEY_APP_SET, new HashSet<>()));
        if (checked) set.add(pkg); else set.remove(pkg);
        sp.edit().putStringSet(AdSkipStore.KEY_APP_SET, set).apply();
        appSet = set;
    }

    /** 从「不再跳过」屏蔽列表移除 */
    private void unmute(String pkg) {
        AdSkipStore.setMuted(this, pkg, false);
        mutedSet = new HashSet<>(AdSkipStore.getMuted(this));
        Toast.makeText(this, "已恢复跳过", Toast.LENGTH_SHORT).show();
        rebuildRows();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<Row> rows = new ArrayList<>();

        void setRows(List<Row> list) {
            rows = list;
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            switch (viewType) {
                case TYPE_HEADER:
                    return new HeaderVH(inf.inflate(R.layout.item_adskip_section, parent, false));
                case TYPE_MUTED:
                    return new MutedVH(inf.inflate(R.layout.item_adskip_muted, parent, false));
                default:
                    return new AppVH(inf.inflate(R.layout.item_adskip_app, parent, false));
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int pos) {
            Row row = rows.get(pos);
            if (vh instanceof HeaderVH) {
                ((HeaderVH) vh).text.setText(row.header);
            } else if (vh instanceof AppVH) {
                AppVH h = (AppVH) vh;
                h.bind(row);
            } else if (vh instanceof MutedVH) {
                MutedVH h = (MutedVH) vh;
                h.bind(row);
            }
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    class AppVH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        final TextView pkg;
        final CheckBox check;

        AppVH(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.adskip_app_icon);
            label = itemView.findViewById(R.id.adskip_app_label);
            pkg = itemView.findViewById(R.id.adskip_app_package);
            check = itemView.findViewById(R.id.adskip_app_check);
        }

        void bind(Row row) {
            icon.setImageDrawable(row.icon);
            label.setText(row.label);
            pkg.setText(row.pkg);
            check.setOnCheckedChangeListener(null);
            check.setChecked(appSet.contains(row.pkg));
            check.setOnCheckedChangeListener((v, isChecked) -> toggleInSet(row.pkg, isChecked));
            itemView.setOnClickListener(v -> check.toggle());
        }
    }

    class MutedVH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        final TextView pkg;
        final TextView remove;

        MutedVH(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.adskip_muted_icon);
            label = itemView.findViewById(R.id.adskip_muted_label);
            pkg = itemView.findViewById(R.id.adskip_muted_package);
            remove = itemView.findViewById(R.id.adskip_muted_remove);
        }

        void bind(Row row) {
            icon.setImageDrawable(row.icon);
            label.setText(row.label);
            pkg.setText(row.pkg);
            remove.setOnClickListener(v -> unmute(row.pkg));
            itemView.setOnClickListener(v -> unmute(row.pkg));
        }
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView text;
        HeaderVH(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.adskip_section_text);
        }
    }
}
