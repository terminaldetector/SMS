/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.saturnmask.gallery.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.exifinterface.media.ExifInterface
import com.saturnmask.gallery.data.SAMPLE_RATE
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "AGUtils"

const val LOCAL_URL_BASE = "https://appassets.androidplatform.net"

fun cleanUpMediapipeTaskErrorMessage(message: String): String {
  val index = message.indexOf("=== Source Location Trace")
  if (index >= 0) {
    return message.substring(0, index)
  }
  return message
}

// Extensions actually used by LiteRT-LM model files in this fork/upstream (see doc comments in
// data/Model.kt and ui/llmchat/LlmChatModelHelper.kt referring to ".task"/".litertlm" files).
// Deliberately not narrowed to exactly one of these per declared model type — nothing in this
// codebase enforces that distinction today, and guessing wrong here would reject valid imports.
// This only catches the obviously-wrong case (a GGUF, a zip, a text file, etc. renamed/mistaken
// for a model file), not a subtler container/schema mismatch within a plausible extension.
private val KNOWN_LITERT_MODEL_EXTENSIONS = setOf(".task", ".litertlm", ".tflite")

/**
 * Conservative pre-native-engine sanity check: catches an empty/missing file or an obviously
 * wrong file type before it's ever handed to the native LiteRT-LM engine. Returns a user-facing
 * reason string on failure, or null if the file looks plausible. This is NOT format/metadata/
 * op-set/quantization validation — none of that is checked here, since doing so correctly would
 * need a bundled flatbuffer schema parser (a real new dependency) rather than a cheap client-side
 * check. A file that passes this can still fail once the native engine actually parses it.
 */
fun validateModelFileOrNull(modelPath: String): String? {
  val file = File(modelPath)
  if (!file.exists() || file.length() <= 0L) {
    return "the model file is missing or empty (expected at ${file.path})"
  }
  val hasKnownExtension =
    KNOWN_LITERT_MODEL_EXTENSIONS.any { file.name.endsWith(it, ignoreCase = true) }
  if (!hasKnownExtension) {
    return "\"${file.name}\" doesn't look like a LiteRT-LM model file (expected one of " +
      "${KNOWN_LITERT_MODEL_EXTENSIONS.joinToString(", ")})"
  }
  return null
}

fun processLlmResponse(response: String): String {
  return response.replace("\\n", "\n")
}

inline fun <reified T> getJsonResponse(url: String): JsonObjAndTextContent<T>? {
  try {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.connect()

    val responseCode = connection.responseCode
    if (responseCode == HttpURLConnection.HTTP_OK) {
      val inputStream = connection.inputStream
      val response = inputStream.bufferedReader().use { it.readText() }

      val jsonObj = parseJson<T>(response)
      return if (jsonObj != null) {
        JsonObjAndTextContent(jsonObj = jsonObj, textContent = response)
      } else {
        null
      }
    } else {
      Log.e("AGUtils", "HTTP error: $responseCode")
    }
  } catch (e: Exception) {
    Log.e("AGUtils", "Error when getting or parsing json response", e)
  }

  return null
}

/** Parses a JSON string into an object of type [T] using Gson. */
inline fun <reified T> parseJson(response: String): T? {
  return try {
    val gson = Gson()
    gson.fromJson(response, T::class.java)
  } catch (e: Exception) {
    Log.e("AGUtils", "Error parsing JSON string", e)
    null
  }
}

private enum class AudioFileFormat {
  WAV,
  MP3,
  UNKNOWN,
}

/**
 * Sniffs the first bytes of [uri]'s content to tell WAV and MP3 apart, rather than trusting the
 * picker-reported MIME type -- some content providers ignore an ACTION_GET_CONTENT intent's
 * EXTRA_MIME_TYPES filter, so a file can arrive here with a MIME type that doesn't match its
 * actual bytes.
 */
private fun sniffAudioFileFormat(context: Context, uri: Uri): AudioFileFormat {
  val header =
    try {
      val stream =
        (if (uri.scheme == null || uri.scheme == "file") {
          FileInputStream(uri.path ?: "")
        } else {
          context.contentResolver.openInputStream(uri)
        }) ?: return AudioFileFormat.UNKNOWN
      stream.use {
        val buffer = ByteArray(12)
        val bytesRead = it.read(buffer)
        if (bytesRead <= 0) ByteArray(0) else buffer.copyOf(bytesRead)
      }
    } catch (e: Exception) {
      return AudioFileFormat.UNKNOWN
    }
  return when {
    header.size >= 12 &&
      header[0] == 'R'.code.toByte() &&
      header[1] == 'I'.code.toByte() &&
      header[2] == 'F'.code.toByte() &&
      header[3] == 'F'.code.toByte() &&
      header[8] == 'W'.code.toByte() &&
      header[9] == 'A'.code.toByte() &&
      header[10] == 'V'.code.toByte() &&
      header[11] == 'E'.code.toByte() -> AudioFileFormat.WAV
    // ID3v2-tagged MP3.
    header.size >= 3 &&
      header[0] == 'I'.code.toByte() &&
      header[1] == 'D'.code.toByte() &&
      header[2] == '3'.code.toByte() -> AudioFileFormat.MP3
    // Untagged MP3: raw MPEG frame sync (11 set bits at the start of a frame header).
    header.size >= 2 &&
      (header[0].toInt() and 0xFF) == 0xFF &&
      (header[1].toInt() and 0xE0) == 0xE0 -> AudioFileFormat.MP3
    else -> AudioFileFormat.UNKNOWN
  }
}

/**
 * Converts an audio file at [uri] to mono 16-bit PCM at (at least) [SAMPLE_RATE] Hz, trimmed to
 * [maxSeconds] -- the shape the chat/model pipeline expects (see
 * ui/llmchat/LlmChatModelHelper.kt's `Content.AudioBytes` usage). Dispatches to a WAV or MP3
 * decode path based on [sniffAudioFileFormat]; returns null for anything else.
 */
fun convertAudioToMonoWithMaxSeconds(
  context: Context,
  uri: Uri,
  maxSeconds: Int = 30,
): AudioClip? =
  when (sniffAudioFileFormat(context, uri)) {
    AudioFileFormat.WAV -> convertWavToMonoWithMaxSeconds(context, uri, maxSeconds)
    AudioFileFormat.MP3 -> convertMp3ToMonoWithMaxSeconds(context, uri, maxSeconds)
    AudioFileFormat.UNKNOWN -> {
      Log.e(TAG, "Unrecognized audio file format for $uri (not WAV or MP3)")
      null
    }
  }

/**
 * Decodes an MP3 file at [uri] to mono 16-bit PCM via Android's built-in MediaExtractor/MediaCodec
 * -- MP3 has been a mandatory platform codec since API 10, so no third-party decoder library is
 * needed. Downmixes to mono before resampling (unlike [convertWavToMonoWithMaxSeconds], whose
 * resample-then-downmix order only works for its own always-16-bit-PCM-source case) so the shared
 * [resample] helper -- which only implements the mono case -- is always called with real mono
 * data, regardless of whether the source MP3 was upsampled or downsampled relative to
 * [SAMPLE_RATE].
 */
private fun convertMp3ToMonoWithMaxSeconds(
  context: Context,
  uri: Uri,
  maxSeconds: Int,
): AudioClip? {
  var extractor: MediaExtractor? = null
  var codec: MediaCodec? = null
  try {
    extractor = MediaExtractor()
    extractor.setDataSource(context, uri, null)

    var trackIndex = -1
    var format: MediaFormat? = null
    for (i in 0 until extractor.trackCount) {
      val candidate = extractor.getTrackFormat(i)
      if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
        trackIndex = i
        format = candidate
        break
      }
    }
    if (trackIndex == -1 || format == null) {
      Log.e(TAG, "No audio track found in $uri")
      return null
    }
    extractor.selectTrack(trackIndex)

    val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
    val sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    Log.d(TAG, "MP3 track: mime=$mime, sampleRate=$sourceSampleRate, channels=$channels")

    codec = MediaCodec.createDecoderByType(mime)
    codec.configure(format, null, null, 0)
    codec.start()

    val pcmOut = ByteArrayOutputStream()
    val bufferInfo = MediaCodec.BufferInfo()
    var sawInputEos = false
    var sawOutputEos = false
    val timeoutUs = 10_000L
    // Bounds how long a very long MP3 keeps decoding -- the precise trim-to-maxSeconds cut
    // happens on the final mono/resampled samples below, same as the WAV path.
    val maxRawBytes = (maxSeconds + 5).toLong() * sourceSampleRate * channels * 2

    while (!sawOutputEos && pcmOut.size() < maxRawBytes) {
      if (!sawInputEos) {
        val inputIndex = codec.dequeueInputBuffer(timeoutUs)
        if (inputIndex >= 0) {
          val inputBuffer = codec.getInputBuffer(inputIndex)
          val sampleSize = inputBuffer?.let { extractor.readSampleData(it, 0) } ?: -1
          if (sampleSize < 0) {
            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            sawInputEos = true
          } else {
            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
            extractor.advance()
          }
        }
      }
      val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
      if (outputIndex >= 0) {
        if (bufferInfo.size > 0) {
          codec.getOutputBuffer(outputIndex)?.let { outputBuffer ->
            val chunk = ByteArray(bufferInfo.size)
            outputBuffer.get(chunk)
            pcmOut.write(chunk)
          }
        }
        codec.releaseOutputBuffer(outputIndex, false)
        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
          sawOutputEos = true
        }
      }
    }

    val decodedShortBuffer =
      ByteBuffer.wrap(pcmOut.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val decodedSamples = ShortArray(decodedShortBuffer.remaining())
    decodedShortBuffer.get(decodedSamples)

    // Downmix to mono first (at the source sample rate), matching the codebase's existing
    // stereo-average approach in convertWavToMonoWithMaxSeconds.
    var monoSamples =
      if (channels == 2) {
        val mono = ShortArray(decodedSamples.size / 2)
        for (i in mono.indices) {
          val left = decodedSamples[i * 2]
          val right = decodedSamples[i * 2 + 1]
          mono[i] = ((left + right) / 2).toShort()
        }
        mono
      } else {
        decodedSamples
      }

    var effectiveSampleRate = sourceSampleRate
    if (sourceSampleRate != SAMPLE_RATE) {
      Log.d(TAG, "Resampling MP3 from $sourceSampleRate Hz to $SAMPLE_RATE Hz.")
      monoSamples = resample(monoSamples, sourceSampleRate, SAMPLE_RATE, channels = 1)
      effectiveSampleRate = SAMPLE_RATE
    }

    val maxSamples = maxSeconds * effectiveSampleRate
    if (monoSamples.size > maxSamples) {
      monoSamples = monoSamples.copyOfRange(0, maxSamples)
    }

    val monoByteBuffer = ByteBuffer.allocate(monoSamples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    monoByteBuffer.asShortBuffer().put(monoSamples)
    return AudioClip(audioData = monoByteBuffer.array(), sampleRate = effectiveSampleRate)
  } catch (e: Exception) {
    Log.e(TAG, "Failed to decode mp3", e)
    return null
  } finally {
    try {
      codec?.stop()
    } catch (e: Exception) {
      // Already stopped/never started -- fine to ignore during cleanup.
    }
    codec?.release()
    extractor?.release()
  }
}

fun convertWavToMonoWithMaxSeconds(
  context: Context,
  stereoUri: Uri,
  maxSeconds: Int = 30,
): AudioClip? {
  Log.d(TAG, "Start to convert wav file to mono channel")

  try {
    val inputStream =
      (if (stereoUri.scheme == null || stereoUri.scheme == "file") {
        FileInputStream(stereoUri.path ?: "")
      } else {
        context.contentResolver.openInputStream(stereoUri)
      }) ?: return null
    val originalBytes = inputStream.readBytes()
    inputStream.close()

    // Read WAV header
    if (originalBytes.size < 44) {
      // Not a valid WAV file
      Log.e(TAG, "Not a valid wav file")
      return null
    }

    val headerBuffer = ByteBuffer.wrap(originalBytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
    val channels = headerBuffer.getShort(22)
    var sampleRate = headerBuffer.getInt(24)
    val bitDepth = headerBuffer.getShort(34)
    Log.d(TAG, "File metadata: channels: $channels, sampleRate: $sampleRate, bitDepth: $bitDepth")

    // Normalize audio to 16-bit.
    val audioDataBytes = originalBytes.copyOfRange(fromIndex = 44, toIndex = originalBytes.size)
    var sixteenBitBytes: ByteArray =
      if (bitDepth.toInt() == 8) {
        Log.d(TAG, "Converting 8-bit audio to 16-bit.")
        convert8BitTo16Bit(audioDataBytes)
      } else {
        // Assume 16-bit or other format that can be handled directly
        audioDataBytes
      }

    // Convert byte array to short array for processing
    val shortBuffer =
      ByteBuffer.wrap(sixteenBitBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    var pcmSamples = ShortArray(shortBuffer.remaining())
    shortBuffer.get(pcmSamples)

    // Resample if sample rate is less than 16000 Hz ---
    if (sampleRate < SAMPLE_RATE) {
      Log.d(TAG, "Resampling from $sampleRate Hz to $SAMPLE_RATE Hz.")
      pcmSamples = resample(pcmSamples, sampleRate, SAMPLE_RATE, channels.toInt())
      sampleRate = SAMPLE_RATE
      Log.d(TAG, "Resampling complete. New sample count: ${pcmSamples.size}")
    }

    // Convert stereo to mono if necessary
    var monoSamples =
      if (channels.toInt() == 2) {
        Log.d(TAG, "Converting stereo to mono.")
        val mono = ShortArray(pcmSamples.size / 2)
        for (i in mono.indices) {
          val left = pcmSamples[i * 2]
          val right = pcmSamples[i * 2 + 1]
          mono[i] = ((left + right) / 2).toShort()
        }
        mono
      } else {
        Log.d(TAG, "Audio is already mono. No channel conversion needed.")
        pcmSamples
      }

    // Trim the audio to maxSeconds ---
    val maxSamples = maxSeconds * sampleRate
    if (monoSamples.size > maxSamples) {
      Log.d(TAG, "Trimming clip from ${monoSamples.size} samples to $maxSamples samples.")
      monoSamples = monoSamples.copyOfRange(0, maxSamples)
    }

    val monoByteBuffer = ByteBuffer.allocate(monoSamples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    monoByteBuffer.asShortBuffer().put(monoSamples)
    return AudioClip(audioData = monoByteBuffer.array(), sampleRate = sampleRate)
  } catch (e: Exception) {
    Log.e(TAG, "Failed to convert wav to mono", e)
    return null
  }
}

/** Converts 8-bit unsigned PCM audio data to 16-bit signed PCM. */
private fun convert8BitTo16Bit(eightBitData: ByteArray): ByteArray {
  // The new 16-bit data will be twice the size
  val sixteenBitData = ByteArray(eightBitData.size * 2)
  val buffer = ByteBuffer.wrap(sixteenBitData).order(ByteOrder.LITTLE_ENDIAN)

  for (byte in eightBitData) {
    // Convert the unsigned 8-bit byte (0-255) to a signed 16-bit short (-32768 to 32767)
    // 1. Get the unsigned value by masking with 0xFF
    // 2. Subtract 128 to center the waveform around 0 (range becomes -128 to 127)
    // 3. Scale by 256 to expand to the 16-bit range
    val unsignedByte = byte.toInt() and 0xFF
    val sixteenBitSample = ((unsignedByte - 128) * 256).toShort()
    buffer.putShort(sixteenBitSample)
  }
  return sixteenBitData
}

/** Resamples PCM audio data from an original sample rate to a target sample rate. */
private fun resample(
  inputSamples: ShortArray,
  originalSampleRate: Int,
  targetSampleRate: Int,
  channels: Int,
): ShortArray {
  if (originalSampleRate == targetSampleRate) {
    return inputSamples
  }

  val ratio = targetSampleRate.toDouble() / originalSampleRate
  val outputLength = (inputSamples.size * ratio).toInt()
  val resampledData = ShortArray(outputLength)

  if (channels == 1) { // Mono
    for (i in resampledData.indices) {
      val position = i / ratio
      val index1 = floor(position).toInt()
      val index2 = index1 + 1
      val fraction = position - index1

      val sample1 = if (index1 < inputSamples.size) inputSamples[index1].toDouble() else 0.0
      val sample2 = if (index2 < inputSamples.size) inputSamples[index2].toDouble() else 0.0

      resampledData[i] = (sample1 * (1 - fraction) + sample2 * fraction).toInt().toShort()
    }
  }

  return resampledData
}

fun calculatePeakAmplitude(buffer: ByteArray, bytesRead: Int): Int {
  // Wrap the byte array in a ByteBuffer and set the order to little-endian
  val shortBuffer =
    ByteBuffer.wrap(buffer, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

  var maxAmplitude = 0
  // Iterate through the short buffer to find the maximum absolute value
  while (shortBuffer.hasRemaining()) {
    val currentSample = abs(shortBuffer.get().toInt())
    if (currentSample > maxAmplitude) {
      maxAmplitude = currentSample
    }
  }
  return maxAmplitude
}

fun decodeSampledBitmapFromUri(context: Context, uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
  // First, decode with inJustDecodeBounds=true to check dimensions
  val options =
    BitmapFactory.Options().apply {
      inJustDecodeBounds = true
      (if (uri.scheme == null || uri.scheme == "file") {
          FileInputStream(uri.path ?: "")
        } else {
          context.contentResolver.openInputStream(uri)
        })
        ?.use { BitmapFactory.decodeStream(it, null, this) }

      // Calculate inSampleSize
      inSampleSize = calculateInSampleSize(this, reqWidth, reqHeight)

      // Decode bitmap with inSampleSize set
      inJustDecodeBounds = false
    }

  return (if (uri.scheme == null || uri.scheme == "file") {
      FileInputStream(uri.path ?: "")
    } else {
      context.contentResolver.openInputStream(uri)
    })
    ?.use { BitmapFactory.decodeStream(it, null, options) }
}

fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
  val matrix = Matrix()
  when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1.0f, 1.0f)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1.0f, -1.0f)
    ExifInterface.ORIENTATION_TRANSPOSE -> {
      matrix.postRotate(90f)
      matrix.preScale(-1.0f, 1.0f)
    }
    ExifInterface.ORIENTATION_TRANSVERSE -> {
      matrix.postRotate(270f)
      matrix.preScale(-1.0f, 1.0f)
    }
    ExifInterface.ORIENTATION_NORMAL -> return bitmap
    else -> return bitmap
  }
  return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun calculateInSampleSize(
  options: BitmapFactory.Options,
  reqWidth: Int,
  reqHeight: Int,
): Int {
  // Raw height and width of image
  val height: Int = options.outHeight
  val width: Int = options.outWidth
  var inSampleSize = 1

  if (height > reqHeight || width > reqWidth) {
    // Calculate the ratio of height and width to the requested height and width
    val heightRatio = (height.toFloat() / reqHeight.toFloat()).roundToInt()
    val widthRatio = (width.toFloat() / reqWidth.toFloat()).roundToInt()

    // Choose the largest ratio as inSampleSize value to ensure
    // that both dimensions are smaller than or equal to the requested dimensions.
    inSampleSize = max(heightRatio, widthRatio)
  }

  return inSampleSize
}

fun readFileToByteBuffer(file: File): ByteBuffer? {
  return try {
    val fileInputStream = FileInputStream(file)
    val fileChannel: FileChannel = fileInputStream.channel
    val byteBuffer = ByteBuffer.allocateDirect(fileChannel.size().toInt())
    fileChannel.read(byteBuffer)
    byteBuffer.rewind()
    fileInputStream.close()
    byteBuffer
  } catch (e: Exception) {
    e.printStackTrace()
    null
  }
}

fun isPixel10(): Boolean {
  return Build.MODEL != null && Build.MODEL.lowercase().contains("pixel 10")
}

fun isPixelDevice(): Boolean {
  return Build.MODEL != null && Build.MODEL.lowercase().contains("pixel")
}

fun Modifier.clearFocusOnKeyboardDismiss(): Modifier = composed {
  var isFocused by remember { mutableStateOf(false) }
  var keyboardAppearedSinceLastFocused by remember { mutableStateOf(false) }

  if (isFocused) {
    val imeIsVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val focusManager = LocalFocusManager.current

    LaunchedEffect(imeIsVisible) {
      if (imeIsVisible) {
        keyboardAppearedSinceLastFocused = true
      } else if (keyboardAppearedSinceLastFocused) {
        focusManager.clearFocus()
      }
    }
  }

  onFocusEvent {
    if (isFocused != it.isFocused) {
      isFocused = it.isFocused
      if (isFocused) keyboardAppearedSinceLastFocused = false
    }
  }
}

fun isAICoreSupported(allowedDeviceModels: Set<String>?): Boolean {
  if (allowedDeviceModels.isNullOrEmpty()) {
    Log.w(TAG, "isAICoreSupported: allowedDeviceModels is null or empty")
    return false
  }
  val currentModel = android.os.Build.MODEL?.lowercase()
  if (currentModel == null) {
    Log.w(TAG, "isAICoreSupported: current device model is null")
    return false
  }
  val supported = allowedDeviceModels.contains(currentModel)
  if (!supported) {
    Log.w(
      TAG,
      "isAICoreSupported: device model '$currentModel' is not in the allowed list: $allowedDeviceModels",
    )
  }
  return supported
}

fun convertStringToJsonObject(jsonString: String): JsonObject {
  return try {
    JsonParser.parseString(jsonString).asJsonObject
  } catch (e: Exception) {
    JsonObject()
  }
}
