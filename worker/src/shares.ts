/**
 * Condividere una nota: un link che si apre e si ascolta.
 *
 * Il bundle ZIP serve a un assistente; a un compagno serve una pagina. Il testo e la trascrizione
 * sono gia' nell'indice (D1), quindi una condivisione non copia niente: e' una riga in `shares`
 * con un token lungo, e la pagina legge le righe della nota al momento in cui viene aperta. Solo
 * l'audio va caricato, in R2, e solo quello della nota condivisa: 60 MB l'ora, e togliere la
 * condivisione lo cancella.
 *
 * Il token e' la chiave: 24 byte casuali, non indovinabile, revocabile. Le chiavi in R2 stanno
 * sotto `<ownerId>/<shareId>/`, cosi' la revoca e' una cancellazione per prefisso.
 *
 * L'audio sale a blocchi (multipart di R2) quando e' grande: una richiesta a un Worker porta al
 * massimo 100 MB, e una lezione da due ore e' di piu'.
 */

import type { Env } from "./auth";
import { BadRequest } from "./sync";

export interface ShareEnv extends Env {
  AUDIO: R2Bucket;
}

export interface ShareInfo {
  shareId: string;
  noteId: string;
  title: string;
  url: string;
  createdAt: number;
  openedAt: number | null;
  opens: number;
  audioBytes: number;
}

interface ShareRow {
  ownerId: string;
  shareId: string;
  token: string;
  noteId: string;
  title: string;
  createdAt: number;
  revokedAt: number | null;
  openedAt: number | null;
  opens: number;
  audioBytes: number;
}

export class NotFound extends Error {}

function shareUrl(origin: string, token: string): string {
  return `${origin}/s/${token}`;
}

function info(row: ShareRow, origin: string): ShareInfo {
  return {
    shareId: row.shareId,
    noteId: row.noteId,
    title: row.title,
    url: shareUrl(origin, row.token),
    createdAt: row.createdAt,
    openedAt: row.openedAt,
    opens: row.opens,
    audioBytes: row.audioBytes,
  };
}

function newToken(): string {
  const bytes = new Uint8Array(24);
  crypto.getRandomValues(bytes);
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function audioKey(ownerId: string, shareId: string, partId: string): string {
  return `${ownerId}/${shareId}/${partId}`;
}

async function liveShare(env: ShareEnv, ownerId: string, shareId: string): Promise<ShareRow> {
  const row = await env.DB.prepare("SELECT * FROM shares WHERE ownerId = ? AND shareId = ? AND revokedAt IS NULL").bind(ownerId, shareId).first<ShareRow>();
  if (!row) throw new NotFound("condivisione inesistente");
  return row;
}

/**
 * La condivisione viva, e `partId` e' una registrazione della nota condivisa: una parte nell'indice
 * la cui sessione sta sotto quella nota. Senza, il caricamento accettava qualunque nome, e un token
 * del proprietario bastava a mettere in R2 — sotto un link pubblico — un file che con la nota non
 * c'entra. La pagina, del resto, mostra solo le parti che l'indice le dice.
 */
async function liveSharePart(env: ShareEnv, ownerId: string, shareId: string, partId: string): Promise<ShareRow> {
  const share = await liveShare(env, ownerId, shareId);
  const part = await env.DB.prepare(
    `SELECT 1 AS ok FROM state p JOIN state s ON s.ownerId = p.ownerId AND s.tbl = 'sessions' AND s.op = 'U' AND s.rowId = json_extract(p.payload, '$.sessionId')
     WHERE p.ownerId = ? AND p.tbl = 'audio_parts' AND p.rowId = ? AND p.op = 'U' AND json_extract(s.payload, '$.noteId') = ?`,
  ).bind(ownerId, partId, share.noteId).first<{ ok: number }>();
  if (!part) throw new NotFound("registrazione inesistente in questa nota");
  return share;
}

// ---------------------------------------------------------------------------------------------
// Il proprietario: crea, elenca, revoca, carica l'audio
// ---------------------------------------------------------------------------------------------

/** Crea la condivisione, o restituisce quella viva se la nota e' gia' condivisa: due tocchi non fanno due link. */
export async function createShare(env: ShareEnv, ownerId: string, body: { noteId?: string; title?: string }, origin: string): Promise<ShareInfo> {
  const noteId = typeof body.noteId === "string" ? body.noteId.trim() : "";
  if (!noteId) throw new BadRequest("noteId mancante");
  const note = await env.DB.prepare("SELECT payload FROM state WHERE ownerId = ? AND tbl = 'notes' AND rowId = ? AND op = 'U'").bind(ownerId, noteId).first<{ payload: string }>();
  if (!note) throw new BadRequest("la nota non e' nell'indice: sincronizza prima");

  const existing = await env.DB.prepare("SELECT * FROM shares WHERE ownerId = ? AND noteId = ? AND revokedAt IS NULL").bind(ownerId, noteId).first<ShareRow>();
  if (existing) return info(existing, origin);

  const title = (typeof body.title === "string" && body.title.trim()) || titleOf(note.payload) || "Nota";
  const row: ShareRow = {
    ownerId,
    shareId: crypto.randomUUID(),
    token: newToken(),
    noteId,
    title,
    createdAt: Date.now(),
    revokedAt: null,
    openedAt: null,
    opens: 0,
    audioBytes: 0,
  };
  await env.DB.prepare(
    "INSERT INTO shares (ownerId, shareId, token, noteId, title, createdAt, revokedAt, openedAt, opens, audioBytes) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, 0, 0)",
  ).bind(row.ownerId, row.shareId, row.token, row.noteId, row.title, row.createdAt).run();
  return info(row, origin);
}

function titleOf(notePayload: string): string {
  try {
    const parsed = JSON.parse(notePayload) as { note?: { title?: string } };
    return parsed.note?.title ?? "";
  } catch {
    return "";
  }
}

export async function listShares(env: ShareEnv, ownerId: string, origin: string): Promise<ShareInfo[]> {
  const rows = await env.DB.prepare("SELECT * FROM shares WHERE ownerId = ? AND revokedAt IS NULL ORDER BY createdAt DESC").bind(ownerId).all<ShareRow>();
  return rows.results.map((r) => info(r, origin));
}

/** Revoca: il link muore subito, e l'audio sparisce da R2. Torna falso se non c'era niente da revocare. */
export async function revokeShare(env: ShareEnv, ownerId: string, shareId: string): Promise<boolean> {
  const row = await env.DB.prepare("SELECT * FROM shares WHERE ownerId = ? AND shareId = ? AND revokedAt IS NULL").bind(ownerId, shareId).first<ShareRow>();
  if (!row) return false;
  await env.DB.prepare("UPDATE shares SET revokedAt = ? WHERE ownerId = ? AND shareId = ?").bind(Date.now(), ownerId, shareId).run();
  await deletePrefix(env, `${ownerId}/${shareId}/`);
  return true;
}

async function deletePrefix(env: ShareEnv, prefix: string): Promise<void> {
  let cursor: string | undefined;
  do {
    const page = await env.AUDIO.list({ prefix, cursor });
    if (page.objects.length) await env.AUDIO.delete(page.objects.map((o) => o.key));
    cursor = page.truncated ? page.cursor : undefined;
  } while (cursor);
}

export async function headAudio(env: ShareEnv, ownerId: string, shareId: string, partId: string): Promise<{ exists: boolean; bytes: number }> {
  await liveShare(env, ownerId, shareId);
  const head = await env.AUDIO.head(audioKey(ownerId, shareId, partId));
  return { exists: head !== null, bytes: head?.size ?? 0 };
}

/**
 * Il tipo con cui l'audio verra' servito a chi apre il link. Solo `audio/*`: il tipo lo sceglie chi
 * carica, e un `text/html` qui farebbe di un «audio» una pagina servita dall'origine del Worker,
 * con accesso a tutto quello che l'origine vede. Qualunque altra cosa diventa `audio/mp4`, che e'
 * quello che l'app registra.
 */
function audioMime(declared: string | null | undefined): string {
  const mime = (declared ?? "").split(";")[0].trim().toLowerCase();
  return /^audio\/[a-z0-9][a-z0-9.+-]*$/.test(mime) ? mime : "audio/mp4";
}

/** Un file intero in una richiesta: va bene fino a qualche decina di megabyte. */
export async function putAudio(env: ShareEnv, ownerId: string, shareId: string, partId: string, request: Request): Promise<{ bytes: number }> {
  await liveSharePart(env, ownerId, shareId, partId);
  const mime = audioMime(request.headers.get("content-type"));
  const key = audioKey(ownerId, shareId, partId);
  const before = await env.AUDIO.head(key);
  const object = await env.AUDIO.put(key, request.body, { httpMetadata: { contentType: mime } });
  await accountBytes(env, ownerId, shareId, (object?.size ?? 0) - (before?.size ?? 0));
  return { bytes: object?.size ?? 0 };
}

export async function beginMultipart(env: ShareEnv, ownerId: string, shareId: string, partId: string, mime: string): Promise<{ uploadId: string }> {
  await liveSharePart(env, ownerId, shareId, partId);
  const upload = await env.AUDIO.createMultipartUpload(audioKey(ownerId, shareId, partId), { httpMetadata: { contentType: audioMime(mime) } });
  return { uploadId: upload.uploadId };
}

export async function putMultipartPart(
  env: ShareEnv, ownerId: string, shareId: string, partId: string, uploadId: string, partNumber: number, request: Request,
): Promise<{ partNumber: number; etag: string }> {
  await liveShare(env, ownerId, shareId);
  if (!Number.isInteger(partNumber) || partNumber < 1) throw new BadRequest("partNumber non valido");
  const upload = env.AUDIO.resumeMultipartUpload(audioKey(ownerId, shareId, partId), uploadId);
  const part = await upload.uploadPart(partNumber, request.body ?? new Uint8Array(0));
  return { partNumber: part.partNumber, etag: part.etag };
}

export async function completeMultipart(
  env: ShareEnv, ownerId: string, shareId: string, partId: string, uploadId: string, parts: { partNumber: number; etag: string }[],
): Promise<{ bytes: number }> {
  await liveSharePart(env, ownerId, shareId, partId);
  if (!Array.isArray(parts) || !parts.length) throw new BadRequest("parti mancanti");
  const key = audioKey(ownerId, shareId, partId);
  const before = await env.AUDIO.head(key);
  const upload = env.AUDIO.resumeMultipartUpload(key, uploadId);
  const object = await upload.complete(parts);
  await accountBytes(env, ownerId, shareId, object.size - (before?.size ?? 0));
  return { bytes: object.size };
}

export async function abortMultipart(env: ShareEnv, ownerId: string, shareId: string, partId: string, uploadId: string): Promise<void> {
  await liveShare(env, ownerId, shareId);
  await env.AUDIO.resumeMultipartUpload(audioKey(ownerId, shareId, partId), uploadId).abort();
}

async function accountBytes(env: ShareEnv, ownerId: string, shareId: string, delta: number): Promise<void> {
  if (!delta) return;
  await env.DB.prepare("UPDATE shares SET audioBytes = MAX(0, audioBytes + ?) WHERE ownerId = ? AND shareId = ?").bind(delta, ownerId, shareId).run();
}

// ---------------------------------------------------------------------------------------------
// Chi apre il link: la pagina, i dati, l'audio
// ---------------------------------------------------------------------------------------------

async function shareByToken(env: ShareEnv, token: string): Promise<ShareRow | null> {
  if (!token || token.length > 64) return null;
  return env.DB.prepare("SELECT * FROM shares WHERE token = ? AND revokedAt IS NULL").bind(token).first<ShareRow>();
}

/** Chiamato quando la pagina si apre: il pannello dice «aperta l'ultima volta il…». */
export async function noteOpened(env: ShareEnv, token: string): Promise<boolean> {
  const share = await shareByToken(env, token);
  if (!share) return false;
  await env.DB.prepare("UPDATE shares SET opens = opens + 1, openedAt = ? WHERE ownerId = ? AND shareId = ?").bind(Date.now(), share.ownerId, share.shareId).run();
  return true;
}

export interface PageSegment {
  partId: string;
  sessionStartMs: number;
  sessionEndMs: number;
  text: string;
  /** `inizio,fine,testo` per riga, tempi relativi a `sessionStartMs`. */
  words: string | null;
  /** «Chi parla»: l'etichetta del computer («SPEAKER_00»), null senza voci separate. */
  speaker: string | null;
}

export interface PageSession {
  id: string;
  title: string;
  date: string;
  parts: { id: string; name: string; durationMs: number; available: boolean }[];
  /** La grezza, con i tempi: e' quella che si accende. */
  raw: { model: string; text: string; wordsEstimated: boolean; segments: PageSegment[] } | null;
  /** La ripulita mostrata nell'app, se ce n'e' una: solo testo, niente tempi. */
  refined: { model: string; text: string } | null;
}

export interface PageData {
  title: string;
  folder: string;
  body: string;
  tags: string[];
  updatedAt: number;
  sessions: PageSession[];
  sources: string[];
}

export async function pageData(env: ShareEnv, token: string): Promise<PageData | null> {
  const share = await shareByToken(env, token);
  if (!share) return null;
  const ownerId = share.ownerId;

  const noteRow = await env.DB.prepare("SELECT payload FROM state WHERE ownerId = ? AND tbl = 'notes' AND rowId = ? AND op = 'U'").bind(ownerId, share.noteId).first<{ payload: string }>();
  if (!noteRow) return null;
  const notePayload = JSON.parse(noteRow.payload) as { note: { title: string; body: string; folderId: string; updatedAt: number }; tags?: string[] };
  const note = notePayload.note;

  const folderRow = await env.DB.prepare("SELECT payload FROM state WHERE ownerId = ? AND tbl = 'folders' AND rowId = ? AND op = 'U'").bind(ownerId, note.folderId).first<{ payload: string }>();
  const folder = folderRow ? ((JSON.parse(folderRow.payload) as { name?: string }).name ?? "") : "";

  const sessionRows = await env.DB.prepare(
    "SELECT payload FROM state WHERE ownerId = ? AND tbl = 'sessions' AND op = 'U' AND json_extract(payload, '$.noteId') = ? ORDER BY json_extract(payload, '$.position')",
  ).bind(ownerId, share.noteId).all<{ payload: string }>();

  const sessions: PageSession[] = [];
  for (const row of sessionRows.results) {
    const session = JSON.parse(row.payload) as { id: string; title: string; date: string; activeTranscriptId?: string | null };
    const partRows = await env.DB.prepare(
      "SELECT payload FROM state WHERE ownerId = ? AND tbl = 'audio_parts' AND op = 'U' AND json_extract(payload, '$.sessionId') = ? ORDER BY json_extract(payload, '$.position')",
    ).bind(ownerId, session.id).all<{ payload: string }>();
    const parts = [];
    for (const p of partRows.results) {
      const part = JSON.parse(p.payload) as { id: string; originalName: string; durationMs: number };
      const head = await env.AUDIO.head(audioKey(ownerId, share.shareId, part.id));
      parts.push({ id: part.id, name: part.originalName, durationMs: part.durationMs, available: head !== null });
    }

    const transcriptRows = await env.DB.prepare(
      "SELECT rowId, payload FROM state WHERE ownerId = ? AND tbl = 'transcripts' AND op = 'U' AND json_extract(payload, '$.sessionId') = ?",
    ).bind(ownerId, session.id).all<{ rowId: string; payload: string }>();
    const transcripts = transcriptRows.results.map((t) => JSON.parse(t.payload) as { id: string; kind: string; model: string; text: string; createdAt: number });
    const raw = transcripts.filter((t) => t.kind === "RAW").sort((a, b) => b.createdAt - a.createdAt)[0] ?? null;
    const active = transcripts.find((t) => t.id === session.activeTranscriptId) ?? null;
    const refined = active && active.kind === "REFINED" ? active : null;

    let rawPage: PageSession["raw"] = null;
    if (raw) {
      const chunks = await env.DB.prepare("SELECT payload FROM segment_chunks WHERE ownerId = ? AND transcriptId = ? ORDER BY chunk").bind(ownerId, raw.id).all<{ payload: string }>();
      const segments: PageSegment[] = [];
      let estimated = false;
      for (const c of chunks.results) {
        for (const s of JSON.parse(c.payload) as { partId: string; partStartMs: number; sessionStartMs: number; sessionEndMs: number; text: string; wordsJson?: string | null; wordsEstimated?: boolean; speaker?: string | null }[]) {
          if (s.wordsEstimated) estimated = true;
          segments.push({ partId: s.partId, sessionStartMs: s.sessionStartMs, sessionEndMs: s.sessionEndMs, text: s.text, words: s.wordsJson ?? null, speaker: s.speaker ?? null });
        }
      }
      segments.sort((a, b) => a.sessionStartMs - b.sessionStartMs);
      rawPage = { model: raw.model, text: raw.text, wordsEstimated: estimated, segments };
    }

    sessions.push({
      id: session.id,
      title: session.title,
      date: session.date,
      parts,
      raw: rawPage,
      refined: refined ? { model: refined.model, text: refined.text } : null,
    });
  }

  const sourceRows = await env.DB.prepare(
    "SELECT payload FROM state WHERE ownerId = ? AND tbl = 'sources' AND op = 'U' AND json_extract(payload, '$.noteId') = ?",
  ).bind(ownerId, share.noteId).all<{ payload: string }>();

  return {
    title: note.title,
    folder,
    body: note.body ?? "",
    tags: notePayload.tags ?? [],
    updatedAt: note.updatedAt,
    sessions,
    sources: sourceRows.results.map((s) => (JSON.parse(s.payload) as { originalName: string }).originalName),
  };
}

/** L'audio, con `Range`: il lettore salta al minuto quaranta senza scaricare i primi trentanove. */
export async function audioResponse(env: ShareEnv, token: string, partId: string, request: Request): Promise<Response> {
  const share = await shareByToken(env, token);
  if (!share) return new Response("non trovato", { status: 404 });
  const key = audioKey(share.ownerId, share.shareId, partId);
  const head = await env.AUDIO.head(key);
  if (!head) return new Response("audio non disponibile", { status: 404 });

  const size = head.size;
  // Anche quello che e' gia' in R2 passa dal filtro: caricato prima che ci fosse, puo' avere qualunque tipo.
  const type = audioMime(head.httpMetadata?.contentType);
  const range = parseRange(request.headers.get("range"), size);
  const base: Record<string, string> = { "content-type": type, "accept-ranges": "bytes", "cache-control": "private, max-age=3600" };

  if (request.method === "HEAD") return new Response(null, { status: 200, headers: { ...base, "content-length": String(size) } });

  if (range === "unsatisfiable") {
    return new Response(null, { status: 416, headers: { "content-range": `bytes */${size}` } });
  }
  if (!range) {
    const object = await env.AUDIO.get(key);
    if (!object) return new Response("audio non disponibile", { status: 404 });
    return new Response(object.body, { status: 200, headers: { ...base, "content-length": String(size) } });
  }
  const end = Math.min(range.end, size - 1);
  const object = await env.AUDIO.get(key, { range: { offset: range.start, length: end - range.start + 1 } });
  if (!object) return new Response("audio non disponibile", { status: 404 });
  return new Response(object.body, {
    status: 206,
    headers: { ...base, "content-length": String(end - range.start + 1), "content-range": `bytes ${range.start}-${end}/${size}` },
  });
}

/**
 * `null`: nessun `Range`, o uno che non si capisce (altra unita', piu' intervalli) — si ignora e si
 * manda il file intero, come chiede HTTP. `"unsatisfiable"`: un intervallo scritto bene ma che nel
 * file non c'e' (`5-3`, oltre la fine, gli ultimi zero byte): 416, non un 206 con una lunghezza
 * negativa o un corpo vuoto che il lettore scambierebbe per la fine dell'audio.
 */
function parseRange(header: string | null, size: number): { start: number; end: number } | "unsatisfiable" | null {
  if (!header) return null;
  const m = /^bytes=(\d*)-(\d*)$/.exec(header.trim());
  if (!m) return null;
  const [, a, b] = m;
  if (a === "" && b === "") return null;
  if (a === "") {
    // gli ultimi N byte
    const n = Number(b);
    if (!Number.isSafeInteger(n) || n <= 0 || size === 0) return "unsatisfiable";
    return { start: Math.max(0, size - n), end: size - 1 };
  }
  const start = Number(a);
  const end = b === "" ? size - 1 : Number(b);
  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start > end || start >= size) return "unsatisfiable";
  return { start, end };
}
