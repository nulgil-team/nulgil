package kr.routine.app.platform;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import ai.pleos.playground.navi.constants.NaviErrorCode;
import ai.pleos.playground.navi.constants.RouteOption;
import ai.pleos.playground.navi.data.BookmarkInfo;
import ai.pleos.playground.navi.data.DestinationInfo;
import ai.pleos.playground.navi.data.RecentDestinationInfo;
import ai.pleos.playground.navi.data.RouteInfo;
import ai.pleos.playground.navi.data.RouteStartInfo;
import ai.pleos.playground.navi.data.RouteStateInfo;
import ai.pleos.playground.navi.data.TripInfo;
import ai.pleos.playground.navi.data.TripRequest;
import ai.pleos.playground.navi.helper.NaviHelper;
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kr.routine.core.Model.*;
import kr.routine.core.NaviGateway;
import kotlin.Unit;

/** Single-flight SDK adapter. No route cancellation, hidden APIs, automatic retry, or guessed ETA units. */
public final class PleosNaviGateway implements NaviGateway, NaviHelperEventListener {
    private enum Phase { IDLE, CONNECT, PLACES, ESTIMATE, QUERY_ROUTE, PRECHECK, ROUTING, READBACK, RECOVERY, CLOSED }
    private record PlaceMetadata(String provider, Double displayLatitude, Double displayLongitude) {}
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences journal;
    private final PublicParkedGuard parked;
    private final Map<Destination, PlaceMetadata> knownPlaces = new LinkedHashMap<>();
    private final Set<Destination> ambiguousPlaces = new HashSet<>();
    private NaviHelper helper;
    private Phase phase = Phase.IDLE;
    private volatile long operation;
    private boolean connected, poisoned, routeIssued;
    private Callback<Void> connectCallback;
    private Callback<List<Destination>> placesCallback;
    private Callback<Estimate> estimateCallback;
    private Callback<ActiveRoute> activeRouteCallback;
    private Callback<RouteResult> routeCallback;
    private List<RecentDestinationInfo> recent;
    private List<BookmarkInfo> bookmarks;
    private Destination target;
    private Preference preference;
    private DestinationInfo readDestination;
    private RouteStateInfo readState;

    public PleosNaviGateway(Context context) {
        this.context = context.getApplicationContext();
        journal = this.context.getSharedPreferences("pleos_route_journal", Context.MODE_PRIVATE);
        parked = new PublicParkedGuard(this.context);
    }

    @Override public void connect(Callback<Void> callback) {
        requireMain();
        if (unavailable(callback)) return;
        if (connected) { callback.onSuccess(null); return; }
        if (busy(callback)) return;
        connectCallback = callback;
        begin(Phase.CONNECT, 12000);
        try {
            helper = new NaviHelper(context);
            helper.initialize(() -> {
                deliver(() -> {
                    if (phase != Phase.CONNECT) return;
                    helper.addListener(this);
                    connected = true;
                    if (journal.getBoolean("requestOutstanding", false)) recoverReadOnly();
                    else finishConnection();
                });
                return Unit.INSTANCE;
            }, error -> {
                deliver(() -> failSession("순정 내비 연결 실패: " + error));
                return Unit.INSTANCE;
            });
        } catch (RuntimeException ex) { failSession("순정 내비 초기화 실패: " + ex.getClass().getSimpleName()); }
    }

    private void recoverReadOnly() {
        // Process death never makes an outstanding request eligible for reissue.
        // Recovery only compares the current native route with the persisted target.
        try {
            target = new Destination(journal.getString("id", ""), journal.getString("name", ""),
                journal.getString("address", ""), Double.parseDouble(journal.getString("lat", "")),
                Double.parseDouble(journal.getString("lon", "")), journal.getString("subId", ""));
            preference = Preference.valueOf(journal.getString("preference", ""));
            phase = Phase.RECOVERY;
            readDestination = null; readState = null;
            helper.getDestinationInfo();
            helper.getRouteStateInfo();
        } catch (RuntimeException ex) {
            failSession("이전 미확인 요청 기록을 확인할 수 없습니다. 재발행하지 않습니다.");
        }
    }

    private void finishConnection() {
        Callback<Void> callback = connectCallback;
        connectCallback = null;
        phase = Phase.IDLE;
        if (callback != null) callback.onSuccess(null);
    }

    @Override public void loadDestinations(Callback<List<Destination>> callback) {
        requireMain();
        if (!ready(callback)) return;
        placesCallback = callback;
        recent = null; bookmarks = null;
        begin(Phase.PLACES, 8000);
        try { helper.getRecentDestinationInfo(); helper.getBookmarkInfo(); }
        catch (RuntimeException ex) { failSession("순정 장소 조회 실패: " + ex.getClass().getSimpleName()); }
    }

    @Override public void estimate(Destination destination, long now, Callback<Estimate> callback) {
        requireMain();
        if (!ready(callback)) return;
        if (!knownPlaces.containsKey(destination)) {
            callback.onFailure("순정 최근 목적지·즐겨찾기에서 현재 확인한 장소를 선택하세요."); return;
        }
        estimateCallback = callback;
        target = destination;
        begin(Phase.ESTIMATE, 8000);
        try {
            // Static SDK constructor inspection: latitude, longitude, poiId, poiName, poiSubId.
            helper.requestTrip(new TripRequest(destination.latitude(), destination.longitude(),
                destination.id(), destination.name(), destination.subId()));
        } catch (RuntimeException ex) { failSession("교통 정보 요청 실패: " + ex.getClass().getSimpleName()); }
    }

    @Override public void queryActiveRoute(Callback<ActiveRoute> callback) {
        requireMain();
        if (!ready(callback)) return;
        activeRouteCallback = callback;
        begin(Phase.QUERY_ROUTE, 8000);
        try { helper.getRouteStateInfo(); }
        catch (RuntimeException ex) { failSession("순정 안내 상태 조회 실패: " + ex.getClass().getSimpleName()); }
    }

    @Override public void start(Destination destination, Preference option, Callback<RouteResult> callback) {
        requireMain();
        if (phase != Phase.IDLE || !connected || poisoned) {
            callback.onSuccess(RouteResult.notSent(ActiveRoute.UNAVAILABLE, "연결이 준비되면 안내 상태를 다시 확인하세요.")); return;
        }
        if (routeIssued) {
            callback.onSuccess(RouteResult.notSent(ActiveRoute.UNAVAILABLE, "이 연결의 이전 안내 요청 상태를 확인하세요.")); return;
        }
        if (destination == null || option == null || !knownPlaces.containsKey(destination)) {
            callback.onSuccess(RouteResult.notSent(ActiveRoute.UNAVAILABLE, "현재 순정에서 확인한 장소와 경로 옵션이 필요합니다.")); return;
        }
        target = destination; preference = option; routeCallback = callback;
        begin(Phase.PRECHECK, 16000);
        try { helper.getRouteStateInfo(); }
        catch (RuntimeException ex) { failSession("현재 경로 상태 조회 실패: " + ex.getClass().getSimpleName()); }
    }

    private void issueAfterPrecheck(RouteStateInfo state) {
        ActiveRoute status = PleosRouteStatus.from(state);
        if (status != ActiveRoute.INACTIVE) {
            finishWithoutRequest(status, status == ActiveRoute.ACTIVE ? "이미 안내 중입니다. 현재 안내를 유지합니다."
                : "순정 안내 상태를 확인할 수 없어 요청을 보류했습니다.");
            return;
        }
        if (!parked.isParked()) { finishWithoutRequest(status, parked.reason()); return; }
        PlaceMetadata metadata = knownPlaces.get(target);
        if (metadata == null) { finishWithoutRequest(status, "장소 정보가 바뀌었습니다. 목적지를 다시 선택하세요."); return; }
        RouteInfo request;
        try {
            // Another gateway in the same process can have recorded a dispatch while we awaited the SDK.
            if (journal.getBoolean("requestOutstanding", false)) {
                finishWithoutRequest(ActiveRoute.UNAVAILABLE, "이전 요청의 반영 상태를 먼저 확인하세요.");
                return;
            }
            // Build the complete SDK request before recording a possible dispatch.
            request = new RouteInfo(target.latitude(), target.longitude(), metadata.displayLatitude(),
                metadata.displayLongitude(), target.name(), target.id(), target.address(), target.subId(),
                RouteOption.valueOf(preference.name()), metadata.provider(), false);
            boolean persisted = journal.edit().putBoolean("requestOutstanding", true)
                .putString("id", target.id()).putString("name", target.name()).putString("address", target.address())
                .putString("lat", Double.toString(target.latitude())).putString("lon", Double.toString(target.longitude()))
                .putString("subId", target.subId()).putString("preference", preference.name()).commit();
            if (!persisted) { failSession("안내 요청 기록을 저장할 수 없어 발행을 보류했습니다."); return; }
        } catch (RuntimeException ex) { failSession("안내 요청 준비 실패: " + ex.getClass().getSimpleName()); return; }
        routeIssued = true;
        phase = Phase.ROUTING;
        try {
            helper.requestRoute(request);
        } catch (RuntimeException ex) {
            failSession("경로 요청 결과 미확인: " + ex.getClass().getSimpleName() + ". 재발행하지 않습니다.");
        }
    }

    private void finishWithoutRequest(ActiveRoute status, String message) {
        Callback<RouteResult> callback = routeCallback;
        routeCallback = null;
        phase = Phase.IDLE;
        callback.onSuccess(RouteResult.notSent(status, message));
    }

    @Override public void onRecentDestinationInfo(List<RecentDestinationInfo> value) {
        deliver(() -> { if (phase == Phase.PLACES) { recent = value; finishPlacesIfReady(); } });
    }

    @Override public void onBookmarkInfo(List<BookmarkInfo> value) {
        deliver(() -> { if (phase == Phase.PLACES) { bookmarks = value; finishPlacesIfReady(); } });
    }

    private void finishPlacesIfReady() {
        if (recent == null || bookmarks == null) return;
        knownPlaces.clear();
        ambiguousPlaces.clear();
        for (RecentDestinationInfo value : recent) {
            if (value == null) continue;
            remember(value.getPoiId(), value.getName(), value.getAddress(), value.getLatitude(), value.getLongitude(),
                value.getPoiSubId(), value.getPoiProvider(), value.getDisplayLatitude(), value.getDisplayLongitude());
        }
        for (BookmarkInfo value : bookmarks) {
            if (value == null) continue;
            remember(value.getPoiId(), value.getName(), value.getAddress(), value.getLatitude(), value.getLongitude(),
                value.getPoiSubId(), value.getPoiProvider(), value.getDisplayLatitude(), value.getDisplayLongitude());
        }
        Callback<List<Destination>> callback = placesCallback;
        placesCallback = null; phase = Phase.IDLE;
        callback.onSuccess(new ArrayList<>(knownPlaces.keySet()));
    }

    private void remember(String id, String name, String address, double latitude, double longitude,
                          String subId, String provider, Double displayLatitude, Double displayLongitude) {
        if (id == null || name == null || address == null || subId == null || provider == null
            || address.isBlank() || (latitude == 0.0 && longitude == 0.0)
            || (displayLatitude != null && (!Double.isFinite(displayLatitude) || Math.abs(displayLatitude) > 90))
            || (displayLongitude != null && (!Double.isFinite(displayLongitude) || Math.abs(displayLongitude) > 180))) return;
        try {
            Destination destination = new Destination(id, name, address, latitude, longitude, subId);
            if (ambiguousPlaces.contains(destination)) return;
            PlaceMetadata value = new PlaceMetadata(provider, displayLatitude, displayLongitude);
            PlaceMetadata old = knownPlaces.get(destination);
            if (old != null && !old.equals(value)) {
                // A core destination cannot represent conflicting providers. Omit it instead of guessing.
                knownPlaces.remove(destination);
                ambiguousPlaces.add(destination);
                return;
            }
            knownPlaces.put(destination, value);
        } catch (IllegalArgumentException ignored) { /* Invalid SDK place is not an actionable candidate. */ }
    }

    @Override public void onTripInfo(TripInfo value) {
        deliver(() -> {
            if (phase != Phase.ESTIMATE) return;
            if (value == null) { failSession("순정에서 빈 예상 정보를 반환했습니다."); return; }
            int segments = value.getSummary() == null || value.getSummary().getTrafficConditions() == null
                ? 0 : value.getSummary().getTrafficConditions().size();
            String detail = "SDK ETA 원시값 " + value.getEta() + " · 단위 미검증 / 교통 요약 " + segments
                + "구간. 선택한 경로 옵션에 대한 예상 시간인지 아직 검증하지 않았습니다.";
            Estimate result = new Estimate(target.id(), "PLEOS_ORIGIN_UNVERIFIED", "PLEOS_TRIP_RAW_UNVERIFIED", -1,
                System.currentTimeMillis(), detail, false);
            Callback<Estimate> callback = estimateCallback;
            estimateCallback = null; phase = Phase.IDLE;
            callback.onSuccess(result);
        });
    }

    @Override public void onRouteStateInfo(RouteStateInfo value) {
        long receivedDuring = operation;
        deliver(() -> {
            if (receivedDuring != operation) return;
            if (phase == Phase.QUERY_ROUTE) {
                Callback<ActiveRoute> callback = activeRouteCallback;
                activeRouteCallback = null;
                phase = Phase.IDLE;
                callback.onSuccess(PleosRouteStatus.from(value));
            } else if (phase == Phase.PRECHECK) issueAfterPrecheck(value);
            else if (phase == Phase.READBACK || phase == Phase.RECOVERY) { readState = value; verifyReadback(); }
        });
    }

    @Override public void onRouteStarted(RouteStartInfo value) {
        deliver(() -> {
            if (phase == Phase.QUERY_ROUTE || phase == Phase.PRECHECK)
                failSession("상태 조회 중 순정 경로 탐색이 시작됐습니다.");
        });
    }

    @Override public void onRoutingCompleted() {
        deliver(() -> {
            if (phase == Phase.QUERY_ROUTE || phase == Phase.PRECHECK) { failSession("상태 조회 중 순정 경로가 바뀌었습니다."); return; }
            if (phase != Phase.ROUTING) return;
            phase = Phase.READBACK;
            readDestination = null; readState = null;
            try { helper.getDestinationInfo(); helper.getRouteStateInfo(); }
            catch (RuntimeException ex) { failSession("경로 완료 후 재조회 실패. 결과는 미확인입니다."); }
        });
    }

    @Override public void onDestinationInfo(DestinationInfo value) {
        deliver(() -> {
            if (phase == Phase.READBACK || phase == Phase.RECOVERY) { readDestination = value; verifyReadback(); }
        });
    }

    private void verifyReadback() {
        if (readDestination == null || readState == null) return;
        boolean matches = target != null && PleosRouteStatus.confirmsNativeGuidance(readState)
            && readState.getRouteOption() == RouteOption.valueOf(preference.name())
            && Objects.equals(target.id(), readDestination.getPoiId())
            && Objects.equals(target.subId(), readDestination.getPoiSubId())
            && Math.abs(target.latitude() - readDestination.getLatitude()) <= 0.000001
            && Math.abs(target.longitude() - readDestination.getLongitude()) <= 0.000001;
        if (!matches) {
            failSession("재조회한 목적지·좌표·경로 옵션이 요청과 일치하지 않습니다. 결과 미확인, 재발행 차단.");
            return;
        }
        if (!journal.edit().putBoolean("requestOutstanding", false).commit()) {
            failSession("경로 일치는 확인했지만 요청 기록을 갱신하지 못했습니다. 재발행하지 않습니다."); return;
        }
        if (phase == Phase.RECOVERY) { finishConnection(); return; }
        Callback<RouteResult> callback = routeCallback;
        routeCallback = null; phase = Phase.IDLE;
        callback.onSuccess(new RouteResult(true, "순정 목적지·좌표·경로 옵션 재조회 일치. 실제 지도 표시는 별도 확인이 필요합니다."));
    }

    @Override public void onDestinationChanged() {
        deliver(() -> { if (phase == Phase.QUERY_ROUTE || phase == Phase.PRECHECK) failSession("상태 조회 중 목적지가 바뀌었습니다."); });
    }

    @Override public void onRouteCancelled() { deliver(this::routeInterrupted); }
    @Override public void onRouteEnded() { deliver(this::routeInterrupted); }

    private void routeInterrupted() {
        if (phase == Phase.QUERY_ROUTE || phase == Phase.PRECHECK || phase == Phase.ROUTING || phase == Phase.READBACK || phase == Phase.RECOVERY)
            failSession("순정 경로가 취소·종료되어 결과를 확정하지 않습니다.");
    }

    @Override public void onError(NaviErrorCode error) {
        deliver(() -> { if (phase != Phase.IDLE) failSession("순정 내비 오류: " + error); });
    }

    private void begin(Phase next, long timeoutMs) {
        phase = next;
        long token = ++operation;
        main.postDelayed(() -> {
            if (token == operation && phase != Phase.IDLE && phase != Phase.CLOSED)
                failSession("순정 응답 시간 초과. 늦은 응답을 새 요청에 연결하지 않도록 이 연결을 중단했습니다.");
        }, timeoutMs);
    }

    private boolean unavailable(Callback<?> callback) {
        if (phase == Phase.CLOSED || poisoned) {
            callback.onFailure("연결이 종료됐거나 결과가 불확실합니다. 다시 연결해 현재 상태부터 확인하세요."); return true;
        }
        return false;
    }

    private boolean busy(Callback<?> callback) {
        if (phase != Phase.IDLE) { callback.onFailure("이전 조회·요청이 끝날 때까지 기다리세요."); return true; }
        return false;
    }

    private boolean ready(Callback<?> callback) {
        if (unavailable(callback) || busy(callback)) return false;
        if (!connected) { callback.onFailure("순정 내비가 연결되지 않았습니다."); return false; }
        return true;
    }

    private void failSession(String message) {
        if (phase == Phase.CLOSED || poisoned) return;
        boolean beforeDispatch = phase == Phase.PRECHECK;
        poisoned = true; connected = false; phase = Phase.IDLE; operation++;
        Callback<Void> connection = connectCallback;
        Callback<List<Destination>> places = placesCallback;
        Callback<Estimate> estimate = estimateCallback;
        Callback<ActiveRoute> query = activeRouteCallback;
        Callback<RouteResult> route = routeCallback;
        connectCallback = null; placesCallback = null; estimateCallback = null; activeRouteCallback = null; routeCallback = null;
        releaseHelper();
        if (connection != null) connection.onFailure(message);
        if (places != null) places.onFailure(message);
        if (estimate != null) estimate.onFailure(message);
        if (query != null) query.onFailure(message);
        if (route != null) {
            if (beforeDispatch) route.onSuccess(RouteResult.notSent(ActiveRoute.UNAVAILABLE, message));
            else route.onFailure(message);
        }
    }

    private void deliver(Runnable action) {
        main.post(() -> { if (phase != Phase.CLOSED && !poisoned) action.run(); });
    }

    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("UI 스레드에서 호출해야 합니다.");
    }

    private void releaseHelper() {
        NaviHelper old = helper; helper = null;
        if (old != null) {
            try { old.removeListener(this); } catch (RuntimeException ignored) { }
            try { old.release(); } catch (RuntimeException ignored) { }
        }
    }

    @Override public boolean isDemo() { return false; }
    public boolean needsReconnect() { return poisoned; }
    @Override public boolean isParked() { return phase != Phase.CLOSED && connected && !poisoned && parked.isParked(); }
    public String parkedStatus() { return parked.reason(); }

    @Override public void close() {
        requireMain();
        phase = Phase.CLOSED; connected = false; operation++;
        connectCallback = null; placesCallback = null; estimateCallback = null; activeRouteCallback = null; routeCallback = null;
        main.removeCallbacksAndMessages(null);
        releaseHelper(); parked.close();
        // An outstanding journal entry is deliberately retained across destruction.
    }
}
