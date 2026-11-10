import java.nio.file.*;
import java.time.*;
import java.util.*;
import kr.routine.core.*;
import kr.routine.core.Model.*;

public final class TimezoneChecks {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    static final Destination A = DemoFixtures.OFFICE;
    static final RoutineRecommender RECOMMENDER = new RoutineRecommender();
    static final TrafficAdvisor ADVISOR = new TrafficAdvisor();
    static int passed, failed;
    interface Check { void run() throws Exception; }
    static void check(String name, Check body) {
        try { body.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable e) { failed++; System.out.println("FAIL " + name + ": " + e); }
    }
    static void expect(boolean value, String message) { if(!value) throw new AssertionError(message); }
    static long at(String local, ZoneId zone) { return LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli(); }
    static long offset(String value) { return OffsetDateTime.parse(value).toInstant().toEpochMilli(); }
    static Selection visit(long time, ZoneId zone) {
        ZonedDateTime local = Instant.ofEpochMilli(time).atZone(zone);
        return new Selection(A,local.getDayOfWeek().getValue(),local.getHour()*60+local.getMinute(),time);
    }
    static List<Selection> visits(ZoneId zone, String... local) {
        List<Selection> result = new ArrayList<>();
        for(String time:local) result.add(visit(at(time,zone),zone));
        return result;
    }
    static Recommendation recommend(ZoneId zone, String now, List<Selection> history) {
        return RECOMMENDER.recommend(List.of(),history,at(now,zone),zone);
    }
    static void held(Recommendation result, int samples) {
        expect(result.destination()==null && result.samples()==samples,"expected held samples="+samples+", actual="+result);
    }
    static void learned(Recommendation result, int samples) {
        expect(A.equals(result.destination()) && result.samples()==samples,"expected learned samples="+samples+", actual="+result);
    }
    static Estimate eta(int minutes,long time) { return new Estimate(A.id(),"origin","ETA_RECOMMENDED",minutes,time,"fixture",true); }
    static List<Estimate> traffic(List<Selection> history) {
        List<Estimate> result = new ArrayList<>();
        for(Selection row:history) result.add(eta(30,row.selectedAt()));
        return result;
    }
    public static void main(String[] args) throws Exception {
        Path out=Paths.get(args[0]); Files.createDirectories(out);
        List<Selection> seoulSplit=visits(SEOUL,"2026-10-28T08:30","2026-10-28T09:15","2026-10-21T09:00");
        List<Selection> newYorkSplit=visits(NEW_YORK,"2026-10-28T19:30","2026-10-28T20:15","2026-10-21T20:00");
        List<Selection> weekly=visits(SEOUL,"2026-10-28T09:00","2026-10-21T09:00","2026-10-14T09:00");
        check("KST local date crossing UTC midnight counts once",()->held(recommend(SEOUL,"2026-11-04T09:00",seoulSplit),2));
        check("negative offset local date crossing UTC midnight counts once",()->held(recommend(NEW_YORK,"2026-11-04T20:00",newYorkSplit),2));
        check("three separate local weekly dates still recommend",()->learned(recommend(SEOUL,"2026-11-04T09:00",weekly),3));
        check("timestamp weekday overrides incorrect saved weekday",()->{
            List<Selection> history=new ArrayList<>();
            for(Selection row:visits(SEOUL,"2026-10-27T09:00","2026-10-20T09:00","2026-10-13T09:00"))history.add(new Selection(A,3,540,row.selectedAt()));
            held(recommend(SEOUL,"2026-11-04T09:00",history),0);
        });
        check("timestamp minute overrides incorrect saved minute",()->{
            List<Selection> history=new ArrayList<>();
            for(Selection row:visits(SEOUL,"2026-10-28T06:00","2026-10-21T06:00","2026-10-14T06:00"))history.add(new Selection(A,3,540,row.selectedAt()));
            held(recommend(SEOUL,"2026-11-04T09:00",history),0);
        });
        check("valid timestamps survive stale stored weekday and minute",()->{
            List<Selection> history=new ArrayList<>();
            for(Selection row:weekly)history.add(new Selection(A,1,0,row.selectedAt()));
            learned(recommend(SEOUL,"2026-11-04T09:00",history),3);
        });
        check("one hour local boundary is inclusive",()->learned(recommend(SEOUL,"2026-11-04T09:00",visits(SEOUL,"2026-10-28T08:00","2026-10-21T10:00","2026-10-14T08:00")),3));
        check("sixty one minutes is outside local window",()->held(recommend(SEOUL,"2026-11-04T09:00",visits(SEOUL,"2026-10-28T07:59","2026-10-21T10:01","2026-10-14T07:59")),0));
        check("midnight does not expand into adjacent weekday",()->held(recommend(SEOUL,"2026-11-04T00:10",visits(SEOUL,"2026-10-27T23:50","2026-10-20T23:50","2026-10-13T23:50")),0));
        check("Sunday Monday rule boundary stays separate",()->{
            Recommendation result=RECOMMENDER.recommend(List.of(new Rule(A,7,1430)),List.of(),at("2026-11-02T00:10",SEOUL),SEOUL);
            held(result,0);
        });
        check("rules are local weekly schedules in requested zone",()->{
            long now=at("2026-11-04T09:00",SEOUL);
            List<Rule> rules=List.of(new Rule(A,3,540));
            expect(A.equals(RECOMMENDER.recommend(rules,List.of(),now,SEOUL).destination()),"Seoul rule applies");
            held(RECOMMENDER.recommend(rules,List.of(),now,NEW_YORK),0);
        });
        check("zone change recalculates both now and saved instants",()->{
            long now=at("2026-11-04T09:00",SEOUL);
            learned(RECOMMENDER.recommend(List.of(),weekly,now,SEOUL),3);
            learned(RECOMMENDER.recommend(List.of(),weekly,now,NEW_YORK),3);
        });
        check("system default time zone cannot change recommendation or traffic",()->{
            TimeZone previous=TimeZone.getDefault();
            try {
                long now=at("2026-11-04T09:00",SEOUL);
                Recommendation expected=RECOMMENDER.recommend(List.of(),weekly,now,SEOUL);
                Advice advice=ADVISOR.advise(eta(45,now),traffic(weekly),now,SEOUL);
                for(String hostZone:List.of("UTC","America/Los_Angeles","Pacific/Kiritimati")) {
                    TimeZone.setDefault(TimeZone.getTimeZone(hostZone));
                    expect(expected.equals(RECOMMENDER.recommend(List.of(),weekly,now,SEOUL)),"host zone changed recommendation");
                    expect(advice.equals(ADVISOR.advise(eta(45,now),traffic(weekly),now,SEOUL)),"host zone changed traffic");
                }
            } finally { TimeZone.setDefault(previous); }
        });
        check("DST repeated hour is one date",()->{
            List<Selection> history=List.of(visit(offset("2026-11-01T01:15:00-04:00"),NEW_YORK),visit(offset("2026-11-01T01:45:00-05:00"),NEW_YORK),visit(at("2026-10-25T01:30",NEW_YORK),NEW_YORK));
            held(recommend(NEW_YORK,"2026-11-08T01:30",history),2);
        });
        check("DST fall weekly clock times remain comparable",()->{
            List<Selection> history=new ArrayList<>(visits(NEW_YORK,"2026-11-08T01:30","2026-10-25T01:30"));
            history.add(visit(offset("2026-11-01T01:30:00-04:00"),NEW_YORK));
            history.add(visit(offset("2026-11-01T01:30:00-05:00"),NEW_YORK));
            learned(recommend(NEW_YORK,"2026-11-15T01:30",history),3);
        });
        check("DST spring valid instants compare local clock minutes",()->{
            List<Selection> history=new ArrayList<>(visits(NEW_YORK,"2026-03-01T03:00","2026-02-22T03:00"));
            history.add(visit(offset("2026-03-08T01:59:00-05:00"),NEW_YORK));
            held(recommend(NEW_YORK,"2026-03-15T03:00",history),2);
            history.add(visit(offset("2026-03-08T03:01:00-04:00"),NEW_YORK));
            learned(recommend(NEW_YORK,"2026-03-15T03:00",history),3);
        });
        check("six week elapsed age includes boundary and excludes older",()->{
            long now=at("2026-11-04T09:00",SEOUL), boundary=now-RoutineRecommender.MAX_AGE_MILLIS;
            List<Selection> history=new ArrayList<>(weekly.subList(0,2));
            history.add(visit(boundary,SEOUL));
            learned(RECOMMENDER.recommend(List.of(),history,now,SEOUL),3);
            history.set(2,visit(boundary-1,SEOUL));
            held(RECOMMENDER.recommend(List.of(),history,now,SEOUL),2);
        });
        check("future timestamps and old dates cannot satisfy minimum",()->{
            List<Selection> history=new ArrayList<>(weekly.subList(0,2));
            history.add(visit(at("2026-11-11T09:00",SEOUL),SEOUL));
            history.add(visit(at("2026-09-16T09:00",SEOUL),SEOUL));
            held(recommend(SEOUL,"2026-11-04T09:00",history),2);
        });
        check("traffic uses same KST local date deduplication",()->{
            long now=at("2026-11-04T09:00",SEOUL);
            expect(ADVISOR.advise(eta(45,now),traffic(seoulSplit),now,SEOUL).delayMinutes()==null,"two local dates cannot form baseline");
        });
        check("traffic uses same negative offset date deduplication",()->{
            long now=at("2026-11-04T20:00",NEW_YORK);
            expect(ADVISOR.advise(eta(45,now),traffic(newYorkSplit),now,NEW_YORK).delayMinutes()==null,"two local dates cannot form baseline");
        });
        check("traffic DST repeated hour chooses latest observation of date",()->{
            long now=at("2026-11-08T01:30",NEW_YORK);
            List<Estimate> history=List.of(eta(100,offset("2026-11-01T01:15:00-04:00")),eta(30,offset("2026-11-01T01:45:00-05:00")),eta(28,at("2026-10-25T01:30",NEW_YORK)),eta(32,at("2026-10-18T01:30",NEW_YORK)));
            expect(Integer.valueOf(15).equals(ADVISOR.advise(eta(45,now),history,now,NEW_YORK).delayMinutes()),"latest per-date median should be 30");
        });
        check("traffic spring gap uses valid local times",()->{
            long now=at("2026-03-15T03:00",NEW_YORK);
            List<Estimate> history=new ArrayList<>(List.of(eta(30,at("2026-03-01T03:00",NEW_YORK)),eta(30,at("2026-02-22T03:00",NEW_YORK)),eta(30,offset("2026-03-08T01:59:00-05:00"))));
            expect(ADVISOR.advise(eta(45,now),history,now,NEW_YORK).delayMinutes()==null,"01:59 is outside local window");
            history.add(eta(30,offset("2026-03-08T03:01:00-04:00")));
            expect(Integer.valueOf(15).equals(ADVISOR.advise(eta(45,now),history,now,NEW_YORK).delayMinutes()),"03:01 matches");
        });
        check("traffic stale future and same-day samples stay excluded",()->{
            long now=at("2026-11-04T09:00",SEOUL);
            List<Estimate> history=new ArrayList<>(traffic(weekly.subList(0,2)));
            history.add(eta(30,now-60000)); history.add(eta(30,now+60000)); history.add(eta(30,now-7L*7*86400000));
            expect(ADVISOR.advise(eta(45,now),history,now,SEOUL).delayMinutes()==null,"ineligible dates excluded");
            expect(ADVISOR.advise(eta(45,now-120001),traffic(weekly),now,SEOUL).delayMinutes()==null,"stale current withheld");
            expect(ADVISOR.advise(eta(45,now+1),traffic(weekly),now,SEOUL).delayMinutes()==null,"future current withheld");
        });
        check("demo fixtures support explicit zone across DST",()->{
            long now=at("2026-11-08T08:00",NEW_YORK);
            RoutineStore store=new RoutineStore(out.resolve("new-york-fixture.properties"));
            store.setCollectionEnabled(true); DemoFixtures.seed(store,now,NEW_YORK);
            learned(RECOMMENDER.recommend(List.of(),store.selections(),now,NEW_YORK),3);
            for(Selection row:store.selections()) {
                ZonedDateTime local=Instant.ofEpochMilli(row.selectedAt()).atZone(NEW_YORK);
                expect(local.getDayOfWeek().getValue()==7 && local.getHour()==8 && local.getMinute()==0,"fixture drifted with DST");
                expect(row.day()==7 && row.minute()==480,"stored metadata follows actual local instant");
            }
        });
        check("legacy fixture rejects mismatched Korea clock input",()->{
            RoutineStore store=new RoutineStore(out.resolve("legacy-fixture.properties")); store.setCollectionEnabled(true);
            boolean rejected=false;
            try { DemoFixtures.seed(store,at("2026-11-04T09:00",SEOUL),2,540); } catch(IllegalArgumentException expected) { rejected=true; }
            expect(rejected && store.rules().isEmpty() && store.selections().isEmpty(),"invalid fixture input must not write records");
        });
        System.out.println("RESULT "+passed+" passed, "+failed+" failed");
        if(failed>0) System.exit(1);
    }
}
