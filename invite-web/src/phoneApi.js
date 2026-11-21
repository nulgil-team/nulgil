// Server calls for the driver's phone (폰에서 경로 보내기). Uses the phone's own link token; never the car token.
import { SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY } from "./config.js";
import { ApiError } from "./api.js";

async function rpc(name, body) {
  let response;
  try {
    response = await fetch(`${SUPABASE_URL}/rest/v1/rpc/${name}`, {
      method: "POST",
      headers: { apikey: SUPABASE_PUBLISHABLE_KEY, "Content-Type": "application/json" },
      body: JSON.stringify(body ?? {}),
    });
  } catch {
    throw new ApiError("NETWORK", "인터넷 연결을 확인해 주세요.");
  }
  const data = await response.json().catch(() => null);
  if (!response.ok) {
    const code = data?.message?.split(" ")[0] ?? `HTTP_${response.status}`;
    throw new ApiError(code, messageFor(code));
  }
  return data;
}

function messageFor(code) {
  switch (code) {
    case "NOT_ALLOWED": return "차와의 연결이 끊겼어요. 차 화면에서 다시 연결해 주세요.";
    case "TOO_FAST": return "한 시간에 10곳까지 보낼 수 있어요. 조금 뒤 다시 보내 주세요.";
    default: return "잠시 뒤 다시 시도해 주세요.";
  }
}

/** Returns the id of the sent route. */
export async function sendRoute(link, place) {
  return rpc("send_phone_route", {
    p_link: link.id, p_phone_token: link.token, p_place_name: place.name, p_place_address: place.address,
    p_latitude: place.latitude, p_longitude: place.longitude,
  });
}

/** WAITING | TAKEN | EXPIRED | NONE */
export async function routeStatus(link, id) {
  return rpc("get_phone_route_status", { p_link: link.id, p_phone_token: link.token, p_id: id });
}
