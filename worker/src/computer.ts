/**
 * Il computer di casa segue l'account.
 *
 * Chi entra con Google su un telefono nuovo ha gia' le note, le cartelle, le trascrizioni: senza
 * questo dovrebbe ancora ricollegare il PC a mano — riscrivere due indirizzi e un token, o tornare
 * davanti al QR — che e' esattamente la cosa che «entra con l'account» promette di risparmiare.
 * Qui sta una riga per proprietario: indirizzo di casa, indirizzo Tailscale, nome, modello e il
 * token del companion.
 *
 * Il token e' l'unico segreto che l'indice tiene, e non ci sta in chiaro: AES-GCM con una chiave
 * che vive nei segreti del Worker (`COMPUTER_KEY`, 32 byte in base64), non nel database. Chi
 * legge D1 — un backup, un dump, un errore di configurazione — vede indirizzi e un blob. Senza la
 * chiave il Worker tiene gli indirizzi e **non** il token, e lo dice (`tokenStored: false`):
 * meglio un telefono che chiede il token che un token scritto in chiaro.
 *
 * Vince l'ultimo che ha scritto, per orologio del dispositivo (`updatedAt`): un PUT piu' vecchio
 * di quello che c'e' non tocca niente, e la risposta porta la versione corrente, che il client
 * applica. Non ci sono due verita' da conservare come per il testo di una nota: il computer e'
 * uno, e l'ultimo indirizzo scritto e' quello buono.
 */

import type { Env } from "./auth";
import { BadRequest } from "./sync";

export interface ComputerView {
  url: string;
  remoteUrl: string;
  name: string;
  model: string;
  /** In chiaro, solo al proprietario. `null` se il server non ne ha uno. */
  token: string | null;
  updatedAt: number;
  deviceId: string;
  /** Se il token e' custodito qui. `false` anche quando manca la chiave per cifrarlo. */
  tokenStored: boolean;
}

export interface PutComputerBody {
  url?: string;
  remoteUrl?: string;
  name?: string;
  model?: string;
  /** Assente: resta quello che c'era. Stringa vuota: si cancella. */
  token?: string | null;
  updatedAt?: number;
  deviceId?: string;
}

export interface PutComputerResult {
  /** `true` se questa scrittura e' diventata la versione corrente. */
  accepted: boolean;
  /** Solo quando non lo e': qualcuno ha scritto dopo, e `computer` e' la sua versione. */
  stale?: boolean;
  computer: ComputerView;
}

interface ComputerRow {
  ownerId: string;
  url: string;
  remoteUrl: string;
  name: string;
  model: string;
  tokenCipher: string | null;
  updatedAt: number;
  deviceId: string;
}

export async function getComputer(env: Env, ownerId: string): Promise<ComputerView | null> {
  const row = await env.DB.prepare("SELECT * FROM computers WHERE ownerId = ?").bind(ownerId).first<ComputerRow>();
  return row ? view(env, row) : null;
}

export async function putComputer(env: Env, ownerId: string, body: PutComputerBody): Promise<PutComputerResult> {
  const updatedAt = Number(body.updatedAt);
  if (!Number.isFinite(updatedAt) || updatedAt <= 0) throw new BadRequest("updatedAt mancante");
  const url = text(body.url, 500);
  const remoteUrl = text(body.remoteUrl, 500);
  const name = text(body.name, 100);
  const model = text(body.model, 100);
  const deviceId = text(body.deviceId, 100);
  if (body.token !== undefined && body.token !== null && typeof body.token !== "string") throw new BadRequest("token non valido");

  const existing = await env.DB.prepare("SELECT * FROM computers WHERE ownerId = ?").bind(ownerId).first<ComputerRow>();
  // Uguale non basta per scrivere. Lo stesso PUT rimandato perche' la risposta si e' persa trova
  // la sua stessa versione: «non accettato, ecco la corrente» gli riconsegna quello che ha scritto,
  // e applicarlo non cambia niente.
  if (existing && updatedAt <= existing.updatedAt) {
    return { accepted: false, stale: true, computer: await view(env, existing) };
  }

  const key = await computerKey(env);
  let tokenCipher: string | null;
  if (body.token === undefined || body.token === null) {
    // Nessuna parola sul token: resta quello di prima. Anche se la chiave nel frattempo e' cambiata
    // e non si apre piu' — lo dira' `tokenStored` alla lettura.
    tokenCipher = existing?.tokenCipher ?? null;
  } else if (body.token.trim() === "") {
    tokenCipher = null;
  } else {
    tokenCipher = key ? await seal(key, body.token.trim()) : null;
  }

  // Un solo statement con la guardia dentro: due dispositivi che scrivono insieme non possono
  // passare tutti e due il controllo di sopra e sovrascriversi a vicenda.
  const result = await env.DB.prepare(
    `INSERT INTO computers (ownerId, url, remoteUrl, name, model, tokenCipher, updatedAt, deviceId)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT (ownerId) DO UPDATE SET
       url = excluded.url, remoteUrl = excluded.remoteUrl, name = excluded.name, model = excluded.model,
       tokenCipher = excluded.tokenCipher, updatedAt = excluded.updatedAt, deviceId = excluded.deviceId
     WHERE excluded.updatedAt > computers.updatedAt`,
  ).bind(ownerId, url, remoteUrl, name, model, tokenCipher, updatedAt, deviceId).run();

  const current = await env.DB.prepare("SELECT * FROM computers WHERE ownerId = ?").bind(ownerId).first<ComputerRow>();
  if (!current) throw new Error("computer sparito dopo la scrittura");
  const accepted = (result.meta.changes ?? 0) > 0;
  return accepted ? { accepted, computer: await view(env, current) } : { accepted, stale: true, computer: await view(env, current) };
}

export async function deleteComputer(env: Env, ownerId: string): Promise<boolean> {
  const result = await env.DB.prepare("DELETE FROM computers WHERE ownerId = ?").bind(ownerId).run();
  return (result.meta.changes ?? 0) > 0;
}

async function view(env: Env, row: ComputerRow): Promise<ComputerView> {
  const key = row.tokenCipher ? await computerKey(env) : null;
  const token = key && row.tokenCipher ? await open(key, row.tokenCipher) : null;
  return {
    url: row.url,
    remoteUrl: row.remoteUrl,
    name: row.name,
    model: row.model,
    token,
    updatedAt: row.updatedAt,
    deviceId: row.deviceId,
    tokenStored: token !== null,
  };
}

function text(value: unknown, max: number): string {
  if (value === undefined || value === null) return "";
  if (typeof value !== "string") throw new BadRequest("campo non valido");
  return value.trim().slice(0, max);
}

// ---------------------------------------------------------------------------------------------
// La cifratura del token
// ---------------------------------------------------------------------------------------------

/** La chiave, importata una volta per isolato. `null` se manca o non e' lunga 32 byte. */
let keyCache: { raw: string; key: CryptoKey | null } | null = null;

async function computerKey(env: Env): Promise<CryptoKey | null> {
  const raw = env.COMPUTER_KEY?.trim() ?? "";
  if (keyCache && keyCache.raw === raw) return keyCache.key;
  let key: CryptoKey | null = null;
  if (raw) {
    try {
      const bytes = fromBase64(raw);
      if (bytes.length === 32) key = await crypto.subtle.importKey("raw", bytes, { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
      else console.error("COMPUTER_KEY non e' lunga 32 byte: il token del computer non si custodisce");
    } catch {
      console.error("COMPUTER_KEY non e' base64: il token del computer non si custodisce");
    }
  }
  keyCache = { raw, key };
  return key;
}

/**
 * `v1.<iv>.<cifrato>`, base64. Il prefisso di versione c'e' per il giorno in cui la chiave si
 * ruota: un blob vecchio si riconosce senza provarlo.
 */
async function seal(key: CryptoKey, plain: string): Promise<string> {
  const iv = new Uint8Array(12);
  crypto.getRandomValues(iv);
  const sealed = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, new TextEncoder().encode(plain));
  return `v1.${toBase64(iv)}.${toBase64(new Uint8Array(sealed))}`;
}

/** `null` se il blob non si apre: chiave cambiata, o riga toccata a mano. */
async function open(key: CryptoKey, blob: string): Promise<string | null> {
  const [version, iv, sealed] = blob.split(".");
  if (version !== "v1" || !iv || !sealed) return null;
  try {
    const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv: fromBase64(iv) }, key, fromBase64(sealed));
    return new TextDecoder().decode(plain);
  } catch {
    return null;
  }
}

function toBase64(bytes: Uint8Array): string {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary);
}

function fromBase64(input: string): Uint8Array {
  const binary = atob(input);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}
