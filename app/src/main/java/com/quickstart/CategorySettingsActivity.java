package com.quickstart;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.quickstart.model.AppEntry;
import com.quickstart.util.AppLoader;
import com.quickstart.util.CategoryConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 分类管理：显示所有可编辑分类（智能分类不可编辑，不在此列出）。
 *  - 顶部：设置默认打开的分类、是否循环滑动。
 *  - 分类行：点击行编辑；▲/▼ 调整顺序；✕ 删除（「主界面」不可删除）。
 *  - 顶栏「添加分类」：新建（关键词/手动）。
 *
 * 持久化：
 *  - 分类本体与增删改都经 {@link CategoryConfig} 保存。
 *  - 默认分类：SharedPreferences("settings").default_category（null/空=无）
 *  - 循环滑动：SharedPreferences("settings").categories_loop（boolean）
 */
public class CategorySettingsActivity extends AppCompatActivity {

    private static final String PREFS = "settings";
    private static final String KEY_DEFAULT_CAT = "default_category";
    private static final String KEY_LOOP = "categories_loop";

    private RecyclerView recycler;
    private CategoryListAdapter adapter;
    private TextView defaultCatValue;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_category_settings);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("分类管理");
        }

        recycler = findViewById(R.id.category_list);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new CategoryListAdapter();
        recycler.setAdapter(adapter);

        defaultCatValue = findViewById(R.id.default_category_value);
        findViewById(R.id.row_default_category).setOnClickListener(v -> showDefaultCategoryDialog());

        SwitchMaterial loopSwitch = findViewById(R.id.loop_swipe_switch);
        loopSwitch.setChecked(prefs().getBoolean(KEY_LOOP, true));
        loopSwitch.setOnCheckedChangeListener((btn, checked) ->
                prefs().edit().putBoolean(KEY_LOOP, checked).apply());

        findViewById(R.id.btn_add_category).setOnClickListener(v -> showAddDialog());

        refreshDefaultSummary();
        reload();
    }

    private void refreshDefaultSummary() {
        String def = prefs().getString(KEY_DEFAULT_CAT, null);
        if (def == null || def.isEmpty() || !CategoryConfig.exists(this, def)) {
            defaultCatValue.setText("全部应用");
        } else {
            defaultCatValue.setText(def);
        }
    }

    private void showDefaultCategoryDialog() {
        List<String> names = new ArrayList<>();
        names.add("全部应用");
        names.addAll(CategoryConfig.getUserCategoryNames(this));

        String current = prefs().getString(KEY_DEFAULT_CAT, null);
        int checked = 0;
        if (current != null && !current.isEmpty()) {
            int idx = names.indexOf(current);
            if (idx > 0) checked = idx;
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("默认打开分类")
                .setSingleChoiceItems(names.toArray(new String[0]), checked, (d, which) -> {
                    if (which == 0) {
                        prefs().edit().remove(KEY_DEFAULT_CAT).apply();
                    } else {
                        prefs().edit().putString(KEY_DEFAULT_CAT, names.get(which)).apply();
                    }
                    refreshDefaultSummary();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void reload() {
        adapter.setItems(CategoryConfig.getAll(this));
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

    // ==================== 添加 ====================

    private void showAddDialog() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(60, 0, 60, 0);

        EditText nameInput = new EditText(this);
        nameInput.setHint("分类名称（如 工作、学习）");
        body.addView(nameInput);

        RadioGroup typeGroup = new RadioGroup(this);
        CheckBox keywordRb = new CheckBox(this);
        keywordRb.setText("关键词规则（按名称/包名自动归类）");
        keywordRb.setChecked(true);
        CheckBox manualRb = new CheckBox(this);
        manualRb.setText("手动（手动挑选应用加入）");
        typeGroup.addView(keywordRb);
        typeGroup.addView(manualRb);
        keywordRb.setOnClickListener(v -> { keywordRb.setChecked(true); manualRb.setChecked(false); });
        manualRb.setOnClickListener(v -> { keywordRb.setChecked(false); manualRb.setChecked(true); });
        body.addView(typeGroup);

        new android.app.AlertDialog.Builder(this)
                .setTitle("添加分类")
                .setView(body)
                .setPositiveButton("添加", (d, w) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "请输入分类名称", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (CategoryConfig.exists(this, name)) {
                        Toast.makeText(this, "已存在同名分类", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    List<CategoryConfig.Category> list = CategoryConfig.getAll(this);
                    boolean isManual = manualRb.isChecked();
                    CategoryConfig.Category c = new CategoryConfig.Category(name,
                            isManual ? CategoryConfig.TYPE_MANUAL : CategoryConfig.TYPE_KEYWORD);
                    list.add(c);
                    CategoryConfig.save(this, list);
                    reload();
                    Toast.makeText(this, "已添加「" + name + "」", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 编辑 / 删除 / 排序 ====================

    private void showEditDialog(CategoryConfig.Category c) {
        if (CategoryConfig.TYPE_MANUAL.equals(c.type)) {
            showManualEditDialog(c);
            return;
        }
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(60, 0, 60, 0);

        final EditText nameInput;
        if (c.system) {
            TextView locked = new TextView(this);
            locked.setText("名称：" + c.name + "（内置，不可改名）");
            body.addView(locked);
            nameInput = null;
        } else {
            nameInput = new EditText(this);
            nameInput.setText(c.name);
            nameInput.setHint("分类名称");
            body.addView(nameInput);
        }

        EditText labelInput = new EditText(this);
        labelInput.setHint("名称关键词（逗号分隔，如：微信,微博,qq）");
        labelInput.setText(c.labelKeywords);
        body.addView(labelInput);

        EditText pkgInput = new EditText(this);
        pkgInput.setHint("包名关键词（逗号分隔，如：com.tencent.mm）");
        pkgInput.setText(c.pkgKeywords);
        body.addView(pkgInput);

        new android.app.AlertDialog.Builder(this)
                .setTitle("编辑分类")
                .setView(body)
                .setPositiveButton("保存", (d, w) -> {
                    List<CategoryConfig.Category> list = CategoryConfig.getAll(this);
                    CategoryConfig.Category target = findByName(list, c.name);
                    if (target == null) return;
                    if (nameInput != null) {
                        String newName = nameInput.getText().toString().trim();
                        if (!newName.isEmpty() && !newName.equals(target.name)) {
                            if (CategoryConfig.exists(this, newName)) {
                                Toast.makeText(this, "已存在同名分类", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            target.name = newName;
                        }
                    }
                    target.labelKeywords = labelInput.getText().toString().trim();
                    target.pkgKeywords = pkgInput.getText().toString().trim();
                    CategoryConfig.save(this, list);
                    reload();
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showManualEditDialog(CategoryConfig.Category c) {
        io.execute(() -> {
            final List<AppEntry> apps = AppLoader.loadLaunchableApps(this);
            final Set<String> current = new HashSet<>(c.apps);
            runOnUiThread(() -> {
                final String[] labels = new String[apps.size()];
                final boolean[] checked = new boolean[apps.size()];
                for (int i = 0; i < apps.size(); i++) {
                    AppEntry e = apps.get(i);
                    labels[i] = e.label;
                    checked[i] = current.contains(key(e));
                }
                new android.app.AlertDialog.Builder(this)
                        .setTitle("选择「" + c.name + "」中的应用")
                        .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                        .setPositiveButton("保存", (d, w) -> {
                            List<String> sel = new ArrayList<>();
                            for (int i = 0; i < apps.size(); i++) if (checked[i]) sel.add(key(apps.get(i)));
                            List<CategoryConfig.Category> list = CategoryConfig.getAll(this);
                            CategoryConfig.Category target = findByName(list, c.name);
                            if (target != null) {
                                target.apps = sel;
                                CategoryConfig.save(this, list);
                                reload();
                                Toast.makeText(this, "已保存 " + sel.size() + " 个应用", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        });
    }

    private void showDeleteDialog(CategoryConfig.Category c) {
        if (c.system) {
            Toast.makeText(this, "「" + c.name + "」为内置分类，不可删除", Toast.LENGTH_SHORT).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除分类")
                .setMessage("确定删除分类「" + c.name + "」吗？")
                .setPositiveButton("删除", (d, w) -> {
                    List<CategoryConfig.Category> list = CategoryConfig.getAll(this);
                    for (int i = list.size() - 1; i >= 0; i--) {
                        if (list.get(i).name.equals(c.name)) {
                            list.remove(i);
                            break;
                        }
                    }
                    CategoryConfig.save(this, list);
                    // 若默认分类被删则清除默认
                    String def = prefs().getString(KEY_DEFAULT_CAT, null);
                    if (c.name.equals(def)) prefs().edit().remove(KEY_DEFAULT_CAT).apply();
                    reload();
                    refreshDefaultSummary();
                    Toast.makeText(this, "已删除「" + c.name + "」", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void move(int from, int direction) {
        List<CategoryConfig.Category> list = CategoryConfig.getAll(this);
        int to = from + direction;
        if (to < 0 || to >= list.size()) return;
        CategoryConfig.Category tmp = list.get(from);
        list.set(from, list.get(to));
        list.set(to, tmp);
        CategoryConfig.save(this, list);
        reload();
    }

    private static CategoryConfig.Category findByName(List<CategoryConfig.Category> list, String name) {
        for (CategoryConfig.Category c : list) if (c.name.equals(name)) return c;
        return null;
    }

    private static String key(AppEntry e) {
        return e.packageName + "/" + (e.activityName == null ? "" : e.activityName);
    }

    // ==================== 列表适配器 ====================

    private class CategoryListAdapter extends RecyclerView.Adapter<CategoryListAdapter.VH> {
        private List<CategoryConfig.Category> items = new ArrayList<>();

        void setItems(List<CategoryConfig.Category> list) {
            items = list;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_category_settings, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            final CategoryConfig.Category c = items.get(position);
            holder.name.setText(c.name);
            boolean kw = CategoryConfig.TYPE_KEYWORD.equals(c.type);
            holder.type.setText(kw
                    ? ("关键词 · " + countKeywords(c) + " 词")
                    : ("手动 · " + c.apps.size() + " 应用"));
            // 内置分类（主界面）不可删除
            holder.actions.setVisibility(c.system ? View.INVISIBLE : View.VISIBLE);

            holder.itemView.setOnClickListener(v -> showEditDialog(c));
            holder.up.setOnClickListener(v -> move(position, -1));
            holder.down.setOnClickListener(v -> move(position, 1));
            holder.actions.setOnClickListener(v -> showDeleteDialog(c));
        }

        private int countKeywords(CategoryConfig.Category c) {
            int n = 0;
            for (String s : (c.labelKeywords + "," + c.pkgKeywords).split("[,，\\s]+")) {
                if (!s.isEmpty()) n++;
            }
            return n;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView name, type, up, down, actions;
            VH(@NonNull View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.cat_name);
                type = itemView.findViewById(R.id.cat_type);
                up = itemView.findViewById(R.id.cat_up);
                down = itemView.findViewById(R.id.cat_down);
                actions = itemView.findViewById(R.id.cat_actions);
            }
        }
    }
}