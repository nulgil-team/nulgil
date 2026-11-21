// Server settings for the invite page. The publishable key is meant to be public;
// every server function checks the trip code (friends) or the car token (car) itself.
export const SUPABASE_URL = "https://bugrlfzfokmfpzshcjrt.supabase.co";
export const SUPABASE_PUBLISHABLE_KEY = "sb_publishable_oc1KkjeSa4J5A_rYWY9s_g_sQZA9kF-";

// How often a friend's page checks what the driver did with the suggestion.
export const STATUS_POLL_MS = 5000;
