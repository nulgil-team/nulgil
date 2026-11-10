package kr.routine.core;

import java.util.Objects;
import java.util.List;
import java.time.LocalDate;

public final class Model {
    private Model() {}
    public enum Preference { RECOMMENDED, SHORT_TIME, COMFORTABLE }
    public enum ActiveRoute { ACTIVE, INACTIVE, UNAVAILABLE }
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
