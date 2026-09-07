package com.quickstart;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import java.util.concurrent.Executors;

/**
 * 快开启 - 设置界面
 */
public class SettingsActivity extends AppCompatActivity {

    private SettingsFragment fragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // 设置 ActionBar
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("设置");
        }

        if (savedInstanceState == null) {
            fragment = new SettingsFragment();
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings_container, fragment)
                    .commit();
        }

        // 搜索功能（在 fragment 创建后初始化）
        EditText searchBox = findViewById(R.id.settings_search);
        if (searchBox != null) {
            searchBox.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (fragment != null) fragment.filterPreferences(s.toString());
                }

                @Override
                public void afterTextChanged(android.text.Editable s) {}
            });
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            // 使用和 MainActivity 同一个 SharedPreferences 文件，确保设置同步
            getPreferenceManager().setSharedPreferencesName("settings");
            setPreferencesFromResource(R.xml.preferences, rootKey);

            // 窗口大小
            ListPreference windowSize = findPreference("window_size");
            if (windowSize != null) {
                windowSize.setOnPreferenceChangeListener((pref, val) -> {
                    applyWindowSize(String.valueOf(val));
                    Toast.makeText(getContext(), "窗口大小: " + val, Toast.LENGTH_SHORT).show();
                    return true;
                });
            }

            // 背景颜色
            Preference bgColor = findPreference("background_color");
            if (bgColor != null) {
                bgColor.setOnPreferenceClickListener(pref -> {
                    showColorPicker("background_color", "设置背景颜色");
                    return true;
                });
            }

            // 字体颜色
            Preference fontColor = findPreference("font_color");
            if (fontColor != null) {
                fontColor.setOnPreferenceClickListener(pref -> {
                    showColorPicker("font_color", "设置字体颜色");
                    return true;
                });
            }

            // 最近应用圆点
            SwitchPreferenceCompat recentDot = findPreference("recent_app_dot");
            if (recentDot != null) {
                recentDot.setOnPreferenceChangeListener((pref, val) -> true);
            }

            // 列数（SeekBar 已被 ListPreference 替代）

            // 主题
            ListPreference theme = findPreference("theme_mode");
            if (theme != null) {
                theme.setOnPreferenceChangeListener((pref, val) -> {
                    int mode = "dark".equals(val) ? AppCompatDelegate.MODE_NIGHT_YES
                            : "light".equals(val) ? AppCompatDelegate.MODE_NIGHT_NO
                            : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                    AppCompatDelegate.setDefaultNightMode(mode);
                    return true;
                });
            }

            // 全局手势类型
            ListPreference keyGesture = findPreference("key_gesture");
            if (keyGesture != null) {
                keyGesture.setOnPreferenceChangeListener((pref, val) -> true);
            }

            // 数字键绑定应用
            Preference keyBinding = findPreference("key_binding");
            if (keyBinding != null) {
                keyBinding.setOnPreferenceClickListener(pref -> {
                    startActivity(new Intent(requireContext(), KeyBindingActivity.class));
                    return true;
                });
            }

            // 列数（ListPreference "columns"，MainActivity 下次启动时读取）

            // 最近更新范围
            ListPreference recentTime = findPreference("recent_time_range");
            if (recentTime != null) {
                recentTime.setOnPreferenceChangeListener((pref, val) -> true);
            }

            // 已隐藏的应用
            Preference hiddenApps = findPreference("hidden_apps");
            if (hiddenApps != null) {
                hiddenApps.setOnPreferenceClickListener(pref -> {
                    startActivity(new Intent(requireContext(), HiddenAppsActivity.class));
                    return true;
                });
            }

            // 导入/导出配置
            Preference importExport = findPreference("import_export_config");
            if (importExport != null) {
                importExport.setOnPreferenceClickListener(pref -> {
                    showImportExportDialog();
                    return true;
                });
            }

            // 强制刷新应用列表
            Preference forceRefresh = findPreference("force_refresh");
            if (forceRefresh != null) {
                forceRefresh.setOnPreferenceClickListener(pref -> {
                    com.quickstart.util.FastCache.clear(requireContext());
                    com.quickstart.util.IconCache.clear(requireContext());
                    // 设置标志，返回主界面时触发完整重扫
                    getPreferenceManager().getSharedPreferences()
                            .edit().putBoolean("force_refresh_pending", true).apply();
                    Toast.makeText(requireContext(),
                            "缓存已清除，返回主界面将重新加载", Toast.LENGTH_SHORT).show();
                    return true;
                });
            }
        }

        /** 根据搜索文本过滤设置项（public 供 Activity 调用） */
        public void filterPreferences(String query) {
            PreferenceScreen screen = getPreferenceScreen();
            if (screen == null) return;

            if (query.isEmpty()) {
                // 显示所有
                for (int i = 0; i < screen.getPreferenceCount(); i++) {
                    screen.getPreference(i).setVisible(true);
                }
                return;
            }

            String lower = query.toLowerCase();
            for (int i = 0; i < screen.getPreferenceCount(); i++) {
                Preference pref = screen.getPreference(i);
                boolean match = pref.getTitle() != null && pref.getTitle().toString().toLowerCase().contains(lower)
                        || pref.getSummary() != null && pref.getSummary().toString().toLowerCase().contains(lower);
                pref.setVisible(match);
            }
        }

        /** 显示导入/导出配置对话框 */
        private void showImportExportDialog() {
            String[] options = {
                    "扫码传送给新设备（本机显示二维码）",
                    "扫码接收其他设备的配置（打开相机）",
                    "导出全部配置到剪贴板",
                    "从剪贴板导入配置"};
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("导入/导出配置")
                    .setItems(options, (dialog, which) -> {
                        if (which == 0 || which == 1) {
                            Intent intent = new Intent(requireContext(), QrTransferActivity.class);
                            intent.putExtra(QrTransferActivity.EXTRA_MODE,
                                    which == 0 ? QrTransferActivity.MODE_SEND
                                               : QrTransferActivity.MODE_RECEIVE);
                            startActivity(intent);
                        } else if (which == 2) {
                            exportConfig();
                        } else {
                            importConfig();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }

        /** 导出全部配置到剪贴板（与二维码传送共用同一份序列化） */
        private void exportConfig() {
            try {
                String jsonStr = com.quickstart.util.ConfigTransfer.buildConfigJson(requireContext());
                int count = new org.json.JSONObject(jsonStr).length();

                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) requireContext()
                                .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("config", jsonStr));

                Toast.makeText(requireContext(),
                        "已导出全部配置（" + count + " 项）到剪贴板", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(requireContext(), "导出失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        /** 从剪贴板导入配置 */
        private void importConfig() {
            try {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) requireContext()
                                .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (clipboard.getPrimaryClip() == null ||
                    clipboard.getPrimaryClip().getItemCount() == 0) {
                    Toast.makeText(requireContext(), "剪贴板为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                String text = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
                int n = com.quickstart.util.ConfigTransfer.applyConfigJson(requireContext(), text);

                Toast.makeText(requireContext(),
                        "已导入 " + n + " 项配置，重启应用生效", Toast.LENGTH_LONG).show();
            } catch (Throwable t) {
                Toast.makeText(requireContext(), "导入失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        /** 应用窗口大小设置 */
        private void applyWindowSize(String size) {
            getPreferenceManager().getSharedPreferences()
                    .edit().putString("window_size", size).apply();
            // 通知 MainActivity 重新调整大小
            if (getActivity() != null) {
                getActivity().finish();
            }
        }

        private void showColorPicker(String key, String title) {
            // 简化版：预设几种颜色循环切换
            String[] colors = {"#FFFFFF", "#F5F5F5", "#E3F2FD", "#E8F5E9", "#FFF3E0", "#FCE4EC", "#263238", "#000000"};
            String current = getPreferenceManager().getSharedPreferences()
                    .getString(key, "#FFFFFF");
            int idx = 0;
            for (int i = 0; i < colors.length; i++) {
                if (colors[i].equalsIgnoreCase(current)) { idx = i; break; }
            }
            String next = colors[(idx + 1) % colors.length];
            getPreferenceManager().getSharedPreferences()
                    .edit().putString(key, next).apply();
            Toast.makeText(getContext(), title + ": " + next, Toast.LENGTH_SHORT).show();
        }
    }
}
