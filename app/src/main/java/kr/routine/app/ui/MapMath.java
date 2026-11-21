package kr.routine.app.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Web Mercator math for the in-app map: which zoom fits the markers, where each marker and tile goes.
 * Pure Java so it can be checked without Android.
 */
public final class MapMath {
    private MapMath() {}
    public static final int TILE = 256, MIN_ZOOM = 5, MAX_ZOOM = 16, SINGLE_ZOOM = 15;

    public enum Kind { ORIGIN, STOP, DESTINATION, FRIEND }

    /** label: short text inside the pin (stop number, F for a friend's place); name: shown in the legend. */
    public record Marker(double latitude, double longitude, Kind kind, String label, String name) {
        public Marker {
            Objects.requireNonNull(kind);
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || Math.abs(latitude) > 85 || Math.abs(longitude) > 180)
                throw new IllegalArgumentException("bad marker");
            label = label == null ? "" : label; name = name == null ? "" : name;
        }
    }

    /** Pixel position on the world map at a zoom level. */
    public static double worldX(double longitude, int zoom) { return (longitude + 180.0) / 360.0 * TILE * (1L << zoom); }

    public static double worldY(double latitude, int zoom) {
        double rad = Math.toRadians(latitude);
        return (1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2 * TILE * (1L << zoom);
    }

    /** View placement: zoom and the world pixel at the view's top-left corner. */
    public record Viewport(int zoom, double left, double top, int width, int height) {
        public float x(double longitude) { return (float) (worldX(longitude, zoom) - left); }
        public float y(double latitude) { return (float) (worldY(latitude, zoom) - top); }
        /** Tiles covering the view, as {x, y} at this zoom. Wraps nothing: Korea is far from the date line. */
        public List<int[]> tiles() {
            List<int[]> out = new ArrayList<>();
            int max = (1 << zoom) - 1;
            int x0 = (int) Math.floor(left / TILE), y0 = (int) Math.floor(top / TILE);
            int x1 = (int) Math.floor((left + width - 1) / TILE), y1 = (int) Math.floor((top + height - 1) / TILE);
            for (int ty = Math.max(0, y0); ty <= Math.min(max, y1); ty++)
                for (int tx = Math.max(0, x0); tx <= Math.min(max, x1); tx++) out.add(new int[]{tx, ty});
            return out;
        }
    }

    /** Largest zoom at which every marker fits inside the view with the given padding. */
    public static Viewport fit(List<Marker> markers, int width, int height, int padding) {
        if (markers.isEmpty() || width < 1 || height < 1) throw new IllegalArgumentException("nothing to show");
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (Marker m : markers) {
            minLat = Math.min(minLat, m.latitude()); maxLat = Math.max(maxLat, m.latitude());
            minLon = Math.min(minLon, m.longitude()); maxLon = Math.max(maxLon, m.longitude());
        }
        int innerW = Math.max(1, width - 2 * padding), innerH = Math.max(1, height - 2 * padding);
        int zoom = MIN_ZOOM;
        boolean single = maxLat - minLat < 1e-6 && maxLon - minLon < 1e-6;
        if (single) zoom = SINGLE_ZOOM;
        else for (int z = MAX_ZOOM; z >= MIN_ZOOM; z--) {
            double w = worldX(maxLon, z) - worldX(minLon, z), h = worldY(minLat, z) - worldY(maxLat, z);
            if (w <= innerW && h <= innerH) { zoom = z; break; }
        }
        double cx = (worldX(minLon, zoom) + worldX(maxLon, zoom)) / 2, cy = (worldY(maxLat, zoom) + worldY(minLat, zoom)) / 2;
        return new Viewport(zoom, cx - width / 2.0, cy - height / 2.0, width, height);
    }

    /**
     * Screen positions for pins so none overlap. Earlier points have priority and keep their place; a later point
     * that collides moves to the nearest free spot around it (rings of 12 directions). Callers draw a leader line
     * from the true position to the moved pin.
     */
    public static float[][] declutter(float[][] points, float minDistance) {
        float[][] out = new float[points.length][];
        for (int i = 0; i < points.length; i++) {
            float[] chosen = points[i];
            if (collides(chosen, out, i, minDistance)) {
                search:
                for (int ring = 1; ring <= 4; ring++)
                    for (int k = 0; k < 12; k++) {
                        double angle = Math.toRadians(-90 + k * 30);
                        float[] candidate = {(float) (points[i][0] + ring * minDistance * Math.cos(angle)),
                            (float) (points[i][1] + ring * minDistance * Math.sin(angle))};
                        if (!collides(candidate, out, i, minDistance)) { chosen = candidate; break search; }
                    }
            }
            out[i] = chosen;
        }
        return out;
    }

    private static boolean collides(float[] p, float[][] placed, int count, float minDistance) {
        for (int j = 0; j < count; j++)
            if (Math.hypot(p[0] - placed[j][0], p[1] - placed[j][1]) < minDistance) return true;
        return false;
    }

    /** Pins in drawing priority: the route (origin, stops, destination) keeps true positions before friends. */
    public static List<Marker> priority(List<Marker> markers) {
        List<Marker> out = new ArrayList<>(path(markers));
        for (Marker m : markers) if (m.kind() == Kind.FRIEND) out.add(m);
        return out;
    }

    /** Visiting order for the dashed line: origin, stops, destination. Friends' places are not on the line. */
    public static List<Marker> path(List<Marker> markers) {
        List<Marker> out = new ArrayList<>();
        for (Kind kind : new Kind[]{Kind.ORIGIN, Kind.STOP, Kind.DESTINATION})
            for (Marker m : markers) if (m.kind() == kind) out.add(m);
        return out;
    }
}
