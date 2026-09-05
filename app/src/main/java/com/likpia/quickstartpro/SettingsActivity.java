package com.likpia.quickstartpro;

import android.graphics.Color;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

/**
 * 快开启 - 设置界面
 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("设置");
        }
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings_container, new SettingsFragment())
                    .commit();
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

            // 列数
            SeekBarPreference columns = findPreference("column_count");
            if (columns != null) {
                columns.setOnPreferenceChangeListener((pref, val) -> true);
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
