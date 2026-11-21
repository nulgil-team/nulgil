// Server calls for the friend side. Only RPCs that need the trip code; never the car token.
import { SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY } from "./config.js";

export class ApiError extends Error {
  constructor(code, message) { super(message); this.code = code; }
}

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
    case "TRIP_CLOSED": return "이 초대는 끝났거나 시간이 지났어요.";
    case "TOO_MANY_SUGGESTIONS": return "이 이동에는 더 보낼 수 없어요. (최대 10곳)";
    default: return "잠시 뒤 다시 시도해 주세요.";
  }
}

/** { open, expiresAt, suggestionCount } */
export async function getTrip(code) {
  const [row] = await rpc("get_invite_trip", { p_code: code });
  return { open: !!row?.open, expiresAt: row?.expires_at ?? null, suggestionCount: row?.suggestion_count ?? 0 };
}

/** Returns the new suggestion id. */
export async function submitSuggestion(code, { sender, place, note }) {
  const [row] = await rpc("submit_invite_suggestion", {
    p_code: code, p_sender: sender, p_place_name: place.name, p_place_address: place.address,
    p_latitude: place.latitude, p_longitude: place.longitude, p_note: note ?? "",
  });
  return row.id;
}

/** PENDING | REQUESTED | ADDED | UNCONFIRMED | FAILED | DISMISSED, or null when unknown. */
export async function getSuggestionStatus(code, id) {
  const [row] = await rpc("get_invite_suggestion", { p_code: code, p_id: id });
  return row?.status ?? null;
}
