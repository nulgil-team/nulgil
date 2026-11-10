/*
 * 프로토타입 실제 렌더 검사.
 * 사용: node verify.cjs <출력 폴더> [--shots=all|key]
 * 모든 상태를 야간·주간, 전체·분할 창으로 띄워 잘림, 글자 크기, 터치 영역, 대비, 금지 문구를 검사하고
 * 실제 클릭으로 핵심 흐름을 시험한다.
 */
const fs = require("fs");
const path = require("path");
const puppeteer = require("puppeteer-core");

const OUT = process.argv[2];
const SHOTS = (process.argv[3] || "--shots=key").split("=")[1];
const BASE = "http://localhost:5173/";
const EDGE = ["C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe", "C:/Program Files/Microsoft/Edge/Application/msedge.exe", "C:/Program Files/Google/Chrome/Application/chrome.exe"].find(fs.existsSync);

const KEY = ["첫사용", "시간등록", "기록 부족", "후보 동률", "평소와 비슷", "큰 지연", "교통 없음", "제안 도착", "제안 확인", "들를 곳 포함", "친구 초대", "정차 미확인", "요청 중", "안내 시작됨", "들를 곳과 안내", "결과 미확인", "안내 중 제안", "넘김", "이미 안내 중", "내 일상", "추천 이유"];

const { audit } = require("./audit.cjs");

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await puppeteer.launch({ executablePath: EDGE, headless: "new", dumpio: !!process.env.DUMP, userDataDir: process.env.EDGE_PROFILE, args: ["--hide-scrollbars", "--no-first-run", "--disable-gpu", "--no-sandbox"] });
  const page = await browser.newPage();
  await page.setViewport({ width: 2560, height: 1440, deviceScaleFactor: 1 });
  const errors = [];
  page.on("pageerror", (e) => errors.push(`pageerror: ${e.message}`));
  page.on("console", (m) => { if (m.type() === "error") errors.push(`console: ${m.text()}`); });
  page.on("requestfailed", (r) => errors.push(`request failed: ${r.url()}`));
  await page.goto(`${BASE}?present&offline`, { waitUntil: "networkidle0" });
  await page.evaluate(() => document.fonts.ready);
  const fontOk = await page.evaluate(() => document.fonts.check('700 40px "Asta Sans"', "늘길"));
  const states = await page.evaluate(() => window.__proto.states());
  const report = { edge: path.basename(EDGE), font: fontOk, states: states.length, combos: 0, issues: {}, flows: [], errors };

  for (const theme of ["night", "day"]) {
    for (const layout of ["full", "split"]) {
      for (const name of states) {
        await page.evaluate((n, t, l) => { window.__proto.go(n); window.__proto.set({ theme: t, layout: l, panel: false, phoneOn: false, carOn: true }); }, name, theme, layout);
        await new Promise((r) => setTimeout(r, 20));
        const issues = await page.evaluate(audit);
        report.combos += 1;
        if (issues.length) report.issues[`${name} / ${theme} / ${layout}`] = issues;
        const wanted = SHOTS === "all" || (KEY.includes(name) && ((theme === "night" && layout === "full") || (["큰 지연", "들를 곳 포함", "안내 시작됨"].includes(name))));
        if (wanted) await page.screenshot({ path: path.join(OUT, `${theme}_${layout}_${name.replace(/\s/g, "")}.png`) });
      }
    }
  }

  /* 실제 클릭 시험 */
  const click = async (text) => {
    const ok = await page.evaluate((t) => {
      const scope = document.querySelector("#app .scrim") || document.getElementById("app");
      const b = [...scope.querySelectorAll("button")].find((x) => x.textContent.trim().includes(t) && !x.disabled);
      if (!b) return false;
      b.click();
      return true;
    }, text);
    if (!ok) throw new Error(`버튼 없음: ${text}`);
  };
  const has = (text) => page.evaluate((t) => document.getElementById("app").innerText.includes(t), text);
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const flow = async (name, fn) => {
    try { await fn(); report.flows.push(`PASS ${name}`); } catch (e) { report.flows.push(`FAIL ${name}: ${e.message}`); }
  };
  const expect = async (text) => { if (!(await has(text))) throw new Error(`없음: ${text}`); };
  const reject = async (text) => { if (await has(text)) throw new Error(`있으면 안 됨: ${text}`); };
  const open = (name) => page.evaluate((n) => { window.__proto.go(n); window.__proto.set({ theme: "night", layout: "full", panel: false, phoneOn: false }); }, name);

  await flow("처음 사용: 동의, 장소, 시간 등록 후 경로 화면", async () => {
    await open("첫사용"); await click("기록 켜고 시작"); await expect("평소에 어디로 가세요?");
    await click("소프트웨어드림센터"); await expect("언제 가세요?"); await click("등록하고 시작");
    await expect("등록해 둔 곳이에요"); await expect("평소 장소를 등록했어요");
  });
  await flow("저장 실패 시 동의 화면 유지", async () => {
    await open("첫사용"); await page.evaluate(() => window.__proto.set({ nextSaveFails: true }));
    await click("기록 켜고 시작"); await expect("저장하지 못했어요"); await expect("늘 가는 곳을 기억해 둘까요?");
  });
  await flow("안내 시작: 요청 중 잠금 후 확인", async () => {
    await open("큰 지연"); await click("안내 시작"); await expect("안내를 요청하고 있어요");
    const locked = await page.evaluate(() => [...document.querySelectorAll("#app .pref")].every((b) => b.disabled));
    if (!locked) throw new Error("요청 중 성향이 잠기지 않음");
    await wait(1100); await expect("안내를 시작했어요");
  });
  await flow("결과 미확인: 재요청 버튼 없음", async () => {
    await open("큰 지연"); await page.evaluate(() => window.__proto.set({ nextResult: "confirm_unconfirmed" }));
    await click("안내 시작"); await wait(1100); await expect("확인하지 못했어요"); await reject("안내 시작\n"); await reject("다시 요청"); await reject("도착 예정");
  });
  await flow("정차 미확인: 시작 차단", async () => {
    await open("정차 미확인"); await expect("정차를 확인하면 시작할 수 있어요");
    const off = await page.evaluate(() => document.querySelector("#app .primary").disabled);
    if (!off) throw new Error("시작 버튼이 활성");
  });
  await flow("넘기기: 내비 변경 없음 안내", async () => {
    await open("평소와 비슷"); await click("이번엔 넘기기"); await expect("내비는 그대로예요"); await click("다시 보기"); await expect("소프트웨어드림센터");
  });
  await flow("친구 제안: 수락하면 경로에 들어감", async () => {
    await open("제안 도착"); await click("보기"); await expect("역 후문 주차장"); await click("경유지로 추가");
    await expect("경유지를 추가했어요"); await reject("경유지가 도착했어요");
  });
  await flow("친구 제안: 넘기면 경로 유지", async () => {
    await open("제안 도착"); await click("보기"); await click("넘기기"); await expect("경로는 그대로예요"); await reject("경유지를 추가했어요");
  });
  await flow("초대 후 제안 도착", async () => {
    await open("평소와 비슷");
    await page.evaluate(() => document.querySelector("#app .route-add").click());
    await click("친구에게 받기"); await click("기다리기"); await wait(1600); await expect("경유지가 도착했어요");
  });
  await flow("안내 중 제안: 경유지 추가 확인", async () => {
    await open("안내 중 제안"); await click("보기"); await click("경유지로 추가"); await expect("경유지를 추가하고 있어요"); await wait(1100); await expect("경유지를 추가했어요");
  });
  await flow("안내 중 제안: 경유지 미확인에 재요청 없음", async () => {
    await open("안내 중 제안"); await page.evaluate(() => window.__proto.set({ nextWaypoint: "unconfirmed" }));
    await click("보기"); await click("경유지로 추가"); await wait(1100); await expect("추가됐는지 확인하지 못했어요"); await reject("다시 요청");
  });
  await flow("장소 바꾸기: 비교 기록 없는 곳은 시간만", async () => {
    await open("평소와 비슷"); await click("바꾸기"); await click("강남 거래처"); await expect("강남 거래처"); await expect("기록을 모으는 중"); await reject("평소보다");
  });
  await flow("교통 없음: 시간 표시 없음, 시작 가능", async () => {
    await open("교통 없음"); await expect("교통 정보를 받지 못했어요"); await reject("0분"); await click("다시 확인"); await wait(1000); await expect("아직 교통 정보를 받지 못했어요"); await click("교통 없이 안내 시작");
  });
  await flow("교통 오래됨: 다시 확인 시 갱신", async () => {
    await open("교통 오래됨"); await reject("분\n"); await click("다시 확인"); await wait(1000); await expect("평소와 비슷해요");
  });
  await flow("이력 지우기: 평소 장소 유지", async () => {
    await open("내 일상"); await click("이력 지우기"); await expect("평소 장소와 기본 경로는 남아요"); await page.evaluate(() => document.querySelector("#app .sheet .primary").click());
    await expect("기록 0건"); await expect("소프트웨어드림센터");
  });
  await flow("기록 끄기", async () => {
    await open("내 일상"); await page.evaluate(() => document.querySelector("#app .switch").click()); await expect("새 기록을 남기지 않아요");
  });
  await flow("이미 안내 중: 덮어쓰지 않음", async () => {
    await open("이미 안내 중"); await expect("덮어쓰지 않아요"); await reject("안내 시작");
  });

  await flow("폰 딸깍: 보내면 차에 준비된 경로 표시", async () => {
    await page.setViewport({ width: 1920, height: 1080, deviceScaleFactor: 1 });
    await page.evaluate(() => { window.__proto.go("폰: 내려오는 중"); window.__proto.set({ panel: false, phoneOn: true, theme: "night", layout: "full" }); });
    await page.screenshot({ path: path.join(OUT, "phone_01_ready.png") });
    const ph = (sel) => page.evaluate((s) => { document.querySelector(s).click(); }, sel);
    await page.evaluate(() => window.__proto.set({ proposal: true }));
    await page.screenshot({ path: path.join(OUT, "phone_02_proposal.png") });
    await ph('#phone [data-ph="accept"]');
    await ph('#phone [data-ph="send"]'); await wait(900);
    const text = await page.evaluate(() => document.getElementById("phone").innerText);
    if (!text.includes("차에 보냈어요")) throw new Error("폰 완료 표시 없음");
    await page.screenshot({ path: path.join(OUT, "phone_03_sent.png") });
    await page.evaluate(() => document.getElementById("boardBtn").click());
    await expect("폰에서 보낸 경로예요"); await expect("곧 안내를 시작해요"); await expect("역 후문 주차장");
    await page.screenshot({ path: path.join(OUT, "phone_04_car.png") });
    await wait(3900); await expect("안내를 시작했어요");
    await page.screenshot({ path: path.join(OUT, "phone_05_started.png") });
  });
  await flow("탑승 자동 시작: 취소하면 요청하지 않음", async () => {
    await page.evaluate(() => { window.__proto.go("폰: 보냄"); window.__proto.set({ panel: false, phoneOn: false, theme: "night", layout: "full" }); document.getElementById("boardBtn").click(); });
    await click("이번엔 취소"); await expect("안내를 시작하지 않았어요"); await wait(3200); await reject("안내를 시작했어요");
  });
  await flow("탑승 자동 시작: 정차 미확인이면 대기", async () => {
    await page.evaluate(() => { window.__proto.go("폰: 보냄"); window.__proto.set({ panel: false, phoneOn: false, parked: false }); document.getElementById("boardBtn").click(); });
    await wait(3400); await expect("정차를 확인하면 바로 시작해요"); await reject("안내를 시작했어요");
  });
  await flow("탑승 자동 시작: 이미 안내 중이면 덮어쓰지 않음", async () => {
    await page.evaluate(() => { window.__proto.go("폰: 보냄"); window.__proto.set({ panel: false, phoneOn: false, activeRoute: true }); document.getElementById("boardBtn").click(); });
    await wait(3400); await expect("덮어쓰지 않았어요"); await reject("안내를 시작했어요");
  });
  await flow("폰 화면 넘침 없음", async () => {
    for (const n of ["폰: 내려오는 중", "폰: 친구 제안", "폰: 들를 곳 포함", "폰: 보냄", "폰: 차 대기", "교통 없음", "기록 없음"]) {
      for (const t of ["night", "day"]) {
        await page.evaluate((a, b) => { window.__proto.go(a); window.__proto.set({ panel: false, phoneOn: true, theme: b }); }, n, t);
        const bad = await page.evaluate(() => {
          const o = [];
          const b = document.querySelector("#phone .ph-body");
          if (b.scrollHeight > b.clientHeight + 2) o.push("overflow");
          for (const el of document.querySelectorAll("#phone button")) if (el.offsetHeight < 44) o.push("touch " + el.textContent);
          for (const w of ["undefined", "NaN", "null"]) if (document.getElementById("phone").innerText.includes(w)) o.push(w);
          return o;
        });
        if (bad.length) throw new Error(n + " " + t + " " + bad.join(","));
      }
    }
  });

  await browser.close();
  fs.writeFileSync(path.join(OUT, "report.json"), JSON.stringify(report, null, 1), "utf8");
  const issueCount = Object.values(report.issues).reduce((n, l) => n + l.length, 0);
  console.log(`font ${report.font}, combos ${report.combos}, issues ${issueCount} in ${Object.keys(report.issues).length}, errors ${errors.length}`);
  console.log(report.flows.join("\n"));
  const summary = {};
  for (const [where, list] of Object.entries(report.issues)) for (const i of list) { const k = i.replace(/[\d.]+/g, "#").slice(0, 60) + " @" + where.split(" / ").slice(1).join("/"); summary[k] = (summary[k] || 0) + 1; }
  console.log(Object.entries(summary).sort((a, b) => b[1] - a[1]).slice(0, 40).map(([k, v]) => `${v}x ${k}`).join("\n"));
  if (errors.length) console.log(errors.slice(0, 8).join("\n"));
})().catch((e) => { console.error(e); process.exit(1); });
