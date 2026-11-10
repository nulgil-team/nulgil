/*
 * 실제 로컬 API에 연결한 화면 검사.
 * 사용: node frontend/tests/verify-live.cjs <출력 폴더> <저장 파일 경로>
 * 백엔드와 dev-server가 떠 있어야 한다. 저장 파일은 시험 전용 경로를 쓴다.
 */
const fs = require("fs");
const path = require("path");
const puppeteer = require("puppeteer-core");
const { audit } = require("./audit.cjs");

const OUT = process.argv[2];
const DATA = process.argv[3];
const BASE = "http://localhost:5173/";
const EDGE = process.env.BROWSER_PATH || ["C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe", "C:/Program Files/Microsoft/Edge/Application/msedge.exe"].find(fs.existsSync);

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await puppeteer.launch({ executablePath: EDGE, pipe: true, headless: "new", userDataDir: process.env.EDGE_PROFILE, args: ["--hide-scrollbars", "--no-first-run", "--disable-gpu", "--no-sandbox"] });
  const page = await browser.newPage();
  await page.setViewport({ width: 2560, height: 1440, deviceScaleFactor: 1 });
  const errors = [];
  const allowFailures = { on: false };
  page.on("pageerror", (e) => errors.push(`pageerror: ${e.message}`));
  page.on("console", (m) => { if (m.type() === "error" && !allowFailures.on) errors.push(`console: ${m.text()} ${m.location()?.url ?? ""}`); });
  page.on("response", (r) => { if (r.status() >= 400 && !allowFailures.on) errors.push(`http ${r.status()} ${r.request().method()} ${r.url()}`); });

  const report = { flows: [], audit: {}, errors };
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const state = () => page.evaluate(() => window.__proto.live().api.getState());
  const text = () => page.evaluate(() => document.getElementById("app").innerText);
  const ready = () => page.waitForFunction(() => { const l = window.__proto?.live?.(); return l && l.snap && !l.busy && !l.confirming; }, { timeout: 15000 });
  const open = async (query = "?present") => { await page.goto(BASE + query, { waitUntil: "networkidle0" }); await ready(); await wait(150); };
  const go = async (name) => { await page.evaluate((n) => window.__proto.go(n), name); await ready(); await page.evaluate(() => window.__proto.set({ panel: false, phoneOn: false, carOn: true })); };
  const click = async (label) => {
    const ok = await page.evaluate((t) => {
      const scope = document.querySelector("#app .scrim") || document.getElementById("app");
      const b = [...scope.querySelectorAll("button")].find((x) => x.textContent.trim().includes(t) && !x.disabled);
      if (!b) return false;
      b.click();
      return true;
    }, label);
    if (!ok) throw new Error(`버튼 없음: ${label}`);
    await wait(40);
    await ready();
  };
  const expect = async (t) => { const v = await text(); if (!v.includes(t)) throw new Error(`없음: ${t}`); };
  const reject = async (t) => { const v = await text(); if (v.includes(t)) throw new Error(`있으면 안 됨: ${t}`); };
  const equal = (a, b, label) => { if (a !== b) throw new Error(`${label}: ${a} != ${b}`); };
  const flow = async (name, fn) => {
    try { await fn(); report.flows.push(`PASS ${name}`); } catch (e) { report.flows.push(`FAIL ${name}: ${e.message}`); await page.screenshot({ path: path.join(OUT, `fail_${report.flows.length}.png`) }); }
  };
  const historyRows = () => page.evaluate(() => [...document.querySelectorAll("#app [data-history-date]")].map((row) => ({
    localDate: row.querySelector("time").textContent,
    minutes: Number(row.querySelector("b").textContent.replace("분", "")),
  })));
  const assertHistory = async (expected) => equal(JSON.stringify(await historyRows()), JSON.stringify(expected), "날짜별 표시 기록");
  const assertLive = async () => equal(await page.evaluate(() => !!window.__proto.get().offlineView), false, "실제 API 화면");

  await flow("최초 접속: 읽기만 하고 동의·기록·안내를 자동 실행하지 않음", async () => {
    await open();
    const s = await state();
    equal(s.collectionEnabled, false, "동의");
    equal(s.counts.selections, 0, "기록");
    equal(s.counts.navigationRequests, 0, "안내 요청");
    await expect("늘 가는 곳을 기억해 둘까요?");
  });

  await flow("처음 사용: 동의, 장소, 시간 등록이 API에 반영", async () => {
    await click("기록 켜고 시작");
    equal((await state()).collectionEnabled, true, "동의");
    await click("소프트웨어드림센터");
    await click("등록하고 시작");
    const s = await state();
    if (s.counts.rules < 1) throw new Error("규칙 미등록");
    equal(s.recommendation.source, "RULE", "추천 출처");
    equal(s.context.minute, 480, "시각 복원");
    await expect("등록해 둔 곳이에요");
  });

  await flow("큰 지연: 화면 숫자가 API 응답과 일치", async () => {
    await go("큰 지연");
    const s = await state();
    equal(s.traffic.estimateMinutes, 44, "예상");
    equal(s.traffic.baselineMinutes, 29, "기준");
    equal(s.traffic.delayMinutes, 15, "차이");
    equal(s.recommendation.source, "HISTORY", "출처");
    await expect("44"); await expect("평소보다 15분 더 걸려요"); await expect("오전 8:44 도착 예정");
    await page.screenshot({ path: path.join(OUT, "01_congested.png") });
  });

  await flow("평소와 비슷: API 교통값으로 표시", async () => {
    await go("평소와 비슷");
    const s = await state();
    equal(s.traffic.estimateMinutes, 28, "예상");
    equal(s.traffic.important, false, "강조");
    await expect("평소와 비슷해요");
  });

  await flow("연타: 안내 요청은 한 번만", async () => {
    await go("큰 지연");
    const before = (await state()).counts.navigationRequests;
    await page.evaluate(() => { const b = [...document.querySelectorAll("#app button")].find((x) => x.textContent.trim() === "안내 시작"); for (let i = 0; i < 6; i++) b.click(); });
    await wait(1200); await ready();
    const s = await state();
    equal(s.counts.navigationRequests, before + 1, "요청 횟수");
    equal(s.navigation.state, "VERIFIED", "상태");
    await expect("안내를 시작했어요");
    await page.screenshot({ path: path.join(OUT, "02_verified.png") });
  });

  await flow("새로고침: 안내 상태 유지, 재요청 없음", async () => {
    const before = (await state()).counts.navigationRequests;
    await open();
    await wait(1000);
    equal((await state()).counts.navigationRequests, before, "요청 횟수");
    await expect("안내를 시작했어요"); await reject("안내 시작\n");
  });

  await flow("경로 성향: 저장되고 새로고침 후 유지", async () => {
    await go("평소와 비슷");
    await reject("편안한 경로");
    await click("내 일상"); await click("편안한");
    equal((await state()).preference, "COMFORTABLE", "선호");
    await open();
    await click("내 일상");
    const on = await page.evaluate(() => document.querySelector('#app .pref[aria-pressed="true"]').textContent);
    if (!on.includes("편안한")) throw new Error(on);
    await click("추천");
  });

  await flow("정차 미확인: canConfirm 기준으로 잠금", async () => {
    await go("정차 미확인");
    const s = await state();
    equal(s.navigation.canConfirm, false, "canConfirm");
    equal(s.vehicle.state, "MOVING", "차량");
    await expect("정차를 확인하면 시작할 수 있어요");
    const off = await page.evaluate(() => document.querySelector("#app .primary").disabled);
    equal(off, true, "버튼 잠금");
    await page.screenshot({ path: path.join(OUT, "03_not_parked.png") });
  });

  await flow("결과 미확인: 재요청 없음", async () => {
    const before = (await state()).counts.navigationRequests;
    await go("결과 미확인");
    const s = await state();
    equal(s.navigation.state, "UNCONFIRMED", "상태");
    equal(s.counts.navigationRequests, before + 1, "요청 횟수");
    await expect("확인하지 못했어요"); await reject("도착 예정"); await reject("다시 요청");
    await click("확인했어요");
    equal((await state()).counts.navigationRequests, before + 1, "재요청 없음");
    await open(); await expect("확인하지 못했어요");
    await page.screenshot({ path: path.join(OUT, "04_unconfirmed.png") });
  });

  await flow("교통 없음: null을 0분으로 표시하지 않음", async () => {
    await go("교통 없음");
    const s = await state();
    equal(s.traffic.estimateMinutes, null, "예상");
    equal(s.traffic.status, "UNAVAILABLE", "상태");
    await expect("교통 정보를 받지 못했어요"); await reject("0분");
    await click("다시 확인"); await expect("아직 교통 정보를 받지 못했어요");
    await expect("교통 없이 안내 시작");
  });

  await flow("넘기기: API에서 DISMISSED, 요청 없음, 다시 보기 가능", async () => {
    await go("평소와 비슷");
    const before = (await state()).counts.navigationRequests;
    await click("이번엔 넘기기");
    const s = await state();
    equal(s.navigation.state, "DISMISSED", "상태");
    equal(s.counts.navigationRequests, before, "요청");
    await expect("내비는 그대로예요");
    await click("다시 보기");
    equal((await state()).recommendation.status, "READY", "추천");
  });

  await flow("장소 바꾸기: id와 subId로 선택, 비교 기준 없으면 시간만", async () => {
    await go("평소와 비슷");
    await click("바꾸기"); await click("강남 거래처");
    const s = await state();
    equal(s.recommendation.source, "MANUAL", "출처");
    equal(s.traffic.status, "BASELINE_UNAVAILABLE", "교통");
    await expect("강남 거래처"); await expect("직접 고른 곳이에요"); await expect("기록을 모으는 중"); await reject("평소보다");
  });

  await flow("기록 없음: 수치를 만들지 않음", async () => {
    await go("기록 없음");
    const s = await state();
    equal(s.recommendation.destination, null, "목적지");
    equal(s.navigation.canConfirm, false, "canConfirm");
    await expect("오늘은 어디로 갈까요?"); await reject("분 더");
  });

  await flow("이력 지우기: 규칙 유지, 기록 0", async () => {
    await go("내 일상");
    const rules = (await state()).counts.rules;
    await click("이력 지우기");
    await page.evaluate(() => document.querySelector("#app .sheet .primary").click());
    await wait(60); await ready();
    const s = await state();
    equal(s.counts.selections, 0, "선택");
    equal(s.counts.rules, rules, "규칙");
    await expect("기록 0건");
  });

  await flow("기록 끄기: API에 반영", async () => {
    await go("내 일상");
    await page.evaluate(() => document.querySelector("#app .switch").click());
    await wait(60); await ready();
    equal((await state()).collectionEnabled, false, "동의");
    await expect("새 기록을 남기지 않아요");
  });

  await flow("BR-17 기록 2일: API 보류 유형과 표본 수로 표시", async () => {
    const before = (await state()).counts.navigationRequests;
    await go("기록 부족");
    const s = await state();
    equal(s.recommendation.holdType, "NOT_ENOUGH", "보류 유형");
    equal(s.recommendation.samples, 2, "표본 일수");
    equal(s.navigation.canConfirm, false, "canConfirm");
    equal(s.counts.navigationRequests, before, "요청 없음");
    await open(); await assertLive();
    await expect("고른 날이 2일이에요");
    await page.screenshot({ path: path.join(OUT, "07_not_enough.png") });
  });

  await flow("BR-17 후보 동률: 응답 후보만 표시, 사용자 선택은 API에 반영", async () => {
    const before = (await state()).counts.navigationRequests;
    await go("후보 동률");
    const s = await state();
    equal(s.recommendation.holdType, "TIE", "보류 유형");
    equal(s.recommendation.candidates.length, 2, "동률 후보 수");
    await open(); await assertLive();
    const ids = await page.evaluate(() => [...document.querySelectorAll("#app .places [data-id]")].map((button) => button.dataset.id));
    equal(JSON.stringify(ids), JSON.stringify(s.recommendation.candidates.map((c) => c.destination.id)), "후보 목록");
    for (const candidate of s.recommendation.candidates) {
      equal(candidate.samples, 3, "후보별 표본 일수");
      await expect(candidate.destination.name);
    }
    await page.screenshot({ path: path.join(OUT, "08_tie.png") });
    await click(s.recommendation.candidates[1].destination.name);
    equal((await state()).recommendation.destination.id, s.recommendation.candidates[1].destination.id, "직접 선택");
    equal((await state()).counts.navigationRequests, before, "요청 없음");
  });

  await flow("BR-17 비교 기록 2일: 예상만 표시하고 실제 날짜 목록 제공", async () => {
    await go("비교 부족"); await assertLive();
    const s = await state();
    equal(s.traffic.kind, "NO_COMPARE", "교통 유형");
    equal(s.traffic.history.length, 2, "비교 기록 수");
    equal(s.traffic.baselineMinutes, null, "중앙값 보류");
    await expect("평소 시간은 기록을 모으는 중이에요");
    await click("지난 기록"); await assertHistory(s.traffic.history);
    await page.screenshot({ path: path.join(OUT, "09_compare_short.png") });
    await click("닫기");
  });

  await flow("BR-17 오래된 교통: API STALE로 표시", async () => {
    await go("교통 오래됨"); await assertLive();
    const s = await state();
    equal(s.traffic.kind, "STALE", "교통 유형");
    equal(s.traffic.estimateMinutes, null, "예상 시간 숨김");
    await expect("교통 정보가 오래됐어요"); await reject("도착 예정");
    await page.screenshot({ path: path.join(OUT, "10_stale.png") });
  });

  await flow("BR-17 단위 미확인: API UNKNOWN_UNITS로 구분", async () => {
    await go("단위 미확인"); await assertLive();
    const s = await state();
    equal(s.traffic.kind, "UNKNOWN_UNITS", "교통 유형");
    equal(s.traffic.estimateMinutes, null, "예상 시간 숨김");
    await expect("예상 시간을 아직 표시할 수 없어요");
    await reject("교통 정보가 오래됐어요"); await reject("도착 예정");
    await page.screenshot({ path: path.join(OUT, "11_unknown_units.png") });
  });

  await flow("BR-16 비교 날짜: 실제 응답 목록과 화면이 동일", async () => {
    await go("큰 지연");
    const s = await state();
    equal(s.traffic.history.length, 3, "비교 일수");
    await click("지난 기록"); await assertHistory(s.traffic.history);
    for (const record of s.traffic.history) {
      await expect(record.localDate); await expect(`${record.minutes}분`);
    }
    await page.screenshot({ path: path.join(OUT, "12_history.png") });
    await click("닫기");
  });

  await flow("BR-16 동의 해제: 직전 후보와 기록이 API·화면에서 비어 있음", async () => {
    await go("큰 지연");
    await click("지난 기록");
    if ((await historyRows()).length !== 3) throw new Error("해제 전 기록 없음");
    await page.evaluate(async () => {
      const api = window.__proto.live().api;
      await api.setConsent(false);
      await window.__routineBoot(api);
    });
    await ready();
    const s = await state();
    equal(s.collectionEnabled, false, "동의");
    equal(s.traffic.history.length, 0, "API 기록 비움");
    equal(s.recommendation.candidates.length, 0, "API 후보 비움");
    await assertHistory([]); await expect("지난 기록을 표시하지 않아요");
    await go("후보 동률");
    await page.evaluate(async () => {
      const api = window.__proto.live().api;
      await api.setConsent(false);
      await window.__routineBoot(api);
    });
    await ready();
    equal((await state()).recommendation.candidates.length, 0, "직전 동률 후보 비움");
    equal(await page.evaluate(() => document.querySelectorAll("#app .places [data-id]").length), 0, "화면 후보 비움");
  });

  for (const theme of ["night", "day"]) {
    for (const layout of ["full", "split"]) {
      for (const name of ["큰 지연", "평소와 비슷", "교통 없음", "정차 미확인", "기록 없음", "안내 시작됨", "결과 미확인", "넘김", "내 일상", "기록 부족", "후보 동률", "비교 부족", "교통 오래됨", "단위 미확인", "추천 이유"]) {
        await go(name);
        await page.evaluate((t, l) => window.__proto.set({ theme: t, layout: l }), theme, layout);
        const issues = await page.evaluate(audit);
        if (issues.length) report.audit[`${name}/${theme}/${layout}`] = issues;
      }
    }
  }

  await flow("이미 안내 중: API로 표시, 요청 0회", async () => {
    await go("이미 안내 중");
    const s = await state();
    equal(s.navigation.activeRoute, "ACTIVE", "활성 경로");
    equal(s.navigation.canConfirm, false, "canConfirm");
    await expect("지금 안내 중인 경로가 있어요"); await reject("안내 시작\n");
    const before = s.counts.navigationRequests;
    await click("내비 보기");
    equal((await state()).counts.navigationRequests, before, "요청 횟수");
    await page.screenshot({ path: path.join(OUT, "07_active_route.png") });
  });

  await flow("경로 조회 불가: 잠금, 다시 확인, 요청 0회", async () => {
    await go("경로 조회 불가");
    const s = await state();
    equal(s.navigation.activeRoute, "UNAVAILABLE", "활성 경로");
    await expect("현재 경로를 확인하지 못했어요");
    const off = await page.evaluate(() => document.querySelector("#app .primary").disabled);
    equal(off, true, "버튼 잠금");
    await click("다시 확인");
    equal((await state()).counts.navigationRequests, s.counts.navigationRequests, "요청 횟수");
    await page.screenshot({ path: path.join(OUT, "08_route_unavailable.png") });
  });

  const board = () => page.evaluate(() => document.getElementById("boardBtn").click());
  await flow("폰 자동 시작: 앱이 열리면 정차 확인 후 요청 1회", async () => {
    await go("폰: 보냄");
    const before = (await state()).counts.navigationRequests;
    await board();
    await expect("곧 안내를 시작해요");
    await wait(4200); await ready();
    const s = await state();
    equal(s.counts.navigationRequests, before + 1, "요청 횟수");
    equal(s.navigation.state, "VERIFIED", "상태");
    await expect("안내를 시작했어요");
  });

  await flow("폰 자동 시작: 취소하면 요청 0회", async () => {
    await go("폰: 보냄");
    const before = (await state()).counts.navigationRequests;
    await board();
    await click("이번엔 취소");
    await wait(3200);
    equal((await state()).counts.navigationRequests, before, "요청 횟수");
    await reject("안내를 시작했어요");
  });

  await flow("폰 자동 시작: 이미 안내 중이면 요청 0회", async () => {
    await go("차: 보냈지만 안내 중");
    const before = (await state()).counts.navigationRequests;
    await page.evaluate(() => window.__proto.set({ carOn: false }));
    await board();
    await wait(3200);
    equal((await state()).counts.navigationRequests, before, "요청 횟수");
    await expect("덮어쓰지 않았어요");
  });

  await flow("폰 자동 시작: 대기 중 안내가 시작되면 요청 0회", async () => {
    await go("폰: 보냄");
    const before = (await state()).counts.navigationRequests;
    await board();
    await page.evaluate(() => window.__proto.live().api.setDemoScenario({ activeRoute: "ACTIVE" }));
    await wait(3600); await ready();
    const s = await state();
    equal(s.counts.navigationRequests, before, "요청 횟수");
    equal(s.navigation.activeRoute, "ACTIVE", "활성 경로");
    await reject("안내를 시작했어요");
  });

  await flow("저장 실패: 성공처럼 표시하지 않고 안내 실패와 구분", async () => {
    await go("평소와 비슷");
    allowFailures.on = true;
    await click("내 일상");
    fs.appendFileSync(DATA, "\n# external edit\n");
    await click("빠른");
    await expect("저장하지 못했어요");
    const s = await state();
    equal(s.preference, "RECOMMENDED", "선호 유지");
    if (!s.notice || s.notice.code !== "STORAGE_ERROR") throw new Error("notice 없음");
    await page.screenshot({ path: path.join(OUT, "05_storage_error.png") });
    allowFailures.on = false;
  });

  await flow("연결 끊김: 잠금 유지, 복구 후에도 자동 재전송 없음", async () => {
    await open();
    const s0 = await state();
    if (!s0.navigation.canConfirm) await go("평소와 비슷");
    const before = (await state()).counts.navigationRequests;
    allowFailures.on = true;
    await page.setRequestInterception(true);
    const block = (req) => (req.url().includes(":8791") ? req.abort() : req.continue());
    page.on("request", block);
    await page.evaluate(() => [...document.querySelectorAll("#app button")].find((x) => x.textContent.trim() === "안내 시작").click());
    await wait(1400);
    await expect("연결을 확인할 때까지 시작하지 않아요");
    await reject("저장하지 못했어요");
    const off = await page.evaluate(() => document.querySelector("#app .primary").disabled);
    equal(off, true, "잠금");
    await page.screenshot({ path: path.join(OUT, "06_connection_lost.png") });
    page.off("request", block);
    await page.setRequestInterception(false);
    await click("다시 연결");
    await wait(300);
    equal((await state()).counts.navigationRequests, before, "요청 횟수");
    allowFailures.on = false;
  });

  await flow("개발 서버 공개 범위: 저장소 내부 파일을 제공하지 않음", async () => {
    allowFailures.on = true;
    const statuses = await page.evaluate(async () => {
      const paths = ["/.git/config", "/../AGENTS.md", "/%2e%2e/README.md", "/runs/", "/dev-server.mjs", "/tests/audit.cjs", "/AGENTS.md"];
      return Promise.all(paths.map((p) => fetch(p).then((r) => r.status)));
    });
    allowFailures.on = false;
    if (statuses.some((s) => s !== 404)) throw new Error(statuses.join(","));
  });

  await browser.close();
  fs.writeFileSync(path.join(OUT, "report.json"), JSON.stringify(report, null, 1), "utf8");
  console.log(report.flows.join("\n"));
  console.log(`audit issues ${Object.keys(report.audit).length}, errors ${errors.length}`);
  console.log(JSON.stringify(report.audit).slice(0, 700));
  if (errors.length) console.log(errors.slice(0, 6).join("\n"));
  if (report.flows.some((result) => result.startsWith("FAIL")) || Object.keys(report.audit).length || errors.length) process.exitCode = 1;
})().catch((e) => { console.error(e); process.exit(1); });
