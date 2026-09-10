package com.quickstart;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 排序权重：列表长按拖动调整规则优先级（越靠前越优先），点击行修改数值。
 *
 * 持久化：
 *  - 顺序：SharedPreferences("settings").weight_order（逗号分隔的规则 key，从前到后即优先级从高到低）
 *  - 数值：SharedPreferences("settings").weight_<key>（沿用原有 key）
 *
 * 主界面排序 {@link MainActivity#sortBySearchWeight} 按该顺序做「词序」比较：
 * 排在前面的规则先比较，得分不同即分出先后，因此最前面的规则会盖过后面所有规则。
 */
public class WeightOrderActivity extends AppCompatActivity {

    private static final String PREFS = "settings";
    private static final String KEY_ORDER = "weight_order";

    /** 规则定义：[key, 标题, 说明, 默认值]（默认顺序即优先级默认顺序） */
    private static final String[][] RULES = {
            {"weight_exact", "完全匹配加分", "输入与应用名完全一致（如 QQ、X）时给予的高额加分", "150"},
            {"weight_prefix", "开头匹配加分", "应用名以输入内容开头（如 weix 命中 微信）时的加分", "50"},
            {"weight_contains", "包含匹配加分", "应用名只是包含输入内容（未开头）时的加分", "30"},
            {"weight_freq", "使用频率·每次积分", "每使用过 1 次所加的分数（次数上限 50 次），数值越高越偏袒常用应用", "2"},
            {"weight_recent", "最近使用·时间倍率", "今天 80 分／昨天 60／3 天内 40／一周内 20，该值为其倍率，0 表示忽略最近使用时间", "1"},
    };

    private final List<Rule> rules = new ArrayList<>();
    private RecyclerView recycler;
    private RuleAdapter adapter;
    private ItemTouchHelper touchHelper;

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_weight_order);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("T9 排序权重");
        }

        recycler = findViewById(R.id.weight_list);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RuleAdapter();
        recycler.setAdapter(adapter);

        touchHelper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
            @Override
            public boolean isLongPressDragEnabled() {
                return true; // 长按任意位置即可拖动
            }

            @Override
            public boolean isItemViewSwipeEnabled() {
                return false;
            }

            @Override
            public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                adapter.move(from.getBindingAdapterPosition(), to.getBindingAdapterPosition());
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
            }

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                saveOrder();
            }
        });
        touchHelper.attachToRecyclerView(recycler);

        findViewById(R.id.btn_reset_weight).setOnClickListener(v -> resetAll());

        load();
    }

    /** 按持久化的优先级顺序加载规则（缺失的规则追加到末尾） */
    private void load() {
        rules.clear();
        SharedPreferences prefs = prefs();
        String order = prefs.getString(KEY_ORDER, null);
        Set<String> used = new HashSet<>();
        if (order != null) {
            for (String key : order.split(",")) {
                key = key.trim();
                if (key.isEmpty() || used.contains(key)) continue;
                for (String[] r : RULES) {
                    if (r[0].equals(key)) {
                        rules.add(new Rule(r[0], r[1], r[2], r[3]));
                        used.add(key);
                        break;
                    }
                }
            }
        }
        for (String[] r : RULES) {
            if (!used.contains(r[0])) {
                rules.add(new Rule(r[0], r[1], r[2], r[3]));
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void saveOrder() {
        StringBuilder sb = new StringBuilder();
        for (Rule r : rules) {
            if (sb.length() > 0) sb.append(',');
            sb.append(r.key);
        }
        prefs().edit().putString(KEY_ORDER, sb.toString()).apply();
    }

    private void resetAll() {
        SharedPreferences.Editor editor = prefs().edit();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < RULES.length; i++) {
            String[] r = RULES[i];
            editor.putString(r[0], r[3]);
            if (i > 0) sb.append(',');
            sb.append(r[0]);
        }
        editor.putString(KEY_ORDER, sb.toString()).apply();
        load();
        Toast.makeText(this, "已恢复默认权重与顺序", Toast.LENGTH_SHORT).show();
    }

    private void showValueDialog(Rule r) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        String cur = prefs().getString(r.key, r.def);
        input.setText(cur);
        input.setSelection(input.getText().length());

        new android.app.AlertDialog.Builder(this)
                .setTitle(r.title)
                .setMessage("数值越大该规则影响越强，0 表示忽略该规则")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String s = input.getText().toString().trim();
                    if (s.isEmpty()) s = "0";
                    try {
                        int n = Integer.parseInt(s);
                        if (n < 0) n = 0;
                        prefs().edit().putString(r.key, String.valueOf(n)).apply();
                    } catch (NumberFormatException ex) {
                        Toast.makeText(this, "请输入 0 或正整数", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private static class Rule {
        final String key, title, summary, def;

        Rule(String key, String title, String summary, String def) {
            this.key = key;
            this.title = title;
            this.summary = summary;
            this.def = def;
        }
    }

    private class RuleAdapter extends RecyclerView.Adapter<RuleAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_weight_order, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            final Rule r = rules.get(position);
            holder.title.setText(r.title);
            holder.summary.setText(r.summary);
            holder.value.setText(prefs().getString(r.key, r.def));
            holder.itemView.setOnClickListener(v -> showValueDialog(r));
            // 拖动把手：按住即开始拖动排序
            holder.handle.setOnTouchListener((v, ev) -> {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    touchHelper.startDrag(holder);
                }
                return false;
            });
        }

        void move(int from, int to) {
            if (from < 0 || to < 0 || from >= rules.size() || to >= rules.size()) return;
            Rule tmp = rules.remove(from);
            rules.add(to, tmp);
            notifyItemMoved(from, to);
        }

        @Override
        public int getItemCount() {
            return rules.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView title, summary, value, handle;

            VH(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.rule_title);
                summary = itemView.findViewById(R.id.rule_summary);
                value = itemView.findViewById(R.id.rule_value);
                handle = itemView.findViewById(R.id.rule_handle);
            }
        }
    }
}
