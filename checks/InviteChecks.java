import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import kr.routine.core.*;
import kr.routine.core.Invite.*;
import kr.routine.core.Model.*;

/** Friend suggestions (친구 들를 곳): invite lifecycle, polling, status reporting. No network, no navi. */
public final class InviteChecks {
    private static final Runnable NO_CHANGE = () -> {};
    private static final AtomicLong NOW = new AtomicLong(1_800_000_000_000L);
    private static int passed;

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of(args[0]));
        checkModel();
        checkOpenOnceAndRestore();
        checkExpiredTripDropped();
        checkPollThrottle();
        checkStatusOrderAndLocalEcho();
        checkDismissOnlyPending();
        checkWriteFailureKeepsDecision();
        checkServerClosedEndsInvite();
        checkCloseIgnoresLateCallbacks();
        checkInviteStopThroughDeparture();
        System.out.println("RESULT " + passed + " invite assertions passed");
    }

    private static void checkModel() {
        Suggestion s = new Suggestion("abc", "민지", "서울숲", "", 37.54, 127.04, null, Status.PENDING, 1);
        Destination stop = s.toStop();
        expect(Invite.isInviteStop(stop) && "abc".equals(Invite.suggestionId(stop)), "a suggestion becomes an external stop with its id");
        expect(stop.subId().isEmpty() && stop.address().contains("민지"), "external stop has no sub id and a readable address");
        expect(!Invite.isInviteStop(DemoFixtures.OFFICE) && Invite.suggestionId(DemoFixtures.OFFICE) == null, "stock places are not invite stops");
        expect(Invite.after(StopState.ADDED) == Status.ADDED && Invite.after(StopState.UNCONFIRMED) == Status.UNCONFIRMED
            && Invite.after(StopState.NOT_SENT) == Status.PENDING && Invite.after(StopState.REQUESTING) == null,
            "stop results map to friend statuses; not sent keeps the friend waiting");
        expect(throwsArg(() -> new Trip("abc123", "t", 1)), "lowercase or ambiguous codes are rejected");
    }

    private static void checkOpenOnceAndRestore() {
        DemoInviteService service = new DemoInviteService(NOW::get);
        InviteService.MemoryTripStore store = new InviteService.MemoryTripStore();
        InviteInbox inbox = new InviteInbox(service, store, NOW.get());
        inbox.open(NOW.get(), NO_CHANGE);
        inbox.open(NOW.get(), NO_CHANGE);
        expect(service.created() == 1 && inbox.isOpen(NOW.get()), "opening twice creates one invite");
        expect(store.load() != null && store.load().code().equals(DemoInviteService.CODE), "the invite is kept for restarts");
        InviteInbox restored = new InviteInbox(service, store, NOW.get() + 1000);
        expect(restored.isOpen(NOW.get() + 1000) && restored.trip().equals(inbox.trip()), "a restarted app keeps the same code");
    }

    private static void checkExpiredTripDropped() {
        InviteService.MemoryTripStore store = new InviteService.MemoryTripStore();
        store.save(new Trip("ABC234", "token", NOW.get() - 1));
        InviteInbox inbox = new InviteInbox(new DemoInviteService(NOW::get), store, NOW.get());
        expect(!inbox.isOpen(NOW.get()) && store.load() == null, "an expired invite is not restored");
    }

    private static void checkPollThrottle() {
        CountingService service = new CountingService(new DemoInviteService(NOW::get));
        InviteInbox inbox = opened(service);
        long t = NOW.get();
        inbox.poll(t, true, NO_CHANGE);
        inbox.poll(t + 1000, false, NO_CHANGE);
        expect(service.lists == 1, "polling waits " + InviteInbox.POLL_MS + " ms between reads");
        inbox.poll(t + InviteInbox.POLL_MS, false, NO_CHANGE);
        expect(service.lists == 2 && inbox.pending().size() == 2, "the next read happens after the interval and shows pending places");
        int[] redraws = {0};
        inbox.poll(t + InviteInbox.POLL_MS + 1, true, () -> redraws[0]++);
        expect(service.lists == 3, "a forced read (driver pressed 새로 확인) skips the wait");
        expect(redraws[0] == 0, "a read with nothing new does not redraw the screen");
    }

    private static void checkStatusOrderAndLocalEcho() {
        DemoInviteService service = new DemoInviteService(NOW::get);
        InviteInbox inbox = opened(service);
        inbox.poll(NOW.get(), true, NO_CHANGE);
        inbox.setStatus("demo-1", Status.REQUESTED, NO_CHANGE);
        expect(inbox.pending().size() == 1 && inbox.status(inbox.find("demo-1")) == Status.REQUESTED,
            "the decision shows at once, before the server echoes it");
        inbox.setStatus("demo-1", Status.ADDED, NO_CHANGE);
        expect(service.writes().equals(List.of("demo-1=REQUESTED", "demo-1=ADDED")), "status writes reach the server in order");
        inbox.poll(NOW.get() + InviteInbox.POLL_MS, false, NO_CHANGE);
        expect(inbox.handled().size() == 1 && inbox.status(inbox.handled().get(0)) == Status.ADDED, "decided places move to the handled list");
        inbox.setStatus("missing", Status.ADDED, NO_CHANGE);
        expect(service.writes().size() == 2, "unknown suggestions are never written");
    }

    private static void checkDismissOnlyPending() {
        DemoInviteService service = new DemoInviteService(NOW::get);
        InviteInbox inbox = opened(service);
        inbox.poll(NOW.get(), true, NO_CHANGE);
        inbox.setStatus("demo-1", Status.REQUESTED, NO_CHANGE);
        inbox.dismiss("demo-1", NO_CHANGE);
        inbox.dismiss("demo-2", NO_CHANGE);
        expect(service.writes().equals(List.of("demo-1=REQUESTED", "demo-2=DISMISSED")), "only waiting places can be passed over");
    }

    private static void checkWriteFailureKeepsDecision() {
        DemoInviteService service = new DemoInviteService(NOW::get);
        InviteInbox inbox = opened(service);
        inbox.poll(NOW.get(), true, NO_CHANGE);
        service.setFailNext(true);
        inbox.setStatus("demo-2", Status.DISMISSED, NO_CHANGE);
        expect(inbox.status(inbox.find("demo-2")) == Status.DISMISSED && inbox.message().contains("알리지 못했어요"),
            "a failed write keeps the car's decision and says the friend was not told");
        inbox.setStatus("demo-1", Status.REQUESTED, NO_CHANGE);
        expect(service.writes().equals(List.of("demo-1=REQUESTED")), "later writes still go out after a failure");
    }

    private static void checkServerClosedEndsInvite() {
        DemoInviteService service = new DemoInviteService(NOW::get);
        InviteService.MemoryTripStore store = new InviteService.MemoryTripStore();
        InviteInbox inbox = new InviteInbox(service, store, NOW.get());
        inbox.open(NOW.get(), NO_CHANGE);
        service.closeTrip(inbox.trip(), new NaviGateway.Callback<>() { public void onSuccess(Void v) {} public void onFailure(String m) {} });
        inbox.poll(NOW.get(), true, NO_CHANGE);
        expect(!inbox.isOpen(NOW.get()) && store.load() == null && inbox.message().contains("끝났어요"), "a closed invite ends on the car too");
        InviteInbox later = opened(new DemoInviteService(NOW::get));
        later.poll(NOW.get() + 13L * 60 * 60_000, true, NO_CHANGE);
        expect(!later.isOpen(NOW.get()) && later.message().contains("시간"), "an invite past its time ends without a server call");
    }

    private static void checkCloseIgnoresLateCallbacks() {
        DeferredService service = new DeferredService();
        InviteInbox inbox = new InviteInbox(service, new InviteService.MemoryTripStore(), NOW.get());
        inbox.open(NOW.get(), NO_CHANGE);
        service.pendingTrip.onSuccess(new Trip("ABC234", "token", NOW.get() + 60_000));
        inbox.poll(NOW.get(), true, NO_CHANGE);
        inbox.close(NO_CHANGE);
        service.pendingList.onSuccess(List.of(new Suggestion("x", "a", "b", "", 37, 127, "", Status.PENDING, 1)));
        expect(inbox.pending().isEmpty() && !inbox.isOpen(NOW.get()), "a late answer after closing changes nothing");
    }

    private static void checkInviteStopThroughDeparture() throws Exception {
        DemoNaviGateway g = new DemoNaviGateway();
        g.connect(new NaviGateway.Callback<>() { public void onSuccess(Void v) {} public void onFailure(String m) {} });
        g.setActiveRoute(ActiveRoute.ACTIVE);
        Path dir = Files.createTempDirectory("invite-departure");
        DepartureController c = new DepartureController(g, new RoutineStore(dir.resolve("routine.data")));
        c.refreshActiveRoute(NO_CHANGE);
        Destination stop = new Suggestion("s1", "민지", "서울숲", "서울 성동구", 37.5444, 127.0374, "", Status.PENDING, 1).toStop();
        c.addStop(stop, NO_CHANGE);
        c.addStop(stop, NO_CHANGE);
        expect(c.stopState() == StopState.NOT_SENT && g.stopRequests() == 1 && g.routeStops().equals(List.of(stop)),
            "a friend's place is added once while guiding and never sent twice");
    }

    private static InviteInbox opened(InviteService service) {
        InviteInbox inbox = new InviteInbox(service, new InviteService.MemoryTripStore(), NOW.get());
        inbox.open(NOW.get(), NO_CHANGE);
        return inbox;
    }

    /** Counts list calls, delegating everything. */
    private static final class CountingService implements InviteService {
        final InviteService inner; int lists;
        CountingService(InviteService inner) { this.inner = inner; }
        public void createTrip(NaviGateway.Callback<Trip> c) { inner.createTrip(c); }
        public void listSuggestions(Trip t, NaviGateway.Callback<List<Suggestion>> c) { lists++; inner.listSuggestions(t, c); }
        public void setStatus(Trip t, String id, Status s, NaviGateway.Callback<Void> c) { inner.setStatus(t, id, s, c); }
        public void closeTrip(Trip t, NaviGateway.Callback<Void> c) { inner.closeTrip(t, c); }
        public String inviteUrl(Trip t) { return ""; }
    }

    /** Holds callbacks so a check can answer late. */
    private static final class DeferredService implements InviteService {
        NaviGateway.Callback<Trip> pendingTrip; NaviGateway.Callback<List<Suggestion>> pendingList;
        public void createTrip(NaviGateway.Callback<Trip> c) { pendingTrip = c; }
        public void listSuggestions(Trip t, NaviGateway.Callback<List<Suggestion>> c) { pendingList = c; }
        public void setStatus(Trip t, String id, Status s, NaviGateway.Callback<Void> c) { c.onSuccess(null); }
        public void closeTrip(Trip t, NaviGateway.Callback<Void> c) { c.onSuccess(null); }
        public String inviteUrl(Trip t) { return ""; }
    }

    private static boolean throwsArg(Runnable action) {
        try { action.run(); return false; } catch (IllegalArgumentException expected) { return true; }
    }
    private static void expect(boolean condition, String label) {
        if (!condition) { System.out.println("FAIL " + label); System.exit(1); }
        passed++; System.out.println("PASS " + label);
    }
}
