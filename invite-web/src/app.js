// Flow of the friend page: code → compose (name + search) → confirm (map + note) → sent (status).
// State lives here; screens are rendered by views.js; server and search are separate modules.
import * as api from "./api.js";
import { searchPlaces } from "./places.js";
import { showPlace } from "./map.js";
import * as views from "./views.js";
import { STATUS_POLL_MS } from "./config.js";

const root = document.getElementById("app");
const storage = {
  get(key) { try { return localStorage.getItem(key); } catch { return null; } },
  set(key, value) { try { localStorage.setItem(key, value); } catch { /* private mode */ } },
};

const state = {
  screen: "loading", code: "", sender: storage.get("nulgil.sender") ?? "", query: "", results: [],
  place: null, note: "", sentId: null, status: null, error: "", busy: false,
};
let pollTimer = null;

function render() {
  root.innerHTML = views[state.screen]({
    ...state, searching: state.busy && state.screen === "compose", sending: state.busy && state.screen === "confirm",
  });
  if (state.screen === "confirm") showPlace(root.querySelector("[data-map]"), state.place);
}

function go(screen, patch = {}) { Object.assign(state, { error: "" }, patch, { screen }); render(); }

function readFields() {
  root.querySelectorAll("[data-field]").forEach((input) => { state[input.dataset.field] = input.value; });
}

async function openCode(code) {
  const normalized = code.trim().toUpperCase();
  if (!/^[A-Z2-9]{6}$/.test(normalized)) { go("enterCode", { code, error: "6자리 코드를 확인해 주세요." }); return; }
  go("loading", { code: normalized });
  try {
    const trip = await api.getTrip(normalized);
    if (!trip.open) { go("closed", { message: "이 초대는 끝났거나 시간이 지났어요." }); return; }
    history.replaceState(null, "", `?c=${normalized}`);
    go("compose");
  } catch (e) { go("enterCode", { code: normalized, error: e.message }); }
}

async function search() {
  readFields();
  if (!state.query.trim()) return;
  state.busy = true; go("compose");
  try { state.results = await searchPlaces(state.query); state.busy = false; go("compose"); }
  catch (e) { state.busy = false; go("compose", { error: e.message }); }
}

async function send() {
  readFields();
  const sender = state.sender.trim();
  if (!sender) { go("compose", { error: "내 이름을 적어 주세요." }); return; }
  storage.set("nulgil.sender", sender);
  state.busy = true; go("confirm");
  try {
    const id = await api.submitSuggestion(state.code, { sender, place: state.place, note: state.note.trim() });
    state.busy = false;
    go("sent", { sentId: id, status: "PENDING" });
    poll();
  } catch (e) {
    state.busy = false;
    if (e.code === "TRIP_CLOSED") go("closed", { message: e.message }); else go("confirm", { error: e.message });
  }
}

function poll() {
  clearTimeout(pollTimer);
  if (state.screen !== "sent" || !state.sentId) return;
  pollTimer = setTimeout(async () => {
    try {
      const status = await api.getSuggestionStatus(state.code, state.sentId);
      if (status && status !== state.status && state.screen === "sent") go("sent", { status });
    } catch { /* keep the last known status and try again */ }
    if (!["ADDED", "FAILED", "DISMISSED"].includes(state.status)) poll();
  }, STATUS_POLL_MS);
}

root.addEventListener("click", (event) => {
  const target = event.target.closest("[data-action]");
  if (!target || target.disabled) return;
  const action = target.dataset.action;
  if (action === "open-code") { readFields(); openCode(state.code); }
  else if (action === "search") search();
  else if (action === "choose") { readFields(); go("confirm", { place: state.results[Number(target.dataset.index)], note: "" }); }
  else if (action === "back") { readFields(); go("compose"); }
  else if (action === "send") send();
  else if (action === "again") { clearTimeout(pollTimer); go("compose", { query: "", results: [], place: null, sentId: null, status: null }); }
});

root.addEventListener("keydown", (event) => {
  if (event.key !== "Enter") return;
  const field = event.target.dataset?.field;
  if (field === "query") { event.preventDefault(); search(); }
  if (field === "code") { event.preventDefault(); readFields(); openCode(state.code); }
});

const initial = new URLSearchParams(location.search).get("c");
if (initial) openCode(initial); else go("enterCode", { code: "" });
