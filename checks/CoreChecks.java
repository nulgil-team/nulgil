import kr.routine.core.*;
import kr.routine.core.Model.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

public final class CoreChecks {
    static final Destination A = new Destination("36khp2hdj2cfcm5nqhe3", "소프트웨어드림센터", "경기도 성남시 수정구 창업로40번길 6", 37.41275833, 127.09481944, "0");
    static final Destination B = new Destination("36khp37hh8gqcm5nqhe5", "강남 거래처", "서울시 강남구 남부순환로 2621", 37.48544722, 127.03636666, "0");
    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    static final long NOW = ZonedDateTime.of(2026, 11, 4, 8, 0, 0, 0, ZONE).toInstant().toEpochMilli();
    static final long WEEK = 7L * 24 * 60 * 60 * 1000;
    static Path out;
    static int passed, failed;
    interface Check { void run() throws Exception; }
    static void check(String name, Check body) {
        try { body.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable failure) { failed++; System.out.println("FAIL " + name + ": " + failure); }
    }
    static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static RoutineStore store(String name) throws Exception { return new RoutineStore(out.resolve(name + ".properties")); }
    static List<Selection> visits(Destination destination, int day, int minute, int count) {
        List<Selection> result = new ArrayList<>();
        ZonedDateTime at = Instant.ofEpochMilli(NOW).atZone(ZONE).with(DayOfWeek.of(day))
            .withHour(minute / 60).withMinute(minute % 60);
        for (int i = 1; i <= count; i++) result.add(new Selection(destination, day, minute, at.minusWeeks(i).toInstant().toEpochMilli()));
        return result;
    }
    static Estimate eta(String destination, String origin, String basis, int minutes, long time, boolean demo) {
        return new Estimate(destination, origin, basis, minutes, time, "fixture", demo);
    }
    static List<Estimate> baseline() {
        return List.of(eta(A.id(), "origin", "ETA_RECOMMENDED", 28, NOW-WEEK, false),
            eta(A.id(), "origin", "ETA_RECOMMENDED", 29, NOW-2*WEEK, false),
            eta(A.id(), "origin", "ETA_RECOMMENDED", 30, NOW-3*WEEK, false));
    }
    static Estimate current() { return eta(A.id(), "origin", "ETA_RECOMMENDED", 45, NOW, false); }

    public static void main(String[] args) throws Exception {
        out = Paths.get(args[0]); Files.createDirectories(out);
        RoutineRecommender recommender = new RoutineRecommender();
        TrafficAdvisor advisor = new TrafficAdvisor();
        check("recommendation empty history holds", () -> expect(recommender.recommend(List.of(),List.of(),NOW,ZONE).destination()==null,"should hold"));
        check("registered routine works before learning", () -> expect(A.equals(recommender.recommend(List.of(new Rule(A,3,480)),List.of(),NOW,ZONE).destination()),"registered candidate"));
        check("three weekly observations recommend", () -> expect(A.equals(recommender.recommend(List.of(),visits(A,3,475,3),NOW,ZONE).destination()),"learned candidate"));
        check("fewer than three observations hold", () -> expect(recommender.recommend(List.of(),visits(A,3,480,2),NOW,ZONE).destination()==null,"too few"));
        check("wrong weekday excluded", () -> expect(recommender.recommend(List.of(),visits(A,2,480,3),NOW,ZONE).destination()==null,"wrong day"));
        check("outside one hour excluded", () -> expect(recommender.recommend(List.of(),visits(A,3,360,3),NOW,ZONE).destination()==null,"wrong time"));
        check("tied learned destinations hold", () -> { List<Selection> all=new ArrayList<>(visits(A,3,480,3)); all.addAll(visits(B,3,480,3)); expect(recommender.recommend(List.of(),all,NOW,ZONE).destination()==null,"tie" ); });
        check("conflicting registered routines hold", () -> expect(recommender.recommend(List.of(new Rule(A,3,480),new Rule(B,3,480)),List.of(),NOW,ZONE).destination()==null,"registered tie"));
        check("old and future observations excluded", () -> { List<Selection> all=List.of(new Selection(A,3,480,NOW-10*WEEK),new Selection(A,3,480,NOW-11*WEEK),new Selection(A,3,480,NOW+WEEK)); expect(recommender.recommend(List.of(),all,NOW,ZONE).destination()==null,"age guard"); });
        check("median matched estimate comparison", () -> { Advice a=advisor.advise(current(),baseline(),NOW,ZONE); expect(Integer.valueOf(16).equals(a.delayMinutes()) && a.important(),"expected 16 minute delay"); });
        check("small changes do not raise alert", () -> { Advice a=advisor.advise(eta(A.id(),"origin","ETA_RECOMMENDED",32,NOW,false),baseline(),NOW,ZONE); expect(!a.important(),"3 minute change"); });
        check("stale current estimate withheld", () -> expect(advisor.advise(current(),baseline(),NOW+120001,ZONE).delayMinutes()==null,"stale"));
        check("unknown current units withheld", () -> expect(advisor.advise(eta(A.id(),"origin","ETA_RECOMMENDED",-1,NOW,false),baseline(),NOW,ZONE).delayMinutes()==null,"unit unknown"));
        check("different route options excluded", () -> expect(advisor.advise(eta(A.id(),"origin","ETA_COMFORTABLE",45,NOW,false),baseline(),NOW,ZONE).delayMinutes()==null,"different option"));
        check("different origin excluded", () -> expect(advisor.advise(eta(A.id(),"another","ETA_RECOMMENDED",45,NOW,false),baseline(),NOW,ZONE).delayMinutes()==null,"different origin"));
        check("simulated history cannot compare with live", () -> expect(advisor.advise(eta(A.id(),"origin","ETA_RECOMMENDED",45,NOW,true),baseline(),NOW,ZONE).delayMinutes()==null,"demo separation"));
        check("actual durations cannot compare with estimates", () -> expect(advisor.advise(eta(A.id(),"origin","ACTUAL_RECOMMENDED",45,NOW,false),baseline(),NOW,ZONE).delayMinutes()==null,"measurement kind"));
        check("traffic fewer than three samples holds", () -> expect(advisor.advise(current(),baseline().subList(0,2),NOW,ZONE).delayMinutes()==null,"too few traffic"));
        check("traffic wrong day excluded", () -> { List<Estimate> h=new ArrayList<>(); for(Estimate e:baseline()) h.add(eta(e.destinationId(),e.originKey(),e.basis(),e.minutes(),e.observedAt()-86400000,false)); expect(advisor.advise(current(),h,NOW,ZONE).delayMinutes()==null,"weekday mismatch"); });
        check("traffic wrong time excluded", () -> { List<Estimate> h=new ArrayList<>(); for(Estimate e:baseline()) h.add(eta(e.destinationId(),e.originKey(),e.basis(),e.minutes(),e.observedAt()-7200000,false)); expect(advisor.advise(current(),h,NOW,ZONE).delayMinutes()==null,"time mismatch"); });
        check("store persistence and consent", () -> { RoutineStore s=store("roundtrip"); expect(!s.collectionEnabled(),"default consent off"); s.recordSelection(new Selection(A,3,480,NOW)); expect(s.selections().isEmpty(),"off no tracking"); s.setCollectionEnabled(true); s.putRule(new Rule(A,3,480)); s.setPreference(Preference.COMFORTABLE); s.recordSelection(new Selection(A,3,480,NOW)); s.recordEstimate(current()); RoutineStore read=new RoutineStore(out.resolve("roundtrip.properties")); expect(read.collectionEnabled() && read.preference()==Preference.COMFORTABLE && read.rules().size()==1 && read.selections().size()==1 && read.estimates().size()==1,"roundtrip"); });
        check("store history capped and clear preserves rules", () -> { RoutineStore s=store("bounded"); s.setCollectionEnabled(true); s.putRule(new Rule(A,3,480)); for(int i=0;i<205;i++) s.recordSelection(new Selection(A,3,480,NOW-i)); expect(s.selections().size()==200,"cap"); s.clearHistory(); expect(s.selections().isEmpty() && s.rules().size()==1,"clear only observations"); });
        check("editing exact routine slot replaces its destination", () -> { RoutineStore s=store("rule-replacement");s.putRule(new Rule(A,3,480));s.putRule(new Rule(B,3,480));RoutineStore read=new RoutineStore(out.resolve("rule-replacement.properties"));expect(read.rules().size()==1&&B.equals(recommender.recommend(read.rules(),List.of(),NOW,ZONE).destination()),"same weekday/time must replace after reload"); });
        check("corrupt store fails without overwrite", () -> { Path p=out.resolve("broken.properties"); byte[] original="not-a-routine-store".getBytes(StandardCharsets.UTF_8); Files.write(p,original,StandardOpenOption.CREATE_NEW); boolean rejected=false; try { new RoutineStore(p); } catch(java.io.IOException expected) {rejected=true;} expect(rejected && Arrays.equals(original,Files.readAllBytes(p)),"preserve corruption"); });
        check("controller parked check blocks", () -> { TrackingGateway g=new TrackingGateway(); g.parked=false; RoutineStore s=store("parked"); DepartureController c=new DepartureController(g,s); c.propose(A,Preference.RECOMMENDED); c.confirm(3,480,NOW,()->{}); expect(g.calls==0 && c.state()!=DepartureController.State.VERIFIED,"moving guard"); });
        check("controller confirmation records once", () -> { TrackingGateway g=new TrackingGateway(); RoutineStore s=store("confirm"); s.setCollectionEnabled(true); DepartureController c=new DepartureController(g,s); c.propose(A,Preference.COMFORTABLE); c.confirm(3,480,NOW,()->{}); c.confirm(3,480,NOW,()->{}); expect(g.calls==1 && s.selections().size()==1 && c.state()==DepartureController.State.VERIFIED,"single request and observation"); });
        check("duplicate callback cannot duplicate observation", () -> { TrackingGateway g=new TrackingGateway(); g.doubleCallback=true; RoutineStore s=store("duplicate-callback"); s.setCollectionEnabled(true); DepartureController c=new DepartureController(g,s); c.propose(A,Preference.RECOMMENDED); c.confirm(3,480,NOW,()->{}); expect(s.selections().size()==1,"single terminal callback"); });
        check("uncertain route cannot retry by reproposal", () -> { TrackingGateway g=new TrackingGateway(); g.fail=true; RoutineStore s=store("uncertain"); s.setCollectionEnabled(true); DepartureController c=new DepartureController(g,s); c.propose(A,Preference.RECOMMENDED); c.confirm(3,480,NOW,()->{}); c.propose(B,Preference.SHORT_TIME); c.confirm(3,480,NOW,()->{}); expect(g.calls==1 && s.selections().isEmpty() && c.state()==DepartureController.State.UNCONFIRMED,"unknown blocked"); });
        check("dismiss never starts navigation", () -> { TrackingGateway g=new TrackingGateway(); DepartureController c=new DepartureController(g,store("dismiss")); c.propose(A,Preference.RECOMMENDED); c.dismiss(); c.confirm(3,480,NOW,()->{}); expect(g.calls==0,"dismiss should not route"); });
        check("gateway failure is fail closed", () -> { TrackingGateway g=new TrackingGateway(); g.throwParked=true; DepartureController c=new DepartureController(g,store("exception")); c.propose(A,Preference.RECOMMENDED); c.confirm(3,480,NOW,()->{}); expect(g.calls==0,"exception should not route"); });
        check("same-day repeat cannot teach a routine", () -> { List<Selection> h=List.of(new Selection(A,3,480,NOW-WEEK),new Selection(A,3,481,NOW-WEEK+60000),new Selection(A,3,482,NOW-WEEK+120000)); expect(recommender.recommend(List.of(),h,NOW,ZONE).destination()==null,"three separate dates required"); });
        check("registered nearest time is selected", () -> expect(A.equals(recommender.recommend(List.of(new Rule(A,3,480),new Rule(B,3,530)),List.of(),NOW+5*60000,ZONE).destination()),"closest rule"));
        check("same-day refreshes cannot inflate traffic baseline", () -> { List<Estimate> h=List.of(eta(A.id(),"origin","ETA_RECOMMENDED",28,NOW-WEEK,false),eta(A.id(),"origin","ETA_RECOMMENDED",29,NOW-WEEK+60000,false),eta(A.id(),"origin","ETA_RECOMMENDED",30,NOW-WEEK+120000,false)); expect(advisor.advise(current(),h,NOW,ZONE).delayMinutes()==null,"three dates required"); });
        check("current date excluded from traffic baseline", () -> { List<Estimate> h=new ArrayList<>(baseline().subList(0,2));h.add(eta(A.id(),"origin","ETA_RECOMMENDED",20,NOW-60000,false));expect(advisor.advise(current(),h,NOW,ZONE).delayMinutes()==null,"current date is not past baseline"); });
        check("future current estimate withheld", () -> expect(advisor.advise(eta(A.id(),"origin","ETA_RECOMMENDED",45,NOW+1000,false),baseline(),NOW,ZONE).delayMinutes()==null,"future current"));
        check("other destination is not a baseline", () -> expect(advisor.advise(eta(B.id(),"origin","ETA_RECOMMENDED",45,NOW,false),baseline(),NOW,ZONE).delayMinutes()==null,"destination mismatch"));
        check("delay requires both absolute and relative threshold", () -> { List<Estimate> h=List.of(eta(A.id(),"origin","ETA_RECOMMENDED",100,NOW-WEEK,false),eta(A.id(),"origin","ETA_RECOMMENDED",100,NOW-2*WEEK,false),eta(A.id(),"origin","ETA_RECOMMENDED",100,NOW-3*WEEK,false));Advice a=advisor.advise(eta(A.id(),"origin","ETA_RECOMMENDED",105,NOW,false),h,NOW,ZONE);expect(Integer.valueOf(5).equals(a.delayMinutes())&&!a.important(),"5 percent is not meaningful"); });
        check("store duplicate selection deduplicates", () -> { RoutineStore s=store("store-duplicate");s.setCollectionEnabled(true);Selection row=new Selection(A,3,480,NOW);s.recordSelection(row);s.recordSelection(row);expect(s.selections().size()==1,"same confirmation stored once"); });
        check("store detects external edits before writing", () -> { RoutineStore s=store("external-edit");s.setCollectionEnabled(true);Path p=out.resolve("external-edit.properties");byte[] edit="externally changed".getBytes(StandardCharsets.UTF_8);Files.write(p,edit);boolean rejected=false;try{s.setPreference(Preference.COMFORTABLE);}catch(java.io.IOException expected){rejected=true;}expect(rejected&&Arrays.equals(edit,Files.readAllBytes(p)),"external edit retained"); });
        check("unverified callback records no selection", () -> { TrackingGateway g=new TrackingGateway();g.unverified=true;RoutineStore s=store("unverified");s.setCollectionEnabled(true);DepartureController c=new DepartureController(g,s);c.propose(A,Preference.RECOMMENDED);c.confirm(3,480,NOW,()->{});expect(c.state()==DepartureController.State.UNCONFIRMED&&s.selections().isEmpty(),"no success without readback"); });
        check("late success after failure cannot change result", () -> { TrackingGateway g=new TrackingGateway();g.fail=true;g.lateSuccess=true;RoutineStore s=store("late-success");s.setCollectionEnabled(true);DepartureController c=new DepartureController(g,s);c.propose(A,Preference.RECOMMENDED);c.confirm(3,480,NOW,()->{});expect(c.state()==DepartureController.State.UNCONFIRMED&&s.selections().isEmpty(),"terminal failure retained"); });
        check("fixtures require collection consent", () -> { RoutineStore s=store("fixture-no-consent");boolean rejected=false;try{DemoFixtures.seed(s,NOW,3,480);}catch(IllegalStateException expected){rejected=true;}expect(rejected&&s.rules().isEmpty()&&s.selections().isEmpty(),"explicit demo opt in"); });
        check("demo integration computes learned destination and delay", () -> { RoutineStore s=store("demo-integration");s.setCollectionEnabled(true);DemoFixtures.seed(s,NOW,3,480);expect(A.equals(recommender.recommend(List.of(),s.selections(),NOW,ZONE).destination()),"fixture learning");DemoNaviGateway g=new DemoNaviGateway();g.connect(noFailure());g.setCongested(true);Estimate[] result={null};g.estimate(A,NOW,new NaviGateway.Callback<Estimate>(){public void onSuccess(Estimate e){result[0]=e;}public void onFailure(String m){throw new AssertionError(m);}});Advice a=advisor.advise(result[0],s.estimates(),NOW,ZONE);expect(Integer.valueOf(15).equals(a.delayMinutes())&&a.important(),"demo computed 15 minute delay");DepartureController c=new DepartureController(g,s);c.propose(A,Preference.COMFORTABLE);c.confirm(3,480,NOW,()->{});expect(c.state()==DepartureController.State.VERIFIED&&g.startCount()==1&&s.selections().size()==4,"confirmed demo flow"); });
        check("demo preserves its active route", () -> {
            DemoNaviGateway g = new DemoNaviGateway();
            g.connect(noFailure());
            List<RouteResult> results = new ArrayList<>();
            NaviGateway.Callback<RouteResult> collector = new NaviGateway.Callback<>() {
                public void onSuccess(RouteResult result) { results.add(result); }
                public void onFailure(String error) { throw new AssertionError(error); }
            };
            g.start(A, Preference.RECOMMENDED, collector);
            g.start(B, Preference.SHORT_TIME, collector);
            expect(results.size() == 2 && results.get(0).verified() && results.get(1).blockedBy() == ActiveRoute.ACTIVE
                && !results.get(1).verified() && g.startCount() == 1, "active route retained");
        });
        check("demo missing traffic is explicit failure", () -> { DemoNaviGateway g=new DemoNaviGateway();g.connect(noFailure());g.setMissingTraffic(true);boolean[] failed={false};g.estimate(A,NOW,new NaviGateway.Callback<Estimate>(){public void onSuccess(Estimate e){throw new AssertionError("invented traffic");}public void onFailure(String m){failed[0]=true;}});expect(failed[0],"missing traffic not estimated"); });
        System.out.println("RESULT " + passed + " passed, " + failed + " failed");
        if(failed>0) System.exit(1);
    }

    static NaviGateway.Callback<Void> noFailure(){return new NaviGateway.Callback<Void>(){public void onSuccess(Void v){}public void onFailure(String m){throw new AssertionError(m);}};}

    static final class TrackingGateway implements NaviGateway {
        int calls; boolean parked=true,fail,doubleCallback,throwParked,unverified,lateSuccess;
        public void connect(Callback<Void> c){c.onSuccess(null);}
        public void queryActiveRoute(Callback<ActiveRoute> c){c.onSuccess(ActiveRoute.INACTIVE);}
        public void loadDestinations(Callback<List<Destination>> c){c.onSuccess(List.of(A,B));}
        public void estimate(Destination d,long n,Callback<Estimate> c){c.onSuccess(current());}
        public void start(Destination d,Preference p,Callback<RouteResult> c){calls++; if(fail){c.onFailure("timeout");if(lateSuccess)c.onSuccess(new RouteResult(true,"late"));return;} c.onSuccess(new RouteResult(!unverified,"fixture readback")); if(doubleCallback)c.onSuccess(new RouteResult(true,"duplicate"));}
        public boolean isDemo(){return true;}
        public boolean isParked(){if(throwParked)throw new IllegalStateException("service down");return parked;}
        public void close(){}
    }
}
