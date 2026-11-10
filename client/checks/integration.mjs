import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { createRoutineClient } from "../routine-client.mjs";
const [connectionPath, evidenceDir] = process.argv.slice(2);
if (!connectionPath || !evidenceDir) throw new Error("Usage: node integration.mjs <connection.json> <new evidence dir>");
const connection=JSON.parse(await fs.readFile(connectionPath,"utf8"));
const api=createRoutineClient(connection);
await fs.mkdir(evidenceDir,{recursive:true});
let checks=0;
const check=(condition,label)=>{ assert.ok(condition,label); checks++; console.log("PASS "+label); };
const save=async(name,state)=>fs.writeFile(path.join(evidenceDir,name),JSON.stringify(state,null,2),"utf8");
let state=await api.getState();
check(state.mode==="DEMO" && state.collectionEnabled===false,"fresh store and DEMO contract");
check(!state.capabilities.liveNavigation && !state.capabilities.mapGeometry && !state.capabilities.etaPerPreference,"unsupported capabilities explicit");
state=await api.recommend(true);
check(state.recommendation.destination===null && !state.navigation.canConfirm,"empty history holds recommendation");
await save("01-empty.json",state);
await api.setContext(Date.parse("2026-11-04T08:00:00+09:00"),"Asia/Seoul");
await api.setConsent(true);
await api.seedDemo();
state=await api.recommend(true);
check(state.recommendation.source==="HISTORY" && state.recommendation.samples===3,"real recommender uses three local dates");
check(state.context.localDate==="2026-11-04" && state.context.day===3 && state.context.minute===480,"explicit KST context");
await api.setDemoScenario({traffic:"CONGESTED"});
state=await api.refreshTraffic();
check(state.traffic.estimateMinutes===44 && state.traffic.baselineMinutes===29 && state.traffic.delayMinutes===15 && state.traffic.important,"congested comparison from core");
await save("02-congestion.json",state);
await api.setPreference("COMFORTABLE");
state=await api.setDemoScenario({parked:false});
check(!state.navigation.canConfirm && state.navigation.code==="VEHICLE_NOT_PARKED","moving disables confirmation");
await assert.rejects(api.confirm(),e=>e.code==="VEHICLE_NOT_PARKED" && !e.uncertainMutation); checks++; console.log("PASS moving command rejected");
await api.setDemoScenario({parked:true});
state=await api.confirm();
check(state.navigation.state==="VERIFIED" && state.counts.navigationRequests===1 && state.counts.selections===4,"one verified simulated request and saved selection");
await assert.rejects(api.confirm(),e=>e.code==="TRIP_LOCKED" && e.status===409); checks++; console.log("PASS duplicate command rejected");
state=await api.getState();
check(state.counts.navigationRequests===1,"polling never reissues navigation");
await save("03-confirmed.json",state);
await api.newDemoTrip();
state=await api.recommend();
await api.setDemoScenario({traffic:"MISSING"});
state=await api.refreshTraffic();
check(state.traffic.status==="UNAVAILABLE" && state.traffic.estimateMinutes===null && state.traffic.delayMinutes===null,"missing traffic remains null");
await save("04-missing.json",state);
await api.setDemoScenario({failNext:true});
state=await api.confirm();
check(state.navigation.state==="UNCONFIRMED" && !state.navigation.canConfirm,"uncertain navigation does not retry");
await api.newDemoTrip();
await api.clearHistory();
state=await api.setConsent(false);
check(state.counts.selections===0 && state.counts.estimates===0 && state.counts.rules===1 && !state.collectionEnabled,"clear history preserves registered routine");
await api.setContext(Date.parse("2026-11-04T23:30:00Z"),"America/Los_Angeles");
state=await api.getState();
check(state.context.localDate==="2026-11-04" && state.context.minute===930,"negative-offset context");
// Count actual gateway dispatches separately from POSTs and status queries.
await api.newDemoTrip();
const place = (await api.getState()).destinations[0];
await api.selectDestination(place.id, place.subId);
const before = (await api.getState()).counts.navigationRequests;
for (const [activeRoute, code] of [["ACTIVE", "ALREADY_GUIDING"], ["UNAVAILABLE", "ROUTE_UNAVAILABLE"]]) {
  await api.setDemoScenario({ activeRoute });
  state = await api.refreshActiveRoute();
  check(state.navigation.activeRoute === activeRoute && state.navigation.state === code && !state.navigation.canConfirm,
    activeRoute + " blocks confirmation");
  await assert.rejects(api.confirm(), error => error.status === 409 && error.code === code && !error.uncertainMutation);
  state = await api.getState();
  check(state.counts.navigationRequests === before && state.navigation.state === code, activeRoute + " issues 0 requests");
}
await api.setDemoScenario({ activeRoute: "INACTIVE" });
state = await api.refreshActiveRoute();
check(state.navigation.canConfirm && state.counts.navigationRequests === before, "inactive lookup issues 0 requests");
state = await api.confirm();
check(state.navigation.state === "VERIFIED" && state.counts.navigationRequests === before + 1, "explicit inactive confirmation issues 1 request");
await api.refreshActiveRoute();
await assert.rejects(api.confirm(), error => error.status === 409 && error.code === "TRIP_LOCKED");
check((await api.getState()).counts.navigationRequests === before + 1, "post-send lookup and duplicate confirm issue 0 additional requests");
// BR-17/16 uses the same real socket and client; every case has isolated demo history.
await api.newDemoTrip();
await api.setContext(Date.parse("2026-11-04T08:00:00+09:00"), "Asia/Seoul");
await api.setConsent(true);
const places = (await api.getState()).destinations;
const requestCount = (await api.getState()).counts.navigationRequests;
const seedCase = async seed => {
  await api.newDemoTrip(); await api.clearHistory();
  await api.setDemoScenario({ traffic: "NORMAL" });
  await api.seedDemo(seed);
  return api.recommend(true);
};
state = await seedCase({ days:2 });
check(state.recommendation.holdType === "NOT_ENOUGH" && state.recommendation.samples === 2
  && state.recommendation.candidates.length === 0 && !state.navigation.canConfirm, "BR17 two-date hold is structured");
await save("05-not-enough.json",state);
state = await seedCase({ days:3, destinationIds:places.map(p => p.id) });
assert.deepEqual(state.recommendation.candidates.map(c => [c.destination.id,c.samples]), places.map(p => [p.id,3]));
check(state.recommendation.holdType === "TIE" && !state.navigation.canConfirm, "BR17 tied candidates include dates counted for each destination");
await save("06-tie.json",state);
state = await api.setConsent(false);
check(state.recommendation.candidates.length === 0 && state.traffic.history.length === 0, "consent off immediately hides both lists in command response");
state = await api.getState();
check(state.recommendation.candidates.length === 0 && state.traffic.history.length === 0, "consent off GET retains empty lists");
state = await api.recommend(true);
check(state.recommendation.holdType === "NOT_ENOUGH" && state.recommendation.samples === 0, "consent off does not reuse stored selections");
await assert.rejects(api.seedDemo({days:3}),e=>e.code==="COLLECTION_DISABLED" && !e.uncertainMutation);
await api.setConsent(true);
state = await seedCase({ days:3, estimateDays:2 });
state = await api.refreshTraffic();
assert.deepEqual(state.traffic.history,[{localDate:"2026-10-28",minutes:28},{localDate:"2026-10-21",minutes:29}]);
check(state.traffic.kind === "NO_COMPARE" && state.traffic.baselineMinutes === null && state.traffic.delayMinutes === null,
  "BR17 insufficient comparison exposes two eligible dates without inventing a median");
await save("07-no-compare.json",state);
await seedCase({ days:3 });
await api.selectDestination(places[0].id, places[0].subId);
await api.setDemoScenario({traffic:"CONGESTED"});
state=await api.refreshTraffic();
const expectedHistory=[{localDate:"2026-10-28",minutes:28},{localDate:"2026-10-21",minutes:29},{localDate:"2026-10-14",minutes:30}];
assert.deepEqual(state.traffic.history,expectedHistory);
check(state.traffic.kind==="LATE" && state.traffic.baselineMinutes===29 && state.traffic.delayMinutes===15,
  "BR16 API rows exactly support the baseline median");
await save("08-comparison-history.json",state);
const beforeConsentCounts=state.counts;
state=await api.setConsent(false);
check(state.traffic.history.length===0 && state.traffic.baselineMinutes===null && state.traffic.delayMinutes===null,
  "consent off immediately clears cached comparison and numeric baseline");
state=await api.refreshTraffic();
check(state.traffic.kind==="NO_COMPARE" && state.traffic.history.length===0 && state.traffic.baselineMinutes===null,
  "consent off traffic refresh cannot redisclose stored history");
assert.deepEqual(state.counts,beforeConsentCounts);
check(state.counts.estimates===3 && state.counts.selections===3, "consent off is distinct from deleting stored history");
await save("09-consent-off.json",state);
await api.setConsent(true);
state=await api.refreshTraffic();
assert.deepEqual(state.traffic.history,expectedHistory);
check(state.traffic.baselineMinutes===29, "explicit re-consent permits comparison again");
for(const [traffic,filename] of [["STALE","10-stale.json"],["UNKNOWN_UNITS","11-unknown-units.json"]]) {
  await api.setDemoScenario({traffic});
  state=await api.refreshTraffic();
  check(state.traffic.kind===traffic && state.traffic.estimateMinutes===null && state.traffic.baselineMinutes===null
    && state.traffic.history.length===0, "BR17 "+traffic+" preserves unknown values and hides comparison rows");
  await save(filename,state);
}
const stable=await api.getState();
for(const args of [{days:"7"},{days:"-1"},{days:"1.5"},{destinationIds:"unknown"},{destinationIds:places[0].id+","+places[0].id}]) {
  await assert.rejects(api.command("seedDemo",args),e=>e.status===400);
  assert.deepEqual(await api.getState(),stable);
}
check(true,"invalid seed input makes no partial state changes");
state=await api.getState();
check(state.counts.navigationRequests===requestCount,"five scenario reproductions issue zero navigation requests");
console.log("INTEGRATION PASS " + checks + " checks");

