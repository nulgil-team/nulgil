import java.nio.file.*;
import java.time.*;
import java.util.*;
import kr.routine.core.*;
import kr.routine.core.Model.*;

public final class HistoryCoreChecks {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final long NOW = DemoFixtures.NOW;
    private static final long WEEK = 7L * 24 * 60 * 60 * 1000;
    private static final RoutineRecommender RECOMMENDER = new RoutineRecommender();
    private static final TrafficAdvisor ADVISOR = new TrafficAdvisor();
    private static int passed;
    private static Path output;
    private interface Check { void run() throws Exception; }
    private static void check(String name, Check body) throws Exception {
        body.run(); passed++; System.out.println("PASS " + name);
    }
    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static RoutineStore store(String name) throws Exception {
        RoutineStore store = new RoutineStore(output.resolve(name + ".properties"));
        store.setCollectionEnabled(true);
        return store;
    }
    private static Recommendation recommend(RoutineStore store) {
        return RECOMMENDER.recommend(List.of(), store.selections(), NOW, ZONE);
    }
    private static Estimate eta(int minutes, long at) {
        return new Estimate(DemoFixtures.OFFICE.id(), DemoFixtures.ORIGIN, DemoFixtures.BASIS, minutes, at, "fixture", true);
    }
    private static List<Estimate> baseline() {
        return List.of(eta(28, NOW - WEEK), eta(29, NOW - 2 * WEEK), eta(30, NOW - 3 * WEEK));
    }
    private static void immutable(List<?> values) {
        try { values.clear(); throw new AssertionError("mutable contract list"); }
        catch (UnsupportedOperationException expected) {}
    }
    private static Estimate read(DemoNaviGateway gateway) {
        Estimate[] result = new Estimate[1];
        gateway.estimate(DemoFixtures.OFFICE, NOW, new NaviGateway.Callback<>() {
            public void onSuccess(Estimate value) { result[0] = value; }
            public void onFailure(String message) {}
        });
        return result[0];
    }
    public static void main(String[] args) throws Exception {
        output = Paths.get(args[0]); Files.createDirectories(output);
        check("two distinct days have structured NOT_ENOUGH hold", () -> {
            RoutineStore store = store("two-days");
            DemoFixtures.seed(store, NOW, ZONE, List.of(DemoFixtures.OFFICE), 2, 0);
            Recommendation found = recommend(store);
            expect(found.holdType() == HoldType.NOT_ENOUGH && found.samples() == 2, "two-day hold");
            expect(found.destination() == null && found.candidates().isEmpty(), "no tie candidates");
            expect(store.rules().isEmpty() && store.estimates().isEmpty(), "requested data only");
        });
        check("tied histories expose each destination and matching sample days", () -> {
            RoutineStore store = store("tie");
            DemoFixtures.seed(store, NOW, ZONE, DemoFixtures.destinations(), 3, 0);
            Recommendation found = recommend(store);
            expect(found.holdType() == HoldType.TIE && found.destination() == null, "tie hold");
            expect(found.candidates().equals(List.of(new TiedCandidate(DemoFixtures.OFFICE, 3),
                new TiedCandidate(DemoFixtures.OTHER, 3))), "each tied destination has 3 days");
            immutable(found.candidates());
        });
        check("two-day ties remain insufficient history", () -> {
            RoutineStore store = store("insufficient-tie");
            DemoFixtures.seed(store, NOW, ZONE, DemoFixtures.destinations(), 2, 0);
            expect(recommend(store).holdType() == HoldType.NOT_ENOUGH, "minimum precedes tie");
        });
        check("unique best history has no hold or tie candidates", () -> {
            RoutineStore store = store("winner");
            DemoFixtures.seed(store, NOW, ZONE, DemoFixtures.destinations(), 3, 0);
            DemoFixtures.seed(store, NOW, ZONE, List.of(DemoFixtures.OFFICE), 4, 0);
            Recommendation found = recommend(store);
            expect(found.destination().equals(DemoFixtures.OFFICE) && found.samples() == 4, "unique winner");
            expect(found.holdType() == HoldType.NONE && found.candidates().isEmpty(), "ready metadata");
        });
        check("registered tie reports zero observed samples", () -> {
            Recommendation found = RECOMMENDER.recommend(List.of(new Rule(DemoFixtures.OFFICE, 3, 470),
                new Rule(DemoFixtures.OTHER, 3, 490)), List.of(), NOW, ZONE);
            expect(found.holdType() == HoldType.TIE && found.candidates().size() == 2, "rule tie");
            expect(found.candidates().stream().allMatch(row -> row.samples() == 0), "not inferred history");
        });
        check("traffic history contains exact median source rows in descending local date", () -> {
            Advice advice = ADVISOR.advise(eta(44, NOW), baseline(), NOW, ZONE);
            expect(advice.kind() == TrafficKind.LATE && advice.delayMinutes() == 15, "median 29");
            expect(advice.history().equals(List.of(new DayEstimate(LocalDate.of(2026,10,28),28),
                new DayEstimate(LocalDate.of(2026,10,21),29), new DayEstimate(LocalDate.of(2026,10,14),30))), "exact rows");
            immutable(advice.history());
        });
        check("incomplete comparison exposes only qualifying two days", () -> {
            Advice advice = ADVISOR.advise(eta(44, NOW), baseline().subList(0,2), NOW, ZONE);
            expect(advice.kind() == TrafficKind.NO_COMPARE && advice.delayMinutes() == null, "held comparison");
            expect(advice.history().size() == 2, "two eligible rows");
        });
        check("latest sample on each date supplies both list and median", () -> {
            List<Estimate> rows = new ArrayList<>(baseline());
            rows.add(eta(60, NOW - WEEK + 60_000));
            Advice advice = ADVISOR.advise(eta(44, NOW), rows, NOW, ZONE);
            expect(advice.history().get(0).minutes() == 60 && advice.history().size() == 3, "latest daily value");
            expect(advice.delayMinutes() == 14, "median 30");
        });
        check("different origin basis source day time and destination stay out of disclosed history", () -> {
            List<Estimate> rows = new ArrayList<>(baseline());
            rows.add(new Estimate(DemoFixtures.OTHER.id(),DemoFixtures.ORIGIN,DemoFixtures.BASIS,99,NOW-4*WEEK,"fixture",true));
            rows.add(new Estimate(DemoFixtures.OFFICE.id(),"other",DemoFixtures.BASIS,99,NOW-4*WEEK,"fixture",true));
            rows.add(new Estimate(DemoFixtures.OFFICE.id(),DemoFixtures.ORIGIN,"ETA_OTHER",99,NOW-4*WEEK,"fixture",true));
            rows.add(new Estimate(DemoFixtures.OFFICE.id(),DemoFixtures.ORIGIN,DemoFixtures.BASIS,99,NOW-4*WEEK,"fixture",false));
            rows.add(eta(99,NOW-4*WEEK+86400000)); rows.add(eta(99,NOW-4*WEEK-3660000));
            rows.add(eta(99,NOW-60000)); rows.add(eta(99,NOW+WEEK)); rows.add(eta(99,NOW-7*WEEK));
            rows.add(eta(-1,NOW-4*WEEK));
            expect(ADVISOR.advise(eta(44,NOW),rows,NOW,ZONE).history()
                .equals(ADVISOR.advise(eta(44,NOW),baseline(),NOW,ZONE).history()), "only comparable dates");
        });
        check("unknown units and stale or missing current estimates disclose no comparison", () -> {
            expect(ADVISOR.advise(null,baseline(),NOW,ZONE).kind() == TrafficKind.MISSING, "missing kind");
            Advice stale = ADVISOR.advise(eta(44,NOW-120001),baseline(),NOW,ZONE);
            Advice future = ADVISOR.advise(eta(44,NOW+1),baseline(),NOW,ZONE);
            Advice units = ADVISOR.advise(eta(-1,NOW),baseline(),NOW,ZONE);
            expect(stale.kind()==TrafficKind.STALE && future.kind()==TrafficKind.STALE, "age kinds");
            expect(units.kind()==TrafficKind.UNKNOWN_UNITS, "units kind");
            expect(stale.history().isEmpty() && future.history().isEmpty() && units.history().isEmpty(), "withheld rows");
        });
        check("fresh boundary and calm status remain accurate", () -> {
            expect(ADVISOR.advise(eta(28,NOW),baseline(),NOW+120000,ZONE).kind()==TrafficKind.CALM, "inclusive freshness");
        });
        check("local dates use explicit zone even around UTC midnight", () -> {
            long now = LocalDateTime.parse("2026-11-04T09:00").atZone(ZONE).toInstant().toEpochMilli();
            Advice advice = ADVISOR.advise(eta(44,now),List.of(eta(28,now-WEEK-1800000),
                eta(60,now-WEEK+900000),eta(29,now-2*WEEK)),now,ZONE);
            expect(advice.history().size()==2 && advice.history().get(0).localDate().equals(LocalDate.of(2026,10,28)), "one KST date");
            expect(advice.history().get(0).minutes()==60, "latest crosses UTC date");
        });
        check("parameterized seed is idempotent and preserves existing rule", () -> {
            RoutineStore store = store("idempotent");
            Rule rule = new Rule(DemoFixtures.OTHER,1,600); store.putRule(rule);
            DemoFixtures.seed(store,NOW,ZONE,DemoFixtures.destinations(),3,2);
            DemoFixtures.seed(store,NOW,ZONE,DemoFixtures.destinations(),3,2);
            expect(store.rules().equals(List.of(rule)), "existing explicit rule");
            expect(store.selections().size()==6 && store.estimates().size()==4, "no duplicate seed");
        });
        check("zero seed counts add no records or rules", () -> {
            RoutineStore store = store("zero");
            DemoFixtures.seed(store,NOW,ZONE,List.of(DemoFixtures.OFFICE),0,0);
            expect(store.rules().isEmpty() && store.selections().isEmpty() && store.estimates().isEmpty(), "zero fixture");
        });
        check("invalid seed options never partially write", () -> {
            RoutineStore store = store("invalid");
            try { DemoFixtures.seed(store,NOW,ZONE,List.of(DemoFixtures.OFFICE),3,7); throw new AssertionError("accepted"); }
            catch (IllegalArgumentException expected) {}
            expect(store.rules().isEmpty() && store.selections().isEmpty() && store.estimates().isEmpty(), "no partial fixture");
        });
        check("failed seed persistence leaves memory and external file intact", () -> {
            RoutineStore store = store("external");
            Path path = output.resolve("external.properties");
            byte[] changed = "external edit".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(path,changed);
            try { DemoFixtures.seed(store,NOW,ZONE,DemoFixtures.destinations(),3,3); throw new AssertionError("accepted"); }
            catch (java.io.IOException expected) {}
            expect(store.rules().isEmpty() && store.selections().isEmpty() && store.estimates().isEmpty(), "atomic memory");
            expect(Arrays.equals(changed,Files.readAllBytes(path)), "external file preserved");
        });
        check("consent-off blocks every seed write", () -> {
            RoutineStore store = store("consent");
            store.setCollectionEnabled(false);
            try { DemoFixtures.seed(store,NOW,ZONE,DemoFixtures.destinations(),3,3); throw new AssertionError("accepted"); }
            catch (IllegalStateException expected) {}
            expect(store.selections().isEmpty() && store.estimates().isEmpty(), "no collected rows");
        });
        check("demo traffic scenarios use real advisor classification and clear prior scenario", () -> {
            DemoNaviGateway gateway = new DemoNaviGateway();
            gateway.connect(new NaviGateway.Callback<>() {
                public void onSuccess(Void value) {}
                public void onFailure(String message) { throw new AssertionError(message); }
            });
            Map<DemoNaviGateway.TrafficScenario,TrafficKind> expected = new LinkedHashMap<>();
            expected.put(DemoNaviGateway.TrafficScenario.STALE,TrafficKind.STALE);
            expected.put(DemoNaviGateway.TrafficScenario.UNKNOWN_UNITS,TrafficKind.UNKNOWN_UNITS);
            expected.put(DemoNaviGateway.TrafficScenario.MISSING,TrafficKind.MISSING);
            expected.put(DemoNaviGateway.TrafficScenario.CONGESTED,TrafficKind.LATE);
            expected.put(DemoNaviGateway.TrafficScenario.NORMAL,TrafficKind.CALM);
            for (var item : expected.entrySet()) {
                gateway.setTrafficScenario(item.getKey());
                expect(ADVISOR.advise(read(gateway),baseline(),NOW,ZONE).kind()==item.getValue(), "scenario "+item.getKey());
            }
            expect(gateway.startCount()==0, "scenario changes never start route");
            gateway.close();
        });
        System.out.println("RESULT " + passed + " passed, 0 failed");
    }
}
