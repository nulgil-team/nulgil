package kr.routine.app;

import android.app.Application;
import android.content.SharedPreferences;
import java.io.IOException;
import java.time.Clock;
import java.time.ZoneId;
import kr.routine.app.invite.PrefsTripStore;
import kr.routine.app.invite.SupabaseInviteService;
import kr.routine.app.phone.PrefsPhoneLinkStore;
import kr.routine.app.phone.SupabasePhoneService;
import kr.routine.app.platform.PleosNaviGateway;
import kr.routine.app.vehicle.VehicleSession;
import kr.routine.core.DemoInviteService;
import kr.routine.core.DemoNaviGateway;
import kr.routine.core.InviteInbox;
import kr.routine.core.InviteService;
import kr.routine.core.PhoneInbox;
import kr.routine.core.NaviGateway;
import kr.routine.core.RoutineStore;

/** A view recreation must not replace the trip controller or release its request latch. */
public final class RoutineApplication extends Application {
    private VehicleSession session;
    private PleosNaviGateway platform;
    private boolean demo;
    private SupabaseInviteService inviteService;
    private SupabasePhoneService phoneService;

    public VehicleSession session() throws IOException {
        if (session == null) {
            demo = preferences().getBoolean("demo", true);
            session = createSession(demo);
        }
        return session;
    }

    /** Only an explicit settings action can change transport, never during an issued trip. */
    public boolean switchMode() throws IOException {
        if (session != null && !session.snapshot().canSwitchMode()) return false;
        boolean nextDemo = !demo;
        VehicleSession next = createSession(nextDemo);
        if (session != null) { session.setListener(null); session.close(); }
        session = next;
        demo = nextDemo;
        preferences().edit().putBoolean("demo", demo).apply();
        return true;
    }

    private VehicleSession createSession(boolean demoMode) throws IOException {
        // Read first: an unreadable store is never replaced with empty data.
        RoutineStore store = new RoutineStore(getFilesDir().toPath()
            .resolve(demoMode ? "demo-routine.data" : "live-routine.data"));
        NaviGateway gateway = demoMode ? new DemoNaviGateway() : new PleosNaviGateway(this);
        platform = gateway instanceof PleosNaviGateway live ? live : null;
        return new VehicleSession(gateway, store, demoMode, Clock.systemUTC(), ZoneId.of("Asia/Seoul"), invites(demoMode), phone(demoMode));
    }

    /** Friend suggestions: invented and offline in 시연 mode, the invite server in 연결 시험 mode. */
    private InviteInbox invites(boolean demoMode) {
        long now = System.currentTimeMillis();
        if (demoMode) return new InviteInbox(new DemoInviteService(System::currentTimeMillis), new InviteService.MemoryTripStore(), now);
        if (inviteService == null) inviteService = SupabaseInviteService.fromResources(this);
        return inviteService == null ? null : new InviteInbox(inviteService, new PrefsTripStore(this, "invite_trip_live"), now);
    }

    /** 폰에서 경로 보내기: only in 연결 시험 mode with a configured phone page. */
    private PhoneInbox phone(boolean demoMode) {
        if (demoMode) return null;
        if (phoneService == null) phoneService = SupabasePhoneService.fromResources(this);
        return phoneService == null ? null : new PhoneInbox(phoneService, new PrefsPhoneLinkStore(this));
    }

    /** Explicit reconnect before dispatch only; the SDK's persistent journal remains authoritative. */
    public boolean reconnectIfNeeded() throws IOException {
        // An unconfirmed trip locks the session, but reconnecting is still safe: the persisted journal makes the new
        // gateway verify (or, when the navi is idle, release) the earlier request before anything can be sent again.
        if (platform == null || !platform.needsReconnect() || session == null
                || session.snapshot().busy() || session.snapshot().collectionBlocked()) return false;
        VehicleSession next = createSession(false);
        session.setListener(null);
        session.close();
        session = next;
        return true;
    }

    public String theme() { return preferences().getString("theme", "system"); }
    public void setTheme(String value) {
        if (!value.equals("system") && !value.equals("day") && !value.equals("night"))
            throw new IllegalArgumentException("Unknown theme");
        preferences().edit().putString("theme", value).apply();
    }
    private SharedPreferences preferences() { return getSharedPreferences("routine_ui", MODE_PRIVATE); }
}
