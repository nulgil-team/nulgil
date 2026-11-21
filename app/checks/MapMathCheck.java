import java.util.List;
import kr.routine.app.ui.MapMath;
import kr.routine.app.ui.MapMath.*;

/** In-app map placement: tiles, zoom fit, visiting order. No Android needed. */
public final class MapMathCheck {
    private static int checks;
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++; System.out.println("PASS " + label);
    }

    public static void main(String[] args) {
        // Known tile: Seoul City Hall (37.5663, 126.9779) is tile 13/6985/3172 in the OpenStreetMap scheme.
        check((int) (MapMath.worldX(126.9779, 13) / MapMath.TILE) == 6985 && (int) (MapMath.worldY(37.5663, 13) / MapMath.TILE) == 3172,
            "Web Mercator matches the OpenStreetMap tile numbering");
        Marker origin = new Marker(37.4200, 127.1265, Kind.ORIGIN, "", "현재 위치");
        Marker stop = new Marker(37.5153, 127.0438, Kind.STOP, "1", "봉은사");
        Marker destination = new Marker(37.5116, 127.0592, Kind.DESTINATION, "", "코엑스");
        Marker friend = new Marker(37.5085, 127.0488, Kind.FRIEND, "A", "선정릉");
        List<Marker> all = List.of(friend, destination, stop, origin);
        Viewport view = MapMath.fit(all, 1600, 460, 64);
        boolean inside = true;
        for (Marker m : all) {
            float x = view.x(m.longitude()), y = view.y(m.latitude());
            inside &= x >= 63 && x <= 1600 - 63 && y >= 63 && y <= 460 - 63;
        }
        check(inside, "every marker fits inside the padded view");
        Viewport closer = new Viewport(view.zoom() + 1, 0, 0, 1600, 460);
        double w = MapMath.worldX(127.1265, closer.zoom()) - MapMath.worldX(127.0438, closer.zoom());
        double h = MapMath.worldY(37.4200, closer.zoom()) - MapMath.worldY(37.5153, closer.zoom());
        check(w > 1600 - 128 || h > 460 - 128, "the chosen zoom is the closest one that still fits");
        check(MapMath.fit(List.of(destination), 800, 400, 64).zoom() == MapMath.SINGLE_ZOOM, "a single place uses a street-level zoom");
        List<int[]> tiles = view.tiles();
        check(!tiles.isEmpty() && tiles.size() <= (1600 / 256 + 2) * (460 / 256 + 2), "only tiles covering the view are requested");
        check(MapMath.path(all).equals(List.of(origin, stop, destination)), "the dashed line follows origin, stops, destination; friends stay off it");
        float[][] placed = MapMath.declutter(new float[][]{{100, 100}, {104, 98}, {400, 100}, {100, 100}}, 66);
        boolean apart = true;
        for (int i = 0; i < placed.length; i++)
            for (int j = 0; j < i; j++) apart &= Math.hypot(placed[i][0] - placed[j][0], placed[i][1] - placed[j][1]) >= 65.9;
        check(apart && placed[0][0] == 100 && placed[0][1] == 100 && placed[2][0] == 400,
            "overlapping pins move apart; the first and non-colliding pins keep their true place");
        check(MapMath.priority(List.of(friend, destination, stop, origin)).equals(List.of(origin, stop, destination, friend)),
            "route pins are placed before friends' places");
        boolean rejected;
        try { new Marker(Double.NaN, 127, Kind.STOP, "", ""); rejected = false; } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "invalid coordinates are rejected");
        System.out.println("MapMathCheck: " + checks + " assertions passed");
    }
}
