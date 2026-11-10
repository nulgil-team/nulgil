import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import kr.routine.core.*;
import kr.routine.core.Model.*;
import kr.routine.core.DepartureController.State;

public final class ActiveRouteChecks {
    private static final Runnable NO_CHANGE = () -> {};
    private static final Preference PREF = Preference.RECOMMENDED;
    private static Path root;
    private static int passed;

    public static void main(String[] args) throws Exception {
        root = Files.createDirectories(Path.of(args[0]));
        for (ActiveRoute status : ActiveRoute.values()) checkDemo(status);
        checkChangedBeforeConfirm(ActiveRoute.ACTIVE);
        checkChangedBeforeConfirm(ActiveRoute.UNAVAILABLE);
        checkUnknownResultAfterDispatch();
        checkCancelDuringRead(false);
        checkCancelDuringRead(true);
        checkLateReadAndDoubleConfirm();
        checkParkingChange();
        checkReadFailures();
        checkLastMileRefusal(ActiveRoute.ACTIVE);
        checkLastMileRefusal(ActiveRoute.UNAVAILABLE);
        System.out.println("RESULT " + passed + " active-route assertions passed");
    }

    private static void checkDemo(ActiveRoute status) throws Exception {
        DemoNaviGateway gateway = new DemoNaviGateway();
        gateway.connect(CoreChecks.noFailure());
        gateway.setActiveRoute(status);
        RoutineStore store = store("entry-" + status);
        DepartureController controller = new DepartureController(gateway, store);
        controller.refreshActiveRoute(NO_CHANGE);
        expect(controller.activeRoute() == status && gateway.startCount() == 0, status + " entry is read-only");
        controller.propose(DemoFixtures.OFFICE, PREF);
        State expected = status == ActiveRoute.ACTIVE ? State.ALREADY_GUIDING
            : status == ActiveRoute.UNAVAILABLE ? State.ROUTE_UNAVAILABLE : State.READY;
        expect(controller.state() == expected, status + " pre-confirm state " + expected);
        confirm(controller); confirm(controller);
        int expectedRequests = status == ActiveRoute.INACTIVE ? 1 : 0;
        expect(gateway.startCount() == expectedRequests && store.selections().size() == expectedRequests,
            status + " actual requests=" + gateway.startCount() + ", expected=" + expectedRequests);
        expect(controller.state() == (status == ActiveRoute.INACTIVE ? State.VERIFIED : expected),
            status + " final state " + controller.state());
        if (status == ActiveRoute.INACTIVE) {
            gateway.setActiveRoute(ActiveRoute.INACTIVE);
            controller.refreshActiveRoute(NO_CHANGE);
            controller.propose(DemoFixtures.OTHER, PREF);
            confirm(controller);
            expect(controller.state() == State.VERIFIED && controller.activeRoute() == ActiveRoute.INACTIVE
                && gateway.startCount() == 1, "refresh reads latest route but cannot unlock verified trip");
        }
    }

    private static void checkChangedBeforeConfirm(ActiveRoute next) throws Exception {
        DemoNaviGateway gateway = new DemoNaviGateway();
        gateway.connect(CoreChecks.noFailure());
        DepartureController c = new DepartureController(gateway, store("change-" + next));
        c.propose(DemoFixtures.OFFICE, PREF);
        gateway.setActiveRoute(next);
        confirm(c);
        expect(c.activeRoute() == next && c.state() == (next == ActiveRoute.ACTIVE ? State.ALREADY_GUIDING : State.ROUTE_UNAVAILABLE)
            && gateway.startCount() == 0, "fresh confirmation catches " + next);
        gateway.setActiveRoute(ActiveRoute.INACTIVE);
        c.refreshActiveRoute(NO_CHANGE);
        expect(c.state() == State.READY && gateway.startCount() == 0, "explicit reread alone never dispatches " + next);
    }

    private static void checkUnknownResultAfterDispatch() throws Exception {
        DemoNaviGateway gateway = new DemoNaviGateway();
        gateway.connect(CoreChecks.noFailure());
        gateway.setFailNext(true);
        DepartureController c = new DepartureController(gateway, store("lost-response"));
        c.propose(DemoFixtures.OFFICE, PREF);
        confirm(c);
        expect(c.state() == State.UNCONFIRMED && c.activeRoute() == ActiveRoute.UNAVAILABLE
            && gateway.startCount() == 1, "lost response after actual dispatch is unconfirmed");
        c.refreshActiveRoute(NO_CHANGE);
        confirm(c);
        expect(c.state() == State.UNCONFIRMED && c.activeRoute() == ActiveRoute.INACTIVE && gateway.startCount() == 1,
            "reread cannot retry an uncertain trip");
    }

    private static void checkCancelDuringRead(boolean dismiss) throws Exception {
        ControlledGateway g = new ControlledGateway();
        DepartureController c = ready(g, "cancel-" + dismiss);
        confirm(c);
        if (dismiss) c.dismiss(); else c.propose(DemoFixtures.OTHER, Preference.COMFORTABLE);
        g.query.onSuccess(ActiveRoute.INACTIVE);
        expect(g.requests == 0 && c.state() == (dismiss ? State.DISMISSED : State.READY),
            "old confirmation dropped after " + (dismiss ? "dismiss" : "proposal change"));
    }

    private static void checkLateReadAndDoubleConfirm() throws Exception {
        ControlledGateway g = new ControlledGateway();
        DepartureController c = ready(g, "rapid");
        NaviGateway.Callback<ActiveRoute> old = g.query;
        confirm(c); confirm(c);
        old.onSuccess(ActiveRoute.INACTIVE);
        expect(g.reads == 2 && g.requests == 0 && c.state() == State.CHECKING_ROUTE, "pending read rejects old callback and repeat tap");
        g.query.onSuccess(ActiveRoute.INACTIVE);
        g.query.onSuccess(ActiveRoute.INACTIVE);
        confirm(c);
        expect(g.requests == 1 && c.state() == State.REQUESTING, "only one dispatch before result");
        g.result.onSuccess(new RouteResult(true, "모의 재조회 일치"));
        expect(c.state() == State.VERIFIED && c.activeRoute() == ActiveRoute.ACTIVE, "verified result conveys active route");
    }

    private static void checkParkingChange() throws Exception {
        ControlledGateway g = new ControlledGateway();
        DepartureController c = ready(g, "moving");
        confirm(c);
        g.parked = false;
        g.query.onSuccess(ActiveRoute.INACTIVE);
        expect(g.requests == 0 && c.state() == State.READY, "parking rechecked after delayed route lookup");
    }

    private static void checkReadFailures() throws Exception {
        ControlledGateway g = new ControlledGateway();
        DepartureController c = ready(g, "unreachable");
        confirm(c);
        g.query.onFailure("timeout");
        g.query.onSuccess(ActiveRoute.INACTIVE);
        expect(g.requests == 0 && c.state() == State.ROUTE_UNAVAILABLE, "failed lookup stays unsent despite late success");
        c.refreshActiveRoute(NO_CHANGE);
        g.query.onSuccess(null);
        expect(g.requests == 0 && c.activeRoute() == ActiveRoute.UNAVAILABLE, "empty lookup is unavailable");
        g.throwRead = true;
        c.refreshActiveRoute(NO_CHANGE);
        expect(g.requests == 0 && c.state() == State.ROUTE_UNAVAILABLE, "lookup exception is unavailable");
    }

    private static void checkLastMileRefusal(ActiveRoute status) throws Exception {
        ControlledGateway g = new ControlledGateway();
        DepartureController c = ready(g, "last-check-" + status);
        g.blockAtDispatch = status;
        confirm(c);
        g.query.onSuccess(ActiveRoute.INACTIVE);
        NaviGateway.Callback<RouteResult> old = g.result;
        expect(g.requests == 0 && c.activeRoute() == status
            && c.state() == (status == ActiveRoute.ACTIVE ? State.ALREADY_GUIDING : State.ROUTE_UNAVAILABLE),
            "gateway's final check blocks " + status + " before dispatch");
        g.blockAtDispatch = null;
        c.refreshActiveRoute(NO_CHANGE);
        g.query.onSuccess(ActiveRoute.INACTIVE);
        expect(g.requests == 0, "no resend after last-mile refusal " + status);
        confirm(c);
        g.query.onSuccess(ActiveRoute.INACTIVE);
        old.onSuccess(new RouteResult(true, "late"));
        expect(g.requests == 1 && c.state() == State.REQUESTING, "obsolete result cannot verify a later explicit request");
        g.result.onSuccess(new RouteResult(true, "new"));
        expect(c.state() == State.VERIFIED, "current explicit request verified " + status);
    }

    private static DepartureController ready(ControlledGateway g, String name) throws Exception {
        DepartureController c = new DepartureController(g, store(name));
        c.propose(DemoFixtures.OFFICE, PREF);
        g.query.onSuccess(ActiveRoute.INACTIVE);
        return c;
    }

    private static RoutineStore store(String name) throws Exception {
        RoutineStore store = new RoutineStore(root.resolve(name + ".properties"));
        store.setCollectionEnabled(true);
        return store;
    }
    private static void confirm(DepartureController c) { c.confirm(3, 480, DemoFixtures.NOW, NO_CHANGE); }
    private static void expect(boolean condition, String check) {
        if (!condition) throw new AssertionError(check);
        passed++;
        System.out.println("PASS " + check);
    }

    /** Delivers callbacks under test control. Counts the actual dispatch, after the final precheck. */
    private static final class ControlledGateway implements NaviGateway {
        boolean parked = true, throwRead;
        int reads, requests;
        ActiveRoute blockAtDispatch;
        Callback<ActiveRoute> query;
        Callback<RouteResult> result;
        public void queryActiveRoute(Callback<ActiveRoute> callback) {
            reads++;
            if (throwRead) throw new IllegalStateException("simulated lookup failure");
            query = callback;
        }
        public void start(Destination d, Preference p, Callback<RouteResult> callback) {
            result = callback;
            if (blockAtDispatch != null) callback.onSuccess(RouteResult.notSent(blockAtDispatch, "모의 사전 차단"));
            else requests++;
        }
        public boolean isParked() { return parked; }
        public boolean isDemo() { return true; }
        public void connect(Callback<Void> c) { c.onSuccess(null); }
        public void loadDestinations(Callback<List<Destination>> c) { throw new UnsupportedOperationException(); }
        public void estimate(Destination d, long now, Callback<Estimate> c) { throw new UnsupportedOperationException(); }
        public void close() {}
    }
}
