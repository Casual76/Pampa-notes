package dev.pampa.pampanotes.ui.export

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.pampa.pampanotes.R
import dev.pampa.pampanotes.core.export.ExportLabels

/**
 * Le parole del bundle, prese dalla lingua in cui l'app e' aperta.
 *
 * I writer stanno in `:core` e sono puri, quindi non possono leggere `res/values`; i valori di
 * ripiego che hanno dentro sono italiani e servono ai test. Qui si sostituiscono con quelli veri,
 * cosi' un pacchetto esportato da un telefono in inglese ha i titoli in inglese e la frase sulla
 * trascrizione automatica arriva a chi la deve leggere.
 */
@Composable
fun exportLabels(): ExportLabels = ExportLabels(
  notes = stringResource(R.string.export_label_notes),
  noNotes = stringResource(R.string.export_label_no_notes),
  session = stringResource(R.string.export_label_session),
  transcript = stringResource(R.string.export_label_transcript),
  transcriptRaw = stringResource(R.string.export_label_raw),
  transcriptRefined = stringResource(R.string.export_label_refined),
  noTranscript = stringResource(R.string.export_label_no_transcript),
  refinedHasNoTimings = stringResource(R.string.export_label_no_timings),
  part = stringResource(R.string.export_label_part),
  startsAt = stringResource(R.string.export_label_starts_at),
  sessions = stringResource(R.string.export_label_sessions),
  of = stringResource(R.string.export_label_of),
  previous = stringResource(R.string.export_label_previous),
  next = stringResource(R.string.export_label_next),
  machineText = stringResource(R.string.export_label_machine_text),
  notesAreIn = stringResource(R.string.export_label_notes_are_in),
  handwriting = stringResource(R.string.export_label_handwriting),
  handwritingDetail = stringResource(R.string.export_label_handwriting_detail),
  page = stringResource(R.string.export_label_page),
  pages = stringResource(R.string.export_label_pages),
  pageSingular = stringResource(R.string.export_label_page_singular),
  indexHowTo = stringResource(R.string.export_label_index_how_to),
  sources = stringResource(R.string.export_label_sources),
  index = stringResource(R.string.export_label_index),
  recording = stringResource(R.string.export_label_recording),
  recordings = stringResource(R.string.export_label_recordings),
  minutes = stringResource(R.string.export_label_minutes),
  hours = stringResource(R.string.session_silence_hours),
  // La stessa riga della schermata: il segnaposto resta, lo riempie il writer con la durata.
  silence = stringResource(R.string.session_silence),
  words = stringResource(R.string.export_label_words),
  note = stringResource(R.string.export_label_note),
  notesPlural = stringResource(R.string.export_label_notes_plural),
)
