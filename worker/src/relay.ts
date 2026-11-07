/** Same-account file transport. Independent of sync/archive/public shares; never durable storage. */
import type { Env } from "./auth";

export interface RelayEnv extends Env { AUDIO: R2Bucket }
type Clock = () => number;
export const RELAY_LIMITS = Object.freeze({
  ttlMs: 600_000,
  chunkBytes: 8 * 1024 * 1024,
  maxFileBytes: 256 * 1024 * 1024,
  maxOwnerBytes: 512 * 1024 * 1024,
  maxOwnerTransfers: 8,
  beginFreshnessMs: 180_000,
  maxJsonBytes: 4096,
});
export const RELAY_PREFIX = "__relay/v1/";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const SHA = /^[0-9a-f]{64}$/;
const ID = /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$/;
const MIME = /^[a-z0-9][a-z0-9!#$&^_.+-]*\/[a-z0-9][a-z0-9!#$&^_.+-]*$/;

interface Metadata {
  requestId: string;
  requestedAt: number;
  kind: "audio" | "source";
  entityId: string;
  sha256: string;
  bytes: number;
  mime: string;
  originalName: string;
  deviceId: string;
  deviceName: string;
}
interface Transfer extends Metadata {
  ownerId: string;
  transferId: string;
  createdAt: number;
  expiresAt: number;
  state: "uploading" | "ready" | "deleting";
  cleanupAttempts: number;
}
interface Chunk { chunkIndex: number; bytes: number; sha256: string; uploaded: number }

export class RelayError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message); }
}
function fail(status: number, code: string, message: string): never { throw new RelayError(status, code, message); }
export function relayJson(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), { status, headers: {
    "content-type": "application/json; charset=utf-8", "cache-control": "private, no-store",
    "x-content-type-options": "nosniff",
  } });
}
export function relayErrorResponse(error: RelayError): Response {
  return relayJson({ error: error.message, code: error.code }, error.status);
}

/** Does not trust Content-Length, and never materializes more than the operation's fixed bound. */
async function boundedBody(request: Request, max: number): Promise<Uint8Array> {
  const length = request.headers.get("content-length");
  if (length !== null && (!/^\d+$/.test(length) || Number(length) > max)) fail(413, "too_large", "Corpo troppo grande");
  const bytes = new Uint8Array(max);
  if (!request.body) return bytes.subarray(0, 0);
  const reader = request.body.getReader();
  let count = 0;
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      if (count + value.byteLength > max) {
        await reader.cancel();
        fail(413, "too_large", "Corpo troppo grande");
      }
      bytes.set(value, count);
      count += value.byteLength;
    }
  } finally { reader.releaseLock(); }
  return bytes.subarray(0, count);
}
async function metadata(request: Request): Promise<Metadata> {
  let b: Record<string, unknown>;
  try {
    const parsed: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(await boundedBody(request, RELAY_LIMITS.maxJsonBytes)));
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error();
    b = parsed as Record<string, unknown>;
  } catch (error) {
    if (error instanceof RelayError) throw error;
    fail(400, "invalid_metadata", "JSON non valido");
  }
  for (const key of ["requestId", "kind", "entityId", "sha256", "mime", "originalName", "deviceId", "deviceName"]) {
    if (typeof b[key] !== "string") fail(400, "invalid_metadata", `Campo non valido: ${key}`);
  }
  const m = b as unknown as Metadata;
  if (!UUID.test(m.requestId) || !ID.test(m.entityId) || !ID.test(m.deviceId) || !SHA.test(m.sha256) ||
    !["audio", "source"].includes(m.kind) || m.mime.length > 128 || !MIME.test(m.mime) ||
    !m.originalName.trim() || m.originalName.length > 255 || /^[.]{1,2}$/.test(m.originalName) ||
    /[\\/\x00-\x1f\x7f:]/.test(m.originalName) || m.deviceName.length > 128 || /[\x00-\x1f\x7f]/.test(m.deviceName) ||
    !Number.isSafeInteger(m.requestedAt) || m.requestedAt < 0 || !Number.isSafeInteger(m.bytes) || m.bytes < 1) {
    fail(400, "invalid_metadata", "Metadati non validi");
  }
  if (m.bytes > RELAY_LIMITS.maxFileBytes) fail(413, "file_too_large", "File troppo grande");
  // Canonical fields only: identity comparisons must not depend on JSON field order/unknown fields.
  return { requestId: m.requestId, requestedAt: m.requestedAt, kind: m.kind, entityId: m.entityId,
    sha256: m.sha256, bytes: m.bytes, mime: m.mime, originalName: m.originalName,
    deviceId: m.deviceId, deviceName: m.deviceName };
}
function sameMetadata(row: Transfer, m: Metadata): boolean {
  return Object.keys(m).every(k => row[k as keyof Metadata] === m[k as keyof Metadata]);
}
function prefix(row: Transfer): string { return `${RELAY_PREFIX}${row.expiresAt}/${row.transferId}/`; }
function key(row: Transfer, chunk: Chunk): string { return `${prefix(row)}${chunk.chunkIndex}-${chunk.sha256}`; }
function countChunks(row: Transfer): number { return Math.ceil(row.bytes / RELAY_LIMITS.chunkBytes); }
function chunkBytes(row: Transfer, n: number): number { return Math.min(RELAY_LIMITS.chunkBytes, row.bytes - n * RELAY_LIMITS.chunkBytes); }
function ensureLive(row: Transfer, clock: Clock): void {
  if (clock() >= row.expiresAt) fail(410, "expired", "Trasferimento scaduto: invialo di nuovo");
  if (row.state === "deleting") fail(410, "deleted", "Trasferimento eliminato");
}
async function transfer(env: RelayEnv, ownerId: string, id: string, clock: Clock): Promise<Transfer> {
  if (!UUID.test(id)) fail(400, "invalid_id", "Identificatore non valido");
  const row = await env.DB.prepare("SELECT * FROM relay_transfers WHERE ownerId = ? AND transferId = ?").bind(ownerId, id).first<Transfer>();
  if (!row) fail(404, "not_found", "Trasferimento non trovato");
  ensureLive(row, clock);
  return row;
}
async function chunks(env: RelayEnv, row: Transfer): Promise<Chunk[]> {
  const result = await env.DB.prepare("SELECT chunkIndex, bytes, sha256, uploaded FROM relay_chunks WHERE transferId = ? ORDER BY chunkIndex").bind(row.transferId).all<Chunk>();
  return result.results;
}
function description(row: Transfer, clock: Clock, parts?: Chunk[]) {
  return { transferId: row.transferId, requestId: row.requestId, kind: row.kind, entityId: row.entityId,
    sha256: row.sha256, bytes: row.bytes, mime: row.mime, originalName: row.originalName,
    deviceId: row.deviceId, deviceName: row.deviceName, state: row.state,
    createdAt: row.createdAt, expiresAt: row.expiresAt, serverTime: clock(),
    chunkBytes: RELAY_LIMITS.chunkBytes, chunkCount: countChunks(row),
    ...(parts === undefined ? {} : { chunks: parts.filter(c => c.uploaded === 1).map(c => ({ index: c.chunkIndex, bytes: c.bytes, sha256: c.sha256 })) }),
  };
}
async function begin(env: RelayEnv, ownerId: string, request: Request, clock: Clock): Promise<Response> {
  const m = await metadata(request);
  let row = await env.DB.prepare("SELECT * FROM relay_transfers WHERE ownerId = ? AND requestId = ?").bind(ownerId, m.requestId).first<Transfer>();
  if (row) {
    ensureLive(row, clock);
    if (!sameMetadata(row, m)) fail(409, "idempotency_conflict", "requestId gia' usato con altri metadati");
    return relayJson(description(row, clock, await chunks(env, row)));
  }
  const now = clock();
  // Use serverTime from discovery, not a client clock. Once metadata is collected, an unchanged
  // begin retry is too old to recreate expired payloads, even after cleanup removed the row.
  if (m.requestedAt > now || now - m.requestedAt > RELAY_LIMITS.beginFreshnessMs) fail(410, "stale_begin", "Richiedi serverTime e inizia un nuovo invio");
  const id = crypto.randomUUID();
  await env.DB.prepare(`INSERT INTO relay_transfers
    (ownerId, transferId, requestId, requestedAt, kind, entityId, sha256, bytes, mime, originalName,
      deviceId, deviceName, createdAt, expiresAt, state, cleanupAttempts, nextCleanupAt)
    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'uploading', 0, 0
    WHERE (SELECT COUNT(*) FROM relay_transfers WHERE ownerId = ?) < ?
      AND COALESCE((SELECT SUM(bytes) FROM relay_transfers WHERE ownerId = ?), 0) + ? <= ?
    ON CONFLICT(ownerId, requestId) DO NOTHING`).bind(
      ownerId, id, m.requestId, m.requestedAt, m.kind, m.entityId, m.sha256, m.bytes, m.mime, m.originalName,
      m.deviceId, m.deviceName, now, now + RELAY_LIMITS.ttlMs,
      ownerId, RELAY_LIMITS.maxOwnerTransfers, ownerId, m.bytes, RELAY_LIMITS.maxOwnerBytes,
    ).run();
  row = await env.DB.prepare("SELECT * FROM relay_transfers WHERE ownerId = ? AND requestId = ?").bind(ownerId, m.requestId).first<Transfer>();
  if (!row) fail(429, "owner_quota", "Quota trasferimenti esaurita: attendi la pulizia");
  ensureLive(row, clock);
  if (!sameMetadata(row, m)) fail(409, "idempotency_conflict", "requestId gia' usato con altri metadati");
  return relayJson(description(row, clock, await chunks(env, row)), row.transferId === id ? 201 : 200);
}
function indexOf(value: string, row: Transfer): number {
  if (!/^(0|[1-9][0-9]*)$/.test(value)) fail(400, "invalid_chunk", "Indice chunk non valido");
  const n = Number(value);
  if (!Number.isSafeInteger(n) || n >= countChunks(row)) fail(400, "invalid_chunk", "Indice chunk non valido");
  return n;
}
function hex(bytes: ArrayBuffer): string { return [...new Uint8Array(bytes)].map(b => b.toString(16).padStart(2, "0")).join(""); }
async function putChunk(env: RelayEnv, ownerId: string, row: Transfer, n: number, request: Request, clock: Clock): Promise<Response> {
  const sha256 = request.headers.get("x-chunk-sha256") ?? "";
  if (!SHA.test(sha256)) fail(400, "invalid_hash", "X-Chunk-SHA256 non valido");
  if (row.state !== "uploading") fail(409, "not_uploading", "Trasferimento gia' completato");
  const expected = chunkBytes(row, n);
  const body = await boundedBody(request, expected);
  if (body.byteLength !== expected) fail(400, "size_mismatch", "Lunghezza chunk diversa da quella attesa");
  if (hex(await crypto.subtle.digest("SHA-256", body)) !== sha256) fail(422, "hash_mismatch", "SHA-256 chunk diverso");
  ensureLive(row, clock);
  // Reserve the hash before R2. A racing different chunk can never overwrite accepted bytes.
  await env.DB.prepare(`INSERT INTO relay_chunks (transferId, chunkIndex, bytes, sha256, uploaded)
    SELECT ?, ?, ?, ?, 0 WHERE EXISTS (SELECT 1 FROM relay_transfers
      WHERE transferId = ? AND ownerId = ? AND state = 'uploading' AND expiresAt > ?)
    ON CONFLICT(transferId, chunkIndex) DO NOTHING`).bind(row.transferId, n, expected, sha256, row.transferId, ownerId, clock()).run();
  const part = await env.DB.prepare("SELECT * FROM relay_chunks WHERE transferId = ? AND chunkIndex = ?").bind(row.transferId, n).first<Chunk>();
  if (!part) { await transfer(env, ownerId, row.transferId, clock); fail(409, "not_uploading", "Trasferimento gia' completato"); }
  if (part.sha256 !== sha256) fail(409, "chunk_conflict", "Chunk gia' riservato con un altro hash");
  if (part.uploaded !== 1) {
    await env.AUDIO.put(key(row, part), body, { sha256, httpMetadata: { contentType: "application/octet-stream" } });
    const accepted = await env.DB.prepare(`UPDATE relay_chunks SET uploaded = 1 WHERE transferId = ? AND chunkIndex = ?
      AND EXISTS (SELECT 1 FROM relay_transfers WHERE transferId = ? AND ownerId = ? AND state = 'uploading' AND expiresAt > ?)`)
      .bind(row.transferId, n, row.transferId, ownerId, clock()).run();
    if (!accepted.meta.changes) {
      // An identical PUT may have acknowledged this immutable key and committed while our R2
      // write was pending. Never delete a shared key after losing the acknowledgement race.
      // Expired/cancelled late writes remain inaccessible and the expiry-prefix sweep collects
      // them even if cleanup already removed their D1 rows.
      const current = await transfer(env, ownerId, row.transferId, clock);
      const acknowledged = await env.DB.prepare("SELECT * FROM relay_chunks WHERE transferId = ? AND chunkIndex = ?")
        .bind(row.transferId, n).first<Chunk>();
      if (current.state !== "ready" || acknowledged?.uploaded !== 1 ||
        acknowledged.sha256 !== sha256 || acknowledged.bytes !== expected) {
        fail(409, "not_uploading", "Trasferimento gia' completato");
      }
    }
  }
  await transfer(env, ownerId, row.transferId, clock);
  return relayJson({ index: n, bytes: expected, sha256, expiresAt: row.expiresAt, serverTime: clock() });
}
async function commit(env: RelayEnv, ownerId: string, row: Transfer, clock: Clock): Promise<Response> {
  const parts = await chunks(env, row);
  if (row.state === "ready") { ensureLive(row, clock); return relayJson(description(row, clock, parts)); }
  if (parts.length !== countChunks(row) || parts.some((p, n) => p.chunkIndex !== n || p.uploaded !== 1 || p.bytes !== chunkBytes(row, n))) {
    fail(409, "incomplete", "Caricamento incompleto: controlla i chunk ricevuti");
  }
  const digest = new crypto.DigestStream("SHA-256");
  // pipeTo below suppresses an unhandled rejection on digest if an R2 read fails.
  void digest.digest.catch(() => {});
  let total = 0;
  try {
    for (const part of parts) {
      ensureLive(row, clock);
      const object = await env.AUDIO.get(key(row, part));
      if (!object || object.size !== part.bytes) {
        if (object) await object.body.cancel();
        fail(409, "incomplete", "Chunk mancante o non integro");
      }
      let seen = 0;
      const counter = new TransformStream<Uint8Array, Uint8Array>({ transform(data, controller) {
        ensureLive(row, clock);
        seen += data.byteLength;
        if (seen > part.bytes) fail(422, "size_mismatch", "Lunghezza file diversa");
        controller.enqueue(data);
      } });
      await object.body.pipeThrough(counter).pipeTo(digest, { preventClose: true });
      if (seen !== part.bytes) fail(422, "size_mismatch", "Lunghezza file diversa");
      total += seen;
    }
    await digest.close();
    if (total !== row.bytes || hex(await digest.digest) !== row.sha256) fail(422, "file_hash_mismatch", "SHA-256 file diverso: elimina e invia di nuovo");
  } catch (error) {
    await digest.abort().catch(() => {});
    throw error;
  }
  // Re-check expiry/deletion atomically after all I/O; never publish a partial/expired upload.
  await env.DB.prepare(`UPDATE relay_transfers SET state = 'ready'
    WHERE ownerId = ? AND transferId = ? AND state = 'uploading' AND expiresAt > ?`)
    .bind(ownerId, row.transferId, clock()).run();
  const current = await transfer(env, ownerId, row.transferId, clock);
  if (current.state !== "ready") fail(409, "not_ready", "Trasferimento non disponibile");
  return relayJson(description(current, clock, parts));
}
async function download(env: RelayEnv, ownerId: string, row: Transfer, n: number, clock: Clock): Promise<Response> {
  if (row.state !== "ready") fail(409, "not_ready", "Caricamento non completato");
  const part = await env.DB.prepare("SELECT * FROM relay_chunks WHERE transferId = ? AND chunkIndex = ? AND uploaded = 1").bind(row.transferId, n).first<Chunk>();
  if (!part) fail(409, "incomplete", "Chunk non disponibile");
  const object = await env.AUDIO.get(key(row, part));
  if (!object) fail(409, "incomplete", "Chunk non disponibile");
  if (object.size !== part.bytes) { await object.body.cancel(); fail(409, "incomplete", "Chunk non integro"); }
  try { await transfer(env, ownerId, row.transferId, clock); } catch (error) { await object.body.cancel(); throw error; }
  const body = object.body.pipeThrough(new TransformStream<Uint8Array, Uint8Array>({ transform(data, controller) {
    ensureLive(row, clock);
    controller.enqueue(data);
  } })).pipeThrough(new FixedLengthStream(part.bytes));
  return new Response(body, { headers: { "content-type": "application/octet-stream", "content-length": String(part.bytes),
    "x-chunk-sha256": part.sha256, "x-file-sha256": row.sha256, "x-expires-at": String(row.expiresAt),
    "cache-control": "private, no-store", "x-content-type-options": "nosniff" } });
}

/** clock is an internal dependency for isolated tests, never populated from HTTP/env bindings. */
export async function relayRequest(request: Request, env: RelayEnv, ownerId: string, path: string[], clock: Clock = Date.now): Promise<Response> {
  const url = new URL(request.url);
  if (path.length === 2) {
    if (request.method === "POST") return begin(env, ownerId, request, clock);
    if (request.method === "GET") {
      const sha = url.searchParams.get("sha256"), kind = url.searchParams.get("kind");
      if ((sha !== null && !SHA.test(sha)) || (kind !== null && !["audio", "source"].includes(kind))) fail(400, "invalid_filter", "Filtro non valido");
      const now = clock();
      const result = await env.DB.prepare(`SELECT * FROM relay_transfers WHERE ownerId = ? AND expiresAt > ? AND state != 'deleting'
        AND (? IS NULL OR sha256 = ?) AND (? IS NULL OR kind = ?) ORDER BY createdAt DESC, transferId LIMIT ?`)
        .bind(ownerId, now, sha, sha, kind, kind, RELAY_LIMITS.maxOwnerTransfers).all<Transfer>();
      const quota = await env.DB.prepare("SELECT COUNT(*) AS transfers, COALESCE(SUM(bytes), 0) AS bytes FROM relay_transfers WHERE ownerId = ?").bind(ownerId).first();
      return relayJson({ serverTime: clock(), limits: RELAY_LIMITS, reserved: quota,
        transfers: result.results.filter(r => r.expiresAt > clock()).map(r => description(r, clock)) });
    }
  }
  if (path.length >= 3) {
    // DELETE is idempotent for absent/expired/same-owner rows; another owner learns nothing.
    if (path.length === 3 && request.method === "DELETE") {
      if (!UUID.test(path[2])) fail(400, "invalid_id", "Identificatore non valido");
      await env.DB.prepare("UPDATE relay_transfers SET state = 'deleting', nextCleanupAt = 0 WHERE ownerId = ? AND transferId = ?").bind(ownerId, path[2]).run();
      return relayJson({ deleted: true }, 202);
    }
    const row = await transfer(env, ownerId, path[2], clock);
    if (path.length === 3 && request.method === "GET") {
      const parts = await chunks(env, row);
      ensureLive(row, clock);
      return relayJson(description(row, clock, parts));
    }
    if (path.length === 4 && path[3] === "commit" && request.method === "POST") return commit(env, ownerId, row, clock);
    if (path.length === 5 && path[3] === "chunks") {
      const n = indexOf(path[4], row);
      if (request.method === "PUT") return putChunk(env, ownerId, row, n, request, clock);
      if (request.method === "GET") return download(env, ownerId, row, n, clock);
    }
  }
  return relayJson({ error: "Rotta non trovata", code: "not_found" }, 404);
}

/** Bounded work per cron; failed rows remain queued with backoff. No R2 multipart is created. */
export async function cleanupRelay(env: RelayEnv, clock: Clock = Date.now): Promise<{ removed: number; orphans: number; failed: number }> {
  const now = clock();
  const due = await env.DB.prepare(`SELECT * FROM relay_transfers WHERE (expiresAt <= ? OR state = 'deleting')
    AND nextCleanupAt <= ? ORDER BY nextCleanupAt, expiresAt LIMIT 4`).bind(now, now).all<Transfer>();
  let removed = 0, failed = 0, orphans = 0;
  for (const row of due.results) {
    try {
      // Normal maximum is 32 objects; a page keeps GC bounded even if the bucket contains junk.
      const page = await env.AUDIO.list({ prefix: prefix(row), limit: 128 });
      if (page.objects.length) await env.AUDIO.delete(page.objects.map(o => o.key));
      if (page.truncated) continue; // retain metadata until every byte was successfully deleted
      const result = await env.DB.batch([
        env.DB.prepare("DELETE FROM relay_chunks WHERE transferId = ?").bind(row.transferId),
        env.DB.prepare("DELETE FROM relay_transfers WHERE transferId = ? AND ownerId = ? AND expiresAt <= ?").bind(row.transferId, row.ownerId, clock()),
        // Keep a cancellation receipt until original expiry, so begin retries cannot recreate
        // the deleted transfer during their freshness window. No bytes survive this receipt.
        env.DB.prepare("UPDATE relay_transfers SET nextCleanupAt = expiresAt WHERE transferId = ? AND state = 'deleting'").bind(row.transferId),
      ]);
      if (result[1].meta.changes) removed++;
    } catch {
      failed++;
      await env.DB.prepare("UPDATE relay_transfers SET cleanupAttempts = cleanupAttempts + 1, nextCleanupAt = ? WHERE transferId = ?")
        .bind(now + Math.min(300_000, 60_000 * 2 ** Math.min(row.cleanupAttempts, 3)), row.transferId).run();
    }
  }
  // The expiry is part of the server-generated key. This independent sweep handles an isolate
  // killed between R2.put and D1 acknowledgement, even if the transfer row was already collected.
  // Fixed-width epoch milliseconds sort oldest first, so no persistent cursor can miss late puts.
  const page = await env.AUDIO.list({ prefix: RELAY_PREFIX, limit: 128 });
  const expired = page.objects.filter(o => {
    const rest = o.key.slice(RELAY_PREFIX.length);
    const expiry = rest.split("/")[0];
    return /^\d{13}$/.test(expiry) && Number(expiry) <= now;
  });
  if (expired.length) {
    await env.AUDIO.delete(expired.map(o => o.key));
    orphans = expired.length;
  }
  return { removed, orphans, failed };
}
