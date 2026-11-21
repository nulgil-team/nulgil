package kr.routine.core;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import kr.routine.core.Model.*;

public final class DepartureController {
    public enum State { IDLE, READY, CHECKING_ROUTE, ALREADY_GUIDING, ROUTE_UNAVAILABLE, REQUESTING, VERIFIED, UNCONFIRMED, DISMISSED }

    private final NaviGateway gateway;
    private final RoutineStore store;
    private State state = State.IDLE;
    private String message = "목적지를 먼저 선택하세요.";
    private Destination proposed;
    private Preference preference;
    private ActiveRoute activeRoute = ActiveRoute.UNAVAILABLE;
    private boolean hasReading, querying, issued;
    private long proposalRevision, querySequence, attemptSequence;
    private List<Destination> stops = List.of();
    private List<Destination> sentStops = List.of();
    private StopState stopState = StopState.NONE;
    private String stopMessage = "";
    private long stopSequence;
    private int addedStops;
    private List<Destination> addedStopList = List.of();
    /** Stops ever dispatched while guiding. Never sent twice, whatever the result. */
    private final Set<String> issuedStops = new HashSet<>();

    public DepartureController(NaviGateway gateway, RoutineStore store) {
        this.gateway = Objects.requireNonNull(gateway);
        this.store = Objects.requireNonNull(store);
    }

    public void propose(Destination destination, Preference preference) {
        if (issued) return;
        proposed = Objects.requireNonNull(destination);
        this.preference = Objects.requireNonNull(preference);
        stops = stops.stream().filter(stop -> !Model.samePlace(stop, destination)).toList();
        proposalRevision++;
        if (querying) {
            showChecking();
        } else if (hasReading) {
            showAvailability();
        } else {
            refreshActiveRoute(() -> {});
        }
    }

    /**
     * Stops planned with the proposal, sent together with the destination on confirm. Changing them invalidates
     * a confirmation that is still checking the route, exactly like changing the destination.
     */
    public boolean setStops(List<Destination> value) {
        if (issued || proposed == null || state == State.REQUESTING) return false;
        stops = Model.checkStops(proposed, value);
        proposalRevision++;
        return true;
    }

    /** Read the current route on entry or explicit refresh. Never dispatch or unlock an issued trip. */
    public void refreshActiveRoute(Runnable changed) {
        Objects.requireNonNull(changed);
        if (querying || state == State.REQUESTING) return;
        readRoute(() -> {}, changed);
    }

    public void confirm(int day, int minute, long now, Runnable changed) {
        Objects.requireNonNull(changed);
        Model.checkTime(day, minute);
        if (state != State.READY || querying || issued) return;
        if (!parked()) { changed.run(); return; }
        long confirmedRevision = proposalRevision;
        readRoute(changed, () -> {
            if (confirmedRevision == proposalRevision && state == State.READY && parked()) {
                dispatch(day, minute, now, changed);
            } else {
                changed.run();
            }
        });
    }

    private void readRoute(Runnable started, Runnable finished) {
        long query = ++querySequence;
        querying = true;
        if (!issued) showChecking();
        started.run();
        NaviGateway.Callback<ActiveRoute> callback = new NaviGateway.Callback<>() {
            @Override public void onSuccess(ActiveRoute status) {
                if (!querying || query != querySequence) return;
                querying = false;
                hasReading = true;
                activeRoute = status == null ? ActiveRoute.UNAVAILABLE : status;
                if (!issued && state != State.DISMISSED) showAvailability();
                finished.run();
            }
            @Override public void onFailure(String error) { onSuccess(ActiveRoute.UNAVAILABLE); }
        };
        try { gateway.queryActiveRoute(callback); }
        catch (RuntimeException error) { callback.onFailure(error.getMessage()); }
    }

    private void showChecking() {
        state = State.CHECKING_ROUTE;
        message = "현재 안내 경로를 확인하고 있습니다.";
    }

    private void showAvailability() {
        switch (activeRoute) {
            case ACTIVE -> {
                state = State.ALREADY_GUIDING;
                message = "이미 안내 중입니다. 현재 경로를 유지합니다.";
            }
            case UNAVAILABLE -> {
                state = State.ROUTE_UNAVAILABLE;
                message = "현재 안내 경로를 확인하지 못했습니다. 상태를 다시 확인해 주세요.";
            }
            case INACTIVE -> {
                state = proposed == null ? State.IDLE : State.READY;
                message = proposed == null ? "목적지를 먼저 선택하세요." : proposed.name() + " · 확인하면 안내를 요청합니다.";
            }
        }
    }

    private boolean parked() {
        try {
            if (gateway.isParked()) return true;
            message = "정차 상태를 확인하지 못했습니다. 안내 요청을 보류합니다.";
        } catch (RuntimeException error) {
            message = "차량 상태 조회 실패로 안내 요청을 보류합니다.";
        }
        return false;
    }

    private void dispatch(int day, int minute, long now, Runnable changed) {
        Destination submitted = proposed;
        Preference submittedPreference = preference;
        List<Destination> submittedStops = stops;
        long attempt = ++attemptSequence;
        issued = true;
        state = State.REQUESTING;
        message = "안내 요청의 실제 반영을 확인하고 있습니다.";
        changed.run();
        NaviGateway.Callback<RouteResult> callback = new NaviGateway.Callback<>() {
            private boolean current() { return state == State.REQUESTING && attempt == attemptSequence; }

            @Override public void onSuccess(RouteResult result) {
                if (!current()) return;
                if (result != null && result.blockedBy() != null) {
                    // The gateway's final check determined that nothing was sent.
                    issued = false;
                    activeRoute = result.blockedBy();
                    showAvailability();
                    if (result.message() != null) message = result.message();
                } else if (result == null || !result.verified()) {
                    activeRoute = ActiveRoute.UNAVAILABLE;
                    state = State.UNCONFIRMED;
                    message = result == null ? "안내 결과 미확인. 순정 내비를 확인하세요." : result.message();
                } else {
                    activeRoute = ActiveRoute.ACTIVE;
                    state = State.VERIFIED;
                    message = result.message();
                    sentStops = submittedStops;
                    // A place sent from the phone is a one-off: it is not learned as a routine destination.
                    if (!Model.external(submitted)) {
                        try { store.recordSelection(new Selection(submitted, day, minute, now)); }
                        catch (IOException error) { message += " 선택 기록은 저장하지 못했습니다."; }
                    }
                }
                changed.run();
            }

            @Override public void onFailure(String error) {
                onSuccess(new RouteResult(false, "안내 결과 미확인: " + reason(error) + ". 재요청하지 않고 순정 내비를 확인하세요."));
            }
        };
        try { gateway.start(submitted, submittedStops, submittedPreference, callback); }
        catch (RuntimeException error) { callback.onFailure("안내 요청 중 오류가 발생했습니다"); }
    }

    /** Stops known to be on the guided route: sent with a verified start plus verified additions. */
    private int knownStops() { return sentStops.size() + addedStops; }

    /** A stop can be added only while the navi is guiding and no other check or request is in flight. */
    public boolean canAddStop() {
        return activeRoute == ActiveRoute.ACTIVE && !querying && state != State.REQUESTING
            && stopState != StopState.REQUESTING && knownStops() < Model.MAX_STOPS;
    }

    /**
     * Adds one stop to the guided route after the gateway rechecks guidance, existing stops and parking.
     * The same place is dispatched at most once per session, including after an unverified result.
     */
    public void addStop(Destination stop, Runnable changed) {
        Objects.requireNonNull(stop); Objects.requireNonNull(changed);
        if (!canAddStop()) return;
        String key = stop.id() + "/" + stop.subId();
        if (issuedStops.contains(key)) {
            stopState = StopState.NOT_SENT;
            stopMessage = "이미 요청한 곳이에요. 다시 요청하지 않아요.";
            changed.run(); return;
        }
        if (!parked()) {
            stopState = StopState.NOT_SENT;
            stopMessage = "정차를 확인하면 들를 곳을 넣을 수 있어요.";
            changed.run(); return;
        }
        long attempt = ++stopSequence;
        stopState = StopState.REQUESTING;
        stopMessage = stop.name() + " · 경로에 넣고 있어요.";
        changed.run();
        NaviGateway.Callback<RouteResult> callback = new NaviGateway.Callback<>() {
            private boolean current() { return stopState == StopState.REQUESTING && attempt == stopSequence; }
            @Override public void onSuccess(RouteResult result) {
                if (!current()) return;
                if (result != null && result.blockedBy() != null) {
                    issuedStops.remove(key);
                    stopState = StopState.NOT_SENT;
                    stopMessage = result.message() == null ? "들를 곳을 요청하지 않았어요." : result.message();
                } else if (result != null && result.verified()) {
                    addedStops++;
                    java.util.ArrayList<Destination> next = new java.util.ArrayList<>(addedStopList); next.add(stop); addedStopList = List.copyOf(next);
                    stopState = StopState.ADDED;
                    stopMessage = result.message();
                } else {
                    stopState = StopState.UNCONFIRMED;
                    stopMessage = result == null ? "들를 곳이 들어갔는지 확인하지 못했어요. 다시 요청하지 않아요." : result.message();
                }
                changed.run();
            }
            @Override public void onFailure(String error) {
                onSuccess(new RouteResult(false, "들를 곳 결과 미확인: " + reason(error) + ". 다시 요청하지 않고 순정 내비를 확인하세요."));
            }
        };
        issuedStops.add(key);
        try { gateway.addStop(stop, callback); }
        catch (RuntimeException error) { callback.onFailure("들를 곳 요청 중 오류가 발생했습니다"); }
    }

    public void dismiss() {
        if (issued) return;
        proposalRevision++;
        proposed = null;
        stops = List.of();
        state = State.DISMISSED;
        message = "이번 추천을 넘겼습니다. 내비는 변경하지 않았습니다.";
    }
    /** Planned stops; after confirm these are the stops that were submitted with the destination. */
    public List<Destination> stops() { return stops; }
    /** Stops verified as added while guiding. */
    public int addedStops() { return addedStops; }
    /** Places verified as added while guiding, in the order they were added. */
    public List<Destination> addedStopList() { return addedStopList; }
    public StopState stopState() { return stopState; }
    public String stopMessage() { return stopMessage; }
    public ActiveRoute activeRoute() { return activeRoute; }
    public State state() { return state; }
    public String message() { return message; }

    /** Gateway errors are sentences that may already end with a period; strip it so messages never show "..". */
    static String reason(String error) {
        if (error == null || error.isBlank()) return "알 수 없는 오류";
        return error.strip().replaceAll("[.\\s]+$", "");
    }
}
