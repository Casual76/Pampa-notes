package dev.pampa.pampanotes.core.backup

import dev.pampa.pampanotes.core.settings.PampaSettings
import dev.pampa.pampanotes.core.settings.RefinementPreset
import dev.pampa.pampanotes.core.settings.SpeakerSeparation
import dev.pampa.pampanotes.core.settings.TranscriptionProviderId
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * Cosa c'e' dentro un backup, in numeri.
 *
 * Si legge **prima** di ripristinare: "1 cartella, 3 note, 2 registrazioni" e' l'unica cosa che
 * permette di accorgersi di aver scelto il file sbagliato mentre si puo' ancora annullare.
 */
@Serializable
data class BackupCounts(
  val folders: Int = 0,
  val notes: Int = 0,
  val sessions: Int = 0,
  val parts: Int = 0,
  val transcripts: Int = 0,
  val sources: Int = 0,
)

/**
 * Le preferenze che viaggiano nel backup.
 *
 * **Nessun segreto.** La chiave di Groq e il token del server personale sono cifrati col Keystore
 * del telefono, che non esce dal telefono: in un file su Drive sarebbero in chiaro, e su un altro
 * dispositivo non si decifrerebbero comunque. Si riscrivono a mano, ed e' giusto cosi'.
 */
@Serializable
data class BackupSettings(
  val language: String = "auto",
  val vocabulary: String = "",
  val chunkMinutes: Int = 10,
  val groqMaxUploadMb: Int = 25,
  /** I pezzi del computer di casa: null, il file intero. */
  val customMaxMinutes: Int? = null,
  val preferredProvider: String = "groq",
  val customOnly: Boolean = false,
  val autoTranscribeOnImport: Boolean = true,
  /** «Separa le voci» ([dev.pampa.pampanotes.core.settings.SpeakerSeparation]), per nome. */
  val speakerSeparation: String = "PERSONAL",
  val endpointUrl: String = "",
  val endpointName: String = "",
  val endpointModel: String = "",
  val endpointTimeoutMinutes: Int = 180,
  val refinementEnabled: Boolean = false,
  val refinementModel: String = "",
  val refinementPreset: String = "CLEAN",
  val refinementCustomPrompt: String = "",
  val exportDefaultsJson: String = "",
)

/**
 * La carta d'identita' dell'archivio, prima voce dello zip.
 *
 * E' la prima apposta: leggerla non costa aprire i gigabyte che vengono dopo, e una schermata che
 * chiede conferma puo' dire cosa sta per ripristinare senza scaricare niente.
 */
@Serializable
data class BackupManifest(
  val schema: Int = SCHEMA,
  val createdAt: Long,
  /** Chi lo ha scritto: "Pampa Notes 0.1.0". */
  val app: String,
  /**
   * La versione dello schema Room dentro il file.
   *
   * Piu' vecchia della corrente va bene: Room migra all'apertura. Piu' nuova no, e va detto invece
   * di far esplodere l'app al riavvio, quando non c'e' piu' niente da spiegare.
   */
  val databaseVersion: Int,
  val includesAudio: Boolean = false,
  val includesSources: Boolean = false,
  val audioBytes: Long = 0,
  val sourceBytes: Long = 0,
  val databaseBytes: Long = 0,
  val counts: BackupCounts = BackupCounts(),
  val settings: BackupSettings? = null,
  /**
   * L'archivio finisce con [BackupEntries.TRAILER], che dice cosa e' stato scritto davvero. Uno zip
   * troncato fra una voce e l'altra si legge senza errori — finisce e basta — e senza la chiusura
   * un backup a meta' su Drive sostituiva registrazioni vere con la meta' di quelle. I backup di
   * prima non ce l'hanno, e per loro vale il controllo che si puo' fare (il database).
   */
  val sealed: Boolean = false,
) {
  val totalBytes: Long get() = databaseBytes + audioBytes + sourceBytes

  companion object {
    /** Cambia solo se la disposizione dentro lo zip cambia, non a ogni versione dell'app. */
    const val SCHEMA = 1
  }
}

/** I nomi delle voci dentro l'archivio. In un posto solo: chi scrive e chi legge devono dire lo stesso. */
object BackupEntries {
  const val MANIFEST = "manifest.json"
  const val DATABASE = "database/pampa_notes.db"
  const val AUDIO_DIR = "files/audio/"
  const val SOURCES_DIR = "files/sources/"

  /** L'ultima voce: [BackupTrailer]. */
  const val TRAILER = "end.json"
}

/**
 * La chiusura dell'archivio, l'ultima voce: quanti file e quanti byte sono entrati davvero.
 *
 * Il manifesto sta davanti e si scrive prima dei file, quindi dice quello che si voleva mettere; un
 * file tolto a meta' backup (una «Libera spazio», un cestino) non c'e', e chi ripristina controlla
 * contro questa, non contro il manifesto.
 */
@Serializable
data class BackupTrailer(
  val databaseBytes: Long = 0,
  val audioFiles: Int = 0,
  val audioBytes: Long = 0,
  val sourceFiles: Int = 0,
  val sourceBytes: Long = 0,
)

/** Dalle preferenze vive a quelle che si portano via. I segreti restano dove sono. */
fun PampaSettings.toBackup(): BackupSettings = BackupSettings(
  language = language,
  vocabulary = vocabulary,
  chunkMinutes = chunkMinutes,
  groqMaxUploadMb = groqMaxUploadMb,
  customMaxMinutes = customMaxMinutes,
  preferredProvider = preferredProvider.id,
  customOnly = customOnly,
  autoTranscribeOnImport = autoTranscribeOnImport,
  speakerSeparation = speakerSeparation.name,
  endpointUrl = endpointUrl,
  endpointName = endpointName,
  endpointModel = endpointModel,
  endpointTimeoutMinutes = endpointTimeoutMinutes,
  refinementEnabled = refinementEnabled,
  refinementModel = refinementModel,
  refinementPreset = refinementPreset.name,
  refinementCustomPrompt = refinementCustomPrompt,
  exportDefaultsJson = exportDefaultsJson,
)

/** Il verso opposto, indulgente: un valore che non si riconosce piu' torna al suo default. */
fun BackupSettings.providerId(): TranscriptionProviderId = TranscriptionProviderId.fromId(preferredProvider)

fun BackupSettings.preset(): RefinementPreset =
  runCatching { RefinementPreset.valueOf(refinementPreset) }.getOrDefault(RefinementPreset.CLEAN)

fun BackupSettings.separation(): SpeakerSeparation =
  runCatching { SpeakerSeparation.valueOf(speakerSeparation) }.getOrDefault(SpeakerSeparation.PERSONAL)

private val backupStamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT)

/** `pampa-notes-backup-20260918-1830.zip`: si riconosce in una cartella e si ordina da solo. */
fun backupFileName(atMillis: Long): String =
  "pampa-notes-backup-" + Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()).format(backupStamp) + ".zip"
