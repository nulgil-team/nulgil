package kr.routine.app.platform;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import ai.pleos.playground.navi.constants.NaviErrorCode;
import ai.pleos.playground.navi.constants.RouteOption;
import ai.pleos.playground.navi.constants.WaypointCmdType;
import ai.pleos.playground.navi.constants.WaypointIndex;
import ai.pleos.playground.navi.data.BookmarkInfo;
import ai.pleos.playground.navi.data.RequestWaypointInfo;
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest;
import ai.pleos.playground.navi.data.WaypointChangedInfo;
import ai.pleos.playground.navi.data.WaypointInfo;
import ai.pleos.playground.navi.data.CurrentLocationInfo;
import ai.pleos.playground.navi.data.NavigationScreenRequest;
import ai.pleos.playground.navi.data.RouteOverviewShowInfo;
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
import kr.routine.core.Model;
import kr.routine.core.Model.*;
import kr.routine.core.NaviGateway;
import kotlin.Unit;

/** Single-flight SDK adapter. No route cancellation, hidden APIs, automatic retry, or guessed ETA units. */
public final class PleosNaviGateway implements NaviGateway, NaviHelperEventListener {
    private static final String TAG = "RoutineNavi";
    private enum Phase { IDLE, CONNECT, PLACES, ESTIMATE, QUERY_ROUTE, PRECHECK, ROUTING, READBACK, RECOVERY,
        STOP_PRECHECK, STOP_ADDING, STOP_READBACK, OPTION_CHANGING, CLOSED }
    /** One changeRouteOption per issued route, after the destination is verified. */
    private boolean optionChangeTried, optionEventSeen;
    private record PlaceMetadata(String provider, Double displayLatitude, Double displayLongitude) {}
    /** A friend's place (Invite stop) has no stock POI: it is sent as plain coordinates with a blank POI id. */
    private static final PlaceMetadata EXTERNAL_PLACE = new PlaceMetadata("", null, null);
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences journal;
    private final PublicParkedGuard parked;
    private final Map<Destination, PlaceMetadata> knownPlaces = new LinkedHashMap<>();
    private final Set<Destination> ambiguousPlaces = new HashSet<>();
    private NaviHelper helper;
    private Phase phase = Phase.IDLE;
    private volatile long operation;
    private boolean connected, poisoned, routeIssued, routeStartSeen;
    /** Stock navi pushes RouteStarted then RouteEnded (same ms) and no RoutingCompleted; let state settle before readback. */
    private static final long ROUTE_SETTLE_MS = 1500;
    private static final long ROUTE_TIMEOUT_MS = 30000;
    private static final long ORIGIN_WAIT_MS = 1000;
    private static final int ROUTE_OVERVIEW_MS = 10000;
    private TripInfo tripValue;
    private CurrentLocationInfo originValue;
    /** Last navi position for the in-app map; memory only. */
    private Point lastPosition;
    private boolean originAnswered;
    /**
     * Pleos Connect 2.1.0 returns the destination snapped toward the road entrance. Measured on 2026-11-21:
     * 29 m for a hotel, 192 m for a convention center. POI id and subId must still match exactly; the coordinate
     * is only a same-place sanity check, so the tolerance has to cover large venues.
     */
    private static final double READBACK_COORD_TOLERANCE_M = 500.0;
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
    /** Stops sent with the route; the readback must find exactly these waypoints. */
    private List<Destination> routeStops = List.of();
    private boolean waypointsNeeded;
    private List<WaypointInfo> readWaypoints;
    /** One stop added while guiding: precheck, addWaypoint, settled readback. */
    private Destination stopTarget;
    private Callback<RouteResult> stopCallback;
    private RouteStateInfo stopReadState;
    private List<WaypointInfo> stopWaypoints;
    private DestinationInfo stopDestination;
    private boolean stopEventSeen;
    private int stopCountBefore;
    private String stopDestinationBefore;

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
            routeStops = decodeStops(journal.getString("stops", ""));
            phase = Phase.RECOVERY;
            readDestination = null; readState = null; readWaypoints = null;
            waypointsNeeded = !routeStops.isEmpty();
            if (waypointsNeeded) helper.getWaypointInfo();
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
        if (!knownPlaces.containsKey(destination) && !Model.external(destination)) {
            callback.onFailure("순정 최근 목적지·즐겨찾기에서 현재 확인한 장소를 선택하세요."); return;
        }
        estimateCallback = callback;
        target = destination;
        tripValue = null; originValue = null; originAnswered = false;
        begin(Phase.ESTIMATE, 8000);
        try {
            // The origin only groups comparable history; the trip is still reported if the location never arrives.
            helper.getCurrentLocationInfo();
            // Static SDK constructor inspection: latitude, longitude, poiId, poiName, poiSubId.
            // TripRequest.poiSubId is nullable in navi-data 3.0.4; send null for a POI without a sub-entrance.
            // A place sent from the phone has no stock POI: coordinates and name only, like a friend's stop.
            boolean external = Model.external(destination);
            helper.requestTrip(new TripRequest(destination.latitude(), destination.longitude(),
                external ? "" : destination.id(), destination.name(), external || destination.subId().isEmpty() ? null : destination.subId()));
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
        start(destination, List.of(), option, callback);
    }

    @Override public void start(Destination destination, List<Destination> stops, Preference option, Callback<RouteResult> callback) {
        requireMain();
        List<Destination> checkedStops;
        try { checkedStops = Model.checkStops(destination, stops); }
        catch (IllegalArgumentException | NullPointerException ex) {
            callback.onSuccess(RouteResult.notSent(ActiveRoute.INACTIVE, "들를 곳을 다시 확인해 주세요: " + ex.getMessage())); return;
        }
        for (Destination stop : checkedStops) {
            if (stopMetadata(stop) == null) {
                callback.onSuccess(RouteResult.notSent(ActiveRoute.INACTIVE, "들를 곳은 순정 장소나 친구가 보낸 곳에서 골라 주세요.")); return;
            }
        }
        if (phase != Phase.IDLE || !connected || poisoned) {
            callback.onSuccess(RouteResult.notSent(ActiveRoute.UNAVAILABLE, "연결이 준비되면 안내 상태를 다시 확인하세요.")); return;
        }
        if (routeIssued) {
            callback.onSuccess(RouteResult.notSent(ActiveRoute.UNAVAILABLE, "이 연결의 이전 안내 요청 상태를 확인하세요.")); return;
        }
        if (destination == null || option == null || (!knownPlaces.containsKey(destination) && !Model.external(destination))) {
            callback.onSuccess(RouteResult.notSent(ActiveRoute.UNAVAILABLE, "현재 순정에서 확인한 장소와 경로 옵션이 필요합니다.")); return;
        }
        target = destination; preference = option; routeCallback = callback; routeStops = checkedStops;
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
        PlaceMetadata metadata = Model.external(target) ? EXTERNAL_PLACE : knownPlaces.get(target);
        if (metadata == null) { finishWithoutRequest(status, "장소 정보가 바뀌었습니다. 목적지를 다시 선택하세요."); return; }
        RouteInfo request;
        List<RequestWaypointInfo> waypoints = new ArrayList<>();
        try {
            // Another gateway in the same process can have recorded a dispatch while we awaited the SDK.
            if (journal.getBoolean("requestOutstanding", false)) {
                finishWithoutRequest(ActiveRoute.UNAVAILABLE, "이전 요청의 반영 상태를 먼저 확인하세요.");
                return;
            }
            // Build the complete SDK request before recording a possible dispatch.
            boolean externalTarget = Model.external(target);
            request = new RouteInfo(target.latitude(), target.longitude(), metadata.displayLatitude(),
                metadata.displayLongitude(), target.name(), externalTarget ? "" : target.id(), target.address(),
                externalTarget ? "" : target.subId(), RouteOption.valueOf(preference.name()), metadata.provider(), false);
            for (int i = 0; i < routeStops.size(); i++) {
                Destination stop = routeStops.get(i);
                PlaceMetadata stopMetadata = stopMetadata(stop);
                if (stopMetadata == null) { finishWithoutRequest(status, "들를 곳 정보가 바뀌었습니다. 다시 골라 주세요."); return; }
                waypoints.add(waypointRequest(stop, stopMetadata, i == 0 ? WaypointIndex.FIRST : WaypointIndex.SECOND));
            }
            boolean persisted = journal.edit().putBoolean("requestOutstanding", true)
                .putString("id", target.id()).putString("name", target.name()).putString("address", target.address())
                .putString("lat", Double.toString(target.latitude())).putString("lon", Double.toString(target.longitude()))
                .putString("subId", target.subId()).putString("preference", preference.name())
                .putString("stops", encodeStops(routeStops)).commit();
            if (!persisted) { failSession("안내 요청 기록을 저장할 수 없어 발행을 보류했습니다."); return; }
        } catch (RuntimeException ex) { failSession("안내 요청 준비 실패: " + ex.getClass().getSimpleName()); return; }
        routeIssued = true;
        routeStartSeen = false;
        optionChangeTried = false;
        begin(Phase.ROUTING, ROUTE_TIMEOUT_MS);
        Log.i(TAG, "route: " + (waypoints.isEmpty() ? "requestRoute" : "requestRouteWithWaypoints stops=" + waypoints.size())
            + " issued option=" + preference);
        try {
            if (waypoints.isEmpty()) helper.requestRoute(request);
            else helper.requestRouteWithWaypoints(new RouteWithWaypointsRequest(request, waypoints));
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
            if (value == null) { Log.i(TAG, "place rejected: recent entry is null"); continue; }
            remember("recent", value.getPoiId(), value.getName(), value.getAddress(), value.getLatitude(), value.getLongitude(),
                value.getPoiSubId(), value.getPoiProvider(), value.getDisplayLatitude(), value.getDisplayLongitude());
        }
        for (BookmarkInfo value : bookmarks) {
            if (value == null) { Log.i(TAG, "place rejected: bookmark entry is null"); continue; }
            remember("bookmark", value.getPoiId(), value.getName(), value.getAddress(), value.getLatitude(), value.getLongitude(),
                value.getPoiSubId(), value.getPoiProvider(), value.getDisplayLatitude(), value.getDisplayLongitude());
        }
        // Counts and rejection reasons only; place names and coordinates stay out of the log.
        Log.i(TAG, "places: recent=" + recent.size() + " bookmarks=" + bookmarks.size()
            + " accepted=" + knownPlaces.size() + " ambiguous=" + ambiguousPlaces.size());
        Callback<List<Destination>> callback = placesCallback;
        placesCallback = null; phase = Phase.IDLE;
        callback.onSuccess(new ArrayList<>(knownPlaces.keySet()));
    }

    private void remember(String source, String id, String name, String address, double latitude, double longitude,
                          String subId, String provider, Double displayLatitude, Double displayLongitude) {
        if ((id == null || id.isBlank()) && (name == null || name.isBlank()) && latitude == 0.0 && longitude == 0.0) {
            Log.i(TAG, "place skipped (" + source + "): empty slot");
            return;
        }
        // Observed on Pleos Connect 2.1.0 (2026-11-21): bookmarks report poiSubId=null and poiProvider="".
        // A POI without a sub-entrance is valid; RouteInfo requires a non-null subId, so absent becomes "".
        if (subId == null) subId = "";
        if (id == null || name == null || address == null || provider == null
            || address.isBlank() || (latitude == 0.0 && longitude == 0.0)
            || (displayLatitude != null && (!Double.isFinite(displayLatitude) || Math.abs(displayLatitude) > 90))
            || (displayLongitude != null && (!Double.isFinite(displayLongitude) || Math.abs(displayLongitude) > 180))) {
            Log.i(TAG, "place rejected (" + source + "): id=" + presence(id) + " name=" + presence(name)
                + " address=" + presence(address) + " subId=" + presence(subId) + " provider=" + presence(provider)
                + " zeroCoord=" + (latitude == 0.0 && longitude == 0.0)
                + " displayLat=" + (displayLatitude == null ? "null" : "set") + " displayLon=" + (displayLongitude == null ? "null" : "set"));
            return;
        }
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

    @Override public void onCurrentLocationInfo(CurrentLocationInfo value) {
        deliver(() -> {
            if (value != null) { Point position = Point.of(value.getLatitude(), value.getLongitude()); if (position != null) lastPosition = position; }
            if (phase != Phase.ESTIMATE || originAnswered) return;
            originAnswered = true; originValue = value;
            if (tripValue != null) finishEstimate();
        });
    }

    @Override public void onTripInfo(TripInfo value) {
        deliver(() -> {
            if (phase != Phase.ESTIMATE || tripValue != null) return;
            if (value == null) { failSession("순정에서 빈 예상 정보를 반환했습니다."); return; }
            tripValue = value;
            if (originAnswered) { finishEstimate(); return; }
            long token = operation;
            main.postDelayed(() -> {
                if (token == operation && phase == Phase.ESTIMATE && !poisoned && tripValue != null) {
                    originAnswered = true; finishEstimate();
                }
            }, ORIGIN_WAIT_MS);
        });
    }

    private void finishEstimate() {
        TripInfo value = tripValue;
        CurrentLocationInfo origin = originValue;
        tripValue = null; originValue = null;
        {
            int segments = value.getSummary() == null || value.getSummary().getTrafficConditions() == null
                ? 0 : value.getSummary().getTrafficConditions().size();
            Log.i(TAG, "trip: rawEta=" + value.getEta() + " rawDistance=" + value.getDistance() + " receivedAtMs=" + System.currentTimeMillis()
                + " trafficSegments=" + segments + " summary=" + (value.getSummary() == null ? "null" : value.getSummary().getClass().getSimpleName())
                + " routePoints=" + (value.getSummary() == null || value.getSummary().getRoutePoints() == null ? "null"
                    : value.getSummary().getRoutePoints().size() + routePointTypes(value.getSummary().getRoutePoints())));
            // TripInfo.eta is seconds and distance meters: matched the stock guidance screen on 2026-11-21
            // (2357 s ↔ 40분·22km, 2111 s ↔ 36분·13km). requestTrip takes no route option, so this is the
            // stock default option. Without a known origin the time is shown but never compared with history.
            int eta = value.getEta();
            int minutes = eta > 0 ? Math.max(1, (int) Math.round(eta / 60.0)) : -1;
            String originKey = originKey(origin);
            String basis = originKey == null ? "PLEOS_TRIP_NO_ORIGIN" : "ETA_PLEOS_TRIP_DEFAULT_OPTION";
            String detail = "순정 예상 " + eta + "초 · " + value.getDistance() + "m · 순정 기본 경로 기준 · 교통 " + segments + "구간"
                + (originKey == null ? " · 출발 위치 미확인(평소 대비 비교 안 함)" : " · 출발 위치 약 1km 격자");
            Log.i(TAG, "estimate: minutes=" + minutes + " basis=" + basis + " origin=" + (originKey == null ? "unknown" : "grid"));
            Estimate result = new Estimate(target.id(), originKey == null ? "PLEOS_ORIGIN_UNKNOWN" : originKey, basis, minutes,
                System.currentTimeMillis(), detail, false);
            Callback<Estimate> callback = estimateCallback;
            estimateCallback = null; phase = Phase.IDLE;
            callback.onSuccess(result);
        }
    }

    /** About 1 km grid of the stock navi's current position. Stored only on this device. */
    private static String originKey(CurrentLocationInfo origin) {
        if (origin == null) return null;
        double lat = origin.getLatitude(), lon = origin.getLongitude();
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || (lat == 0.0 && lon == 0.0)
            || Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
        return "GRID100_" + (long) Math.floor(lat * 100) + "_" + (long) Math.floor(lon * 100);
    }

    @Override public Point currentPosition() { return lastPosition; }

    @Override public void showNativeMap(Destination destination, boolean routeOverview, Callback<Void> callback) {
        requireMain();
        if (unavailable(callback)) return;
        if (!connected || helper == null) { callback.onFailure("순정 내비가 연결되지 않았습니다."); return; }
        if (phase != Phase.IDLE) { callback.onFailure("이전 조회·요청이 끝날 때까지 기다리세요."); return; }
        NavigationScreenRequest request;
        if (routeOverview) {
            request = new NavigationScreenRequest.OverviewRoute(new RouteOverviewShowInfo(ROUTE_OVERVIEW_MS));
        } else {
            PlaceMetadata metadata = destination == null ? null : knownPlaces.get(destination);
            if (metadata == null) { callback.onFailure("순정에서 확인한 장소를 먼저 골라 주세요."); return; }
            request = new NavigationScreenRequest.POIDetailPopup(destination.id(), metadata.provider());
        }
        try {
            helper.requestNavigationScreen(request);
            Log.i(TAG, "map: requestNavigationScreen " + (routeOverview ? "OverviewRoute" : "POIDetailPopup"));
            callback.onSuccess(null);
        } catch (RuntimeException ex) {
            // A display request has no route side effect, so a failure does not poison the session.
            callback.onFailure("순정 지도를 열지 못했어요: " + ex.getClass().getSimpleName());
        }
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
            else if (phase == Phase.STOP_PRECHECK) { stopReadState = value; stopPrecheckIfReady(); }
            else if (phase == Phase.STOP_READBACK) { stopReadState = value; stopReadbackIfReady(); }
        });
    }

    @Override public void onWayPointInfo(List<WaypointInfo> value) {
        deliver(() -> {
            List<WaypointInfo> list = value == null ? List.of() : value;
            if ((phase == Phase.READBACK || phase == Phase.RECOVERY) && waypointsNeeded) { readWaypoints = list; verifyReadback(); }
            else if (phase == Phase.STOP_PRECHECK) { stopWaypoints = list; stopPrecheckIfReady(); }
            else if (phase == Phase.STOP_READBACK) { stopWaypoints = list; stopReadbackIfReady(); }
        });
    }

    @Override public void onWaypointChanged(WaypointChangedInfo value) {
        deliver(() -> {
            Log.i(TAG, "stop event: waypointChanged cmd=" + (value == null ? "null" : value.getCmdType())
                + " index=" + (value == null ? "null" : value.getIndex()) + " phase=" + phase);
            if (phase == Phase.STOP_ADDING && (value == null || value.getCmdType() == WaypointCmdType.ADD)) scheduleStopReadback();
        });
    }

    @Override public void onRouteStarted(RouteStartInfo value) {
        deliver(() -> {
            Log.i(TAG, "route event: started phase=" + phase);
            if (phase == Phase.QUERY_ROUTE || phase == Phase.PRECHECK) {
                failSession("상태 조회 중 순정 경로 탐색이 시작됐습니다."); return;
            }
            // Adding a waypoint re-routes; either this or WaypointChanged starts the settled readback.
            if (phase == Phase.STOP_ADDING) { scheduleStopReadback(); return; }
            if (phase == Phase.OPTION_CHANGING) { scheduleOptionReadback(); return; }
            if (phase != Phase.ROUTING || routeStartSeen) return;
            // Observed on Pleos Connect 2.1.0: RouteStarted is the only completion signal. Read back after it settles.
            routeStartSeen = true;
            long token = operation;
            main.postDelayed(() -> { if (token == operation && phase == Phase.ROUTING && !poisoned) startReadback(); }, ROUTE_SETTLE_MS);
        });
    }

    @Override public void onRoutingCompleted() {
        deliver(() -> {
            Log.i(TAG, "route event: routingCompleted phase=" + phase);
            if (phase == Phase.QUERY_ROUTE || phase == Phase.PRECHECK) { failSession("상태 조회 중 순정 경로가 바뀌었습니다."); return; }
            if (phase != Phase.ROUTING) return;
            startReadback();
        });
    }

    private void startReadback() {
        phase = Phase.READBACK;
        readDestination = null; readState = null; readWaypoints = null;
        waypointsNeeded = !routeStops.isEmpty();
        try {
            if (waypointsNeeded) helper.getWaypointInfo();
            helper.getDestinationInfo(); helper.getRouteStateInfo();
        }
        catch (RuntimeException ex) { failSession("경로 시작 후 재조회 실패. 결과는 미확인입니다."); }
    }

    @Override public void onDestinationInfo(DestinationInfo value) {
        deliver(() -> {
            if (phase == Phase.READBACK || phase == Phase.RECOVERY) { readDestination = value; verifyReadback(); }
            else if (phase == Phase.STOP_PRECHECK) { stopDestination = value; stopPrecheckIfReady(); }
            else if (phase == Phase.STOP_READBACK) { stopDestination = value; stopReadbackIfReady(); }
        });
    }

    private void verifyReadback() {
        if (readDestination == null || readState == null) return;
        if (waypointsNeeded && readWaypoints == null) return;
        if (phase == Phase.RECOVERY && PleosRouteStatus.from(readState) == ActiveRoute.INACTIVE) {
            // The navi reports no route and no routing in progress: the earlier request can no longer take effect, so a
            // new request cannot overwrite it. Release the lock instead of blocking the driver forever.
            Log.i(TAG, "recovery: navi is not guiding; outstanding request released");
            if (!journal.edit().putBoolean("requestOutstanding", false).remove("stops").commit()) {
                failSession("이전 요청 기록을 갱신하지 못했습니다. 재발행하지 않습니다."); return;
            }
            finishConnection(); return;
        }
        boolean stopsMatch = !waypointsNeeded || waypointsMatch(routeStops, readWaypoints);
        boolean optionMatch = readState.getRouteOption() == RouteOption.valueOf(preference.name());
        // Destination identity decides the result. Pleos Connect 2.1.0 ignores RouteInfo.routeOption and guides with
        // its own setting (measured 2026-11-21), so an option mismatch is fixed once via changeRouteOption, not failed.
        boolean matches = target != null && PleosRouteStatus.confirmsNativeGuidance(readState)
            && sameDestination(target, readDestination) && stopsMatch;
        if (waypointsNeeded) Log.i(TAG, "readback stops: wanted=" + routeStops.size() + " read=" + readWaypoints.size() + " match=" + stopsMatch
            + " " + describeWaypoints(routeStops, readWaypoints));
        Log.i(TAG, "readback: phase=" + phase + " mode=" + readState.getNaviMode() + " routing=" + readState.isRouting()
            + " terms=" + readState.getTermsAgreement() + " option=" + readState.getRouteOption() + " wanted=" + preference
            + " external=" + (target != null && Model.external(target)) + " nameMatch=" + (target != null && target.name().equals(readDestination.getName()))
            + " idMatch=" + (target != null && Objects.equals(target.id(), readDestination.getPoiId()))
            + " subIdMatch=" + (target != null && Objects.equals(target.subId(), Objects.requireNonNullElse(readDestination.getPoiSubId(), "")))
            + " coordDeltaM=" + (target == null ? "na" : Math.round(metersBetween(target.latitude(), target.longitude(),
                readDestination.getLatitude(), readDestination.getLongitude())))
            + " dLatE6=" + (target == null ? "na" : Math.round((readDestination.getLatitude() - target.latitude()) * 1e6))
            + " dLonE6=" + (target == null ? "na" : Math.round((readDestination.getLongitude() - target.longitude()) * 1e6))
            + " readDisplay=" + (readDestination.getDisplayLatitude() == null ? "null" : "set")
            + " optionMatch=" + optionMatch + " optionChangeTried=" + optionChangeTried
            + " result=" + matches);
        if (!matches) {
            failSession(waypointsNeeded && !stopsMatch
                ? "재조회한 들를 곳이 요청과 일치하지 않습니다. 결과 미확인, 재발행 차단."
                : "재조회한 목적지·좌표가 요청과 일치하지 않습니다. 결과 미확인, 재발행 차단.");
            return;
        }
        if (!optionMatch && phase == Phase.READBACK && !optionChangeTried) {
            // The destination is right; ask once for the chosen route option, then read back again.
            optionChangeTried = true; optionEventSeen = false;
            begin(Phase.OPTION_CHANGING, ROUTE_TIMEOUT_MS);
            long token = operation;
            // If the navi sends no event for the option change, read back anyway; the destination is already verified.
            main.postDelayed(() -> { if (token == operation && phase == Phase.OPTION_CHANGING && !poisoned) startReadback(); }, 10000);
            Log.i(TAG, "route: changeRouteOption " + readState.getRouteOption() + " -> " + preference);
            try { helper.changeRouteOption(RouteOption.valueOf(preference.name())); }
            catch (RuntimeException ex) { Log.i(TAG, "route: changeRouteOption failed " + ex.getClass().getSimpleName()); startReadback(); }
            return;
        }
        if (!journal.edit().putBoolean("requestOutstanding", false).remove("stops").commit()) {
            failSession("경로 일치는 확인했지만 요청 기록을 갱신하지 못했습니다. 재발행하지 않습니다."); return;
        }
        if (phase == Phase.RECOVERY) { finishConnection(); return; }
        // Verified and journal cleared: this connection may serve the next trip.
        routeIssued = false;
        Callback<RouteResult> callback = routeCallback;
        routeCallback = null; phase = Phase.IDLE;
        String place = routeStops.isEmpty() ? "순정 목적지·좌표 재조회 일치." : "순정 목적지·들를 곳 " + routeStops.size() + "곳 재조회 일치.";
        callback.onSuccess(new RouteResult(true, optionMatch
            ? place + " 경로 성향 " + optionLabel(readState.getRouteOption()) + "."
            : place + " 경로 성향은 순정 설정(" + optionLabel(readState.getRouteOption()) + ")으로 안내 중이에요. 순정 내비에서 바꿀 수 있어요."));
    }

    private static String optionLabel(RouteOption option) {
        if (option == null) return "알 수 없음";
        return switch (option.name()) {
            case "RECOMMENDED" -> "추천 경로";
            case "SHORT_TIME" -> "빠른 경로";
            case "COMFORTABLE" -> "편안한 경로";
            case "SHORT_DISTANCE" -> "최단 거리";
            case "FREE" -> "무료 도로";
            case "HIGHWAY" -> "고속도로 우선";
            case "SCHOOL_ZONE" -> "어린이 보호구역 회피";
            default -> option.name();
        };
    }

    @Override public void onRouteOptionChanged(RouteOption value) {
        deliver(() -> {
            Log.i(TAG, "route event: optionChanged " + value + " phase=" + phase);
            if (phase == Phase.OPTION_CHANGING) scheduleOptionReadback();
        });
    }

    private void scheduleOptionReadback() {
        if (optionEventSeen) return;
        optionEventSeen = true;
        long token = operation;
        main.postDelayed(() -> { if (token == operation && phase == Phase.OPTION_CHANGING && !poisoned) startReadback(); }, ROUTE_SETTLE_MS);
    }

    // --- Adding one stop while guiding ---

    @Override public void addStop(Destination stop, Callback<RouteResult> callback) {
        requireMain();
        if (phase != Phase.IDLE || !connected || poisoned || helper == null) {
            callback.onSuccess(RouteResult.notSent(ActiveRoute.UNAVAILABLE, "연결이 준비되면 다시 확인하세요.")); return;
        }
        if (stop == null || stopMetadata(stop) == null) {
            callback.onSuccess(RouteResult.notSent(ActiveRoute.ACTIVE, "들를 곳은 순정 장소나 친구가 보낸 곳에서 골라 주세요.")); return;
        }
        stopTarget = stop; stopCallback = callback;
        stopReadState = null; stopWaypoints = null; stopDestination = null; stopEventSeen = false;
        begin(Phase.STOP_PRECHECK, 16000);
        try { helper.getRouteStateInfo(); helper.getWaypointInfo(); helper.getDestinationInfo(); }
        catch (RuntimeException ex) { failSession("들를 곳 넣기 전 확인 실패: " + ex.getClass().getSimpleName()); }
    }

    private void stopPrecheckIfReady() {
        if (stopReadState == null || stopWaypoints == null || stopDestination == null) return;
        ActiveRoute status = PleosRouteStatus.from(stopReadState);
        Log.i(TAG, "stop precheck: mode=" + stopReadState.getNaviMode() + " routing=" + stopReadState.isRouting()
            + " waypoints=" + stopWaypoints.size());
        if (!PleosRouteStatus.confirmsNativeGuidance(stopReadState) || stopReadState.isRouting()) {
            finishStop(RouteResult.notSent(status == ActiveRoute.INACTIVE ? ActiveRoute.INACTIVE : ActiveRoute.ACTIVE,
                "순정 내비가 안내 중일 때만 들를 곳을 넣을 수 있어요.")); return;
        }
        if (stopWaypoints.size() >= Model.MAX_STOPS) {
            finishStop(RouteResult.notSent(ActiveRoute.ACTIVE, "이미 들를 곳이 " + Model.MAX_STOPS + "곳이에요. 순정 내비에서 직접 바꿔 주세요.")); return;
        }
        for (WaypointInfo existing : stopWaypoints) {
            if (sameWaypoint(stopTarget, existing)) { finishStop(RouteResult.notSent(ActiveRoute.ACTIVE, "이미 경로에 있는 곳이에요.")); return; }
        }
        if (Objects.equals(stopTarget.id(), stopDestination.getPoiId())
            && Objects.equals(stopTarget.subId(), Objects.requireNonNullElse(stopDestination.getPoiSubId(), ""))) {
            finishStop(RouteResult.notSent(ActiveRoute.ACTIVE, "지금 안내 중인 목적지예요. 들를 곳으로 넣을 수 없어요.")); return;
        }
        if (!parked.isParked()) { finishStop(RouteResult.notSent(ActiveRoute.ACTIVE, parked.reason())); return; }
        PlaceMetadata metadata = stopMetadata(stopTarget);
        if (metadata == null) { finishStop(RouteResult.notSent(ActiveRoute.ACTIVE, "장소 정보가 바뀌었습니다. 다시 골라 주세요.")); return; }
        RequestWaypointInfo request;
        try { request = waypointRequest(stopTarget, metadata, stopWaypoints.isEmpty() ? WaypointIndex.FIRST : WaypointIndex.SECOND); }
        catch (RuntimeException ex) { finishStop(RouteResult.notSent(ActiveRoute.ACTIVE, "들를 곳 요청을 만들지 못했어요: " + ex.getClass().getSimpleName())); return; }
        stopCountBefore = stopWaypoints.size();
        stopDestinationBefore = destinationKey(stopDestination);
        begin(Phase.STOP_ADDING, ROUTE_TIMEOUT_MS);
        Log.i(TAG, "stop: addWaypoint issued index=" + (stopCountBefore == 0 ? "FIRST" : "SECOND"));
        try { helper.addWaypoint(request); }
        catch (RuntimeException ex) { failSession("들를 곳 요청 결과 미확인: " + ex.getClass().getSimpleName() + ". 다시 요청하지 않습니다."); }
    }

    private void scheduleStopReadback() {
        if (stopEventSeen) return;
        stopEventSeen = true;
        long token = operation;
        main.postDelayed(() -> {
            if (token != operation || phase != Phase.STOP_ADDING || poisoned) return;
            phase = Phase.STOP_READBACK;
            stopReadState = null; stopWaypoints = null; stopDestination = null;
            try { helper.getRouteStateInfo(); helper.getWaypointInfo(); helper.getDestinationInfo(); }
            catch (RuntimeException ex) { failSession("들를 곳 추가 후 재조회 실패. 결과는 미확인입니다."); }
        }, ROUTE_SETTLE_MS);
    }

    private void stopReadbackIfReady() {
        if (stopReadState == null || stopWaypoints == null || stopDestination == null) return;
        boolean guiding = PleosRouteStatus.confirmsNativeGuidance(stopReadState);
        boolean destinationKept = Objects.equals(stopDestinationBefore, destinationKey(stopDestination));
        boolean contains = false;
        for (WaypointInfo w : stopWaypoints) if (sameWaypoint(stopTarget, w)) contains = true;
        boolean countOk = stopWaypoints.size() == stopCountBefore + 1;
        boolean verified = guiding && destinationKept && contains && countOk;
        Log.i(TAG, "stop readback: guiding=" + guiding + " destinationKept=" + destinationKept + " contains=" + contains
            + " waypoints=" + stopCountBefore + "->" + stopWaypoints.size() + " result=" + verified);
        finishStop(verified
            ? new RouteResult(true, stopTarget.name() + " · 들를 곳을 경로에 넣었어요. 목적지는 그대로예요.")
            : new RouteResult(false, "들를 곳이 들어갔는지 확인하지 못했어요. 다시 요청하지 않고 순정 내비를 확인하세요."));
    }

    private void finishStop(RouteResult result) {
        Callback<RouteResult> callback = stopCallback;
        stopCallback = null; stopTarget = null; phase = Phase.IDLE;
        if (callback != null) callback.onSuccess(result);
    }

    /** Stock places match by POI id, sub id and position; an external place (from the phone) by position or name. */
    private static boolean sameDestination(Destination target, DestinationInfo read) {
        if (Model.external(target)) {
            if (read.getLatitude() != 0.0 || read.getLongitude() != 0.0)
                return metersBetween(target.latitude(), target.longitude(), read.getLatitude(), read.getLongitude()) <= READBACK_COORD_TOLERANCE_M;
            return read.getName() != null && read.getName().trim().equals(target.name().trim());
        }
        return Objects.equals(target.id(), read.getPoiId())
            && Objects.equals(target.subId(), Objects.requireNonNullElse(read.getPoiSubId(), ""))
            && metersBetween(target.latitude(), target.longitude(), read.getLatitude(), read.getLongitude()) <= READBACK_COORD_TOLERANCE_M;
    }

    private PlaceMetadata stopMetadata(Destination stop) {
        if (stop == null) return null;
        return Model.external(stop) ? EXTERNAL_PLACE : knownPlaces.get(stop);
    }

    private static RequestWaypointInfo waypointRequest(Destination stop, PlaceMetadata metadata, WaypointIndex index) {
        // Constructor order from navi-data 3.0.4: latitude, longitude, poiName, index, poiId, address, poiSubId, poiProvider.
        boolean external = Model.external(stop);
        return new RequestWaypointInfo(stop.latitude(), stop.longitude(), stop.name(), index, external ? "" : stop.id(),
            stop.address(), external ? "" : stop.subId(), metadata.provider() == null ? "" : metadata.provider());
    }

    /**
     * A friend's place has no POI id to compare. Match by reported position when the navi gives one, otherwise by
     * name; the readback also requires exactly one more waypoint and the destination unchanged.
     */
    private static boolean sameExternalWaypoint(Destination stop, WaypointInfo w) {
        if (w.getLatitude() != 0.0 || w.getLongitude() != 0.0)
            return metersBetween(stop.latitude(), stop.longitude(), w.getLatitude(), w.getLongitude()) <= READBACK_COORD_TOLERANCE_M;
        if (w.getDisplayLatitude() != null && w.getDisplayLongitude() != null
            && (w.getDisplayLatitude() != 0.0 || w.getDisplayLongitude() != 0.0))
            return metersBetween(stop.latitude(), stop.longitude(), w.getDisplayLatitude(), w.getDisplayLongitude()) <= READBACK_COORD_TOLERANCE_M;
        return w.getName() != null && w.getName().trim().equals(stop.name().trim());
    }

    /**
     * POI id and subId must match exactly. Pleos Connect 2.1.0 returns waypoint coordinates as (0, 0) with no
     * display coordinates (measured 2026-11-21, while remainDistance matched the stop), so the coordinate check
     * applies only when the navi actually reports a position.
     */
    private static boolean sameWaypoint(Destination stop, WaypointInfo w) {
        if (stop != null && w != null && Model.external(stop)) return sameExternalWaypoint(stop, w);
        if (stop == null || w == null || !Objects.equals(stop.id(), w.getPoiId())
            || !Objects.equals(stop.subId(), Objects.requireNonNullElse(w.getPoiSubId(), ""))) return false;
        if (w.getLatitude() != 0.0 || w.getLongitude() != 0.0)
            return metersBetween(stop.latitude(), stop.longitude(), w.getLatitude(), w.getLongitude()) <= READBACK_COORD_TOLERANCE_M;
        if (w.getDisplayLatitude() != null && w.getDisplayLongitude() != null)
            return metersBetween(stop.latitude(), stop.longitude(), w.getDisplayLatitude(), w.getDisplayLongitude()) <= READBACK_COORD_TOLERANCE_M;
        return true;
    }

    /** Field-by-field comparison for the log: match flags and distances only, no names or coordinates. */
    private static String describeWaypoints(List<Destination> wanted, List<WaypointInfo> read) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; read != null && i < read.size(); i++) {
            WaypointInfo w = read.get(i);
            Destination s = i < wanted.size() ? wanted.get(i) : null;
            if (i > 0) out.append("; ");
            if (w == null || s == null) { out.append(w == null ? "null" : "extra"); continue; }
            out.append("id=").append(Objects.equals(s.id(), w.getPoiId()) ? "same" : (w.getPoiId() == null ? "null" : w.getPoiId().isBlank() ? "blank" : "diff"))
                .append(" sub=").append(w.getPoiSubId() == null ? "null" : w.getPoiSubId().isBlank() ? "blank" : Objects.equals(s.subId(), w.getPoiSubId()) ? "same" : "diff")
                .append(" provider=").append(w.getPoiProvider() == null ? "null" : w.getPoiProvider().isBlank() ? "blank" : "set")
                .append(" name=").append(Objects.equals(s.name(), w.getName()) ? "same" : "diff")
                .append(" deltaM=").append(Math.round(metersBetween(s.latitude(), s.longitude(), w.getLatitude(), w.getLongitude())))
                .append(" zero=").append(w.getLatitude() == 0.0 && w.getLongitude() == 0.0)
                .append(" display=").append(w.getDisplayLatitude() == null || w.getDisplayLongitude() == null ? "null"
                    : "deltaM" + Math.round(metersBetween(s.latitude(), s.longitude(), w.getDisplayLatitude(), w.getDisplayLongitude())))
                .append(" remain=").append(w.getRemainDistance()).append("m/").append(w.getRemainTime()).append("s");
        }
        return out.append("]").toString();
    }

    /** Every requested stop must be on the route, and nothing else. Order is not compared. */
    private static boolean waypointsMatch(List<Destination> wanted, List<WaypointInfo> read) {
        if (read == null || read.size() != wanted.size()) return false;
        for (Destination stop : wanted) {
            boolean found = false;
            for (WaypointInfo w : read) if (sameWaypoint(stop, w)) found = true;
            if (!found) return false;
        }
        return true;
    }

    private static String destinationKey(DestinationInfo value) {
        return value == null ? null : value.getPoiId() + "/" + Objects.requireNonNullElse(value.getPoiSubId(), "");
    }

    /** Journal format: id|subId|lat|lon|name per stop, separated by newlines. The name verifies a friend's place. */
    private static String encodeStops(List<Destination> stops) {
        StringBuilder out = new StringBuilder();
        for (Destination s : stops) {
            if (out.length() > 0) out.append('\n');
            out.append(s.id()).append('|').append(s.subId()).append('|').append(s.latitude()).append('|').append(s.longitude())
                .append('|').append(s.name().replace('|', ' ').replace('\n', ' '));
        }
        return out.toString();
    }

    private static List<Destination> decodeStops(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<Destination> out = new ArrayList<>();
        for (String line : value.split("\n")) {
            String[] f = line.split("\\|", -1);
            out.add(new Destination(f[0], f.length > 4 && !f[4].isBlank() ? f[4] : "들를 곳", "", Double.parseDouble(f[2]), Double.parseDouble(f[3]), f[1]));
        }
        return out;
    }

    @Override public void onDestinationChanged() {
        deliver(() -> { if (phase == Phase.QUERY_ROUTE || phase == Phase.PRECHECK) failSession("상태 조회 중 목적지가 바뀌었습니다."); });
    }

    @Override public void onRouteCancelled() { deliver(() -> routeInterrupted("cancelled")); }
    @Override public void onRouteEnded() { deliver(() -> routeInterrupted("ended")); }

    private void routeInterrupted(String event) {
        Log.i(TAG, "route event: " + event + " phase=" + phase + " startSeen=" + routeStartSeen);
        // Adding a waypoint re-routes the guided trip; the settled readback decides, not this event.
        if (phase == Phase.STOP_ADDING || phase == Phase.STOP_READBACK || phase == Phase.STOP_PRECHECK
            || phase == Phase.OPTION_CHANGING) return;
        // The stock navi ends its previous (free-drive) session right after RouteStarted. That is a transition,
        // not a failure; the settled readback decides. An end without a start keeps failing as before.
        if (phase == Phase.ROUTING && routeStartSeen) return;
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
        boolean stopBeforeDispatch = phase == Phase.STOP_PRECHECK;
        poisoned = true; connected = false; phase = Phase.IDLE; operation++;
        Callback<Void> connection = connectCallback;
        Callback<List<Destination>> places = placesCallback;
        Callback<Estimate> estimate = estimateCallback;
        Callback<ActiveRoute> query = activeRouteCallback;
        Callback<RouteResult> route = routeCallback;
        Callback<RouteResult> stop = stopCallback;
        connectCallback = null; placesCallback = null; estimateCallback = null; activeRouteCallback = null; routeCallback = null;
        stopCallback = null; stopTarget = null;
        if (stop != null) {
            if (stopBeforeDispatch) stop.onSuccess(RouteResult.notSent(ActiveRoute.UNAVAILABLE, message));
            else stop.onFailure(message);
        }
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

    /** Point types and route ratios only; no names or coordinates in the log. */
    private static String routePointTypes(List<ai.pleos.playground.navi.data.RoutePointInfo> points) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < Math.min(points.size(), 6); i++) {
            ai.pleos.playground.navi.data.RoutePointInfo p = points.get(i);
            if (i > 0) out.append(',');
            out.append(p == null ? "null" : p.getPointType() + "@" + Math.round(p.getRatioOnRoute() * 100) / 100.0);
        }
        return out.append(points.size() > 6 ? ",...]" : "]").toString();
    }

    /** Haversine distance in meters. */
    static double metersBetween(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0, p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static String presence(String value) {
        return value == null ? "null" : value.isBlank() ? "blank" : "set";
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
