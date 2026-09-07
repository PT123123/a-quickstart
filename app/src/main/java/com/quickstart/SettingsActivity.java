package com.quickstart;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.quickstart.util.BackgroundManager;

import java.util.concurrent.Executors;

/**
 * 快开启 - 设置界面（Tab 分页 + ViewPager2）
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String[] TAB_TITLES = {"显示", "通用", "键盘", "数据"};
    private static final int[] TAB_XML_RES = {
            R.xml.prefs_display,
            R.xml.prefs_general,
            R.xml.prefs_keyboard,
            R.xml.prefs_data
    };

    private ViewPager2 viewPager;
    private TabLayout tabLayout;
    private EditText searchBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // 设置 ActionBar
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("设置");
        }

        // ViewPager2 + Adapter
        viewPager = findViewById(R.id.settings_viewpager);
        SettingsPagerAdapter pagerAdapter = new SettingsPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);
        viewPager.setOffscreenPageLimit(3); // 保持相邻 fragment 存活，搜索时可访问

        // TabLayout
        tabLayout = findViewById(R.id.settings_tabs);
        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            tab.setText(TAB_TITLES[position]);
        }).attach();

        // 搜索框
        searchBox = findViewById(R.id.settings_search);
        if (searchBox != null) {
            searchBox.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filterAllFragments(s.toString());
                }

                @Override
                public void afterTextChanged(android.text.Editable s) {}
            });
        }
    }

    /** 跨所有 Tab 搜索设置项 */
    private void filterAllFragments(String query) {
        FragmentManager fm = getSupportFragmentManager();
        for (int i = 0; i < TAB_XML_RES.length; i++) {
            // ViewPager2 的 Fragment tag 格式为 "f" + itemId，itemId 默认为 position
            Fragment fragment = fm.findFragmentByTag("f" + i);
            if (fragment instanceof SettingsFragment) {
                ((SettingsFragment) fragment).filterPreferences(query);
            }
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

    /** ViewPager2 适配器 */
    private static class SettingsPagerAdapter extends FragmentStateAdapter {
        SettingsPagerAdapter(@NonNull AppCompatActivity activity) {
            super(activity.getSupportFragmentManager(), activity.getLifecycle());
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return SettingsFragment.newInstance(TAB_XML_RES[position]);
        }

        @Override
        public int getItemCount() {
            return TAB_XML_RES.length;
        }
    }

    /**
     * 设置 Fragment：每个 Tab 对应一个实例，加载各自的 preferences XML
     */
    public static class SettingsFragment extends PreferenceFragmentCompat {

        private static final String ARG_XML_RES = "xml_res";

        /** 图片选择器：选择图片后跳转到裁剪界面 */
        private final ActivityResultLauncher<String> imagePickerLauncher =
                registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                    if (uri != null) {
                        launchCropActivity(uri);
                    }
                });

        /** 裁剪界面结果：裁剪完成后保存路径 */
        private final ActivityResultLauncher<Intent> cropLauncher =
                registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        String path = result.getData().getStringExtra(ImageCropActivity.EXTRA_OUTPUT_PATH);
                        if (path != null) {
                            getPreferenceManager().getSharedPreferences()
                                    .edit().putString("background_image_path", path).apply();
                            Toast.makeText(getContext(), "背景图片已设置", Toast.LENGTH_SHORT).show();
                        }
                    }
                });

        static SettingsFragment newInstance(int xmlResId) {
            SettingsFragment fragment = new SettingsFragment();
            Bundle args = new Bundle();
            args.putInt(ARG_XML_RES, xmlResId);
            fragment.setArguments(args);
            return fragment;
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            int xmlResId = getArguments() != null ? getArguments().getInt(ARG_XML_RES, R.xml.prefs_display) : R.xml.prefs_display;
            // 使用和 MainActivity 同一个 SharedPreferences 文件，确保设置同步
            getPreferenceManager().setSharedPreferencesName("settings");
            setPreferencesFromResource(xmlResId, rootKey);

            setupPreferences();
        }

        /** 根据当前 Fragment 的 XML 资源设置各偏好项的监听器 */
        private void setupPreferences() {
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

            // 模糊效果 - 切换时预生成对应模糊图（离线生成，运行时不再计算）
            ListPreference bgBlur = findPreference("background_blur");
            if (bgBlur != null) {
                bgBlur.setOnPreferenceChangeListener((pref, val) -> {
                    String mode = String.valueOf(val);
                    final CharSequence label = bgBlur.getEntries()[bgBlur.findIndexOfValue(mode)];

                    // 无模糊 / 还没选图：直接生效
                    if (BackgroundManager.NONE.equals(mode) || !BackgroundManager.hasSource(requireContext())) {
                        Toast.makeText(getContext(), "模糊效果: " + label, Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    // 已生成过：直接用
                    if (BackgroundManager.getBlurredFile(requireContext(), mode).exists()) {
                        Toast.makeText(getContext(), "模糊效果: " + label, Toast.LENGTH_SHORT).show();
                        return true;
                    }

                    // 首次使用：后台生成一次，之后一直复用
                    android.app.ProgressDialog dialog = new android.app.ProgressDialog(requireContext());
                    dialog.setMessage("正在生成模糊背景…");
                    dialog.setCancelable(false);
                    dialog.show();
                    BackgroundManager.ensureBlurAsync(requireContext(), mode, (ok, err) -> {
                        try {
                            if (dialog.isShowing()) dialog.dismiss();
                        } catch (Exception ignored) {}
                        if (getContext() == null) return;
                        Toast.makeText(getContext(),
                                ok ? "模糊效果: " + label : "生成失败: " + err,
                                Toast.LENGTH_SHORT).show();
                    });
                    return true;
                });
            }

            // 图片背景
            Preference bgImage = findPreference("background_image");
            if (bgImage != null) {
                bgImage.setOnPreferenceClickListener(pref -> {
                    imagePickerLauncher.launch("image/*");
                    return true;
                });
            }

            // 清除图片背景
            Preference clearBgImage = findPreference("clear_background_image");
            if (clearBgImage != null) {
                clearBgImage.setOnPreferenceClickListener(pref -> {
                    getPreferenceManager().getSharedPreferences()
                            .edit().remove("background_image_path").apply();
                    // 同时删除原图与所有预生成的模糊图
                    BackgroundManager.clearAll(requireContext());
                    Toast.makeText(getContext(), "已恢复默认背景", Toast.LENGTH_SHORT).show();
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

            // 数字键绑定应用
            Preference keyBinding = findPreference("key_binding");
            if (keyBinding != null) {
                keyBinding.setOnPreferenceClickListener(pref -> {
                    startActivity(new Intent(requireContext(), KeyBindingActivity.class));
                    return true;
                });
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
                    getPreferenceManager().getSharedPreferences()
                            .edit().putBoolean("force_refresh_pending", true).apply();
                    Toast.makeText(requireContext(),
                            "缓存已清除，返回主界面将重新加载", Toast.LENGTH_SHORT).show();
                    return true;
                });
            }
        }

        /** 根据搜索文本过滤设置项（跨 Tab 全局搜索时由 Activity 调用） */
        public void filterPreferences(String query) {
            PreferenceScreen screen = getPreferenceScreen();
            if (screen == null) return;

            if (query == null || query.isEmpty()) {
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

        /** 导出全部配置到剪贴板 */
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
            if (getActivity() != null) {
                getActivity().finish();
            }
        }

        /** 启动裁剪界面 */
        private void launchCropActivity(Uri imageUri) {
            Intent intent = new Intent(requireContext(), ImageCropActivity.class);
            intent.putExtra(ImageCropActivity.EXTRA_IMAGE_URI, imageUri.toString());
            cropLauncher.launch(intent);
        }

        /** 颜色选择器（显示预设颜色列表供用户选择） */
        private void showColorPicker(String key, String title) {
            String[] colors = {"#FFFFFF", "#F5F5F5", "#E3F2FD", "#E8F5E9", "#FFF3E0", "#FCE4EC", "#263238", "#000000"};
            String[] colorNames = {"白色", "浅灰", "浅蓝", "浅绿", "浅橙", "浅粉", "深灰", "黑色"};
            String current = getPreferenceManager().getSharedPreferences()
                    .getString(key, "#FFFFFF");

            // 找到当前颜色对应的索引作为默认选中项
            int checked = 0;
            for (int i = 0; i < colors.length; i++) {
                if (colors[i].equalsIgnoreCase(current)) {
                    checked = i;
                    break;
                }
            }

            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(title)
                    .setSingleChoiceItems(colorNames, checked, (dialog, which) -> {
                        getPreferenceManager().getSharedPreferences()
                                .edit().putString(key, colors[which]).apply();
                        Toast.makeText(getContext(), title + ": " + colorNames[which], Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }
    }
}
