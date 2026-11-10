package kr.routine.app.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import kr.routine.app.vehicle.VehicleUiState;
import kr.routine.core.DepartureController;
import kr.routine.core.Model.*;

/** Native rendering of the web specification's app area. No navigation decisions or SDK calls. */
public final class VehicleScreen {
    public interface Actions {
        void selectDestination(Destination destination);
        void setPreference(Preference preference);
        void confirm();
        void dismiss();
        void refresh();
        void recommend();
        void setConsent(boolean enabled);
        void registerRoutine(Destination destination,int day,int minute);
        void clearHistory();
        void demoScenario(String key);
        void switchMode();
        void setTheme(String key);
    }
    private static final int MATCH=ViewGroup.LayoutParams.MATCH_PARENT, WRAP=ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final String[] DAYS={"월","화","수","목","금","토","일"};
    private static final int[] TIMES={450,480,510,720,1080,1110};
    private final Activity activity;
    private final Actions actions;
    private final FrameLayout root;
    private final Typeface typeface;
    private VehicleUiState state;
    private VehicleVisuals.Metrics metrics;
    private VehicleVisuals.Palette colors;
    private String screen="home", sheet, theme="system", pendingScenario, confirmDestination;
    private String onboardId, onboardSubId;
    private int onboardDay=3, onboardMinute=480;
    private boolean building;
    private int width,height;

    public VehicleScreen(Activity activity,Actions actions) {
        this.activity=Objects.requireNonNull(activity); this.actions=Objects.requireNonNull(actions);
        Typeface loaded;
        try { loaded=Typeface.createFromAsset(activity.getAssets(),"AstaSans-Variable.ttf"); }
        catch (RuntimeException missingFont) { loaded=Typeface.create("sans-serif",Typeface.NORMAL); }
        typeface=loaded;
        root=new FrameLayout(activity);
        root.setFitsSystemWindows(true);
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)-> {
            int nextWidth=r-l-root.getPaddingLeft()-root.getPaddingRight();
            int nextHeight=b-t-root.getPaddingTop()-root.getPaddingBottom();
            if(nextWidth>0 && nextHeight>0 && (nextWidth!=width || nextHeight!=height)) {
                width=nextWidth; height=nextHeight; rebuild();
            }
        });
    }
    public View view() { return root; }
    public String screenKey() { return screen; }
    public void render(VehicleUiState next) {
        Objects.requireNonNull(next);
        boolean wasTerminal=state!=null && terminal(state);
        boolean startedMoving=state!=null && state.parked() && !next.parked();
        state=next;
        String nextScreen=VehicleVisuals.screenAfterState(screen,wasTerminal,terminal(next),startedMoving);
        if(!screen.equals(nextScreen) || startedMoving) sheet=null;
        screen=nextScreen;
        rebuild();
    }
    public void setTheme(String choice) {
        if(!List.of("system","day","night").contains(choice)) throw new IllegalArgumentException("Unknown theme");
        theme=choice; rebuild();
    }
    public void saveState(Bundle out) {
        out.putString("screen",screen); out.putString("theme",theme);
        out.putString("onboardId",onboardId); out.putString("onboardSubId",onboardSubId);
        out.putInt("onboardDay",onboardDay); out.putInt("onboardMinute",onboardMinute);
        // A pending confirmation is intentionally not restored as an accepted action.
        out.putString("sheet",Objects.equals(sheet,"why") ? "why" : null);
    }
    public void restoreState(Bundle saved) {
        if(saved==null) return;
        String candidate=saved.getString("screen","home");
        screen=List.of("home","picker","guide","settings","onboard1","onboard2","onboard3").contains(candidate)?candidate:"home";
        String savedTheme=saved.getString("theme","system");
        theme=List.of("system","day","night").contains(savedTheme)?savedTheme:"system";
        onboardId=saved.getString("onboardId"); onboardSubId=saved.getString("onboardSubId");
        onboardDay=Math.max(1,Math.min(7,saved.getInt("onboardDay",3)));
        onboardMinute=Math.max(0,Math.min(1439,saved.getInt("onboardMinute",480)));
        sheet="why".equals(saved.getString("sheet"))?"why":null;
        rebuild();
    }
    public boolean goBack() {
        if(sheet!=null) { sheet=null; rebuild(); return true; }
        if(!screen.equals("home") && !screen.equals("guide")) {
            screen=state!=null && terminal(state)?"guide":"home"; rebuild(); return true;
        }
        return false;
    }
    private void rebuild() {
        if(building || state==null || width<=0 || height<=0) return;
        building=true;
        try {
            var display=activity.getResources().getDisplayMetrics();
            metrics=new VehicleVisuals.Metrics(width,height,display.density,display.scaledDensity);
            boolean night=theme.equals("night") || (theme.equals("system") &&
                (activity.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
            colors=night?VehicleVisuals.NIGHT:VehicleVisuals.DAY;
            root.removeAllViews(); root.setBackgroundColor(colors.background()); root.setTag(screen);
            LinearLayout page=column(); page.setPadding(metrics.padding(),0,metrics.padding(),24);
            root.addView(page,new FrameLayout.LayoutParams(MATCH,MATCH));
            header(page);
            ScrollView scroll=new ScrollView(activity); scroll.setFillViewport(true); scroll.setClipToPadding(false);
            page.addView(scroll,new LinearLayout.LayoutParams(MATCH,0,1));
            LinearLayout body=column(); body.setPadding(0,12,0,20); scroll.addView(body,new ScrollView.LayoutParams(MATCH,WRAP));
            if(state.notice()!=null && !state.notice().isBlank()) banner(body,state.notice(),true);
            switch(screen) {
                case "picker" -> picker(body,false);
                case "settings" -> settings(body);
                case "onboard1" -> onboardConsent(body);
                case "onboard2" -> picker(body,true);
                case "onboard3" -> onboardSchedule(body);
                case "guide" -> guide(body,page);
                default -> home(body,page);
            }
            if(sheet!=null) overlay();
        } finally { building=false; }
    }
    private void header(LinearLayout parent) {
        LinearLayout bar=metrics.compact()?column():row();
        bar.setPadding(0,14,0,18);
        LinearLayout brand=row();
        TextView title=text(screen.equals("settings")?"내 일상":"늘길",40,true,colors.text());
        brand.addView(title,new LinearLayout.LayoutParams(0,WRAP,1));
        TextView tag=text(state.demo()?"시연 데이터":"연결 시험",30,false,colors.secondary());
        tag.setPadding(16,6,16,6); tag.setBackground(shape(colors.surface(),colors.muted(),12));
        brand.addView(tag,new LinearLayout.LayoutParams(WRAP,WRAP));
        if(metrics.compact()) bar.addView(brand,new LinearLayout.LayoutParams(MATCH,WRAP));
        else bar.addView(brand,new LinearLayout.LayoutParams(0,WRAP,1));
        boolean main=screen.equals("home")||screen.equals("guide");
        if(main && !metrics.compact()) {
            Button refresh=button("새로 확인",false,state.canRefresh(),actions::refresh);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(WRAP,WRAP); lp.leftMargin=24; bar.addView(refresh,lp);
        }
        Button navigation=button(main?"내 일상":"돌아가기",false,true,()->navigate(main?"settings":terminal(state)?"guide":"home"));
        if(metrics.compact()) add(bar,navigation,12); else { LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(WRAP,WRAP); lp.leftMargin=24; bar.addView(navigation,lp); }
        add(parent,bar,0);
    }
    private void home(LinearLayout body,LinearLayout page) {
        if(state.source().equals("UNRESOLVED")) {
            heading(body,"저장한 장소를 찾지 못했어요"); description(body,"현재 내비의 장소 목록에서 다시 골라 주세요.");
            add(body,button("목적지 다시 고르기",false,state.canEdit()&&!state.destinations().isEmpty(),()->navigate("picker")),28);
            refreshButton(body); return;
        }
        if(state.departureState()==DepartureController.State.DISMISSED && state.holdType()==HoldType.NONE) {
            heading(body,"이번 추천을 넘겼어요"); description(body,"순정 내비의 경로는 바꾸지 않았어요.");
            add(body,button("다시 보기",false,state.canEdit(),actions::recommend),32); return;
        }
        if(state.activeRoute()==ActiveRoute.ACTIVE) {
            heading(body,"이미 안내 중이에요"); description(body,"현재 경로를 유지할게요. 안내 내용은 순정 내비에서 확인해 주세요.");
            refreshButton(body); return;
        }
        if(state.activeRoute()==ActiveRoute.UNAVAILABLE || !state.connected()) {
            heading(body,state.busy()?"현재 경로를 확인하고 있어요":"현재 경로를 확인할 수 없어요");
            description(body,state.message()); refreshButton(body); return;
        }
        if(state.destination()==null) { empty(body); return; }
        if(!state.parked()) banner(body,"정차 상태를 확인한 뒤 조작할 수 있어요",true);
        LinearLayout lead=metrics.compact()?column():row();
        LinearLayout main=column();
        add(main,text(state.reason(),38,false,colors.secondary()),0);
        LinearLayout destinationLine=row();
        TextView name=text(state.destination().name(),metrics.compact()?58:84,true,colors.text()); name.setAccessibilityHeading(true);
        destinationLine.addView(name,new LinearLayout.LayoutParams(0,WRAP,1));
        Button change=button("바꾸기",false,state.canEdit(),()->navigate("picker"));
        LinearLayout.LayoutParams changeParams=new LinearLayout.LayoutParams(metrics.touch(170),WRAP); changeParams.leftMargin=22;
        destinationLine.addView(change,changeParams); add(main,destinationLine,6);
        verdict(main);
        if(metrics.compact()) add(lead,main,0);
        else {
            lead.addView(main,new LinearLayout.LayoutParams(0,WRAP,2f));
            LinearLayout summary=column(); summary.setPadding(28,28,28,28); summary.setBackground(shape(colors.surface(),0,26));
            add(summary,text("경로 요약",34,true,colors.text()),0);
            add(summary,text("현재 위치 → 목적지",34,false,colors.secondary()),20);
            add(summary,text("지도와 실제 경로는 순정 내비에서 확인해 주세요.",30,false,colors.secondary()),24);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,WRAP,.8f); lp.leftMargin=40; lead.addView(summary,lp);
        }
        // Route preference lives in settings only, matching the navi-style home of the web spec.
        add(body,lead,0); route(body);
        actionRow(page,button(state.busy()?"확인 중":"안내 시작",true,state.canConfirm(),()-> {
            confirmDestination=destinationKey(state.destination()); showSheet("confirm");
        }),button("이번엔 넘기기",false,state.canEdit() && !state.busy(),actions::dismiss));
    }
    private void empty(LinearLayout body) {
        if(state.holdType()==HoldType.TIE && !state.candidates().isEmpty()) {
            heading(body,"오늘은 어디로 갈까요?"); description(body,"이 시간대의 후보가 여러 곳이에요. 직접 골라 주세요.");
            for(TiedCandidate candidate:state.candidates()) place(body,candidate.destination(),candidate.samples()>0?candidate.samples()+"일 선택":"등록한 장소",false);
        } else {
            heading(body,state.holdType()==HoldType.NOT_ENOUGH && state.samples()>0?"조금만 더 다니면 먼저 준비해 둘게요":"오늘은 어디로 갈까요?");
            description(body,state.reason());
            if(state.samples()>0) add(body,text(state.samples()+"일 기록 · 추천에는 3일분이 필요해요",36,false,colors.secondary()),28);
            add(body,button("평소 장소 등록",true,state.canEdit(),()->navigate("onboard1")),32);
            add(body,button("직접 목적지 고르기",false,state.canEdit()&&!state.destinations().isEmpty(),()->navigate("picker")),14);
        }
        refreshButton(body);
    }
    private void verdict(LinearLayout parent) {
        boolean historyVisible=state.collectionEnabled() && (!state.history().isEmpty() || state.samples()>0);
        if(state.minutes()!=null) {
            LinearLayout verdict=metrics.compact()?column():row();
            verdict.setGravity(Gravity.BOTTOM);
            TextView eta=text(state.minutes()+"분",metrics.compact()?104:148,true,colors.text());
            LinearLayout details=column();
            if(metrics.compact()) add(verdict,eta,0); else verdict.addView(eta,new LinearLayout.LayoutParams(WRAP,WRAP));
            String message=switch(state.trafficKind()) {
                case "LATE" -> "평소보다 "+state.delayMinutes()+"분 더 걸려요";
                case "NO_COMPARE" -> "평소 시간은 기록을 모으는 중이에요";
                default -> "평소와 비슷해요";
            };
            add(details,text(message,metrics.compact()?40:50,true,state.trafficKind().equals("LATE")?colors.warningText():colors.text()),4);
            LocalDateTime arrival=Instant.ofEpochMilli(state.now()).atZone(state.zone()).toLocalDateTime().plusMinutes(state.minutes());
            add(details,text(clock(arrival.getHour()*60+arrival.getMinute())+" 도착 예정",36,false,colors.secondary()),6);
            if(metrics.compact()) add(verdict,details,8); else {
                LinearLayout.LayoutParams detailParams=new LinearLayout.LayoutParams(0,WRAP,1); detailParams.leftMargin=24; verdict.addView(details,detailParams);
            }
            if(historyVisible) {
                Button history=button("지난 기록",false,true,()->showSheet("why"));
                if(metrics.compact()) add(verdict,history,12); else {
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(metrics.touch(190),WRAP); lp.leftMargin=16; verdict.addView(history,lp);
                }
            }
            add(parent,verdict,18);
        } else {
            String message=switch(state.trafficKind()) {
                case "STALE" -> "교통 정보가 오래됐어요";
                case "UNKNOWN_UNITS" -> "예상 시간을 아직 표시할 수 없어요";
                case "NOT_REQUESTED" -> state.busy()?"교통 정보를 확인하고 있어요":"교통 정보를 확인해 주세요";
                default -> "교통 정보를 받지 못했어요";
            };
            add(parent,text(message,metrics.compact()?40:50,true,colors.text()),28);
            add(parent,text("알 수 없는 시간은 표시하지 않아요",30,false,colors.secondary()),8);
            if(historyVisible) add(parent,button("지난 기록",false,true,()->showSheet("why")),12);
        }
    }
    private void route(LinearLayout parent) {
        RouteSummaryView road=new RouteSummaryView(activity,colors);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(MATCH,metrics.routeHeight()); lp.topMargin=24; parent.addView(road,lp);
        LinearLayout labels=metrics.compact()?column():row();
        TextView origin=text("현재 위치",34,true,colors.text());
        TextView destination=text(state.destination()==null?"목적지":state.destination().name(),metrics.compact()?38:46,true,colors.text());
        if(metrics.compact()) { add(labels,origin,0); add(labels,destination,8); }
        else { labels.addView(origin,new LinearLayout.LayoutParams(0,WRAP,1)); destination.setGravity(Gravity.END); labels.addView(destination,new LinearLayout.LayoutParams(0,WRAP,1)); }
        add(parent,labels,0);
    }
    private void preferences(LinearLayout parent) {
        LinearLayout choices=metrics.stackPreferences()?column():row();
        for(Preference preference:Preference.values()) {
            Button choice=button(preferenceLabel(preference),false,state.canEdit(),()->actions.setPreference(preference));
            choice.setSelected(preference==state.preference()); choice.setContentDescription(preferenceLabel(preference)+(choice.isSelected()?", 선택됨":""));
            if(choice.isSelected()) choice.setBackground(background(colors.selected(),colors.selectedLine()));
            if(metrics.stackPreferences()) add(choices,choice,12);
            else { LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,WRAP,1); if(choices.getChildCount()>0)lp.leftMargin=14; choices.addView(choice,lp); }
        }
        add(parent,choices,22);
    }
    private void guide(LinearLayout body,LinearLayout page) {
        String phase=switch(state.departureState()) {
            case REQUESTING,CHECKING_ROUTE -> "requesting";
            case VERIFIED -> "verified";
            default -> "unconfirmed";
        };
        body.setTag(phase);
        heading(body,switch(phase) {
            case "requesting" -> "안내 반영을 확인하고 있어요";
            case "verified" -> state.demo()?"모의 안내를 확인했어요":"안내 반영을 확인했어요";
            default -> "안내 결과를 확인하지 못했어요";
        });
        description(body,state.message());
        if(state.destination()!=null) route(body);
        if(state.departureState()==DepartureController.State.UNCONFIRMED)
            banner(body,"같은 이동은 다시 요청하지 않아요. 순정 내비에서 현재 경로를 확인해 주세요.",true);
        if(state.demo()) banner(body,"모의 응답입니다. 실제 내비에는 연결하지 않았어요.",false);
        add(body,text("현재 경로: "+switch(state.activeRoute()) {
            case ACTIVE -> "안내 중"; case INACTIVE -> "안내 중인 경로 없음"; case UNAVAILABLE -> "조회 불가";
        },34,false,colors.secondary()),24);
        actionRow(page,button("안내 상태 다시 확인",false,state.canRefresh(),actions::refresh),null);
    }
    private void picker(LinearLayout body,boolean onboarding) {
        heading(body,onboarding?"평소 가는 곳을 골라 주세요":"어디로 갈까요?");
        description(body,state.demo()?"모의 장소 목록입니다":"순정 내비의 즐겨찾기와 최근 목적지예요");
        for(Destination destination:state.destinations()) place(body,destination,destination.address(),onboarding);
        if(state.destinations().isEmpty()) { description(body,"장소를 불러오지 못했어요. 순정 내비에서 저장한 장소를 확인해 주세요."); refreshButton(body); }
    }
    private void place(LinearLayout parent,Destination destination,String detail,boolean onboarding) {
        Button choice=button(destination.name()+"\n"+detail,false,state.canEdit(),()-> {
            if(onboarding) { onboardId=destination.id(); onboardSubId=destination.subId(); navigate("onboard3"); }
            else { screen="home"; actions.selectDestination(destination); }
        });
        choice.setGravity(Gravity.START|Gravity.CENTER_VERTICAL); choice.setPadding(28,22,28,22);
        choice.setMinHeight(metrics.touch(146)); choice.setMinimumHeight(metrics.touch(146));
        add(parent,choice,14);
    }
    private void settings(LinearLayout body) {
        heading(body,"내 일상"); description(body,"평소 장소와 기록을 관리해요");
        if(state.rules().isEmpty()) description(body,"아직 등록한 일상이 없어요.");
        for(Rule rule:state.rules()) {
            TextView saved=text(rule.destination().name()+"\n"+DAYS[rule.day()-1]+"요일 · "+clock(rule.minute()),38,true,colors.text());
            saved.setPadding(24,18,24,18); saved.setBackground(shape(colors.surface(),0,20)); add(body,saved,14);
        }
        add(body,button("평소 장소 등록",false,state.canEdit(),()->navigate("onboard1")),24);
        add(body,text("경로 선택",40,true,colors.text()),32);
        preferences(body);
        add(body,button("교통·안내 상태 다시 확인",false,state.canRefresh(),actions::refresh),14);
        add(body,button(state.collectionEnabled()?"기록 저장 켜짐 · 끄기":"기록 저장 꺼짐 · 켜기",false,
            state.connected() && state.parked() && !state.busy(),()-> {
                if(state.collectionEnabled()) actions.setConsent(false); else showSheet("consent");
            }),14);
        description(body,"확인한 목적지 선택과 예상 시간만 이 기기에 보관해요. 서버로 보내지 않아요.");
        add(body,button("선택·교통 기록 지우기",false,state.connected()&&state.parked()&&!state.busy(),()->showSheet("clear")),14);
        add(body,text("화면 밝기",40,true,colors.text()),32);
        for(String choice:List.of("system","day","night")) {
            String label=switch(choice) { case "day"->"주간"; case "night"->"야간"; default->"차량 설정 따르기"; };
            Button option=button(label+(choice.equals(theme)?" · 선택됨":""),false,state.parked(),()->actions.setTheme(choice));
            add(body,option,12);
        }
        add(body,text("연결 시험",40,true,colors.text()),32);
        add(body,button(state.demo()?"순정 내비 연결 시험":"모의 시연으로 전환",false,state.canSwitchMode(),()->showSheet("mode")),14);
        description(body,"지도·경유지·폰 전송은 아직 연결되지 않았어요.");
        if(state.demo()) {
            add(body,text("개발 시연",40,true,colors.text()),32);
            description(body,"아래 재현은 모의 이동과 시연 기록을 초기화해요. 먼저 기록 저장에 동의해 주세요.");
            String[][] scenarios={{"평소와 비슷","learned_usual"},{"큰 지연","learned_congested"},{"등록한 장소","rule_usual"},
                {"기록 없음","no_history"},{"기록 부족 · 2일","not_enough"},{"후보 동률","tie"},
                {"비교 기록 부족","compare_short"},{"교통 없음","traffic_missing"},{"오래된 교통","traffic_stale"},
                {"단위 미확인","live_unknown_units"},{"이미 안내 중","active"},{"안내 조회 불가","unavailable"},
                {"정차 미확인","moving"},{"안내 결과 미확인","unconfirmed"}};
            for(String[] scenario:scenarios) add(body,button(scenario[0],false,!state.busy() && (state.collectionEnabled()||scenario[1].equals("no_history")),()-> {
                pendingScenario=scenario[1]; showSheet("scenario");
            }),12);
        }
    }
    private void onboardConsent(LinearLayout body) {
        heading(body,"평소 가는 곳을 알려 주세요"); description(body,"1 / 3 · 기록은 내가 정해요");
        add(body,text("확인한 선택만 기록해요",44,true,colors.text()),28);
        description(body,"실제 운행 완료 이력은 아니에요. 기록을 끄거나 지울 수 있고, 등록한 평소 장소는 따로 유지해요.");
        add(body,button(state.collectionEnabled()?"기록 저장 켜짐 · 끄기":"기록 저장 꺼짐 · 켜기",false,state.connected()&&state.parked()&&!state.busy(),()-> {
            if(state.collectionEnabled()) actions.setConsent(false); else showSheet("consent");
        }),24);
        add(body,button("장소 고르기",true,state.canEdit(),()->navigate("onboard2")),32);
    }
    private void onboardSchedule(LinearLayout body) {
        heading(body,"언제 가는 곳인가요?"); description(body,"3 / 3 · 요일과 시간을 골라 주세요");
        Destination chosen=onboardDestination();
        if(chosen==null) { description(body,"선택한 장소를 현재 목록에서 찾을 수 없어요."); add(body,button("장소 다시 고르기",false,state.canEdit(),()->navigate("onboard2")),24); return; }
        add(body,text(chosen.name(),48,true,colors.text()),24);
        add(body,text("요일",36,true,colors.text()),24);
        choices(body,7,index->DAYS[index]+"요일"+(onboardDay==index+1?" · 선택됨":""),index->{onboardDay=index+1; rebuild();});
        add(body,text("시간",36,true,colors.text()),24);
        choices(body,TIMES.length,index->clock(TIMES[index])+(onboardMinute==TIMES[index]?" · 선택됨":""),index->{onboardMinute=TIMES[index]; rebuild();});
        add(body,button("이 일상 저장",true,state.canEdit(),()-> {
            screen="home"; actions.registerRoutine(chosen,onboardDay,onboardMinute);
        }),32);
    }
    private interface ChoiceLabel { String label(int index); }
    private interface ChoiceClick { void click(int index); }
    private void choices(LinearLayout parent,int count,ChoiceLabel label,ChoiceClick callback) {
        int columns=metrics.compact()?1:3;
        for(int start=0;start<count;start+=columns) {
            LinearLayout line=row();
            for(int n=start;n<Math.min(start+columns,count);n++) {
                final int index=n; Button choice=button(label.label(n),false,state.canEdit(),()->callback.click(index));
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,WRAP,1); if(n>start)lp.leftMargin=14; line.addView(choice,lp);
            }
            add(parent,line,12);
        }
    }
    private void overlay() {
        FrameLayout shade=new FrameLayout(activity); shade.setBackgroundColor(colors.scrim()); shade.setClickable(true); shade.setTag(sheet);
        root.addView(shade,new FrameLayout.LayoutParams(MATCH,MATCH));
        ScrollView scroll=new ScrollView(activity); scroll.setFillViewport(false); scroll.setBackground(shape(colors.surface(),0,32));
        FrameLayout.LayoutParams frame=new FrameLayout.LayoutParams(Math.max(1,Math.min(1180,width-48)),Math.max(1,height-48),Gravity.CENTER);
        shade.addView(scroll,frame);
        LinearLayout card=column(); card.setPadding(32,32,32,32); scroll.addView(card,new ScrollView.LayoutParams(MATCH,WRAP));
        switch(sheet) {
            case "why" -> {
                heading(card,"왜 이 경로인가요?");
                description(card,state.collectionEnabled()?state.reason():"기록 저장이 꺼져 있어 지난 기록을 표시하지 않아요.");
                if(state.collectionEnabled()) {
                    if(state.baselineMinutes()!=null) description(card,"비교한 예상 시간의 중앙값은 "+state.baselineMinutes()+"분이에요.");
                    else description(card,"평소 시간을 비교하려면 3일분이 필요해요.");
                    for(DayEstimate record:state.history()) {
                        TextView row=text(record.localDate()+"  ·  "+record.minutes()+"분",38,true,colors.text());
                        row.setPadding(24,20,24,20); row.setBackground(shape(colors.selected(),0,16)); row.setTag(record.localDate().toString()); add(card,row,12);
                    }
                }
            }
            case "confirm" -> {
                heading(card,"이 목적지로 안내할까요?");
                description(card,state.destination()==null?"목적지를 다시 확인해 주세요":state.destination().name());
                description(card,state.demo()?"모의 안내입니다. 순정 내비는 바뀌지 않아요.":"순정 내비에 목적지를 한 번 요청해요.");
                description(card,preferenceLabel(state.preference())+" · 성향별 예상 시간은 아직 제공하지 않아요.");
                boolean same=Objects.equals(confirmDestination,destinationKey(state.destination()));
                add(card,button("안내 시작",true,same&&state.canConfirm(),()-> {
                    if(state.canConfirm() && Objects.equals(confirmDestination,destinationKey(state.destination()))) {
                        sheet=null; actions.confirm();
                    }
                }),28);
            }
            case "clear" -> {
                heading(card,"선택·교통 기록을 지울까요?"); description(card,"이 앱의 기록만 지워요. 등록한 평소 장소와 순정 내비 기록은 유지돼요.");
                add(card,button("기록 지우기",true,state.parked()&&!state.busy(),()->{sheet=null;actions.clearHistory();}),28);
            }
            case "consent" -> {
                heading(card,"기록 저장을 켤까요?"); description(card,"확인한 목적지 선택과 예상 시간을 이 기기에 저장해요. 서버로 보내지 않고, 언제든 끄거나 지울 수 있어요.");
                add(card,button("동의하고 켜기",true,state.parked()&&!state.busy(),()->{sheet=null;actions.setConsent(true);}),28);
            }
            case "mode" -> {
                heading(card,state.demo()?"순정 내비 연결을 시험할까요?":"모의 시연으로 바꿀까요?");
                description(card,"자동으로 안내를 시작하지 않아요. 실제 연결과 지도 동작은 아직 검증 전이에요.");
                add(card,button("전환",true,state.canSwitchMode(),()->{sheet=null;screen="home";actions.switchMode();}),28);
            }
            case "scenario" -> {
                heading(card,"모의 상태를 다시 준비할까요?"); description(card,"모의 이동과 시연 기록을 초기화해요. 실제 순정 내비에는 요청하지 않아요.");
                add(card,button("시연 상태 준비",true,state.demo()&&!state.busy()&&(state.collectionEnabled()||"no_history".equals(pendingScenario)),()-> {
                    String choice=pendingScenario; sheet=null; screen="home"; actions.demoScenario(choice);
                }),28);
            }
            default -> description(card,"현재 상태를 다시 확인해 주세요");
        }
        add(card,button("닫기",false,true,()->{sheet=null;rebuild();}),16);
    }
    private Destination onboardDestination() {
        for(Destination destination:state.destinations())
            if(destination.id().equals(onboardId)&&destination.subId().equals(onboardSubId))return destination;
        return null;
    }
    private void refreshButton(LinearLayout parent) { add(parent,button("상태 다시 확인",false,state.canRefresh(),actions::refresh),28); }
    private void navigate(String next) { screen=next; sheet=null; rebuild(); }
    private void showSheet(String next) { sheet=next; rebuild(); }
    private void heading(LinearLayout parent,String value) { TextView title=text(value,metrics.compact()?52:62,true,colors.text()); title.setAccessibilityHeading(true); add(parent,title,12); }
    private void description(LinearLayout parent,String value) { if(value!=null&&!value.isBlank())add(parent,text(value,38,false,colors.secondary()),14); }
    private void banner(LinearLayout parent,String value,boolean warning) {
        TextView banner=text(value,34,false,warning?colors.warningText():colors.text()); banner.setPadding(24,20,24,20);
        banner.setBackground(shape(warning?colors.warning():colors.surface(),0,20)); add(parent,banner,16);
    }
    private void actionRow(LinearLayout parent,Button primary,Button secondary) {
        primary.setMinHeight(metrics.primaryHeight()); primary.setMinimumHeight(metrics.primaryHeight());
        if(secondary!=null) { secondary.setMinHeight(metrics.primaryHeight()); secondary.setMinimumHeight(metrics.primaryHeight()); }
        LinearLayout line=metrics.stackButtons()?column():row(); line.setPadding(0,14,0,0);
        if(metrics.stackButtons()) { add(line,primary,0); if(secondary!=null)add(line,secondary,12); }
        else {
            line.addView(primary,new LinearLayout.LayoutParams(0,WRAP,secondary==null?1:2));
            if(secondary!=null) { LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,WRAP,1); lp.leftMargin=14; line.addView(secondary,lp); }
        }
        // Short or large-font windows scroll the controls as part of the document instead of hiding content.
        if(height<700 || metrics.text(40)>80) {
            ScrollView scroll=(ScrollView)parent.getChildAt(1);
            ((LinearLayout)scroll.getChildAt(0)).addView(line,new LinearLayout.LayoutParams(MATCH,WRAP));
        } else add(parent,line,0);
    }
    private TextView text(String value,int size,boolean bold,int color) {
        TextView view=new TextView(activity); view.setText(value==null?"":value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX,metrics.text(size)); view.setTextColor(color);
        view.setTypeface(typeface,bold?Typeface.BOLD:Typeface.NORMAL); view.setIncludeFontPadding(true);
        view.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
        return view;
    }
    private Button button(String value,boolean primary,boolean enabled,Runnable command) {
        Button button=new Button(activity); button.setAllCaps(false); button.setText(value);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX,metrics.text(primary?48:34));
        button.setTypeface(typeface,primary?Typeface.BOLD:Typeface.NORMAL);
        int height=primary?metrics.primaryHeight():metrics.touch(84);
        button.setMinHeight(height); button.setMinimumHeight(height);
        button.setMinWidth(metrics.touch(84)); button.setMinimumWidth(metrics.touch(84));
        button.setPadding(24,16,24,16); button.setSingleLine(false); button.setGravity(Gravity.CENTER);
        button.setTextColor(enabled?(primary?colors.primaryText():colors.text()):colors.muted());
        button.setBackgroundTintList(null);
        button.setBackground(background(enabled?(primary?colors.primary():colors.surface()):colors.raised(),0));
        button.setEnabled(enabled); button.setOnClickListener(v->command.run());
        button.setOnFocusChangeListener((v,focused)->button.setBackground(background(enabled?(primary?colors.primary():colors.surface()):colors.raised(),focused?colors.selectedLine():0)));
        return button;
    }
    private RippleDrawable background(int color,int border) { return new RippleDrawable(ColorStateList.valueOf(colors.selectedLine()&0x33ffffff),shape(color,border,20),null); }
    private GradientDrawable shape(int color,int border,int radius) {
        GradientDrawable drawable=new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(radius);
        if(border!=0)drawable.setStroke(3,border); return drawable;
    }
    private LinearLayout column() { LinearLayout value=new LinearLayout(activity); value.setOrientation(LinearLayout.VERTICAL); return value; }
    private LinearLayout row() { LinearLayout value=new LinearLayout(activity); value.setOrientation(LinearLayout.HORIZONTAL); value.setGravity(Gravity.CENTER_VERTICAL); return value; }
    private void add(LinearLayout parent,View child,int top) { LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(MATCH,WRAP); lp.topMargin=top; parent.addView(child,lp); }
    private static boolean terminal(VehicleUiState value) { return switch(value.departureState()) { case REQUESTING,VERIFIED,UNCONFIRMED->true; default->false; }; }
    private static String destinationKey(Destination value) { return value==null?null:value.id()+"/"+value.subId(); }
    private static String preferenceLabel(Preference value) { return switch(value) { case RECOMMENDED->"추천 경로"; case SHORT_TIME->"빠른 경로"; case COMFORTABLE->"편안한 경로"; }; }
    private static String clock(int minute) { int hour=(minute/60)%24; return (hour<12?"오전 ":"오후 ")+(hour%12==0?12:hour%12)+":"+String.format(java.util.Locale.ROOT,"%02d",minute%60); }
}
