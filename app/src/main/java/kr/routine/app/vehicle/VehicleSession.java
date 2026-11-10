package kr.routine.app.vehicle;

import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import kr.routine.core.*;
import kr.routine.core.Model.*;

/** UI-thread session retained outside Activity. Rendering never creates a trip or sends a route. */
public final class VehicleSession implements AutoCloseable {
    private final NaviGateway gateway;
    private final RoutineStore store;
    private final boolean demo;
    private final Clock clock;
    private final ZoneId zone;
    private final RoutineRecommender recommender = new RoutineRecommender();
    private final TrafficAdvisor advisor = new TrafficAdvisor();
    private DepartureController departure;
    private Runnable listener;
    private List<Destination> destinations = List.of();
    private Destination destination;
    private Recommendation recommendation = new Recommendation(null, "평소 장소를 등록하거나 직접 골라 주세요.", 0);
    private String source = "NONE", notice = "", status = "연결을 준비하고 있어요";
    private Estimate estimate;
    private boolean connected, busy, closed, started, pendingForeground, initialRecommendationPending, trafficRequested, collectionBlocked;
    private boolean lastParked, lastFresh;
    private boolean explicitlyDismissed;
    private long sequence, demoNow = DemoFixtures.NOW;

    public VehicleSession(NaviGateway gateway, RoutineStore store, boolean demo, Clock clock, ZoneId zone) {
        this.gateway = Objects.requireNonNull(gateway); this.store = Objects.requireNonNull(store);
        this.demo = demo; this.clock = Objects.requireNonNull(clock); this.zone = Objects.requireNonNull(zone);
        if (gateway.isDemo() != demo) throw new IllegalArgumentException("gateway mode mismatch");
        departure = new DepartureController(gateway, store);
    }

    public VehicleUiState snapshot() {
        boolean parked = parked(), working = working(), collecting = collecting();
        boolean edit = connected && !closed && !working && !pendingForeground && !locked()
            && !collectionBlocked && departure.activeRoute() == ActiveRoute.INACTIVE && parked;
        Estimate comparable = estimate != null && destination != null && estimate.demo() == demo
            && estimate.destinationId().equals(destination.id()) ? estimate : null;
        Advice advice = advisor.advise(comparable, collecting ? store.estimates() : List.of(), now(), zone);
        boolean valid = validEstimate();
        Integer minutes = valid ? estimate.minutes() : null;
        Integer delay = valid ? advice.delayMinutes() : null;
        String message = status;
        if (connected && !busy) message = departure.message();
        // When the native navi does not answer, the cause is the route lookup, not parking; keep that message.
        if (connected && !working && !parked && !locked() && departure.activeRoute() != ActiveRoute.UNAVAILABLE)
            message = "정차 상태를 확인한 뒤 조작할 수 있어요";
        if (collectionBlocked) message = "수집 중지 설정을 저장하지 못했어요. 새 기록과 안내 요청을 중지했어요.";
        if (closed) message = "연결을 종료했어요";
        return new VehicleUiState(demo, connected, working, parked, collecting, collectionBlocked,
            destinations, destination, store.preference(), source,
            !collecting && source.equals("HISTORY") ? 0 : recommendation.samples(),
            recommendation.holdType(), collecting ? recommendation.candidates() : List.of(),
            !collecting && source.equals("HISTORY") ? "기록 저장이 꺼져 있어 지난 선택 기록은 표시하지 않아요." : recommendation.reason(),
            trafficRequested ? advice.kind().name() : "NOT_REQUESTED", minutes,
            delay == null ? null : minutes - delay, delay,
            collecting && trafficRequested ? advice.history() : List.of(), departure.state(), departure.activeRoute(),
            edit, edit && destination != null && departure.state() == DepartureController.State.READY,
            !closed && !working, !closed && !working && !locked() && !collectionBlocked, message, notice, now(), zone,
            gateway instanceof DemoNaviGateway g ? g.startCount() : -1, store.rules());
    }

    public void setListener(Runnable value) { listener = value; if (listener != null) listener.run(); }

    public void connect() {
        if (closed || working() || connected || started) return;
        started = true; busy = true; status = "내비 연결을 준비하고 있어요";
        long request = ++sequence; emit();
        gateway.connect(callback(request, value -> {
            connected = true; busy = false; status = "현재 안내 상태를 확인하고 있어요"; refresh();
        }, error -> { busy = false; connected = false; status = "내비 연결을 확인하지 못했어요"; notice = error; emit(); }));
    }

    /** Explicit retry is a read/connect operation only. Never creates a new DepartureController. */
    public void refresh() {
        if (closed || working()) return;
        if (!connected) { started = false; connect(); return; }
        pendingForeground = false; busy = true; status = "현재 안내 상태를 확인하고 있어요";
        long request = ++sequence;
        departure.refreshActiveRoute(() -> {
            if (!current(request)) return;
            busy = false;
            if (explicitlyDismissed && !locked() && departure.activeRoute() == ActiveRoute.INACTIVE) departure.dismiss();
            if (!locked() && departure.activeRoute() == ActiveRoute.INACTIVE && parked()) {
                if (destinations.isEmpty()) loadPlaces();
                else if (destination != null) preview();
                else if (initialRecommendationPending) { initialRecommendationPending = false; recommend(false); }
                else emit();
            } else emit();
        });
        emit();
    }

    public void onForeground() {
        if (closed) return;
        if (working()) { pendingForeground = true; emit(); }
        else refresh();
    }

    /** Only redraw when a gate or estimate validity changed, not once every clock tick. */
    public void tick() {
        if (closed) return;
        boolean parked = parked(), fresh = validEstimate();
        if (parked != lastParked || fresh != lastFresh) { lastParked = parked; lastFresh = fresh; emit(); }
        drainForeground();
    }

    private void loadPlaces() {
        busy = true; status = "즐겨찾기와 최근 목적지를 불러오고 있어요";
        long request = ++sequence; emit();
        gateway.loadDestinations(callback(request, values -> {
            busy = false; destinations = values == null ? List.of() : List.copyOf(values);
            initialRecommendationPending = true;
            if (!drainForeground()) { initialRecommendationPending = false; recommend(false); }
        }, error -> { busy = false; notice = error; emit(); drainForeground(); }));
    }

    public void selectDestination(Destination value) {
        if (!snapshot().canEdit()) return;
        Destination current = resolve(value);
        if (current == null) { notice = "현재 내비에서 장소를 다시 확인해 주세요."; emit(); return; }
        propose(current, "MANUAL", new Recommendation(current, "직접 선택한 목적지예요.", 0));
    }

    public void recommend(boolean historyOnly) {
        if (!snapshot().canEdit()) return;
        explicitlyDismissed = false;
        Recommendation found = recommender.recommend(historyOnly ? List.of() : store.rules(),
            collecting() ? store.selections() : List.of(), now(), zone);
        Destination selected = found.destination() == null ? null : resolve(found.destination());
        if (selected != null) propose(selected, found.samples() == 0 ? "RULE" : "HISTORY", found);
        else {
            destination = null; estimate = null; trafficRequested = false;
            source = found.destination() == null ? "NONE" : "UNRESOLVED";
            List<TiedCandidate> candidates = new ArrayList<>();
            for (TiedCandidate candidate : found.candidates()) {
                Destination resolved = resolve(candidate.destination());
                if (resolved != null) candidates.add(new TiedCandidate(resolved, candidate.samples()));
            }
            recommendation = new Recommendation(null, found.destination() == null ? found.reason()
                : "저장한 장소를 현재 내비 목록에서 확인하지 못했어요.",
                found.samples(), found.holdType(), candidates);
            departure.dismiss(); emit();
        }
    }

    private void propose(Destination value, String kind, Recommendation found) {
        explicitlyDismissed = false;
        destination = value; source = kind; recommendation = found; estimate = null; trafficRequested = false;
        departure.propose(value, store.preference()); notice = ""; preview();
    }

    public void setPreference(Preference preference) {
        if (!snapshot().canEdit()) return;
        if (save(() -> store.setPreference(preference))) {
            if (destination != null) departure.propose(destination, preference);
            emit();
        }
    }

    private void preview() {
        if (!snapshot().canEdit() || destination == null) { emit(); return; }
        Destination selected = destination; busy = true; trafficRequested = true; estimate = null;
        status = "교통 정보를 확인하고 있어요";
        long request = ++sequence; emit();
        gateway.estimate(selected, now(), callback(request, value -> {
            busy = false;
            if (selected.equals(destination)) estimate = value;
            emit(); drainForeground();
        }, error -> { busy = false; estimate = null; notice = error; emit(); drainForeground(); }));
    }

    public void confirm() {
        if (!snapshot().canConfirm()) return;
        long at = now(); ZonedDateTime local = Instant.ofEpochMilli(at).atZone(zone);
        final Estimate submitted = estimate;
        final long request = ++sequence;
        final boolean[] persisted = {false};
        departure.confirm(local.getDayOfWeek().getValue(), local.getHour() * 60 + local.getMinute(), at, () -> {
            if (!current(request)) return;
            if (!persisted[0] && departure.state() == DepartureController.State.VERIFIED) {
                persisted[0] = true;
                if (collecting() && validEstimate(submitted, now())) save(() -> store.recordEstimate(submitted));
            }
            emit(); drainForeground();
        });
        emit();
    }

    public void dismiss() {
        if (!snapshot().canEdit()) return;
        explicitlyDismissed = true;
        departure.dismiss(); destination = null; estimate = null; trafficRequested = false;
        source = "NONE"; recommendation = new Recommendation(null, "이번 추천은 넘겼어요.", 0, HoldType.NONE, List.of()); emit();
    }

    public void setConsent(boolean enabled) {
        if (closed) return;
        if (working()) { notice = "진행 중인 확인이 끝나면 기록 설정을 바꿀 수 있어요."; emit(); return; }
        try { store.setCollectionEnabled(enabled); collectionBlocked = false; notice = ""; }
        catch (IOException failure) {
            if (!enabled) collectionBlocked = true;
            notice = "기록 설정을 저장하지 못했어요. 기존 파일을 보존했어요.";
        }
        if (!collecting() && !locked() && (source.equals("HISTORY") || recommendation.holdType() != HoldType.NONE)) clearProposal();
        emit();
    }

    public void registerRoutine(Destination value, int day, int minute) {
        if (!snapshot().canEdit()) return;
        Destination resolved = resolve(value);
        if (resolved == null) { notice = "등록할 장소를 다시 선택해 주세요."; emit(); return; }
        Model.checkTime(day, minute);
        if (save(() -> store.putRule(new Rule(resolved, day, minute)))) {
            recommend(false);
            notice = "평소 가는 장소와 시간을 저장했어요.";
        }
        emit();
    }

    public void clearHistory() {
        if (closed || working()) return;
        if (save(store::clearHistory) && !locked() && (source.equals("HISTORY") || recommendation.holdType() != HoldType.NONE)) clearProposal();
        emit();
    }

    /** Called only from a developer control after explicit history-reset confirmation. */
    public void demoScenario(String key) {
        if (!demo || !(gateway instanceof DemoNaviGateway g) || closed || working()) return;
        Set<String> supported = Set.of("learned_usual","learned_congested","rule_usual","no_history","not_enough","tie",
            "compare_short","traffic_missing","traffic_stale","live_unknown_units","active","unavailable","moving","unconfirmed");
        if (!supported.contains(key)) { notice = "지원하지 않는 시연 상태예요."; emit(); return; }
        boolean seeded = !key.equals("no_history") && !key.equals("active") && !key.equals("unavailable");
        if (seeded && !collecting()) { notice = "예시 기록을 넣기 전에 기록 저장을 직접 허용해 주세요."; emit(); return; }
        if (!save(store::clearHistory)) return;
        if (seeded) {
            if (!save(() -> {
                if (key.equals("rule_usual")) DemoFixtures.seed(store, demoNow, zone);
                else DemoFixtures.seed(store, demoNow, zone,
                    key.equals("tie") ? DemoFixtures.destinations() : List.of(DemoFixtures.OFFICE),
                    key.equals("not_enough") ? 2 : 3, key.equals("compare_short") ? 2 : key.equals("not_enough") ? 2 : 3);
            })) return;
        }
        ++sequence; g.resetRoute(); g.setParked(true); g.setFailNext(key.equals("unconfirmed"));
        g.setTrafficScenario(switch(key) {
            case "learned_congested" -> DemoNaviGateway.TrafficScenario.CONGESTED;
            case "traffic_missing" -> DemoNaviGateway.TrafficScenario.MISSING;
            case "traffic_stale" -> DemoNaviGateway.TrafficScenario.STALE;
            case "live_unknown_units" -> DemoNaviGateway.TrafficScenario.UNKNOWN_UNITS;
            default -> DemoNaviGateway.TrafficScenario.NORMAL;
        });
        if (key.equals("active")) g.setActiveRoute(ActiveRoute.ACTIVE);
        if (key.equals("unavailable")) g.setActiveRoute(ActiveRoute.UNAVAILABLE);
        clearProposal(); departure = new DepartureController(gateway, store);
        pendingForeground = false; notice = "모의 상황을 준비했어요. 실제 순정 내비는 바뀌지 않았어요.";
        departure.refreshActiveRoute(() -> {
            if (!key.equals("active") && !key.equals("unavailable")) recommend(!key.equals("rule_usual"));
            else emit();
        });
        if (key.equals("moving")) g.setParked(false);
        emit();
    }

    private void clearProposal() {
        explicitlyDismissed = false;
        destination = null; estimate = null; trafficRequested = false; source = "NONE";
        recommendation = new Recommendation(null, "평소 장소를 등록하거나 직접 골라 주세요.", 0);
        if (!locked()) departure.dismiss();
    }
    private Destination resolve(Destination value) {
        if (value == null) return null;
        List<Destination> found = destinations.stream().filter(p -> p.id().equals(value.id()) && p.subId().equals(value.subId())).toList();
        return found.size() == 1 ? found.get(0) : null;
    }
    private boolean validEstimate() { return validEstimate(estimate, now()); }
    private boolean validEstimate(Estimate value, long at) {
        return value != null && destination != null && value.destinationId().equals(destination.id()) && value.demo() == demo
            && value.minutes() > 0 && value.observedAt() <= at && at - value.observedAt() <= 120_000;
    }
    private boolean collecting() { return store.collectionEnabled() && !collectionBlocked; }
    private long now() { return demo ? demoNow : clock.millis(); }
    private boolean parked() { try { return connected && !closed && gateway.isParked(); } catch (RuntimeException error) { return false; } }
    private boolean locked() { return switch(departure.state()) { case REQUESTING,VERIFIED,UNCONFIRMED -> true; default -> false; }; }
    private boolean working() { return busy || departure.state() == DepartureController.State.REQUESTING || departure.state() == DepartureController.State.CHECKING_ROUTE; }
    private boolean current(long request) { return !closed && sequence == request; }
    private boolean drainForeground() {
        if (pendingForeground && connected && !working() && !closed) { pendingForeground = false; refresh(); return true; }
        return false;
    }
    private <T> NaviGateway.Callback<T> callback(long request, Consumer<T> success, Consumer<String> failure) {
        return new NaviGateway.Callback<>() {
            boolean delivered;
            public void onSuccess(T value) { if (!delivered && current(request)) { delivered = true; success.accept(value); } }
            public void onFailure(String message) { if (!delivered && current(request)) { delivered = true; failure.accept(message); } }
        };
    }
    private interface Save { void run() throws IOException; }
    private boolean save(Save action) {
        try { action.run(); return true; }
        catch (IOException error) { notice = "로컬 저장에 실패했어요. 기존 기록은 보존했어요."; emit(); return false; }
    }
    private void emit() { lastParked = parked(); lastFresh = validEstimate(); if (listener != null) listener.run(); }
    @Override public void close() { if (closed) return; closed = true; ++sequence; listener = null; gateway.close(); }
}
