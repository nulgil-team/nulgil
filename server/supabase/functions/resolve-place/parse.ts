// Shared map text (Naver Map, KakaoMap, Google Maps, or a plain place name) → what we can read from it.
// Pure: no network. Example from the Naver Map app's share sheet (2026-11-21):
//   [네이버지도]\n홈플러스 문경점\n경북 문경시 모전로 65 홈플러스\nhttps://naver.me/xAfHKpx5

export type Source = "NAVER" | "KAKAO" | "GOOGLE" | "TEXT";

export interface Shared {
  source: Source;
  name: string;
  address: string;
  url: string | null;
  /** Coordinates written in the link itself, when present. */
  point: { latitude: number; longitude: number } | null;
}

const URL_RE = /https?:\/\/[^\s<>"']+/i;
const HEADER_RE = /^\[([^\]]+)\]\s*(.*)$/;

export function parseShared(text: string): Shared {
  const url = (text.match(URL_RE) ?? [null])[0];
  const lines = text.split(/\r?\n/).map((l) => l.replace(URL_RE, "").trim()).filter(Boolean);
  let source: Source = "TEXT";
  const header = lines.length ? lines[0].match(HEADER_RE) : null;
  if (header) {
    const label = header[1];
    source = /네이버/.test(label) ? "NAVER" : /카카오/.test(label) ? "KAKAO" : /google|구글/i.test(label) ? "GOOGLE" : "TEXT";
    lines[0] = header[2]; // KakaoMap may put the name right after the label
    if (!lines[0]) lines.shift();
  }
  if (url && source === "TEXT") {
    if (/naver\.(me|com)/.test(url)) source = "NAVER";
    else if (/kko\.to|kakao\.com/.test(url)) source = "KAKAO";
    else if (/google\.|goo\.gl/.test(url)) source = "GOOGLE";
  }
  const name = (lines[0] ?? "").slice(0, 80);
  const address = (lines[1] ?? "").slice(0, 160);
  return { source, name, address, url, point: url ? pointInUrl(url) : null };
}

/** Coordinates some links carry: Google @lat,lng or q=lat,lng, KakaoMap /link/map/name,lat,lng. */
export function pointInUrl(url: string): { latitude: number; longitude: number } | null {
  const patterns = [/@(-?\d+\.\d+),(-?\d+\.\d+)/, /[?&](?:q|ll|query)=(-?\d+\.\d+),\s*(-?\d+\.\d+)/, /\/link\/(?:map|to)\/[^,]*,(-?\d+\.\d+),(-?\d+\.\d+)/];
  for (const re of patterns) {
    const m = decodeURIComponent(url).match(re);
    if (m) {
      const latitude = Number(m[1]), longitude = Number(m[2]);
      if (inKorea(latitude, longitude)) return { latitude, longitude };
    }
  }
  return null;
}

export function inKorea(latitude: number, longitude: number): boolean {
  return latitude >= 33 && latitude <= 39 && longitude >= 124 && longitude <= 132;
}

/** Kakao place id from a resolved KakaoMap link (place.map.kakao.com/123 or ...itemId=123). */
export function kakaoPlaceId(url: string): string | null {
  const m = url.match(/place\.map\.kakao\.com\/(?:m\/)?(\d+)/) ?? url.match(/[?&]itemId=(\d+)/);
  return m ? m[1] : null;
}

const squash = (s: string) => s.replace(/\s+/g, "").toLowerCase();

/** Same place: same name ignoring spaces, and the shared address starts with Kakao's road or lot address. */
export function samePlace(name: string, address: string, doc: { place_name: string; road_address_name: string; address_name: string }): boolean {
  if (squash(doc.place_name) !== squash(name)) return false;
  if (!address) return true;
  const a = squash(address);
  return [doc.road_address_name, doc.address_name].some((x) => x && a.startsWith(squash(x)));
}
