/**
 * Il protocollo: push, pull, e le regole che li tengono coerenti.
 *
 * **Push.** Il dispositivo manda un lotto di righe cambiate (`op` U o D, `updatedAt` del suo
 * orologio, `hash` del contenuto, il JSON della riga). Il server confronta con quello che ha: una
 * riga piu' vecchia di quella gia' presente viene **rifiutata** e il dispositivo se la riprende
 * con il pull — e' cosi' che un telefono rimasto spento una settimana non resuscita una nota
 * cancellata nel frattempo. Le righe accettate prendono un `seq` nuovo e crescente.
 *
 * Il lotto ha un id: se arriva due volte — confermato ma perso per strada — la seconda volta si
 * risponde come la prima, senza toccare niente.
 *
 * **Pull.** Le righe con `seq > since`, in ordine, a pagine, escluse quelle che il dispositivo
 * stesso ha scritto (le conosce gia': il push gli ha detto il loro `seq`). Una trascrizione
 * arriva insieme ai suoi segmenti, sempre interi: l'unita' «trascrizione + segmenti» non si spezza
 * fra due pagine, perche' un dispositivo con le parti ma senza i segmenti che riordina una parte
 * farebbe cancellare la trascrizione al suo `rebuildRaw`.
 *
 * **Tombstone.** Una cancellazione resta come riga con `op = 'D'` per novanta giorni; poi si pota,
 * e chi chiede un pull da prima della potatura riceve `rebaseline: true`: deve rifare tutto da
 * zero, cancellando localmente solo cio' che non ha modificato lui.
 *
 * I limiti di D1 disegnano le forme: due megabyte per riga, cento parametri per query, poche
 * decine di query per invocazione. Per questo i segmenti stanno a blocchi e ogni lotto si scrive
 * con un `batch` solo.
 */

import type { Env } from "./auth";

export const TABLES = ["folders", "notes", "sessions", "audio_parts", "transcripts", "sources", "export_presets"] as const;
export type Table = (typeof TABLES)[number];

/** L'ordine in cui le righe vanno applicate: un figlio non deve arrivare prima del padre. */
export const APPLY_ORDER: Record<Table, number> = {
  folders: 0,
  notes: 1,
  sources: 2,
  sessions: 3,
  audio_parts: 4,
  transcripts: 5,
  export_presets: 6,
};

export interface Change {
  tbl: Table;
  id: string;
  op: "U" | "D";
  updatedAt: number;
  hash?: string;
  /**
   * Solo in arrivo dal dispositivo: l'impronta dell'ultima versione che ha visto di questa riga,
   * vuota se non l'ha mai vista. E' quello che dice se sta scrivendo *sopra* la versione corrente
   * o sopra una che non c'e' piu'.
   */
  baseHash?: string;
  payload?: unknown;
  /** Solo per `transcripts`: i segmenti, interi. */
  segments?: unknown[];
}

export interface PushRequest {
  protocolVersion?: number;
  deviceId: string;
  deviceName?: string;
  batchId: string;
  changes: Change[];
}

export interface PushResult {
  seq: number;
  applied: number;
  /** Le righe di cui il dispositivo non aveva visto l'ultima versione: le riprende col pull, e il merge decide. */
  rejected: { tbl: string; id: string; reason: string }[];
}

const SEGMENTS_PER_CHUNK = 400;
const MAX_PULL = 200;

export class BadRequest extends Error {}

function assertChange(c: Change): void {
  if (!(TABLES as readonly string[]).includes(c.tbl)) throw new BadRequest(`tabella sconosciuta: ${c.tbl}`);
  if (typeof c.id !== "string" || !c.id) throw new BadRequest("id mancante");
  if (c.op !== "U" && c.op !== "D") throw new BadRequest(`op sconosciuta: ${c.op}`);
  if (typeof c.updatedAt !== "number") throw new BadRequest("updatedAt mancante");
  if (c.op === "U" && c.payload === undefined) throw new BadRequest(`payload mancante per ${c.tbl}/${c.id}`);
}

export async function push(env: Env, ownerId: string, body: PushRequest): Promise<PushResult> {
  if (!body.deviceId || !body.batchId || !Array.isArray(body.changes)) throw new BadRequest("lotto malformato");
  body.changes.forEach(assertChange);
  const now = Date.now();

  // Lo stesso lotto, una seconda volta: la risposta di allora.
  const seen = await env.DB.prepare("SELECT result FROM batches WHERE ownerId = ? AND batchId = ?").bind(ownerId, body.batchId).first<{ result: string }>();
  if (seen) return JSON.parse(seen.result) as PushResult;

  await env.DB.prepare("INSERT OR IGNORE INTO owners (ownerId, seq, prunedSeq) VALUES (?, 0, 0)").bind(ownerId).run();
  const owner = await env.DB.prepare("SELECT seq FROM owners WHERE ownerId = ?").bind(ownerId).first<{ seq: number }>();
  let seq = owner?.seq ?? 0;

  // Quello che c'e' gia' delle righe in arrivo, in una query sola per tabella.
  const existing = new Map<string, { updatedAt: number; hash: string; op: string }>();
  for (const tbl of TABLES) {
    const ids = body.changes.filter((c) => c.tbl === tbl).map((c) => c.id);
    for (let i = 0; i < ids.length; i += 90) {
      const slice = ids.slice(i, i + 90);
      const rows = await env.DB.prepare(
        `SELECT rowId, updatedAt, hash, op FROM state WHERE ownerId = ? AND tbl = ? AND rowId IN (${slice.map(() => "?").join(",")})`,
      ).bind(ownerId, tbl, ...slice).all<{ rowId: string; updatedAt: number; hash: string; op: string }>();
      for (const r of rows.results) existing.set(`${tbl}/${r.rowId}`, { updatedAt: r.updatedAt, hash: r.hash, op: r.op });
    }
  }

  const statements: D1PreparedStatement[] = [];
  const rejected: PushResult["rejected"] = [];
  let applied = 0;

  for (const change of body.changes) {
    const current = existing.get(`${change.tbl}/${change.id}`);
    if (current) {
      // Stesso contenuto: niente da fare, e niente `seq` sprecato.
      if ((change.hash ?? "") === current.hash && change.op === current.op) continue;
      // Il dispositivo non ha visto la versione che c'e' adesso: si rifiuta, se la riprende col
      // pull, e il merge decide cosa tenere. Non si guarda l'orologio, di proposito: un telefono
      // con l'ora indietro che ha letto l'ultima versione e ci ha scritto sopra ha una versione
      // successiva, non piu' vecchia — e rifiutarla lo lascerebbe fuori per sempre.
      if ((change.baseHash ?? "") !== current.hash) {
        rejected.push({ tbl: change.tbl, id: change.id, reason: "stale" });
        continue;
      }
    }
    seq += 1;
    applied += 1;
    statements.push(
      env.DB.prepare(
        `INSERT INTO state (ownerId, tbl, rowId, op, updatedAt, hash, deviceId, receivedAt, payload, seq) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(ownerId, tbl, rowId) DO UPDATE SET op = excluded.op, updatedAt = excluded.updatedAt, hash = excluded.hash,
           deviceId = excluded.deviceId, receivedAt = excluded.receivedAt, payload = excluded.payload, seq = excluded.seq`,
      ).bind(ownerId, change.tbl, change.id, change.op, change.updatedAt, change.hash ?? "", body.deviceId, now, change.op === "U" ? JSON.stringify(change.payload) : null, seq),
    );
    if (change.tbl === "transcripts") {
      statements.push(env.DB.prepare("DELETE FROM segment_chunks WHERE ownerId = ? AND transcriptId = ?").bind(ownerId, change.id));
      if (change.op === "U" && Array.isArray(change.segments)) {
        for (let i = 0, chunk = 0; i < change.segments.length; i += SEGMENTS_PER_CHUNK, chunk++) {
          statements.push(
            env.DB.prepare("INSERT INTO segment_chunks (ownerId, transcriptId, chunk, payload) VALUES (?, ?, ?, ?)").bind(
              ownerId, change.id, chunk, JSON.stringify(change.segments.slice(i, i + SEGMENTS_PER_CHUNK)),
            ),
          );
        }
      }
    }
  }

  const result: PushResult = { seq, applied, rejected };
  statements.push(env.DB.prepare("UPDATE owners SET seq = ? WHERE ownerId = ?").bind(seq, ownerId));
  statements.push(
    env.DB.prepare(
      "INSERT INTO devices (ownerId, deviceId, name, lastSeenAt) VALUES (?, ?, ?, ?) ON CONFLICT(ownerId, deviceId) DO UPDATE SET name = COALESCE(excluded.name, devices.name), lastSeenAt = excluded.lastSeenAt",
    ).bind(ownerId, body.deviceId, body.deviceName ?? null, now),
  );
  statements.push(env.DB.prepare("INSERT INTO batches (ownerId, batchId, seq, result, at) VALUES (?, ?, ?, ?, ?)").bind(ownerId, body.batchId, seq, JSON.stringify(result), now));
  // Vecchi lotti e vecchi tombstone se ne vanno con lo stesso giro. La potatura dei tombstone
  // alza `prunedSeq`: chi era rimasto indietro lo scopre al prossimo pull. Si guarda l'orologio
  // del server e non quello del dispositivo: un telefono con la data sbagliata non deve far
  // sparire una cancellazione prima che gli altri l'abbiano vista.
  const retentionMs = Number(env.TOMBSTONE_RETENTION_DAYS ?? "90") * 86_400_000;
  statements.push(env.DB.prepare("DELETE FROM batches WHERE ownerId = ? AND at < ?").bind(ownerId, now - 7 * 86_400_000));
  statements.push(
    env.DB.prepare("UPDATE owners SET prunedSeq = MAX(prunedSeq, COALESCE((SELECT MAX(seq) FROM state WHERE ownerId = ? AND op = 'D' AND receivedAt < ?), 0)) WHERE ownerId = ?").bind(ownerId, now - retentionMs, ownerId),
  );
  statements.push(env.DB.prepare("DELETE FROM state WHERE ownerId = ? AND op = 'D' AND receivedAt < ?").bind(ownerId, now - retentionMs));

  // Tutto in un lotto solo: o entra tutto, o niente, e conta come poche query.
  for (let i = 0; i < statements.length; i += 40) {
    await env.DB.batch(statements.slice(i, i + 40));
  }
  return result;
}

export interface PullChange extends Change {
  seq: number;
  deviceId: string;
}

export interface PullResult {
  changes: PullChange[];
  seq: number;
  more: boolean;
  /** Il `since` era prima della potatura dei tombstone: ricominciare da zero. */
  rebaseline?: boolean;
}

export async function pull(env: Env, ownerId: string, deviceId: string, since: number, limit: number): Promise<PullResult> {
  const owner = await env.DB.prepare("SELECT seq, prunedSeq FROM owners WHERE ownerId = ?").bind(ownerId).first<{ seq: number; prunedSeq: number }>();
  if (!owner) return { changes: [], seq: 0, more: false };
  if (since > 0 && since < owner.prunedSeq) return { changes: [], seq: owner.seq, more: false, rebaseline: true };

  const pageSize = Math.max(1, Math.min(limit || MAX_PULL, MAX_PULL));
  const rows = await env.DB.prepare(
    "SELECT tbl, rowId, op, updatedAt, hash, deviceId, payload, seq FROM state WHERE ownerId = ? AND seq > ? AND deviceId != ? ORDER BY seq LIMIT ?",
  ).bind(ownerId, since, deviceId, pageSize + 1).all<{ tbl: Table; rowId: string; op: "U" | "D"; updatedAt: number; hash: string; deviceId: string; payload: string | null; seq: number }>();

  const more = rows.results.length > pageSize;
  const page = rows.results.slice(0, pageSize);

  const transcriptIds = page.filter((r) => r.tbl === "transcripts" && r.op === "U").map((r) => r.rowId);
  const segmentsById = new Map<string, unknown[]>();
  for (let i = 0; i < transcriptIds.length; i += 90) {
    const slice = transcriptIds.slice(i, i + 90);
    const chunks = await env.DB.prepare(
      `SELECT transcriptId, chunk, payload FROM segment_chunks WHERE ownerId = ? AND transcriptId IN (${slice.map(() => "?").join(",")}) ORDER BY transcriptId, chunk`,
    ).bind(ownerId, ...slice).all<{ transcriptId: string; chunk: number; payload: string }>();
    for (const c of chunks.results) {
      const list = segmentsById.get(c.transcriptId) ?? [];
      list.push(...(JSON.parse(c.payload) as unknown[]));
      segmentsById.set(c.transcriptId, list);
    }
  }

  const changes: PullChange[] = page.map((r) => ({
    tbl: r.tbl,
    id: r.rowId,
    op: r.op,
    updatedAt: r.updatedAt,
    hash: r.hash,
    deviceId: r.deviceId,
    seq: r.seq,
    payload: r.payload ? JSON.parse(r.payload) : undefined,
    segments: r.tbl === "transcripts" && r.op === "U" ? segmentsById.get(r.rowId) ?? [] : undefined,
  }));

  // La pagina si ferma all'ultimo `seq` consegnato: il prossimo pull riparte da li'. Se non c'e'
  // altro, si dice il `seq` del proprietario, cosi' il client sa di essere in pari.
  const lastSeq = page.length ? page[page.length - 1].seq : owner.seq;
  return { changes, seq: more ? lastSeq : owner.seq, more };
}

export interface Status {
  ownerId: string;
  seq: number;
  rows: number;
  tombstones: number;
  devices: { deviceId: string; name: string | null; lastSeenAt: number }[];
}

export async function status(env: Env, ownerId: string): Promise<Status> {
  const owner = await env.DB.prepare("SELECT seq FROM owners WHERE ownerId = ?").bind(ownerId).first<{ seq: number }>();
  const counts = await env.DB.prepare("SELECT SUM(op = 'U') AS rows, SUM(op = 'D') AS tombstones FROM state WHERE ownerId = ?").bind(ownerId).first<{ rows: number | null; tombstones: number | null }>();
  const devices = await env.DB.prepare("SELECT deviceId, name, lastSeenAt FROM devices WHERE ownerId = ? ORDER BY lastSeenAt DESC").bind(ownerId).all<{ deviceId: string; name: string | null; lastSeenAt: number }>();
  return { ownerId, seq: owner?.seq ?? 0, rows: counts?.rows ?? 0, tombstones: counts?.tombstones ?? 0, devices: devices.results };
}
