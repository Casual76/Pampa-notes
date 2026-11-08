/** Complete speaker maps have recording clocks independent of the session's metadata clock. */
const CLOCKS = "_pampaVoiceClocks";
type Names = { names: Map<string, string>; clocks: Map<string, number>; clocked: boolean };
const partOf = (key: string): string => key.split("|", 1)[0];

function decode(raw: unknown): Names | undefined {
  if (raw === null || raw === undefined || raw === "") return { names: new Map(), clocks: new Map(), clocked: false };
  if (typeof raw !== "string") return undefined;
  let value: unknown;
  try { value = JSON.parse(raw); } catch { return undefined; }
  if (!value || typeof value !== "object" || Array.isArray(value)) return undefined;
  const names = new Map<string, string>();
  const clocks = new Map<string, number>();
  for (const [key, name] of Object.entries(value)) {
    if (!key.trim() || key === CLOCKS || typeof name !== "string") continue;
    const clean = Array.from(name.replace(/[\p{Cc}\p{White_Space}]/gu, " ").replace(/ +/g, " ").trim()).slice(0, 40).join("").trim();
    if (clean) names.set(key, clean);
  }
  const nested = (value as Record<string, unknown>)[CLOCKS];
  if (nested && typeof nested === "object" && !Array.isArray(nested)) {
    for (const [part, time] of Object.entries(nested)) {
      if (part.trim() && typeof time === "number" && Number.isSafeInteger(time) && time >= 0) clocks.set(part, time);
    }
  }
  return { names, clocks, clocked: !!nested && typeof nested === "object" && !Array.isArray(nested) };
}

function recordings(names: Map<string, string>): Map<string, Map<string, string>> {
  const groups = new Map<string, Map<string, string>>();
  for (const [key, name] of names) {
    const part = partOf(key), group = groups.get(part) ?? new Map<string, string>();
    group.set(key, name); groups.set(part, group);
  }
  return groups;
}
function same(a: Map<string, unknown>, b: Map<string, unknown>): boolean {
  return a.size === b.size && [...a].every(([key, value]) => b.has(key) && b.get(key) === value);
}

/** Only recording identities needed for the bounded ownership lookup; no speaker data escapes. */
export function voiceRecordingIds(raw: unknown): string[] {
  const value = decode(raw);
  return value ? [...new Set([...value.clocks.keys(), ...[...value.names.keys()].map(partOf)])] : [];
}

/**
 * Called only for live session snapshots, guarded by sync.ts's existing base/seq checks.
 * An omitted field is unknown. A legacy string/null edits only the recording maps that differ
 * from the stored snapshot: unchanged maps (including empty tombstones) keep their clocks.
 * Explicit new-client clocks compete per complete map; the stored map wins ties, like Android.
 * A structured omission (including the empty clock object from a last-recording move) keeps
 * source authority until real audio ownership changes; a legacy null remains an actual edit.
 * Never rewrite the caller's opaque hash or raise the session's title/date/selection clock.
 */
export function retainSessionVoiceClocks(payload: unknown, previous: { voiceNames: unknown; updatedAt: number } | undefined, updatedAt: number, relocated: ReadonlySet<string> = new Set(), owners: ReadonlyMap<string, string> = new Map()): unknown {
  if (!previous || !payload || typeof payload !== "object" || Array.isArray(payload)) return payload;
  const incoming = payload as Record<string, unknown>;
  if (!("voiceNames" in incoming)) return typeof previous.voiceNames === "string" && previous.voiceNames
    ? { ...incoming, voiceNames: previous.voiceNames } : payload;
  const old = decode(previous.voiceNames), next = decode(incoming.voiceNames);
  if (!old || !next || (!old.clocks.size && !next.clocks.size && !relocated.size && !owners.size)) return payload;
  const time = typeof incoming.updatedAt === "number" && Number.isSafeInteger(incoming.updatedAt) && incoming.updatedAt >= 0 ? incoming.updatedAt : updatedAt;
  const parts = new Set([...old.clocks.keys(), ...next.clocks.keys(), ...relocated, ...[...old.names.keys(), ...next.names.keys()].map(partOf)]);
  const oldMaps = recordings(old.names), nextMaps = recordings(next.names);
  const names = new Map<string, string>(), clocks = new Map<string, number>();
  for (const part of parts) {
    const before = oldMaps.get(part) ?? new Map<string, string>(), after = nextMaps.get(part) ?? new Map<string, string>();
    const oldTime = old.clocks.get(part) ?? previous.updatedAt;
    const explicit = next.clocks.get(part);
    // Once audio really belongs elsewhere, dropping its source evidence is cleanup, not a new
    // empty-map edit. This lets the enrichment push converge even for an empty source session.
    if (!relocated.has(part) && explicit === undefined && !after.size && owners.has(part) && owners.get(part) !== incoming.id) continue;
    // Without an explicit clock, equality proves this recording was not edited by the legacy
    // decoder/rename/encoder. A changed complete map, including null removal, is a legacy edit.
    // A source dropping a moved map is not a voice edit. Retain its old evidence until audio
    // ownership is received, even if the source session is pulled on an earlier page.
    const keep = explicit === undefined ? relocated.has(part) || (next.clocked && !after.size) || same(before, after)
      : (old.clocks.has(part) || before.size > 0) && oldTime >= explicit;
    for (const [key, value] of keep ? before : after) names.set(key, value);
    clocks.set(part, keep ? oldTime : explicit ?? time);
  }
  if (same(names, next.names) && same(clocks, next.clocks)) return payload;
  const ordered = Object.fromEntries([...names].sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0));
  const times = Object.fromEntries([...clocks].sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0));
  return { ...incoming, voiceNames: !names.size && !clocks.size ? null : JSON.stringify({ ...ordered, [CLOCKS]: times }) };
}
