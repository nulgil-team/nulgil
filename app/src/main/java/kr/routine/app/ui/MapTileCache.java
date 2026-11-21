package kr.routine.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kr.routine.app.R;

/**
 * Downloads and keeps map tiles for every map view in the app. Screens are rebuilt often, so the cache outlives
 * views. Main thread only, except the downloads. A failed tile is not asked again for a while.
 */
public final class MapTileCache {
    private static final long RETRY_MS = 30_000;
    private static MapTileCache shared;

    private final String urlTemplate, userAgent;
    private final LruCache<String, Bitmap> tiles = new LruCache<>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private final Set<String> loading = new HashSet<>();
    private final Map<String, Long> failedAt = new HashMap<>();
    private final Set<Runnable> listeners = new HashSet<>();
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    private MapTileCache(String urlTemplate, String userAgent) { this.urlTemplate = urlTemplate; this.userAgent = userAgent; }

    public static MapTileCache get(Context context) {
        if (shared == null) shared = new MapTileCache(context.getString(R.string.map_tile_url), context.getString(R.string.map_user_agent));
        return shared;
    }

    /** The tile if loaded; otherwise starts loading it and returns null. Listeners run when it arrives. */
    public Bitmap tile(int zoom, int x, int y) {
        String key = zoom + "/" + x + "/" + y;
        Bitmap ready = tiles.get(key);
        if (ready != null || loading.contains(key)) return ready;
        Long failed = failedAt.get(key);
        if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return null;
        loading.add(key);
        String url = urlTemplate.replace("{z}", String.valueOf(zoom)).replace("{x}", String.valueOf(x)).replace("{y}", String.valueOf(y));
        io.execute(() -> {
            Bitmap bitmap = download(url);
            main.post(() -> {
                loading.remove(key);
                if (bitmap == null) { failedAt.put(key, System.currentTimeMillis()); return; }
                failedAt.remove(key); tiles.put(key, bitmap);
                for (Runnable listener : listeners.toArray(new Runnable[0])) listener.run();
            });
        });
        return null;
    }

    public void addListener(Runnable listener) { listeners.add(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }

    private Bitmap download(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", userAgent);
            if (connection.getResponseCode() != 200) return null;
            try (InputStream in = connection.getInputStream()) { return BitmapFactory.decodeStream(in); }
        } catch (Exception failure) {
            return null;
        } finally { if (connection != null) connection.disconnect(); }
    }
}
