package dev.pampa.pampanotes.core.spending

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Synchronous durable transactions also work at the raw HTTP response boundary. */
class DurableCloudCostStore(private val file: File) {
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private val monitor = monitors.getOrPut(file.canonicalPath) { Any() }
  private val revision = MutableStateFlow(0L)
  val changes: StateFlow<Long> = revision

  fun reserve(charge: CloudCharge, estimate: CostEstimate): CloudCharge {
    update { data ->
      SpendingLedger.check(data, charge.jobId, estimate, charge.createdAt)?.let {
        throw dev.pampa.pampanotes.core.transcription.TranscriptionError.CloudControl(it)
      }
      require(data.charges.none { it.id == charge.id })
      data.copy(charges = data.charges + charge.copy(reservedUsd = estimate.maxUsd, priceVerified = estimate.reservable))
    }
    return charge
  }

  fun settle(id: String, actualUsd: String, now: Long, reconciled: Boolean = false) {
    require(Usd.amount(actualUsd) != null)
    update { data -> data.copy(charges = data.charges.map {
      if (it.id == id && it.settledAt == null) it.copy(actualUsd = actualUsd, settledAt = now, reconciled = reconciled) else it
    }) }
  }

  fun read(): CloudCostData = locked { load() }
  fun update(change: (CloudCostData) -> CloudCostData): CloudCostData = locked {
    val next = change(load()).let { it.copy(settings = it.settings.validated()) }
    require(next.schema == 1)
    next.settings.validated()
    val tmp = File(file.path + ".new")
    val backup = File(file.path + ".bak")
    FileOutputStream(tmp).use { out -> out.write(json.encodeToString(next).toByteArray(Charsets.UTF_8)); out.fd.sync() }
    if (file.exists()) check(file.renameTo(backup)) { "Cannot retain cloud ledger" }
    check(tmp.renameTo(file)) { "Cannot commit cloud ledger" }
    if (backup.exists()) check(backup.delete()) { "Cannot finish cloud ledger commit" }
    revision.value += 1
    next
  }

  private fun load(): CloudCostData {
    val backup = File(file.path + ".bak")
    // A backup means the previous commit did not finish. Never start an empty ledger on corruption.
    if (backup.exists()) {
      if (file.exists()) check(file.delete())
      check(backup.renameTo(file))
    }
    if (!file.exists()) return CloudCostData()
    val text = file.readText()
    val root = json.parseToJsonElement(text) as? JsonObject ?: error("Invalid cloud ledger")
    require((root["schema"] as? JsonPrimitive)?.intOrNull == 1 && root["settings"] is JsonObject && root["charges"] is JsonArray)
    return json.decodeFromString<CloudCostData>(text).let {
      require(it.schema == 1)
      require(it.charges.map { charge -> charge.id }.distinct().size == it.charges.size)
      require(it.charges.all { charge ->
        (charge.reservedUsd == null || Usd.amount(charge.reservedUsd) != null) &&
          (charge.actualUsd == null || Usd.amount(charge.actualUsd) != null) &&
          (charge.settledAt == null || charge.actualUsd != null)
      })
      it.copy(settings = it.settings.validated())
    }
  }

  private fun <T> locked(block: () -> T): T = synchronized(monitor) {
    file.parentFile?.let { check(it.exists() || it.mkdirs()) }
    RandomAccessFile(File(file.path + ".lock"), "rw").use { lockFile -> lockFile.channel.lock().use { block() } }
  }

  companion object { private val monitors = ConcurrentHashMap<String, Any>() }
}
