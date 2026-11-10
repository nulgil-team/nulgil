package kr.routine.app;

import android.app.Application;
import android.content.SharedPreferences;
import java.io.IOException;
import java.time.Clock;
import java.time.ZoneId;
import kr.routine.app.platform.PleosNaviGateway;
import kr.routine.app.vehicle.VehicleSession;
import kr.routine.core.DemoNaviGateway;
import kr.routine.core.NaviGateway;
import kr.routine.core.RoutineStore;

/** A view recreation must not replace the trip controller or release its request latch. */
public final class RoutineApplication extends Application {
    private VehicleSession session;
    private PleosNaviGateway platform;
    private boolean demo;

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
        return new VehicleSession(gateway, store, demoMode, Clock.systemUTC(), ZoneId.of("Asia/Seoul"));
    }

    /** Explicit reconnect before dispatch only; the SDK's persistent journal remains authoritative. */
    public boolean reconnectIfNeeded() throws IOException {
        if (platform == null || !platform.needsReconnect() || session == null
                || !session.snapshot().canSwitchMode()) return false;
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
