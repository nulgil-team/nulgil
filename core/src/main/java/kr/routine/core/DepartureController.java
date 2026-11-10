package kr.routine.core;

import java.io.IOException;
import java.util.Objects;
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

    public DepartureController(NaviGateway gateway, RoutineStore store) {
        this.gateway = Objects.requireNonNull(gateway);
        this.store = Objects.requireNonNull(store);
    }

    public void propose(Destination destination, Preference preference) {
        if (issued) return;
        proposed = Objects.requireNonNull(destination);
        this.preference = Objects.requireNonNull(preference);
        proposalRevision++;
        if (querying) {
            showChecking();
        } else if (hasReading) {
            showAvailability();
        } else {
            refreshActiveRoute(() -> {});
        }
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
                    try { store.recordSelection(new Selection(submitted, day, minute, now)); }
                    catch (IOException error) { message += " 선택 기록은 저장하지 못했습니다."; }
                }
                changed.run();
            }

            @Override public void onFailure(String error) {
                onSuccess(new RouteResult(false, "안내 결과 미확인: " + error + ". 재요청하지 않고 순정 내비를 확인하세요."));
            }
        };
        try { gateway.start(submitted, submittedPreference, callback); }
        catch (RuntimeException error) { callback.onFailure("안내 요청 중 오류가 발생했습니다"); }
    }

    public void dismiss() {
        if (issued) return;
        proposalRevision++;
        proposed = null;
        state = State.DISMISSED;
        message = "이번 추천을 넘겼습니다. 내비는 변경하지 않았습니다.";
    }
    public ActiveRoute activeRoute() { return activeRoute; }
    public State state() { return state; }
    public String message() { return message; }
}
