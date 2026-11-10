package kr.routine.core;

import java.util.List;
import kr.routine.core.Model.*;

/** Synchronous simulation; no network, SDK, map rendering, or real navigation calls. */
public final class DemoNaviGateway implements NaviGateway {
    public enum TrafficScenario { NORMAL, CONGESTED, MISSING, STALE, UNKNOWN_UNITS }
    private boolean connected, closed, congested, parked=true, failNext, missingTraffic;
    private TrafficScenario trafficScenario = TrafficScenario.NORMAL;
    private ActiveRoute activeRoute = ActiveRoute.INACTIVE;
    private int startCount;
    public void setCongested(boolean value){congested=value;}
    public void setParked(boolean value){parked=value;}
    public void setFailNext(boolean value){failNext=value;}
    public void setMissingTraffic(boolean value){missingTraffic=value;}
    public void setTrafficScenario(TrafficScenario value) {
        trafficScenario=java.util.Objects.requireNonNull(value);
        congested=value==TrafficScenario.CONGESTED;
        missingTraffic=value==TrafficScenario.MISSING;
    }
    public int startCount(){return startCount;}
    /** Explicit demo-only scenario reset; this method never changes a real route. */
    public void resetRoute(){activeRoute=ActiveRoute.INACTIVE;failNext=false;}
    public void setActiveRoute(ActiveRoute value){activeRoute=java.util.Objects.requireNonNull(value);}
    @Override public void queryActiveRoute(Callback<ActiveRoute> callback){
        callback.onSuccess(connected && !closed ? activeRoute : ActiveRoute.UNAVAILABLE);
    }
    @Override public void connect(Callback<Void> callback){if(closed){callback.onFailure("모의 연결이 종료됐습니다");return;}connected=true;callback.onSuccess(null);}
    @Override public void loadDestinations(Callback<List<Destination>> callback){if(!ready(callback))return;callback.onSuccess(DemoFixtures.destinations());}
    @Override public void estimate(Destination destination,long now,Callback<Estimate> callback){
        if(!ready(callback))return;
        if(missingTraffic){callback.onFailure("모의 교통 정보 누락");return;}
        int minutes=trafficScenario==TrafficScenario.UNKNOWN_UNITS ? -1 : congested?44:28;
        long observedAt=trafficScenario==TrafficScenario.STALE ? now-120001 : now;
        callback.onSuccess(new Estimate(destination.id(),DemoFixtures.ORIGIN,DemoFixtures.BASIS,minutes,observedAt,
            congested?"모의 상황: 평소보다 지연이 큰 날":"모의 상황: 평소와 비슷한 날",true));
    }
    @Override public void start(Destination destination,Preference preference,Callback<RouteResult> callback){
        if(!connected || closed || activeRoute == ActiveRoute.UNAVAILABLE){
            callback.onSuccess(RouteResult.notSent(ActiveRoute.UNAVAILABLE,"모의 안내 상태를 확인하지 못했습니다."));return;
        }
        if(activeRoute == ActiveRoute.ACTIVE){
            callback.onSuccess(RouteResult.notSent(ActiveRoute.ACTIVE,"이미 안내 중입니다."));return;
        }
        if(!parked){callback.onSuccess(RouteResult.notSent(ActiveRoute.INACTIVE,"모의 주행 중에는 요청할 수 없습니다."));return;}
        startCount++;
        if(failNext){failNext=false;callback.onFailure("모의 응답 시간 초과");return;}
        activeRoute=ActiveRoute.ACTIVE;
        callback.onSuccess(new RouteResult(true,"모의 안내 설정 확인: "+destination.name()+" · "+preference+". 순정 내비에는 연결하지 않았습니다."));
    }
    private boolean ready(Callback<?> callback){if(!connected||closed){callback.onFailure("모의 연결이 준비되지 않았습니다");return false;}return true;}
    @Override public boolean isDemo(){return true;}
    @Override public boolean isParked(){return connected&&!closed&&parked;}
    @Override public void close(){closed=true;connected=false;}
}
