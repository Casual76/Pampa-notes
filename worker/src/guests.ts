/**
 * Gli ospiti del computer di casa: un amico che trascrive col tuo PC.
 *
 * Il companion ha un token solo, quello del proprietario. Un amico non deve averlo: avrebbe
 * anche l'archivio dei file e il comando per scaricare il modello. Gli ospiti hanno un token
 * loro (`pg_…`), emesso qui e revocabile da qui, e il companion chiede a questo Worker se e'
 * buono prima di trascrivere. Quello che il companion sa del proprietario e' la sua email (o il
 * suo `ownerId`), scritta nel suo `config.json`: un token di ospite vale solo per il computer del
 * proprietario che l'ha creato, e un altro utente di questo stesso Worker non puo' fabbricarne
 * uno per il PC di qualcun altro.
 *
 * Il registro: a ogni trascrizione il companion dice «quanti secondi», e il pannello mostra chi
 * ha trascritto quanto e quando. Non cosa: il testo non passa di qui.
 */

import type { Env } from "./auth";
import { BadRequest } from "./sync";

export interface GuestInfo {
  guestId: string;
  name: string;
  createdAt: number;
  lastUsedAt: number | null;
  jobs: number;
  seconds: number;
  /** Solo alla creazione: dopo non si rivede, come una password. */
  token?: string;
}

interface GuestRow {
  ownerId: string;
  guestId: string;
  name: string;
  token: string;
  createdAt: number;
  revokedAt: number | null;
  lastUsedAt: number | null;
  jobs: number;
  seconds: number;
}

export const GUEST_PREFIX = "pg_";

function info(row: GuestRow, withToken = false): GuestInfo {
  const out: GuestInfo = { guestId: row.guestId, name: row.name, createdAt: row.createdAt, lastUsedAt: row.lastUsedAt, jobs: row.jobs, seconds: row.seconds };
  if (withToken) out.token = row.token;
  return out;
}

function newGuestToken(): string {
  const bytes = new Uint8Array(24);
  crypto.getRandomValues(bytes);
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return GUEST_PREFIX + btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function createGuest(env: Env, ownerId: string, body: { name?: string }): Promise<GuestInfo> {
  const name = typeof body.name === "string" ? body.name.trim().slice(0, 60) : "";
  if (!name) throw new BadRequest("nome mancante");
  const row: GuestRow = {
    ownerId,
    guestId: crypto.randomUUID(),
    name,
    token: newGuestToken(),
    createdAt: Date.now(),
    revokedAt: null,
    lastUsedAt: null,
    jobs: 0,
    seconds: 0,
  };
  await env.DB.prepare(
    "INSERT INTO guests (ownerId, guestId, name, token, createdAt, revokedAt, lastUsedAt, jobs, seconds) VALUES (?, ?, ?, ?, ?, NULL, NULL, 0, 0)",
  ).bind(row.ownerId, row.guestId, row.name, row.token, row.createdAt).run();
  return info(row, true);
}

export async function listGuests(env: Env, ownerId: string): Promise<GuestInfo[]> {
  const rows = await env.DB.prepare("SELECT * FROM guests WHERE ownerId = ? AND revokedAt IS NULL ORDER BY createdAt DESC").bind(ownerId).all<GuestRow>();
  return rows.results.map((r) => info(r));
}

export async function revokeGuest(env: Env, ownerId: string, guestId: string): Promise<boolean> {
  const result = await env.DB.prepare("UPDATE guests SET revokedAt = ? WHERE ownerId = ? AND guestId = ? AND revokedAt IS NULL").bind(Date.now(), ownerId, guestId).run();
  return (result.meta.changes ?? 0) > 0;
}

/**
 * Il companion chiede: questo token e' un ospite del proprietario che sono io?
 *
 * `owner` e' l'email dell'account Google (quella che il proprietario vede in Sincronizzazione) o
 * l'`ownerId` nudo. Con l'email si passa dalle sessioni aperte: e' l'unico posto in cui il
 * Worker la conosce.
 */
export async function verifyGuest(env: Env, token: string, owner: string): Promise<{ name: string } | null> {
  if (!token.startsWith(GUEST_PREFIX) || token.length < 20 || !owner) return null;
  const guest = await env.DB.prepare("SELECT * FROM guests WHERE token = ? AND revokedAt IS NULL").bind(token).first<GuestRow>();
  if (!guest) return null;
  if (!(await ownerMatches(env, guest.ownerId, owner))) return null;
  return { name: guest.name };
}

/**
 * `owner` (quello che il companion ha nel suo `config.json`) e' il proprietario `ownerId`? Si' se
 * e' proprio l'`ownerId`, oppure se e' un'email con una sessione viva di quell'`ownerId`: le
 * sessioni sono l'unico posto in cui il Worker conosce le email. Vale per gli ospiti e per i
 * biglietti del PC (`computer.ts`), con la stessa regola.
 */
export async function ownerMatches(env: Env, ownerId: string, owner: string): Promise<boolean> {
  if (!owner) return false;
  if (ownerId === owner) return true;
  const session = await env.DB.prepare("SELECT 1 AS ok FROM sessions WHERE ownerId = ? AND lower(email) = lower(?) AND revokedAt IS NULL LIMIT 1")
    .bind(ownerId, owner).first<{ ok: number }>();
  return session !== null;
}

/** Una trascrizione fatta: si contano i secondi. Il token e' la prova, come per la verifica. */
export async function reportUsage(env: Env, token: string, seconds: number): Promise<boolean> {
  if (!token.startsWith(GUEST_PREFIX)) return false;
  const add = Number.isFinite(seconds) && seconds > 0 ? Math.round(seconds) : 0;
  const result = await env.DB.prepare("UPDATE guests SET jobs = jobs + 1, seconds = seconds + ?, lastUsedAt = ? WHERE token = ? AND revokedAt IS NULL")
    .bind(add, Date.now(), token).run();
  return (result.meta.changes ?? 0) > 0;
}
