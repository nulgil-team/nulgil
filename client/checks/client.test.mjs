import test from "node:test";
import assert from "node:assert/strict";
import { createRoutineClient, RoutineApiError } from "../routine-client.mjs";
const state = { schemaVersion:"1.0", mode:"DEMO", context:{}, navigation:{}, capabilities:{} };
const setup = fetchImpl => createRoutineClient({ baseUrl:"http://127.0.0.1:8791", token:"test-only", fetchImpl, timeoutMs:20 });
const response = (data = state, status = 200) => ({ status, ok: status < 400, json: async () => data });
test("sends token and form-encoded Unicode arguments, one request", async () => {
  const calls = []; const client = setup(async (...args) => { calls.push(args); return response(); });
  assert.deepEqual(await client.command("selectDestination", { id:"한글 &+=?", subId:0 }), state);
  assert.equal(calls.length,1);
  const [url, options] = calls[0]; assert.equal(url,"http://127.0.0.1:8791/v1/commands");
  assert.equal(options.headers["X-Routine-Token"],"test-only");
  assert.equal(new URLSearchParams(options.body).get("id"),"한글 &+=?");
  assert.equal(options.credentials,"omit"); assert.equal(options.redirect,"error");
});
test("GET has no mutation body", async () => {
  await setup(async (_, options) => { assert.equal(options.method,"GET"); assert.equal(options.body,undefined); return response(); }).getState();
});
test("does not retry uncertain command", async () => {
  let count = 0; const client=setup(async () => { count++; throw new Error("lost response"); });
  await assert.rejects(client.confirm(), e => e instanceof RoutineApiError && e.uncertainMutation && e.code==="TRANSPORT_ERROR");
  assert.equal(count,1);
  await assert.rejects(client.getState(), e => !e.uncertainMutation);
});
test("4xx rejection is distinct from uncertain 5xx", async () => {
  for (const status of [409,500]) await assert.rejects(
    setup(async () => response({schemaVersion:"1.0",error:{code:"TRIP_LOCKED",message:"확인 필요"}},status)).confirm(),
    e => e.code==="TRIP_LOCKED" && e.status===status && e.uncertainMutation===(status>=500));
});
test("schema mismatch and malformed JSON cannot report command success", async () => {
  await assert.rejects(setup(async () => response({...state,schemaVersion:"2.0"})).confirm(), e => e.code==="PROTOCOL_ERROR" && e.uncertainMutation);
  await assert.rejects(setup(async () => ({status:200,ok:true,json:async()=>{throw new Error("JSON");}})).confirm(), e=>e.uncertainMutation);
});
test("abort propagates without retry", async () => {
  const client=setup((_,options)=>new Promise((resolve,reject)=>{
    if(options.signal.aborted) reject(new Error("aborted"));
    else options.signal.addEventListener("abort",()=>reject(new Error("aborted")),{once:true});
  }));
  await assert.rejects(client.confirm(), e=>e.code==="ABORTED_OR_TIMEOUT" && e.uncertainMutation);
  const abort = new AbortController(); abort.abort();
  await assert.rejects(client.getState({signal:abort.signal}),e=>e.code==="ABORTED_OR_TIMEOUT" && !e.uncertainMutation);
});
test("rejects invalid destinations for bridge transport and invalid command values", () => {
  for(const baseUrl of ["https://example.com","http://127.0.0.1:8791/path","http://u:p@localhost:8791","http://localhost/?x=1"])
    assert.throws(()=>createRoutineClient({baseUrl,token:"x"}));
  const client=setup(async()=>response());
  for(const args of [{operation:"confirm"},{bad:null},{bad:{}},{bad:NaN}]) assert.throws(()=>client.command("x",args));
});
test("seedDemo preserves legacy options and encodes configured dates and destinations once", async () => {
  const calls = [];
  const client = setup(async (_, options) => { calls.push(new URLSearchParams(options.body)); return response(); });
  await client.seedDemo();
  await client.seedDemo({ signal: new AbortController().signal });
  await client.seedDemo({ days: 3, estimateDays: 2, destinationIds: ["place-a", "place-b"] });
  assert.deepEqual([...calls[0]], [["operation", "seedDemo"]]);
  assert.deepEqual([...calls[1]], [...calls[0]]);
  assert.deepEqual(Object.fromEntries(calls[2]), {operation:"seedDemo", days:"3", estimateDays:"2", destinationIds:"place-a,place-b"});
  for (const seed of [null, [], {destinationIds:[]}, {destinationIds:["a,b"]}, {days:2,signal:new AbortController().signal}])
    assert.throws(() => client.seedDemo(seed));
  assert.equal(calls.length, 3);
});
test("stale and unknown-units scenarios are explicit commands without retries", async () => {
  const calls = [];
  const client = setup(async (_, options) => { calls.push(new URLSearchParams(options.body).get("traffic")); return response(); });
  await client.setDemoScenario({ traffic: "STALE" });
  await client.setDemoScenario({ traffic: "UNKNOWN_UNITS" });
  assert.deepEqual(calls, ["STALE", "UNKNOWN_UNITS"]);
});

