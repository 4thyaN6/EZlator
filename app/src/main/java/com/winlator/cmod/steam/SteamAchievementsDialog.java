package com.winlator.cmod.steam;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.R;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Achievement list of one Steam game, laid out like a handheld console's
 * trophy list: a progress header (percent, unlocked / total, bar), filter chips
 * (all / unlocked / locked) and one row per achievement (icon, name,
 * description, unlock date). Locked rows are dimmed and use the grey icon;
 * hidden achievements show no name or text until unlocked. Colours come from
 * the app's neon theme.
 * 스팀 게임 하나의 도전과제 목록임. 휴대용 게임기의 트로피 목록처럼 구성함:
 * 진행률 머리(퍼센트, 달성 / 전체, 막대), 필터 칩(전체 / 달성 / 미달성), 도전과제마다
 * 한 줄(아이콘, 이름, 설명, 달성 날짜). 미달성 줄은 흐리게 하고 회색 아이콘을 씀.
 * 숨겨진 도전과제는 달성 전까지 이름과 설명을 보여 주지 않음. 색은 앱의 네온
 * 테마를 따름
 */
public final class SteamAchievementsDialog {
    private static final int FILTER_ALL = 0, FILTER_UNLOCKED = 1, FILTER_LOCKED = 2;

    private final Activity activity;
    private final int appId;
    private final Dialog dialog;
    private final View root;
    private final ExecutorService iconPool = Executors.newFixedThreadPool(4);
    // Icons already decoded in this dialog (about 4 MB at most)
    // 이 창에서 이미 읽은 아이콘(최대 약 4MB)
    private final LruCache<String, Bitmap> icons = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final List<SteamAchievements.Achievement> all = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private int filter = FILTER_ALL;

    private SteamAchievementsDialog(Activity activity, int appId, String title) {
        this.activity = activity;
        this.appId = appId;
        root = LayoutInflater.from(activity).inflate(R.layout.steam_achievements_dialog, null);
        ((TextView) root.findViewById(R.id.TVAchTitle)).setText(title);

        RecyclerView rv = root.findViewById(R.id.RVAchievements);
        rv.setLayoutManager(new LinearLayoutManager(activity));
        rv.setAdapter(adapter);

        // Filter chips: the selected one is drawn filled (neon_chip_bg selected state)
        // 필터 칩: 고른 칩은 채워서 그림(neon_chip_bg의 선택 상태)
        int[] chipIds = {R.id.TVAchFilterAll, R.id.TVAchFilterUnlocked, R.id.TVAchFilterLocked};
        for (int i = 0; i < chipIds.length; i++) {
            final int f = i;
            root.findViewById(chipIds[i]).setOnClickListener(v -> {
                filter = f;
                for (int id : chipIds) root.findViewById(id).setSelected(id == chipIds[f]);
                adapter.apply();
            });
        }
        root.findViewById(R.id.TVAchFilterAll).setSelected(true);

        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            // The layout draws its own card; full height so the list can scroll
            // 레이아웃이 자기 카드를 그림. 목록이 스크롤되도록 높이를 꽉 채움
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dialog.setOnDismissListener(d -> iconPool.shutdownNow());
        // Set after the dialog exists (the listener uses it)
        // 창이 만들어진 뒤에 붙임(리스너가 창을 씀)
        final Dialog shown = dialog;
        root.findViewById(R.id.BTAchClose).setOnClickListener(v -> shown.dismiss());
    }

    // Opens the list and loads it in the background
    // 목록 창을 열고 백그라운드에서 불러옴
    public static void show(Activity activity, int appId, String title) {
        SteamAchievementsDialog d = new SteamAchievementsDialog(activity, appId, title);
        d.dialog.show();
        d.load();
    }

    private void load() {
        setState(true, null);
        final Context app = activity.getApplicationContext();
        new Thread(() -> {
            SteamAchievements.Result r = SteamAchievements.load(app, appId);
            activity.runOnUiThread(() -> {
                if (!dialog.isShowing()) return;
                all.clear();
                all.addAll(r.list);
                String notice = null;
                if (r.notSignedIn && r.list.isEmpty()) notice = activity.getString(R.string.steam_not_signed_in);
                else if (r.failed) notice = activity.getString(R.string.steam_achievements_failed);
                else if (r.fromCache) notice = activity.getString(R.string.steam_achievements_offline);
                else if (r.list.isEmpty()) notice = activity.getString(R.string.steam_achievements_none);
                setState(false, notice);
                updateHeader();
                adapter.apply();
            });
        }, "SteamAchievements").start();
    }

    // Loading ring / notice line
    // 불러오는 중 고리 / 안내 줄
    private void setState(boolean loading, String notice) {
        root.findViewById(R.id.PBAchLoading).setVisibility(loading ? View.VISIBLE : View.GONE);
        TextView tv = root.findViewById(R.id.TVAchNotice);
        tv.setText(notice != null ? notice : "");
        tv.setVisibility(notice != null ? View.VISIBLE : View.GONE);
    }

    private void updateHeader() {
        int total = all.size(), unlocked = 0;
        for (SteamAchievements.Achievement a : all) if (a.isUnlocked()) unlocked++;
        int percent = total > 0 ? unlocked * 100 / total : 0;
        ((TextView) root.findViewById(R.id.TVAchPercent)).setText(percent + "%");
        ((TextView) root.findViewById(R.id.TVAchCount)).setText(activity.getString(R.string.steam_achievements_progress, unlocked, total));
        ((ProgressBar) root.findViewById(R.id.PBAchProgress)).setProgress(percent);
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        private final List<SteamAchievements.Achievement> shown = new ArrayList<>();

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name, desc, date;

            Holder(View v) {
                super(v);
                icon = v.findViewById(R.id.IVAchIcon);
                name = v.findViewById(R.id.TVAchName);
                desc = v.findViewById(R.id.TVAchDesc);
                date = v.findViewById(R.id.TVAchDate);
            }
        }

        // Rebuilds the visible rows from the filter (schema order is kept)
        // 필터에 맞춰 보이는 줄을 다시 만듦(스키마 순서는 유지함)
        void apply() {
            shown.clear();
            for (SteamAchievements.Achievement a : all) {
                if (filter == FILTER_UNLOCKED && !a.isUnlocked()) continue;
                if (filter == FILTER_LOCKED && a.isUnlocked()) continue;
                shown.add(a);
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.steam_achievement_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            SteamAchievements.Achievement a = shown.get(position);
            boolean unlocked = a.isUnlocked();
            boolean masked = a.hidden && !unlocked;
            h.name.setText(masked ? activity.getString(R.string.steam_achievements_hidden) : a.name);
            h.desc.setText(masked ? activity.getString(R.string.steam_achievements_hidden_desc) : a.description);
            h.date.setText(unlocked
                    ? DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(a.unlockTime * 1000L))
                    : activity.getString(R.string.steam_achievements_locked));
            // Locked rows are dimmed
            // 미달성 줄은 흐리게 함
            h.itemView.setAlpha(unlocked ? 1f : 0.6f);

            // Icon: memory cache, else loaded off the UI thread; the tag guards
            // against a recycled row showing another row's icon
            // 아이콘: 메모리 캐시에 있으면 바로, 없으면 UI 스레드 밖에서 읽음. 태그로
            // 재사용된 줄에 다른 줄의 아이콘이 들어가는 것을 막음
            final String key = (unlocked ? "u:" : "l:") + a.apiName;
            h.icon.setTag(key);
            Bitmap cached = icons.get(key);
            h.icon.setImageBitmap(cached);
            if (cached == null && !iconPool.isShutdown()) {
                final Context app = activity.getApplicationContext();
                iconPool.execute(() -> {
                    Bitmap b = SteamAchievements.icon(app, appId, a);
                    if (b == null) return;
                    activity.runOnUiThread(() -> {
                        icons.put(key, b);
                        if (key.equals(h.icon.getTag())) h.icon.setImageBitmap(b);
                    });
                });
            }
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }
}
