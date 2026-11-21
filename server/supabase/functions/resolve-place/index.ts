// resolve-place: shared map text → a place with coordinates, for the 늘길 phone app (and later the web).
//
// POST { "text": "[네이버지도]\n홈플러스 문경점\n경북 문경시 모전로 65 홈플러스\nhttps://naver.me/xAfHKpx5" }
// → { "status": "OK", "place": { name, address, latitude, longitude, source }, "candidates": [...] }
//   status OK: one place; CHOOSE: several candidates, the person picks; NOT_FOUND: nothing usable.
//
// Steps: coordinates in the link → follow the short link (KakaoMap place id) → Kakao keyword search by name,
// matching name and address (or the Kakao place id) → Kakao address search. Nothing is stored. The Kakao REST
// key lives in Supabase Vault and is read with the service role (public.kakao_rest_key()).
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { inKorea, kakaoPlaceId, parseShared, samePlace } from "./parse.ts";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const MAX_TEXT = 600;
let restKey: string | null = null;

interface Place { name: string; address: string; latitude: number; longitude: number; source: string; }

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
  if (req.method !== "POST") return json({ error: "POST only" }, 405);
  let text = "";
  try { text = String((await req.json())?.text ?? ""); } catch { return json({ error: "bad json" }, 400); }
  text = text.slice(0, MAX_TEXT).trim();
  if (!text) return json({ status: "NOT_FOUND", candidates: [] });
  try { return json(await resolve(text)); }
  catch (e) { console.error("resolve-place", e); return json({ status: "ERROR", candidates: [] }, 502); }
});

async function resolve(text: string) {
  const shared = parseShared(text);
  let name = shared.name, address = shared.address, placeId: string | null = null;
  if (shared.point) {
    return ok({ name: name || "공유한 위치", address, ...shared.point, source: shared.source });
  }
  if (shared.url) {
    const finalUrl = await followRedirects(shared.url);
    if (finalUrl) placeId = kakaoPlaceId(finalUrl);
  }
  const key = await kakaoKey();
  const docs = name ? await kakao(key, "keyword", name) : [];
  const exact = placeId ? docs.find((d) => d.id === placeId) : docs.find((d) => samePlace(name, address, d));
  if (exact) return ok(toPlace(exact, shared.source));
  // Name not found exactly: the shared address alone still gives the building.
  if (address) {
    // Full address first; Naver adds the building name at the end ("... 모전로 65 홈플러스"), so then without it.
    const hit = (await kakao(key, "address", address))[0]
      ?? (/\s\D+$/.test(address) ? (await kakao(key, "address", address.replace(/\s+\D+$/, "")))[0] : undefined);
    if (hit) {
      const latitude = Number(hit.y), longitude = Number(hit.x);
      if (inKorea(latitude, longitude)) return ok({ name: name || address, address, latitude, longitude, source: shared.source });
    }
  }
  const candidates = docs.map((d) => toPlace(d, shared.source)).filter((p) => inKorea(p.latitude, p.longitude)).slice(0, 8);
  if (candidates.length === 1) return ok(candidates[0]);
  return { status: candidates.length ? "CHOOSE" : "NOT_FOUND", candidates };
}

const ok = (place: Place) => ({ status: "OK", place, candidates: [place] });

function toPlace(d: Record<string, string>, source: string): Place {
  return { name: d.place_name, address: d.road_address_name || d.address_name, latitude: Number(d.y), longitude: Number(d.x), source };
}

/** Short links (naver.me, kko.to) redirect a few times; the last URL carries the place id. */
async function followRedirects(url: string): Promise<string | null> {
  let current = url;
  for (let i = 0; i < 5; i++) {
    const res = await fetch(current, { redirect: "manual", signal: AbortSignal.timeout(4000) }).catch(() => null);
    if (!res) return current;
    const next = res.headers.get("location");
    await res.body?.cancel();
    if (!next || res.status < 300 || res.status >= 400) return current;
    current = new URL(next, current).toString();
  }
  return current;
}

async function kakao(key: string, kind: "keyword" | "address", query: string): Promise<Record<string, string>[]> {
  const res = await fetch(`https://dapi.kakao.com/v2/local/search/${kind}.json?size=10&query=${encodeURIComponent(query)}`,
    { headers: { Authorization: `KakaoAK ${key}` }, signal: AbortSignal.timeout(5000) });
  if (!res.ok) throw new Error(`kakao ${kind} ${res.status}`);
  return (await res.json()).documents ?? [];
}

async function kakaoKey(): Promise<string> {
  if (restKey) return restKey;
  const res = await fetch(`${Deno.env.get("SUPABASE_URL")}/rest/v1/rpc/kakao_rest_key`, {
    method: "POST",
    headers: { apikey: Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, Authorization: `Bearer ${Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")}`,
      "Content-Type": "application/json" },
    body: "{}",
  });
  const value = res.ok ? await res.json() : null;
  if (!value) throw new Error("kakao key missing");
  restKey = String(value);
  return restKey;
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { ...CORS, "Content-Type": "application/json; charset=utf-8" } });
}
