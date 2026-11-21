package kr.routine.phone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Street-level map around one place (OpenStreetMap tiles, Web Mercator) with a pin in the middle. Drawing only. */
public final class PlaceMapView extends View {
    private static final int TILE = 256, ZOOM = 16;
    private static final LruCache<String, Bitmap> TILES = new LruCache<>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private static final Set<String> LOADING = new HashSet<>();
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final String template, userAgent;
    private Place place;

    public PlaceMapView(Context context) {
        super(context);
        template = context.getString(R.string.map_tile_url); userAgent = context.getString(R.string.map_user_agent);
    }

    public void show(Place value) { place = value; setContentDescription(value == null ? "" : "지도: " + value.name()); invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.save();
        Path clip = new Path(); clip.addRoundRect(new RectF(0, 0, w, h), 28, 28, Path.Direction.CW); canvas.clipPath(clip);
        canvas.drawColor(0xffe9e6df);
        if (place == null) { canvas.restore(); return; }
        double cx = worldX(place.longitude()), cy = worldY(place.latitude());
        double left = cx - w / 2.0, top = cy - h / 2.0;
        for (int tx = (int) Math.floor(left / TILE); tx <= (int) Math.floor((left + w) / TILE); tx++)
            for (int ty = (int) Math.floor(top / TILE); ty <= (int) Math.floor((top + h) / TILE); ty++) {
                Bitmap tile = tile(tx, ty);
                if (tile == null) continue;
                float x = (float) (tx * TILE - left), y = (float) (ty * TILE - top);
                canvas.drawBitmap(tile, null, new RectF(x, y, x + TILE, y + TILE), paint);
            }
        drawPin(canvas, w / 2f, h / 2f);
        paint.setColor(0xccffffff); paint.setStyle(Paint.Style.FILL);
        String credit = "© OpenStreetMap contributors";
        paint.setTextSize(22); float tw = paint.measureText(credit);
        canvas.drawRect(w - tw - 20, h - 34, w, h, paint);
        paint.setColor(0xff333333); canvas.drawText(credit, w - tw - 10, h - 10, paint);
        canvas.restore();
    }

    private void drawPin(Canvas canvas, float x, float y) {
        paint.setStyle(Paint.Style.FILL);
        Path pin = new Path();
        pin.moveTo(x, y); pin.lineTo(x - 22, y - 40); pin.lineTo(x + 22, y - 40); pin.close();
        paint.setColor(0xfff2c12e); canvas.drawPath(pin, paint); canvas.drawCircle(x, y - 52, 30, paint);
        paint.setColor(0xff1b1c1e); canvas.drawCircle(x, y - 52, 11, paint);
    }

    private Bitmap tile(int x, int y) {
        String key = ZOOM + "/" + x + "/" + y;
        Bitmap ready = TILES.get(key);
        if (ready != null || LOADING.contains(key)) return ready;
        LOADING.add(key);
        String url = template.replace("{z}", String.valueOf(ZOOM)).replace("{x}", String.valueOf(x)).replace("{y}", String.valueOf(y));
        IO.execute(() -> {
            Bitmap bitmap = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(8000); c.setRequestProperty("User-Agent", userAgent);
                if (c.getResponseCode() == 200) try (InputStream in = c.getInputStream()) { bitmap = BitmapFactory.decodeStream(in); }
            } catch (Exception ignored) {
            } finally { if (c != null) c.disconnect(); }
            Bitmap done = bitmap;
            main.post(() -> { LOADING.remove(key); if (done != null) { TILES.put(key, done); invalidate(); } });
        });
        return null;
    }

    private static double worldX(double longitude) { return (longitude + 180) / 360 * TILE * (1 << ZOOM); }
    private static double worldY(double latitude) {
        double s = Math.sin(Math.toRadians(latitude));
        return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * TILE * (1 << ZOOM);
    }
}
