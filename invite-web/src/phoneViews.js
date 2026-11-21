// Screen templates for the driver's phone page (phone.html). Wording and layout only; behavior lives in phone.js.
// Like views.js, buttons and inputs talk to phone.js only through data-action / data-field attributes.
import { PLACES_ATTRIBUTION } from "./places.js";
import { MAP_ATTRIBUTION } from "./map.js";

const esc = (value) => String(value ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

const header = (title, lead) => `
  <header class="page-header">
    <div class="brand">늘길 · 차에 경로 보내기</div>
    <h1>${esc(title)}</h1>
    ${lead ? `<p class="muted">${esc(lead)}</p>` : ""}
  </header>`;

const error = (message) => (message ? `<p class="notice notice-error" role="alert">${esc(message)}</p>` : "");

export function unlinked() {
  return `${header("차와 먼저 연결해 주세요", "차 화면에서 내 일상 → 폰 연결을 누르고, 나오는 QR을 이 폰으로 찍어 주세요. 한 번만 하면 돼요.")}
  <section class="card"><p class="muted">연결하면 내려오면서 미리 목적지를 보내 둘 수 있어요. 차에 타서 안내 시작만 누르면 돼요.</p></section>`;
}

export function compose({ query, results, recent, searching, error: message }) {
  const recentList = recent.length ? `<p class="field-label">최근 보낸 곳</p><ul class="place-list">${recent.map((place, i) => `
      <li class="place-item"><button class="place-button" data-action="choose-recent" data-index="${i}">
        <span class="place-name">${esc(place.name)}</span><span class="place-address">${esc(place.address)}</span></button></li>`).join("")}</ul>` : "";
  const list = results.length
    ? `<ul class="place-list">${results.map((place, i) => `
        <li class="place-item"><button class="place-button" data-action="choose" data-index="${i}">
          <span class="place-name">${esc(place.name)}</span><span class="place-address">${esc(place.address)}</span></button></li>`).join("")}</ul>`
    : query && !searching ? `<p class="muted">검색 결과가 없어요. 다른 이름으로 찾아보세요.</p>` : "";
  return `${header("오늘 어디로 갈까요?", "보낸 목적지는 차에 타면 바로 보여요.")}
  <section class="card stack">
    ${error(message)}
    <label class="field"><span class="field-label">목적지</span>
      <div class="field-row">
        <input class="field-input" data-field="query" value="${esc(query)}" maxlength="60" placeholder="장소 이름이나 주소" enterkeyhint="search">
        <button class="btn btn-primary" data-action="search" ${searching ? "disabled" : ""}>${searching ? "찾는 중" : "검색"}</button>
      </div></label>
    ${list}
    ${recentList}
  </section>
  <p class="footer-note">${esc(PLACES_ATTRIBUTION)}</p>`;
}

export function confirm({ place, minutes, estimating, sending, error: message }) {
  const time = minutes != null
    ? `<p class="eta">약 <strong>${esc(minutes)}</strong>분 <span class="muted">· 지금 위치 기준 어림값이에요</span></p>`
    : `<button class="btn btn-block" data-action="estimate" ${estimating ? "disabled" : ""}>${estimating ? "확인 중" : "내 위치로 걸리는 시간 보기"}</button>`;
  return `${header(place.name, place.address)}
  <section class="card stack">
    ${error(message)}
    ${time}
    <div class="map" data-map></div>
    <p class="map-attribution">${esc(MAP_ATTRIBUTION)}</p>
    <button class="btn btn-primary btn-block" data-action="send" ${sending ? "disabled" : ""}>${sending ? "보내는 중" : "차에 경로 보내기"}</button>
    <button class="btn btn-block" data-action="back">다시 고르기</button>
  </section>
  <p class="footer-note">차의 순정 내비가 실제 경로와 시간을 계산해요.</p>`;
}

const STATUS = {
  WAITING: ["차에 보냈어요", "badge badge-warning", "차에 타서 늘길을 열면 바로 보여요. 안내 시작만 누르면 돼요."],
  TAKEN: ["차가 받았어요", "badge badge-success", "늘길 화면에서 안내 시작을 눌러 출발하세요."],
  EXPIRED: ["시간이 지나 취소됐어요", "badge", "보낸 뒤 2시간 안에 차에서 열지 않았어요. 다시 보내 주세요."],
  NONE: ["새 목적지로 바뀌었어요", "badge", "나중에 보낸 곳이 차로 가요."],
};

export function sent({ place, status }) {
  const [title, badge, lead] = STATUS[status] ?? STATUS.WAITING;
  return `${header(place.name, place.address)}
  <section class="card stack">
    <p><span class="${badge}">${esc(title)}</span></p>
    <p class="muted">${esc(lead)}</p>
    <button class="btn btn-block" data-action="again">다른 곳 보내기</button>
  </section>`;
}
