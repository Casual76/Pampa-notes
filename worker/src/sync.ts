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
 * risponde come la prima, senza toccare niente. L'id si scrive **per ultimo**, dopo tutte le righe:
 * un lotto grande va in piu' `batch` di D1, ognuno confermato per conto suo, e un lotto morto a
 * meta' che avesse gia' l'id scritto verrebbe «riconosciuto» al secondo tentativo e non finirebbe
 * mai. Senza l'id, il secondo tentativo rifa' il giro: le righe gia' entrate hanno la stessa
 * impronta e si saltano, le altre entrano.
 *
 * **Due push insieme** (due dispositivi, o lo stesso che riprova mentre il primo e' ancora in
 * volo) non devono ne' darsi lo stesso `seq` ne' passare tutti e due il controllo della base. Il
 * `seq` si riserva dentro la stessa transazione che scrive le righe (vedi `writeGroup`), e ogni
 * riga si scrive solo se nel frattempo e' ancora la versione letta: chi perde la corsa se la vede
 * rifiutata come `stale`, e il merge del pull decide.
 *
 * **Pull.** Le righe con `seq > since`, in ordine, a pagine, escluse quelle che il dispositivo
 * stesso ha scritto (le conosce gia': il push gli ha detto il loro `seq`) — tranne che nel
 * riallineamento completo (`includeOwn`), in cui il dispositivo deve sapere tutto quello che
 * l'indice ha, suo compreso. Una trascrizione arriva insieme ai suoi segmenti, sempre interi:
 * l'unita' «trascrizione + segmenti» non si spezza fra due pagine, perche' un dispositivo con le
 * parti ma senza i segmenti che riordina una parte farebbe cancellare la trascrizione al suo
 * `rebuildRaw`. Una pagina e' al massimo duecento righe **e** circa quattro megabyte: una lezione
 * da due ore con le parole sono un megabyte di segmenti, e duecento cosi' in una risposta sola
 * finirebbero la memoria del telefono prima di arrivare.
 *
 * **Tombstone.** Una cancellazione resta come riga con `op = 'D'` per novanta giorni; poi si pota,
 * e chi chiede un pull da prima della potatura riceve `rebaseline: true`: deve rifare tutto da
 * zero, cancellando localmente solo cio' che non ha modificato lui.
 *
 * I limiti di D1 disegnano le forme: due megabyte per riga, cento parametri per query, poche
 * decine di query per invocazione. Per questo i segmenti stanno a blocchi, una riga troppo grande
 * si rifiuta da sola (`too_large`) invece di far fallire il lotto, e le scritture vanno in pochi
 * `batch`.
 */

import type { Env } from "./auth";

/**
 * Le tabelle che l'indice conosce. `transcription_runs` sono le statistiche delle trascrizioni
 * (quanto audio, in quanto tempo, su cosa): righe scritte una volta e mai piu' toccate, senza un
 * padre — una lezione cancellata non si porta via il fatto di essere stata trascritta.
 */
export const TABLES = ["folders", "notes", "sessions", "audio_parts", "transcripts", "sources", "export_presets", "transcription_runs"] as const;
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
  transcription_runs: 7,
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
  /**
   * Le righe che non sono entrate. `stale`: il dispositivo non aveva visto l'ultima versione, la
   * riprende col pull e il merge decide. `too_large`: la riga non sta in D1 e non ci stara' mai,
   * riprovarla non serve.
   */
  rejected: { tbl: string; id: string; reason: "stale" | "too_large" }[];
}

const SEGMENTS_PER_CHUNK = 400;
const MAX_PULL = 200;
/** Una riga di D1 vale due megabyte: sotto, con un margine per le altre colonne. */
const MAX_ROW_BYTES = 1_900_000;
/** Un blocco di segmenti si chiude a quattrocento segmenti o a un megabyte e mezzo, quello che arriva prima. */
const CHUNK_BYTES = 1_500_000;
/** Quanto testo per pagina di pull, segmenti compresi. Almeno una riga passa sempre, anche se da sola e' di piu'. */
const PULL_BYTES = 4_000_000;
/**
 * Un `batch` di D1 e' una transazione: piu' e' grande, piu' tiene fermo il database per tutti. Si
 * chiude a cinquanta statement o a quattro megabyte, ma un'unita' «riga + blocchi dei segmenti» non
 * si spezza mai fra due: e' l'unico modo per non lasciare una trascrizione senza segmenti.
 */
const WRITE_STATEMENTS = 50;
const WRITE_BYTES = 4_000_000;

export class BadRequest extends Error {}

const utf8 = new TextEncoder();
function bytesOf(text: string): number {
  return utf8.encode(text).length;
}

function assertChange(c: Change): void {
  if (!c || typeof c !== "object") throw new BadRequest("modifica malformata");
  if (!(TABLES as readonly string[]).includes(c.tbl)) throw new BadRequest(`tabella sconosciuta: ${c.tbl}`);
  if (typeof c.id !== "string" || !c.id) throw new BadRequest("id mancante");
  if (c.op !== "U" && c.op !== "D") throw new BadRequest(`op sconosciuta: ${c.op}`);
  if (typeof c.updatedAt !== "number") throw new BadRequest("updatedAt mancante");
  if (c.op === "U" && c.payload === undefined) throw new BadRequest(`payload mancante per ${c.tbl}/${c.id}`);
}

/** Una riga che ha passato i controlli e va scritta. */
interface Planned {
  change: Change;
  /** Il JSON della riga; `null` per un tombstone. */
  payload: string | null;
  /** I blocchi di segmenti, gia' in JSON. Vuoto per tutto quello che non e' una trascrizione con segmenti. */
  chunks: string[];
  /** Il `seq` della versione letta, `null` se la riga non c'era: si scrive solo se e' ancora quella. */
  expectedSeq: number | null;
  bytes: number;
}

export async function push(env: Env, ownerId: string, body: PushRequest): Promise<PushResult> {
  if (!body || typeof body !== "object" || typeof body.deviceId !== "string" || !body.deviceId || typeof body.batchId !== "string" || !body.batchId || !Array.isArray(body.changes)) {
    throw new BadRequest("lotto malformato");
  }
  body.changes.forEach(assertChange);
  const now = Date.now();

  // Lo stesso lotto, una seconda volta: la risposta di allora.
  const seen = await env.DB.prepare("SELECT result FROM batches WHERE ownerId = ? AND batchId = ?").bind(ownerId, body.batchId).first<{ result: string }>();
  if (seen) return JSON.parse(seen.result) as PushResult;

  await env.DB.prepare("INSERT OR IGNORE INTO owners (ownerId, seq, prunedSeq) VALUES (?, 0, 0)").bind(ownerId).run();

  const existing = await currentVersions(env, ownerId, body.changes);
  const kinds = await storedFolderKinds(env, ownerId, body.changes);
  // Le trascrizioni che il dispositivo manda uguali a quelle che ci sono, con dei segmenti: se qui
  // di segmenti non ce ne sono, il lotto di prima e' morto fra la riga e i blocchi (col codice di
  // prima, che li scriveva in `batch` diversi), e «uguale, si salta» li lascerebbe persi per sempre.
  const chunkCounts = await segmentChunkCounts(env, ownerId, body.changes.filter((c) => {
    const current = existing.get(`${c.tbl}/${c.id}`);
    return c.tbl === "transcripts" && c.op === "U" && Array.isArray(c.segments) && c.segments.length > 0 && current && current.hash === (c.hash ?? "") && current.op === "U";
  }).map((c) => c.id));

  const rejected: PushResult["rejected"] = [];
  const planned: Planned[] = [];

  for (const change of body.changes) {
    // Troppo grande per una riga di D1: non ci stara' mai, e un errore di D1 a meta' lotto
    // fermerebbe anche tutte le altre. Si rifiuta questa, e il resto passa.
    const payload = change.op === "U" ? JSON.stringify(keepFolderKind(change, kinds)) : null;
    const chunks = change.tbl === "transcripts" && change.op === "U" && Array.isArray(change.segments) ? chunkSegments(change.segments) : [];
    if ((payload !== null && bytesOf(payload) > MAX_ROW_BYTES) || chunks === null) {
      rejected.push({ tbl: change.tbl, id: change.id, reason: "too_large" });
      continue;
    }

    const current = existing.get(`${change.tbl}/${change.id}`);
    if (current) {
      if ((change.hash ?? "") === current.hash && change.op === current.op) {
        // Stesso contenuto: niente da fare, e niente `seq` sprecato — tranne la trascrizione rimasta
        // senza segmenti, che si riscrive (e prende un `seq` nuovo, cosi' chi l'ha tirata vuota la
        // riceve di nuovo intera).
        const repair = change.tbl === "transcripts" && chunks.length > 0 && (chunkCounts.get(change.id) ?? 0) === 0;
        if (!repair) continue;
      } else if ((change.baseHash ?? "") !== current.hash && current.deviceId !== body.deviceId) {
        // Il dispositivo non ha visto la versione che c'e' adesso: si rifiuta, se la riprende col
        // pull, e il merge decide cosa tenere. Non si guarda l'orologio, di proposito: un telefono
        // con l'ora indietro che ha letto l'ultima versione e ci ha scritto sopra ha una versione
        // successiva, non piu' vecchia — e rifiutarla lo lascerebbe fuori per sempre.
        //
        // Tranne quando la versione che c'e' adesso l'ha scritta lui: e' la risposta di un push
        // persa per strada (il server ha scritto, il telefono non l'ha saputo e dichiara ancora la
        // base di prima). Quello che manda adesso viene da quello che aveva scritto, e il pull non
        // gliela riporterebbe mai — non rimanda a nessuno le sue righe —: rifiutarla voleva dire
        // tenerla ferma nell'outbox per sempre. Chi ripristina un backup cambia id apposta
        // (`SyncRepository.afterRestore`), perche' li' «quello che aveva scritto» e' piu' nuovo.
        rejected.push({ tbl: change.tbl, id: change.id, reason: "stale" });
        continue;
      }
    }
    const bytes = (payload ? payload.length : 0) + chunks.reduce((sum, c) => sum + c.length, 0);
    planned.push({ change, payload, chunks, expectedSeq: current ? current.seq : null, bytes });
  }

  // Le scritture, a gruppi: ogni gruppo e' una transazione che riserva i suoi `seq` e scrive le
  // sue righe. Un gruppo confermato resta anche se il successivo fallisce: il lotto si ripete, e
  // le righe di questo si riconoscono uguali.
  let applied = 0;
  let seq = 0;
  const lost: Planned[] = [];
  for (const group of groups(planned)) {
    const outcome = await writeGroup(env, ownerId, body.deviceId, now, group);
    seq = outcome.seq;
    group.forEach((p, k) => (outcome.written[k] ? applied++ : lost.push(p)));
  }

  // Chi ha perso la corsa: qualcun altro ha scritto la stessa riga fra la lettura e la scrittura.
  // Se ha scritto proprio questo contenuto non c'e' niente da rifiutare; altrimenti e' `stale`, come
  // se fosse arrivato un attimo dopo.
  if (lost.length) {
    const after = await currentVersions(env, ownerId, lost.map((p) => p.change));
    for (const p of lost) {
      const current = after.get(`${p.change.tbl}/${p.change.id}`);
      if (current && current.hash === (p.change.hash ?? "") && current.op === p.change.op) continue;
      rejected.push({ tbl: p.change.tbl, id: p.change.id, reason: "stale" });
    }
  }

  if (!seq) {
    const owner = await env.DB.prepare("SELECT seq FROM owners WHERE ownerId = ?").bind(ownerId).first<{ seq: number }>();
    seq = owner?.seq ?? 0;
  }
  const result: PushResult = { seq, applied, rejected };

  // L'id del lotto per ultimo, e solo adesso che tutte le righe sono dentro. Due copie dello
  // stesso lotto in volo insieme scrivono tutte e due qui: vince la prima, e la seconda non fa
  // errore per questo.
  const retentionMs = Number(env.TOMBSTONE_RETENTION_DAYS ?? "90") * 86_400_000;
  await env.DB.batch([
    env.DB.prepare(
      "INSERT INTO devices (ownerId, deviceId, name, lastSeenAt) VALUES (?, ?, ?, ?) ON CONFLICT(ownerId, deviceId) DO UPDATE SET name = COALESCE(excluded.name, devices.name), lastSeenAt = excluded.lastSeenAt",
    ).bind(ownerId, body.deviceId, body.deviceName ?? null, now),
    env.DB.prepare("INSERT INTO batches (ownerId, batchId, seq, result, at) VALUES (?, ?, ?, ?, ?) ON CONFLICT(ownerId, batchId) DO NOTHING")
      .bind(ownerId, body.batchId, seq, JSON.stringify(result), now),
    // Vecchi lotti e vecchi tombstone se ne vanno con lo stesso giro. La potatura dei tombstone
    // alza `prunedSeq`: chi era rimasto indietro lo scopre al prossimo pull. Si guarda l'orologio
    // del server e non quello del dispositivo: un telefono con la data sbagliata non deve far
    // sparire una cancellazione prima che gli altri l'abbiano vista.
    env.DB.prepare("DELETE FROM batches WHERE ownerId = ? AND at < ?").bind(ownerId, now - 7 * 86_400_000),
    env.DB.prepare("UPDATE owners SET prunedSeq = MAX(prunedSeq, COALESCE((SELECT MAX(seq) FROM state WHERE ownerId = ? AND op = 'D' AND receivedAt < ?), 0)) WHERE ownerId = ?")
      .bind(ownerId, now - retentionMs, ownerId),
    env.DB.prepare("DELETE FROM state WHERE ownerId = ? AND op = 'D' AND receivedAt < ?").bind(ownerId, now - retentionMs),
  ]);
  return result;
}

type Current = { hash: string; op: string; seq: number; deviceId: string };

/** Quello che c'e' gia' delle righe in arrivo, in una query sola per tabella (a fette da novanta, per i cento parametri). */
async function currentVersions(env: Env, ownerId: string, changes: Change[]): Promise<Map<string, Current>> {
  const existing = new Map<string, Current>();
  for (const tbl of TABLES) {
    const ids = [...new Set(changes.filter((c) => c.tbl === tbl).map((c) => c.id))];
    for (let i = 0; i < ids.length; i += 90) {
      const slice = ids.slice(i, i + 90);
      const rows = await env.DB.prepare(
        `SELECT rowId, hash, op, seq, deviceId FROM state WHERE ownerId = ? AND tbl = ? AND rowId IN (${slice.map(() => "?").join(",")})`,
      ).bind(ownerId, tbl, ...slice).all<{ rowId: string } & Current>();
      for (const r of rows.results) existing.set(`${tbl}/${r.rowId}`, { hash: r.hash, op: r.op, seq: r.seq, deviceId: r.deviceId });
    }
  }
  return existing;
}

/**
 * Una cartella che arriva senza `kind` da un'app di prima delle Registrazioni tiene quello che
 * aveva: altrimenti rinominarla dal telefono non aggiornato la faceva tornare una materia su tutti i
 * dispositivi — nelle statistiche di scuola, nell'«Esporta tutto», e non piu' solo sul computer.
 * L'impronta resta quella che il dispositivo ha mandato: un'app nuova che la tira la ricalcola col
 * `kind` e la rimanda una volta, e da li' combaciano.
 */
function keepFolderKind(change: Change, kinds: Map<string, string>): unknown {
  const payload = change.payload as Record<string, unknown> | null | undefined;
  if (change.tbl !== "folders" || !payload || typeof payload !== "object" || "kind" in payload) return change.payload;
  const kind = kinds.get(change.id);
  return kind ? { ...payload, kind } : change.payload;
}

async function storedFolderKinds(env: Env, ownerId: string, changes: Change[]): Promise<Map<string, string>> {
  const kinds = new Map<string, string>();
  const ids = [...new Set(changes.filter((c) => {
    const p = c.payload as Record<string, unknown> | null | undefined;
    return c.tbl === "folders" && c.op === "U" && p && typeof p === "object" && !("kind" in p);
  }).map((c) => c.id))];
  for (let i = 0; i < ids.length; i += 90) {
    const slice = ids.slice(i, i + 90);
    const rows = await env.DB.prepare(
      `SELECT rowId, json_extract(payload, '$.kind') AS kind FROM state WHERE ownerId = ? AND tbl = 'folders' AND op = 'U' AND rowId IN (${slice.map(() => "?").join(",")})`,
    ).bind(ownerId, ...slice).all<{ rowId: string; kind: string | null }>();
    for (const r of rows.results) if (typeof r.kind === "string" && r.kind) kinds.set(r.rowId, r.kind);
  }
  return kinds;
}

async function segmentChunkCounts(env: Env, ownerId: string, transcriptIds: string[]): Promise<Map<string, number>> {
  const counts = new Map<string, number>();
  const ids = [...new Set(transcriptIds)];
  for (let i = 0; i < ids.length; i += 90) {
    const slice = ids.slice(i, i + 90);
    const rows = await env.DB.prepare(
      `SELECT transcriptId, COUNT(*) AS n FROM segment_chunks WHERE ownerId = ? AND transcriptId IN (${slice.map(() => "?").join(",")}) GROUP BY transcriptId`,
    ).bind(ownerId, ...slice).all<{ transcriptId: string; n: number }>();
    for (const r of rows.results) counts.set(r.transcriptId, r.n);
  }
  return counts;
}

/**
 * I segmenti in blocchi: quattrocento per blocco, o meno se sono grossi (una lezione allineata da
 * WhisperX ha le parole con i tempi dentro ogni segmento). `null` se un segmento da solo non sta in
 * una riga: la trascrizione non si puo' salvare intera, e salvarla a meta' sarebbe peggio.
 */
function chunkSegments(segments: unknown[]): string[] | null {
  const chunks: string[] = [];
  let current: string[] = [];
  let bytes = 2;
  for (const segment of segments) {
    const json = JSON.stringify(segment) ?? "null";
    const size = bytesOf(json) + 1;
    if (size + 2 > MAX_ROW_BYTES) return null;
    if (current.length && (current.length >= SEGMENTS_PER_CHUNK || bytes + size > CHUNK_BYTES)) {
      chunks.push(`[${current.join(",")}]`);
      current = [];
      bytes = 2;
    }
    current.push(json);
    bytes += size;
  }
  if (current.length) chunks.push(`[${current.join(",")}]`);
  return chunks;
}

function statementsOf(p: Planned): number {
  return 1 + (p.change.tbl === "transcripts" ? 1 + p.chunks.length : 0);
}

/** Le righe in gruppi da scrivere ognuno in un `batch`, senza mai separare una riga dai suoi blocchi. */
function groups(planned: Planned[]): Planned[][] {
  const out: Planned[][] = [];
  let group: Planned[] = [];
  let statements = 1;
  let bytes = 0;
  for (const p of planned) {
    const n = statementsOf(p);
    if (group.length && (statements + n > WRITE_STATEMENTS || bytes + p.bytes > WRITE_BYTES)) {
      out.push(group);
      group = [];
      statements = 1;
      bytes = 0;
    }
    group.push(p);
    statements += n;
    bytes += p.bytes;
  }
  if (group.length) out.push(group);
  return out;
}

/**
 * Un gruppo di righe in una transazione sola (un `batch` di D1 lo e').
 *
 * Il primo statement alza `owners.seq` di quante righe ci sono, e ogni riga prende il suo numero
 * da li' (`seq` del proprietario meno la sua distanza dalla fine). Riservarli prima, in una
 * richiesta a parte, non basterebbe: due push riservano 1–10 e 11–20, il secondo scrive per primo,
 * un pull legge fino a 20 e riparte da li' — e le righe 1–10, confermate un attimo dopo, non le
 * vede piu' nessuno. Dentro la transazione i numeri diventano visibili nell'ordine in cui si
 * confermano, che e' l'unico ordine che il pull puo' seguire.
 *
 * Ogni riga si scrive solo se e' ancora la versione letta (`state.seq` uguale a quello di allora, o
 * nessuna riga se non c'era): e' il controllo della base fatto dal database, non da una lettura
 * di qualche millisecondo prima. I blocchi dei segmenti si scrivono solo se la loro riga e' stata
 * scritta in questo gruppo. Un numero riservato per una riga che perde la corsa resta un buco
 * nella sequenza: il pull non ne ha bisogno di contigui.
 */
async function writeGroup(env: Env, ownerId: string, deviceId: string, now: number, group: Planned[]): Promise<{ seq: number; written: boolean[] }> {
  const statements: D1PreparedStatement[] = [
    env.DB.prepare("UPDATE owners SET seq = seq + ? WHERE ownerId = ? RETURNING seq").bind(group.length, ownerId),
  ];
  const upsertAt: number[] = [];
  const mine = "EXISTS (SELECT 1 FROM state WHERE ownerId = ? AND tbl = 'transcripts' AND rowId = ? AND seq = (SELECT seq FROM owners WHERE ownerId = ?) - ?)";

  group.forEach((p, k) => {
    const { change } = p;
    const offset = group.length - 1 - k;
    upsertAt.push(statements.length);
    statements.push(
      env.DB.prepare(
        `INSERT INTO state (ownerId, tbl, rowId, op, updatedAt, hash, deviceId, receivedAt, payload, seq)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, (SELECT seq FROM owners WHERE ownerId = ?) - ?)
         ON CONFLICT(ownerId, tbl, rowId) DO UPDATE SET op = excluded.op, updatedAt = excluded.updatedAt, hash = excluded.hash,
           deviceId = excluded.deviceId, receivedAt = excluded.receivedAt, payload = excluded.payload, seq = excluded.seq
         WHERE state.seq = ?`,
      ).bind(ownerId, change.tbl, change.id, change.op, change.updatedAt, change.hash ?? "", deviceId, now, p.payload, ownerId, offset, p.expectedSeq),
    );
    if (change.tbl === "transcripts") {
      statements.push(
        env.DB.prepare(`DELETE FROM segment_chunks WHERE ownerId = ? AND transcriptId = ? AND ${mine}`).bind(ownerId, change.id, ownerId, change.id, ownerId, offset),
      );
      p.chunks.forEach((chunk, i) => {
        statements.push(
          env.DB.prepare(`INSERT INTO segment_chunks (ownerId, transcriptId, chunk, payload) SELECT ?, ?, ?, ? WHERE ${mine}`)
            .bind(ownerId, change.id, i, chunk, ownerId, change.id, ownerId, offset),
        );
      });
    }
  });

  const results = await env.DB.batch(statements);
  const top = (results[0].results[0] as { seq: number } | undefined)?.seq;
  if (typeof top !== "number") throw new Error("proprietario sparito durante il push");
  return { seq: top, written: upsertAt.map((i) => (results[i].meta.changes ?? 0) > 0) };
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

/**
 * @param includeOwn anche le righe scritte da `deviceId`. Serve al riallineamento completo: chi
 *   riparte da zero confronta quello che ha con tutto l'indice, e cancella quello che l'indice non
 *   ha. Senza le sue righe, ogni cosa scritta da lui sembrerebbe sparita, e la cancellerebbe.
 */
export async function pull(env: Env, ownerId: string, deviceId: string, since: number, limit: number, includeOwn = false): Promise<PullResult> {
  const owner = await env.DB.prepare("SELECT seq, prunedSeq FROM owners WHERE ownerId = ?").bind(ownerId).first<{ seq: number; prunedSeq: number }>();
  if (!owner) return { changes: [], seq: 0, more: false };
  if (since > 0 && since < owner.prunedSeq) return { changes: [], seq: owner.seq, more: false, rebaseline: true };

  const pageSize = Math.max(1, Math.min(limit || MAX_PULL, MAX_PULL));
  const exclude = includeOwn ? null : deviceId;
  const notMine = exclude === null ? "" : " AND deviceId != ?";
  const mineArgs = exclude === null ? [] : [exclude];

  // Prima le misure, senza il testo: quante righe stanno nella pagina lo decide quanto pesano, e
  // leggere duecento righe da due megabyte per poi tenerne due sarebbe gia' il danno da evitare.
  const heads = await env.DB.prepare(
    `SELECT tbl, rowId, op, seq, COALESCE(length(payload), 0) AS bytes FROM state WHERE ownerId = ? AND seq > ?${notMine} ORDER BY seq LIMIT ?`,
  ).bind(ownerId, since, ...mineArgs, pageSize + 1).all<{ tbl: Table; rowId: string; op: "U" | "D"; seq: number; bytes: number }>();
  const segmentBytes = new Map<string, number>();
  const measured = heads.results.filter((r) => r.tbl === "transcripts" && r.op === "U").map((r) => r.rowId);
  for (let i = 0; i < measured.length; i += 90) {
    const slice = measured.slice(i, i + 90);
    const sizes = await env.DB.prepare(
      `SELECT transcriptId, SUM(length(payload)) AS bytes FROM segment_chunks WHERE ownerId = ? AND transcriptId IN (${slice.map(() => "?").join(",")}) GROUP BY transcriptId`,
    ).bind(ownerId, ...slice).all<{ transcriptId: string; bytes: number }>();
    for (const s of sizes.results) segmentBytes.set(s.transcriptId, s.bytes);
  }

  // Ci si ferma alla prima riga che sfora, non la si salta: le righe vanno consegnate in ordine di
  // `seq`, o la ripartenza da `lastSeq` ne perderebbe una. La prima entra sempre, anche da sola
  // oltre il budget, o una riga grossa fermerebbe il pull per sempre.
  let taken = 0;
  let used = 0;
  for (const head of heads.results) {
    if (taken >= pageSize) break;
    const size = head.bytes + (head.tbl === "transcripts" && head.op === "U" ? segmentBytes.get(head.rowId) ?? 0 : 0);
    if (taken > 0 && used + size > PULL_BYTES) break;
    used += size;
    taken++;
  }
  const more = taken < heads.results.length;
  const lastSeq = taken ? heads.results[taken - 1].seq : owner.seq;

  // Poi le righe intere, per intervallo di `seq`: una riga riscritta nel frattempo ha preso un
  // `seq` piu' alto ed esce dall'intervallo (arrivera' dopo); nessuna puo' entrarci, perche' i
  // numeri nuovi stanno tutti sopra quelli gia' confermati.
  const page = taken
    ? (await env.DB.prepare(
        `SELECT tbl, rowId, op, updatedAt, hash, deviceId, payload, seq FROM state WHERE ownerId = ? AND seq > ? AND seq <= ?${notMine} ORDER BY seq`,
      ).bind(ownerId, since, lastSeq, ...mineArgs).all<StateRow>()).results
    : [];
  if (more) page.push(...(await parentsAfter(env, ownerId, exclude, page, lastSeq)));

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
  // altro, si dice il `seq` del proprietario, cosi' il client sa di essere in pari. Ma `owner` e'
  // stato letto prima delle righe, e un push confermato nel mezzo puo' aver messo nella pagina
  // righe con un `seq` piu' alto: dire quello di prima gliele farebbe riscaricare al giro dopo
  // (e una riga riscaricata dopo una modifica fatta li' sembrava un conflitto).
  return { changes, seq: more ? lastSeq : Math.max(owner.seq, lastSeq), more };
}

type StateRow = { tbl: Table; rowId: string; op: "U" | "D"; updatedAt: number; hash: string; deviceId: string; payload: string | null; seq: number };

/** Dove sta il padre di una riga, per le tabelle che ne hanno uno. */
function parentOf(row: StateRow): { tbl: Table; id: string } | null {
  if (row.op !== "U" || !row.payload) return null;
  const payload = JSON.parse(row.payload) as Record<string, unknown>;
  const pick = (tbl: Table, value: unknown) => (typeof value === "string" && value ? { tbl, id: value } : null);
  switch (row.tbl) {
    case "folders": return pick("folders", payload.parentId);
    // Il client manda la nota dentro `note`, insieme ai tag; i test del protocollo la mandano piatta.
    case "notes": return pick("folders", (payload.note as Record<string, unknown> | undefined)?.folderId ?? payload.folderId);
    case "sessions":
    case "sources": return pick("notes", payload.noteId);
    case "audio_parts": return pick("sessions", payload.sessionId);
    case "transcripts": return pick("sessions", payload.sessionId);
    // `export_presets` e `transcription_runs` non hanno un padre: il `sessionId` di una corsa non e'
    // una chiave esterna, e la sessione puo' non esserci piu'.
    default: return null;
  }
}

/**
 * I padri che arriverebbero in una pagina dopo, messi in questa.
 *
 * Lo stato tiene una riga per elemento col `seq` della sua **ultima** modifica: una nota ritoccata
 * dopo che le si e' aggiunta una sessione ha un `seq` piu' alto della sessione, e se la pagina si
 * ferma in mezzo la sessione arriva senza la nota. Sul dispositivo la chiave esterna la rifiuta, e
 * un client che non sa aspettare il padre fallisce il pull sempre nello stesso punto — e siccome il
 * push viene dopo il pull, smette anche di mandare. Con i padri dentro la pagina (il client applica
 * padre prima di figlio) la pagina entra; i padri torneranno nella loro pagina, e riapplicarli non
 * cambia niente. Si risale la catena: parte → sessione → nota → cartella.
 */
async function parentsAfter(env: Env, ownerId: string, exclude: string | null, page: StateRow[], lastSeq: number): Promise<StateRow[]> {
  const notMine = exclude === null ? "" : " AND deviceId != ?";
  const mineArgs = exclude === null ? [] : [exclude];
  const have = new Set(page.map((r) => `${r.tbl}/${r.rowId}`));
  const extra: StateRow[] = [];
  let frontier = page;
  for (let depth = 0; depth < 4 && frontier.length; depth++) {
    const wanted = new Map<string, { tbl: Table; id: string }>();
    for (const row of frontier) {
      const parent = parentOf(row);
      if (parent && !have.has(`${parent.tbl}/${parent.id}`)) wanted.set(`${parent.tbl}/${parent.id}`, parent);
    }
    const found: StateRow[] = [];
    const list = [...wanted.values()];
    for (let i = 0; i < list.length; i += 40) {
      const slice = list.slice(i, i + 40);
      const where = slice.map(() => "(tbl = ? AND rowId = ?)").join(" OR ");
      const result = await env.DB.prepare(
        `SELECT tbl, rowId, op, updatedAt, hash, deviceId, payload, seq FROM state WHERE ownerId = ? AND seq > ?${notMine} AND op = 'U' AND (${where})`,
      ).bind(ownerId, lastSeq, ...mineArgs, ...slice.flatMap((p) => [p.tbl, p.id])).all<StateRow>();
      found.push(...result.results);
    }
    for (const row of found) have.add(`${row.tbl}/${row.rowId}`);
    extra.push(...found);
    frontier = found;
  }
  return extra;
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
