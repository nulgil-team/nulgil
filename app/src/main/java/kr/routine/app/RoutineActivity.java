package kr.routine.app;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import kr.routine.app.ui.VehicleScreen;
import kr.routine.app.vehicle.VehicleSession;
import kr.routine.core.Model.Destination;
import kr.routine.core.Model.Preference;

/** Driver-opened native vehicle UI. The web frontend is its visual specification only. */
public final class RoutineActivity extends Activity implements VehicleScreen.Actions {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private RoutineApplication application;
    private VehicleSession session;
    private VehicleScreen screen;
    private boolean visible, listening;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        application = (RoutineApplication) getApplication();
        screen = new VehicleScreen(this, this);
        screen.setTheme(application.theme());
        if (saved != null) screen.restoreState(saved);
        setContentView(screen.view());
        try {
            session = application.session();
            screen.render(session.snapshot());
        } catch (IOException failure) {
            showFatal("기록을 읽지 못했어요\n기존 파일을 보존하고 실행을 중단했습니다.");
        }
    }

    private void attach() {
        if (session == null) return;
        listening = true;
        session.setListener(() -> { if (visible) screen.render(session.snapshot()); });
        boolean wasConnected = session.snapshot().connected();
        session.connect(); // Idempotent; never sends a route.
        if (wasConnected) session.onForeground(); // Read only, serialized after ongoing queries.
        screen.render(session.snapshot());
    }
    @Override protected void onResume() {
        super.onResume();
        visible = true;
        attach();
        ui.post(tick);
    }
    @Override protected void onPause() {
        visible = false;
        ui.removeCallbacks(tick);
        if (session != null && listening) session.setListener(null);
        listening = false;
        super.onPause();
    }
    @Override public void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        // Application owns the trip; changing the view never closes its gateway.
        if (session != null && listening) session.setListener(null);
        listening = false;
        super.onDestroy();
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        screen.setTheme(application.theme());
        if (session != null) screen.render(session.snapshot());
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        screen.saveState(out);
    }
    @Override public void onBackPressed() {
        if (!screen.goBack()) super.onBackPressed();
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            if (session != null) session.tick();
            ui.postDelayed(this, 1000);
        }
    };

    @Override public void selectDestination(Destination value) { if (session != null) session.selectDestination(value); }
    @Override public void setPreference(Preference value) { if (session != null) session.setPreference(value); }
    @Override public void confirm() { if (session != null) session.confirm(); }
    @Override public void dismiss() { if (session != null) session.dismiss(); }
    @Override public void refresh() {
        if (session == null) return;
        try {
            if (application.reconnectIfNeeded()) { session = application.session(); attach(); }
            else session.refresh();
            if (!session.snapshot().demo() && !session.snapshot().busy()) requestVehiclePermissions();
        } catch (IOException failure) {
            showFatal("기록을 읽지 못해 연결을 다시 열지 못했어요. 기존 파일을 보존했습니다.");
        }
    }
    @Override public void recommend() { if (session != null) session.recommend(false); }
    @Override public void setConsent(boolean value) { if (session != null) session.setConsent(value); }
    @Override public void registerRoutine(Destination value, int day, int minute) {
        if (session != null) session.registerRoutine(value, day, minute);
    }
    @Override public void clearHistory() { if (session != null) session.clearHistory(); }
    @Override public void demoScenario(String key) { if (session != null) session.demoScenario(key); }
    @Override public void setTheme(String value) {
        application.setTheme(value);
        screen.setTheme(value);
    }
    @Override public void switchMode() {
        try {
            if (!application.switchMode()) return;
            session = application.session();
            attach();
            if (!session.snapshot().demo()) requestVehiclePermissions();
        } catch (IOException failure) {
            showFatal("전환할 모드의 기록을 읽지 못했어요\n기존 파일을 보존했습니다. 앱을 다시 열어 확인해 주세요.");
        }
    }
    private void requestVehiclePermissions() {
        List<String> needed = new ArrayList<>();
        for (String permission : new String[]{"android.car.permission.CAR_POWERTRAIN", "android.car.permission.CAR_SPEED"})
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) needed.add(permission);
        if (!needed.isEmpty()) requestPermissions(needed.toArray(new String[0]), 41);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        // Grant or denial affects observation only. Never reset a trip or retry confirm.
        if (code == 41 && session != null) session.onForeground();
    }
    private void showFatal(String message) {
        TextView error = new TextView(this);
        error.setText(message);
        error.setTextSize(30);
        error.setTextColor(0xfff4f5f0);
        error.setBackgroundColor(0xff262b30);
        error.setPadding(40, 40, 40, 40);
        setContentView(error);
    }
}
