/**
 * L'indice in cloud di Pampa Notes. Un Worker, un D1, un bucket R2.
 *
 * Solo testo nell'indice: note, cartelle, sessioni, trascrizioni coi segmenti, sorgenti, preset.
 * Mai un file audio, mai un originale: quelli vanno da dispositivo a computer di casa e basta.
 * L'unica eccezione e' l'audio delle note **condivise**, che sta in R2 finche' il link e' vivo.
 */

import { bearerOf, loginWithGoogle, logout, ownerOf, Unauthorized, type Env } from "./auth";
import { createGuest, listGuests, reportUsage, revokeGuest, verifyGuest } from "./guests";
import { pageHtml } from "./page";
import {
  abortMultipart, audioResponse, beginMultipart, completeMultipart, createShare, headAudio, listShares, noteOpened,
  NotFound, pageData, putAudio, putMultipartPart, revokeShare, type ShareEnv,
} from "./shares";
import { BadRequest, pull, push, status, type PushRequest } from "./sync";

const JSON_HEADERS = { "content-type": "application/json; charset=utf-8" };

function json(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), { ...init, headers: { ...JSON_HEADERS, ...(init.headers ?? {}) } });
}

/** `/v1/shares/:id/audio/:partId/multipart/:uploadId/:n` e simili: i pezzi del percorso, gia' decodificati. */
function segments(pathname: string): string[] {
  return pathname.split("/").filter(Boolean).map((s) => decodeURIComponent(s));
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const protocol = Number(env.PROTOCOL_VERSION ?? "1");
    const path = segments(url.pathname);
    const method = request.method;

    if (url.pathname === "/health") {
      return json({ status: "ok", protocolVersion: protocol });
    }

    try {
      // --- la pagina di una nota condivisa: senza token, e' il link la chiave ---
      if (path[0] === "s" && path.length >= 2) {
        const share = env as ShareEnv;
        const token = path[1];
        if (path.length === 2 && method === "GET") {
          if (!(await noteOpened(share, token))) return new Response(pageHtml(), { status: 404, headers: { "content-type": "text/html; charset=utf-8" } });
          return new Response(pageHtml(), { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });
        }
        if (path.length === 3 && path[2] === "data" && method === "GET") {
          const data = await pageData(share, token);
          return data ? json(data, { headers: { "cache-control": "no-store" } }) : json({ error: "non trovato" }, { status: 404 });
        }
        if (path.length === 4 && path[2] === "audio" && (method === "GET" || method === "HEAD")) {
          return audioResponse(share, token, path[3], request);
        }
        return json({ error: "non trovato" }, { status: 404 });
      }

      // --- l'accesso: un ID token di Google diventa una sessione ---
      if (url.pathname === "/v1/auth/google" && method === "POST") {
        const body = (await request.json().catch(() => ({}))) as { idToken?: string; deviceId?: string; deviceName?: string };
        if (typeof body.idToken !== "string" || !body.idToken) return json({ error: "idToken mancante" }, { status: 400 });
        return json(await loginWithGoogle(env, body.idToken, body.deviceId ?? "", body.deviceName ?? ""));
      }
      if (url.pathname === "/v1/auth/logout" && method === "POST") {
        const closed = await logout(env, bearerOf(request));
        return closed ? json({ loggedOut: true }) : json({ error: "nessuna sessione da chiudere" }, { status: 401 });
      }

      // --- gli ospiti del computer: il companion chiede e riporta, senza token del proprietario ---
      if (url.pathname === "/v1/guests/verify" && method === "POST") {
        const body = (await request.json().catch(() => ({}))) as { token?: string; owner?: string };
        const guest = await verifyGuest(env, typeof body.token === "string" ? body.token : "", typeof body.owner === "string" ? body.owner.trim() : "");
        return guest ? json({ ok: true, name: guest.name }) : json({ error: "ospite non riconosciuto" }, { status: 401 });
      }
      if (url.pathname === "/v1/guests/usage" && method === "POST") {
        const body = (await request.json().catch(() => ({}))) as { token?: string; seconds?: number };
        const counted = await reportUsage(env, typeof body.token === "string" ? body.token : "", Number(body.seconds ?? 0));
        return counted ? json({ ok: true }) : json({ error: "ospite non riconosciuto" }, { status: 401 });
      }
      if (path[0] === "v1" && path[1] === "guests") {
        const ownerId = await ownerOf(request, env);
        if (path.length === 2 && method === "POST") return json(await createGuest(env, ownerId, (await request.json().catch(() => ({}))) as { name?: string }));
        if (path.length === 2 && method === "GET") return json({ guests: await listGuests(env, ownerId) });
        if (path.length === 3 && method === "DELETE") {
          const revoked = await revokeGuest(env, ownerId, path[2]);
          return revoked ? json({ revoked: true }) : json({ error: "ospite inesistente" }, { status: 404 });
        }
      }

      // --- l'indice ---
      if (url.pathname === "/v1/sync/push" && method === "POST") {
        const ownerId = await ownerOf(request, env);
        const body = (await request.json()) as PushRequest;
        if (body.protocolVersion !== undefined && body.protocolVersion !== protocol) {
          return json({ error: "protocollo diverso", protocolVersion: protocol }, { status: 409 });
        }
        return json(await push(env, ownerId, body));
      }

      if (url.pathname === "/v1/sync/pull" && method === "GET") {
        const ownerId = await ownerOf(request, env);
        const since = Number(url.searchParams.get("since") ?? "0");
        const limit = Number(url.searchParams.get("limit") ?? "200");
        const deviceId = url.searchParams.get("deviceId") ?? "";
        if (!deviceId) return json({ error: "deviceId mancante" }, { status: 400 });
        if (!Number.isFinite(since) || since < 0) return json({ error: "since non valido" }, { status: 400 });
        return json(await pull(env, ownerId, deviceId, since, limit));
      }

      if (url.pathname === "/v1/sync/status" && method === "GET") {
        const ownerId = await ownerOf(request, env);
        return json(await status(env, ownerId));
      }

      // --- le condivisioni, dal proprietario ---
      if (path[0] === "v1" && path[1] === "shares") {
        const share = env as ShareEnv;
        const ownerId = await ownerOf(request, env);
        const origin = env.PUBLIC_ORIGIN?.trim() || url.origin;

        if (path.length === 2 && method === "POST") return json(await createShare(share, ownerId, (await request.json()) as { noteId?: string; title?: string }, origin));
        if (path.length === 2 && method === "GET") return json({ shares: await listShares(share, ownerId, origin) });
        if (path.length === 3 && method === "DELETE") {
          const revoked = await revokeShare(share, ownerId, path[2]);
          return revoked ? json({ revoked: true }) : json({ error: "condivisione inesistente" }, { status: 404 });
        }
        if (path.length >= 5 && path[3] === "audio") {
          const shareId = path[2];
          const partId = path[4];
          if (path.length === 5 && method === "HEAD") {
            const head = await headAudio(share, ownerId, shareId, partId);
            return new Response(null, { status: head.exists ? 200 : 404, headers: { "content-length": String(head.bytes) } });
          }
          if (path.length === 5 && method === "PUT") return json(await putAudio(share, ownerId, shareId, partId, request));
          if (path.length === 6 && path[5] === "multipart" && method === "POST") {
            const body = (await request.json().catch(() => ({}))) as { mime?: string };
            return json(await beginMultipart(share, ownerId, shareId, partId, body.mime ?? ""));
          }
          if (path.length === 8 && path[5] === "multipart" && method === "PUT") {
            return json(await putMultipartPart(share, ownerId, shareId, partId, path[6], Number(path[7]), request));
          }
          if (path.length === 8 && path[5] === "multipart" && path[7] === "complete" && method === "POST") {
            const body = (await request.json()) as { parts: { partNumber: number; etag: string }[] };
            return json(await completeMultipart(share, ownerId, shareId, partId, path[6], body.parts));
          }
          if (path.length === 7 && path[5] === "multipart" && method === "DELETE") {
            await abortMultipart(share, ownerId, shareId, partId, path[6]);
            return json({ aborted: true });
          }
        }
      }

      return json({ error: "non trovato" }, { status: 404 });
    } catch (error) {
      if (error instanceof Unauthorized) return json({ error: error.message }, { status: 401 });
      if (error instanceof BadRequest) return json({ error: error.message }, { status: 400 });
      if (error instanceof NotFound) return json({ error: error.message }, { status: 404 });
      console.error(error);
      return json({ error: "errore interno" }, { status: 500 });
    }
  },
} satisfies ExportedHandler<Env>;
