/*
 * 로컬 API 연결. 개발 서버가 있을 때만 동작한다.
 * 연결하지 못하면 화면은 오프라인 예시(core-snapshot)로 남는다.
 */
async function connect() {
  if (new URLSearchParams(location.search).has("offline")) return null;
  const response = await fetch("/dev-connection.json", { cache: "no-store" });
  if (!response.ok) return null;
  const connection = await response.json();
  const { createRoutineClient } = await import("/client/routine-client.mjs");
  return createRoutineClient(connection);
}

connect()
  .catch(() => null)
  .then((api) => window.__routineBoot(api));
