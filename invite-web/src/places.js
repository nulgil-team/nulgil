// Place search provider. Swap this file to use another search service (for example Kakao or Naver local
// search); the rest of the page only relies on searchPlaces() returning { name, address, latitude, longitude }.
//
// Current provider: OpenStreetMap Nominatim (no key). Usage policy: at most one request per second and
// no autocomplete, so the page searches only when the friend presses 검색.
const ENDPOINT = "https://nominatim.openstreetmap.org/search";

export const PLACES_ATTRIBUTION = "장소 검색 © OpenStreetMap contributors";

export async function searchPlaces(query) {
  const q = query.trim();
  if (!q) return [];
  const url = `${ENDPOINT}?format=jsonv2&addressdetails=1&limit=8&countrycodes=kr&accept-language=ko&q=${encodeURIComponent(q)}`;
  let response;
  try { response = await fetch(url, { headers: { Accept: "application/json" } }); }
  catch { throw new Error("장소 검색에 연결하지 못했어요."); }
  if (!response.ok) throw new Error("장소 검색이 잠시 막혔어요. 조금 뒤 다시 시도해 주세요.");
  const rows = await response.json();
  return rows.map(toPlace).filter(Boolean);
}

function toPlace(row) {
  const latitude = Number(row.lat), longitude = Number(row.lon);
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return null;
  const name = row.name || row.display_name.split(",")[0];
  return { name: name.slice(0, 80), address: shortAddress(row).slice(0, 160), latitude, longitude };
}

function shortAddress(row) {
  const a = row.address || {};
  const parts = [a.province || a.state, a.city, a.borough || a.county, a.suburb || a.quarter || a.neighbourhood,
    a.road, a.house_number];
  const text = [...new Set(parts.filter(Boolean))].join(" ");
  return text || row.display_name;
}
