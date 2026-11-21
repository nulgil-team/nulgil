// Server settings for the invite page. The publishable key is meant to be public;
// every server function checks the trip code (friends) or the car token (car) itself.
export const SUPABASE_URL = "https://bugrlfzfokmfpzshcjrt.supabase.co";
export const SUPABASE_PUBLISHABLE_KEY = "sb_publishable_oc1KkjeSa4J5A_rYWY9s_g_sQZA9kF-";

// Kakao Maps JavaScript key (Kakao Developers app "공모전_플렉", which holds the account's free Kakao Map quota).
// Allowed only on registered domains: https://nulgil-invite.vercel.app and http://localhost:5173 (local tests).
// Empty: Kakao is skipped and the page uses OpenStreetMap only.
export const KAKAO_JS_KEY = "0654bacf413faf4326ce189a1d4ed723";

// How often a friend's page checks what the driver did with the suggestion.
// Often right after sending (the driver may be looking), then slowly; never while the tab is hidden.
export const STATUS_POLL_FAST_MS = 5000;
export const STATUS_POLL_FAST_FOR_MS = 2 * 60 * 1000;
export const STATUS_POLL_SLOW_MS = 30000;
// The car never changes these again, so checking stops.
export const FINAL_STATUSES = ["ADDED", "UNCONFIRMED", "FAILED", "DISMISSED", "EXPIRED"];
