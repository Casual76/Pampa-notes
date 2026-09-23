/**
 * L'indice in cloud di Pampa Notes. Un Worker, un D1, un bucket R2.
 *
 * Solo testo nell'indice: note, cartelle, sessioni, trascrizioni coi segmenti, sorgenti, preset.
 * Mai un file audio, mai un originale: quelli vanno da dispositivo a computer di casa e basta.
 * L'unica eccezione e' l'audio delle note **condivise**, che sta in R2 finche' il link e' vivo.
 */

import { bearerOf, loginWithGoogle, logout, ownerOf, Unauthorized, type Env } from "./auth";
import { deleteComputer, getComputer, issueTicket, putComputer, verifyTicket, type PutComputerBody } from "./computer";
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
  // Un `%` che non apre una sequenza valida (`/s/abc%zz`) fa lanciare a decodeURIComponent: e' una
  // richiesta sbagliata, non un guasto del server.
  try {
    return pathname.split("/").filter(Boolean).map((s) => decodeURIComponent(s));
  } catch {
    throw new BadRequest("percorso non valido");
  }
}

/** Il corpo JSON di una richiesta che ne ha bisogno: illeggibile o non un oggetto e' un 400, non un 500. */
async function readJson<T>(request: Request): Promise<T> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    throw new BadRequest("corpo JSON non valido");
  }
  if (!body || typeof body !== "object" || Array.isArray(body)) throw new BadRequest("corpo JSON non valido");
  return body as T;
}

/** Per le rotte in cui il corpo e' facoltativo o si controlla campo per campo: quello che non e' un oggetto vale `{}`. */
async function looseJson(request: Request): Promise<Record<string, unknown>> {
  const body: unknown = await request.json().catch(() => null);
  return body && typeof body === "object" && !Array.isArray(body) ? (body as Record<string, unknown>) : {};
}

/**
 * Le intestazioni di tutto quello che sta sotto `/s/`. Il link e' la chiave: non deve uscire come
 * `Referer` verso nessuno, non deve finire in un motore di ricerca, e un audio caricato con un tipo
 * strano non deve diventare una pagina agli occhi del browser.
 */
const SHARE_HEADERS: Record<string, string> = {
  "x-content-type-options": "nosniff",
  "referrer-policy": "no-referrer",
  "x-robots-tag": "noindex, nofollow",
};

/**
 * La pagina e' un file solo con script e stile in linea, e parla solo con la sua origine (i dati e
 * l'audio). Tutto il resto — script di altri, form, frame, `<base>` — non ha motivo di esserci, e
 * se un giorno un titolo sfuggisse all'escape non avrebbe dove mandare niente.
 */
const PAGE_CSP = "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; media-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

function withShareHeaders(response: Response, extra: Record<string, string> = {}): Response {
  const out = new Response(response.body, response);
  for (const [k, v] of Object.entries({ ...SHARE_HEADERS, ...extra })) out.headers.set(k, v);
  return out;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const protocol = Number(env.PROTOCOL_VERSION ?? "1");
    const method = request.method;

    if (url.pathname === "/health") {
      return json({ status: "ok", protocolVersion: protocol });
    }

    try {
      const path = segments(url.pathname);

      // --- la pagina di una nota condivisa: senza token, e' il link la chiave ---
      if (path[0] === "s" && path.length >= 2) {
        const share = env as ShareEnv;
        const token = path[1];
        if (path.length === 2 && method === "GET") {
          const html = { "content-type": "text/html; charset=utf-8", "content-security-policy": PAGE_CSP };
          if (!(await noteOpened(share, token))) return withShareHeaders(new Response(pageHtml(), { status: 404, headers: html }));
          return withShareHeaders(new Response(pageHtml(), { headers: { ...html, "cache-control": "no-store" } }));
        }
        if (path.length === 3 && path[2] === "data" && method === "GET") {
          const data = await pageData(share, token);
          return withShareHeaders(data ? json(data, { headers: { "cache-control": "no-store" } }) : json({ error: "non trovato" }, { status: 404 }));
        }
        if (path.length === 4 && path[2] === "audio" && (method === "GET" || method === "HEAD")) {
          return withShareHeaders(await audioResponse(share, token, path[3], request));
        }
        return withShareHeaders(json({ error: "non trovato" }, { status: 404 }));
      }

      // --- l'accesso: un ID token di Google diventa una sessione ---
      if (url.pathname === "/v1/auth/google" && method === "POST") {
        const body = (await looseJson(request)) as { idToken?: string; deviceId?: string; deviceName?: string };
        if (typeof body.idToken !== "string" || !body.idToken) return json({ error: "idToken mancante" }, { status: 400 });
        return json(await loginWithGoogle(env, body.idToken, body.deviceId ?? "", body.deviceName ?? ""));
      }
      if (url.pathname === "/v1/auth/logout" && method === "POST") {
        const closed = await logout(env, bearerOf(request));
        return closed ? json({ loggedOut: true }) : json({ error: "nessuna sessione da chiudere" }, { status: 401 });
      }

      // --- gli ospiti del computer: il companion chiede e riporta, senza token del proprietario ---
      if (url.pathname === "/v1/guests/verify" && method === "POST") {
        const body = (await looseJson(request)) as { token?: string; owner?: string };
        const guest = await verifyGuest(env, typeof body.token === "string" ? body.token : "", typeof body.owner === "string" ? body.owner.trim() : "");
        return guest ? json({ ok: true, name: guest.name }) : json({ error: "ospite non riconosciuto" }, { status: 401 });
      }
      if (url.pathname === "/v1/guests/usage" && method === "POST") {
        const body = (await looseJson(request)) as { token?: string; seconds?: number };
        const counted = await reportUsage(env, typeof body.token === "string" ? body.token : "", Number(body.seconds ?? 0));
        return counted ? json({ ok: true }) : json({ error: "ospite non riconosciuto" }, { status: 401 });
      }
      if (path[0] === "v1" && path[1] === "guests") {
        const ownerId = await ownerOf(request, env);
        if (path.length === 2 && method === "POST") return json(await createGuest(env, ownerId, (await looseJson(request)) as { name?: string }));
        if (path.length === 2 && method === "GET") return json({ guests: await listGuests(env, ownerId) });
        if (path.length === 3 && method === "DELETE") {
          const revoked = await revokeGuest(env, ownerId, path[2]);
          return revoked ? json({ revoked: true }) : json({ error: "ospite inesistente" }, { status: 404 });
        }
      }

      // --- il biglietto per il PC: lo chiede l'app con la sessione, lo verifica il companion senza ---
      if (url.pathname === "/v1/computer/ticket" && method === "POST") {
        const ownerId = await ownerOf(request, env);
        const ticket = await issueTicket(env, ownerId);
        return ticket ? json(ticket, { headers: { "cache-control": "no-store" } }) : json({ error: "COMPUTER_KEY mancante" }, { status: 503 });
      }
      if (url.pathname === "/v1/computer/verify" && method === "POST") {
        const body = await looseJson(request);
        const verdict = await verifyTicket(env, typeof body.ticket === "string" ? body.ticket.trim() : "", typeof body.owner === "string" ? body.owner.trim() : "");
        if (verdict === "no-key") return json({ error: "COMPUTER_KEY mancante" }, { status: 503 });
        return verdict ? json({ ok: true, ...verdict }) : json({ error: "biglietto non valido" }, { status: 401 });
      }

      // --- il computer di casa, che segue l'account ---
      if (url.pathname === "/v1/account/computer") {
        const ownerId = await ownerOf(request, env);
        if (method === "GET") {
          const computer = await getComputer(env, ownerId);
          return computer ? json(computer, { headers: { "cache-control": "no-store" } }) : json({ error: "nessun computer" }, { status: 404 });
        }
        if (method === "PUT") {
          const body = (await request.json().catch(() => null)) as PutComputerBody | null;
          if (!body || typeof body !== "object") return json({ error: "corpo mancante" }, { status: 400 });
          return json(await putComputer(env, ownerId, body), { headers: { "cache-control": "no-store" } });
        }
        if (method === "DELETE") {
          const removed = await deleteComputer(env, ownerId);
          return removed ? json({ removed: true }) : json({ error: "nessun computer" }, { status: 404 });
        }
      }

      // --- l'indice ---
      if (url.pathname === "/v1/sync/push" && method === "POST") {
        const ownerId = await ownerOf(request, env);
        const body = (await readJson<PushRequest>(request));
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
        // Solo il riallineamento completo lo chiede: vedi `pull`.
        const includeOwn = url.searchParams.get("includeOwn") === "1";
        return json(await pull(env, ownerId, deviceId, since, limit, includeOwn));
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

        if (path.length === 2 && method === "POST") return json(await createShare(share, ownerId, await readJson<{ noteId?: string; title?: string }>(request), origin));
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
            const body = (await looseJson(request)) as { mime?: string };
            return json(await beginMultipart(share, ownerId, shareId, partId, body.mime ?? ""));
          }
          if (path.length === 8 && path[5] === "multipart" && method === "PUT") {
            return json(await putMultipartPart(share, ownerId, shareId, partId, path[6], Number(path[7]), request));
          }
          if (path.length === 8 && path[5] === "multipart" && path[7] === "complete" && method === "POST") {
            const body = await readJson<{ parts: { partNumber: number; etag: string }[] }>(request);
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
