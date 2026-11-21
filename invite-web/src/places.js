// Place search. The rest of the page only relies on searchPlaces() returning { name, address, latitude, longitude }.
//
// 1st: Kakao local search (src/kakao.js) — best coverage of Korean shops and buildings, free daily quota.
// 2nd: OpenStreetMap Nominatim (no key) when Kakao is unavailable (blocked, quota, error) or finds nothing.
//      Nominatim usage policy: at most one request per second and no autocomplete, so the page searches only
//      when the person presses 검색.
import { kakaoKeywordSearch } from "./kakao.js";

const NOMINATIM = "https://nominatim.openstreetmap.org/search";

export const PLACES_ATTRIBUTION = "장소 검색: 카카오(안 될 때 OpenStreetMap)";

export async function searchPlaces(query) {
  const q = query.trim();
  if (!q) return [];
  try {
    const rows = await kakaoKeywordSearch(q);
    if (rows.length) return rows.map(fromKakao).filter(Boolean);
  } catch { /* fall through to OpenStreetMap */ }
  return searchNominatim(q);
}

function fromKakao(row) {
  const latitude = Number(row.y), longitude = Number(row.x);
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return null;
  const address = row.road_address_name || row.address_name || "";
  return { name: String(row.place_name).slice(0, 80), address: address.slice(0, 160), latitude, longitude };
}

async function searchNominatim(q) {
  const url = `${NOMINATIM}?format=jsonv2&addressdetails=1&limit=8&countrycodes=kr&accept-language=ko&q=${encodeURIComponent(q)}`;
  let response;
  try { response = await fetch(url, { headers: { Accept: "application/json" } }); }
  catch { throw new Error("장소 검색에 연결하지 못했어요."); }
  if (!response.ok) throw new Error("장소 검색이 잠시 막혔어요. 조금 뒤 다시 시도해 주세요.");
  const rows = await response.json();
  return rows.map(fromNominatim).filter(Boolean);
}

function fromNominatim(row) {
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
