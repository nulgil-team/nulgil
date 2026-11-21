package kr.routine.core;

import java.util.List;
import kr.routine.core.Model.*;

/** Callbacks occur on the calling UI thread. At most one terminal callback per operation. */
public interface NaviGateway extends AutoCloseable {
    interface Callback<T> { void onSuccess(T value); void onFailure(String message); }
    void connect(Callback<Void> callback);
    void loadDestinations(Callback<List<Destination>> callback);
    void estimate(Destination destination, long now, Callback<Estimate> callback);
    /** Read only. Missing or ambiguous information must not be returned as INACTIVE. */
    void queryActiveRoute(Callback<ActiveRoute> callback);
    /** Recheck route and parking before dispatch. Report a known pre-dispatch refusal via RouteResult.notSent. */
    void start(Destination destination, Preference preference, Callback<RouteResult> callback);
    /**
     * Same contract as start(destination, preference), with stops visited in order before the destination.
     * A gateway that cannot send stops must report notSent, never drop them silently.
     */
    default void start(Destination destination, List<Destination> stops, Preference preference, Callback<RouteResult> callback) {
        if (stops.isEmpty()) { start(destination, preference, callback); return; }
        callback.onSuccess(RouteResult.notSent(ActiveRoute.INACTIVE, "이 연결에서는 들를 곳을 함께 요청할 수 없어요."));
    }
    /**
     * Adds one stop to the route currently being guided. Rechecks guidance, existing stops and parking first and
     * reports notSent when nothing was sent. Never retries; an unverified result stays unverified.
     */
    default void addStop(Destination stop, Callback<RouteResult> callback) {
        callback.onSuccess(RouteResult.notSent(ActiveRoute.ACTIVE, "이 연결에서는 안내 중 들를 곳을 넣을 수 없어요."));
    }
    /**
     * Shows the stock navigation map: the destination before departure, or the whole active route when
     * routeOverview is true. Display only; it never requests, changes or cancels a route.
     */
    default void showNativeMap(Destination destination, boolean routeOverview, Callback<Void> callback) {
        callback.onFailure("이 연결에서는 순정 지도를 열 수 없어요.");
    }
    /**
     * Last position the navi reported (kept in memory only, for the in-app map). Null when unknown.
     * Never triggers a navi call by itself.
     */
    default Point currentPosition() { return null; }
    /** Congestion along the route of the last estimate for this destination; empty when unknown. Never calls the navi. */
    default java.util.List<TrafficSegment> traffic(Destination destination) { return java.util.List.of(); }
    boolean isDemo();
    boolean isParked();
    void close();
}
