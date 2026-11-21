// Map preview for the chosen place. app.js and phone.js only call showPlace(element, place).
// 1st: Kakao map (src/kakao.js). 2nd: Leaflet with OpenStreetMap tiles (loaded in the HTML) when Kakao fails.
import { loadKakao } from "./kakao.js";

export const MAP_ATTRIBUTION = "지도: 카카오맵(안 될 때 © OpenStreetMap contributors)";

let leafletMap = null, leafletMarker = null;

export function showPlace(element, place) {
  loadKakao()
    .then((kakao) => { if (element.isConnected) showKakao(kakao, element, place); })
    .catch(() => { if (element.isConnected) showLeaflet(element, place); });
}

function showKakao(kakao, element, place) {
  const position = new kakao.maps.LatLng(place.latitude, place.longitude);
  const map = new kakao.maps.Map(element, { center: position, level: 3 });
  new kakao.maps.Marker({ position, map });
  setTimeout(() => { map.relayout(); map.setCenter(position); }, 0);
}

function showLeaflet(element, place) {
  const L = window.L;
  if (!L) { element.textContent = "지도를 불러오지 못했어요."; return; }
  const position = [place.latitude, place.longitude];
  if (!leafletMap || leafletMap.getContainer() !== element) {
    leafletMap = L.map(element, { zoomControl: false, attributionControl: false }).setView(position, 16);
    L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19 }).addTo(leafletMap);
    leafletMarker = L.marker(position).addTo(leafletMap);
  } else {
    leafletMap.setView(position, 16);
    leafletMarker.setLatLng(position);
  }
  setTimeout(() => leafletMap.invalidateSize(), 0);
}
