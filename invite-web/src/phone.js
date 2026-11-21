// Flow of the driver's phone page: link (once, from the car's QR) → compose (search or recent) → confirm (map,
// rough time) → sent (did the car take it?). Screens are in phoneViews.js; server calls in phoneApi.js.
import * as api from "./phoneApi.js";
import { searchPlaces } from "./places.js";
import { showPlace } from "./map.js";
import { currentPosition, roughMinutes } from "./eta.js";
import * as views from "./phoneViews.js";
import { STATUS_POLL_FAST_MS, STATUS_POLL_FAST_FOR_MS, STATUS_POLL_SLOW_MS } from "./config.js";

const root = document.getElementById("app");
const storage = {
  get(key) { try { return JSON.parse(localStorage.getItem(key)); } catch { return null; } },
  set(key, value) { try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* private mode */ } },
};
const RECENT_MAX = 5;
const FINAL = ["TAKEN", "EXPIRED", "NONE"];

const state = {
  screen: "unlinked", link: null, query: "", results: [], recent: storage.get("nulgil.recent") ?? [],
  place: null, minutes: null, estimating: false, sending: false, searching: false,
  sentId: null, sentAt: 0, status: null, error: "",
};
let pollTimer = null, checking = false;

function render() {
  root.innerHTML = views[state.screen](state);
  if (state.screen === "confirm") showPlace(root.querySelector("[data-map]"), state.place);
}
function go(screen, patch = {}) { Object.assign(state, { error: "" }, patch, { screen }); render(); }
function readFields() { root.querySelectorAll("[data-field]").forEach((input) => { state[input.dataset.field] = input.value; }); }

// The car's QR carries ?l=<link id>&k=<phone token>. Keep them on this phone and drop them from the address bar.
function loadLink() {
  const params = new URLSearchParams(location.search);
  const id = params.get("l"), token = params.get("k");
  if (id && /^[0-9a-f-]{36}$/.test(id) && token && /^[0-9a-f]{48}$/.test(token)) {
    storage.set("nulgil.link", { id, token });
    history.replaceState(null, "", location.pathname);
  }
  return storage.get("nulgil.link");
}

async function search() {
  readFields();
  if (!state.query.trim()) return;
  go("compose", { searching: true });
  try { go("compose", { results: await searchPlaces(state.query), searching: false }); }
  catch (e) { go("compose", { searching: false, error: e.message }); }
}

async function estimate() {
  go("confirm", { estimating: true });
  try {
    const here = await currentPosition();
    const minutes = await roughMinutes(here, state.place);
    go("confirm", { estimating: false, minutes, error: minutes == null ? "걸리는 시간을 확인하지 못했어요." : "" });
  } catch (e) { go("confirm", { estimating: false, error: e.message }); }
}

async function send() {
  go("confirm", { sending: true });
  try {
    const id = await api.sendRoute(state.link, state.place);
    const recent = [state.place, ...state.recent.filter((p) => p.name !== state.place.name || p.address !== state.place.address)].slice(0, RECENT_MAX);
    storage.set("nulgil.recent", recent);
    go("sent", { sending: false, sentId: id, sentAt: Date.now(), status: "WAITING", recent });
    poll();
  } catch (e) {
    if (e.code === "NOT_ALLOWED") { try { localStorage.removeItem("nulgil.link"); } catch { /* ignore */ } go("unlinked", { link: null }); return; }
    go("confirm", { sending: false, error: e.message });
  }
}

// Same pacing as the friend page: often right after sending, then slowly, never while hidden, stop when final.
function poll(delay) {
  clearTimeout(pollTimer); pollTimer = null;
  if (checking || state.screen !== "sent" || FINAL.includes(state.status) || document.hidden) return;
  const wait = delay ?? (Date.now() - state.sentAt < STATUS_POLL_FAST_FOR_MS ? STATUS_POLL_FAST_MS : STATUS_POLL_SLOW_MS);
  pollTimer = setTimeout(async () => {
    pollTimer = null; checking = true;
    try {
      const status = await api.routeStatus(state.link, state.sentId);
      if (status !== state.status && state.screen === "sent") go("sent", { status });
    } catch { /* keep the last status and try again */ }
    checking = false; poll();
  }, wait);
}
document.addEventListener("visibilitychange", () => { if (document.hidden) { clearTimeout(pollTimer); pollTimer = null; } else poll(0); });

root.addEventListener("click", (event) => {
  const target = event.target.closest("[data-action]");
  if (!target || target.disabled) return;
  const action = target.dataset.action;
  if (action === "search") search();
  else if (action === "choose") go("confirm", { place: state.results[Number(target.dataset.index)], minutes: null });
  else if (action === "choose-recent") go("confirm", { place: state.recent[Number(target.dataset.index)], minutes: null });
  else if (action === "estimate") estimate();
  else if (action === "send") send();
  else if (action === "back") go("compose");
  else if (action === "again") { clearTimeout(pollTimer); go("compose", { query: "", results: [], place: null, sentId: null, status: null }); }
});
root.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && event.target.dataset?.field === "query") { event.preventDefault(); search(); }
});

state.link = loadLink();
go(state.link ? "compose" : "unlinked");
