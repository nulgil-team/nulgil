// Rough travel time for the phone screen ("약 N분"). Public OpenStreetMap router (OSRM), display only: the car's
// stock navi computes the real time. Uses the phone's position only when the person asks, and only for this call.
const ROUTER = "https://router.project-osrm.org/route/v1/driving/";

export function currentPosition() {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) { reject(new Error("이 브라우저는 위치를 지원하지 않아요.")); return; }
    navigator.geolocation.getCurrentPosition(
      (p) => resolve({ latitude: p.coords.latitude, longitude: p.coords.longitude }),
      () => reject(new Error("위치를 확인하지 못했어요.")),
      { enableHighAccuracy: false, timeout: 8000, maximumAge: 300000 });
  });
}

/** Minutes, rounded, or null when the router has no answer. */
export async function roughMinutes(from, to) {
  const coords = [from, to].map((p) => `${p.longitude.toFixed(3)},${p.latitude.toFixed(3)}`).join(";");
  try {
    const response = await fetch(`${ROUTER}${coords}?overview=false`);
    if (!response.ok) return null;
    const data = await response.json();
    const seconds = data?.routes?.[0]?.duration;
    return Number.isFinite(seconds) ? Math.max(1, Math.round(seconds / 60)) : null;
  } catch { return null; }
}
