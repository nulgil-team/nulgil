package kr.routine.app.platform;

import ai.pleos.playground.navi.constants.NaviMode;
import ai.pleos.playground.navi.constants.RouteOption;
import ai.pleos.playground.navi.data.RouteStateInfo;
import java.util.List;
import kr.routine.core.Model.ActiveRoute;

/** Real SDK DTOs on the JVM, without starting the navigation service. */
public final class PleosRouteStatusCheck {
    private static int checks;

    public static void main(String[] args) {
        check(null, ActiveRoute.UNAVAILABLE);
        for (NaviMode mode : NaviMode.values()) {
            check(observation(false, mode, false), ActiveRoute.UNAVAILABLE);
            check(observation(false, mode, true), ActiveRoute.UNAVAILABLE);
        }
        for (boolean routing : new boolean[]{false, true}) {
            check(observation(true, NaviMode.ROUTE_GUIDING, routing), ActiveRoute.ACTIVE);
            check(observation(true, NaviMode.SIMULATION_GUIDING, routing), ActiveRoute.ACTIVE);
            check(observation(true, NaviMode.NOT_WORKING, routing), ActiveRoute.UNAVAILABLE);
        }
        check(observation(true, NaviMode.SAFE_GUIDING, true), ActiveRoute.UNAVAILABLE);
        check(observation(true, NaviMode.SAFE_GUIDING, false), ActiveRoute.INACTIVE);
        checkReadback(observation(false, NaviMode.ROUTE_GUIDING, false), false);
        checkReadback(observation(true, NaviMode.ROUTE_GUIDING, false), true);
        checkReadback(observation(true, NaviMode.SIMULATION_GUIDING, false), false);
        checkReadback(observation(true, NaviMode.SAFE_GUIDING, false), false);
        checkReadback(null, false);
        System.out.println("PASS " + checks + " Pleos SDK route-state and readback checks");
    }

    private static RouteStateInfo observation(boolean terms, NaviMode mode, boolean routing) {
        return new RouteStateInfo(terms, mode, routing, RouteOption.RECOMMENDED, 0, List.of(), null);
    }

    private static void check(RouteStateInfo observation, ActiveRoute expected) {
        ActiveRoute actual = PleosRouteStatus.from(observation);
        if (actual != expected) throw new AssertionError("Expected " + expected + " but got " + actual + " for " + observation);
        checks++;
    }

    private static void checkReadback(RouteStateInfo observation, boolean expected) {
        if (PleosRouteStatus.confirmsNativeGuidance(observation) != expected)
            throw new AssertionError("Readback confirmation should be " + expected + " for " + observation);
        checks++;
    }
}
