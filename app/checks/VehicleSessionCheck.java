import java.nio.file.*;
import java.time.*;
import java.util.*;
import kr.routine.app.vehicle.*;
import kr.routine.core.*;
import kr.routine.core.NaviGateway.Callback;
import kr.routine.core.Model.*;

public final class VehicleSessionCheck {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(DemoFixtures.NOW), ZONE);
    private static Path root;
    private static int checks;
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++; System.out.println("PASS " + label);
    }
    private static RoutineStore store(String name) throws Exception { return new RoutineStore(root.resolve(name + ".properties")); }
    private static VehicleSession session(Fake gateway, RoutineStore store) { return new VehicleSession(gateway, store, true, CLOCK, ZONE); }
    private static final class Fake implements NaviGateway {
        ActiveRoute route = ActiveRoute.INACTIVE;
        boolean parked = true, autoRoute = true, autoEstimate = true, autoStart = true, autoPlaces = true, failStart;
        int starts, queries, loads;
        Callback<ActiveRoute> routeCallback;
        Callback<Estimate> estimateCallback;
        Callback<RouteResult> startCallback;
        Callback<List<Destination>> placesCallback;
        public void connect(Callback<Void> c) { c.onSuccess(null); }
        public void loadDestinations(Callback<List<Destination>> c) { loads++; if(autoPlaces)c.onSuccess(DemoFixtures.destinations()); else placesCallback=c; }
        public void estimate(Destination d, long now, Callback<Estimate> c) {
            if (autoEstimate) c.onSuccess(new Estimate(d.id(),DemoFixtures.ORIGIN,DemoFixtures.BASIS,44,now,"demo",true));
            else estimateCallback = c;
        }
        public void queryActiveRoute(Callback<ActiveRoute> c) { queries++; if(autoRoute)c.onSuccess(route); else routeCallback=c; }
        public void start(Destination d, Preference p, Callback<RouteResult> c) {
            starts++; startCallback=c;
            if(autoStart) { if(failStart)c.onFailure("lost"); else {route=ActiveRoute.ACTIVE;c.onSuccess(new RouteResult(true,"demo verified"));} }
        }
        public boolean isDemo() {return true;}
        public boolean isParked(){return parked;}
        public void close(){}
    }
    public static void main(String[] args) throws Exception {
        root=Path.of(args[0]); Files.createDirectories(root);
        for(ActiveRoute active : ActiveRoute.values()) {
            Fake f=new Fake(); f.route=active;
            VehicleSession s=session(f,store("route-"+active)); s.connect();
            check(s.snapshot().activeRoute()==active && f.starts==0,"entry observes "+active+" with zero dispatch");
            if(active==ActiveRoute.INACTIVE) s.selectDestination(DemoFixtures.OFFICE);
            s.confirm(); s.confirm();
            check(f.starts==(active==ActiveRoute.INACTIVE?1:0),"confirm dispatch count "+active);
            s.onForeground(); check(f.starts==(active==ActiveRoute.INACTIVE?1:0),"foreground is read only "+active);
            s.close();
        }
        {
            RoutineStore store=store("register"); Fake f=new Fake(); VehicleSession s=session(f,store); s.connect();
            s.registerRoutine(DemoFixtures.OFFICE,3,480);
            check(s.snapshot().destination().equals(DemoFixtures.OFFICE) && s.snapshot().source().equals("RULE")
                && s.snapshot().canConfirm() && f.starts==0,"first routine registration prepares current rule without dispatch");
            check(s.snapshot().rules().equals(List.of(new Rule(DemoFixtures.OFFICE,3,480))) && !s.snapshot().notice().isBlank(),"actual stored schedule and saved notice exposed");
            s.setConsent(false);check(s.snapshot().rules().size()==1 && s.snapshot().source().equals("RULE"),"consent off preserves explicit registered rules");s.close();
        }
        {
            RoutineStore store=store("unresolved-rule");
            Destination absent=new Destination("unlisted-place","저장된 예시 장소","모의 주소",37,127,"0");
            store.putRule(new Rule(absent,3,480));
            Fake f=new Fake(); VehicleSession s=session(f,store); s.connect(); s.confirm();
            check(s.snapshot().source().equals("UNRESOLVED") && s.snapshot().destination()==null
                && !s.snapshot().canConfirm() && f.starts==0,"missing catalog destination is unresolved, not an explicit dismissal");
            check(s.snapshot().reason().equals("저장한 장소를 현재 내비 목록에서 확인하지 못했어요."),"unresolved destination retains explanatory reason");
            s.close();
        }
        {
            RoutineStore store=store("initial-return"); store.putRule(new Rule(DemoFixtures.OFFICE,3,480));
            Fake f=new Fake(); f.autoPlaces=false;
            VehicleSession s=session(f,store); s.connect(); s.onForeground();
            f.placesCallback.onSuccess(DemoFixtures.destinations());
            check(s.snapshot().destination().equals(DemoFixtures.OFFICE) && f.starts==0,"queued foreground during initial list load still presents registered destination without dispatch");
            s.dismiss();s.onForeground();
            check(s.snapshot().destination()==null && s.snapshot().departureState()==DepartureController.State.DISMISSED,"explicit dismiss remains dismissed across foreground refresh");
            s.selectDestination(DemoFixtures.OFFICE);s.confirm();s.setConsent(false);s.onForeground();s.confirm();
            check(f.starts==1 && s.snapshot().departureState()==DepartureController.State.VERIFIED && s.snapshot().history().isEmpty(),"consent off after terminal result preserves issued trip latch");
            s.close();
        }
        {
            RoutineStore store=store("pending"); store.setCollectionEnabled(true);
            Fake f=new Fake(); VehicleSession s=session(f,store); s.connect(); s.selectDestination(DemoFixtures.OFFICE);
            f.autoRoute=false; f.autoStart=false;
            s.confirm(); s.confirm();
            check(s.snapshot().busy() && f.starts==0,"confirm is disabled while fresh route lookup pending");
            s.setListener(()->{}); s.setListener(null); s.setListener(()->{});
            s.setConsent(false);
            check(s.snapshot().collectionEnabled() && !s.snapshot().notice().isBlank(),"busy consent change is explicitly rejected, not pretended saved");
            s.onForeground();
            Callback<ActiveRoute> oldRead=f.routeCallback; oldRead.onSuccess(ActiveRoute.INACTIVE);
            check(f.starts==1 && s.snapshot().departureState()==DepartureController.State.REQUESTING,"listener reattachment preserves one pending request");
            s.confirm(); oldRead.onSuccess(ActiveRoute.INACTIVE);
            check(f.starts==1,"late duplicate precheck and repeated confirm cannot dispatch twice");
            f.autoRoute=true; f.route=ActiveRoute.ACTIVE; f.startCallback.onSuccess(new RouteResult(true,"verified"));
            s.confirm();
            check(f.starts==1 && s.snapshot().departureState()==DepartureController.State.VERIFIED,"deferred foreground read preserves verified latch");
            check(store.selections().size()==1 && store.estimates().size()==1,"selection and valid estimate recorded only after verified result");
            s.close();
        }
        {
            RoutineStore store=store("estimate"); store.setCollectionEnabled(true);
            Fake f=new Fake(); VehicleSession s=session(f,store); s.connect(); f.autoEstimate=false;
            s.selectDestination(DemoFixtures.OFFICE);
            check(s.snapshot().busy() && store.estimates().isEmpty(),"pending estimate does not write history");
            Callback<Estimate> old=f.estimateCallback;
            s.onForeground(); f.autoEstimate=true;
            old.onSuccess(new Estimate(DemoFixtures.OFFICE.id(),DemoFixtures.ORIGIN,DemoFixtures.BASIS,44,DemoFixtures.NOW,"demo",true));
            check(!s.snapshot().busy() && f.queries==2,"return-to-app query waits until single-flight estimate completes");
            s.selectDestination(DemoFixtures.OTHER);
            old.onSuccess(new Estimate(DemoFixtures.OFFICE.id(),DemoFixtures.ORIGIN,DemoFixtures.BASIS,99,DemoFixtures.NOW,"late",true));
            check(s.snapshot().destination().equals(DemoFixtures.OTHER) && s.snapshot().minutes()==44,"obsolete estimate cannot replace new destination data");
            check(store.estimates().isEmpty(),"view/preview refresh never collects estimates");
            s.close();
        }
        {
            Fake f=new Fake(); f.failStart=true; VehicleSession s=session(f,store("uncertain")); s.connect(); s.selectDestination(DemoFixtures.OFFICE);
            s.confirm(); s.onForeground(); s.selectDestination(DemoFixtures.OTHER); s.confirm();
            check(f.starts==1 && s.snapshot().departureState()==DepartureController.State.UNCONFIRMED,"uncertain route remains latched across foreground and edits");
            check(!s.snapshot().canSwitchMode(),"mode switch cannot release issued trip latch"); s.close();
        }
        {
            Fake f=new Fake(); VehicleSession s=session(f,store("parking")); s.connect(); s.selectDestination(DemoFixtures.OFFICE);
            f.autoRoute=false; s.confirm(); f.parked=false; f.routeCallback.onSuccess(ActiveRoute.INACTIVE);
            check(f.starts==0 && !s.snapshot().canConfirm(),"parking is rechecked after delayed route observation");
            int[] changed={0}; s.setListener(()->changed[0]++); int previous=changed[0]; s.tick(); s.tick();
            check(changed[0]==previous,"stable timer ticks do not rebuild screen"); s.close();
        }
        {
            RoutineStore store=store("privacy"); store.setCollectionEnabled(true); DemoFixtures.seed(store,DemoFixtures.NOW,ZONE);
            Fake f=new Fake(); VehicleSession s=session(f,store); s.connect(); s.selectDestination(DemoFixtures.OFFICE);
            check(s.snapshot().history().size()==3 && s.snapshot().baselineMinutes()==29,"native history and median use core comparison rows");
            s.setConsent(false); s.refresh();
            check(s.snapshot().history().isEmpty() && s.snapshot().candidates().isEmpty() && s.snapshot().baselineMinutes()==null,"accepted consent off hides lists and baseline on refresh");
            check(store.estimates().size()==3,"consent off does not delete persisted history");
            s.setConsent(true); s.recommend(true);
            Files.writeString(root.resolve("privacy.properties"),"external-edit");
            s.setConsent(false);
            check(s.snapshot().collectionBlocked() && !s.snapshot().collectionEnabled() && !s.snapshot().canConfirm() && !s.snapshot().canSwitchMode()
                && s.snapshot().history().isEmpty() && s.snapshot().candidates().isEmpty(),"failed consent-off save hides history and blocks new request");
            s.close();
        }
        {
            RoutineStore store=store("scenarios"); DemoNaviGateway g=new DemoNaviGateway();
            VehicleSession s=new VehicleSession(g,store,true,CLOCK,ZONE);s.connect();
            s.demoScenario("tie");check(!s.snapshot().collectionEnabled() && store.selections().isEmpty(),"developer fixture never grants consent automatically");
            s.setConsent(true);
            s.demoScenario("not_enough");check(s.snapshot().holdType()==HoldType.NOT_ENOUGH && s.snapshot().samples()==2,"native NOT_ENOUGH fixture");
            s.demoScenario("tie");check(s.snapshot().holdType()==HoldType.TIE && s.snapshot().candidates().size()==2,"native TIE fixture");
            s.demoScenario("compare_short");check(s.snapshot().trafficKind().equals("NO_COMPARE") && s.snapshot().history().size()==2,"native NO_COMPARE fixture");
            s.demoScenario("traffic_stale");check(s.snapshot().trafficKind().equals("STALE") && s.snapshot().minutes()==null,"native STALE fixture");
            s.demoScenario("live_unknown_units");check(s.snapshot().trafficKind().equals("UNKNOWN_UNITS") && s.snapshot().minutes()==null,"native UNKNOWN_UNITS fixture");
            s.demoScenario("moving");check(s.snapshot().destination()!=null && !s.snapshot().canConfirm(),"moving scenario retains prepared destination while blocking");
            s.demoScenario("active");check(s.snapshot().departureState()==DepartureController.State.ALREADY_GUIDING,"active scenario display state");
            s.demoScenario("unavailable");check(s.snapshot().departureState()==DepartureController.State.ROUTE_UNAVAILABLE,"unavailable scenario display state");
            check(g.startCount()==0,"all scenario preparations issue zero navigation requests");
            s.demoScenario("unconfirmed");s.confirm();s.confirm();check(g.startCount()==1 && s.snapshot().departureState()==DepartureController.State.UNCONFIRMED,"explicit failure fixture requires one user confirm");
            s.close();
        }
        System.out.println("VehicleSessionCheck: "+checks+" assertions passed");
    }
}
