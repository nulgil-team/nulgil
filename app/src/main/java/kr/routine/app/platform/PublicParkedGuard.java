package kr.routine.app.platform;

import android.car.Car;
import android.car.VehicleGear;
import android.car.VehiclePropertyIds;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Public AAOS APIs only. Permission denial and unsupported or stale values are blocking. */
final class PublicParkedGuard implements AutoCloseable {
    private static final long FRESH_NANOS = TimeUnit.SECONDS.toNanos(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService reader = Executors.newSingleThreadScheduledExecutor();
    private volatile Car car;
    private volatile CarPropertyManager properties;
    private volatile CarUxRestrictionsManager restrictions;
    private volatile boolean closed;
    private volatile boolean allowed;
    private volatile long checkedAt;
    private volatile String reason = "차량 정차 상태를 확인하고 있습니다.";

    PublicParkedGuard(Context context) {
        try {
            car = Car.createCar(context.getApplicationContext(), main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
                (service, ready) -> {
                    if (closed) return;
                    allowed = false;
                    if (!ready) {
                        properties = null; restrictions = null;
                        reason = "차량 서비스가 연결되지 않았습니다.";
                        return;
                    }
                    try {
                        properties = (CarPropertyManager) service.getCarManager(Car.PROPERTY_SERVICE);
                        restrictions = (CarUxRestrictionsManager) service.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
                        if (restrictions != null) restrictions.registerListener(value -> {
                            if (value == null || value.isRequiresDistractionOptimization()) {
                                allowed = false;
                                reason = "차량의 주행 중 조작 제한이 적용돼 있습니다.";
                            }
                        });
                    } catch (RuntimeException ex) {
                        reason = "차량 상태 접근 실패: " + ex.getClass().getSimpleName();
                    }
                });
            reader.scheduleWithFixedDelay(this::sample, 0, 500, TimeUnit.MILLISECONDS);
        } catch (RuntimeException | LinkageError ex) {
            report("공개 차량 상태 API 사용 불가: " + ex.getClass().getSimpleName(), String.valueOf(ex));
        }
    }

    private void sample() {
        if (closed) return;
        Car service = car;
        CarPropertyManager manager = properties;
        CarUxRestrictionsManager ux = restrictions;
        if (service == null || manager == null || ux == null || !service.isConnected()) {
            allowed = false;
            return;
        }
        final long startedAt = SystemClock.elapsedRealtimeNanos();
        try {
            // These reads run off the UI thread. GEAR_SELECTION is an on-change property:
            // its event timestamp may legitimately predate this fresh synchronous read.
            CarPropertyValue<Integer> gear = manager.getProperty(Integer.class, VehiclePropertyIds.GEAR_SELECTION, 0);
            CarPropertyValue<Float> speed = manager.getProperty(Float.class, VehiclePropertyIds.PERF_VEHICLE_SPEED, 0);
            CarUxRestrictions restriction = ux.getCurrentCarUxRestrictions();
            long now = SystemClock.elapsedRealtimeNanos();
            boolean current = now - startedAt <= FRESH_NANOS && available(gear) && available(speed)
                && gear.getTimestamp() > 0 && gear.getTimestamp() <= now
                && speed.getTimestamp() > 0 && now >= speed.getTimestamp()
                && now - speed.getTimestamp() <= FRESH_NANOS;
            boolean parked = current && gear.getValue() == VehicleGear.GEAR_PARK
                && Float.isFinite(speed.getValue()) && speed.getValue() == 0.0f;
            boolean unrestricted = restriction != null && !restriction.isRequiresDistractionOptimization();
            checkedAt = now;
            allowed = !closed && parked && unrestricted;
            report(allowed ? "기어 P · 속도 0 · 조작 제한 해제 확인"
                : "정차 상태 미확인: 기어 P, 최신 속도 0, 조작 제한 해제가 모두 필요합니다.",
                "gear=" + (gear == null ? null : gear.getValue()) + " speed=" + (speed == null ? null : speed.getValue())
                + " restricted=" + (restriction == null ? null : restriction.isRequiresDistractionOptimization()));
        } catch (RuntimeException ex) {
            allowed = false;
            report("정차 확인 권한·차량 속성 오류: " + ex.getClass().getSimpleName(), String.valueOf(ex));
        }
    }

    // Logs only on change, so the parked decision can be checked on an emulator or vehicle without the navi.
    private void report(String next, String detail) {
        if (!next.equals(reason)) Log.i("RoutineParked", next + " (" + detail + ")");
        reason = next;
    }

    private static boolean available(CarPropertyValue<?> value) {
        return value != null && value.getStatus() == CarPropertyValue.STATUS_AVAILABLE && value.getValue() != null;
    }

    boolean isParked() {
        long age = SystemClock.elapsedRealtimeNanos() - checkedAt;
        return !closed && allowed && age >= 0 && age <= FRESH_NANOS;
    }

    String reason() { return isParked() ? reason : "정차 상태를 확인할 수 없어 경로 변경을 차단합니다. " + reason; }

    @Override public void close() {
        closed = true;
        allowed = false;
        reader.shutdownNow();
        try { if (restrictions != null) restrictions.unregisterListener(); } catch (RuntimeException ignored) { }
        try { if (car != null) car.disconnect(); } catch (RuntimeException ignored) { }
    }
}
