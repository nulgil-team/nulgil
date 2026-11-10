package kr.routine.presentation;

import java.util.List;
import kr.routine.core.Model.ActiveRoute;
import kr.routine.core.Model.Destination;
import kr.routine.core.Model.Preference;
import kr.routine.core.Model.HoldType;
import kr.routine.core.Model.TiedCandidate;

/** Immutable, framework-independent screen contract. All unknown measurements are null. */
public record BackendSnapshot(
        String schemaVersion, String mode, Context context, List<Destination> destinations,
        Preference preference, boolean collectionEnabled, Counts counts,
        Recommendation recommendation, Traffic traffic, Navigation navigation,
        Vehicle vehicle, Capabilities capabilities, Notice notice) {
    public BackendSnapshot { destinations = List.copyOf(destinations); }
    public record Context(long now, String zoneId, String localDate, int day, int minute) {}
    public record Counts(int rules, int selections, int estimates, int navigationRequests) {}
    public record Recommendation(String status, String source, Destination destination, String reason, Integer samples,
                                 HoldType holdType, List<TiedCandidate> candidates) {
        public Recommendation { candidates = List.copyOf(candidates); }
        public Recommendation(String status, String source, Destination destination, String reason, Integer samples) {
            this(status, source, destination, reason, samples, HoldType.NONE, List.of());
        }
    }
    public record DayEstimate(String localDate, int minutes) {}
    public record Traffic(String status, Integer estimateMinutes, Integer baselineMinutes, Integer delayMinutes,
                          boolean important, Long observedAt, String basis, String source, String message,
                          String kind, List<DayEstimate> history) {
        public Traffic { history = List.copyOf(history); }
    }
    public record Navigation(String state, String code, boolean canConfirm, String message, ActiveRoute activeRoute) {}
    public record Vehicle(String state, String source) {}
    public record Capabilities(boolean liveNavigation, boolean mapGeometry, boolean etaPerPreference) {}
    public record Notice(String code, String message) {}
}
