/** Only the schema-15 public evidence. No transport, account or arbitrary payload fields. */
export const RECORDING_ORIGIN_FIELDS = [
  "recordedAtEpochMs", "recordedAtLocal", "recordedAtZoneId", "recordedAtSource", "recordedAtPrecision",
] as const;

type Payload = Record<string, unknown>;
const SOURCE_PREFIX = "pampa-cloud-source-sync:";
const WIRE_PREFIX = `${SOURCE_PREFIX}1:`;
// Comparison/repacking is intentionally bounded. Larger opaque envelopes remain retainable
// unchanged with the SAME RAW; an unsupported rewrite is rejected, never stripped.
const MAX_COMPARE_BYTES = 16 * 1024 * 1024;
const MAX_SOURCE_CHARS = 32 * 1024 * 1024;
const hashPattern = /^[a-f0-9]{64}$/;
export interface RetainedPublicEvidence {
  payload: unknown;
  /** The caller must reject the whole row; neither known observation may be silently replaced. */
  conflict: boolean;
  /** Existing protocol-1 too_large response: Android durably suppresses an unchanged retry. */
  tooLarge?: boolean;
}

function object(value: unknown): Payload | undefined {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Payload : undefined;
}

function requireValue(ok: unknown): asserts ok { if (!ok) throw new Error("Unsupported cloud source evidence"); }
class EvidenceLimit extends Error { constructor() { super("Cloud source comparison limit"); } }
function withinLimit(ok: unknown): asserts ok { if (!ok) throw new EvidenceLimit(); }
function unicode(text: string): boolean {
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    if (c >= 0xd800 && c <= 0xdbff) { const low = text.charCodeAt(++i); if (!(low >= 0xdc00 && low <= 0xdfff)) return false; }
    else if (c >= 0xdc00 && c <= 0xdfff) return false;
  }
  return true;
}
function string(value: unknown, max: number, min = 0): asserts value is string {
  requireValue(typeof value === "string" && value.length >= min && value.length <= max && unicode(value));
}
function integer(value: unknown, min = 0): asserts value is number { requireValue(Number.isSafeInteger(value) && (value as number) >= min); }
function fields(value: unknown, required: string[], defaults: Payload = {}): Payload {
  const item = object(value);
  requireValue(item && Object.keys(item).every((key) => required.includes(key) || Object.hasOwn(defaults, key)) && required.every((key) => Object.hasOwn(item, key)));
  return { ...defaults, ...item };
}
function array(value: unknown, max: number): unknown[] { requireValue(Array.isArray(value) && value.length <= max); return value; }
function unique(values: unknown[]): void { requireValue(new Set(values).size === values.length); }
function nullableString(value: unknown, max: number): void { if (value !== null) string(value, max); }
function digestString(value: unknown, empty = false): void { requireValue(typeof value === "string" && (hashPattern.test(value) || empty && value === "")); }
async function sha256(text: string): Promise<string> {
  return Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text))), (n) => n.toString(16).padStart(2, "0")).join("");
}

function textPart(value: unknown): Payload {
  const p = fields(value, ["id", "sha256", "text", "language", "provider", "model"], { responses: null });
  string(p.id, 128, 1); digestString(p.sha256, true); nullableString(p.text, 4_000_000); nullableString(p.language, 64);
  string(p.provider, 128); string(p.model, 512);
  if (p.responses !== null) {
    p.responses = array(p.responses, 10000).map((value) => {
      const r = fields(value, ["index", "startMs", "endMs", "text", "language"], { audioSha256: null, provider: null, model: null });
      integer(r.index); integer(r.startMs); integer(r.endMs); requireValue((r.endMs as number) >= (r.startMs as number));
      nullableString(r.text, 4_000_000); nullableString(r.language, 64); nullableString(r.provider, 128); nullableString(r.model, 512);
      if (r.audioSha256 !== null) digestString(r.audioSha256);
      requireValue(p.sha256 === "" || r.audioSha256 === p.sha256);
      return r;
    });
    const responses = p.responses as Payload[];
    requireValue(responses.every((r, i) => i === 0 || (responses[i - 1].index as number) < (r.index as number)));
  }
  return p;
}
function parts(value: unknown): Payload[] { const result = array(value, 1000).map(textPart); unique(result.map((p) => p.id)); return result; }
function sourceDocument(value: string): Payload {
  requireValue(value.length <= MAX_SOURCE_CHARS);
  const doc = fields(JSON.parse(value), ["rawId", "rawVersion", "textSha256", "parts", "originals"],
    { version: 1, overlays: [], alignmentBinding: "source_snapshot" });
  requireValue(doc.version === 1 && ["source_snapshot", "portable_unbound"].includes(doc.alignmentBinding as string));
  string(doc.rawId, 128, 1); integer(doc.rawVersion, 1); digestString(doc.textSha256);
  doc.parts = parts(doc.parts);
  doc.originals = array(doc.originals, 1000).map((value) => {
    const o = fields(value, ["rawId", "rawVersion", "text", "textSha256", "provider", "model", "language", "parts"],
      { reportedText: null, contentScope: "complete_original" });
    string(o.rawId, 128, 1); integer(o.rawVersion, 1); string(o.text, 4_000_000); digestString(o.textSha256);
    string(o.provider, 128); string(o.model, 512); nullableString(o.language, 64); nullableString(o.reportedText, 4_000_000);
    requireValue(["complete_original", "portable_projection", "derived_presentation"].includes(o.contentScope as string));
    o.parts = parts(o.parts); return o;
  });
  requireValue((doc.originals as Payload[]).length > 0);
  unique((doc.originals as Payload[]).map((o) => JSON.stringify([o.rawId, o.rawVersion])));
  const byId = new Map((doc.parts as Payload[]).map((p) => [p.id, p]));
  doc.overlays = array(doc.overlays, byId.size).map((value) => {
    const o = fields(value, ["partId", "textSha256", "language", "timings"]);
    const p = byId.get(o.partId); requireValue(p && typeof p.text === "string");
    digestString(o.textSha256); requireValue(typeof o.language === "string" && /^[a-z]{2,3}$/.test(o.language));
    // Provider labels remain verbatim. Locale equivalence is checked by Android's codec; the
    // Worker compares the immutable part unchanged and never manufactures a language label.
    const text = p.text as string;
    const spans = [...text.matchAll(/[^\p{White_Space}\u001c-\u001f]+/gu)];
    requireValue(text.length <= 200_000);
    let end = 0;
    o.timings = array(o.timings, spans.length).map((value, i) => {
      const t = fields(value, ["text_start", "text_end", "start_ms", "end_ms", "status"], { reason: null });
      const span = spans[i]; requireValue(span && t.text_start === span.index && t.text_end === span.index + span[0].length);
      nullableString(t.reason, 512);
      if (t.status === "missing") requireValue(t.start_ms === null && t.end_ms === null);
      else {
        requireValue(t.status === "measured" && t.reason === null && /[\p{L}\p{N}]/u.test(span[0]));
        integer(t.start_ms); integer(t.end_ms, 1);
        requireValue((t.start_ms as number) >= end && (t.end_ms as number) > (t.start_ms as number)); end = t.end_ms as number;
      }
      return t;
    });
    requireValue((o.timings as unknown[]).length === spans.length); return o;
  });
  unique((doc.overlays as Payload[]).map((o) => o.partId));
  const originals = doc.originals as Payload[];
  const allParts = [...doc.parts as Payload[], ...originals.flatMap((o) => o.parts as Payload[])];
  requireValue(originals.reduce((n, o) => n + (o.text as string).length + ((o.reportedText as string | null)?.length ?? 0) +
    (o.parts as Payload[]).reduce((n, p) => n + ((p.text as string | null)?.length ?? 0), 0), 0) <= 16_000_000);
  requireValue(allParts.reduce((n, p) => n + ((p.responses as Payload[] | null) ?? []).reduce((n, r) => n + ((r.text as string | null)?.length ?? 0), 0), 0) <= 16_000_000);
  return doc;
}
function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  const obj = object(value);
  return obj ? `{${Object.keys(obj).sort().map((key) => `${JSON.stringify(key)}:${canonical(obj[key])}`).join(",")}}` : JSON.stringify(value) ?? "null";
}
function overlayKey(o: Payload): string {
  // Same field/default order as CloudOverlay.serializer() on Android; UTF16 lexical order.
  return JSON.stringify({ partId: o.partId, textSha256: o.textSha256, language: o.language,
    timings: (o.timings as Payload[]).map((t) => ({ text_start: t.text_start, text_end: t.text_end,
      start_ms: t.start_ms, end_ms: t.end_ms, status: t.status, reason: t.reason })) });
}
function mergeSource(previous: string, supplied: string): string {
  withinLimit(previous.length <= MAX_COMPARE_BYTES / 2 && supplied.length <= MAX_COMPARE_BYTES / 2);
  const old = sourceDocument(previous), fresh = sourceDocument(supplied);
  requireValue(canonical({ ...old, overlays: [] }) === canonical({ ...fresh, overlays: [] }));
  const selected = new Map<unknown, Payload>();
  const measured = (o: Payload) => (o.timings as Payload[]).filter((t) => t.status === "measured").length;
  for (const o of [...old.overlays as Payload[], ...fresh.overlays as Payload[]]) {
    const p = selected.get(o.partId);
    if (!p || measured(o) > measured(p) || measured(o) === measured(p) && overlayKey(o) > overlayKey(p)) selected.set(o.partId, o);
  }
  const overlays = [...selected.values()].sort((a, b) => (a.partId as string) < (b.partId as string) ? -1 : 1);
  const equivalent = (doc: Payload) => canonical([...(doc.overlays as Payload[])].sort((a, b) => (a.partId as string) < (b.partId as string) ? -1 : 1)) === canonical(overlays);
  if (equivalent(fresh)) return supplied;
  if (equivalent(old)) return previous;
  // Replace the overlays in the parsed ORIGINAL, never reconstruct source parts/responses from
  // the normalized comparison form. Unsupported public fields were rejected recursively above.
  return JSON.stringify({ ...JSON.parse(supplied), overlays });
}

/** The immutable source witness accompanies seq in sync.ts, including the exact RAW text. */
function rawChanged(previous: Payload, incoming: Payload): boolean {
  return typeof previous.text !== "string" || previous.text !== incoming.text;
}

async function compressedBytes(input: Uint8Array, decompress: boolean): Promise<Uint8Array> {
  const stream = decompress ? new DecompressionStream("deflate") : new CompressionStream("deflate");
  const writer = stream.writable.getWriter();
  const writing = (async () => { await writer.write(input); await writer.close(); })();
  const reader = stream.readable.getReader();
  const chunks: Uint8Array[] = []; let size = 0;
  try {
    while (true) { const { done, value } = await reader.read(); if (done) break; size += value.length; withinLimit(size <= MAX_COMPARE_BYTES); chunks.push(value); }
    await writing;
  } catch (e) { await reader.cancel().catch(() => {}); await writing.catch(() => {}); throw e; }
  const result = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { result.set(chunk, offset); offset += chunk.length; } return result;
}
async function expandSource(source: string, raw: string): Promise<string> {
  requireValue(source.startsWith(WIRE_PREFIX) && source.length <= 1_900_000 && raw.length <= 4_000_000);
  const encoded = source.slice(WIRE_PREFIX.length), binary = atob(encoded);
  requireValue(btoa(binary) === encoded);
  const data = await compressedBytes(Uint8Array.from(binary, (c) => c.charCodeAt(0)), true);
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength); let offset = 0;
  const int = () => { requireValue(offset + 4 <= data.length); const n = view.getInt32(offset); offset += 4; return n; };
  const utfHash = () => { requireValue(offset + 2 <= data.length && view.getUint16(offset) === 64); offset += 2;
    const value = new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(data.subarray(offset, offset + 64)); offset += 64; digestString(value); return value; };
  const length = int(); requireValue(length >= 0 && length <= MAX_SOURCE_CHARS);
  withinLimit(length <= MAX_COMPARE_BYTES / 2);
  const sourceHash = utfHash(), rawHash = utfHash(); requireValue(rawHash === await sha256(raw));
  const dictionary = JSON.stringify(raw).slice(1, -1); let result = "", commands = 0;
  while (true) {
    requireValue(offset < data.length && ++commands <= 1_000_000);
    const op = data[offset++]; if (op === 3) break;
    if (op === 0) {
      const count = int(); requireValue(count > 0 && count <= 32768 && offset + count <= data.length);
      result += new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(data.subarray(offset, offset + count)); offset += count;
    } else {
      const start = int(), count = int(); requireValue(start >= 0 && count > 0);
      if (op === 1) { requireValue(start + count <= dictionary.length); result += `"${dictionary.slice(start, start + count)}"`; }
      else { requireValue(op === 2 && start + count <= result.length); result += result.slice(start, start + count); }
    }
    requireValue(result.length <= length);
  }
  requireValue(offset === data.length && result.length === length && !result.startsWith(SOURCE_PREFIX) && sourceHash === await sha256(result)); return result;
}

async function packSource(source: string, raw: string): Promise<string> {
  // Lossless v1 encoder with RAW/previous-string references, identical commands to Android.
  let output = new Uint8Array(64 * 1024), length = 0;
  const byte = (n: number) => {
    if (length === output.length) { withinLimit(length < MAX_COMPARE_BYTES); const grown = new Uint8Array(Math.min(MAX_COMPARE_BYTES, length * 2)); grown.set(output); output = grown; }
    output[length++] = n;
  };
  const int = (n: number) => { byte((n >>> 24) & 255); byte((n >>> 16) & 255); byte((n >>> 8) & 255); byte(n & 255); };
  int(source.length);
  for (const hash of [await sha256(source), await sha256(raw)]) { byte(0); byte(64); for (const c of hash) byte(c.charCodeAt(0)); }
  const dictionary = JSON.stringify(raw).slice(1, -1), seen = new Map<string, number>();
  let cursor = 0, from = 0, commands = 0;
  const literal = (end: number) => { while (from < end) {
    let to = Math.min(end, from + 8192); if (to < end && /[\ud800-\udbff]/.test(source[to - 1])) to--;
    const bytes = new TextEncoder().encode(source.slice(from, to)); byte(0); int(bytes.length);
    for (const value of bytes) byte(value); from = to; requireValue(++commands <= 999999);
  } };
  while (cursor < source.length) {
    if (source[cursor++] !== '"') continue;
    const start = cursor - 1;
    while (cursor < source.length) { const c = source[cursor++]; if (c === "\\" && cursor < source.length) cursor++; else if (c === '"') break; }
    if (source[cursor - 1] !== '"') break;
    if (cursor - start < 64) continue;
    const token = source.slice(start, cursor), previous = seen.get(token), rawAt = previous === undefined ? dictionary.indexOf(token.slice(1, -1)) : -1;
    if (previous !== undefined || rawAt >= 0) { literal(start); byte(previous !== undefined ? 2 : 1); int(previous ?? rawAt); int(previous !== undefined ? token.length : token.length - 2); from = cursor; requireValue(++commands <= 999999); }
    if (!seen.has(token)) seen.set(token, start);
  }
  literal(source.length); byte(3);
  const compressed = await compressedBytes(output.subarray(0, length), false);
  let binary = ""; for (const byte of compressed) binary += String.fromCharCode(byte);
  const envelope = WIRE_PREFIX + btoa(binary); withinLimit(envelope.length <= 1_900_000); return envelope;
}

/** Wire-aware push boundary. No account/base/hash/seq decisions here. The caller retains those
 * guards. Same-RAW opaque/unknown sources stay byte-for-byte; typed overlay enrichment compares
 * expanded documents and emits a v1 envelope again only if either input was already encoded.
 */
export async function retainWirePublicEvidence(table: string, payload: unknown, stored: unknown): Promise<RetainedPublicEvidence> {
  if (table !== "transcripts") return retainPublicEvidence(table, payload, stored);
  const incoming = object(payload), previous = object(stored);
  if (!incoming) return retainPublicEvidence(table, payload, stored);
  const supplied = incoming.cloudSourceJson, known = previous?.cloudSourceJson;
  const encoded = (value: unknown): value is string => typeof value === "string" && value.startsWith(SOURCE_PREFIX);
  const conflict = { payload, conflict: true };
  // The compressed representation contains RAW references AND its hash. Never copy it across
  // an omitted/null-source RAW edit, even when this is the same device or current base.
  if (previous && encoded(known) && (rawChanged(previous, incoming) || previous.id !== incoming.id || previous.createdAt !== incoming.createdAt)) return conflict;
  if (previous && encoded(known) && !rawChanged(previous, incoming) && (supplied == null || supplied === known))
    return { payload: { ...incoming, cloudSourceJson: known }, conflict: false };
  try {
    const oldSource = encoded(known) ? await expandSource(known, previous!.text as string) : known;
    const newSource = encoded(supplied) ? await expandSource(supplied, incoming.text as string) : supplied;
    const old = previous && { ...previous, cloudSourceJson: oldSource };
    const fresh = { ...incoming, cloudSourceJson: newSource };
    const result = retainPublicEvidence(table, fresh, old);
    if (result.tooLarge) return { payload, conflict: false, tooLarge: true };
    if (result.conflict) return conflict;
    const merged = object(result.payload)!.cloudSourceJson;
    if (typeof merged === "string" && oldSource != null && newSource != null && oldSource !== newSource) {
      const doc = sourceDocument(merged), byId = new Map((doc.parts as Payload[]).map((p) => [p.id, p]));
      const hashes = new Map<string, string>();
      const hash = async (text: string) => { if (!hashes.has(text)) hashes.set(text, await sha256(text)); return hashes.get(text); };
      for (const o of doc.originals as Payload[]) requireValue(o.textSha256 === await hash(o.text as string));
      for (const o of doc.overlays as Payload[]) requireValue(o.textSha256 === await hash(byId.get(o.partId)!.text as string));
    }
    // Bind even unsupported future typed metadata to its declared RAW without rewriting it.
    if (typeof merged === "string" && !merged.startsWith(SOURCE_PREFIX)) {
      let doc: Payload | undefined; try { doc = object(JSON.parse(merged)); } catch { /* Opaque old source. */ }
      if (doc && "rawId" in doc && "rawVersion" in doc && "textSha256" in doc && "originals" in doc) {
        requireValue(typeof incoming.text === "string" && doc.rawId === incoming.id && doc.rawVersion === Math.max(1, incoming.createdAt as number) && doc.textSha256 === await sha256(incoming.text));
      }
    }
    let wire = merged;
    if (merged === oldSource && known != null) wire = known;
    else if (merged === newSource) wire = supplied;
    else if (typeof merged === "string" && (encoded(known) || encoded(supplied))) wire = await packSource(merged, incoming.text as string);
    return { payload: { ...incoming, cloudSourceJson: wire }, conflict: false };
  } catch (error) { return error instanceof EvidenceLimit ? { payload, conflict: false, tooLarge: true } : conflict; }
}

function unknownOrigin(value: Payload): boolean {
  return RECORDING_ORIGIN_FIELDS.every((field) => value[field] == null);
}

function extendsLocal(short: unknown, long: unknown): boolean {
  return typeof short === "string" && typeof long === "string" && long.startsWith(short) &&
    long.length > short.length && "T:.".includes(long[short.length]);
}

/** Strict ISO local components, with fractions compared at epoch storage's millisecond boundary.
 * Date.parse alone normalizes invalid days and can silently supply the host's default zone. */
function localParts(value: unknown, dayOnly = false): number[] | undefined {
  if (typeof value !== "string") return undefined;
  const match = (dayOnly ? /^([+-]?\d{4,9})-(\d{2})-(\d{2})$/ :
    /^([+-]?\d{4,9})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?$/).exec(value);
  if (!match || !/^(?:\d{4}|-\d{4,9}|\+\d{5,9})$/.test(match[1]) || match[1] === "-0000") return undefined;
  const [year, month, day, hour, minute, second] = Array.from({ length: 6 }, (_, index) => Number(match[index + 1] ?? 0));
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  if (Math.abs(year) > 999999999 || month < 1 || month > 12 || day < 1 || day > days[month - 1] ||
      hour > 23 || minute > 59 || second > 59) return undefined;
  return [year, month, day, hour, minute, second, Number((match[7] ?? "").padEnd(3, "0").slice(0, 3))];
}

type OriginZone = { offsetSeconds: number } | { formatter: Intl.DateTimeFormat };
function originZone(id: unknown): OriginZone | undefined {
  if (typeof id !== "string") return undefined;
  if (id === "Z" || id === "UTC" || id === "UT" || id === "GMT") return { offsetSeconds: 0 };
  const offset = id.replace(/^(?:UTC|GMT|UT)(?=[+-])/, "");
  if (/^[+-]/.test(offset)) {
    const match = /^([+-])(\d{1,2})(?:(\d{2})(\d{2})?|:(\d{2})(?::(\d{2}))?)?$/.exec(offset);
    if (!match || match[2].length === 1 && offset.length !== 2) return undefined;
    const hour = Number(match[2]), minute = Number(match[3] ?? match[5] ?? 0), second = Number(match[4] ?? match[6] ?? 0);
    if (hour > 18 || minute > 59 || second > 59 || hour === 18 && (minute !== 0 || second !== 0)) return undefined;
    return { offsetSeconds: (hour * 3600 + minute * 60 + second) * (match[1] === "-" ? -1 : 1) };
  }
  try {
    return { formatter: new Intl.DateTimeFormat("en-GB-u-ca-iso8601-nu-latn", {
      timeZone: id, era: "short", year: "numeric", month: "2-digit", day: "2-digit",
      hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23",
    }) };
  } catch { return undefined; }
}

function epochParts(epoch: number, zone: OriginZone): number[] {
  const millis = ((epoch % 1000) + 1000) % 1000;
  if ("offsetSeconds" in zone) {
    const date = new Date(epoch + zone.offsetSeconds * 1000);
    return [date.getUTCFullYear(), date.getUTCMonth() + 1, date.getUTCDate(), date.getUTCHours(), date.getUTCMinutes(), date.getUTCSeconds(), millis];
  }
  const parts = zone.formatter.formatToParts(epoch);
  const part = (type: string) => parts.find((item) => item.type === type)?.value;
  const year = Number(part("year"));
  return [part("era") === "BC" ? 1 - year : year, Number(part("month")), Number(part("day")),
    Number(part("hour")), Number(part("minute")), Number(part("second")), millis];
}

/** Mirrors RecordingOrigin.evidenceQuality(): incomplete/self-contradictory tuples rank zero.
 * Precision is evidence only when that SAME observation independently supplies a usable time. */
function originQuality(value: Payload): number {
  const epoch = value.recordedAtEpochMs;
  const local = value.recordedAtLocal;
  const zoneId = value.recordedAtZoneId;
  const source = value.recordedAtSource;
  const precision = value.recordedAtPrecision;
  if (typeof source !== "string" || !source.trim()) return 0;
  if (precision === "day") return epoch == null && zoneId == null && localParts(local, true) ? 10 : 0;
  const rank = precision === "millisecond" ? 40 : precision === "second" ? 30 : precision === "minute" ? 20 : 0;
  if (!rank || source === "file_modified" || epoch != null && !Number.isSafeInteger(epoch)) return 0;
  const wall = local == null ? undefined : localParts(local);
  if (local != null && !wall || !wall && epoch == null) return 0;
  const zone = zoneId == null ? undefined : originZone(zoneId);
  if (zoneId != null && !zone) return 0;
  if (wall && zone && epoch != null) {
    try {
      const actual = epochParts(epoch as number, zone);
      if (!wall.every((part, index) => part === actual[index])) return 0;
    } catch { return 0; }
  }
  return rank + (epoch != null ? 4 : 0) + (wall ? 2 : 0) + (zone ? 1 : 0);
}

function copyOrigin(target: Payload, origin: Payload): Payload {
  const out = { ...target };
  for (const field of RECORDING_ORIGIN_FIELDS) out[field] = origin[field] ?? null;
  return out;
}

function sameAudio(previous: Payload, incoming: Payload): boolean {
  return typeof previous.id === "string" && previous.id === incoming.id &&
    typeof previous.sha256 === "string" && /^[0-9a-f]{64}$/i.test(previous.sha256) &&
    typeof incoming.sha256 === "string" && previous.sha256.toLowerCase() === incoming.sha256.toLowerCase() &&
    Number.isSafeInteger(previous.sizeBytes) && (previous.sizeBytes as number) >= 0 && previous.sizeBytes === incoming.sizeBytes;
}

/**
 * Mirrors PampaBundleMetadataMerge / SyncMetadataMerge: missing and null mean unknown; an empty
 * source is known exact text, never parsed/trimmed. Clock quality and compatibility keep one
 * coherent observation. Audio identity requires the same valid SHA-256 and exact safe byte count;
 * the sync index has metadata only and does not verify the audio bytes itself.
 *
 * Previous must be the public projection of the current live row, read with its seq. Tombstones
 * supply no previous evidence: deletion/recreation or a new transcript ID can replace provenance.
 * Known incompatible evidence is rejected as `stale` by push, allowing pull/conflict resolution;
 * this helper neither invents a wire result nor changes the client's opaque hash/baseHash.
 */
export function retainPublicEvidence(table: string, payload: unknown, stored: unknown): RetainedPublicEvidence {
  const incoming = object(payload);
  const previous = object(stored);
  const unchanged = { payload, conflict: false };
  if (!incoming || !previous) return unchanged;

  if (table === "transcripts") {
    const known = previous.cloudSourceJson;
    const supplied = incoming.cloudSourceJson;
    if (known == null) return unchanged;
    if (supplied != null && supplied !== known) {
      try {
        requireValue(typeof known === "string" && typeof supplied === "string" && !rawChanged(previous, incoming));
        return { payload: { ...incoming, cloudSourceJson: mergeSource(known, supplied) }, conflict: false };
      } catch (error) { return error instanceof EvidenceLimit ? { payload, conflict: false, tooLarge: true } : { payload, conflict: true }; }
    }
    return supplied == null ? { payload: { ...incoming, cloudSourceJson: known }, conflict: false } : unchanged;
  }
  if (table !== "audio_parts" || !sameAudio(previous, incoming) || unknownOrigin(previous)) return unchanged;
  if (unknownOrigin(incoming)) return { payload: copyOrigin(incoming, previous), conflict: false };

  const sameInstant = previous.recordedAtEpochMs != null && previous.recordedAtEpochMs === incoming.recordedAtEpochMs;
  const oldText = previous.recordedAtLocal;
  const newText = incoming.recordedAtLocal;
  const compatibleText = oldText == null || newText == null || oldText === newText ||
    extendsLocal(oldText, newText) || extendsLocal(newText, oldText);
  if ((previous.recordedAtEpochMs != null && incoming.recordedAtEpochMs != null && !sameInstant) ||
      (!sameInstant && !compatibleText) ||
      (!sameInstant && previous.recordedAtZoneId != null && incoming.recordedAtZoneId != null &&
        previous.recordedAtZoneId !== incoming.recordedAtZoneId)) return { payload, conflict: true };

  // Native import and Android sync choose one intact tuple; incoming wins equal-quality ties.
  // Missing fields remain unknown even when another observation could fill them individually.
  const primary = originQuality(previous) > originQuality(incoming) ? previous : incoming;
  return { payload: primary === incoming ? payload : copyOrigin(incoming, primary), conflict: false };
}
