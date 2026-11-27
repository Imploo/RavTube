package com.liskovsoft.leanbackassistant.channels;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;

import com.liskovsoft.leanbackassistant.utils.AppUtil;
import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RavTube: "Continue watching" on the Android TV home screen (and in RavLauncher's "Verder kijken" row).
 *
 * Source is the account's YouTube history, so videos watched on the phone count too. Every video that was
 * started but didn't get past 90% is published as a Watch Next program with its progress.
 * The history has no dates, so the last time a video's progress changed is tracked here: a video drops out
 * after 7 days without progress.
 *
 * Runs with the periodic channel update (also when RavTube isn't opened) and right after watching.
 */
public class RavContinueWatching {
    private static final String TAG = RavContinueWatching.class.getSimpleName();
    private static final String PREFS = "ravtube_continue_watching";
    private static final long RETENTION_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int FINISHED_PERCENT = 90;
    private static final long MIN_DURATION_MS = 70_000; // no Shorts
    private static final int MIN_HISTORY_SIZE = 60;      // how far back in the history to look
    private static final int MAX_ITEMS = 20;
    private static final long SYNC_DELAY_MS = 5_000;     // give YouTube a moment to store the new position

    private static Handler sHandler;

    /** Syncs in the background, a few seconds from now. Repeated calls collapse into one sync. */
    public static void syncSoon(Context context) {
        if (Build.VERSION.SDK_INT < 26 || context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        Handler handler = handler();
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> sync(app), SYNC_DELAY_MS);
    }

    private static synchronized Handler handler() {
        if (sHandler == null) {
            HandlerThread thread = new HandlerThread(TAG);
            thread.start();
            sHandler = new Handler(thread.getLooper());
        }
        return sHandler;
    }

    /** Blocking; call from a background thread. */
    public static synchronized void sync(Context context) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            List<MediaItem> history = loadHistory();
            if (history == null) {
                return; // offline or signed out: keep what's there
            }
            publish(context, select(context, history));
        } catch (Exception e) {
            Log.e(TAG, "Sync failed: " + e.getMessage());
        }
    }

    private static List<MediaItem> loadHistory() {
        ContentService service = YouTubeServiceManager.instance().getContentService();
        MediaGroup group = service.getHistory();
        if (group == null || group.getMediaItems() == null) {
            return null;
        }
        List<MediaItem> items = new ArrayList<>(group.getMediaItems());
        for (int i = 0; i < 3 && items.size() < MIN_HISTORY_SIZE; i++) {
            group = service.continueGroup(group);
            if (group == null || group.getMediaItems() == null || group.getMediaItems().isEmpty()) {
                break;
            }
            items.addAll(group.getMediaItems());
        }
        return items;
    }

    private static final class Item {
        final MediaItem video;
        final long engagedAt;

        Item(MediaItem video, long engagedAt) {
            this.video = video;
            this.engagedAt = engagedAt;
        }
    }

    /** Unfinished videos with progress in the last 7 days, most recently watched first (history order). */
    private static List<Item> select(Context context, List<MediaItem> history) {
        long now = System.currentTimeMillis();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        Map<String, Item> selected = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < history.size(); i++) {
            MediaItem video = history.get(i);
            String videoId = video.getVideoId();
            if (TextUtils.isEmpty(videoId) || !seen.add(videoId)) {
                continue;
            }
            int percent = video.getPercentWatched();

            // "<percent>|<time it last changed>" per video
            long changedAt = now;
            String saved = prefs.getString(videoId, null);
            if (saved != null) {
                String[] parts = saved.split("\\|");
                try {
                    if (parts.length == 2 && Integer.parseInt(parts[0]) == percent) {
                        changedAt = Long.parseLong(parts[1]);
                    }
                } catch (NumberFormatException ignored) {
                    // rewritten below
                }
            }
            if (changedAt == now) {
                editor.putString(videoId, percent + "|" + now);
            }

            boolean unfinished = percent > 0 && percent < FINISHED_PERCENT
                    && !video.isLive() && !video.isShorts() && video.getDurationMs() >= MIN_DURATION_MS;
            if (unfinished && now - changedAt < RETENTION_MS && selected.size() < MAX_ITEMS) {
                // the history order is the real order; nudge the times so the home screen keeps it
                selected.put(videoId, new Item(video, changedAt - i));
            }
        }
        for (String id : prefs.getAll().keySet()) {
            if (!seen.contains(id)) {
                editor.remove(id); // fell out of the part of the history we look at
            }
        }
        editor.apply();
        return new ArrayList<>(selected.values());
    }

    /** Brings our Watch Next rows in line with the selection. */
    @TargetApi(26)
    private static void publish(Context context, List<Item> items) {
        ContentResolver resolver = context.getContentResolver();
        String pkg = context.getPackageName();

        // our rows by video id; rows the user removed from the home screen (browsable = 0) stay removed
        Map<String, Long> rows = new HashMap<>();
        Set<String> removedByUser = new HashSet<>();
        String[] projection = {TvContract.WatchNextPrograms._ID, TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
                TvContract.WatchNextPrograms.COLUMN_BROWSABLE, TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME};
        try (Cursor c = resolver.query(TvContract.WatchNextPrograms.CONTENT_URI, projection, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (!pkg.equals(c.getString(3)) || c.isNull(1)) {
                    continue;
                }
                if (c.getInt(2) == 0) {
                    removedByUser.add(c.getString(1));
                } else {
                    rows.put(c.getString(1), c.getLong(0));
                }
            }
        }

        Set<String> wanted = new HashSet<>();
        for (Item item : items) {
            String videoId = item.video.getVideoId();
            if (removedByUser.contains(videoId)) {
                continue;
            }
            wanted.add(videoId);
            ContentValues values = toValues(context, item);
            Long rowId = rows.get(videoId);
            if (rowId != null) {
                resolver.update(TvContract.buildWatchNextProgramUri(rowId), values, null, null);
            } else {
                resolver.insert(TvContract.WatchNextPrograms.CONTENT_URI, values);
            }
        }
        for (Map.Entry<String, Long> row : rows.entrySet()) {
            if (!wanted.contains(row.getKey())) {
                resolver.delete(ContentUris.withAppendedId(TvContract.WatchNextPrograms.CONTENT_URI, row.getValue()), null, null);
            }
        }
        Log.d(TAG, "Continue watching: " + wanted.size() + " videos");
    }

    @TargetApi(26)
    private static ContentValues toValues(Context context, Item item) {
        MediaItem video = item.video;
        long duration = video.getDurationMs();
        String poster = !TextUtils.isEmpty(video.getCardImageUrl()) ? video.getCardImageUrl()
                : "https://i.ytimg.com/vi/" + video.getVideoId() + "/hqdefault.jpg";
        Intent intent = AppUtil.getInstance(context).createAppIntent("https://www.youtube.com/watch?v=" + video.getVideoId());

        ContentValues values = new ContentValues();
        values.put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE);
        values.put(TvContract.WatchNextPrograms.COLUMN_TYPE, TvContract.WatchNextPrograms.TYPE_CLIP);
        values.put(TvContract.WatchNextPrograms.COLUMN_TITLE, video.getTitle());
        if (!TextUtils.isEmpty(video.getAuthor())) {
            values.put(TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION, video.getAuthor());
        }
        values.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, poster);
        values.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.WatchNextPrograms.ASPECT_RATIO_16_9);
        values.put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, (int) duration);
        values.put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, (int) (duration * video.getPercentWatched() / 100));
        values.put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, item.engagedAt);
        values.put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, video.getVideoId());
        values.put(TvContract.WatchNextPrograms.COLUMN_CONTENT_ID, video.getVideoId());
        if (intent != null) {
            values.put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, intent.toUri(Intent.URI_INTENT_SCHEME));
        }
        return values;
    }
}
