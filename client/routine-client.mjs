/** Framework-free client for the local DEMO bridge. Never retries a command. */
export class RoutineApiError extends Error {
  constructor(code, message, { status = 0, uncertainMutation = false, cause } = {}) {
    super(message, { cause });
    this.name = "RoutineApiError";
    this.code = code;
    this.status = status;
    this.uncertainMutation = uncertainMutation;
  }
}
export function createRoutineClient({ baseUrl, token, timeoutMs = 8000, fetchImpl = globalThis.fetch } = {}) {
  const url = new URL(baseUrl);
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost"].includes(url.hostname)
      || url.username || url.password || url.search || url.hash || !["", "/"].includes(url.pathname))
    throw new TypeError("baseUrl must be a loopback HTTP origin");
  if (typeof token !== "string" || !token.trim()) throw new TypeError("A connection token is required");
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new TypeError("timeoutMs must be positive");
  if (typeof fetchImpl !== "function") throw new TypeError("fetch is required");
  async function request(method, path, body, { signal } = {}) {
    const controller = new AbortController();
    const forwardAbort = () => controller.abort(signal?.reason);
    if (signal?.aborted) forwardAbort();
    else signal?.addEventListener("abort", forwardAbort, { once: true });
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    let status = 0;
    const mutation = method === "POST";
    try {
      const response = await fetchImpl(url.origin + path, {
        method, headers: { "X-Routine-Token": token, ...(body ? { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" } : {}) },
        body, signal: controller.signal, cache: "no-store", credentials: "omit", redirect: "error"
      });
      status = response.status;
      const result = await response.json();
      if (result?.schemaVersion !== "1.0")
        throw new RoutineApiError("PROTOCOL_ERROR", "Unsupported backend schema", { status, uncertainMutation: mutation });
      if (!response.ok) {
        if (typeof result?.error?.code !== "string" || typeof result?.error?.message !== "string")
          throw new RoutineApiError("PROTOCOL_ERROR", "Invalid backend error", { status, uncertainMutation: mutation });
        throw new RoutineApiError(result.error.code, result.error.message, {
          status, uncertainMutation: mutation && status >= 500
        });
      }
      if (result.mode !== "DEMO" || !result.context || !result.navigation || !result.capabilities)
        throw new RoutineApiError("PROTOCOL_ERROR", "Invalid DEMO state", { status, uncertainMutation: mutation });
      return result;
    } catch (error) {
      if (error instanceof RoutineApiError) throw error;
      throw new RoutineApiError(controller.signal.aborted ? "ABORTED_OR_TIMEOUT" : "TRANSPORT_ERROR",
        controller.signal.aborted ? "Request stopped or timed out; read state before another command" : "Could not read the backend response",
        { status, uncertainMutation: mutation, cause: error });
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener("abort", forwardAbort);
    }
  }
  const getState = options => request("GET", "/v1/state", undefined, options);
  const command = (operation, args = {}, options) => {
    if (typeof operation !== "string" || !operation.trim()) throw new TypeError("operation is required");
    if (args === null || typeof args !== "object" || Array.isArray(args)) throw new TypeError("args must be an object");
    const body = new URLSearchParams({ operation });
    for (const [key, value] of Object.entries(args)) {
      if (key === "operation" || !key || !["string", "number", "boolean"].includes(typeof value)
          || (typeof value === "number" && !Number.isFinite(value)))
        throw new TypeError("Command arguments must be named scalar values");
      body.append(key, String(value));
    }
    return request("POST", "/v1/commands", body.toString(), options);
  };
  const seedDemo = (seed = {}, options) => {
    // Keep the original seedDemo({ signal }) request-options call compatible.
    if (seed === null || typeof seed !== "object" || Array.isArray(seed))
      throw new TypeError("seed must be an object");
    const { signal, ...args } = seed;
    if (signal !== undefined && Object.keys(args).length)
      throw new TypeError("Pass request options as the second argument when configuring seedDemo");
    if (args.destinationIds !== undefined) {
      if (!Array.isArray(args.destinationIds) || !args.destinationIds.length || args.destinationIds.some(
        id => typeof id !== "string" || !id.trim() || id.includes(",")))
        throw new TypeError("destinationIds must be a nonempty array of destination IDs");
      args.destinationIds = args.destinationIds.join(",");
    }
    return command("seedDemo", args, signal === undefined ? options : { signal });
  };
  return Object.freeze({
    getState, command,
    setContext: (now, zoneId, options) => command("setContext", { now, zoneId }, options),
    selectDestination: (id, subId, options) => command("selectDestination", { id, subId }, options),
    setPreference: (value, options) => command("setPreference", { value }, options),
    setConsent: (enabled, options) => command("setConsent", { enabled }, options),
    registerRoutine: (id, subId, options) => command("registerRoutine", { id, subId }, options),
    recommend: (historyOnly = false, options) => command("recommend", { historyOnly }, options),
    refreshTraffic: options => command("refreshTraffic", {}, options),
    refreshActiveRoute: options => command("refreshActiveRoute", {}, options),
    confirm: options => command("confirm", {}, options),
    dismiss: options => command("dismiss", {}, options),
    clearHistory: options => command("clearHistory", {}, options),
    seedDemo,
    setDemoScenario: (scenario, options) => command("setDemoScenario", scenario, options),
    newDemoTrip: options => command("newDemoTrip", {}, options)
  });
}

