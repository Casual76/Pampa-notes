package dev.pampa.pampanotes.core.sync

import dev.pampa.pampanotes.core.files.Hashing
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Transport only. The entity, backup and native source codec continue to contain the exact original
 * JSON string. A reserved, versioned string inside cloudSourceJson needs no Worker/schema change.
 *
 * Deflate alone cannot deduplicate long, high entropy transcripts (its window is only 32 KiB).
 * Before deflate, long JSON string lexemes reference the escaped RAW or an earlier source lexeme.
 * Literals preserve every other character, including JSON formatting, escapes and unknown fields.
 * Hashes always describe the expanded entity, never a particular compressed representation.
 */
object SyncCloudSourceWire {
  const val PREFIX = "pampa-cloud-source-sync:"
  private const val VERSION = "1:"
  const val MAX_SOURCE_CHARS = 32 * 1024 * 1024
  private const val MAX_RAW_CHARS = 4_000_000
  private const val MAX_ENVELOPE_CHARS = 1_900_000
  private const val MIN_SOURCE_CHARS = 32 * 1024
  private const val MIN_TOKEN_CHARS = 64
  private const val MAX_COMMANDS = 1_000_000
  private const val LITERAL = 0
  private const val RAW = 1
  private const val PREVIOUS = 2
  private const val END = 3
  private const val ERROR = "Invalid or unsupported cloud source sync encoding"

  fun toWire(payload: JsonElement): JsonElement {
    val obj = payload as? JsonObject ?: return payload
    val source = (obj["cloudSourceJson"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return payload
    require(!source.startsWith(PREFIX)) { ERROR } // Never recursively wrap a stored envelope.
    if (source.length < MIN_SOURCE_CHARS || source.length > MAX_SOURCE_CHARS) return payload
    val raw = (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return payload
    if (raw.length > MAX_RAW_CHARS) return payload
    val packed = pack(source, raw)
    // Keeping the plain form is lossless too; size failures are handled durably by the repository.
    if (packed.length >= source.length || packed.length > MAX_ENVELOPE_CHARS) return payload
    return JsonObject(obj + ("cloudSourceJson" to JsonPrimitive(packed)))
  }

  /** Call before any entity/graph consumer, including pull, conflict handling and canonical hashing. */
  fun toLocal(payload: JsonElement): JsonElement {
    val obj = payload as? JsonObject ?: return payload
    val source = (obj["cloudSourceJson"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return payload
    if (!source.startsWith(PREFIX)) return payload
    val raw = (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error(ERROR)
    return JsonObject(obj + ("cloudSourceJson" to JsonPrimitive(unpack(source, raw))))
  }

  fun toLocal(change: WireChange): WireChange =
    if (change.tbl == "transcripts" && !change.isDelete && change.payload != null)
      change.copy(payload = toLocal(change.payload)) else change

  private fun escaped(raw: String): String = JsonPrimitive(raw).toString().let { it.substring(1, it.length - 1) }

  private fun pack(source: String, raw: String): String {
    val bytes = ByteArrayOutputStream()
    val dictionary = escaped(raw)
    val seen = HashMap<String, Int>()
    DataOutputStream(DeflaterOutputStream(bytes)).use { out ->
      out.writeInt(source.length)
      out.writeUTF(Hashing.sha256(source))
      out.writeUTF(Hashing.sha256(raw))
      var literalStart = 0
      var cursor = 0
      var commands = 0
      fun literal(end: Int) {
        var from = literalStart
        while (end > from) {
          var to = minOf(end, from + 8192)
          if (to < end && source[to - 1].isHighSurrogate()) to--
          val value = source.substring(from, to).toByteArray(Charsets.UTF_8)
          out.writeByte(LITERAL); out.writeInt(value.size); out.write(value)
          commands++
          from = to
        }
      }
      while (cursor < source.length) {
        if (source[cursor] != '"') { cursor++; continue }
        val start = cursor++
        while (cursor < source.length) {
          val char = source[cursor++]
          if (char == '\\' && cursor < source.length) cursor++
          else if (char == '"') break
        }
        if (source[cursor - 1] != '"') break // Opaque/malformed text is still transported literally.
        if (cursor - start < MIN_TOKEN_CHARS) continue
        val token = source.substring(start, cursor)
        val previous = seen[token]
        val offset = if (previous == null) dictionary.indexOf(token.substring(1, token.length - 1)) else -1
        if (previous != null || offset >= 0) {
          literal(start)
          out.writeByte(if (previous != null) PREVIOUS else RAW)
          out.writeInt(previous ?: offset)
          out.writeInt(if (previous != null) token.length else token.length - 2)
          commands++
          literalStart = cursor
        }
        seen.putIfAbsent(token, start)
        // All valid source documents fit this bound. Opaque larger command sets stay plain.
        if (commands >= MAX_COMMANDS - 2) return source
      }
      literal(source.length)
      out.writeByte(END)
    }
    return PREFIX + VERSION + Base64.getEncoder().encodeToString(bytes.toByteArray())
  }

  private fun unpack(envelope: String, raw: String): String {
    require(envelope.startsWith(PREFIX + VERSION) && envelope.length <= MAX_ENVELOPE_CHARS && raw.length <= MAX_RAW_CHARS) { ERROR }
    try {
      val encoded = envelope.substring((PREFIX + VERSION).length)
      // Basic RFC4648 only, no whitespace, alternate alphabets or unpadded encodings.
      val compressed = Base64.getDecoder().decode(encoded)
      require(Base64.getEncoder().encodeToString(compressed) == encoded) { ERROR }
      val inflater = Inflater()
      try {
        val compressedInput = ByteArrayInputStream(compressed)
        DataInputStream(InflaterInputStream(compressedInput, inflater)).use { input ->
          val length = input.readInt()
          require(length in 0..MAX_SOURCE_CHARS) { ERROR }
          val expectedHash = input.readUTF()
          require(expectedHash.matches(Regex("[0-9a-f]{64}")) && input.readUTF() == Hashing.sha256(raw)) { ERROR }
          val dictionary = escaped(raw)
          val result = StringBuilder(minOf(length, 64 * 1024))
          var commands = 0
          var literalBytes = 0L
          while (true) {
            require(++commands <= MAX_COMMANDS) { ERROR }
            when (input.readUnsignedByte()) {
              END -> break
              LITERAL -> {
                val count = input.readInt()
                require(count in 1..32768 && count <= (length - result.length) * 4L) { ERROR }
                literalBytes += count
                require(literalBytes <= length * 4L) { ERROR }
                val value = ByteArray(count)
                input.readFully(value)
                val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                  .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value))
                require(decoded.length <= length - result.length) { ERROR }
                result.append(decoded)
              }
              RAW -> {
                val start = input.readInt(); val count = input.readInt()
                require(start >= 0 && count > 0 && start.toLong() + count <= dictionary.length && count.toLong() + 2 <= length - result.length) { ERROR }
                result.append('"').append(dictionary, start, start + count).append('"')
              }
              PREVIOUS -> {
                val start = input.readInt(); val count = input.readInt()
                require(start >= 0 && count > 0 && start.toLong() + count <= result.length && count <= length - result.length) { ERROR }
                // Nonoverlapping references only; no recursive/backreference bomb expansion.
                result.append(result.substring(start, start + count))
              }
              else -> error(ERROR)
            }
            require(result.length <= length) { ERROR }
          }
          require(input.read() == -1 && inflater.finished() && inflater.remaining == 0 && compressedInput.available() == 0) { ERROR }
          val source = result.toString()
          require(source.length == length && Hashing.sha256(source) == expectedHash && !source.startsWith(PREFIX)) { ERROR }
          return source
        }
      } finally { inflater.end() }
    } catch (error: Exception) {
      // Do not leak source text, base64, or parser messages through sync error reporting.
      throw IllegalArgumentException(ERROR)
    }
  }
}
