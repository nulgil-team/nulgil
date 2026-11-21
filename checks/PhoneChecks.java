import java.util.ArrayList;
import java.util.List;
import kr.routine.core.*;
import kr.routine.core.PhoneLink.*;

/** 폰에서 경로 보내기, car side: link once, take a sent place at most once a minute, forget the phone. No network. */
public final class PhoneChecks {
    private static final Runnable NO_CHANGE = () -> {};
    private static final String ID = "36f5e553-59dd-4ebb-9665-9a217c0f8b2a", TOKEN = "a".repeat(48), PHONE = "b".repeat(48);
    private static int passed;

    public static void main(String[] args) {
        checkModel();
        checkPairOnceAndRestore();
        checkPollPacing();
        checkRevokedLinkEnds();
        checkUnpairForgets();
        System.out.println("RESULT " + passed + " phone assertions passed");
    }

    private static void checkModel() {
        Route r = new Route("r1", "코엑스", null, 37.51, 127.05, 1);
        expect(PhoneLink.isPhoneDestination(r.toDestination()) && "r1".equals(PhoneLink.routeId(r.toDestination()))
            && r.toDestination().subId().isEmpty(), "a sent place becomes a destination with its id and no stock POI");
        expect(!PhoneLink.isPhoneDestination(DemoFixtures.OFFICE), "stock places are not phone places");
        expect(Model.external(r.toDestination()) && !Model.external(DemoFixtures.OFFICE), "phone places are external to the stock navi");
        expect(throwsArg(() -> new Link("not-a-uuid", TOKEN)) && throwsArg(() -> new Link(ID, "short")), "malformed links are rejected");
    }

    private static void checkPairOnceAndRestore() {
        Fake service = new Fake();
        PhoneLink.MemoryStore store = new PhoneLink.MemoryStore();
        PhoneInbox inbox = new PhoneInbox(service, store);
        inbox.pair(NO_CHANGE); inbox.pair(NO_CHANGE);
        expect(service.created == 1 && inbox.linked() && inbox.pairUrl().contains("k=" + PHONE), "pairing once shows the QR with the phone secret");
        inbox.finishPairing(NO_CHANGE);
        expect(inbox.linked() && inbox.pairUrl().isEmpty(), "leaving the screen hides the phone secret; the link stays");
        expect(new PhoneInbox(service, store).linked(), "a restarted app keeps the link");
    }

    private static void checkPollPacing() {
        Fake service = new Fake();
        PhoneInbox inbox = linked(service);
        List<Route> got = new ArrayList<>();
        inbox.poll(0, true, got::add, NO_CHANGE);
        inbox.poll(1000, false, got::add, NO_CHANGE);
        expect(service.takes == 1, "reads wait " + PhoneInbox.POLL_MS + " ms between checks");
        service.waiting = new Route("r1", "코엑스", "", 37.51, 127.05, 1);
        inbox.poll(PhoneInbox.POLL_MS, false, got::add, NO_CHANGE);
        inbox.poll(PhoneInbox.POLL_MS + 1, true, got::add, NO_CHANGE);
        expect(service.takes == 3 && got.size() == 1 && got.get(0).name().equals("코엑스"), "a sent place is handed over once");
    }

    private static void checkRevokedLinkEnds() {
        Fake service = new Fake(); service.fail = "NOT_ALLOWED";
        PhoneLink.MemoryStore store = new PhoneLink.MemoryStore();
        PhoneInbox inbox = new PhoneInbox(service, store);
        inbox.pair(NO_CHANGE); inbox.finishPairing(NO_CHANGE);
        inbox.poll(0, true, r -> {}, NO_CHANGE);
        expect(!inbox.linked() && store.load() == null && inbox.message().contains("끊겼어요"), "a link the server refuses is forgotten");
    }

    private static void checkUnpairForgets() {
        Fake service = new Fake();
        PhoneLink.MemoryStore store = new PhoneLink.MemoryStore();
        PhoneInbox inbox = new PhoneInbox(service, store);
        inbox.pair(NO_CHANGE);
        inbox.unpair(NO_CHANGE);
        expect(!inbox.linked() && store.load() == null && service.revoked == 1, "unpairing tells the server and forgets the link");
    }

    private static PhoneInbox linked(Fake service) {
        PhoneInbox inbox = new PhoneInbox(service, new PhoneLink.MemoryStore());
        inbox.pair(NO_CHANGE); inbox.finishPairing(NO_CHANGE);
        return inbox;
    }

    private static final class Fake implements Service {
        int created, takes, revoked; Route waiting; String fail;
        public void createLink(NaviGateway.Callback<Pairing> c) { created++; c.onSuccess(new Pairing(new Link(ID, TOKEN), PHONE)); }
        public void take(Link l, NaviGateway.Callback<Route> c) {
            takes++;
            if (fail != null) { c.onFailure(fail); return; }
            Route r = waiting; waiting = null; c.onSuccess(r);
        }
        public void revoke(Link l, NaviGateway.Callback<Void> c) { revoked++; c.onSuccess(null); }
        public String pairUrl(Pairing p) { return "https://x/phone.html?l=" + p.link().id() + "&k=" + p.phoneToken(); }
    }

    private static boolean throwsArg(Runnable action) {
        try { action.run(); return false; } catch (IllegalArgumentException expected) { return true; }
    }
    private static void expect(boolean condition, String label) {
        if (!condition) { System.out.println("FAIL " + label); System.exit(1); }
        passed++; System.out.println("PASS " + label);
    }
}
