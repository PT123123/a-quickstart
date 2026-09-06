package com.quickstart;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.quickstart.model.AppEntry;
import com.quickstart.util.AppLoader;
import com.quickstart.util.KeyBindingHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 数字键绑定管理页面 — 为数字键 0-9 绑定指定应用。
 * 绑定后，在搜索列表匹配到唯一结果前，按对应数字键可直接启动绑定的应用。
 */
public class KeyBindingActivity extends AppCompatActivity {

    private RecyclerView recycler;
    private BindingAdapter adapter;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_key_binding);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("数字键绑定应用");
        }

        recycler = findViewById(R.id.binding_list_recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BindingAdapter();
        recycler.setAdapter(adapter);

        loadBindings();
    }

    /** 加载当前所有数字键绑定 */
    private void loadBindings() {
        List<BindingItem> items = new ArrayList<>();
        for (int digit = 1; digit <= 9; digit++) {
            String pkg = KeyBindingHelper.getBoundPackage(this, digit);
            items.add(new BindingItem(digit, pkg));
        }
        // 0 键
        items.add(new BindingItem(0, KeyBindingHelper.getBoundPackage(this, 0)));
        adapter.setItems(items);
    }

    /** 显示带搜索功能的应用选择对话框 */
    private void showPickAppDialog(int digit) {
        io.execute(() -> {
            final List<AppEntry> apps = AppLoader.loadLaunchableApps(this);
            runOnUiThread(() -> {
                View view = LayoutInflater.from(this).inflate(R.layout.dialog_app_picker, null);
                EditText searchBox = view.findViewById(R.id.picker_search);
                ListView listView = view.findViewById(R.id.picker_app_list);

                final List<AppEntry> filteredApps = new ArrayList<>(apps);
                final ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                        android.R.layout.simple_list_item_1, getLabels(filteredApps));
                listView.setAdapter(adapter);

                final Runnable[] pendingFilter = {null};

                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle("为按键 " + digit + " 选择应用")
                        .setView(view)
                        .setNegativeButton("取消", null)
                        .create();

                // 对话框关闭时清掉未触发的防抖任务
                dialog.setOnDismissListener(d -> {
                    if (pendingFilter[0] != null) {
                        mainHandler.removeCallbacks(pendingFilter[0]);
                    }
                });

                // 点击选中应用
                listView.setOnItemClickListener((parent, v, position, id) -> {
                    AppEntry selected = filteredApps.get(position);
                    KeyBindingHelper.bind(this, digit, selected.packageName);
                    Toast.makeText(this, "已绑定 " + selected.label + " → 按键 " + digit,
                            Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    loadBindings();
                });

                // 搜索过滤（200ms 防抖，避免每敲一个字符都全量重建列表）
                searchBox.addTextChangedListener(new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        if (pendingFilter[0] != null) {
                            mainHandler.removeCallbacks(pendingFilter[0]);
                        }
                        pendingFilter[0] = () -> filterApps(apps,
                                searchBox.getText().toString(), filteredApps, adapter);
                        mainHandler.postDelayed(pendingFilter[0], 200);
                    }

                    @Override
                    public void afterTextChanged(android.text.Editable s) {}
                });

                dialog.show();
            });
        });
    }

    /** 根据搜索关键词过滤应用列表 */
    private void filterApps(List<AppEntry> allApps, String query,
                            List<AppEntry> filteredApps, ArrayAdapter<String> adapter) {
        filteredApps.clear();
        if (query.isEmpty()) {
            filteredApps.addAll(allApps);
        } else {
            String lower = query.toLowerCase();
            for (AppEntry e : allApps) {
                if (e.label.toLowerCase().contains(lower) ||
                        e.packageName.toLowerCase().contains(lower)) {
                    filteredApps.add(e);
                }
            }
        }
        adapter.clear();
        adapter.addAll(getLabels(filteredApps));
        adapter.notifyDataSetChanged();
    }

    /** 提取应用名称列表 */
    private List<String> getLabels(List<AppEntry> apps) {
        List<String> labels = new ArrayList<>();
        for (AppEntry e : apps) labels.add(e.label);
        return labels;
    }

    /** 清除绑定 */
    private void clearBinding(int digit) {
        KeyBindingHelper.unbind(this, digit);
        Toast.makeText(this, "已清除按键 " + digit + " 的绑定", Toast.LENGTH_SHORT).show();
        loadBindings();
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

    /** 绑定项数据 */
    private static class BindingItem {
        int digit;
        String packageName;

        BindingItem(int digit, String packageName) {
            this.digit = digit;
            this.packageName = packageName;
        }
    }

    /** 绑定列表适配器 */
    private class BindingAdapter extends RecyclerView.Adapter<BindingAdapter.VH> {
        private List<BindingItem> items = new ArrayList<>();

        void setItems(List<BindingItem> list) {
            items = list;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_key_binding, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            BindingItem item = items.get(position);
            holder.boundPkg = item.packageName; // 供异步回调校验 holder 是否仍绑定该应用
            holder.digit.setText(String.valueOf(item.digit));

            if (item.packageName != null && !item.packageName.isEmpty()) {
                // 尝试加载应用名称和图标
                loadAppInfo(holder, item);
                holder.gesture.setText("点击更换 · 长按清除");
            } else {
                holder.appName.setText("未绑定");
                holder.appIcon.setImageResource(android.R.drawable.sym_def_app_icon);
                holder.gesture.setText("点击选择应用");
            }

            holder.itemView.setOnClickListener(v -> showPickAppDialog(item.digit));
            holder.itemView.setOnLongClickListener(v -> {
                if (item.packageName != null && !item.packageName.isEmpty()) {
                    clearBinding(item.digit);
                } else {
                    showPickAppDialog(item.digit);
                }
                return true;
            });
        }

        private void loadAppInfo(VH holder, BindingItem item) {
            io.execute(() -> {
                try {
                    PackageManager pm = getPackageManager();
                    ApplicationInfo ai = pm.getApplicationInfo(item.packageName, 0);
                    String label = pm.getApplicationLabel(ai).toString();
                    Drawable icon = pm.getApplicationIcon(ai);
                    runOnUiThread(() -> {
                        // holder 可能已被复用到其它条目，包名不一致时丢弃这次回调
                        if (item.packageName.equals(holder.boundPkg)) {
                            holder.appName.setText(label);
                            holder.appIcon.setImageDrawable(icon);
                        }
                    });
                } catch (PackageManager.NameNotFoundException e) {
                    runOnUiThread(() -> {
                        if (item.packageName.equals(holder.boundPkg)) {
                            holder.appName.setText(item.packageName + " (已卸载)");
                            holder.appIcon.setImageResource(android.R.drawable.sym_def_app_icon);
                        }
                    });
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView digit;
            ImageView appIcon;
            TextView appName;
            TextView gesture;
            String boundPkg;

            VH(@NonNull View itemView) {
                super(itemView);
                digit = itemView.findViewById(R.id.binding_digit);
                appIcon = itemView.findViewById(R.id.binding_app_icon);
                appName = itemView.findViewById(R.id.binding_app_name);
                gesture = itemView.findViewById(R.id.binding_gesture);
            }
        }
    }
}
