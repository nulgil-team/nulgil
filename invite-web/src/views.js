// Screen templates. Change wording and layout here; behavior lives in app.js.
// Every template returns an HTML string. Buttons and inputs talk to app.js only through
// data-action / data-field attributes, so markup can be redesigned freely.
import { PLACES_ATTRIBUTION } from "./places.js";
import { MAP_ATTRIBUTION } from "./map.js";

const esc = (value) => String(value ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

const header = (title, lead) => `
  <header class="page-header">
    <div class="brand">늘길 · 들를 곳</div>
    <h1>${esc(title)}</h1>
    ${lead ? `<p class="muted">${esc(lead)}</p>` : ""}
  </header>`;

const error = (message) => (message ? `<p class="notice notice-error" role="alert">${esc(message)}</p>` : "");

export function loading() {
  return `${header("초대를 확인하고 있어요")}`;
}

export function enterCode({ code, error: message }) {
  return `${header("초대 코드를 입력해 주세요", "운전자의 차 화면에 보이는 6자리 코드예요.")}
  <section class="card stack">
    ${error(message)}
    <label class="field"><span class="field-label">초대 코드</span>
      <input class="field-input" data-field="code" value="${esc(code)}" maxlength="6" autocapitalize="characters" autocomplete="off" inputmode="text"></label>
    <button class="btn btn-primary btn-block" data-action="open-code">확인</button>
  </section>`;
}

export function closed({ message }) {
  return `${header("지금은 보낼 수 없어요", message)}
  <section class="card"><p class="muted">운전자에게 새 초대를 부탁해 주세요.</p></section>`;
}

export function compose({ sender, query, results, searching, error: message }) {
  const list = results.length
    ? `<ul class="place-list">${results.map((place, i) => `
        <li class="place-item"><button class="place-button" data-action="choose" data-index="${i}">
          <span class="place-name">${esc(place.name)}</span>
          <span class="place-address">${esc(place.address)}</span></button></li>`).join("")}</ul>`
    : query && !searching ? `<p class="muted">검색 결과가 없어요. 다른 이름으로 찾아보세요.</p>` : "";
  return `${header("가는 길에 들를 곳을 보내 주세요", "운전자가 차를 세웠을 때 확인하고 경로에 넣어요.")}
  <section class="card stack">
    ${error(message)}
    <label class="field"><span class="field-label">내 이름</span>
      <input class="field-input" data-field="sender" value="${esc(sender)}" maxlength="20" placeholder="예: 민지"></label>
    <label class="field"><span class="field-label">들를 곳</span>
      <div class="field-row">
        <input class="field-input" data-field="query" value="${esc(query)}" maxlength="60" placeholder="장소 이름이나 주소" enterkeyhint="search">
        <button class="btn btn-primary" data-action="search" ${searching ? "disabled" : ""}>${searching ? "찾는 중" : "검색"}</button>
      </div></label>
    ${list}
  </section>
  <p class="footer-note">${esc(PLACES_ATTRIBUTION)}</p>`;
}

export function confirm({ place, sender, note, sending, error: message }) {
  return `${header("이 곳을 보낼까요?")}
  <section class="card stack">
    ${error(message)}
    <div class="map" data-map></div>
    <p class="map-attribution">${esc(MAP_ATTRIBUTION)}</p>
    <div><span class="place-name">${esc(place.name)}</span><span class="place-address">${esc(place.address)}</span></div>
    <label class="field"><span class="field-label">한마디 (선택)</span>
      <input class="field-input" data-field="note" value="${esc(note)}" maxlength="80" placeholder="예: 빵 사 갈게"></label>
    <p class="muted">${esc(sender)} 님 이름으로 보내요. 차의 위치는 보이지 않아요.</p>
    <button class="btn btn-primary btn-block" data-action="send" ${sending ? "disabled" : ""}>${sending ? "보내는 중" : "들를 곳 보내기"}</button>
    <button class="btn btn-block" data-action="back">다시 고르기</button>
  </section>`;
}

const STATUS = {
  PENDING: ["운전자가 확인하기를 기다리고 있어요", "badge", "차를 세우면 확인해요."],
  REQUESTED: ["경로에 넣고 있어요", "badge badge-warning", "순정 내비가 경로를 다시 찾는 중이에요."],
  ADDED: ["경로에 들어갔어요", "badge badge-success", "가는 길에 들를게요."],
  UNCONFIRMED: ["경로에 넣었지만 확인은 못 했어요", "badge badge-warning", "운전자가 내비에서 직접 확인해요."],
  FAILED: ["이번에는 넣지 못했어요", "badge badge-danger", "들를 곳이 이미 가득 찼을 수 있어요."],
  DISMISSED: ["이번에는 넘겼어요", "badge", "다음에 다시 보내 주세요."],
};

export function sent({ place, status }) {
  const [title, badge, lead] = STATUS[status] ?? ["상태를 확인하고 있어요", "badge", ""];
  return `${header("들를 곳을 보냈어요")}
  <section class="card stack">
    <div><span class="place-name">${esc(place.name)}</span><span class="place-address">${esc(place.address)}</span></div>
    <p><span class="${badge}">${esc(title)}</span></p>
    ${lead ? `<p class="muted">${esc(lead)}</p>` : ""}
    <button class="btn btn-block" data-action="again">다른 곳도 보내기</button>
  </section>`;
}
