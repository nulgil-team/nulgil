package kr.routine.app.vehicle;

import java.time.ZoneId;
import java.util.List;
import kr.routine.core.DepartureController;
import kr.routine.core.Model.*;

/** Immutable native-screen contract; null numbers are unknown, never zero estimates. */
public record VehicleUiState(
    boolean demo, boolean connected, boolean busy, boolean parked,
    boolean collectionEnabled, boolean collectionBlocked,
    List<Destination> destinations, Destination destination, Preference preference,
    String source, int samples, HoldType holdType, List<TiedCandidate> candidates, String reason,
    String trafficKind, Integer minutes, Integer baselineMinutes, Integer delayMinutes,
    List<DayEstimate> history, DepartureController.State departureState, ActiveRoute activeRoute,
    boolean canEdit, boolean canConfirm, boolean canRefresh, boolean canSwitchMode,
    String message, String notice, long now, ZoneId zone, int navigationRequests, List<Rule> rules,
    List<Destination> stops, int addedStops, String stopState, String stopMessage, boolean canPlanStops, boolean canAddStop,
    InviteUiState invite, Point position, List<Destination> addedStopList, PhoneUiState phone, List<TrafficSegment> traffic) {
    public VehicleUiState {
        invite = invite == null ? InviteUiState.unavailable() : invite;
        phone = phone == null ? PhoneUiState.unavailable() : phone;
        traffic = traffic == null ? List.of() : List.copyOf(traffic);
        stops = List.copyOf(stops);
        addedStopList = addedStopList == null ? List.of() : List.copyOf(addedStopList);
        destinations = List.copyOf(destinations);
        candidates = List.copyOf(candidates);
        history = List.copyOf(history);
        rules = List.copyOf(rules);
    }
}
