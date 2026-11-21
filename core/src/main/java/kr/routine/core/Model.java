package kr.routine.core;

import java.util.Objects;
import java.util.List;
import java.time.LocalDate;

public final class Model {
    private Model() {}
    public enum Preference { RECOMMENDED, SHORT_TIME, COMFORTABLE }
    public enum ActiveRoute { ACTIVE, INACTIVE, UNAVAILABLE }
    /** The stock navi accepts two waypoints (WaypointIndex FIRST and SECOND); the MVP keeps the same limit. */
    public static final int MAX_STOPS = 2;
    /** Result of adding a stop to the route being guided. */
    public enum StopState { NONE, REQUESTING, ADDED, NOT_SENT, UNCONFIRMED }

    /** Same POI: id and subId. Coordinates may be snapped by the navi, so they are not part of identity. */
    public static boolean samePlace(Destination a, Destination b) {
        return a != null && b != null && a.id().equals(b.id()) && a.subId().equals(b.subId());
    }

    /** Stops in visiting order: at most two, no duplicates, none equal to the destination. */
    public static List<Destination> checkStops(Destination destination, List<Destination> stops) {
        Objects.requireNonNull(stops, "stops");
        if (stops.size() > MAX_STOPS) throw new IllegalArgumentException("들를 곳은 " + MAX_STOPS + "곳까지예요.");
        for (int i = 0; i < stops.size(); i++) {
            Destination stop = Objects.requireNonNull(stops.get(i), "stop");
            if (samePlace(stop, destination)) throw new IllegalArgumentException("목적지는 들를 곳으로 넣을 수 없어요.");
            for (int j = 0; j < i; j++)
                if (samePlace(stop, stops.get(j))) throw new IllegalArgumentException("같은 곳을 두 번 넣을 수 없어요.");
        }
        return List.copyOf(stops);
    }
    public record Destination(String id, String name, String address, double latitude,
                              double longitude, String subId) {
        public Destination {
            Objects.requireNonNull(id); Objects.requireNonNull(name); Objects.requireNonNull(address);
            Objects.requireNonNull(subId);
            if (id.isBlank() || name.isBlank() || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180)
                throw new IllegalArgumentException("유효한 순정 장소 정보가 필요합니다.");
        }
    }
    /** A map position. (0, 0) is how the navi reports "unknown", so it is rejected. */
    public record Point(double latitude, double longitude) {
        public Point {
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || latitude < -85 || latitude > 85
                || longitude < -180 || longitude > 180 || (latitude == 0.0 && longitude == 0.0))
                throw new IllegalArgumentException("잘못된 위치");
        }
        public static Point of(double latitude, double longitude) {
            try { return new Point(latitude, longitude); } catch (IllegalArgumentException invalid) { return null; }
        }
    }
    public record Rule(Destination destination, int day, int minute) {
        public Rule { Objects.requireNonNull(destination); checkTime(day, minute); }
    }
    /** Confirmed destination selections, not completed journeys. */
    public record Selection(Destination destination, int day, int minute, long selectedAt) {
        public Selection { Objects.requireNonNull(destination); checkTime(day, minute); }
    }
    /** basis includes route option and measurement kind; -1 means unavailable/unverified units. */
    public record Estimate(String destinationId, String originKey, String basis, int minutes,
                           long observedAt, String details, boolean demo) {
        public Estimate {
            Objects.requireNonNull(destinationId); Objects.requireNonNull(originKey);
            Objects.requireNonNull(basis); Objects.requireNonNull(details);
            if (minutes < -1) throw new IllegalArgumentException("잘못된 예상 시간");
        }
    }
    public enum HoldType { NONE, NOT_ENOUGH, TIE }
    public enum TrafficKind { MISSING, STALE, UNKNOWN_UNITS, NO_COMPARE, CALM, LATE }
    public record TiedCandidate(Destination destination, int samples) {
        public TiedCandidate { Objects.requireNonNull(destination); }
    }
    public record Recommendation(Destination destination, String reason, int samples,
                                 HoldType holdType, List<TiedCandidate> candidates) {
        public Recommendation { Objects.requireNonNull(holdType); candidates = List.copyOf(candidates); }
        public Recommendation(Destination destination, String reason, int samples) {
            this(destination, reason, samples, destination == null ? HoldType.NOT_ENOUGH : HoldType.NONE, List.of());
        }
    }
    public record DayEstimate(LocalDate localDate, int minutes) {
        public DayEstimate { Objects.requireNonNull(localDate); }
    }
    public record Advice(String text, Integer delayMinutes, boolean important,
                         TrafficKind kind, List<DayEstimate> history) {
        public Advice { Objects.requireNonNull(kind); history = List.copyOf(history); }
        public Advice(String text, Integer delayMinutes, boolean important) {
            this(text, delayMinutes, important,
                delayMinutes == null ? TrafficKind.NO_COMPARE : important ? TrafficKind.LATE : TrafficKind.CALM,
                List.of());
        }
    }
    public record RouteResult(boolean verified, String message, ActiveRoute blockedBy) {
        public RouteResult(boolean verified, String message) { this(verified, message, null); }
        /** The adapter proved no outbound route request was made. */
        public static RouteResult notSent(ActiveRoute status, String reason) {
            return new RouteResult(false, reason, Objects.requireNonNull(status));
        }
    }
    public static void checkTime(int day, int minute) {
        if (day < 1 || day > 7 || minute < 0 || minute > 1439)
            throw new IllegalArgumentException("요일 또는 시간이 올바르지 않습니다.");
    }
}
