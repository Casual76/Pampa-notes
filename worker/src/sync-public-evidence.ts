/** Only the schema-15 public evidence. No transport, account or arbitrary payload fields. */
export const RECORDING_ORIGIN_FIELDS = [
  "recordedAtEpochMs", "recordedAtLocal", "recordedAtZoneId", "recordedAtSource", "recordedAtPrecision",
] as const;

type Payload = Record<string, unknown>;
export interface RetainedPublicEvidence {
  payload: unknown;
  /** The caller must reject the whole row; neither known observation may be silently replaced. */
  conflict: boolean;
}

function object(value: unknown): Payload | undefined {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Payload : undefined;
}

function unknownOrigin(value: Payload): boolean {
  return RECORDING_ORIGIN_FIELDS.every((field) => value[field] == null);
}

function extendsLocal(short: unknown, long: unknown): boolean {
  return typeof short === "string" && typeof long === "string" && long.startsWith(short) &&
    long.length > short.length && "T:.".includes(long[short.length]);
}

function strength(value: Payload): number {
  const precision = value.recordedAtPrecision;
  return (value.recordedAtEpochMs != null ? 100 : 0) +
    (precision === "millisecond" ? 40 : precision === "second" ? 30 : precision === "minute" ? 20 : precision === "day" ? 10 : 0) +
    (value.recordedAtZoneId != null ? 2 : 0) + (value.recordedAtLocal != null ? 1 : 0);
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
 * source is known exact text, never parsed/trimmed. Clock strength and compatibility keep one
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
    if (supplied != null && supplied !== known) return { payload, conflict: true };
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

  const primary = strength(previous) > strength(incoming) ? previous : incoming;
  const secondary = primary === previous ? incoming : previous;
  // The same instant in different zones keeps the stronger complete representation, without
  // filling its missing fields from the other zone's incompatible local date/time.
  if (!compatibleText) return { payload: copyOrigin(incoming, primary), conflict: false };
  const merged = { ...incoming };
  for (const field of RECORDING_ORIGIN_FIELDS) merged[field] = primary[field] ?? secondary[field] ?? null;
  return { payload: merged, conflict: false };
}
