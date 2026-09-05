package com.quickstart;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 已隐藏应用管理页面 — 列出所有被隐藏的应用，支持恢复显示。
 */
public class HiddenAppsActivity extends AppCompatActivity {

    private RecyclerView recycler;
    private TextView emptyHint;
    private HiddenAppAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hidden_apps);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("已隐藏的应用");
        }

        recycler = findViewById(R.id.hidden_apps_list);
        emptyHint = findViewById(R.id.hidden_empty_hint);

        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HiddenAppAdapter();
        recycler.setAdapter(adapter);

        loadHiddenApps();
    }

    /** 从 SharedPreferences 读取隐藏列表，加载对应应用信息 */
    private void loadHiddenApps() {
        Set<String> hidden = getSharedPreferences("settings", MODE_PRIVATE)
                .getStringSet("hidden_apps", new HashSet<>());

        List<AppInfo> list = new ArrayList<>();
        PackageManager pm = getPackageManager();

        for (String pkg : hidden) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                String label = pm.getApplicationLabel(ai).toString();
                Drawable icon = pm.getApplicationIcon(ai);
                list.add(new AppInfo(label, pkg, icon));
            } catch (PackageManager.NameNotFoundException e) {
                // 应用已卸载，仅显示包名
                list.add(new AppInfo(pkg + " (已卸载)", pkg, null));
            }
        }

        adapter.setApps(list);
        emptyHint.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        recycler.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** 恢复指定应用：从 hidden_apps 集合中移除 */
    private void restoreApp(String packageName) {
        SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        Set<String> hidden = new HashSet<>(sp.getStringSet("hidden_apps", new HashSet<>()));
        hidden.remove(packageName);
        sp.edit().putStringSet("hidden_apps", hidden).apply();
        Toast.makeText(this, "已恢复", Toast.LENGTH_SHORT).show();
        loadHiddenApps(); // 刷新列表
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** 简单的应用信息内部类 */
    private static class AppInfo {
        String label;
        String packageName;
        Drawable icon;

        AppInfo(String label, String packageName, Drawable icon) {
            this.label = label;
            this.packageName = packageName;
            this.icon = icon;
        }
    }

    /** 隐藏应用列表适配器 */
    private class HiddenAppAdapter extends RecyclerView.Adapter<HiddenAppAdapter.VH> {
        private List<AppInfo> apps = new ArrayList<>();

        void setApps(List<AppInfo> list) {
            apps = list;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_hidden_app, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            AppInfo info = apps.get(position);
            holder.label.setText(info.label);
            holder.packageName.setText(info.packageName);
            if (info.icon != null) {
                holder.icon.setImageDrawable(info.icon);
            } else {
                holder.icon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            holder.restore.setOnClickListener(v -> restoreApp(info.packageName));
            holder.itemView.setOnClickListener(v -> restoreApp(info.packageName));
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView icon;
            TextView label;
            TextView packageName;
            TextView restore;

            VH(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.hidden_app_icon);
                label = itemView.findViewById(R.id.hidden_app_label);
                packageName = itemView.findViewById(R.id.hidden_app_package);
                restore = itemView.findViewById(R.id.hidden_app_restore);
            }
        }
    }
}
