package kr.routine.app.platform;

import ai.pleos.playground.navi.constants.NaviMode;
import ai.pleos.playground.navi.data.RouteStateInfo;
import kr.routine.core.Model.ActiveRoute;

/** Policy for one SDK state observation. Only stable, ready safe-guidance permits a new route. */
final class PleosRouteStatus {
    private PleosRouteStatus() {}

    static ActiveRoute from(RouteStateInfo state) {
        if (state == null || !state.getTermsAgreement()) return ActiveRoute.UNAVAILABLE;
        NaviMode mode = state.getNaviMode();
        if (mode == NaviMode.ROUTE_GUIDING || mode == NaviMode.SIMULATION_GUIDING) return ActiveRoute.ACTIVE;
        if (mode == NaviMode.SAFE_GUIDING && !state.isRouting()) return ActiveRoute.INACTIVE;
        return ActiveRoute.UNAVAILABLE;
    }

    static boolean confirmsNativeGuidance(RouteStateInfo state) {
        return from(state) == ActiveRoute.ACTIVE && state.getNaviMode() == NaviMode.ROUTE_GUIDING;
    }
}
