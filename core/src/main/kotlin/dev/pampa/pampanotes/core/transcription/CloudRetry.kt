package dev.pampa.pampanotes.core.transcription

import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
    read()[scope(provider, keyFingerprint, accountFingerprint)]?.until
  }

  /** Null denotes historical/unspecified timing; false explicitly records an unreadable/missing header. */
  fun retryAfterKnown(provider: String, keyFingerprint: String, accountFingerprint: String): Boolean? = synchronized(lock) {
    read()[scope(provider, keyFingerprint, accountFingerprint)]?.retryAfterKnown
  }

  fun extend(provider: String, keyFingerprint: String, accountFingerprint: String, until: Long, retryAfterKnown: Boolean? = null): Long = synchronized(lock) {
    val values = read().toMutableMap()
    val key = scope(provider, keyFingerprint, accountFingerprint)
    val old = values[key]
    val deadline = maxOf(old?.until ?: 0, until)
    values[key] = Wait(deadline, if (old != null && old.until > until) old.retryAfterKnown else retryAfterKnown ?: old?.retryAfterKnown)
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, "${file.name}.tmp")
    val data = JsonObject(values.mapValues { (_, wait) -> JsonObject(buildMap {
      put("until", JsonPrimitive(wait.until))
      wait.retryAfterKnown?.let { put("retryAfterKnown", JsonPrimitive(it)) }
    }) }).toString().toByteArray(Charsets.UTF_8)
    java.io.FileOutputStream(temporary).use { it.write(data); it.fd.sync() }
    try {
      Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
      Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    deadline
  }

  private data class Wait(val until: Long, val retryAfterKnown: Boolean?)
  private fun read(): Map<String, Wait> {
    if (!file.exists()) return emptyMap()
    // Fail closed on corrupt storage rather than silently forgetting a paid provider's quota.
    val root = Json.parseToJsonElement(file.readText()) as JsonObject
    return root.mapValues { (_, value) ->
      // Read installation data written before timing provenance was introduced.
      if (value is JsonPrimitive) Wait(value.longOrNull ?: error("Invalid cooldown deadline"), null)
      else {
        val record = value as JsonObject
        Wait((record["until"] as? JsonPrimitive)?.longOrNull ?: error("Invalid cooldown deadline"),
          record["retryAfterKnown"]?.let { (it as JsonPrimitive).booleanOrNull ?: error("Invalid cooldown timing") })
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
