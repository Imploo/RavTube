package com.liskovsoft.leanbackassistant.media;

import android.annotation.SuppressLint;
import android.content.Context;

import com.liskovsoft.leanbackassistant.R;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.ContentService;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.Observable;

public class ClipService {
    private static final int SUBSCRIPTIONS_ID = 1;
    private static final int HISTORY_ID = 2;
    private static final int RECOMMENDED_ID = 3;
    private static final String SUBS_CHANNEL_ID = "subs_channel_id";
    private static final String SUBS_PROGRAMS_IDS = "subs_clips_ids";
    private static final String RECOMMENDED_CHANNEL_ID = "recommended_channel_id";
    private static final String RECOMMENDED_PROGRAMS_IDS = "recommended_programs_ids";
    private static final String HISTORY_CHANNEL_ID = "history_channel_id";
    private static final String HISTORY_PROGRAMS_IDS = "history_programs_ids";
    private static final String SUBSCRIPTIONS_URL = "https://www.youtube.com/tv#/zylon-surface?c=FEsubscriptions&resume";
    private static final String HISTORY_URL = "https://www.youtube.com/tv#/zylon-surface?c=FEmy_youtube&resume";
    // RavTube: extra sections, published as additional home screen channels
    private static final int SHORTS_ID = 4;
    private static final int TRENDING_ID = 5;
    private static final int MUSIC_ID = 6;
    private static final int NEWS_ID = 7;
    private static final int GAMING_ID = 8;
    private static final int LIVE_ID = 9;
    private static final int SPORTS_ID = 10;
    private static final int MIN_ROW_SIZE = 6;
    private static final String RECOMMENDED_URL = "https://www.youtube.com/tv#/zylon-surface?c=default&resume";
    @SuppressLint("StaticFieldLeak")
    private static ClipService mInstance;
    private final Context mContext;

    public ClipService(Context context) {
        mContext = context;
    }

    public static ClipService instance(Context context) {
        if (mInstance == null) {
            mInstance = new ClipService(context.getApplicationContext());
        }

        return mInstance;
    }

    public Playlist getSubscriptionsPlaylist() {
        return createPlaylist(
                R.string.header_subscriptions,
                SUBSCRIPTIONS_ID,
                SUBS_CHANNEL_ID,
                SUBS_PROGRAMS_IDS,
                SUBSCRIPTIONS_URL,
                R.drawable.generic_channels,
                ContentService::getSubscriptions,
                false
        );
    }

    public Playlist getHistoryPlaylist() {
        return createPlaylist(
                R.string.header_history,
                HISTORY_ID,
                HISTORY_CHANNEL_ID,
                HISTORY_PROGRAMS_IDS,
                HISTORY_URL,
                R.drawable.generic_channels,
                ContentService::getHistory,
                false);
    }

    public Playlist getRecommendedPlaylist() {
        return createPlaylist(
                R.string.recommended,
                RECOMMENDED_ID,
                RECOMMENDED_CHANNEL_ID,
                RECOMMENDED_PROGRAMS_IDS,
                RECOMMENDED_URL,
                R.drawable.generic_channels,
                ContentService::getRecommended,
                true);
    }

    /**
     * RavTube: the other app sections (Shorts, Trending, Music, ...) as extra channels.
     * Row based sections use their first non-empty row.
     */
    public List<Playlist> getExtraPlaylists() {
        List<Playlist> result = new ArrayList<>();
        result.add(createSectionPlaylist(R.string.header_shorts, SHORTS_ID, "shorts", s -> s.getShortsObserve().blockingFirst()));
        result.add(createSectionPlaylist(R.string.header_trending, TRENDING_ID, "trending", s -> firstRow(s.getTrendingObserve())));
        result.add(createSectionPlaylist(R.string.header_music, MUSIC_ID, "music", s -> firstRow(s.getMusicObserve())));
        result.add(createSectionPlaylist(R.string.header_news, NEWS_ID, "news", s -> firstRow(s.getNewsObserve())));
        result.add(createSectionPlaylist(R.string.header_gaming, GAMING_ID, "gaming", s -> firstRow(s.getGamingObserve())));
        result.add(createSectionPlaylist(R.string.header_live, LIVE_ID, "live", s -> firstRow(s.getLiveObserve())));
        result.add(createSectionPlaylist(R.string.header_sports, SPORTS_ID, "sports", s -> firstRow(s.getSportsObserve())));
        return result;
    }

    private Playlist createSectionPlaylist(int titleResId, int id, String key, GroupCallback callback) {
        return createPlaylist(
                titleResId,
                id,
                key + "_channel_id",
                key + "_programs_ids",
                RECOMMENDED_URL,
                R.drawable.generic_channels,
                callback,
                false);
    }

    /**
     * First row with enough videos to fill a channel; sections often start with a single featured item
     * (Music first emits the liked music row on its own). Falls back to the biggest row.
     */
    private static MediaGroup firstRow(Observable<List<MediaGroup>> rows) {
        List<MediaGroup> groups = rows
                .concatMapIterable(list -> list)
                .takeUntil(group -> size(group) >= MIN_ROW_SIZE) // stop loading once a good row is found
                .toList()
                .blockingGet(); // empty when the section has no content (e.g. Trending)
        MediaGroup biggest = null;

        for (MediaGroup group : groups) {
            if (size(group) >= MIN_ROW_SIZE) {
                return group;
            }

            if (size(group) > size(biggest)) {
                biggest = group;
            }
        }

        return biggest;
    }

    private static int size(MediaGroup group) {
        return group != null && group.getMediaItems() != null ? group.getMediaItems().size() : 0;
    }

    private Playlist createPlaylist(
            int titleResId, int id, String channelId, String programId,
            String recommendedUrl, int logoResId, GroupCallback callback, boolean isDefault) {
        Playlist playlist = new Playlist(
                mContext.getResources().getString(titleResId),
                Integer.toString(id),
                callback,
                isDefault);
        playlist.setChannelKey(channelId);
        playlist.setProgramsKey(programId);
        playlist.setPlaylistUrl(recommendedUrl);
        playlist.setLogoResId(logoResId);

        return playlist;
    }

    public interface GroupCallback {
        MediaGroup call(ContentService mediaTabManager);
    }
}
