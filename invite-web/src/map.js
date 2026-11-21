// Map preview for the chosen place. Leaflet with OpenStreetMap tiles (loaded in index.html).
// Replace this file to use another map; app.js only calls showPlace(element, place).
export const MAP_ATTRIBUTION = "지도 © OpenStreetMap contributors";

let map = null, marker = null;

export function showPlace(element, place) {
  const L = window.L;
  if (!L) { element.textContent = "지도를 불러오지 못했어요."; return; }
  const position = [place.latitude, place.longitude];
  if (!map || map.getContainer() !== element) {
    map = L.map(element, { zoomControl: false, attributionControl: false }).setView(position, 16);
    L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19 }).addTo(map);
    marker = L.marker(position).addTo(map);
  } else {
    map.setView(position, 16);
    marker.setLatLng(position);
  }
  setTimeout(() => map.invalidateSize(), 0);
}
