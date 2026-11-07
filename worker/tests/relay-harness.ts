/** TEST ENTRY ONLY. Never referenced by wrangler.toml or src/. Clock/faults stay here. */
import worker from "../src/index";
import { ownerOf, Unauthorized } from "../src/auth";
import { cleanupRelay, RelayError, relayErrorResponse, relayJson, relayRequest, type RelayEnv } from "../src/relay";

declare const TEST_PUT_CONTROL: string;

export default {
  async fetch(request: Request, env: RelayEnv): Promise<Response> {
    const url = new URL(request.url);
    let now = Number(request.headers.get("x-test-now"));
    const clock = () => now;
    if (!request.headers.has("x-test-now")) return worker.fetch(request, env);
    try {
      const owner = await ownerOf(request, env); // real session validation, including revocation
      if (url.pathname === "/__test/cleanup") {
        const bucket = env.AUDIO;
        const gcEnv = request.headers.has("x-test-fail-delete") ? { ...env, AUDIO: {
          list: bucket.list.bind(bucket), delete: async () => { throw new Error("injected R2 outage"); },
        } as unknown as R2Bucket } : env;
        return relayJson(await cleanupRelay(gcEnv, clock));
      }
      if (url.pathname.startsWith("/v1/relay")) {
        if (request.headers.has("x-test-streaming")) {
          const headers = new Headers(request.headers);
          headers.delete("content-length");
          request = new Request(request, { headers });
        }
        // Advance after a persisted R2 write, as if the isolate was delayed across expiry.
        const bucket = env.AUDIO;
        const pause = request.headers.get("x-test-pause-put");
        async function waitForPutRelease() {
          const response = await fetch(TEST_PUT_CONTROL + "/" + request.headers.get("x-test-put-gate"));
          const release = await response.json() as { now: number };
          now = release.now;
        }
        const delayedEnv = request.headers.has("x-test-expire-after-put") || pause ? { ...env, AUDIO: {
          put: async (...args: Parameters<R2Bucket["put"]>) => {
            if (pause === "before") await waitForPutRelease();
            const object = await bucket.put(...args);
            if (request.headers.has("x-test-expire-after-put")) now += 600_000;
            if (pause === "after") await waitForPutRelease();
            return object;
          },
          get: bucket.get.bind(bucket),
          list: bucket.list.bind(bucket),
          delete: bucket.delete.bind(bucket),
        } as R2Bucket } : env;
        return await relayRequest(request, delayedEnv, owner, url.pathname.split("/").filter(Boolean).map(decodeURIComponent), clock);
      }
      return worker.fetch(request, env);
    } catch (error) {
      if (error instanceof RelayError) return relayErrorResponse(error);
      if (error instanceof Unauthorized) return relayJson({ code: "unauthorized" }, 401);
      return relayJson({ code: "injected_failure" }, 500);
    }
  },
};
