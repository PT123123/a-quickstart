package com.quickstart;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
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

import com.quickstart.util.BackgroundManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 壁纸库：列出所有选过的壁纸，点一下就切成主界面背景，长按可删除。
 *
 * 每张壁纸的缩略图是惰性解码的（{@link BackgroundManager#loadThumb}），
 * 所以在后台线程扫库 + 出缩略图，扫完再一次性刷新列表，避免逐格卡顿。
 */
public class WallpaperLibraryActivity extends AppCompatActivity {

    private RecyclerView recycler;
    private TextView emptyHint;
    private TextView topHint;
    private Adapter adapter;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** 缩略图缓存：id -> Bitmap，避免滚回来重新解码 */
    private final android.util.LruCache<String, Bitmap> thumbs =
            new android.util.LruCache<String, Bitmap>(6 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount() / 1024;
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wallpaper_library);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("壁纸库");
        }

        recycler = findViewById(R.id.wallpaper_list);
        emptyHint = findViewById(R.id.wallpaper_empty_hint);
        topHint = findViewById(R.id.wallpaper_hint);

        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        recycler.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.wallpaper_library, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        if (id == R.id.action_clear_library) {
            confirmClearLibrary();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        thumbs.evictAll();
        io.shutdownNow();
    }

    /** 后台扫库并出缩略图，完成后回主线程刷新 */
    private void refresh() {
        io.execute(() -> {
            final List<BackgroundManager.Wallpaper> list = BackgroundManager.listWallpapers(this);
            final List<Bitmap> images = new ArrayList<>(list.size());
            for (BackgroundManager.Wallpaper w : list) {
                Bitmap cached = thumbs.get(w.id);
                Bitmap bmp = cached != null ? cached : BackgroundManager.loadThumb(this, w.id);
                if (bmp != null && cached == null) thumbs.put(w.id, bmp);
                images.add(bmp);
            }
            final long size = BackgroundManager.getLibrarySize(this);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                adapter.setData(list, images);
                emptyHint.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                recycler.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
                topHint.setText(list.isEmpty()
                        ? "壁纸库是空的，先在设置里选一张图片背景"
                        : "共 " + list.size() + " 张 · 占用 " + formatSize(size)
                                + " · 点击切换，长按删除");
            });
        });
    }

    /** 切到某张壁纸：作废旧模糊图，按当前模糊档位补生成 */
    private void applyWallpaper(BackgroundManager.Wallpaper w) {
        if (w.current) {
            Toast.makeText(this, "已经是当前背景了", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!BackgroundManager.switchWallpaper(this, w.id)) {
            Toast.makeText(this, "切换失败，该壁纸可能已被删除", Toast.LENGTH_SHORT).show();
            refresh();
            return;
        }
        final String mode = getSharedPreferences(BackgroundManager.PREFS, MODE_PRIVATE)
                .getString(BackgroundManager.PREF_BLUR, BackgroundManager.NONE);
        if (BackgroundManager.NONE.equals(mode)) {
            Toast.makeText(this, "已切换背景", Toast.LENGTH_SHORT).show();
            refresh();
            return;
        }
        // 有模糊档位：后台补生成后主界面才能立刻显示模糊效果
        android.app.ProgressDialog dialog = new android.app.ProgressDialog(this);
        dialog.setMessage("正在生成模糊背景…");
        dialog.setCancelable(false);
        dialog.show();
        BackgroundManager.ensureBlurAsync(this, mode, (ok, err) -> {
            try {
                if (dialog.isShowing()) dialog.dismiss();
            } catch (Exception ignored) {
            }
            if (isFinishing() || isDestroyed()) return;
            Toast.makeText(this, ok ? "已切换背景" : "背景已切换，但模糊生成失败",
                    Toast.LENGTH_SHORT).show();
            refresh();
        });
    }

    private void confirmDelete(final BackgroundManager.Wallpaper w) {
        new AlertDialog.Builder(this)
                .setTitle("删除这张壁纸？")
                .setMessage(w.current ? "这是当前正在使用的背景，删除后会恢复成纯色背景。" : "删除后无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, which) -> {
                    io.execute(() -> {
                        // deleteWallpaper 内部已清掉这张壁纸自己的避让区域，
                        // 不能用 clearAll —— 那会把其它壁纸的区域也一起抹掉
                        BackgroundManager.deleteWallpaper(this, w.id);
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                            refresh();
                        });
                    });
                })
                .show();
    }

    private void confirmClearLibrary() {
        if (BackgroundManager.listWallpapers(this).isEmpty()) {
            Toast.makeText(this, "壁纸库已经是空的", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("清空壁纸库？")
                .setMessage("会删除全部留档壁纸并恢复纯色背景，无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, which) -> {
                    io.execute(() -> {
                        BackgroundManager.clearAll(this);
                        com.quickstart.util.ZoneStore.clearAll(this);
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(this, "壁纸库已清空", Toast.LENGTH_SHORT).show();
                            refresh();
                        });
                    });
                })
                .show();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f));
    }

    /** 缩略图加载失败时的占位：灰底 + 一个小图标区域 */
    private void bindThumb(ImageView view, Bitmap bmp) {
        if (bmp == null) {
            view.setImageBitmap(placeholder());
            return;
        }
        view.setImageBitmap(bmp);
    }

    private Bitmap placeholder;

    private Bitmap placeholder() {
        if (placeholder != null) return placeholder;
        Bitmap bmp = Bitmap.createBitmap(72, 54, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(0xFF303030);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF666666);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3f);
        RectF r = new RectF(24, 15, 48, 39);
        canvas.drawRoundRect(r, 3f, 3f, p);
        canvas.drawLine(27, 34, 36, 24, p);
        canvas.drawLine(36, 24, 45, 34, p);
        placeholder = bmp;
        return bmp;
    }

    /** 壁纸列表适配器 */
    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        private List<BackgroundManager.Wallpaper> data = new ArrayList<>();
        private List<Bitmap> images = new ArrayList<>();
        private final SimpleDateFormat fmt =
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

        void setData(List<BackgroundManager.Wallpaper> list, List<Bitmap> imgs) {
            data = list;
            images = imgs;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_wallpaper, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            final BackgroundManager.Wallpaper w = data.get(position);
            holder.time.setText(fmt.format(new Date(w.addedAt)));
            holder.meta.setText(w.file.length() / 1024 + " KB"
                    + (w.current ? " · 正在使用" : ""));
            bindThumb(holder.thumb, position < images.size() ? images.get(position) : null);
            holder.current.setVisibility(w.current ? View.VISIBLE : View.GONE);
            holder.itemView.setOnClickListener(v -> applyWallpaper(w));
            holder.itemView.setOnLongClickListener(v -> {
                confirmDelete(w);
                return true;
            });
            holder.delete.setOnClickListener(v -> confirmDelete(w));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView thumb;
            TextView time;
            TextView meta;
            TextView current;
            TextView delete;

            VH(@NonNull View itemView) {
                super(itemView);
                thumb = itemView.findViewById(R.id.wallpaper_thumb);
                time = itemView.findViewById(R.id.wallpaper_time);
                meta = itemView.findViewById(R.id.wallpaper_meta);
                current = itemView.findViewById(R.id.wallpaper_current);
                delete = itemView.findViewById(R.id.wallpaper_delete);
            }
        }
    }
}