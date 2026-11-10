import java.nio.file.*;
import java.time.*;
import java.util.*;
import kr.routine.core.*;
import kr.routine.core.Model.*;
import kr.routine.presentation.*;

/** Service-level checks using real local persistence and the synchronous DEMO gateway. */
public final class PresentationChecks {
    private static int passed;
    private static Path evidence;
    public static void main(String[] args) throws Exception {
        evidence = Path.of(args[0]); Files.createDirectories(evidence);
        basicFlow(); activeRoutes(); failures(); persistence(); zonesAndResolution(); emptyAndTiedRecommendations(); scenarioHistory(); validation();
        System.out.println("PresentationChecks: " + passed + " assertions passed");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        passed++;
    }
    private static void error(String code, Runnable call) {
        try { call.run(); throw new AssertionError("Expected " + code); }
        catch (BackendException failure) { check(code.equals(failure.code()), "Expected " + code + ", got " + failure.code()); }
    }
    private static Map<String,String> args(String... pairs) {
        Map<String,String> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) values.put(pairs[index], pairs[index+1]);
        return values;
    }
    private static BackendSnapshot run(DemoBackend backend, String name, String... pairs) { return backend.command(name, args(pairs)); }
    private static void choose(DemoBackend backend) { run(backend, "selectDestination", "id", DemoFixtures.OFFICE.id(), "subId", DemoFixtures.OFFICE.subId()); }
    private static void basicFlow() throws Exception {
        Path path = evidence.resolve("basic.properties");
        try (DemoBackend backend = new DemoBackend(path, ZoneId.of("Asia/Seoul"))) {
            BackendSnapshot first = backend.snapshot();
            check(first.mode().equals("DEMO") && first.schemaVersion().equals("1.0"), "explicit demo schema");
            check(first.context().localDate().equals("2026-11-04") && first.context().day() == 3 && first.context().minute() == 480, "fixed Seoul demo context");
            check(!first.collectionEnabled() && first.counts().selections() == 0, "opt-in default");
            check(first.traffic().estimateMinutes() == null && first.traffic().baselineMinutes() == null, "unknown is null");
            check(!first.capabilities().liveNavigation() && !first.capabilities().mapGeometry() && !first.capabilities().etaPerPreference(), "real capabilities not invented");
            check(first.equals(backend.snapshot()) && !Files.exists(path), "snapshot is read-only");
            error("COLLECTION_DISABLED", () -> run(backend, "seedDemo"));
            run(backend, "setConsent", "enabled", "true");
            BackendSnapshot seeded = run(backend, "seedDemo");
            check(seeded.counts().rules() == 1 && seeded.counts().selections() == 3 && seeded.counts().estimates() == 3, "fixtures persisted");
            check(run(backend, "seedDemo").counts().selections() == 3, "seed is idempotent at same context");
            BackendSnapshot recommendation = run(backend, "recommend");
            check(recommendation.recommendation().source().equals("RULE") && recommendation.navigation().canConfirm(), "rule proposal ready");
            check(run(backend, "recommend", "historyOnly", "true").recommendation().samples() == 3, "history distinct days");
            BackendSnapshot traffic = run(backend, "refreshTraffic");
            check(traffic.traffic().estimateMinutes() == 28 && traffic.traffic().baselineMinutes() == 29 && traffic.traffic().delayMinutes() == -1, "computed traffic baseline");
            run(backend, "refreshTraffic"); run(backend, "refreshTraffic");
            check(backend.snapshot().counts().estimates() == 3, "refresh never records history");
            run(backend, "setDemoScenario", "traffic", "CONGESTED");
            BackendSnapshot congested = run(backend, "refreshTraffic");
            check(congested.traffic().estimateMinutes() == 44 && congested.traffic().delayMinutes() == 15 && congested.traffic().important(), "congestion uses advisor");
            run(backend, "setPreference", "value", "COMFORTABLE");
            check(backend.snapshot().traffic().estimateMinutes() == 44 && !backend.snapshot().capabilities().etaPerPreference(), "preference does not invent ETA changes");
            BackendSnapshot verified = run(backend, "confirm");
            check(verified.navigation().state().equals("VERIFIED") && !verified.navigation().canConfirm(), "confirmation verified");
            check(verified.counts().selections() == 4 && verified.counts().estimates() == 4 && verified.counts().navigationRequests() == 1, "confirmed estimates recorded once");
            error("TRIP_LOCKED", () -> run(backend, "confirm"));
            error("TRIP_LOCKED", () -> run(backend, "setPreference", "value", "RECOMMENDED"));
            error("TRIP_LOCKED", () -> choose(backend));
            error("TRIP_LOCKED", () -> run(backend, "setContext", "now", Long.toString(DemoFixtures.NOW), "zoneId", "UTC"));
            check(backend.snapshot().counts().navigationRequests() == 1, "no repeated navigation call");
            check(run(backend, "newDemoTrip").navigation().state().equals("IDLE"), "explicit new demo trip");
            choose(backend); run(backend, "dismiss");
            check(backend.snapshot().navigation().state().equals("DISMISSED") && backend.snapshot().counts().navigationRequests() == 1, "dismiss does not navigate");
            BackendSnapshot cleared = run(backend, "clearHistory");
            check(cleared.counts().selections() == 0 && cleared.counts().estimates() == 0 && cleared.counts().rules() == 1, "history clearing retains routine");
        }
    }
    private static void failures() throws Exception {
        try (DemoBackend backend = new DemoBackend(evidence.resolve("failures.properties"), ZoneId.of("Asia/Seoul"))) {
            choose(backend);
            run(backend, "setDemoScenario", "traffic", "MISSING");
            BackendSnapshot missing = run(backend, "refreshTraffic");
            check(missing.traffic().status().equals("UNAVAILABLE") && missing.traffic().estimateMinutes() == null && missing.traffic().observedAt() == null, "missing traffic null fields");
            run(backend, "setDemoScenario", "parked", "false");
            check(backend.snapshot().vehicle().state().equals("MOVING") && !backend.snapshot().navigation().canConfirm(), "moving mock state");
            error("VEHICLE_NOT_PARKED", () -> run(backend, "confirm"));
            check(backend.snapshot().counts().navigationRequests() == 0, "moving prevents request");
            run(backend, "setDemoScenario", "parked", "true", "failNext", "true");
            BackendSnapshot failed = run(backend, "confirm");
            check(failed.navigation().state().equals("UNCONFIRMED") && failed.counts().navigationRequests() == 1 && failed.counts().selections() == 0, "failed request is not recorded");
            check(run(backend, "refreshActiveRoute").navigation().state().equals("UNCONFIRMED"), "readback alone cannot unlock an uncertain issued trip");
            error("TRIP_LOCKED", () -> run(backend, "confirm"));
            run(backend, "newDemoTrip"); choose(backend);
            check(run(backend, "confirm").navigation().state().equals("VERIFIED"), "new trip clears failure latch");
            check(backend.snapshot().counts().selections() == 0, "consent off never records");
            backend.close(); backend.close();
            check(backend.snapshot().vehicle().state().equals("UNKNOWN") && !backend.snapshot().navigation().canConfirm(), "closed state unknown");
            error("CLOSED", () -> run(backend, "newDemoTrip"));
        }
    }
    private static void activeRoutes() throws Exception {
        for (ActiveRoute route : ActiveRoute.values()) {
            Path path = evidence.resolve("route-" + route + ".properties");
            try (DemoBackend backend = new DemoBackend(path, ZoneId.of("Asia/Seoul"))) {
                check(backend.snapshot().navigation().activeRoute() == ActiveRoute.INACTIVE,
                    "fresh simulated navigation is inactive on entry");
                run(backend, "setDemoScenario", "activeRoute", route.name());
                if (route != ActiveRoute.INACTIVE) {
                    String blocked = route == ActiveRoute.ACTIVE ? "ALREADY_GUIDING" : "ROUTE_UNAVAILABLE";
                    check(backend.snapshot().navigation().state().equals(blocked), "route visible before a destination is chosen: " + route);
                }
                choose(backend);
                BackendSnapshot snapshot = run(backend, "refreshActiveRoute");
                check(snapshot.navigation().activeRoute() == route && snapshot.counts().navigationRequests() == 0,
                    "reading " + route + " sends zero navigation requests");
                check(snapshot.equals(backend.snapshot()) && !Files.exists(path), "poll retains route and leaves storage untouched: " + route);
                if (route == ActiveRoute.INACTIVE) {
                    check(snapshot.navigation().canConfirm(), "inactive, parked and selected allows confirmation");
                    BackendSnapshot confirmed = run(backend, "confirm");
                    check(confirmed.navigation().state().equals("VERIFIED") && confirmed.navigation().activeRoute() == ActiveRoute.ACTIVE
                        && confirmed.counts().navigationRequests() == 1, "inactive confirm sends exactly one request");
                    run(backend, "setDemoScenario", "activeRoute", "INACTIVE");
                    BackendSnapshot after = run(backend, "refreshActiveRoute");
                    check(after.navigation().activeRoute() == ActiveRoute.INACTIVE && after.navigation().state().equals("VERIFIED")
                        && !after.navigation().canConfirm(), "external route ending does not reopen a verified trip");
                    error("TRIP_LOCKED", () -> run(backend, "confirm"));
                    check(backend.snapshot().counts().navigationRequests() == 1, "refresh and repeat do not reissue");
                } else {
                    String blocked = route == ActiveRoute.ACTIVE ? "ALREADY_GUIDING" : "ROUTE_UNAVAILABLE";
                    check(snapshot.navigation().state().equals(blocked) && snapshot.navigation().code().equals(blocked)
                        && !snapshot.navigation().canConfirm(), "blocked route retains explicit UI state: " + route);
                    error(blocked, () -> run(backend, "confirm"));
                    check(backend.snapshot().counts().navigationRequests() == 0 && backend.snapshot().counts().selections() == 0,
                        "blocked confirmation has zero issued requests or selections: " + route);
                    BackendSnapshot available = run(backend, "setDemoScenario", "activeRoute", "INACTIVE");
                    check(available.navigation().canConfirm() && available.counts().navigationRequests() == 0,
                        "clear block permits only a future explicit confirmation: " + route);
                }
            }
        }
    }
    private static void persistence() throws Exception {
        Path path = evidence.resolve("restart.properties");
        try (DemoBackend backend = new DemoBackend(path, ZoneId.of("Asia/Seoul"))) {
            run(backend, "setConsent", "enabled", "true"); run(backend, "seedDemo");
            run(backend, "setPreference", "value", "SHORT_TIME");
        }
        try (DemoBackend backend = new DemoBackend(path, ZoneId.of("Asia/Seoul"))) {
            check(backend.snapshot().counts().selections() == 3 && backend.snapshot().preference() == Preference.SHORT_TIME && backend.snapshot().collectionEnabled(), "restart restores local settings");
            choose(backend);
            Files.writeString(path, "\n# external edit\n", StandardOpenOption.APPEND);
            error("STORAGE_ERROR", () -> run(backend, "setPreference", "value", "COMFORTABLE"));
            check(backend.snapshot().preference() == Preference.SHORT_TIME && backend.snapshot().navigation().state().equals("READY"), "failed preference write rolls back");
            error("CONSENT_NOT_SAVED", () -> run(backend, "setConsent", "enabled", "false"));
            check(!backend.snapshot().collectionEnabled() && !backend.snapshot().navigation().canConfirm(), "failed opt-out closes collection");
            error("CONSENT_NOT_SAVED", () -> run(backend, "confirm"));
            error("CONSENT_NOT_SAVED", () -> run(backend, "seedDemo"));
            check(backend.snapshot().counts().selections() == 3 && backend.snapshot().counts().navigationRequests() == 0, "failed opt-out prevents hidden recording");
            check(Files.readString(path).contains("external edit"), "external file preserved");
        }
        Path confirmPath = evidence.resolve("confirm-storage-failure.properties");
        try (DemoBackend backend = new DemoBackend(confirmPath, ZoneId.of("Asia/Seoul"))) {
            run(backend, "setConsent", "enabled", "true"); choose(backend); run(backend, "refreshTraffic");
            Files.writeString(confirmPath, "\n# external edit\n", StandardOpenOption.APPEND);
            BackendSnapshot result = run(backend, "confirm");
            check(result.navigation().state().equals("VERIFIED") && result.notice().code().equals("STORAGE_ERROR"), "route result separate from storage failure");
            check(result.counts().selections() == 0 && result.counts().estimates() == 0, "failed storage has no fake records");
        }
    }
    private static void zonesAndResolution() throws Exception {
        Path zonePath = evidence.resolve("zone.properties");
        try (DemoBackend backend = new DemoBackend(zonePath, ZoneId.of("America/Los_Angeles"))) {
            check(backend.snapshot().context().localDate().equals("2026-11-03") && backend.snapshot().context().day() == 2 && backend.snapshot().context().minute() == 900, "constructor respects zone");
            run(backend, "setConsent", "enabled", "true"); run(backend, "seedDemo");
            check(run(backend, "recommend", "historyOnly", "true").recommendation().samples() == 3, "seed and history use supplied non-Seoul zone");
            long instant = Instant.parse("2026-11-04T15:30:00Z").toEpochMilli();
            run(backend, "setContext", "now", Long.toString(instant), "zoneId", "Asia/Seoul");
            check(backend.snapshot().context().day() == 4 && backend.snapshot().context().minute() == 30 && backend.snapshot().context().localDate().equals("2026-11-05"), "local midnight conversion");
            run(backend, "registerRoutine", "id", DemoFixtures.OTHER.id(), "subId", "0");
            check(run(backend, "recommend").recommendation().destination().equals(DemoFixtures.OTHER), "routine registered in current local time");
            run(backend, "setContext", "now", Long.toString(instant), "zoneId", "UTC");
            check(backend.snapshot().recommendation().destination() == null && run(backend, "recommend").recommendation().status().equals("UNAVAILABLE"), "time change invalidates proposal");
        }
        Path stalePath = evidence.resolve("stale-destination.properties");
        RoutineStore staleStore = new RoutineStore(stalePath);
        staleStore.putRule(new Rule(new Destination(DemoFixtures.OFFICE.id(), "오래된 이름", "오래된 주소", 0, 0, "0"), 3, 480));
        try (DemoBackend backend = new DemoBackend(stalePath, ZoneId.of("Asia/Seoul"))) {
            check(run(backend, "recommend").recommendation().destination().equals(DemoFixtures.OFFICE), "stored reference resolves current destination object");
        }
        Path unknownPath = evidence.resolve("unknown-destination.properties");
        new RoutineStore(unknownPath).putRule(new Rule(new Destination("unknown", "삭제된 장소", "", 0, 0, "0"), 3, 480));
        try (DemoBackend backend = new DemoBackend(unknownPath, ZoneId.of("Asia/Seoul"))) {
            BackendSnapshot result = run(backend, "recommend");
            check(result.recommendation().status().equals("UNRESOLVED") && !result.navigation().canConfirm(), "unresolved saved destination cannot be requested");
        }
    }
    private static void emptyAndTiedRecommendations() throws Exception {
        try (DemoBackend backend = new DemoBackend(evidence.resolve("cold.properties"), ZoneId.of("Asia/Seoul"))) {
            BackendSnapshot cold = run(backend, "recommend");
            check(cold.recommendation().status().equals("UNAVAILABLE") && cold.recommendation().destination() == null
                && cold.recommendation().samples() == 0 && !cold.navigation().canConfirm(), "cold recommendation preserves empty result");
            check(run(backend, "recommend", "historyOnly", "true").recommendation().samples() == 0, "empty history-only recommendation");
        }
        Path insufficient = evidence.resolve("insufficient.properties");
        RoutineStore shortStore = new RoutineStore(insufficient); shortStore.setCollectionEnabled(true);
        shortStore.recordSelection(new Selection(DemoFixtures.OFFICE, 3, 480, DemoFixtures.NOW - 7L*24*60*60*1000));
        try (DemoBackend backend = new DemoBackend(insufficient, ZoneId.of("Asia/Seoul"))) {
            BackendSnapshot result = run(backend, "recommend", "historyOnly", "true");
            check(result.recommendation().samples() == 1 && result.recommendation().destination() == null && !result.navigation().canConfirm(), "insufficient sample count retained");
        }
        Path tie = evidence.resolve("tie.properties");
        RoutineStore tieStore = new RoutineStore(tie); tieStore.setCollectionEnabled(true);
        tieStore.putRule(new Rule(DemoFixtures.OFFICE, 3, 479));
        tieStore.putRule(new Rule(DemoFixtures.OTHER, 3, 481));
        for (int week = 1; week <= 3; week++) {
            long at = DemoFixtures.NOW - week*7L*24*60*60*1000;
            tieStore.recordSelection(new Selection(DemoFixtures.OFFICE, 3, 480, at));
            tieStore.recordSelection(new Selection(DemoFixtures.OTHER, 3, 480, at));
        }
        try (DemoBackend backend = new DemoBackend(tie, ZoneId.of("Asia/Seoul"))) {
            BackendSnapshot ruleTie = run(backend, "recommend");
            check(ruleTie.recommendation().destination() == null && ruleTie.recommendation().reason().contains("여러 곳") && !ruleTie.navigation().canConfirm(), "rule tie remains unresolved");
            BackendSnapshot historyTie = run(backend, "recommend", "historyOnly", "true");
            check(historyTie.recommendation().destination() == null && historyTie.recommendation().samples() == 3 && !historyTie.navigation().canConfirm(), "history tie retains samples without proposal");
        }
    }
    private static void scenarioHistory() throws Exception {
        try (DemoBackend backend = new DemoBackend(evidence.resolve("scenario-history.properties"), ZoneId.of("Asia/Seoul"))) {
            run(backend, "setConsent", "enabled", "true");
            BackendSnapshot seeded = run(backend, "seedDemo", "days", "2");
            check(seeded.counts().rules() == 0 && seeded.counts().selections() == 2 && seeded.counts().estimates() == 2, "parameterized seed adds only chosen history");
            BackendSnapshot shortHistory = run(backend, "recommend", "historyOnly", "true");
            check(shortHistory.recommendation().holdType() == HoldType.NOT_ENOUGH && shortHistory.recommendation().samples() == 2
                && shortHistory.recommendation().candidates().isEmpty() && !shortHistory.navigation().canConfirm(), "two days produce structured shortage");
            run(backend, "clearHistory");
            run(backend, "seedDemo", "days", "3", "destinationIds", DemoFixtures.OFFICE.id() + "," + DemoFixtures.OTHER.id());
            BackendSnapshot tied = run(backend, "recommend", "historyOnly", "true");
            check(tied.recommendation().holdType() == HoldType.TIE && tied.recommendation().candidates().size() == 2, "history tie exposes both candidates");
            check(tied.recommendation().candidates().stream().allMatch(candidate -> candidate.samples() == 3)
                && tied.recommendation().destination() == null && !tied.navigation().canConfirm(), "tie has three distinct days per candidate and no implicit destination");
            check(run(backend, "setConsent", "enabled", "false").recommendation().candidates().isEmpty(), "turning consent off immediately hides candidate list");
            BackendSnapshot hidden = run(backend, "recommend", "historyOnly", "true");
            check(hidden.recommendation().holdType() == HoldType.NOT_ENOUGH && hidden.recommendation().samples() == 0, "opt-out excludes historical recommendation");
            check(hidden.counts().selections() == 6 && hidden.counts().estimates() == 6, "opt-out preserves stored rows");
            run(backend, "setConsent", "enabled", "true");
            check(run(backend, "recommend", "historyOnly", "true").recommendation().candidates().size() == 2, "explicit consent and recommend restore tie");
            check(run(backend, "clearHistory").recommendation().candidates().isEmpty(), "clearing histories invalidates cached tie");
            run(backend, "seedDemo", "days", "3", "estimateDays", "2");
            run(backend, "recommend", "historyOnly", "true");
            BackendSnapshot noCompare = run(backend, "refreshTraffic");
            check(noCompare.traffic().kind().equals("NO_COMPARE") && noCompare.traffic().history().size() == 2
                && noCompare.traffic().baselineMinutes() == null && noCompare.traffic().delayMinutes() == null, "insufficient comparison exposes actual two eligible rows");
            check(noCompare.traffic().history().equals(List.of(new BackendSnapshot.DayEstimate("2026-10-28", 28),
                new BackendSnapshot.DayEstimate("2026-10-21", 29))), "comparison list has supplied timezone dates and exact values newest first");
            run(backend, "seedDemo", "days", "3", "estimateDays", "3");
            BackendSnapshot enough = run(backend, "refreshTraffic");
            check(enough.traffic().kind().equals("CALM") && enough.traffic().history().size() == 3
                && enough.traffic().baselineMinutes() == 29 && enough.traffic().delayMinutes() == -1, "third eligible date computes median");
            check(backend.snapshot().counts().selections() == 3 && backend.snapshot().counts().estimates() == 3, "repeated seed is additive and idempotent");
            BackendSnapshot off = run(backend, "setConsent", "enabled", "false");
            check(off.traffic().history().isEmpty() && off.traffic().baselineMinutes() == null && off.traffic().delayMinutes() == null, "opt-out hides comparison and median immediately");
            choose(backend);
            check(run(backend, "refreshTraffic").traffic().history().isEmpty(), "manual traffic after opt-out never re-exposes history");
            run(backend, "setConsent", "enabled", "true");
            check(run(backend, "refreshTraffic").traffic().history().size() == 3, "opt-in allows same stored comparison again");
            for (String scenario : List.of("STALE", "UNKNOWN_UNITS")) {
                check(run(backend, "setDemoScenario", "traffic", scenario).traffic().kind().equals("NOT_REQUESTED"), "changing scenario invalidates prior estimate");
                BackendSnapshot unavailable = run(backend, "refreshTraffic");
                check(unavailable.traffic().kind().equals(scenario) && unavailable.traffic().status().equals("STALE_OR_INVALID")
                    && unavailable.traffic().estimateMinutes() == null && unavailable.traffic().baselineMinutes() == null
                    && unavailable.traffic().history().isEmpty(), scenario + " is distinguished without fabricated measurements");
                check(unavailable.equals(run(backend, "refreshTraffic")), scenario + " is repeatable at fixed context");
            }
            run(backend, "setDemoScenario", "traffic", "MISSING");
            check(run(backend, "refreshTraffic").traffic().kind().equals("MISSING"), "missing is distinct from stale and unknown units");
            run(backend, "setDemoScenario", "traffic", "CONGESTED");
            check(run(backend, "refreshTraffic").traffic().kind().equals("LATE"), "large difference exposes late kind");
            check(backend.snapshot().counts().navigationRequests() == 0, "all five scenario workflows send zero navigation requests");
            BackendSnapshot beforeInvalid = backend.snapshot();
            for (String days : List.of("-1", "7", "1.5", "", " 2")) {
                error("INVALID_ARGUMENT", () -> run(backend, "seedDemo", "days", days));
                check(beforeInvalid.equals(backend.snapshot()), "invalid day count makes no partial change");
            }
            error("INVALID_ARGUMENT", () -> run(backend, "seedDemo", "days", "2", "estimateDays", "7"));
            error("INVALID_ARGUMENT", () -> run(backend, "seedDemo", "destinationIds", DemoFixtures.OFFICE.id() + ","));
            error("INVALID_ARGUMENT", () -> run(backend, "seedDemo", "destinationIds", DemoFixtures.OFFICE.id() + "," + DemoFixtures.OFFICE.id()));
            error("DESTINATION_NOT_FOUND", () -> run(backend, "seedDemo", "destinationIds", DemoFixtures.OFFICE.id() + ",missing"));
            check(beforeInvalid.equals(backend.snapshot()), "invalid later seed input preserves prior state");
            error("INVALID_ARGUMENT", () -> run(backend, "setDemoScenario", "traffic", "STALE", "parked", "invalid"));
            check(beforeInvalid.equals(backend.snapshot()), "invalid scenario combination has no partial traffic update");
            run(backend, "clearHistory");
            run(backend, "seedDemo", "days", "0", "estimateDays", "0", "destinationIds", DemoFixtures.OTHER.id());
            check(backend.snapshot().counts().selections() == 0 && backend.snapshot().counts().estimates() == 0, "zero days is a valid empty seed");
            run(backend, "seedDemo", "days", "6", "destinationIds", DemoFixtures.OTHER.id());
            BackendSnapshot other = run(backend, "recommend", "historyOnly", "true");
            check(other.recommendation().destination().equals(DemoFixtures.OTHER) && other.recommendation().samples() == 6, "configured destination and six week boundary usable");
        }
    }
    private static void validation() throws Exception {
        try (DemoBackend backend = new DemoBackend(evidence.resolve("validation.properties"), ZoneId.of("Asia/Seoul"))) {
            error("INVALID_COMMAND", () -> run(backend, "notAnOperation"));
            error("UNKNOWN_ARGUMENT", () -> run(backend, "confirm", "force", "true"));
            error("MISSING_ARGUMENT", () -> run(backend, "setContext", "now", "42"));
            error("INVALID_ARGUMENT", () -> run(backend, "setContext", "now", "no", "zoneId", "UTC"));
            error("INVALID_ARGUMENT", () -> run(backend, "setContext", "now", "42", "zoneId", "NOT_A_ZONE"));
            error("INVALID_ARGUMENT", () -> run(backend, "setConsent", "enabled", "yes"));
            error("INVALID_ARGUMENT", () -> run(backend, "setPreference", "value", "FAST"));
            error("MISSING_ARGUMENT", () -> run(backend, "selectDestination", "id", DemoFixtures.OFFICE.id()));
            error("DESTINATION_NOT_FOUND", () -> run(backend, "selectDestination", "id", "missing", "subId", "0"));
            error("INVALID_ARGUMENT", () -> run(backend, "recommend", "historyOnly", "1"));
            error("INVALID_ARGUMENT", () -> run(backend, "setDemoScenario", "traffic", "CONGESTED", "parked", "yes"));
            error("INVALID_ARGUMENT", () -> run(backend, "setDemoScenario", "parked", "false", "activeRoute", "unknown"));
            error("UNKNOWN_ARGUMENT", () -> run(backend, "refreshActiveRoute", "force", "true"));
            error("MISSING_ARGUMENT", () -> run(backend, "setDemoScenario"));
            error("NOT_READY", () -> run(backend, "refreshTraffic"));
            check(backend.snapshot().context().now() == DemoFixtures.NOW && backend.snapshot().context().zoneId().equals("Asia/Seoul"), "invalid context has no partial update");
            choose(backend); BackendSnapshot traffic = run(backend, "refreshTraffic");
            check(traffic.traffic().estimateMinutes() == 28 && traffic.traffic().baselineMinutes() == null && traffic.traffic().delayMinutes() == null, "missing baseline separate from valid estimate");
            check(backend.snapshot().vehicle().state().equals("PARKED"), "invalid multi-argument scenario has no partial update");
        }
    }
}
