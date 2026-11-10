export type Preference = "RECOMMENDED" | "SHORT_TIME" | "COMFORTABLE";
export type ActiveRoute = "ACTIVE" | "INACTIVE" | "UNAVAILABLE";
export type RecommendationHold = "NONE" | "NOT_ENOUGH" | "TIE";
export type TrafficKind = "NOT_REQUESTED" | "MISSING" | "STALE" | "UNKNOWN_UNITS" | "NO_COMPARE" | "CALM" | "LATE";
export type DemoTraffic = "NORMAL" | "CONGESTED" | "MISSING" | "STALE" | "UNKNOWN_UNITS";
export interface Destination { id: string; name: string; address: string; latitude: number; longitude: number; subId: string }
export interface TiedCandidate { destination: Destination; samples: number }
export interface ComparisonDay { localDate: string; minutes: number }
export interface DemoSeed {
  /** Distinct weekly dates per destination, integer 0..6. Defaults to 3. */
  days?: number;
  /** Estimate dates per destination, integer 0..6. Defaults to days. */
  estimateDays?: number;
  /** IDs from snapshot.destinations. Defaults to the first destination. */
  destinationIds?: readonly string[];
}
export interface BackendSnapshot {
  schemaVersion: "1.0"; mode: "DEMO";
  context: { now: number; zoneId: string; localDate: string; day: number; minute: number };
  destinations: Destination[]; preference: Preference; collectionEnabled: boolean;
  counts: { rules: number; selections: number; estimates: number; navigationRequests: number };
  // Optional additive fields can be absent on older schema 1.0 servers. Do not parse messages as codes.
  recommendation: { status: string; source: string; destination: Destination | null; reason: string; samples: number | null; holdType?: RecommendationHold; candidates?: TiedCandidate[] };
  traffic: { status: string; estimateMinutes: number | null; baselineMinutes: number | null; delayMinutes: number | null; important: boolean; observedAt: number | null; basis: string | null; source: string; message: string; kind?: TrafficKind; history?: ComparisonDay[] };
  navigation: {
    state: "IDLE" | "READY" | "CHECKING_ROUTE" | "ALREADY_GUIDING" | "ROUTE_UNAVAILABLE" | "REQUESTING" | "VERIFIED" | "UNCONFIRMED" | "DISMISSED";
    /** Present on BR-05 and later servers. Require INACTIVE to enable a new request. */
    activeRoute?: ActiveRoute;
    code: string; canConfirm: boolean; message: string;
  };
  vehicle: { state: "PARKED" | "MOVING" | "UNKNOWN"; source: "DEMO" };
  capabilities: { liveNavigation: false; mapGeometry: false; etaPerPreference: false };
  notice: { code: string; message: string } | null;
}
export class RoutineApiError extends Error { code: string; status: number; uncertainMutation: boolean }
export interface RequestOptions { signal?: AbortSignal }
export interface RoutineClient {
  getState(options?: RequestOptions): Promise<BackendSnapshot>;
  command(operation: string, args?: Record<string, string | number | boolean>, options?: RequestOptions): Promise<BackendSnapshot>;
  setContext(now: number, zoneId: string, options?: RequestOptions): Promise<BackendSnapshot>;
  selectDestination(id: string, subId: string, options?: RequestOptions): Promise<BackendSnapshot>;
  setPreference(value: Preference, options?: RequestOptions): Promise<BackendSnapshot>;
  setConsent(enabled: boolean, options?: RequestOptions): Promise<BackendSnapshot>;
  registerRoutine(id: string, subId: string, options?: RequestOptions): Promise<BackendSnapshot>;
  recommend(historyOnly?: boolean, options?: RequestOptions): Promise<BackendSnapshot>;
  refreshTraffic(options?: RequestOptions): Promise<BackendSnapshot>;
  refreshActiveRoute(options?: RequestOptions): Promise<BackendSnapshot>;
  confirm(options?: RequestOptions): Promise<BackendSnapshot>;
  dismiss(options?: RequestOptions): Promise<BackendSnapshot>;
  clearHistory(options?: RequestOptions): Promise<BackendSnapshot>;
  seedDemo(options?: RequestOptions): Promise<BackendSnapshot>;
  seedDemo(seed: DemoSeed, options?: RequestOptions): Promise<BackendSnapshot>;
  setDemoScenario(scenario: { traffic?: DemoTraffic; parked?: boolean; failNext?: boolean; activeRoute?: ActiveRoute }, options?: RequestOptions): Promise<BackendSnapshot>;
  newDemoTrip(options?: RequestOptions): Promise<BackendSnapshot>;
}
export function createRoutineClient(options: { baseUrl: string; token: string; timeoutMs?: number; fetchImpl?: typeof fetch }): RoutineClient;

