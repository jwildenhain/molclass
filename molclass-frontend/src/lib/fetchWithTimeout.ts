/**
 * fetch() with a hard upper bound. A page-load request that never resolves — a stalled dev-server
 * HMR connection, a dropped proxy, a backend that accepted the connection but never answered —
 * otherwise leaves a "Loading..." spinner stuck forever with no way out for the user. This turns
 * that into a normal, retryable error after `timeoutMs`.
 */
export async function fetchWithTimeout(
  input: RequestInfo | URL,
  init: RequestInit = {},
  timeoutMs = 15000,
): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(input, { ...init, signal: controller.signal });
  } catch (cause) {
    if (controller.signal.aborted) {
      throw new Error("The request timed out. Check your connection and try again.");
    }
    throw cause;
  } finally {
    clearTimeout(timer);
  }
}
