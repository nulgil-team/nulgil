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
     * Shows the stock navigation map: the destination before departure, or the whole active route when
     * routeOverview is true. Display only; it never requests, changes or cancels a route.
     */
    default void showNativeMap(Destination destination, boolean routeOverview, Callback<Void> callback) {
        callback.onFailure("이 연결에서는 순정 지도를 열 수 없어요.");
    }
    boolean isDemo();
    boolean isParked();
    void close();
}
