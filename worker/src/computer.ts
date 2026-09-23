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
 *
 * Qui stanno anche i **biglietti per il PC** (in fondo): la prova, per il companion, che chi gli
 * parla ha fatto l'accesso con l'account del proprietario.
 */

import type { Env } from "./auth";
import { ownerMatches } from "./guests";
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

/**
 * Quanto un orologio puo' stare avanti prima di non essere creduto. Un telefono con la data
 * dell'anno prossimo scriverebbe un `updatedAt` che nessun'altra scrittura supera piu': il computer
 * resterebbe inchiodato a quella versione fino all'anno prossimo.
 */
const FUTURE_SLACK_MS = 5 * 60_000;

export async function putComputer(env: Env, ownerId: string, body: PutComputerBody): Promise<PutComputerResult> {
  const claimed = Number(body.updatedAt);
  if (!Number.isFinite(claimed) || claimed <= 0) throw new BadRequest("updatedAt mancante");
  const now = Date.now();
  const future = now + FUTURE_SLACK_MS;
  const updatedAt = claimed > future ? now : claimed;
  // E una riga scritta da un orologio avanti prima di questa regola non vince piu' su nessuno.
  const effective = (stored: number) => (stored > future ? 0 : stored);
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
  if (existing && updatedAt <= effective(existing.updatedAt)) {
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
     WHERE excluded.updatedAt > (CASE WHEN computers.updatedAt > ? THEN 0 ELSE computers.updatedAt END)`,
  ).bind(ownerId, url, remoteUrl, name, model, tokenCipher, updatedAt, deviceId, future).run();

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

// ---------------------------------------------------------------------------------------------
// Il biglietto per il PC
// ---------------------------------------------------------------------------------------------
//
// Il companion deve riconoscere chi ha fatto l'accesso con l'account del proprietario, anche in
// casa. Mandargli il token di sessione del sync vorrebbe dire farlo viaggiare in chiaro su http in
// LAN, e quel token apre tutte le note. Al suo posto un biglietto: vale solo per il companion, dura
// dodici ore, e lo firma questo Worker con una chiave che non esce da qui.
//
// `pt_<payload>.<firma>`: il payload e' base64url (senza `=`) di `{"o":ownerId,"e":scadenza,"v":1}`,
// la firma e' HMAC-SHA256 del payload con una chiave derivata da COMPUTER_KEY. Senza stato: nessuna
// tabella, niente da pulire, e revocarli tutti vuol dire cambiare COMPUTER_KEY. Il companion non lo
// apre mai da solo: lo manda a `/v1/computer/verify`, che risponde anche di chi e'. Il formato e'
// fissato in `contratto-biglietti-pc.md`, condiviso con app e companion.

const TICKET_PREFIX = "pt_";
const TICKET_TTL_MS = 12 * 3_600_000;
/**
 * La chiave dei biglietti e' figlia di COMPUTER_KEY e non COMPUTER_KEY stessa: la stessa chiave
 * usata per due cose diverse (qui HMAC, sopra AES-GCM) e' il modo in cui un errore in una diventa
 * un errore nell'altra.
 */
const TICKET_KEY_LABEL = "pampa-computer-ticket-v1";

let ticketKeyCache: { raw: string; key: CryptoKey | null } | null = null;

async function ticketKey(env: Env): Promise<CryptoKey | null> {
  const raw = env.COMPUTER_KEY?.trim() ?? "";
  if (ticketKeyCache && ticketKeyCache.raw === raw) return ticketKeyCache.key;
  let key: CryptoKey | null = null;
  if (raw) {
    try {
      const secret = fromBase64(raw);
      if (secret.length) {
        const master = await crypto.subtle.importKey("raw", secret, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
        const derived = await crypto.subtle.sign("HMAC", master, new TextEncoder().encode(TICKET_KEY_LABEL));
        key = await crypto.subtle.importKey("raw", derived, { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
      }
    } catch {
      console.error("COMPUTER_KEY non e' base64: niente biglietti per il PC");
    }
  }
  ticketKeyCache = { raw, key };
  return key;
}

/** Un biglietto nuovo per `ownerId`. `null` se il Worker non ha COMPUTER_KEY: non c'e' con cosa firmarlo. */
export async function issueTicket(env: Env, ownerId: string): Promise<{ ticket: string; expiresAt: number } | null> {
  const key = await ticketKey(env);
  if (!key) return null;
  const expiresAt = Date.now() + TICKET_TTL_MS;
  const payload = toBase64Url(new TextEncoder().encode(JSON.stringify({ o: ownerId, e: expiresAt, v: 1 })));
  const signature = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(payload)));
  return { ticket: `${TICKET_PREFIX}${payload}.${toBase64Url(signature)}`, expiresAt };
}

/**
 * Il companion chiede: questo biglietto e' buono, e per il PC di `owner`? `owner` e' quello che ha
 * nel `config.json`, un'email o l'`ownerId` nudo, e vale la stessa regola degli ospiti
 * (`ownerMatches`): un biglietto di un altro account, anche vero, non apre questo PC.
 *
 * `"no-key"` se il Worker non ha la chiave: non e' il biglietto a essere sbagliato, e il companion
 * non deve ricordarselo come tale.
 */
export async function verifyTicket(env: Env, ticket: string, owner: string): Promise<{ ownerId: string; expiresAt: number } | null | "no-key"> {
  const key = await ticketKey(env);
  if (!key) return "no-key";
  if (!ticket.startsWith(TICKET_PREFIX) || ticket.length > 2048 || !owner) return null;
  const [payload, signature, ...rest] = ticket.slice(TICKET_PREFIX.length).split(".");
  if (!payload || !signature || rest.length) return null;

  let claims: { o?: unknown; e?: unknown; v?: unknown };
  try {
    // La firma prima di tutto: un payload non firmato da qui non si guarda nemmeno.
    // crypto.subtle.verify confronta a tempo costante.
    if (!(await crypto.subtle.verify("HMAC", key, fromBase64Url(signature), new TextEncoder().encode(payload)))) return null;
    claims = JSON.parse(new TextDecoder().decode(fromBase64Url(payload)));
  } catch {
    return null;
  }
  if (!claims || claims.v !== 1 || typeof claims.o !== "string" || !claims.o || typeof claims.e !== "number") return null;
  if (claims.e <= Date.now()) return null;
  if (!(await ownerMatches(env, claims.o, owner))) return null;
  return { ownerId: claims.o, expiresAt: claims.e };
}

function toBase64Url(bytes: Uint8Array): string {
  return toBase64(bytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fromBase64Url(input: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]*$/.test(input)) throw new Error("non e' base64url");
  return fromBase64(input.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (input.length % 4)) % 4));
}
