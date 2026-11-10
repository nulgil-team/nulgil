package kr.routine.core;

import java.io.IOException;
import java.time.*;
import java.util.*;
import kr.routine.core.Model.*;

/** Invented observations at public SDK example destinations. Never actual user travel history. */
public final class DemoFixtures {
    private DemoFixtures() {}
    public static final String ORIGIN = "DEMO_ORIGIN";
    public static final String BASIS = "DEMO_ESTIMATE_DEFAULT";
    public static final long NOW = ZonedDateTime.of(2026,11,4,8,0,0,0,ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli();
    public static final Destination OFFICE = new Destination("36khp2hdj2cfcm5nqhe3", "소프트웨어드림센터", "경기도 성남시 수정구 창업로40번길 6", 37.41275833, 127.09481944, "0");
    public static final Destination OTHER = new Destination("36khp37hh8gqcm5nqhe5", "강남 거래처", "서울시 강남구 남부순환로 2621", 37.48544722, 127.03636666, "0");
    public static List<Destination> destinations() {return List.of(OFFICE,OTHER);}
    /** Compatibility entry point for the original Korea-only demo. */
    public static void seed(RoutineStore store,long now,int day,int minute) throws IOException {
        Model.checkTime(day,minute);
        ZonedDateTime at=Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Seoul"));
        if(at.getDayOfWeek().getValue()!=day || at.getHour()*60+at.getMinute()!=minute)
            throw new IllegalArgumentException("모의 시각과 요일·시간 입력을 맞춰 주세요.");
        seed(store,now,at.getZone());
    }
    public static void seed(RoutineStore store,long now,ZoneId zone) throws IOException {
        seed(store, now, zone, List.of(OFFICE), 3, 3, true);
    }
    /** Counts represent separate prior weeks at the same local weekday and clock time. */
    public static void seed(RoutineStore store,long now,ZoneId zone,List<Destination> destinations,
                            int days,int estimateDays) throws IOException {
        seed(store, now, zone, destinations, days, estimateDays, false);
    }
    private static void seed(RoutineStore store,long now,ZoneId zone,List<Destination> destinations,
                             int days,int estimateDays,boolean registerRule) throws IOException {
        if(!store.collectionEnabled())throw new IllegalStateException("모의 기록 저장에 먼저 동의해 주세요.");
        if(days<0 || days>6 || estimateDays<0 || estimateDays>6)
            throw new IllegalArgumentException("모의 기록 일수는 0~6이어야 합니다.");
        destinations=List.copyOf(destinations);
        if(destinations.isEmpty() || !DemoFixtures.destinations().containsAll(destinations)
            || new HashSet<>(destinations).size()!=destinations.size())
            throw new IllegalArgumentException("중복되지 않는 공개 모의 장소가 필요합니다.");
        ZonedDateTime at=Instant.ofEpochMilli(now).atZone(Objects.requireNonNull(zone,"zone"));
        int day=at.getDayOfWeek().getValue(),minute=at.getHour()*60+at.getMinute();
        List<Selection> selections=new ArrayList<>();
        List<Estimate> estimates=new ArrayList<>();
        for(Destination destination:destinations) {
            for(int i=1;i<=Math.max(days,estimateDays);i++) {
                ZonedDateTime previousAt=at.minusWeeks(i);
                long previous=previousAt.toInstant().toEpochMilli();
                if(i<=days) selections.add(new Selection(destination,previousAt.getDayOfWeek().getValue(),
                    previousAt.getHour()*60+previousAt.getMinute(),previous));
                if(i<=estimateDays) estimates.add(new Estimate(destination.id(),ORIGIN,BASIS,27+i,previous,
                    "공개 장소를 이용한 모의 예상 시간",true));
            }
        }
        store.seedDemo(registerRule ? new Rule(OFFICE,day,minute) : null,selections,estimates);
    }
}
