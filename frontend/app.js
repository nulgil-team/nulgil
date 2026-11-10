"use strict";

/* 제품 이름. 2026-11-05 사용자 확정. */
const APP_NAME = "늘길";

/*
 * 데이터 구분
 * - CORE_SNAPSHOT: 기존 Java core를 실제 실행한 결과. 이 파일에서 다시 계산하지 않는다.
 * - FIXTURE: 화면 시연용 예시 값. core에 없는 들를 곳 기능이 여기에 해당한다.
 */
const CORE = window.CORE_SNAPSHOT;
const MAP = window.MAP_DATA;
const QR = window.DEMO_QR;

const FIXTURE = {
  stop: { id: "demo-stop", name: "역 후문 주차장", sub: "받은 지도 링크의 위치", source: "카카오 지도 링크" },
  stopRouteAt: 0.42,
  days: ["월", "화", "수", "목", "금", "토", "일"],
  times: [["오전 7:30", 450], ["오전 8:00", 480], ["오전 8:30", 510], ["오후 6:00", 1080], ["오후 6:30", 1110]],
  prefLabels: { RECOMMENDED: "추천 경로", SHORT_TIME: "빠른 경로", COMFORTABLE: "편안한 경로" },
  placeSource: { "36khp2hdj2cfcm5nqhe3": "즐겨찾기", "36khp37hh8gqcm5nqhe5": "최근 목적지" },
};

const initial = () => ({
  screen: "home",
  scenario: "learned_usual",
  manualId: null,
  pref: "RECOMMENDED",
  parked: true,
  activeRoute: false,
  routeUnknown: false,
  nextResult: "confirm_verified",
  nextWaypoint: "added",
  nextSaveFails: false,
  stop: null,
  stopFresh: false,
  stopCount: 0,
  proposal: false,
  dismissed: false,
  flow: null,
  phase: null,
  waypoint: null,
  sheet: null,
  toast: null,
  onboardPlace: null,
  onboardDays: [2],
  onboardTime: 480,
  rules: [{ id: "36khp2hdj2cfcm5nqhe3", day: 2, minute: 480 }],
  collection: true,
  historyCount: 3,
  checking: false,
  pickerFor: "destination",
  theme: "night",
  layout: "full",
  panel: true,
  demoStep: 0,
  phoneOn: false,
  phone: "ready",
  carOn: true,
  carReady: false,
  carLinked: true,
});

let st = initial();
let toastTimer = null;
const timers = new Set();

const $ = (id) => document.getElementById(id);
const dests = () => (live() ? live().destinations : CORE.destinations);
const destById = (id) => dests().find((d) => d.id === id);
const ctx = () => (live() ? live().context : CORE.meta);
const esc = (v) => String(v).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

function after(ms, fn) {
  const t = setTimeout(() => { timers.delete(t); fn(); }, ms);
  timers.add(t);
}
function clearTimers() { timers.forEach(clearTimeout); timers.clear(); }

function clock(minute) {
  const h = Math.floor(minute / 60) % 24;
  const m = String(minute % 60).padStart(2, "0");
  return `${h < 12 ? "오전" : "오후"} ${h % 12 === 0 ? 12 : h % 12}:${m}`;
}
const dayName = (d) => FIXTURE.days[d - 1];

/* 아이콘 */
const I = {
  car: (s = 72) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 3 5 20l7-3.4L19 20z"/></svg>`,
  pin: (s = 78) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path fill-rule="evenodd" d="M12 2a7.5 7.5 0 0 0-7.5 7.5C4.500 15 12 22 12 22s7.500-7 7.500-12.500A7.500 7.500 0 0 0 12 2m0 10.300a2.800 2.800 0 1 1 0-5.600 2.800 2.800 0 0 1 0 5.600"/></svg>`,
  flag: (s = 70) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M6 2h2v20H6zM9 3h10l-3 4.500L19 12H9z"/></svg>`,
  plus: (s = 70) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.600" stroke-linecap="round" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>`,
  check: (s = 44) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m5 12.500 4.500 4.500L19 7.500"/></svg>`,
  alert: (s = 44) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" aria-hidden="true"><path d="M12 5v9M12 19v.1"/></svg>`,
  lock: (s = 42) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M7 10V7.500a5 5 0 0 1 10 0V10h1.500v11h-13V10zm2.200 0h5.600V7.500a2.800 2.800 0 0 0-5.600 0z"/></svg>`,
  gear: (s = 44) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.200" stroke-linecap="round" aria-hidden="true"><path d="M4 7h10M18 7h2M4 17h2M10 17h10"/><circle cx="16" cy="7" r="2.200"/><circle cx="8" cy="17" r="2.200"/></svg>`,
  back: (s = 46) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.800" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M15 5 8 12l7 7"/></svg>`,
  star: (s = 44) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="m12 2.800 2.900 6 6.600.9-4.800 4.600 1.200 6.500-5.900-3.200-5.900 3.200 1.200-6.500L2.500 9.700l6.600-.9z"/></svg>`,
  clock: (s = 44) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.400" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="8.500"/><path d="M12 7v5.300l3.300 2"/></svg>`,
  people: (s = 44) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><circle cx="8.500" cy="8.500" r="3.500"/><circle cx="16.500" cy="9.500" r="2.800"/><path d="M2 20c.3-4 3-6.500 6.500-6.500S14.700 16 15 20zM16.500 20c-.2-2.400-1-4.300-2.300-5.700.8-.4 1.600-.6 2.400-.6 3 0 5.100 2.300 5.400 6.300z"/></svg>`,
};

/* 지도 */
function parsePath(d) {
  return d.slice(1).split("L").map((p) => p.split(" ").map(Number));
}
function pointOnRoute(fraction) {
  const pts = parsePath(MAP.city.route);
  let total = 0;
  for (let i = 1; i < pts.length; i++) total += Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]);
  let left = total * fraction;
  for (let i = 1; i < pts.length; i++) {
    const seg = Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]);
    if (left <= seg) {
      const t = left / seg;
      return [pts[i - 1][0] + (pts[i][0] - pts[i - 1][0]) * t, pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * t];
    }
    left -= seg;
  }
  return pts[pts.length - 1];
}
function paths(list, cls) {
  return list.length ? `<path class="${cls}" d="${list.join("")}"/>` : "";
}
function cityLayers(u) {
  const c = MAP.city;
  return `<rect x="-3000" y="-3000" width="8000" height="8000" fill="var(--map-land)"/>
    <g fill="var(--map-green)">${paths(c.green, "")}</g>
    <g fill="var(--map-water)">${paths(c.water, "")}</g>
    <g fill="none" stroke-linecap="round" stroke-linejoin="round">
      <g stroke="var(--map-water)" stroke-width="${6 * u}">${paths(c.river, "")}</g>
      <g stroke="var(--map-road)" stroke-width="${2.400 * u}">${paths(c.secondary, "")}</g>
      <g stroke="var(--map-road)" stroke-width="${4 * u}">${paths(c.primary, "")}</g>
      <g stroke="var(--map-road-strong)" stroke-width="${5.500 * u}">${paths(c.major, "")}</g>
    </g>`;
}
function marker(x, y, r, fill, stroke, inner) {
  return `<circle cx="${x}" cy="${y}" r="${r}" fill="${fill}" stroke="${stroke}" stroke-width="${r * .26}"/>${inner || ""}`;
}
function cityMap({ viewBox, showRoute = true, stop = false, u = 1 }) {
  const c = MAP.city;
  const s = pointOnRoute(FIXTURE.stopRouteAt);
  const route = showRoute ? `<g fill="none" stroke-linecap="round" stroke-linejoin="round">
    <path d="${c.route}" stroke="var(--map-route-case)" stroke-width="${15 * u}"/>
    <path d="${c.route}" stroke="var(--map-route)" stroke-width="${9 * u}"/></g>` : "";
  const nodes = showRoute
    ? marker(c.origin[0], c.origin[1], 13 * u, "#ffffff", "#0d2c4f") + marker(c.pin[0], c.pin[1], 15 * u, "#1a66c2", "#ffffff")
    : "";
  const stopNode = stop ? marker(s[0], s[1], 14 * u, "#ffc72c", "#2b2100") : "";
  return `<svg viewBox="${viewBox}" preserveAspectRatio="xMidYMid meet" aria-hidden="true">${cityLayers(u)}${route}${nodes}${stopNode}</svg>`;
}
function routeBox(pad, ratio) {
  const pts = parsePath(MAP.city.route);
  const xs = pts.map((p) => p[0]);
  const ys = pts.map((p) => p[1]);
  let x0 = Math.min(...xs) - pad;
  let x1 = Math.max(...xs) + pad;
  let y0 = Math.min(...ys) - pad;
  let y1 = Math.max(...ys) + pad;
  let w = x1 - x0;
  let h = y1 - y0;
  if (w / h < ratio) { const nw = h * ratio; x0 -= (nw - w) / 2; w = nw; } else { const nh = w / ratio; y0 -= (nh - h) / 2; h = nh; }
  return { x: x0, y: y0, w, h };
}

/* 로컬 API 상태. 값이 없으면 오프라인 예시를 쓴다. 추천·교통 계산은 하지 않고 응답을 옮기기만 한다. */
const LIVE = { api: null, snap: null, busy: false, confirming: false, lost: false, historyOnly: true, notice: null };
const live = () => (LIVE.snap && !st.offlineView ? LIVE.snap : null);

function liveView(s) {
  const r = s.recommendation;
  const t = s.traffic;
  const modes = { LATE: "late", CALM: "calm", NO_COMPARE: "nocompare", STALE: "stale", UNKNOWN_UNITS: "units", MISSING: "missing", NOT_REQUESTED: "missing" };
  const legacyModes = { AVAILABLE: t.important ? "late" : "calm", BASELINE_UNAVAILABLE: "nocompare", STALE_OR_INVALID: "stale", UNAVAILABLE: "missing", NOT_REQUESTED: "missing" };
  return {
    s: { recommendation: { samples: r.samples ?? 0, reason: r.reason }, rulesCount: r.source === "RULE" ? 1 : 0, history: s.collectionEnabled ? t.history ?? [] : [], advice: { text: t.message, delayMinutes: t.delayMinutes } },
    key: "로컬 API",
    dest: r.destination,
    minutes: t.estimateMinutes,
    delay: t.delayMinutes,
    usual: t.baselineMinutes,
    mode: r.destination ? modes[t.kind] ?? legacyModes[t.status] ?? "missing" : "none",
    source: r.source,
    recStatus: r.status,
    holdType: r.holdType,
    candidates: s.collectionEnabled ? r.candidates ?? [] : [],
  };
}

/* core 스냅샷을 화면 값으로 옮긴다. 판단은 하지 않고 표시 형태만 고른다. */
function view() {
  if (live()) return liveView(live());
  const manual = st.manualId && st.manualId !== CORE.scenarios.learned_usual.recommendation.destinationId;
  const key = manual ? "manual_other" : st.scenario;
  const s = CORE.scenarios[key];
  const destId = st.manualId || s.recommendation.destinationId;
  const dest = destId ? destById(destId) : null;
  const minutes = s.estimate && s.estimate.minutes > 0 && s.advice ? s.estimate.minutes : null;
  const delay = s.advice ? s.advice.delayMinutes : null;
  let mode = "none";
  if (s.advice) {
    if (minutes === null) mode = st.scenario === "traffic_stale" ? "stale" : st.scenario === "live_unknown_units" ? "units" : "missing";
    else if (delay === null) mode = st.scenario === "traffic_stale" ? "stale" : "nocompare";
    else mode = s.advice.important ? "late" : "calm";
  }
  if (mode === "stale") return { s, key, dest, minutes: null, delay: null, mode };
  return { s, key, dest, minutes, delay, mode, usual: delay === null || minutes === null ? null : minutes - delay };
}

/* 추천 문구의 때. 요일·시간대 추천이라 아침만이 아니다. */
const daypart = (m) => (m < 360 ? "새벽" : m < 660 ? "아침" : m < 840 ? "점심" : m < 1080 ? "오후" : m < 1260 ? "저녁" : "밤");
const batchim = (w) => (w.charCodeAt(w.length - 1) - 0xac00) % 28 !== 0;
const daypartIf = (m) => daypart(m) + (batchim(daypart(m)) ? "이면" : "면");
const daypartAs = (m) => daypart(m) + (batchim(daypart(m)) ? "으로" : "로");

function leadSub(v) {
  if (st.manualId || v.source === "MANUAL") return "직접 고른 곳이에요";
  if (v.s.rulesCount > 0) return `${dayName(ctx().day)}요일 ${daypartAs(ctx().minute)} 등록해 둔 곳이에요`;
  return `${dayName(ctx().day)}요일 ${daypartIf(ctx().minute)} 가던 곳이에요`;
}

const historyBtn = () => {
  const v = view();
  const available = live() ? live().collectionEnabled && (v.s.history.length || v.s.recommendation.samples) : v.s.recommendation.samples;
  return available ? `<button class="ghost" data-act="why">지난 기록</button>` : "";
};

function verdict(v) {
  const eta = v.minutes ? `${clock(ctx().minute + v.minutes)} 도착 예정` : "";
  if (st.stop && v.minutes) {
    return `<div class="verdict verdict-solo"><div class="verdict-text">
      <div class="verdict-main">경유지를 추가했어요</div>
      <div class="verdict-sub">바로 갈 때 ${v.minutes}분이에요. 들르는 시간은 내비에서 확인해요</div></div></div>`;
  }
  if (v.mode === "late") {
    return `<div class="verdict is-late"><div class="big-time">${v.minutes}<small>분</small></div>
      <div class="verdict-text"><div class="verdict-main">평소보다 ${v.delay}분 더 걸려요</div>
      <div class="verdict-sub">${eta}</div></div>${historyBtn()}</div>`;
  }
  if (v.mode === "calm") {
    return `<div class="verdict"><div class="big-time">${v.minutes}<small>분</small></div>
      <div class="verdict-text"><div class="verdict-main">평소와 비슷해요</div>
      <div class="verdict-sub">${eta}</div></div>${historyBtn()}</div>`;
  }
  if (v.mode === "nocompare") {
    return `<div class="verdict"><div class="big-time">${v.minutes}<small>분</small></div>
      <div class="verdict-text"><div class="verdict-main">평소 시간은 기록을 모으는 중이에요</div>
      <div class="verdict-sub">${eta}</div></div>${historyBtn()}</div>`;
  }
  const title = { missing: "교통 정보를 받지 못했어요", stale: "교통 정보가 오래됐어요", units: "예상 시간을 아직 표시할 수 없어요" }[v.mode];
  const sub = { missing: "경로는 준비돼 있어요", stale: "다시 확인하면 새 정보를 불러와요", units: "경로는 준비돼 있어요" }[v.mode];
  return `<div class="verdict verdict-solo"><div class="verdict-text"><div class="verdict-main">${title}</div>
    <div class="verdict-sub">${sub}</div></div>
    <button class="ghost" data-act="recheck" ${st.checking ? "disabled" : ""}>${st.checking ? '<span class="spinner"></span>확인 중' : "다시 확인"}</button></div>`;
}

function routeStrip(v) {
  const locked = st.phase === "requesting" || st.phase === "auto";
  const stopNode = st.stop
    ? `<div class="route-node node-stop ${st.stopFresh ? "in" : ""}"><div class="disc">${I.flag()}</div></div>
       <div class="route-label lb-stop"><b>${esc(st.stop.name)}</b>${esc(st.stop.sub)}</div>`
    : `<button class="route-add" data-act="add-stop" aria-label="경유지 추가" ${locked || !st.parked ? "disabled" : ""}><span>${I.plus()}</span></button>
       <div class="route-label lb-stop"><b>경유지 추가</b></div>`;
  return `<div class="route">
    <div class="route-road"></div>
    <div class="route-node node-origin"><div class="disc">${I.car()}</div></div>
    ${stopNode}
    <div class="route-node node-dest"><div class="disc">${I.pin()}</div></div>
    <div class="route-label lb-origin"><b>현재 위치</b>${clock(ctx().minute)} 출발</div>
    <div class="route-label lb-dest"><b>${esc(v.dest.name)}</b>${v.minutes ? `${clock(ctx().minute + v.minutes)} 도착 예정` : "목적지"}</div>
  </div>`;
}

function homeMap() {
  const b = routeBox(40, 560 / 470);
  return `<div class="map-tile">${cityMap({ viewBox: `${b.x} ${b.y} ${b.w} ${b.h}`, stop: !!st.stop, u: 1 })}
    <div class="map-chip">예시 경로</div><div class="map-credit">${MAP.attribution}</div></div>`;
}

function topBar(title, back) {
  const left = back
    ? `<button class="ghost" data-act="${back}">${I.back()}뒤로</button>`
    : `<div class="bar-title">${APP_NAME}</div>`;
  return `<div class="bar">${left}${title || ""}<div class="bar-right"><div class="demo-mark">시연 데이터</div>
    ${back ? "" : `<button class="ghost" data-act="settings" aria-label="내 일상 설정">${I.gear()}내 일상</button>`}</div></div>`;
}

function homeActions(v) {
  if (LIVE.lost && live()) {
    return `<div class="actions"><button class="primary locked" disabled>${I.lock()}연결을 확인할 때까지 시작하지 않아요</button>
      <button class="secondary" data-act="reconnect">다시 연결</button></div>`;
  }
  if (st.routeUnknown) {
    return `<div class="actions"><button class="primary locked" disabled>${I.lock()}현재 경로를 확인하면 시작할 수 있어요</button>
      <button class="secondary" data-act="recheck-route">다시 확인</button></div>`;
  }
  if (st.activeRoute) {
    return `<div class="actions"><button class="primary" data-act="open-nav">내비 보기</button>
      <button class="secondary" data-act="dismiss">이번엔 넘기기</button></div>`;
  }
  if (st.phase === "auto") {
    return `<div class="actions"><button class="primary busy" disabled><span class="spinner"></span>곧 안내를 시작해요</button>
      <button class="secondary" data-act="cancel-auto">이번엔 취소</button></div>`;
  }
  if (st.phase === "requesting") {
    return `<div class="actions"><button class="primary busy" disabled><span class="spinner"></span>안내를 요청하고 있어요</button>
      <button class="secondary" disabled>이번엔 넘기기</button></div>`;
  }
  if (live() && st.parked && !live().navigation.canConfirm) {
    return `<div class="actions"><button class="primary locked" disabled>${I.lock()}지금은 안내를 시작할 수 없어요</button>
      <button class="secondary" data-act="change-place">장소 고르기</button></div>`;
  }
  if (!st.parked) {
    return `<div class="actions"><button class="primary locked" disabled>${I.lock()}정차를 확인하면 시작할 수 있어요</button>
      <button class="secondary" data-act="dismiss">이번엔 넘기기</button></div>`;
  }
  const noTraffic = ["missing", "stale", "units"].includes(v.mode);
  const sending = LIVE.busy ? "disabled" : "";
  return `<div class="actions"><button class="primary" data-act="start" ${sending}>${noTraffic ? "교통 없이 안내 시작" : "안내 시작"}</button>
    <button class="secondary" data-act="dismiss">이번엔 넘기기</button></div>`;
}

function proposalStrip() {
  if (!st.proposal) return "";
  return `<div class="strip"><div class="label">경유지가 도착했어요<small>${FIXTURE.stop.name}</small></div>
    <button class="ghost" data-act="open-proposal">보기</button></div>`;
}

function phoneStrip() {
  if (!st.carReady || st.routeUnknown) return "";
  const sub = st.activeRoute ? "지금 안내 중인 경로를 덮어쓰지 않았어요"
    : !st.parked ? "정차를 확인하면 바로 시작해요"
      : st.phase === "auto" ? "곧 안내를 시작해요" : "안내 시작만 누르면 돼요";
  return `<div class="strip info"><div class="label">폰에서 보낸 경로예요<small>${sub}</small></div></div>`;
}

function activeStrip() {
  if (st.routeUnknown) return `<div class="strip info"><div class="label">현재 경로를 확인하지 못했어요<small>기존 안내를 덮어쓰지 않도록 요청하지 않아요</small></div></div>`;
  /* 폰에서 보낸 경로 안내줄이 이미 같은 내용을 말한다. 두 줄이면 시간 표시가 밀려 가려진다. */
  if (!st.activeRoute || st.carReady) return "";
  return `<div class="strip info"><div class="label">지금 안내 중인 경로가 있어요<small>이 경로로 덮어쓰지 않아요</small></div></div>`;
}

function homeScreen() {
  if (st.dismissed) return dismissedScreen();
  const v = view();
  if (!v.dest) return emptyScreen(v);
  const locked = st.phase === "requesting" || st.phase === "auto";
  return `<div class="view">${topBar()}
    ${phoneStrip()}${proposalStrip()}${activeStrip()}
    <div class="lead">
      <div class="lead-text">
        <div class="lead-sub">${leadSub(v)}</div>
        <div class="lead-place"><h1>${esc(v.dest.name)}</h1>
          <button class="ghost" data-act="change-place" ${locked ? "disabled" : ""}>바꾸기</button></div>
        ${verdict(v)}
      </div>
      ${homeMap()}
    </div>
    ${routeStrip(v)}
    ${homeActions(v)}
  </div>`;
}

function emptyScreen(v) {
  const tie = live() ? v.holdType === "TIE" && v.candidates.length > 0 : st.scenario === "tie";
  const days = v.s.recommendation.samples;
  if (v.recStatus === "UNRESOLVED") {
    return `<div class="view">${topBar()}<div class="empty-box"><h1>저장한 장소를 찾지 못했어요</h1><p>내비의 장소 목록에서 다시 골라 주세요.</p>
      <div class="btn-row wide"><button class="primary" data-act="change-place">장소 고르기</button></div></div></div>`;
  }
  if (tie) {
    return `<div class="view">${topBar()}
      <div class="page-head"><h1>오늘은 어디로 갈까요?</h1><p>${live() ? `같은 일수로 고른 곳이 ${v.candidates.length}곳이에요` : "이 시간대에 자주 고른 곳이 두 곳이에요"}</p></div>
      <div class="places">${live() ? v.candidates.map((candidate) => placeButton(candidate.destination, candidate.samples)).join("") : dests().map((d) => placeButton(d)).join("")}</div></div>`;
  }
  const title = days > 0 ? "조금만 더 다니면 먼저 준비해 둘게요" : "오늘은 어디로 갈까요?";
  const body = days > 0
    ? `이 시간대에 고른 날이 ${days}일이에요. 3일이 되면 목적지를 먼저 꺼내 둘게요.`
    : "늘 가는 곳을 등록하면 다음부터 찾지 않아도 돼요.";
  return `<div class="view">${topBar()}
    <div class="empty-box"><h1>${title}</h1><p>${body}</p>
    ${days > 0 ? `<div class="days-progress">${[1, 2, 3].map((d) => `<i class="${d <= days ? "on" : ""}"></i>`).join("")}<span>${days}일 / 3일</span></div>` : ""}
    <div class="btn-row wide"><button class="primary" data-act="change-place">장소 고르기</button>
    <button class="secondary" data-act="onboard-place">평소 장소 등록</button></div></div></div>`;
}

function dismissedScreen() {
  return `<div class="view">${topBar()}
    <div class="empty-box"><h1>이번 추천을 넘겼어요</h1><p>내비는 그대로예요. 다른 곳으로 갈 때는 장소를 골라 주세요.</p>
    <div class="btn-row wide"><button class="primary" data-act="change-place">다른 곳 고르기</button>
    <button class="secondary" data-act="undo-dismiss">다시 보기</button></div></div></div>`;
}

function placeButton(d, samples) {
  return `<button class="place" data-act="pick" data-id="${d.id}">
    <span class="place-ico">${FIXTURE.placeSource[d.id] === "즐겨찾기" ? I.star() : I.clock()}</span>
    <span class="place-text"><div class="place-name">${esc(d.name)}</div><div class="place-sub">${esc(d.address)}</div></span>
    <span class="place-tag">${samples === undefined ? FIXTURE.placeSource[d.id] ?? "" : `${samples}일 선택`}</span></button>`;
}

function pickerScreen() {
  const stop = st.pickerFor === "stop";
  return `<div class="view">${topBar("", "back-home")}
    <div class="page-head"><h1>${stop ? "어디에 들를까요?" : "어디로 갈까요?"}</h1>
    <p>순정 내비의 즐겨찾기와 최근 목적지예요</p></div>
    <div class="places">${dests().filter((d) => !stop || d.id !== view().dest?.id).map((d) => placeButton(d)).join("")}</div></div>`;
}

function guideScreen() {
  const v = view();
  if (!v.dest) return homeScreen();
  const b = routeBox(70, 1681 / 1210);
  const shift = b.w * .24;
  const stateMap = {
    requesting: ["wait", '<span class="spinner"></span>', "안내를 요청하고 있어요"],
    verified: ["", I.check(), "안내를 시작했어요"],
    unconfirmed: ["warn", I.alert(), "안내가 시작됐는지 확인하지 못했어요"],
  };
  const [cls, icon, title] = stateMap[st.phase];
  const rows = [];
  if (v.minutes && !st.stop && st.phase === "verified") rows.push(["도착 예정", clock(ctx().minute + v.minutes)]);
  const nodes = `<div class="vroute"><div class="v-node o"><i>${I.car(46)}</i><b>현재 위치</b></div>
    ${st.stop ? `<div class="v-node s"><i>${I.flag(44)}</i><b>${esc(st.stop.name)}</b></div>` : ""}
    <div class="v-node d"><i>${I.pin(48)}</i><b>${esc(v.dest.name)}</b></div></div>`;
  const wp = guideWaypoint();
  let actions = "";
  if (st.phase === "verified") actions = `<button class="primary" data-act="open-nav">내비 보기</button>`;
  if (st.phase === "unconfirmed") actions = `<button class="primary" data-act="open-nav">내비에서 확인</button><button class="secondary" data-act="ack-unconfirmed">확인했어요</button>`;
  const body = st.phase === "unconfirmed"
    ? `<div class="guide-body">요청을 다시 보내지 않았어요. 내비 화면에서 경로를 확인해 주세요.</div>` : "";
  return `<div class="guide">${cityMap({ viewBox: `${b.x - shift} ${b.y} ${b.w} ${b.h}`, showRoute: st.phase !== "unconfirmed", stop: !!st.stop && st.phase !== "unconfirmed", u: 1.500 })}
    <div class="map-note">예시 경로</div><div class="map-credit">${MAP.attribution}</div>
    <div class="guide-side"><div class="guide-state ${cls}"><span class="dot">${icon}</span><span>${title}</span></div>
      ${nodes}
      <div class="guide-info">${rows.map(([k, val]) => `<div><span>${k}</span><b>${val}</b></div>`).join("")}</div>
      ${body}${wp}
      <div class="guide-actions">${actions}</div></div></div>`;
}

function guideWaypoint() {
  if (st.proposal) {
    return `<div class="strip"><div class="label">경유지가 도착했어요<small>${FIXTURE.stop.name}</small></div>
      <button class="ghost" data-act="open-proposal">보기</button></div>`;
  }
  if (st.waypoint === "requesting") return `<div class="strip info"><span class="spinner"></span><div class="label">경유지를 추가하고 있어요</div></div>`;
  if (st.waypoint === "added") return `<div class="strip info"><div class="label">경유지를 추가했어요<small>목적지는 그대로예요</small></div></div>`;
  if (st.waypoint === "unconfirmed") return `<div class="strip"><div class="label">경유지가 추가됐는지 확인하지 못했어요<small>다시 보내지 않았어요. 내비에서 확인해 주세요</small></div></div>`;
  if (st.waypoint === "exists") return `<div class="strip"><div class="label">이미 경유지가 있어요<small>바꾸려면 내비에서 직접 수정해 주세요</small></div></div>`;
  return "";
}

function onboardConsent() {
  return `<div class="view">${topBar()}
    <div class="grow"><div class="steps"><i class="on"></i><i></i><i></i></div>
    <div class="page-head" style="margin-top:22px"><h1>늘 가는 곳을 기억해 둘까요?</h1><p>다음부터는 경로가 준비된 채로 시작해요</p></div>
    <div class="consent">
      <div><span>기록하는 것</span>확인한 목적지, 요일과 시간대, 예상 시간</div>
      <div><span>기록하는 곳</span>이 앱 안에만 저장해요</div>
      <div><span>기록하지 않는 것</span>앱을 쓰지 않을 때의 운행</div>
      <div><span>언제든</span>내 일상에서 끄거나 지울 수 있어요</div>
    </div></div>
    <div class="btn-row wide"><button class="primary" data-act="consent-on">기록 켜고 시작</button>
    <button class="secondary" data-act="consent-off">기록 없이 시작</button></div></div>`;
}

function onboardPlace() {
  return `<div class="view">${topBar("", "onboard-back1")}
    <div class="steps"><i class="on"></i><i class="on"></i><i></i></div>
    <div class="page-head" style="margin-top:22px"><h1>평소에 어디로 가세요?</h1><p>순정 내비의 즐겨찾기와 최근 목적지에서 골라 주세요</p></div>
    <div class="places">${dests().map((d) => placeButton(d).replace('data-act="pick"', 'data-act="onboard-pick"')).join("")}</div></div>`;
}

function onboardTime() {
  const d = destById(st.onboardPlace) || dests()[0];
  return `<div class="view">${topBar("", "onboard-back2")}
    <div class="grow"><div class="steps"><i class="on"></i><i class="on"></i><i class="on"></i></div>
    <div class="page-head" style="margin-top:22px"><h1>언제 가세요?</h1><p>${esc(d.name)}</p></div>
    <div class="field"><div>요일</div><div class="chips">${FIXTURE.days.map((n, i) => `<button class="chip" data-act="onboard-day" data-day="${i}" aria-pressed="${st.onboardDays.includes(i)}">${n}</button>`).join("")}</div></div>
    <div class="field"><div>출발 시간</div><div class="chips">${FIXTURE.times.map(([label, m]) => `<button class="chip" data-act="onboard-time" data-minute="${m}" aria-pressed="${st.onboardTime === m}">${label}</button>`).join("")}</div></div></div>
    <button class="primary" data-act="onboard-done" ${st.onboardDays.length ? "" : "disabled"}>등록하고 시작</button></div>`;
}

function settingsScreen() {
  const rows = st.rules.map((r) => {
    const d = destById(r.id);
    return `<div class="row"><div><div class="row-title">${esc(d.name)}</div><div class="row-sub">${dayName(r.day + 1)}요일 ${clock(r.minute)}</div></div></div>`;
  }).join("");
  return `<div class="view">${topBar("", "back-home")}
    <div class="page-head"><h1>내 일상</h1></div>
    <div class="settings">
      <section><h2>평소 장소</h2>${rows || '<div class="row"><div class="row-sub">등록한 장소가 없어요</div></div>'}
        <button class="secondary" data-act="onboard-place">장소 추가</button></section>
      <section><h2>기록</h2>
        <div class="row"><div><div class="row-title">기록 저장</div><div class="row-sub">${st.collection ? "확인한 목적지를 이 앱에 남겨요" : "새 기록을 남기지 않아요"}</div></div>
          <button class="switch" role="switch" aria-checked="${st.collection}" aria-label="기록 저장" data-act="toggle-collection"></button></div>
        <button class="row" data-act="ask-clear"><div><div class="row-title">이력 지우기</div><div class="row-sub">기록 ${st.historyCount}건</div></div></button>
        <h2 class="hide-compact" style="margin-top:10px">기본 경로</h2>
        <div class="prefs hide-compact" role="group" aria-label="기본 경로" style="margin-top:0">
          ${Object.entries(FIXTURE.prefLabels).map(([k, label]) => `<button class="pref" data-act="pref" data-pref="${k}" aria-pressed="${st.pref === k}">${label.replace(" 경로", "")}</button>`).join("")}
        </div></section>
    </div></div>`;
}

/* 시트 */
function sheetHtml() {
  const v = view();
  if (st.sheet === "add-stop") {
    return `<div class="sheet"><h2>경유지를 어떻게 추가할까요?</h2>
      <div class="pick-list">
        <button class="place" data-act="invite"><span class="place-ico">${I.people()}</span><span class="place-text"><div class="place-name">친구에게 받기</div><div class="place-sub">친구가 지도에서 고른 곳이 바로 도착해요</div></span></button>
        <button class="place" data-act="pick-stop"><span class="place-ico">${I.star()}</span><span class="place-text"><div class="place-name">내 장소에서 고르기</div><div class="place-sub">즐겨찾기와 최근 목적지</div></span></button>
      </div><div class="one"><button class="secondary" data-act="close-sheet">닫기</button></div></div>`;
  }
  if (st.sheet === "invite") {
    return `<div class="sheet"><h2>친구에게 위치를 받아요</h2>
      <div class="invite"><div class="qr"><svg viewBox="-1 -1 ${QR.size + 2} ${QR.size + 2}" aria-label="예시 초대 코드" role="img"><path d="${QR.path}" fill="#111"/></svg></div>
      <ol><li>휴대폰으로 코드를 스캔해요</li><li>열린 링크를 친구에게 보내요</li><li>친구가 고른 곳이 여기로 도착해요</li></ol></div>
      <div class="btn-row"><button class="secondary" data-act="close-sheet">닫기</button>
      <button class="secondary" data-act="wait-proposal">기다리기</button></div></div>`;
  }
  if (st.sheet === "proposal") {
    const s = pointOnRoute(FIXTURE.stopRouteAt);
    const w = 320;
    const box = `${s[0] - w / 2} ${s[1] - w * .135} ${w} ${w * .27}`;
    const active = st.screen === "guide";
    return `<div class="sheet"><h2>${FIXTURE.stop.name}</h2>
      <p>받은 지도 링크의 위치예요</p>
      <div class="sheet-map">${cityMap({ viewBox: box, showRoute: true, stop: true, u: .35 })}<div class="map-chip">예시 위치</div></div>
      <div class="btn-row wide"><button class="primary" data-act="accept-stop">경유지로 추가</button>
      <button class="secondary" data-act="skip-stop">넘기기</button></div></div>`;
  }
  if (st.sheet === "why") {
    const past = v.s.history || [];
    const days = v.s.recommendation.samples;
    if (live()) {
      const history = past.map((record) => `<div class="week on" data-history-date="${esc(record.localDate)}" data-history-minutes="${record.minutes}"><b>${record.minutes}분</b><time datetime="${esc(record.localDate)}">${esc(record.localDate)}</time></div>`).join("");
      const summary = live().collectionEnabled
        ? `${esc(v.s.recommendation.reason)}${v.usual ? ` 비교한 예상 시간의 중앙값은 ${v.usual}분이에요.` : " 평소 시간을 비교하려면 3일분이 필요해요."}`
        : "기록 저장이 꺼져 있어 지난 기록을 표시하지 않아요.";
      return `<div class="sheet"><h2>왜 이 경로인가요?</h2>
        <p>${summary}</p>
        ${history ? `<div class="weeks history-dates">${history}</div>` : ""}
        <div class="one"><button class="secondary" data-act="close-sheet">닫기</button></div></div>`;
    }
    const cells = [6, 5, 4, 3, 2, 1].map((w) => {
      const hit = past.find((p) => p.weeksAgo === w);
      return `<div class="week ${w <= days ? "on" : ""}"><b>${hit ? `${hit.minutes}분` : w <= days ? "선택" : "없음"}</b>${w}주 전</div>`;
    }).join("");
    return `<div class="sheet"><h2>왜 이 경로인가요?</h2>
      <p>최근 6주 중 ${dayName(ctx().day)}요일 ${daypart(ctx().minute)}에 ${days}일 이곳을 골랐어요. 지난 예상 시간도 함께 비교해요.</p>
      <div class="weeks">${cells}</div>
      <div class="one"><button class="secondary" data-act="close-sheet">닫기</button></div></div>`;
  }
  if (st.sheet === "clear") {
    return `<div class="sheet"><h2>이력을 지울까요?</h2><p>선택과 예상 시간 기록 ${st.historyCount}건을 지워요. 평소 장소와 기본 경로는 남아요.</p>
      <div class="btn-row"><button class="secondary" data-act="close-sheet">취소</button>
      <button class="primary" data-act="clear-history">이력 지우기</button></div></div>`;
  }
  return "";
}

const SCREENS = { home: homeScreen, picker: pickerScreen, guide: guideScreen, settings: settingsScreen, onboard1: onboardConsent, onboard2: onboardPlace, onboard3: onboardTime };

function render() {
  const root = $("screen");
  root.dataset.theme = st.theme;
  root.dataset.layout = st.layout;
  const sheet = st.sheet ? `<div class="scrim">${sheetHtml()}</div>` : "";
  const toast = st.toast ? `<div class="toast" role="status">${esc(st.toast)}</div>` : "";
  $("app").innerHTML = SCREENS[st.screen]() + sheet + toast;
  st.stopFresh = false;
  renderShell();
  renderPhone();
  renderPanel();
  $("carOff").hidden = st.carOn;
  fit();
}

function renderShell() {
  const parked = st.parked;
  $("driving").innerHTML = `<div class="drv-top"><span>500 km</span>
      <div class="drv-gears">${["P", "R", "N", "D"].map((g) => `<span class="${(parked ? "P" : "D") === g ? "on" : ""}">${g}</span>`).join("")}</div></div>
    <div class="drv-speed">${parked ? 0 : 12}<small>km/h</small></div>
    <svg class="drv-art" viewBox="0 0 853 780" fill="none">
      <path d="M376 40 90 760M477 40 763 760" stroke="var(--asphalt-3)" stroke-width="10" stroke-linecap="round"/>
      <path d="M426 60v700" stroke="var(--asphalt-3)" stroke-width="8" stroke-dasharray="46 54"/>
      <rect x="306" y="440" width="240" height="176" rx="44" fill="var(--paint-3)"/>
      <rect x="342" y="462" width="168" height="66" rx="20" fill="var(--shell)"/>
      <rect x="326" y="566" width="46" height="16" rx="8" fill="#d8513e"/><rect x="480" y="566" width="46" height="16" rx="8" fill="#d8513e"/>
    </svg>
    <div class="drv-media"><i>${I.clock(44)}</i><span>재생 중인 항목 없음</span></div>`;
  $("status").innerHTML = `<div>${I.lock(38)}</div><div><span>LTE</span><span>${clock(ctx().minute)}</span></div>`;
  $("gnb").innerHTML = `<div class="gnb-side"><b>23.5°</b><span>A/C</span></div>
    <div class="gnb-mid"><div class="tile">${I.car(42)}</div><div class="tile">${I.pin(42)}</div><div class="tile">${I.star(38)}</div><div class="gnb-sep"></div><div class="tile app-tile">늘</div></div>
    <div class="gnb-side"><span>AUTO</span><b>23.5°</b></div>`;
  if (st.layout === "split") {
    const b = routeBox(90, 847 / 1210);
    const showing = st.screen === "guide" && st.phase === "verified";
    $("navwin").innerHTML = cityMap({ viewBox: `${b.x} ${b.y} ${b.w} ${b.h}`, showRoute: showing, stop: showing && !!st.stop, u: 1.300 }) + `<div class="navwin-tag">순정 내비 자리 (시연)</div>`;
  }
}

/* 휴대폰: 엘리베이터에서 내려오면서 한 번 누르는 화면. 전체가 시연용이다. */
function phoneHtml() {
  const v = view();
  const top = `<div class="ph-top"><span>${clock(ctx().minute - 8)}</span><span>LTE</span></div>
    <div class="ph-head"><b>${APP_NAME}</b><span>시연 데이터</span></div>`;
  if (!v.dest) {
    return `${top}<div class="ph-body"><div class="ph-done"><h2>오늘은 어디로 갈까요?</h2><p>늘 가는 곳을 등록하면 다음부터 한 번에 보낼 수 있어요.</p></div></div>`;
  }
  if (st.phone === "sent" || st.phone === "waiting") {
    const sent = st.phone === "sent";
    return `${top}<div class="ph-body"><div class="ph-done">
      <i class="${sent ? "" : "wait"}">${sent ? I.check(40) : I.clock(40)}</i>
      <h2>${sent ? "차에 보냈어요" : "차를 켜면 이어져요"}</h2>
      <p>${sent ? "타면 안내가 시작돼요." : "경로를 맡아 뒀어요. 차에서 늘길이 열리면 안내가 시작돼요."}</p></div></div>
      <div class="ph-bottom"><button class="ph-text" data-ph="undo">보내기 취소</button></div>`;
  }
  const late = v.mode === "late";
  const time = v.minutes && !st.stop
    // The phone estimate comes from a different route engine than the car's native navi, so it is shown as approximate.
    ? `<div class="ph-time"><b><small>약 </small>${v.minutes}<small>분</small></b><span class="${late ? "late" : ""}">${late ? `평소보다 ${v.delay}분쯤 더 걸려요` : v.mode === "calm" ? "평소와 비슷해요" : "평소 기록을 모으는 중"}</span></div>`
    : st.stop ? `<div class="ph-time"><span>경유지를 추가했어요</span></div>` : `<div class="ph-time"><span>교통 정보를 받지 못했어요</span></div>`;
  const stopRow = st.stop ? `<div class="ph-node s"><i>${I.flag(24)}</i>${esc(st.stop.name)}</div>` : "";
  const card = st.proposal ? `<div class="ph-card"><div>경유지가 도착했어요<small>${FIXTURE.stop.name}</small></div><button data-ph="accept">추가</button></div>` : "";
  const sending = st.phone === "sending";
  const phoneBox = routeBox(36, 350 / 200);
  return `${top}<div class="ph-body"><div class="ph-sub">${leadSub(v)}</div><h2 class="ph-place">${esc(v.dest.name)}</h2>
    ${time}
    <div class="ph-route"><div class="ph-node o"><i>${I.car(24)}</i>현재 위치</div>${stopRow}<div class="ph-node d"><i>${I.pin(26)}</i>${esc(v.dest.name)}</div></div>
    ${card}
    <div class="ph-map">${cityMap({ viewBox: `${phoneBox.x} ${phoneBox.y} ${phoneBox.w} ${phoneBox.h}`, stop: !!st.stop, u: 1.100 })}<span>예시 경로</span></div></div>
    <div class="ph-bottom"><button class="ph-primary" data-ph="send" ${sending ? "disabled" : ""}>${sending ? '<span class="spinner"></span>보내는 중' : "차에 경로 보내기"}</button>
    <button class="ph-text" data-ph="skip" ${sending ? "disabled" : ""}>이번엔 넘기기</button></div>`;
}

function renderPhone() {
  $("phoneWrap").classList.toggle("on", st.phoneOn);
  $("phone").dataset.theme = st.theme;
  if (st.phoneOn) $("phone").innerHTML = phoneHtml();
}

const PHONE = {
  send() {
    st.phone = "sending";
    render();
    after(700, () => {
      st.phone = st.carLinked ? "sent" : "waiting";
      st.carReady = true;
      render();
    });
  },
  undo() { st.phone = "ready"; st.carReady = false; render(); },
  skip() { st.dismissed = true; render(); },
  accept() { st.proposal = false; st.stop = { ...FIXTURE.stop }; st.stopFresh = true; render(); },
};

/* API 명령. 한 번에 하나만 보내고 자동으로 다시 보내지 않는다. */
const sleep = (ms) => new Promise((done) => setTimeout(done, ms));

function sync(snap) {
  LIVE.snap = snap;
  LIVE.lost = false;
  st.pref = snap.preference;
  st.collection = snap.collectionEnabled;
  st.historyCount = snap.counts.selections;
  st.parked = snap.vehicle.state === "PARKED";
  st.dismissed = snap.navigation.state === "DISMISSED";
  st.activeRoute = snap.navigation.activeRoute === "ACTIVE";
  st.routeUnknown = snap.navigation.activeRoute === "UNAVAILABLE";
  const state = snap.navigation.state;
  if (state === "VERIFIED" || state === "UNCONFIRMED") {
    st.screen = "guide";
    st.phase = state === "VERIFIED" ? "verified" : "unconfirmed";
  } else if (state === "REQUESTING") {
    st.phase = "requesting";
  } else if (st.phase === "verified" || st.phase === "unconfirmed") {
    st.phase = null;
    if (st.screen === "guide") st.screen = "home";
  }
  /* 같은 경고는 처음 받았을 때 한 번만 알린다. */
  const noticeCode = snap.notice?.code ?? null;
  if (noticeCode && noticeCode !== LIVE.notice) toast(noticeText(noticeCode));
  LIVE.notice = noticeCode;
  return snap;
}

function noticeText(code) {
  return {
    STORAGE_ERROR: "저장하지 못했어요. 이전 설정을 유지해요.",
    CONSENT_NOT_SAVED: "기록 끄기를 저장하지 못했어요. 이번 실행의 기록과 새 안내를 멈췄어요.",
    TRIP_LOCKED: "이번 이동은 이미 요청했어요. 다시 보내지 않아요.",
    VEHICLE_NOT_PARKED: "정차를 확인하지 못해 요청하지 않았어요",
    DESTINATION_NOT_FOUND: "장소를 찾지 못했어요. 목록에서 다시 골라 주세요.",
    DESTINATION_AMBIGUOUS: "장소를 하나로 정하지 못했어요. 목록에서 다시 골라 주세요.",
    NOT_READY: "목적지를 먼저 골라 주세요",
    ALREADY_GUIDING: "이미 안내 중이라 요청하지 않았어요",
    ROUTE_UNAVAILABLE: "현재 경로를 확인하지 못해 요청하지 않았어요",
  }[code] ?? "요청을 처리하지 못했어요";
}

async function recover(error) {
  try {
    sync(await LIVE.api.getState());
    toast(noticeText(error.code));
  } catch {
    LIVE.lost = true;
    st.toast = null;
  }
}

async function cmd(fn) {
  if (LIVE.busy || LIVE.confirming) return null;
  LIVE.busy = true;
  render();
  try {
    return sync(await fn(LIVE.api));
  } catch (error) {
    await recover(error);
    return null;
  } finally {
    LIVE.busy = false;
    render();
  }
}

/* 안내 요청은 사용자 확인 뒤 한 번만 보낸다. 실패해도 다시 보내지 않고 상태부터 읽는다. */
async function startLive() {
  if (LIVE.confirming || LIVE.busy || LIVE.lost || !live()?.navigation.canConfirm) return;
  LIVE.confirming = true;
  st.phase = "requesting";
  render();
  const shown = sleep(800);
  try {
    const snap = await LIVE.api.confirm();
    await shown;
    sync(snap);
  } catch (error) {
    await shown;
    st.phase = null;
    await recover(error);
  } finally {
    LIVE.confirming = false;
    render();
  }
}

async function reconnect() {
  try {
    sync(await LIVE.api.getState());
    toast("다시 연결했어요");
  } catch {
    toast("아직 연결하지 못했어요");
  }
  render();
}

async function openApp() {
  const { recommendation, navigation } = LIVE.snap;
  if (["VERIFIED", "UNCONFIRMED", "REQUESTING", "DISMISSED"].includes(navigation.state)) return;
  if (recommendation.status !== "NONE") return;
  if (!LIVE.snap.collectionEnabled && LIVE.snap.counts.rules === 0) { st.screen = "onboard1"; return; }
  await cmd(async (api) => {
    const snap = await api.recommend(false);
    return snap.recommendation.destination ? api.refreshTraffic() : snap;
  });
}

/* 시연 패널 전용. 사용자가 버튼을 눌렀을 때만 모의 데이터를 준비한다. */
const BASE_NOW = Date.parse("2026-11-04T08:00:00+09:00");

async function prepLive(want) {
  const api = LIVE.api;
  await api.newDemoTrip();
  await api.setContext(BASE_NOW, "Asia/Seoul");
  const traffic = { learned_congested: "CONGESTED", traffic_missing: "MISSING", traffic_stale: "STALE", live_unknown_units: "UNKNOWN_UNITS" }[want.scenario] ?? "NORMAL";
  const activeRoute = want.activeRoute ? "ACTIVE" : want.routeUnknown ? "UNAVAILABLE" : "INACTIVE";
  await api.setDemoScenario({ traffic, parked: want.parked, failNext: want.phase === "unconfirmed", activeRoute });
  const seeded = want.scenario !== "no_history";
  await api.setConsent(seeded);
  await api.clearHistory();
  if (seeded) {
    const destinationIds = (await api.getState()).destinations.map((d) => d.id);
    const options = {
      not_enough: { days: 2, destinationIds: destinationIds.slice(0, 1) },
      tie: { days: 3, destinationIds },
      compare_short: { days: 3, estimateDays: 2, destinationIds: destinationIds.slice(0, 1) },
    }[want.scenario];
    await api.seedDemo(options);
  }
  LIVE.historyOnly = want.scenario !== "rule_usual";
  let snap = await api.recommend(LIVE.historyOnly);
  if (snap.recommendation.destination) snap = await api.refreshTraffic();
  snap = await api.refreshActiveRoute();
  if (want.dismissed) snap = await api.dismiss();
  if (want.phase === "verified" || want.phase === "unconfirmed") snap = await api.confirm();
  return snap;
}

/* 동작 */
function toast(text) {
  st.toast = text;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { st.toast = null; render(); }, 3200);
}

function trySave(ok, failText) {
  if (st.nextSaveFails) {
    st.nextSaveFails = false;
    toast(failText || "저장하지 못했어요. 이전 설정을 유지해요.");
    render();
    return;
  }
  ok();
  render();
}

function runFlow(key) {
  const flow = CORE.flows[key];
  st.flow = key;
  /* 상태 순서는 DepartureController 실행 기록을 그대로 따른다. */
  const states = flow.states.map((s) => s.state);
  if (!states.includes("REQUESTING")) { toast("정차를 확인하지 못해 요청하지 않았어요"); render(); return; }
  st.phase = "requesting";
  st.screen = "home";
  render();
  after(900, () => {
    st.screen = "guide";
    st.phase = states.includes("VERIFIED") ? "verified" : "unconfirmed";
    if (st.phase === "verified" && st.collection) st.historyCount += flow.recordedSelections;
    render();
  });
}

const ACT = {
  settings() { st.screen = "settings"; render(); },
  "back-home"() { st.screen = "home"; render(); },
  "change-place"() { st.pickerFor = "destination"; st.screen = "picker"; render(); },
  reconnect() { reconnect(); },
  "recheck-route"() {
    if (live()) { cmd((api) => api.refreshActiveRoute()).then((snap) => { if (snap?.navigation.activeRoute === "UNAVAILABLE") { toast("아직 현재 경로를 확인하지 못했어요"); render(); } }); return; }
    st.routeUnknown = false;
    render();
  },
  pick(el) {
    const id = el.dataset.id;
    if (st.pickerFor !== "stop" && live()) {
      const d = destById(id);
      st.pickerFor = "destination";
      st.screen = "home";
      cmd(async (api) => {
        await api.selectDestination(d.id, d.subId);
        return api.refreshTraffic();
      });
      return;
    }
    if (st.pickerFor === "stop") {
      const d = destById(id);
      st.stop = { id, name: d.name, sub: "내 장소에서 고름" };
      st.stopFresh = true;
    } else {
      st.manualId = id;
      if (!CORE.scenarios[st.scenario].recommendation.destinationId) st.scenario = "learned_usual";
      st.dismissed = false;
    }
    st.pickerFor = "destination";
    st.screen = "home";
    render();
  },
  pref(el) {
    const value = el.dataset.pref;
    if (live()) { cmd((api) => api.setPreference(value)); return; }
    trySave(() => { st.pref = value; });
  },
  start() {
    if (live()) { startLive(); return; }
    if (!st.parked || st.phase) return;
    runFlow(st.nextResult);
  },
  dismiss() {
    if (live()) { cmd((api) => api.dismiss()); return; }
    st.dismissed = true;
    render();
  },
  "cancel-auto"() {
    clearTimers();
    st.phase = null;
    st.carReady = false;
    toast("안내를 시작하지 않았어요");
    render();
  },
  "undo-dismiss"() {
    if (live()) {
      cmd(async (api) => {
        const snap = await api.recommend(LIVE.historyOnly);
        return snap.recommendation.destination ? api.refreshTraffic() : snap;
      });
      return;
    }
    st.dismissed = false;
    render();
  },
  "add-stop"() { st.sheet = "add-stop"; render(); },
  invite() { st.sheet = "invite"; render(); },
  "pick-stop"() { st.sheet = null; st.pickerFor = "stop"; st.screen = "picker"; render(); },
  "wait-proposal"() { st.sheet = null; render(); after(1400, () => { st.proposal = true; render(); }); },
  "close-sheet"() { st.sheet = null; render(); },
  why() { st.sheet = "why"; render(); },
  "open-proposal"() { st.sheet = "proposal"; render(); },
  "accept-stop"() {
    st.sheet = null;
    st.proposal = false;
    if (st.screen === "guide") {
      if (st.stop) { st.waypoint = "exists"; render(); return; }
      st.waypoint = "requesting";
      render();
      after(900, () => {
        st.waypoint = st.nextWaypoint;
        if (st.waypoint === "added") st.stop = { ...FIXTURE.stop };
        render();
      });
      return;
    }
    st.stop = { ...FIXTURE.stop };
    st.stopFresh = true;
    render();
  },
  "skip-stop"() { st.sheet = null; st.proposal = false; toast("경로는 그대로예요"); render(); },
  recheck() {
    if (live()) {
      st.checking = true;
      cmd((api) => api.refreshTraffic()).then((snap) => {
        st.checking = false;
        if (snap) toast(snap.traffic.estimateMinutes ? "교통 정보를 새로 받았어요" : "아직 교통 정보를 받지 못했어요");
        render();
      });
      return;
    }
    st.checking = true;
    render();
    after(800, () => {
      st.checking = false;
      if (st.scenario === "traffic_stale") { st.scenario = "learned_usual"; toast("교통 정보를 새로 받았어요"); } else toast("아직 교통 정보를 받지 못했어요");
      render();
    });
  },
  "open-nav"() { toast("순정 내비 화면으로 이동해요 (시연)"); render(); },
  "ack-unconfirmed"() {
    if (live()) { toast("이번 이동은 다시 요청하지 않아요"); render(); return; }
    st.screen = "home";
    st.phase = null;
    st.dismissed = true;
    render();
  },
  "consent-on"() {
    if (live()) { cmd((api) => api.setConsent(true)).then((snap) => { if (snap) { st.screen = "onboard2"; render(); } }); return; }
    trySave(() => { st.collection = true; st.screen = "onboard2"; });
  },
  "consent-off"() {
    if (live()) { cmd((api) => api.setConsent(false)).then((snap) => { if (snap) { st.screen = "onboard2"; render(); } }); return; }
    st.collection = false;
    st.screen = "onboard2";
    render();
  },
  "onboard-back1"() { st.screen = "onboard1"; render(); },
  "onboard-back2"() { st.screen = "onboard2"; render(); },
  "onboard-place"() { st.screen = "onboard2"; render(); },
  "onboard-pick"(el) { st.onboardPlace = el.dataset.id; st.screen = "onboard3"; render(); },
  "onboard-day"(el) {
    const d = Number(el.dataset.day);
    st.onboardDays = st.onboardDays.includes(d) ? st.onboardDays.filter((x) => x !== d) : [...st.onboardDays, d];
    render();
  },
  "onboard-time"(el) { st.onboardTime = Number(el.dataset.minute); render(); },
  "onboard-done"() {
    if (live()) {
      const d = destById(st.onboardPlace);
      const base = live().context;
      cmd(async (api) => {
        /* registerRoutine은 현재 시각의 요일·분에 등록하므로 시각을 옮겨 등록하고 되돌린다. */
        for (const day of st.onboardDays) {
          const shift = ((day + 1 - base.day) * 1440 + (st.onboardTime - base.minute)) * 60000;
          await api.setContext(base.now + shift, base.zoneId);
          await api.registerRoutine(d.id, d.subId);
        }
        await api.setContext(base.now, base.zoneId);
        LIVE.historyOnly = false;
        const snap = await api.recommend(false);
        return snap.recommendation.destination ? api.refreshTraffic() : snap;
      }).then((snap) => {
        if (!snap) return;
        st.screen = "home";
        toast("평소 장소를 등록했어요");
        render();
      });
      return;
    }
    trySave(() => {
      st.onboardDays.forEach((day) => {
        st.rules = st.rules.filter((r) => !(r.day === day && r.minute === st.onboardTime));
        st.rules.push({ id: st.onboardPlace, day, minute: st.onboardTime });
      });
      st.scenario = "rule_usual";
      st.manualId = st.onboardPlace === dests()[0].id ? null : st.onboardPlace;
      st.screen = "home";
      toast("평소 장소를 등록했어요");
    });
  },
  "toggle-collection"() {
    if (live()) { const next = !st.collection; cmd((api) => api.setConsent(next)); return; }
    trySave(() => { st.collection = !st.collection; });
  },
  "ask-clear"() { st.sheet = "clear"; render(); },
  "clear-history"() {
    st.sheet = null;
    if (live()) { cmd((api) => api.clearHistory()).then((snap) => { if (snap) { toast("이력을 지웠어요"); render(); } }); return; }
    trySave(() => {
      /* core 확인 결과: 이력만 지워지고 등록 규칙은 남는다. */
      st.historyCount = CORE.storage.selectionsAfterClear;
      toast("이력을 지웠어요");
    }, "지우지 못했어요. 기록은 그대로예요.");
  },
};

/* 시연 시나리오: 패널과 검증 도구가 같이 쓴다 */
const fresh = (patch) => () => {
  const keep = { theme: st.theme, layout: st.layout, panel: st.panel, phoneOn: st.phoneOn };
  clearTimers();
  st = { ...initial(), ...keep, ...patch };
};
const STATES = {
  "폰: 내려오는 중": fresh({ scenario: "learned_congested", carOn: false, phoneOn: true }),
  "폰: 친구 제안": fresh({ scenario: "learned_congested", carOn: false, phoneOn: true, proposal: true }),
  "폰: 들를 곳 포함": fresh({ scenario: "learned_congested", carOn: false, phoneOn: true, stop: { ...FIXTURE.stop } }),
  "폰: 보냄": fresh({ scenario: "learned_congested", carOn: false, phoneOn: true, phone: "sent", carReady: true, stop: { ...FIXTURE.stop } }),
  "폰: 차 대기": fresh({ scenario: "learned_congested", carOn: false, phoneOn: true, phone: "waiting", carReady: true, carLinked: false }),
  "차: 곧 시작": fresh({ scenario: "learned_congested", phone: "sent", carReady: true, stop: { ...FIXTURE.stop }, phase: "auto" }),
  "차: 보냈지만 정차 미확인": fresh({ scenario: "learned_congested", phone: "sent", carReady: true, parked: false }),
  "차: 보냈지만 안내 중": fresh({ scenario: "learned_congested", phone: "sent", carReady: true, activeRoute: true }),
  첫사용: fresh({ screen: "onboard1", scenario: "no_history" }),
  장소등록: fresh({ screen: "onboard2", scenario: "no_history" }),
  시간등록: fresh({ screen: "onboard3", scenario: "no_history", onboardPlace: "36khp2hdj2cfcm5nqhe3" }),
  "기록 없음": fresh({ scenario: "no_history" }),
  "기록 부족": fresh({ scenario: "not_enough" }),
  "후보 동률": fresh({ scenario: "tie" }),
  "평소와 비슷": fresh({ scenario: "learned_usual" }),
  "큰 지연": fresh({ scenario: "learned_congested" }),
  "등록 규칙": fresh({ scenario: "rule_usual" }),
  "비교 부족": fresh({ scenario: "compare_short" }),
  "교통 없음": fresh({ scenario: "traffic_missing" }),
  "교통 오래됨": fresh({ scenario: "traffic_stale" }),
  "단위 미확인": fresh({ scenario: "live_unknown_units" }),
  "제안 도착": fresh({ scenario: "learned_congested", proposal: true }),
  "제안 확인": fresh({ scenario: "learned_congested", proposal: true, sheet: "proposal" }),
  "들를 곳 포함": fresh({ scenario: "learned_congested", stop: { ...FIXTURE.stop } }),
  "들를 곳 추가": fresh({ scenario: "learned_usual", sheet: "add-stop" }),
  "친구 초대": fresh({ scenario: "learned_usual", sheet: "invite" }),
  "정차 미확인": fresh({ scenario: "learned_congested", parked: false }),
  "요청 중": fresh({ scenario: "learned_usual", phase: "requesting" }),
  "안내 시작됨": fresh({ scenario: "learned_usual", screen: "guide", phase: "verified" }),
  "들를 곳과 안내": fresh({ scenario: "learned_congested", screen: "guide", phase: "verified", stop: { ...FIXTURE.stop } }),
  "결과 미확인": fresh({ scenario: "learned_usual", screen: "guide", phase: "unconfirmed" }),
  "안내 중 제안": fresh({ scenario: "learned_usual", screen: "guide", phase: "verified", proposal: true }),
  "경유지 요청 중": fresh({ scenario: "learned_usual", screen: "guide", phase: "verified", waypoint: "requesting" }),
  "경유지 추가됨": fresh({ scenario: "learned_usual", screen: "guide", phase: "verified", waypoint: "added", stop: { ...FIXTURE.stop } }),
  "경유지 미확인": fresh({ scenario: "learned_usual", screen: "guide", phase: "verified", waypoint: "unconfirmed" }),
  "경유지 있음": fresh({ scenario: "learned_usual", screen: "guide", phase: "verified", waypoint: "exists", stop: { ...FIXTURE.stop } }),
  "넘김": fresh({ scenario: "learned_usual", dismissed: true }),
  "이미 안내 중": fresh({ scenario: "learned_usual", activeRoute: true }),
  "경로 조회 불가": fresh({ scenario: "learned_usual", routeUnknown: true }),
  "장소 고르기": fresh({ scenario: "learned_usual", screen: "picker" }),
  "추천 이유": fresh({ scenario: "learned_congested", sheet: "why" }),
  "내 일상": fresh({ screen: "settings" }),
  "이력 지우기": fresh({ screen: "settings", sheet: "clear" }),
};
const DEMO_ORDER = ["평소와 비슷", "큰 지연", "들를 곳 추가", "친구 초대", "제안 도착", "제안 확인", "들를 곳 포함", "요청 중", "들를 곳과 안내", "안내 중 제안", "경유지 추가됨", "폰: 내려오는 중", "폰: 보냄", "차: 곧 시작", "안내 시작됨"];

function renderPanel() {
  const panel = $("panel");
  panel.classList.toggle("open", st.panel);
  $("panelTab").textContent = st.panel ? "닫기" : "시연 설정";
  const btn = (label, attr, on) => `<button class="pbtn" ${attr} aria-pressed="${!!on}">${label}</button>`;
  const v = view();
  const s = v.s;
  const snap = live();
  const raw = [
    snap ? `연결: 로컬 API (${snap.mode})` : LIVE.api ? "연결: 로컬 API 사용 가능, 이 상태는 오프라인 예시" : "연결: 오프라인 예시",
    `출처: ${v.key}`,
    `reason: ${s.recommendation.reason}`,
    `samples: ${s.recommendation.samples}`,
    `advice: ${s.advice ? s.advice.text : "없음"}`,
    `delay: ${s.advice ? s.advice.delayMinutes : "없음"}`,
    snap ? `navigation: ${snap.navigation.state} / ${snap.navigation.code} / activeRoute ${snap.navigation.activeRoute} / canConfirm ${snap.navigation.canConfirm}` : "",
    snap ? `안내 요청: ${snap.counts.navigationRequests}회, 선택 기록 ${snap.counts.selections}건` : "",
    !snap && st.flow ? `flow: ${st.flow} / ${CORE.flows[st.flow].states.map((x) => x.state).join(" > ")} / 요청 ${CORE.flows[st.flow].navigationRequests}회` : "",
  ].filter(Boolean).join("\n");
  panel.innerHTML = `<h2>시연 설정</h2><p>운전자 화면이 아닙니다. D 키로 숨길 수 있어요.</p>
    <h3>시연 순서 ${st.demoStep + 1} / ${DEMO_ORDER.length}</h3>
    <div class="pstep"><button class="pbtn" data-demo="prev">이전</button><button class="pbtn" data-demo="next">다음</button></div>
    <h3>화면</h3><div class="pgrid">
      ${btn("야간", 'data-set="theme" data-val="night"', st.theme === "night")}${btn("주간", 'data-set="theme" data-val="day"', st.theme === "day")}
      ${btn("전체 창", 'data-set="layout" data-val="full"', st.layout === "full")}${btn("분할 창", 'data-set="layout" data-val="split"', st.layout === "split")}</div>
    <h3>차량과 다음 응답</h3><div class="pgrid">
      ${btn(st.parked ? "정차 확인됨" : "정차 미확인", 'data-toggle="parked"', !st.parked)}
      ${btn("이미 안내 중", 'data-toggle="activeRoute"', st.activeRoute)}
      ${btn("안내 결과 미확인", 'data-next="result"', st.nextResult === "confirm_unconfirmed")}
      ${btn("경유지 결과 미확인", 'data-next="waypoint"', st.nextWaypoint === "unconfirmed")}
      ${btn("다음 저장 실패", 'data-toggle="nextSaveFails"', st.nextSaveFails)}
      ${btn("친구 제안 보내기", 'data-demo="proposal"', st.proposal)}
      ${btn("차와 연결 끊김", 'data-toggle-off="carLinked"', !st.carLinked)}
      ${btn(st.phoneOn ? "폰 화면 숨기기" : "폰 화면 보기", 'data-toggle="phoneOn"', st.phoneOn)}</div>
    <h3>상태로 바로 가기</h3><div class="pgrid">${Object.keys(STATES).map((k) => `<button class="pbtn" data-state="${k}">${k}</button>`).join("")}</div>
    <h3>현재 데이터 (개발 정보)</h3><div class="pinfo">${esc(raw)}</div>
    <p style="margin-top:8px">교통 값과 이력은 모의 값입니다. 경유지 상태는 core 실행 결과가 아닌 화면 시나리오입니다.</p>`;
}

async function go(name) {
  STATES[name]();
  if (!LIVE.api) { render(); return; }
  st.offlineView = false;
  const want = { ...st };
  /* 새 응답이 오기 전에는 그리지 않는다. 이전 상태와 새 화면이 섞이면 안 된다. */
  LIVE.busy = true;
  try {
    sync(await prepLive(want));
    st.screen = want.phase === "verified" || want.phase === "unconfirmed" ? "guide" : want.screen;
    if (want.phase === "requesting" || want.phase === "auto") st.phase = want.phase;
  } catch (error) {
    await recover(error);
  } finally {
    LIVE.busy = false;
    render();
  }
}

/* 탑승: 폰에서 보낸 경로가 있고 정차가 확인되면 곧 안내를 요청한다. 폰에서 누른 것을 운전자 확인으로 본다. */
function board() {
  st.carOn = true;
  if (st.carReady && st.parked && !st.activeRoute) {
    st.phase = "auto";
    render();
    after(2600, async () => {
      st.phase = null;
      if (!live()) { runFlow(st.nextResult); return; }
      /* 요청 직전에 현재 경로를 다시 읽는다. 그 사이 안내가 시작됐으면 요청하지 않는다. */
      const snap = await cmd((api) => api.refreshActiveRoute());
      if (snap?.navigation.canConfirm) startLive();
    });
    return;
  }
  render();
}

document.addEventListener("click", (e) => {
  const ph = e.target.closest("#phone [data-ph]");
  if (ph && !ph.disabled) { PHONE[ph.dataset.ph](); return; }
  if (e.target.id === "boardBtn") { board(); return; }
  const inApp = e.target.closest("#app [data-act]");
  if (inApp && !inApp.disabled) {
    const fn = ACT[inApp.dataset.act];
    if (fn) fn(inApp);
    return;
  }
  const p = e.target.closest("#panel button");
  if (!p) return;
  if (p.dataset.state) { go(p.dataset.state); return; }
  if (p.dataset.set) { st[p.dataset.set] = p.dataset.val; render(); return; }
  if (p.dataset.toggle === "activeRoute" && live()) {
    const activeRoute = st.activeRoute ? "INACTIVE" : "ACTIVE";
    cmd(async (api) => {
      await api.setDemoScenario({ activeRoute });
      return api.refreshActiveRoute();
    });
    return;
  }
  if (p.dataset.toggle === "parked" && live()) { const parked = !st.parked; cmd((api) => api.setDemoScenario({ parked })); return; }
  if (p.dataset.toggle) { st[p.dataset.toggle] = !st[p.dataset.toggle]; render(); return; }
  if (p.dataset.toggleOff) { st[p.dataset.toggleOff] = !st[p.dataset.toggleOff]; render(); return; }
  if (p.dataset.next === "result") {
    st.nextResult = st.nextResult === "confirm_verified" ? "confirm_unconfirmed" : "confirm_verified";
    if (live()) { const failNext = st.nextResult === "confirm_unconfirmed"; cmd((api) => api.setDemoScenario({ failNext })); return; }
    render();
    return;
  }
  if (p.dataset.next === "waypoint") { st.nextWaypoint = st.nextWaypoint === "added" ? "unconfirmed" : "added"; render(); return; }
  if (p.dataset.demo === "proposal") { st.proposal = !st.proposal; render(); return; }
  if (p.dataset.demo === "next" || p.dataset.demo === "prev") {
    const step = Math.max(0, Math.min(DEMO_ORDER.length - 1, st.demoStep + (p.dataset.demo === "next" ? 1 : -1)));
    go(DEMO_ORDER[step]).then(() => {
      st.demoStep = step;
      st.phoneOn = /^(폰|차):/.test(DEMO_ORDER[step]);
      render();
    });
  }
});

$("panelTab").addEventListener("click", () => { st.panel = !st.panel; render(); });
document.addEventListener("keydown", (e) => {
  if (e.key === "d" || e.key === "D") { st.panel = !st.panel; render(); }
});

function fit() {
  const room = $("room");
  const H = room.clientHeight;
  const free = st.panel ? room.clientWidth - 330 : room.clientWidth;
  const gap = st.phoneOn ? Math.max(16, free * .012) : 0;
  const phoneScale = st.phoneOn ? Math.min((H - 24) / 864, .96, (free * .26) / 410) : 0;
  const phoneW = st.phoneOn ? 410 * phoneScale : 0;
  const scale = Math.min((free - phoneW - gap * 2) / 2560, H / 1440);
  const carW = 2560 * scale;
  const left = (free - phoneW - gap - carW) / 2;
  $("bezel").style.transform = `translate(${left + phoneW + gap}px, ${(H - 1440 * scale) / 2}px) scale(${scale})`;
  $("phoneWrap").style.transform = `translate(${left}px, ${(H - 864 * phoneScale) / 2}px) scale(${phoneScale})`;
}
window.addEventListener("resize", fit);

window.__proto = { go, states: () => Object.keys(STATES), set: (patch) => { Object.assign(st, patch); render(); }, get: () => st, live: () => LIVE };

/* connect.js가 호출한다. 초기 화면은 읽기만 하고 동의·예시 이력·안내 요청을 자동으로 하지 않는다. */
window.__routineBoot = async (api) => {
  if (!api) { render(); return; }
  LIVE.api = api;
  try {
    sync(await api.getState());
    await openApp();
    if (live()?.navigation.activeRoute !== undefined) await cmd((client) => client.refreshActiveRoute());
  } catch {
    LIVE.lost = false;
    LIVE.api = null;
  }
  render();
};

const params = new URLSearchParams(location.search);
if (params.has("present")) { st.panel = false; $("panelTab").hidden = true; }
if (params.has("phone")) st.phoneOn = true;
if (params.get("state") && STATES[params.get("state")]) STATES[params.get("state")]();
if (params.get("theme")) st.theme = params.get("theme");
if (params.get("layout")) st.layout = params.get("layout");
document.fonts.ready.then(render);
render();
