package kr.routine.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.util.List;
import kr.routine.app.R;

/**
 * In-app overview map: OpenStreetMap tiles with the current position, stops, destination and friends' places.
 * The dashed line only shows the visiting order; the stock navi computes and shows the real road route.
 * Drawing only; colors come from the screen palette. Change pin look here.
 */
public final class RouteMapView extends android.view.View {
    private static final int PADDING = 64;
    /** Minimum distance between pin centres (largest pin is 32 px in radius with its white ring). */
    private static final float PIN_SPACING = 66;
    private final MapTileCache cache;
    private final List<MapMath.Marker> markers;
    private final VehicleVisuals.Palette colors;
    private final boolean night;
    private final String attribution;
    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
        stroke = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable redraw = this::invalidate;

    public RouteMapView(Context context, List<MapMath.Marker> markers, VehicleVisuals.Palette colors, boolean night, Typeface typeface) {
        super(context);
        this.cache = MapTileCache.get(context);
        this.markers = List.copyOf(markers);
        this.colors = colors; this.night = night;
        this.attribution = context.getString(R.string.map_attribution);
        label.setTypeface(Typeface.create(typeface, Typeface.BOLD)); label.setTextAlign(Paint.Align.CENTER);
        setContentDescription(describe());
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); cache.addListener(redraw); }
    @Override protected void onDetachedFromWindow() { cache.removeListener(redraw); super.onDetachedFromWindow(); }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        canvas.save();
        Path clip = new Path(); clip.addRoundRect(new RectF(0, 0, w, h), 26, 26, Path.Direction.CW); canvas.clipPath(clip);
        canvas.drawColor(colors.raised());
        if (markers.isEmpty()) { canvas.restore(); return; }
        MapMath.Viewport view = MapMath.fit(markers, w, h, PADDING);
        for (int[] t : view.tiles()) {
            Bitmap tile = cache.tile(view.zoom(), t[0], t[1]);
            if (tile != null) {
                float left = (float) (t[0] * MapMath.TILE - view.left()), top = (float) (t[1] * MapMath.TILE - view.top());
                canvas.drawBitmap(tile, null, new RectF(left, top, left + MapMath.TILE, top + MapMath.TILE), tilePaint);
            }
        }
        if (night) canvas.drawColor(0x55000000); // keep light tiles from glaring at night
        drawPath(canvas, view);
        // Pins never hide each other: route pins keep their place, a colliding pin moves aside with a leader line.
        List<MapMath.Marker> ordered = MapMath.priority(markers);
        float[][] at = new float[ordered.size()][];
        for (int i = 0; i < at.length; i++) at[i] = new float[]{view.x(ordered.get(i).longitude()), view.y(ordered.get(i).latitude())};
        float[][] placed = MapMath.declutter(at, PIN_SPACING);
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(4); stroke.setColor(0xff333333);
        for (int i = 0; i < at.length; i++) {
            if (at[i][0] == placed[i][0] && at[i][1] == placed[i][1]) continue;
            canvas.drawLine(at[i][0], at[i][1], placed[i][0], placed[i][1], stroke);
            fill.setStyle(Paint.Style.FILL); fill.setColor(0xff333333); canvas.drawCircle(at[i][0], at[i][1], 6, fill);
        }
        for (int i = at.length - 1; i >= 0; i--) drawPin(canvas, placed[i][0], placed[i][1], ordered.get(i));
        drawAttribution(canvas, w, h);
        canvas.restore();
    }

    private void drawPath(Canvas canvas, MapMath.Viewport view) {
        List<MapMath.Marker> path = MapMath.path(markers);
        if (path.size() < 2) return;
        Path line = new Path();
        for (int i = 0; i < path.size(); i++) {
            float x = view.x(path.get(i).longitude()), y = view.y(path.get(i).latitude());
            if (i == 0) line.moveTo(x, y); else line.lineTo(x, y);
        }
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setPathEffect(null); stroke.setStrokeWidth(12); stroke.setColor(0xccffffff); canvas.drawPath(line, stroke);
        stroke.setPathEffect(new DashPathEffect(new float[]{26, 18}, 0)); stroke.setStrokeWidth(7); stroke.setColor(colors.primary());
        canvas.drawPath(line, stroke);
        stroke.setPathEffect(null);
    }

    private void drawPin(Canvas canvas, float x, float y, MapMath.Marker m) {
        int color = switch (m.kind()) {
            case ORIGIN -> 0xff1a73e8;
            case STOP -> colors.primary();
            case DESTINATION -> 0xffd93025;
            case FRIEND -> 0xff8e44ad;
        };
        float radius = m.kind() == MapMath.Kind.DESTINATION ? 26 : m.kind() == MapMath.Kind.ORIGIN ? 16 : 22;
        fill.setStyle(Paint.Style.FILL); fill.setColor(0xffffffff); canvas.drawCircle(x, y, radius + 6, fill);
        fill.setColor(color); canvas.drawCircle(x, y, radius, fill);
        if (m.kind() == MapMath.Kind.ORIGIN) { fill.setColor(0xffffffff); canvas.drawCircle(x, y, 6, fill); return; }
        String text = m.kind() == MapMath.Kind.DESTINATION ? "도" : m.label();
        label.setColor(0xffffffff); label.setTextSize(radius * 1.05f);
        canvas.drawText(text, x, y - (label.descent() + label.ascent()) / 2, label);
    }

    private void drawAttribution(Canvas canvas, int w, int h) {
        Paint small = new Paint(Paint.ANTI_ALIAS_FLAG); small.setTextSize(20); small.setTextAlign(Paint.Align.RIGHT);
        float width = small.measureText(attribution);
        fill.setColor(0xccffffff); canvas.drawRect(w - width - 20, h - 34, w, h, fill);
        small.setColor(0xff333333); canvas.drawText(attribution, w - 10, h - 10, small);
    }

    private String describe() {
        StringBuilder out = new StringBuilder("지도: ");
        for (MapMath.Marker m : MapMath.path(markers)) out.append(m.name()).append(" → ");
        if (out.toString().endsWith(" → ")) out.setLength(out.length() - 3);
        long friends = markers.stream().filter(m -> m.kind() == MapMath.Kind.FRIEND).count();
        if (friends > 0) out.append(", 친구가 보낸 곳 ").append(friends).append("곳");
        return out.toString();
    }
}
