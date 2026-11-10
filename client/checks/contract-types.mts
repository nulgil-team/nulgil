import { createRoutineClient, type BackendSnapshot, type TrafficKind, type TiedCandidate } from "../routine-client.mjs";

// Compile-only contract check; these commands are never executed by the type checker.
const client = createRoutineClient({ baseUrl: "http://127.0.0.1:8791", token: "type-check-only" });
const signal = new AbortController().signal;
client.seedDemo();
client.seedDemo({ signal });
client.seedDemo({ days: 2 });
client.seedDemo({ days: 3, estimateDays: 2, destinationIds: ["a", "b"] as const }, { signal });
client.setDemoScenario({ traffic: "STALE" });
client.setDemoScenario({ traffic: "UNKNOWN_UNITS", activeRoute: "INACTIVE" });
// @ts-expect-error IDs are an array in the public client, not the wire CSV string.
client.seedDemo({ destinationIds: "a,b" });
// @ts-expect-error Day counts must be numeric.
client.seedDemo({ days: "2" });
// @ts-expect-error Unsupported scenario values cannot be sent from typed clients.
client.setDemoScenario({ traffic: "LIVE" });

function consume(state: BackendSnapshot) {
  const kind: TrafficKind | undefined = state.traffic.kind;
  const candidates: TiedCandidate[] = state.recommendation.candidates ?? [];
  const rows = state.traffic.history ?? [];
  return {kind, choices: candidates.map(c => [c.destination.id, c.samples]), dates: rows.map(r => [r.localDate, r.minutes])};
}
void consume;
