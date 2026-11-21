import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import kr.routine.core.*;
import kr.routine.core.Model.*;
import kr.routine.core.DepartureController.State;

/** Stops (들를 곳): planned with the destination, or added once while guiding. No retries, ever. */
public final class StopChecks {
    private static final Runnable NO_CHANGE = () -> {};
    private static final Preference PREF = Preference.RECOMMENDED;
    private static final Destination HOME = DemoFixtures.OFFICE;
    private static final Destination A = DemoFixtures.OTHER;
    private static final Destination B = new Destination("stop-b", "빵집", "경기도 성남시 분당구 판교역로 1", 37.3947, 127.1112, "");
    private static final Destination C = new Destination("stop-c", "꽃집", "서울시 강남구 테헤란로 2", 37.5003, 127.0365, "");
    private static Path root;
    private static int passed;

    public static void main(String[] args) throws Exception {
        root = Files.createDirectories(Path.of(args[0]));
        checkRules();
        checkDemoStartWithStops();
        checkStopsLockedAfterConfirm();
        checkStopChangeDropsPendingConfirm();
        checkProposeRemovesDestinationFromStops();
        checkAddStopNeedsGuidance();
        checkAddStopVerified();
        checkSamePlaceNeverResent();
        checkUnconfirmedIsNotRetried();
        checkNotSentMayBeRetried();
        checkNotParkedSendsNothing();
        checkDoubleTapSendsOnce();
        checkLimitAfterTwoStops();
        checkDefaultGatewayRefusesStops();
        System.out.println("RESULT " + passed + " stop assertions passed");
    }

    private static void checkRules() {
        expect(Model.checkStops(HOME, List.of(A, B)).equals(List.of(A, B)), "two distinct stops keep visiting order");
        expect(throwsArg(() -> Model.checkStops(HOME, List.of(A, B, C))), "three stops are rejected");
        expect(throwsArg(() -> Model.checkStops(HOME, List.of(A, A))), "duplicate stop is rejected");
        expect(throwsArg(() -> Model.checkStops(HOME, List.of(HOME))), "destination cannot be a stop");
        Destination moved = new Destination(A.id(), A.name(), A.address(), A.latitude() + 0.001, A.longitude(), A.subId());
        expect(Model.samePlace(A, moved), "same POI id and subId is the same place despite snapped coordinates");
        expect(Model.Point.of(0, 0) == null && Model.Point.of(37.5, 127.0) != null, "the navi's (0, 0) means no position");
    }

    private static void checkDemoStartWithStops() throws Exception {
        DemoNaviGateway g = demo();
        DepartureController c = new DepartureController(g, store("demo-start"));
        c.propose(HOME, PREF);
        expect(c.setStops(List.of(A)), "stops can be planned before confirm");
        confirm(c);
        expect(c.state() == State.VERIFIED && g.startCount() == 1 && g.routeStops().equals(List.of(A)),
            "confirm sends the planned stop with the destination once");
        expect(c.stops().equals(List.of(A)), "submitted stops stay visible after confirm");
    }

    private static void checkStopsLockedAfterConfirm() throws Exception {
        DemoNaviGateway g = demo();
        DepartureController c = new DepartureController(g, store("locked"));
        c.propose(HOME, PREF);
        confirm(c);
        expect(!c.setStops(List.of(A)) && c.stops().isEmpty(), "stops cannot change after a request was issued");
    }

    private static void checkStopChangeDropsPendingConfirm() throws Exception {
        Controlled g = new Controlled();
        DepartureController c = new DepartureController(g, store("pending"));
        c.refreshActiveRoute(NO_CHANGE); g.query.onSuccess(ActiveRoute.INACTIVE);
        c.propose(HOME, PREF);
        confirm(c);                      // confirmation re-reads the route first
        c.setStops(List.of(A));          // the driver changes the plan while that read is pending
        g.query.onSuccess(ActiveRoute.INACTIVE);
        expect(g.starts.isEmpty() && c.state() == State.READY, "changing stops drops a confirmation that was still checking");
        confirm(c); g.query.onSuccess(ActiveRoute.INACTIVE);
        expect(g.starts.size() == 1 && g.starts.get(0).equals(List.of(A)), "a fresh confirmation sends the new stops");
    }

    private static void checkProposeRemovesDestinationFromStops() throws Exception {
        DepartureController c = new DepartureController(demo(), store("propose"));
        c.propose(HOME, PREF);
        c.setStops(List.of(A, B));
        c.propose(A, PREF);
        expect(c.stops().equals(List.of(B)), "choosing a stop as the destination removes it from the stops");
    }

    private static void checkAddStopNeedsGuidance() throws Exception {
        DemoNaviGateway g = demo();
        DepartureController c = new DepartureController(g, store("no-guidance"));
        c.refreshActiveRoute(NO_CHANGE);
        c.propose(HOME, PREF);
        expect(!c.canAddStop(), "no stop can be added before guidance");
        c.addStop(A, NO_CHANGE);
        expect(g.stopRequests() == 0 && c.stopState() == StopState.NONE, "addStop before guidance sends nothing");
    }

    private static void checkAddStopVerified() throws Exception {
        DemoNaviGateway g = guiding("added");
        DepartureController c = guidingController(g, "added");
        expect(c.canAddStop(), "a stop can be added while guiding");
        c.addStop(A, NO_CHANGE);
        expect(c.stopState() == StopState.ADDED && c.addedStops() == 1 && g.stopRequests() == 1, "verified stop is ADDED once");
    }

    private static void checkSamePlaceNeverResent() throws Exception {
        DemoNaviGateway g = guiding("same");
        DepartureController c = guidingController(g, "same");
        c.addStop(A, NO_CHANGE);
        c.addStop(A, NO_CHANGE);
        expect(g.stopRequests() == 1 && c.stopState() == StopState.NOT_SENT, "the same place is never sent twice");
    }

    private static void checkUnconfirmedIsNotRetried() throws Exception {
        DemoNaviGateway g = guiding("unconfirmed");
        DepartureController c = guidingController(g, "unconfirmed");
        g.setFailNext(true);
        c.addStop(A, NO_CHANGE);
        expect(c.stopState() == StopState.UNCONFIRMED && g.stopRequests() == 1, "lost answer after sending is UNCONFIRMED");
        c.addStop(A, NO_CHANGE);
        expect(g.stopRequests() == 1, "an unconfirmed stop is not retried");
        c.addStop(B, NO_CHANGE);
        expect(g.stopRequests() == 2 && c.stopState() == StopState.ADDED, "another place can still be added");
    }

    private static void checkNotSentMayBeRetried() throws Exception {
        Controlled g = new Controlled();
        DepartureController c = new DepartureController(g, store("not-sent"));
        startVerified(c, g);
        c.addStop(A, NO_CHANGE);
        g.stop.onSuccess(RouteResult.notSent(ActiveRoute.ACTIVE, "이미 들를 곳이 2곳이에요."));
        expect(c.stopState() == StopState.NOT_SENT && c.stopMessage().contains("2곳"), "gateway refusal is NOT_SENT with its reason");
        c.addStop(A, NO_CHANGE);
        expect(g.stopCalls == 2, "a place that was never sent may be tried again");
    }

    private static void checkNotParkedSendsNothing() throws Exception {
        DemoNaviGateway g = guiding("moving");
        DepartureController c = guidingController(g, "moving");
        g.setParked(false);
        c.addStop(A, NO_CHANGE);
        expect(g.stopRequests() == 0 && c.stopState() == StopState.NOT_SENT, "not parked sends nothing");
    }

    private static void checkDoubleTapSendsOnce() throws Exception {
        Controlled g = new Controlled();
        DepartureController c = new DepartureController(g, store("double"));
        startVerified(c, g);
        c.addStop(A, NO_CHANGE);
        c.addStop(B, NO_CHANGE);
        expect(g.stopCalls == 1 && c.stopState() == StopState.REQUESTING && !c.canAddStop(), "one stop request in flight at a time");
        g.stop.onSuccess(new RouteResult(true, "ok"));
        g.stop.onSuccess(new RouteResult(true, "late duplicate"));
        expect(c.addedStops() == 1, "a duplicate late callback is ignored");
    }

    private static void checkLimitAfterTwoStops() throws Exception {
        DemoNaviGateway g = demo();
        DepartureController c = new DepartureController(g, store("limit"));
        c.propose(HOME, PREF);
        c.setStops(List.of(A, B));
        confirm(c);
        expect(c.state() == State.VERIFIED && !c.canAddStop(), "two stops on the route leave no room to add");
        c.addStop(C, NO_CHANGE);
        expect(g.stopRequests() == 0, "limit blocks sending");
    }

    private static void checkDefaultGatewayRefusesStops() throws Exception {
        CoreChecks.TrackingGateway g = new CoreChecks.TrackingGateway();
        DepartureController c = new DepartureController(g, store("default"));
        c.refreshActiveRoute(NO_CHANGE);
        c.propose(HOME, PREF);
        c.setStops(List.of(A));
        confirm(c);
        expect(g.calls == 0 && c.state() == State.READY && c.message().contains("들를 곳"),
            "a gateway without stop support reports notSent instead of dropping stops");
    }

    // --- helpers ---

    private static DemoNaviGateway demo() {
        DemoNaviGateway g = new DemoNaviGateway();
        g.connect(CoreChecks.noFailure());
        return g;
    }

    private static DemoNaviGateway guiding(String name) { return demo(); }

    private static DepartureController guidingController(DemoNaviGateway g, String name) throws Exception {
        DepartureController c = new DepartureController(g, store(name));
        c.propose(HOME, PREF);
        confirm(c);
        if (c.state() != State.VERIFIED) throw new AssertionError("setup: guidance not verified");
        return c;
    }

    private static void startVerified(DepartureController c, Controlled g) {
        c.refreshActiveRoute(NO_CHANGE); g.query.onSuccess(ActiveRoute.INACTIVE);
        c.propose(HOME, PREF);
        confirm(c); g.query.onSuccess(ActiveRoute.INACTIVE);
        g.route.onSuccess(new RouteResult(true, "verified"));
        if (c.state() != State.VERIFIED) throw new AssertionError("setup: guidance not verified");
    }

    private static RoutineStore store(String name) throws Exception {
        RoutineStore store = new RoutineStore(root.resolve(name + ".properties"));
        store.setCollectionEnabled(true);
        return store;
    }

    private static void confirm(DepartureController c) { c.confirm(3, 480, DemoFixtures.NOW, NO_CHANGE); }

    private static boolean throwsArg(Runnable action) {
        try { action.run(); return false; } catch (IllegalArgumentException expected) { return true; }
    }

    private static void expect(boolean condition, String check) {
        if (!condition) throw new AssertionError(check);
        passed++;
        System.out.println("PASS " + check);
    }

    /** Callbacks are held until the check answers them, to reproduce in-flight and late results. */
    private static final class Controlled implements NaviGateway {
        Callback<ActiveRoute> query;
        Callback<RouteResult> route, stop;
        final List<List<Destination>> starts = new ArrayList<>();
        int stopCalls;
        public void connect(Callback<Void> c) { c.onSuccess(null); }
        public void loadDestinations(Callback<List<Destination>> c) { c.onSuccess(List.of(HOME, A, B)); }
        public void estimate(Destination d, long now, Callback<Estimate> c) { c.onFailure("not used"); }
        public void queryActiveRoute(Callback<ActiveRoute> c) { query = c; }
        public void start(Destination d, Preference p, Callback<RouteResult> c) { start(d, List.of(), p, c); }
        @Override public void start(Destination d, List<Destination> s, Preference p, Callback<RouteResult> c) { starts.add(s); route = c; }
        @Override public void addStop(Destination s, Callback<RouteResult> c) { stopCalls++; stop = c; }
        public boolean isDemo() { return false; }
        public boolean isParked() { return true; }
        public void close() {}
    }
}
