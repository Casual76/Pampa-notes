package dev.pampa.pampanotes.core.transcription

import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Absolute wall-clock deadlines survive process death. Never cap a server quota to one day. */
object CloudRetry {
  const val DEFAULT_WAIT_MS = 600_000L
  fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
  fun scope(provider: String, keyFingerprint: String, accountFingerprint: String): String =
    fingerprint("$provider|$keyFingerprint|$accountFingerprint")

  fun after(now: Long, delayMs: Long): Long = if (delayMs > Long.MAX_VALUE - now) Long.MAX_VALUE else now + delayMs.coerceAtLeast(0)

  fun parseRetryAfter(value: String?, now: Long): Long? {
    val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val seconds = raw.toBigDecimalOrNull()
    if (seconds != null) {
      if (seconds.signum() < 0) return null
      val ms = seconds.multiply(BigDecimal(1000)).setScale(0, RoundingMode.CEILING)
        .min(BigDecimal(Long.MAX_VALUE)).toLong()
      return after(now, ms)
    }
    return runCatching { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli().coerceAtLeast(now) }.getOrNull()
  }

  fun deadline(error: TranscriptionError.RateLimited, now: Long): Long {
    val asked = error.retryAtMillis ?: error.retryAfterSec?.takeIf { it.isFinite() && it >= 0 }
      ?.let { after(now, kotlin.math.ceil(it * 1000).toLong()) } ?: after(now, DEFAULT_WAIT_MS)
    // A zero/past header must still yield, not cause a hot request loop.
    return maxOf(asked, after(now, 1_000))
  }
}

/** Installation-only state. Caller places it under noBackupFilesDir; contains only SHA-256 scopes. */
class DurableCloudCooldowns(private val file: File) {
  fun deadline(provider: String, keyFingerprint: String, accountFingerprint: String): Long? = synchronized(lock) {
    read()[scope(provider, keyFingerprint, accountFingerprint)]?.effective()?.until
  }

  /** Null denotes historical/unspecified timing; false explicitly records an unreadable/missing header. */
  fun retryAfterKnown(provider: String, keyFingerprint: String, accountFingerprint: String): Boolean? = synchronized(lock) {
    read()[scope(provider, keyFingerprint, accountFingerprint)]?.effective()?.retryAfterKnown
  }

  /** An independent observation. An error already carrying this scope's durable aggregate
   * must re-read deadline(), not extend it: it may include another response's provisional wait.
   */
  fun extend(provider: String, keyFingerprint: String, accountFingerprint: String, until: Long, retryAfterKnown: Boolean? = null): Long = synchronized(lock) {
    val values = read().toMutableMap()
    val key = scope(provider, keyFingerprint, accountFingerprint)
    val old = values[key] ?: Record()
    values[key] = old.copy(settled = later(old.settled, Wait(until.coerceAtLeast(0), retryAfterKnown)))
    write(values)
    values.getValue(key).effective()!!.until
  }

  /** One durable fallback per observed response. Tokens never leave installation storage.
   * Keeping each outstanding observation separate prevents equal/concurrent fallback deadlines
   * from being mistaken for this response's fallback when a readable header arrives.
   */
  internal data class Observation(val scope: String, val token: String)

  internal fun beginObservation(provider: String, keyFingerprint: String, accountFingerprint: String, until: Long): Observation = synchronized(lock) {
    val values = read().toMutableMap()
    val key = scope(provider, keyFingerprint, accountFingerprint)
    val observation = Observation(key, UUID.randomUUID().toString())
    val old = values[key] ?: Record()
    values[key] = old.copy(provisional = old.provisional + (observation.token to Wait(until, false)))
    write(values)
    observation
  }

  /** Replace only the matching response's fallback. Null finalizes an unreadable header using
   * that fallback. All independent settled waits and other pending observations survive.
   * If this atomic write fails, disk still contains the conservative provisional deadline.
   */
  internal fun finishObservation(observation: Observation, until: Long?): Long = synchronized(lock) {
    val values = read().toMutableMap()
    val old = values[observation.scope] ?: error("Missing cooldown observation")
    val fallback = old.provisional[observation.token] ?: error("Missing cooldown observation token")
    values[observation.scope] = old.copy(
      settled = later(old.settled, until?.let { Wait(it, true) } ?: fallback),
      provisional = old.provisional - observation.token,
    )
    write(values)
    values.getValue(observation.scope).effective()!!.until
  }

  private fun write(values: Map<String, Record>) {
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, "${file.name}.tmp")
    val data = JsonObject(values.mapValues { (_, record) -> JsonObject(buildMap {
      val effective = record.effective() ?: error("Empty cooldown record")
      // Retain the historical aggregate for older readers, with additive private components.
      putAll(encodeWait(effective))
      put("provisional", JsonObject(record.provisional.mapValues { (_, wait) -> encodeWait(wait) }))
      record.settled?.let { put("settled", encodeWait(it)) }
    }) }).toString().toByteArray(Charsets.UTF_8)
    java.io.FileOutputStream(temporary).use { it.write(data); it.fd.sync() }
    try {
      Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
      Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }

  private data class Wait(val until: Long, val retryAfterKnown: Boolean?)
  private data class Record(val settled: Wait? = null, val provisional: Map<String, Wait> = emptyMap()) {
    fun effective(): Wait? = (listOfNotNull(settled) + provisional.values).maxByOrNull { it.until }
  }
  private fun later(old: Wait?, next: Wait): Wait = when {
    old == null -> next
    old.until > next.until -> old
    else -> next.copy(retryAfterKnown = next.retryAfterKnown ?: old.retryAfterKnown)
  }
  private fun encodeWait(wait: Wait) = JsonObject(buildMap {
    put("until", JsonPrimitive(wait.until))
    wait.retryAfterKnown?.let { put("retryAfterKnown", JsonPrimitive(it)) }
  })
  private fun decodeWait(value: kotlinx.serialization.json.JsonElement): Wait {
    if (value is JsonPrimitive) return Wait(value.longOrNull ?: error("Invalid cooldown deadline"), null)
    val record = value as JsonObject
    return Wait((record["until"] as? JsonPrimitive)?.longOrNull ?: error("Invalid cooldown deadline"),
      record["retryAfterKnown"]?.let { (it as JsonPrimitive).booleanOrNull ?: error("Invalid cooldown timing") })
  }
  private fun read(): Map<String, Record> {
    if (!file.exists()) return emptyMap()
    // Fail closed on corrupt storage rather than silently forgetting a paid provider's quota.
    val root = Json.parseToJsonElement(file.readText()) as JsonObject
    return root.mapValues { (_, value) ->
      // Read installation data written before timing provenance was introduced.
      if (value is JsonPrimitive) Record(settled = decodeWait(value))
      else {
        val record = value as JsonObject
        if ("provisional" !in record) Record(settled = decodeWait(record))
        else Record(settled = record["settled"]?.let(::decodeWait),
          provisional = (record.getValue("provisional") as JsonObject).mapValues { (_, wait) -> decodeWait(wait) })
          .also { requireNotNull(it.effective()) { "Empty cooldown record" } }
      }
    }
  }
  private fun scope(provider: String, key: String, account: String) = CloudRetry.scope(provider, key, account)
  companion object { private val lock = Any() }
}

@Serializable
data class CloudJobContext(val accountFingerprint: String, val cooldownScope: String? = null)

/** Room snapshots are portable backups. Account/key fences belong only in private installation data. */
class DurableCloudJobContexts(private val file: File) {
  private val json = Json { ignoreUnknownKeys = true }
  fun get(jobId: String): CloudJobContext? = synchronized(lock) { read()[CloudRetry.fingerprint(jobId)] }
  fun bind(jobId: String, account: String): CloudJobContext = synchronized(lock) {
    val values = read().toMutableMap()
    val key = CloudRetry.fingerprint(jobId)
    values[key]?.let { return@synchronized it }
    val context = CloudJobContext(account)
    values[key] = context
    write(values)
    context
  }
  fun setCooldown(jobId: String, scope: String, account: String) = synchronized(lock) {
    val values = read().toMutableMap()
    val key = CloudRetry.fingerprint(jobId)
    values[key] = (values[key] ?: CloudJobContext(account)).copy(cooldownScope = scope)
    write(values)
  }
  private fun read(): Map<String, CloudJobContext> = if (file.exists()) json.decodeFromString(file.readText()) else emptyMap()
  private fun write(values: Map<String, CloudJobContext>) {
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, "${file.name}.tmp")
    java.io.FileOutputStream(temporary).use { it.write(json.encodeToString(values).toByteArray(Charsets.UTF_8)); it.fd.sync() }
    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
  }
  companion object { private val lock = Any() }
}
