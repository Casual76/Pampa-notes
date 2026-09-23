/**
 * Chi sta chiamando.
 *
 * Tre strade per lo stesso risultato — un `ownerId` — e nessuna passa da una password:
 *
 *  - un **token di sessione** emesso da qui (`POST /v1/auth/google`): l'app entra una volta con
 *    Google, e da quel momento manda questo. E' la strada normale. Un ID token di Google dura
 *    un'ora e rinnovarlo in silenzio da un worker in background non e' affidabile; un token di
 *    sessione dura finche' non lo si revoca, e si revoca da qui, per dispositivo;
 *  - un **ID token di Google** cosi' com'e', che l'app ottiene dal Credential Manager. Si verifica
 *    contro le chiavi pubbliche di Google (RS256, `aud` uguale al nostro client ID, `iss` di Google,
 *    non scaduto), e l'`ownerId` e' il suo `sub`: stabile per sempre per quell'account, anche se
 *    l'indirizzo email cambia. Serve per aprire la sessione, e vale anche da solo;
 *  - un **token di sviluppo** dalla configurazione (`AUTH_DEV_TOKENS`), per girare in locale
 *    senza un progetto Google. In produzione la variabile resta vuota e la strada non esiste.
 *
 * L'`ownerId` c'e' dal primo giorno anche con un utente solo: infilare un'identita' in un
 * protocollo gia' pieno di dati e' una migrazione di server e di tutti i client.
 */

export interface Env {
  DB: D1Database;
  AUTH_DEV_TOKENS?: string;
  GOOGLE_CLIENT_ID?: string;
  TOMBSTONE_RETENTION_DAYS?: string;
  PROTOCOL_VERSION?: string;
  /** L'origine con cui si costruiscono i link condivisi, se diversa da quella della richiesta (un dominio davanti al Worker). */
  PUBLIC_ORIGIN?: string;
  /**
   * La chiave con cui si cifra il token del computer di casa (`computer.ts`): 32 byte in base64.
   * Un segreto (`wrangler secret put COMPUTER_KEY`, in locale `.dev.vars`), mai in wrangler.toml.
   */
  COMPUTER_KEY?: string;
}

export class Unauthorized extends Error {}

const GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs";
const GOOGLE_ISSUERS = new Set(["https://accounts.google.com", "accounts.google.com"]);

/** Le chiavi di Google, tenute per un'ora: cambiano di rado, e ogni richiesta non puo' scaricarle. */
let jwksCache: { keys: JsonWebKey[]; fetchedAt: number } | null = null;

export function bearerOf(request: Request): string {
  const header = request.headers.get("authorization") ?? "";
  return header.startsWith("Bearer ") ? header.slice(7).trim() : "";
}

export async function ownerOf(request: Request, env: Env): Promise<string> {
  const token = bearerOf(request);
  if (!token) throw new Unauthorized("manca il token");

  const dev = devOwner(token, env.AUTH_DEV_TOKENS ?? "");
  if (dev) return dev;

  const session = await sessionOwner(env, token);
  if (session) return session;

  if (!env.GOOGLE_CLIENT_ID) throw new Unauthorized("token non riconosciuto");
  return (await googleIdentity(token, env.GOOGLE_CLIENT_ID)).ownerId;
}

function devOwner(token: string, table: string): string | null {
  for (const pair of table.split(",")) {
    // Si divide al primo `:` e basta: un ownerId come `google:123` ne ha un altro dentro, e
    // tagliarlo li' darebbe a un token di sviluppo il proprietario `google`, che non e' nessuno.
    const at = pair.indexOf(":");
    if (at < 0) continue;
    const t = pair.slice(0, at).trim();
    const owner = pair.slice(at + 1).trim();
    if (t && owner && timingSafeEqual(t, token)) return owner;
  }
  return null;
}

/** Un confronto che non esce al primo carattere diverso: quanto ci mette direbbe quanti erano giusti. */
function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

// ---------------------------------------------------------------------------------------------
// Le sessioni
// ---------------------------------------------------------------------------------------------

interface SessionRow {
  token: string;
  ownerId: string;
  lastSeenAt: number;
}

async function sessionOwner(env: Env, token: string): Promise<string | null> {
  // Un token di sessione e' lungo e senza punti: un JWT ha due punti, un token di sviluppo e' corto.
  if (token.length < 40 || token.includes(".")) return null;
  const row = await env.DB.prepare("SELECT token, ownerId, lastSeenAt FROM sessions WHERE token = ? AND revokedAt IS NULL").bind(token).first<SessionRow>();
  if (!row) return null;
  // «Visto l'ultima volta» si aggiorna al massimo una volta all'ora: e' un'informazione per il
  // pannello, non una scrittura per richiesta.
  if (Date.now() - row.lastSeenAt > 3_600_000) {
    await env.DB.prepare("UPDATE sessions SET lastSeenAt = ? WHERE token = ?").bind(Date.now(), token).run();
  }
  return row.ownerId;
}

export interface LoginResult {
  token: string;
  ownerId: string;
  email: string | null;
  name: string | null;
}

/**
 * Apre una sessione da un ID token di Google: chi lo presenta e' chi Google dice che e', e da
 * adesso in poi puo' presentare il token di sessione al suo posto.
 */
export async function loginWithGoogle(env: Env, idToken: string, deviceId: string, deviceName: string): Promise<LoginResult> {
  if (!env.GOOGLE_CLIENT_ID) throw new Unauthorized("accesso Google non configurato sul server");
  const identity = await googleIdentity(idToken, env.GOOGLE_CLIENT_ID);
  const token = newSessionToken();
  const now = Date.now();
  await env.DB.prepare(
    "INSERT INTO sessions (token, ownerId, deviceId, deviceName, email, createdAt, lastSeenAt, revokedAt) VALUES (?, ?, ?, ?, ?, ?, ?, NULL)",
  ).bind(token, identity.ownerId, deviceId || "", deviceName || "", identity.email, now, now).run();
  return { token, ownerId: identity.ownerId, email: identity.email, name: identity.name };
}

/** Chiude la sessione del token presentato. Un token che non e' una sessione (dev, ID token) non ha niente da chiudere. */
export async function logout(env: Env, token: string): Promise<boolean> {
  if (!token) return false;
  const result = await env.DB.prepare("UPDATE sessions SET revokedAt = ? WHERE token = ? AND revokedAt IS NULL").bind(Date.now(), token).run();
  return (result.meta.changes ?? 0) > 0;
}

function newSessionToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

// ---------------------------------------------------------------------------------------------
// Google
// ---------------------------------------------------------------------------------------------

interface GoogleIdentity {
  ownerId: string;
  email: string | null;
  name: string | null;
}

async function googleIdentity(idToken: string, clientId: string): Promise<GoogleIdentity> {
  const parts = idToken.split(".");
  if (parts.length !== 3) throw new Unauthorized("non e' un JWT");
  let header: { alg?: string; kid?: string };
  let claims: { iss?: string; aud?: string; exp?: number; sub?: string; email?: string; name?: string };
  try {
    header = JSON.parse(base64UrlDecodeText(parts[0]));
    claims = JSON.parse(base64UrlDecodeText(parts[1]));
  } catch {
    throw new Unauthorized("JWT malformato");
  }

  if (header.alg !== "RS256") throw new Unauthorized("algoritmo inatteso");
  if (!claims.iss || !GOOGLE_ISSUERS.has(claims.iss)) throw new Unauthorized("emittente inatteso");
  if (claims.aud !== clientId) throw new Unauthorized("pubblico inatteso");
  if (typeof claims.exp !== "number" || claims.exp * 1000 < Date.now()) throw new Unauthorized("token scaduto");
  if (typeof claims.sub !== "string" || !claims.sub) throw new Unauthorized("manca il sub");

  const jwk = await googleKey(header.kid ?? "");
  const key = await crypto.subtle.importKey("jwk", jwk, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
  const data = new TextEncoder().encode(`${parts[0]}.${parts[1]}`);
  const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, base64UrlDecode(parts[2]), data);
  if (!ok) throw new Unauthorized("firma non valida");
  return { ownerId: `google:${claims.sub}`, email: typeof claims.email === "string" ? claims.email : null, name: typeof claims.name === "string" ? claims.name : null };
}

async function fetchGoogleKeys(): Promise<JsonWebKey[]> {
  const response = await fetch(GOOGLE_JWKS);
  if (!response.ok) throw new Unauthorized("chiavi di Google non disponibili");
  const body = (await response.json()) as { keys: JsonWebKey[] };
  jwksCache = { keys: body.keys, fetchedAt: Date.now() };
  return body.keys;
}

async function googleKey(kid: string): Promise<JsonWebKey> {
  const find = (keys: JsonWebKey[]) => keys.find((k) => (k as { kid?: string }).kid === kid);
  const keys = !jwksCache || Date.now() - jwksCache.fetchedAt > 3_600_000 ? await fetchGoogleKeys() : jwksCache.keys;
  let key = find(keys);
  // Google ruota le chiavi e firma subito con quella nuova: per un'ora, con la cache vecchia, ogni
  // accesso fallirebbe con «chiave sconosciuta». Si ricarica una volta — ma non piu' di una volta
  // al minuto, o un token inventato con un `kid` a caso farebbe una richiesta a Google ogni volta.
  if (!key && jwksCache && Date.now() - jwksCache.fetchedAt > 60_000) key = find(await fetchGoogleKeys());
  if (!key) throw new Unauthorized("chiave sconosciuta");
  return key;
}

function base64UrlDecode(input: string): Uint8Array {
  const padded = input.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (input.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function base64UrlDecodeText(input: string): string {
  return new TextDecoder().decode(base64UrlDecode(input));
}
