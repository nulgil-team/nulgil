// Kakao Maps JavaScript SDK loader (지도 + 장소 검색 services). Loaded once, on first use.
// The key is a JavaScript key limited to this site's domains in Kakao Developers, so it is safe in the page.
// Anything that fails here (blocked script, wrong domain, quota) makes callers fall back to OpenStreetMap.
import { KAKAO_JS_KEY } from "./config.js";

const LOAD_TIMEOUT_MS = 5000;
let loading = null;

/** Resolves window.kakao with maps and services ready, or rejects. */
export function loadKakao() {
  if (!KAKAO_JS_KEY) return Promise.reject(new Error("no kakao key"));
  if (loading) return loading;
  loading = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("kakao timeout")), LOAD_TIMEOUT_MS);
    const script = document.createElement("script");
    script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(KAKAO_JS_KEY)}&libraries=services&autoload=false`;
    script.onload = () => {
      if (!window.kakao?.maps?.load) { clearTimeout(timer); reject(new Error("kakao sdk missing")); return; }
      window.kakao.maps.load(() => { clearTimeout(timer); resolve(window.kakao); });
    };
    script.onerror = () => { clearTimeout(timer); reject(new Error("kakao sdk blocked")); };
    document.head.appendChild(script);
  });
  // A failed load is retried on the next page load only; this page keeps using OpenStreetMap.
  return loading;
}

/** Keyword search with Kakao's place index. Resolves [] for no results; rejects on errors (quota, network). */
export async function kakaoKeywordSearch(query) {
  const kakao = await loadKakao();
  return new Promise((resolve, reject) => {
    new kakao.maps.services.Places().keywordSearch(query, (data, status) => {
      if (status === kakao.maps.services.Status.OK) resolve(data);
      else if (status === kakao.maps.services.Status.ZERO_RESULT) resolve([]);
      else reject(new Error("kakao search " + status));
    }, { size: 8 });
  });
}
