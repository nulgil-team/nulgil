package kr.routine.app.ui;

import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;
import kr.routine.core.Model.Destination;
import kr.routine.core.Model.Point;

/** Map plus legend, as used on the home, guide and friend screens. Screens decide what to show, not how. */
public final class RouteMapCard {
    private RouteMapCard() {}

    /** Letter for the n-th friend suggestion on the map and in its list (A, B, C ...). */
    public static String friendLetter(int index) { return String.valueOf((char) ('A' + Math.min(index, 25))); }

    public static List<MapMath.Marker> markers(Point position, List<Destination> stops, Destination destination) {
        List<MapMath.Marker> out = new ArrayList<>();
        if (position != null) out.add(new MapMath.Marker(position.latitude(), position.longitude(), MapMath.Kind.ORIGIN, "", "현재 위치"));
        for (int i = 0; i < stops.size(); i++) {
            Destination stop = stops.get(i);
            out.add(new MapMath.Marker(stop.latitude(), stop.longitude(), MapMath.Kind.STOP, String.valueOf(i + 1), stop.name()));
        }
        if (destination != null)
            out.add(new MapMath.Marker(destination.latitude(), destination.longitude(), MapMath.Kind.DESTINATION, "", destination.name()));
        return out;
    }

    public static MapMath.Marker friend(Destination place, int index) {
        return new MapMath.Marker(place.latitude(), place.longitude(), MapMath.Kind.FRIEND, friendLetter(index), place.name());
    }

    public static LinearLayout build(UiKit kit, List<MapMath.Marker> markers, boolean night) {
        LinearLayout box = kit.column();
        int height = kit.metrics.compact() ? 380 : 540;
        RouteMapView map = new RouteMapView(kit.activity, markers, kit.colors, night, kit.typeface);
        box.addView(map, new LinearLayout.LayoutParams(UiKit.MATCH, height));
        StringBuilder legend = new StringBuilder();
        for (MapMath.Marker m : markers) {
            if (legend.length() > 0) legend.append("   ");
            legend.append(switch (m.kind()) {
                case ORIGIN -> "● 현재 위치";
                case STOP -> m.label() + " " + m.name();
                case DESTINATION -> "도 " + m.name();
                case FRIEND -> m.label() + " " + m.name() + "(친구)";
            });
        }
        kit.add(box, kit.text(legend.toString(), 32, true, kit.colors.text()), 12);
        boolean hasOrigin = markers.stream().anyMatch(m -> m.kind() == MapMath.Kind.ORIGIN);
        kit.add(box, kit.text((hasOrigin ? "" : "현재 위치는 교통 정보를 확인하면 표시돼요. ")
            + "점선은 들르는 순서예요. 실제 도로 경로는 순정 내비가 계산해요.", 28, false, kit.colors.secondary()), 6);
        return box;
    }
}
