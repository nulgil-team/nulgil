package kr.routine.app.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kr.routine.app.R;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Road line for the in-app map, from a public OpenStreetMap router (OSRM). Display only: the stock navi computes
 * and guides the real route, and the SDK does not expose its geometry. Points are rounded to about 100 m before
 * they leave the car. An empty {@code route_shape_url} turns this off and maps fall back to the dashed order line.
 */
public final class RouteShapeCache {
    private static final long RETRY_MS = 60_000;
    private static final int MAX_POINTS = 600;
    private static RouteShapeCache shared;

    private final String base, userAgent;
    private final LruCache<String, List<double[]>> shapes = new LruCache<>(32);
    private final Set<String> loading = new HashSet<>();
    private final Map<String, Long> failedAt = new HashMap<>();
    private final Set<Runnable> listeners = new HashSet<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private RouteShapeCache(String base, String userAgent) { this.base = base.trim(); this.userAgent = userAgent; }

    public static RouteShapeCache get(Context context) {
        if (shared == null) shared = new RouteShapeCache(context.getString(R.string.route_shape_url), context.getString(R.string.map_user_agent));
        return shared;
    }

    /** {latitude, longitude} points along roads through the markers in order, or null while loading or unavailable. */
    public List<double[]> shape(List<MapMath.Marker> path) {
        if (base.isEmpty() || path.size() < 2) return null;
        String key = key(path);
        List<double[]> ready = shapes.get(key);
        if (ready != null || loading.contains(key)) return ready;
        Long failed = failedAt.get(key);
        if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return null;
        loading.add(key);
        String url = base + key + "?overview=simplified&geometries=geojson";
        io.execute(() -> {
            List<double[]> line = download(url);
            main.post(() -> {
                loading.remove(key);
                if (line == null) { failedAt.put(key, System.currentTimeMillis()); return; }
                failedAt.remove(key); shapes.put(key, line);
                for (Runnable listener : listeners.toArray(new Runnable[0])) listener.run();
            });
        });
        return null;
    }

    public void addListener(Runnable listener) { listeners.add(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }

    /** "lon,lat;lon,lat" with three decimals (about 100 m), the OSRM coordinate format. */
    static String key(List<MapMath.Marker> path) {
        StringBuilder out = new StringBuilder();
        for (MapMath.Marker m : path) {
            if (out.length() > 0) out.append(';');
            out.append(String.format(Locale.ROOT, "%.3f,%.3f", m.longitude(), m.latitude()));
        }
        return out.toString();
    }

    private List<double[]> download(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", userAgent);
            if (connection.getResponseCode() != 200) return null;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) { in.transferTo(body); }
            return parse(body.toString(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            return null;
        } finally { if (connection != null) connection.disconnect(); }
    }

    static List<double[]> parse(String json) throws org.json.JSONException {
        JSONObject root = new JSONObject(json);
        if (!"Ok".equals(root.optString("code"))) return null;
        JSONArray routes = root.optJSONArray("routes");
        if (routes == null || routes.length() == 0) return null;
        JSONArray coordinates = routes.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates");
        int step = Math.max(1, coordinates.length() / MAX_POINTS);
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < coordinates.length(); i += step) {
            JSONArray p = coordinates.getJSONArray(i);
            out.add(new double[]{p.getDouble(1), p.getDouble(0)});
        }
        JSONArray last = coordinates.getJSONArray(coordinates.length() - 1);
        double[] end = {last.getDouble(1), last.getDouble(0)};
        if (out.isEmpty() || out.get(out.size() - 1)[0] != end[0] || out.get(out.size() - 1)[1] != end[1]) out.add(end);
        return out.size() < 2 ? null : List.copyOf(out);
    }
}
