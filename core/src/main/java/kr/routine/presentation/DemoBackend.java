package kr.routine.presentation;

import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import kr.routine.core.*;
import kr.routine.core.Model.*;

/** Local DEMO service. Owns one trip and never connects to a vehicle or live navigation. */
public final class DemoBackend implements AutoCloseable {
    private final RoutineStore store;
    private final DemoNaviGateway gateway = new DemoNaviGateway();
    private final RoutineRecommender recommender = new RoutineRecommender();
    private final TrafficAdvisor advisor = new TrafficAdvisor();
    private final List<Destination> destinations;
    private DepartureController departure;
    private ZoneId zone;
    private long now = DemoFixtures.NOW;
    private boolean closed, collectionBlocked;
    private Destination selected;
    private BackendSnapshot.Recommendation recommendation = emptyRecommendation();
    private Estimate estimate;
    private boolean trafficRequested;
    private BackendSnapshot.Notice notice;

    public DemoBackend(Path storage, ZoneId zone) throws IOException {
        this.zone = Objects.requireNonNull(zone, "zone");
        store = new RoutineStore(Objects.requireNonNull(storage, "storage"));
        gateway.connect(callback(ignored -> {}, message -> { throw new IllegalStateException(message); }));
        List<Destination> loaded = new ArrayList<>();
        gateway.loadDestinations(callback(loaded::addAll, message -> { throw new IllegalStateException(message); }));
        destinations = List.copyOf(loaded);
        resetProposal();
    }

    /** Reading a snapshot never writes, advances time, changes a proposal or requests navigation. */
    public synchronized BackendSnapshot snapshot() {
        ZonedDateTime at = time();
        String vehicle = closed ? "UNKNOWN" : gateway.isParked() ? "PARKED" : "MOVING";
        ActiveRoute activeRoute = closed ? ActiveRoute.UNAVAILABLE : departure.activeRoute();
        boolean ready = !closed && !collectionBlocked && activeRoute == ActiveRoute.INACTIVE && departure.state() == DepartureController.State.READY
            && vehicle.equals("PARKED");
        String routeBlock = routeBlockCode();
        String navCode = closed ? "CLOSED" : collectionBlocked ? "CONSENT_NOT_SAVED"
            : routeBlock != null ? routeBlock
            : departure.state() == DepartureController.State.READY && !vehicle.equals("PARKED") ? "VEHICLE_NOT_PARKED"
            : "NAVIGATION_" + departure.state().name();
        String message = closed ? "모의 연결을 종료했습니다." : collectionBlocked
            ? "수집 중지 설정을 저장하지 못해 기록과 새 안내 요청을 중지했습니다. 저장 상태를 확인해 주세요."
            : departure.state() == DepartureController.State.READY && !vehicle.equals("PARKED")
                ? "모의 주행 상태입니다. 정차 상태에서만 안내를 확인할 수 있습니다." : departure.message();
        return new BackendSnapshot("1.0", "DEMO",
            new BackendSnapshot.Context(now, zone.getId(), at.toLocalDate().toString(),
                at.getDayOfWeek().getValue(), minute(at)), destinations, store.preference(), collecting(),
            new BackendSnapshot.Counts(store.rules().size(), store.selections().size(), store.estimates().size(), gateway.startCount()),
            visibleRecommendation(), traffic(), new BackendSnapshot.Navigation(departure.state().name(), navCode, ready, message, activeRoute),
            new BackendSnapshot.Vehicle(vehicle, "DEMO"), new BackendSnapshot.Capabilities(false, false, false), notice);
    }

    public synchronized BackendSnapshot command(String operation, Map<String, String> args) {
        if (closed) throw error("CLOSED", "모의 연결이 종료됐습니다.");
        if (operation == null || operation.isBlank()) throw error("INVALID_COMMAND", "명령 이름이 필요합니다.");
        if (args == null || args.entrySet().stream().anyMatch(e -> e.getKey() == null || e.getValue() == null))
            throw error("INVALID_ARGUMENT", "명령 인자는 null일 수 없습니다.");
        try {
            switch (operation) {
                case "setContext" -> {
                    keys(args, "now", "zoneId");
                    long nextNow = epoch(required(args, "now"));
                    ZoneId nextZone = zone(required(args, "zoneId"));
                    Instant.ofEpochMilli(nextNow).atZone(nextZone);
                    mutableTrip();
                    now = nextNow; zone = nextZone; resetProposal();
                }
                case "selectDestination" -> {
                    keys(args, "id", "subId");
                    Destination destination = destination(required(args, "id"), required(args, "subId"));
                    mutableTrip();
                    propose(destination, "MANUAL", "직접 선택한 목적지입니다.", null);
                }
                case "setPreference" -> {
                    keys(args, "value");
                    Preference preference = preference(required(args, "value"));
                    mutableTrip(); store.setPreference(preference);
                    if (selected != null) departure.propose(selected, preference);
                }
                case "setConsent" -> {
                    keys(args, "enabled");
                    boolean enabled = bool(required(args, "enabled"), "enabled");
                    try {
                        store.setCollectionEnabled(enabled); collectionBlocked = false; notice = null;
                        if (!enabled) invalidateHistoryProposal();
                    }
                    catch (IOException failure) {
                        if (!enabled) {
                            collectionBlocked = true;
                            invalidateHistoryProposal();
                            notice = new BackendSnapshot.Notice("CONSENT_NOT_SAVED", "수집 중지 설정 저장에 실패하여 이번 실행에서는 수집과 새 안내 요청을 중지했습니다.");
                            throw new BackendException(notice.code(), notice.message(), failure);
                        }
                        throw failure;
                    }
                }
                case "registerRoutine" -> {
                    keys(args, "id", "subId");
                    Destination destination = destination(required(args, "id"), required(args, "subId"));
                    ZonedDateTime at = time();
                    store.putRule(new Rule(destination, at.getDayOfWeek().getValue(), minute(at)));
                }
                case "recommend" -> {
                    keys(args, "historyOnly");
                    boolean historyOnly = args.containsKey("historyOnly") && bool(args.get("historyOnly"), "historyOnly");
                    mutableTrip();
                    Model.Recommendation found = recommender.recommend(historyOnly ? List.of() : store.rules(),
                        collecting() ? store.selections() : List.of(), now, zone);
                    if (found == null || found.destination() == null) {
                        resetProposal();
                        recommendation = new BackendSnapshot.Recommendation("UNAVAILABLE", "NONE", null,
                            found == null ? "이 시간대에 추천할 등록 목적지나 충분한 선택 기록이 없습니다." : found.reason(),
                            found == null ? null : found.samples(),
                            found == null ? HoldType.NOT_ENOUGH : found.holdType(),
                            found == null || !collecting() ? List.of() : resolvedCandidates(found.candidates()));
                    }
                    else {
                        Destination current = matchingDestination(found.destination());
                        if (current == null) { resetProposal(); recommendation = new BackendSnapshot.Recommendation("UNRESOLVED", found.samples() == 0 ? "RULE" : "HISTORY", null, "기록의 목적지를 현재 목적지 목록에서 유일하게 확인하지 못했습니다.", found.samples()); }
                        else propose(current, found.samples() == 0 ? "RULE" : "HISTORY", found.reason(), found.samples());
                    }
                }
                case "refreshTraffic" -> {
                    keys(args); requireSelected(); trafficRequested = true; estimate = null;
                    gateway.estimate(selected, now, callback(value -> estimate = value, message -> {}));
                }
                case "refreshActiveRoute" -> { keys(args); refreshActiveRoute(); }
                case "confirm" -> { keys(args); confirm(); }
                case "dismiss" -> {
                    keys(args); mutableTrip(); departure.dismiss(); selected = null; estimate = null;
                    trafficRequested = false; recommendation = new BackendSnapshot.Recommendation("DISMISSED", "NONE", null, "이번 추천을 넘겼습니다.", null);
                }
                case "clearHistory" -> {
                    keys(args); store.clearHistory();
                    invalidateHistoryProposal();
                }
                case "seedDemo" -> {
                    keys(args, "days", "estimateDays", "destinationIds");
                    int days = args.containsKey("days") ? days(args.get("days"), "days") : 3;
                    int estimateDays = args.containsKey("estimateDays") ? days(args.get("estimateDays"), "estimateDays") : days;
                    List<Destination> seedDestinations = args.containsKey("destinationIds")
                        ? seedDestinations(args.get("destinationIds")) : List.of(destinations.get(0));
                    requireCollecting();
                    if (args.isEmpty()) DemoFixtures.seed(store, now, zone);
                    else DemoFixtures.seed(store, now, zone, seedDestinations, days, estimateDays);
                }
                case "setDemoScenario" -> {
                    keys(args, "traffic", "parked", "failNext", "activeRoute");
                    if (args.isEmpty()) throw error("MISSING_ARGUMENT", "변경할 모의 상황 인자가 필요합니다.");
                    DemoNaviGateway.TrafficScenario traffic = args.containsKey("traffic") ? trafficScenario(args.get("traffic")) : null;
                    Boolean parked = args.containsKey("parked") ? bool(args.get("parked"), "parked") : null;
                    Boolean failNext = args.containsKey("failNext") ? bool(args.get("failNext"), "failNext") : null;
                    ActiveRoute route = args.containsKey("activeRoute") ? parseActiveRoute(args.get("activeRoute")) : null;
                    if (traffic != null) { gateway.setTrafficScenario(traffic); estimate = null; trafficRequested = false; }
                    if (parked != null) gateway.setParked(parked);
                    if (failNext != null) gateway.setFailNext(failNext);
                    if (route != null) {
                        gateway.setActiveRoute(route);
                        refreshActiveRoute();
                    }
                }
                case "newDemoTrip" -> { keys(args); gateway.resetRoute(); resetProposal(); }
                default -> throw error("INVALID_COMMAND", "지원하지 않는 명령입니다: " + operation);
            }
        } catch (IOException failure) {
            notice = new BackendSnapshot.Notice("STORAGE_ERROR", "저장에 실패했습니다. 디스크의 기존 파일과 현재 저장 상태를 확인해 주세요.");
            throw new BackendException(notice.code(), notice.message(), failure);
        }
        return snapshot();
    }

    private void confirm() {
        if (collectionBlocked) throw error("CONSENT_NOT_SAVED", "수집 중지 설정을 저장한 뒤 다시 시도해 주세요.");
        if (locked()) throw error("TRIP_LOCKED", "이미 안내를 요청한 모의 이동입니다. 새 모의 이동을 시작해 주세요.");
        checkRouteBlock();
        requireSelected();
        if (departure.state() != DepartureController.State.READY) throw error("NOT_READY", "확인할 목적지가 없습니다.");
        if (!gateway.isParked()) throw error("VEHICLE_NOT_PARKED", "모의 정차 상태에서만 안내를 요청할 수 있습니다.");
        ZonedDateTime at = time();
        departure.confirm(at.getDayOfWeek().getValue(), minute(at), now, () -> {});
        checkRouteBlock();
        if (departure.state() == DepartureController.State.VERIFIED && collecting()) {
            boolean selectionSaved = store.selections().stream().anyMatch(row -> row.selectedAt() == now
                && row.destination().id().equals(selected.id()) && row.destination().subId().equals(selected.subId()));
            if (!selectionSaved) notice = new BackendSnapshot.Notice("STORAGE_ERROR", "모의 안내는 확인됐지만 선택 기록을 저장하지 못했습니다.");
            if (validEstimate()) {
                try { store.recordEstimate(estimate); }
                catch (IOException failure) { notice = new BackendSnapshot.Notice("STORAGE_ERROR", "모의 안내는 확인됐지만 교통 기록을 저장하지 못했습니다."); }
            }
        }
    }

    private BackendSnapshot.Traffic traffic() {
        if (estimate == null) return new BackendSnapshot.Traffic(trafficRequested ? "UNAVAILABLE" : "NOT_REQUESTED",
            null, null, null, false, null, null, "DEMO", trafficRequested ? "모의 교통 정보를 받지 못했습니다." : "목적지를 고른 뒤 모의 교통 정보를 조회해 주세요.",
            trafficRequested ? "MISSING" : "NOT_REQUESTED", List.of());
        Advice advice = advisor.advise(estimate, collecting() ? store.estimates() : List.of(), now, zone);
        boolean valid = validEstimate();
        Integer minutes = valid ? estimate.minutes() : null;
        Integer delay = valid ? advice.delayMinutes() : null;
        String status = !valid ? "STALE_OR_INVALID" : delay == null ? "BASELINE_UNAVAILABLE" : "AVAILABLE";
        return new BackendSnapshot.Traffic(status, minutes, delay == null ? null : minutes - delay, delay,
            valid && advice.important(), estimate.observedAt(), estimate.basis(), "DEMO", advice.text(),
            advice.kind().name(), advice.history().stream()
                .map(row -> new BackendSnapshot.DayEstimate(row.localDate().toString(), row.minutes())).toList());
    }

    private void invalidateHistoryProposal() {
        if (!locked() && (recommendation.source().equals("HISTORY") || recommendation.holdType() != HoldType.NONE))
            resetProposal();
    }
    private BackendSnapshot.Recommendation visibleRecommendation() {
        if (collecting()) return recommendation;
        return new BackendSnapshot.Recommendation(recommendation.status(), recommendation.source(), recommendation.destination(),
            recommendation.source().equals("HISTORY") ? "수집 동의가 꺼져 있어 과거 선택 기록을 표시하지 않습니다." : recommendation.reason(),
            recommendation.source().equals("HISTORY") ? null : recommendation.samples(), recommendation.holdType(), List.of());
    }
    private List<TiedCandidate> resolvedCandidates(List<TiedCandidate> candidates) {
        List<TiedCandidate> resolved = new ArrayList<>();
        for (TiedCandidate candidate : candidates) {
            Destination current = matchingDestination(candidate.destination());
            if (current != null) resolved.add(new TiedCandidate(current, candidate.samples()));
        }
        return List.copyOf(resolved);
    }
    private List<Destination> seedDestinations(String value) {
        if (value.isBlank()) throw error("INVALID_ARGUMENT", "destinationIds에 목적지 id를 지정해 주세요.");
        List<Destination> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (String id : value.split(",", -1)) {
            if (id.isBlank() || !unique.add(id)) throw error("INVALID_ARGUMENT", "destinationIds에는 중복이나 빈 id를 넣을 수 없습니다.");
            List<Destination> matches = destinations.stream().filter(row -> row.id().equals(id)).toList();
            if (matches.isEmpty()) throw error("DESTINATION_NOT_FOUND", "현재 목록에 없는 목적지입니다.");
            if (matches.size() != 1) throw error("DESTINATION_AMBIGUOUS", "목적지를 하나로 확인할 수 없습니다.");
            result.add(matches.get(0));
        }
        return List.copyOf(result);
    }
    private static int days(String value, String key) {
        try {
            if (!value.matches("[0-6]")) throw new NumberFormatException();
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw error("INVALID_ARGUMENT", key + "는 0부터 6까지의 정수여야 합니다.");
        }
    }
    private static DemoNaviGateway.TrafficScenario trafficScenario(String value) {
        try { return DemoNaviGateway.TrafficScenario.valueOf(value); }
        catch (IllegalArgumentException failure) {
            throw error("INVALID_ARGUMENT", "traffic은 NORMAL, CONGESTED, MISSING, STALE, UNKNOWN_UNITS 중 하나여야 합니다.");
        }
    }
    private boolean validEstimate() {
        return estimate != null && selected != null && estimate.demo() && estimate.minutes() > 0
            && estimate.destinationId().equals(selected.id()) && estimate.observedAt() <= now
            && now - estimate.observedAt() <= 120_000;
    }
    private boolean collecting() { return store.collectionEnabled() && !collectionBlocked; }
    private void refreshActiveRoute() {
        departure.refreshActiveRoute(() -> {});
    }
    private String routeBlockCode() {
        return switch (departure.state()) {
            case ALREADY_GUIDING, ROUTE_UNAVAILABLE -> departure.state().name();
            default -> null;
        };
    }
    private void checkRouteBlock() {
        String code = routeBlockCode();
        if (code != null) throw error(code, departure.message());
    }
    private void requireCollecting() {
        if (!collecting()) throw error(collectionBlocked ? "CONSENT_NOT_SAVED" : "COLLECTION_DISABLED", "모의 기록을 추가하려면 기록 수집 동의가 필요합니다.");
    }
    private void requireSelected() { if (selected == null) throw error("NOT_READY", "목적지를 먼저 선택해 주세요."); }
    private boolean locked() { return switch (departure.state()) { case REQUESTING, VERIFIED, UNCONFIRMED -> true; default -> false; }; }
    private void mutableTrip() { if (locked()) throw error("TRIP_LOCKED", "이미 요청한 모의 이동입니다. newDemoTrip으로 새 이동을 시작해 주세요."); }
    private void resetProposal() {
        departure = new DepartureController(gateway, store); selected = null; estimate = null;
        trafficRequested = false; recommendation = emptyRecommendation();
        refreshActiveRoute();
    }
    private void propose(Destination destination, String source, String reason, Integer samples) {
        selected = destination; estimate = null; trafficRequested = false;
        recommendation = new BackendSnapshot.Recommendation("READY", source, destination, reason, samples);
        departure.propose(destination, store.preference());
    }
    private Destination matchingDestination(Destination stored) {
        List<Destination> matches = destinations.stream().filter(row -> row.id().equals(stored.id()) && row.subId().equals(stored.subId())).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }
    private Destination destination(String id, String subId) {
        List<Destination> matches = destinations.stream().filter(row -> row.id().equals(id) && row.subId().equals(subId)).toList();
        if (matches.isEmpty()) throw error("DESTINATION_NOT_FOUND", "현재 목록에 없는 목적지입니다.");
        if (matches.size() != 1) throw error("DESTINATION_AMBIGUOUS", "목적지를 하나로 확인할 수 없습니다.");
        return matches.get(0);
    }
    private ZonedDateTime time() { return Instant.ofEpochMilli(now).atZone(zone); }
    private static int minute(ZonedDateTime value) { return value.getHour() * 60 + value.getMinute(); }
    private static BackendSnapshot.Recommendation emptyRecommendation() {
        return new BackendSnapshot.Recommendation("NONE", "NONE", null, "목적지를 선택하거나 추천을 요청해 주세요.", null);
    }
    private static void keys(Map<String, String> args, String... allowed) {
        Set<String> known = Set.of(allowed);
        for (String key : args.keySet()) if (!known.contains(key)) throw error("UNKNOWN_ARGUMENT", "지원하지 않는 인자입니다: " + key);
    }
    private static String required(Map<String, String> args, String key) {
        String value = args.get(key);
        if (value == null || value.isBlank()) throw error("MISSING_ARGUMENT", "필수 인자입니다: " + key);
        return value;
    }
    private static boolean bool(String value, String key) {
        if (!value.equals("true") && !value.equals("false")) throw error("INVALID_ARGUMENT", key + "는 true 또는 false여야 합니다.");
        return Boolean.parseBoolean(value);
    }
    private static ActiveRoute parseActiveRoute(String value) {
        try { return ActiveRoute.valueOf(value); }
        catch (IllegalArgumentException failure) { throw error("INVALID_ARGUMENT", "activeRoute는 ACTIVE, INACTIVE, UNAVAILABLE 중 하나여야 합니다."); }
    }
    private static long epoch(String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException failure) { throw error("INVALID_ARGUMENT", "now는 epoch milliseconds 정수여야 합니다."); }
    }
    private static ZoneId zone(String value) {
        try { return ZoneId.of(value); }
        catch (DateTimeException failure) { throw error("INVALID_ARGUMENT", "유효한 ZoneId가 필요합니다."); }
    }
    private static Preference preference(String value) {
        try { return Preference.valueOf(value); }
        catch (IllegalArgumentException failure) { throw error("INVALID_ARGUMENT", "value는 RECOMMENDED, SHORT_TIME, COMFORTABLE 중 하나여야 합니다."); }
    }
    private static BackendException error(String code, String message) { return new BackendException(code, message); }
    private static <T> NaviGateway.Callback<T> callback(Consumer<T> success, Consumer<String> failure) {
        return new NaviGateway.Callback<>() {
            public void onSuccess(T value) { success.accept(value); }
            public void onFailure(String message) { failure.accept(message); }
        };
    }
    @Override public synchronized void close() { if (!closed) { closed = true; gateway.close(); } }
}
