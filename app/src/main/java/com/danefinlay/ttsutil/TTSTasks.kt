/*
 * TTS Util
 *
 * Authors: Dane Finlay <dane@danefinlay.net>
 *
 * Copyright (C) 2019 Dane Finlay
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

package com.danefinlay.ttsutil

import android.content.Context
import android.media.AudioManager
import android.net.MailTo
import android.net.ParseException
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.*
import android.speech.tts.TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID
import android.speech.tts.UtteranceProgressListener
import androidx.annotation.CallSuper
import androidx.preference.PreferenceManager
import org.jetbrains.anko.*
import java.io.*
import java.net.MalformedURLException
import java.net.URL
import java.util.*

abstract class MyUtteranceProgressListener(ctx: Context, val tts: TextToSpeech) :
    UtteranceProgressListener() {

    val app = ctx.applicationContext as ApplicationEx

    fun displayMessage(string: String, long: Boolean) {
        app.runOnUiThread {
            if (long) longToast(string)
            else toast(string)
        }
    }

    fun displayMessage(id: Int, long: Boolean) {
        app.runOnUiThread {
            if (long) longToast(id)
            else toast(id)
        }
    }

    @CallSuper
    open fun begin(): Boolean {
        tts.setOnUtteranceProgressListener(this)
        return true
    }

    @CallSuper
    open fun finish(success: Boolean): Boolean {
        tts.setOnUtteranceProgressListener(null)
        return success
    }
}


abstract class TTSTask(
    ctx: Context, tts: TextToSpeech,
    private val inputStream: InputStream,
    private val inputSize: Long,
    private val taskId: Int,
    private val observer: TaskProgressObserver
) :
    MyUtteranceProgressListener(ctx, tts), Task {

    private val streamReader: Reader = inputStream.reader().buffered(maxInputLength)
    protected val utteranceBytesQueue: MutableList<Int> =
        Collections.synchronizedList(mutableListOf<Int>())

    private val scaleSilenceToRate: Boolean
    private val speechRate: Float
    private val delimitersToSilenceMap: Map<Int, Long>
    private val endOfTextDelimiters: Set<Int>

    private val filterHashes: Boolean
    private val filterWebLinks: Boolean
    private val filterMailToLinks: Boolean
    private val filtersEnabled: Boolean

    // Note: Instance variables may be accessed by many (at least three) threads.
    // Hence, we use Java's "volatile" mechanism.
    @Volatile
    protected var finalize: Boolean = false

    @Volatile
    protected var inputProcessed: Long = 0

    @Volatile
    private var inputFiltered: Long = 0

    @Volatile
    private var streamHasFurtherInput: Boolean = true

    abstract fun enqueueText(text: CharSequence, bytesRead: Int)
    abstract fun enqueueSilence(durationInMs: Long)

    init {
        // Retrieve values from shared preferences.
        // Note: This cannot be done from the binder threads which invoke utterance
        // progress listener callbacks.
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        scaleSilenceToRate = prefs.getBoolean("pref_scale_silence_to_rate", false)
        speechRate = prefs.getFloat("pref_tts_speech_rate", 1.0f)
        val silLF = prefs.getString("pref_silence_line_endings", "200")!!.toLong()
        val sentenceSil = prefs.getString("pref_silence_sentences", "0")!!.toLong()
        val questionSil = prefs.getString("pref_silence_questions", "0")!!.toLong()
        val excSil = prefs.getString("pref_silence_exclamations", "0")!!.toLong()

        // Set the delimiters to silence map, aliasing the Unicode "halfwidth" and
        // "fullwidth" forms as necessary.
        delimitersToSilenceMap = mapOf(
            // Line Feed (\n).
            0x000a to silLF,

            // Sentences:
            // Full stops.                  Ellipses.
            0x002e to sentenceSil, 0x2026 to sentenceSil,
            0xff0e to sentenceSil,
            0xff61 to sentenceSil,

            // Questions.                   Exclamations.
            0x003f to questionSil, 0x0021 to excSil,
            0xff1f to questionSil, 0xff01 to excSil,
        )

        // The set of end-of-text delimiters includes all non-whitespace delimiters.
        val endOfTextDelimiters = delimitersToSilenceMap.keys.toMutableSet()
        endOfTextDelimiters.remove(0x000a)
        this.endOfTextDelimiters = endOfTextDelimiters

        // Set variables related to input filtering.
        filterHashes = prefs.getBoolean("pref_filter_hash", false)
        filterWebLinks = prefs.getBoolean("pref_filter_web_links", false)
        filterMailToLinks = prefs.getBoolean("pref_filter_mailto_links", false)
        filtersEnabled = filterHashes || filterWebLinks || filterMailToLinks
    }

    override fun begin(): Boolean {
        if (!super.begin()) return false

        // Notify the progress listener that work has begun.
        observer.notifyProgress(0, taskId, 0)

        // Enqueue the first input bytes, catching any IO exceptions.  This
        // jumpstarts the processing of the entire stream.
        try {
            streamHasFurtherInput = enqueueNextInput()
            if (!streamHasFurtherInput && utteranceBytesQueue.size == 0) {
                finish(true)
            }
        } catch (exception: IOException) {
            return finish(false)
        }
        return true
    }

    private fun filterChar(char: Char): Boolean {
        // Check for hash, if appropriate.
        return filterHashes && char.code == 0x23
    }

    private fun filterWord(word: String): Boolean {
        var result = false

        // Check for web hyperlinks, if appropriate.
        if (filterWebLinks) {
            // Check prefixes first as an optimization.
            val lowerCaseWord = word.lowercase(Locale.ROOT)
            for (prefix in listOf("http://", "https://")) {
                result = lowerCaseWord.startsWith(prefix)
                if (result) break
            }

            // If there is a match, try to parse the string using Java's URL class,
            // which is imperfect, but good enough for this use case.
            if (result) {
                result = try {
                    URL(lowerCaseWord); true
                } catch (e: MalformedURLException) {
                    false
                }
            }
        }

        // Check for mailto hyperlinks, if appropriate.
        if (!result && filterMailToLinks) {
            val lowerCaseWord = word.lowercase(Locale.ROOT)
            result = try {
                MailTo.parse(lowerCaseWord); true
            } catch (e: ParseException) {
                false
            }
        }
        return result
    }

    private fun applyTextFilters(buffer: ArrayList<Char>) {
        val initialBufferSize = buffer.size
        val stringBuilder = StringBuilder()
        var markedIndices = mutableListOf<Int>()

        // Mark the index of each character to filter out.
        for ((index, char) in buffer.withIndex()) {
            // Read until we reach a word boundary or the last character.
            // Filter characters, if appropriate.
            val charNotWhitespace = !char.isWhitespace()
            if (charNotWhitespace) {
                stringBuilder.append(char)
                if (filterChar(char)) markedIndices.add(index)
                if (index < buffer.lastIndex) continue
            }

            // We have reached a word boundary.  Filter out the word, if
            // appropriate.
            val word = stringBuilder.toString()
            if (filterWord(word)) {
                var wordIndexZero = index - word.length
                if (charNotWhitespace) wordIndexZero++ // Include the last char.
                word.indices.forEach { i -> markedIndices.add(wordIndexZero + i) }
            }
            stringBuilder.clear()
        }

        // Remove repeated indices and sort the list.
        markedIndices = markedIndices.toMutableSet().toMutableList()
        markedIndices.sort()

        // Remove filtered characters from the buffer in reverse order.
        var index = markedIndices.size - 1
        while (index >= 0) {
            buffer.removeAt(markedIndices[index])
            index--
        }

        // Done.
        inputFiltered += initialBufferSize - buffer.size
    }

    private fun enqueueNextInput(): Boolean {
        val buffer = ArrayList<Char>()
        var bytesRead = 0

        // Read characters until we hit an appropriate delimiter with a positive
        // silence duration or until an appropriate whitespace character is found
        // near the maximum input length -- whichever comes first.
        var byte = streamReader.read()
        var silenceDuration = 0L
        while (byte >= 0) {
            bytesRead++

            // Only add regular characters, not ones which are to be replaced with
            // silence.
            val char = byte.toChar()
            silenceDuration = delimitersToSilenceMap[char.code] ?: 0L
            if (silenceDuration == 0L) buffer.add(char)
            else if (silenceDuration > 0) {
                // There should be at least one non-delimiting, non-whitespace
                // character before this one for it to count as a delimiter.
                // This rule prevents silence from being inserted for, e.g.,
                // successive question marks, which methinks would be a poor
                // imitation of human speech.
                if (byte in endOfTextDelimiters) {
                    val lastChar = buffer.lastOrNull()
                    if (lastChar != null && !lastChar.isWhitespace() &&
                        lastChar.code !in delimitersToSilenceMap
                    ) break
                }

                // This is a whitespace delimiter: break.
                else break
            }

            if (buffer.size >= lineFeedScanThreshold && byte == 0x0a) break
            if (buffer.size >= whitespaceScanThreshold && char.isWhitespace()) break
            if (buffer.size == maxInputLength) break

            byte = streamReader.read()
        }

        // Return early if no bytes were read (end of stream).
        if (bytesRead == 0) return false

        // Process the buffer.
        // If text filters are enabled, apply them.
        if (filtersEnabled) applyTextFilters(buffer)

        // Enqueue the final string.
        val stringBuilder = StringBuilder()
        for (i in 0 until buffer.size) stringBuilder.append(buffer[i])
        enqueueText(stringBuilder.toString(), bytesRead)

        // Enqueue silence, if necessary.  Scale silence by the speech rate, if
        // appropriate.
        if (silenceDuration > 0L) {
            if (scaleSilenceToRate) {
                silenceDuration = (silenceDuration / speechRate).toLong()
            }
            enqueueSilence(silenceDuration)
        }

        // Return whether there is further input.
        return byte >= 0
    }

    override fun finish(success: Boolean): Boolean {
        // Close the reader and input stream.
        streamReader.close()
        inputStream.close()

        // Notify the progress observer that the task is finished.
        val progress = if (success) 100 else -1
        observer.notifyProgress(progress, taskId, 0)

        // If any input was filtered, display a toast message.
        if (inputFiltered > 0) {
            val message = app.getString(
                R.string.filtered_characters_message,
                inputFiltered, app.resources.getQuantityString(
                    R.plurals.characters, inputFiltered.toInt()
                )
            )
            displayMessage(message, false)
        }

        // Call the super method.
        return super.finish(success)
    }

    override fun onStart(utteranceId: String?) {}

    override fun onError(utteranceId: String?) { // deprecated
        handleProcessingError(-1)
    }

    override fun onError(utteranceId: String?, errorCode: Int) {
        handleProcessingError(errorCode)
    }

    private fun handleProcessingError(errorCode: Int) {
        // Get the matching error message string for errorCode.
        val errorMsg = when (errorCode) {
            ERROR_SYNTHESIS -> R.string.synthesis_error_msg_synthesis
            ERROR_SERVICE -> R.string.synthesis_error_msg_service
            ERROR_OUTPUT -> R.string.synthesis_error_msg_output
            ERROR_NETWORK -> R.string.synthesis_error_msg_network
            ERROR_NETWORK_TIMEOUT -> R.string.synthesis_error_msg_net_timeout
            ERROR_INVALID_REQUEST -> R.string.synthesis_error_msg_invalid_req
            ERROR_NOT_INSTALLED_YET -> R.string.synthesis_error_msg_voice_data
            else -> R.string.synthesis_error_msg_generic
        }

        // Display the error message.
        displayMessage(errorMsg, true)

        // Finish.
        finalize()
        finish(false)
    }

    override fun onStop(utteranceId: String?, interrupted: Boolean) {
        super.onStop(utteranceId, interrupted)
        finish(false)
    }

    override fun onDone(utteranceId: String?) {
        // Add processed bytes to *inputProcessed*.
        if (utteranceBytesQueue.size > 0) {
            inputProcessed += utteranceBytesQueue.removeAt(0)
        }

        // Call finish() if we have reached the end of the stream or have been
        // requested to finalize.
        val success = !streamHasFurtherInput
        if (success || finalize) {
            finish(success)
            return
        }

        // Calculate progress and notify the progress listener.
        // Note: progress=[-1, 100] is dispatched by the finish() method.
        val progress = (inputProcessed.toFloat() / inputSize * 100).toInt()
        if (progress in 0..99) observer.notifyProgress(progress, taskId, 0)

        // Enqueue the next input.
        try {
            streamHasFurtherInput = enqueueNextInput()
            if (!streamHasFurtherInput && utteranceBytesQueue.size == 0) {
                finish(true)
            }
        } catch (exception: IOException) {
            finish(false)
        }
    }

    override fun finalize() {
        finalize = true
    }

    companion object {
        private val maxInputLength: Int = when {
            // Use a value appropriate on this Android version.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 -> {
                // Note: This value appears to be interpreted by TTS engines as a
                // sort of maximum index.  TTS Util too will interpret it thus.
                // The input processing logic will most likely find a line feed or
                // another whitespace character before hitting the absolute maximum.
                getMaxSpeechInputLength() - 1
            }

            else -> {
                // Note: This value seems to work all right with Pico TTS on Android
                // 4.0.3 (SDK 15).
                4000 - 1
            }
        }

        private val lineFeedScanThreshold: Int = (maxInputLength * 0.70).toInt()
        private val whitespaceScanThreshold: Int = (maxInputLength * 0.9).toInt()

        private var currentUtteranceId: Long = 0

        fun nextUtteranceId(): String = "${currentUtteranceId++}"
    }
}


class ReadInputTask(
    ctx: Context, tts: TextToSpeech, inputStream: InputStream,
    inputSize: Long, private val queueMode: Int,
    observer: TaskProgressObserver
) :
    TTSTask(
        ctx, tts, inputStream, inputSize, TASK_ID_READ_TEXT,
        observer
    ) {

    override fun begin(): Boolean {
        // Request audio focus.  Finish early if our request was denied.
        // Otherwise, call the super method.
        if (!app.requestAudioFocus()) return finish(false)
        return super.begin()
    }

    override fun enqueueSilence(durationInMs: Long) {
        if (durationInMs == 0L) return

        // The queue mode initially specified for the task is ignored here; it only
        // makes sense to use QUEUE_ADD for silence.
        val uttId = nextUtteranceId()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.playSilentUtterance(durationInMs, QUEUE_ADD, uttId)
        } else {
            val params = HashMap<String, String>()
            params[KEY_PARAM_UTTERANCE_ID] = uttId
            @Suppress("deprecation")
            tts.playSilence(durationInMs, QUEUE_ADD, params)
        }
    }

    override fun enqueueText(text: CharSequence, bytesRead: Int) {
        // Add *bytesRead* to the queue.
        utteranceBytesQueue.add(bytesRead)

        // Add text to the queue as an utterance.
        // Note: This is necessary even when the text is blank because progress
        // callbacks are used to process the input stream in order.
        val uttId = nextUtteranceId()
        val audioStream: Int = AudioManager.STREAM_MUSIC
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val bundle = Bundle()
            bundle.putInt(Engine.KEY_PARAM_STREAM, audioStream)
            @Suppress("deprecation")
            tts.speak(text, queueMode, bundle, uttId)
        } else {
            val params = HashMap<String, String>()
            params[KEY_PARAM_UTTERANCE_ID] = uttId
            params[Engine.KEY_PARAM_STREAM] = "$audioStream"
            @Suppress("deprecation")
            tts.speak(text.toString(), queueMode, params)
        }
    }

    override fun onDone(utteranceId: String?) {
        // Note: This function may be called before an utterance is completely
        // processed.  This appears to be related to our use of silent utterances.
        // With this in mind, wait until the utterance is *really* done before we
        // proceed.
        while (tts.isSpeaking) Thread.sleep(5)
        super.onDone(utteranceId)
    }

    override fun finish(success: Boolean): Boolean {
        app.releaseAudioFocus()
        return super.finish(success)
    }
}

class FileSynthesisTask(
    ctx: Context, tts: TextToSpeech,
    inputStream: InputStream, inputSize: Long,
    private val waveFilename: String,
    private val inWaveFiles: MutableList<File>,
    progressObserver: TaskProgressObserver
) :
    TTSTask(
        ctx, tts, inputStream, inputSize,
        TASK_ID_WRITE_FILE, progressObserver
    ) {

    override fun begin(): Boolean {
        // Delete silent wave files because they may be incompatible with current
        // user settings.
        val workingDirectoryFiles = getWorkingDirectory().listFiles() ?: arrayOf()
        for (file in workingDirectoryFiles) {
            if (file.name.endsWith("ms_sil.wav")) file.delete()
        }

        if (!super.begin()) return false
        return true
    }

    private fun getWorkingDirectory(): File {
        var dir: File

        // Use the application's files directory by default.
        dir = app.filesDir

        // Use external storage on older Android versions.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            val externalFilesDir = app.getExternalFilesDir(null)
            if (externalFilesDir != null && externalFilesDir.canWrite()) {
                dir = externalFilesDir
            } else {
                @Suppress("deprecation")
                dir = Environment.getExternalStorageDirectory()
            }
        }
        return dir
    }

    override fun enqueueSilence(durationInMs: Long) {
        if (durationInMs == 0L) return

        // Android's text-to-speech framework does not allow adding silence to wave
        // files, so we add references to special wave files that contain Xms of
        // silence.  These files are created later.
        val suffix = "ms_sil.wav"
        var filename: String = "$durationInMs$suffix"

        // Add this silence to the last file as an optimisation, if possible.
        if (inWaveFiles.size > 0) {
            val lastFilename = inWaveFiles.last().name
            if (lastFilename.endsWith(suffix)) {
                val lastFileDuration = lastFilename.substringBefore(suffix).toInt()
                filename = "${lastFileDuration + durationInMs}$suffix"
                inWaveFiles.removeAt(inWaveFiles.lastIndex)
            }
        }
        val file: File = File(getWorkingDirectory(), filename)
        inWaveFiles.add(file)
    }

    override fun enqueueText(text: CharSequence, bytesRead: Int) {
        // Add *bytesRead* to the queue.
        utteranceBytesQueue.add(bytesRead)

        // Create a wave file for this utterance and enqueue file synthesis.
        // Note: This is necessary even when the text is blank because progress
        // callbacks are used to process the input stream in order.
        val uttId = nextUtteranceId()
        val dir: File = getWorkingDirectory()
        val file: File = File.createTempFile("speech", ".wav", dir)
        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.synthesizeToFile(text, null, file, uttId)
        } else {
            val params = HashMap<String, String>()
            params[KEY_PARAM_UTTERANCE_ID] = uttId
            @Suppress("deprecation")
            tts.synthesizeToFile(text.toString(), params, file.absolutePath)
        }

        // If successful and the text is not empty, add the file to the list.
        if (success == SUCCESS && text.length > 0) inWaveFiles.add(file)
    }

    override fun finish(success: Boolean): Boolean {
        // Delete wave files and display a toast message and on failure.
        if (!success) {
            for (f in inWaveFiles) {
                if (f.isFile && f.canWrite()) f.delete()
            }
            val messageId = R.string.write_to_file_message_failure
            val message = app.getString(messageId, waveFilename)
            displayMessage(message, true)
        }

        // Call the super method.
        return super.finish(success)
    }
}
