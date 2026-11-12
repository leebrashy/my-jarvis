package com.example.jarvis

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Free, local JARVIS language model. The model is downloaded once and remains
 * in the app's private storage. All LiteRT-LM engine/conversation operations
 * are owned by one worker thread.
 */
class LocalAiManager(context: Context) {
    companion object {
        const val MODEL_BYTES = 964_657_152L
        const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen3.5-0.8B/resolve/main/Qwen3.5-0.8B_int8.litertlm"
    }

    private val appContext = context.applicationContext
    private val modelFile = File(appContext.filesDir, "jarvis-qwen35-08b-cpu.litertlm")
    private val client = OkHttpClient.Builder().build()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    @Volatile private var loading = false

    fun isDownloaded(): Boolean = modelFile.exists() && modelFile.length() > 100_000_000L
    fun isReady(): Boolean = conversation != null
    fun isLoading(): Boolean = loading

    fun download(
        onProgress: (Long, Long) -> Unit,
        onComplete: (Boolean, String?) -> Unit
    ) {
        worker.execute {
            try {
                if (isDownloaded()) {
                    onComplete(true, null)
                    return@execute
                }
                val request = Request.Builder().url(MODEL_URL).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Model download failed (HTTP ${response.code}).")
                    val body = response.body ?: error("The model download was empty.")
                    val total = if (body.contentLength() > 0) body.contentLength() else MODEL_BYTES
                    val temp = File(appContext.filesDir, "jarvis-model.tmp")
                    body.byteStream().use { input ->
                        FileOutputStream(temp).use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            var done = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                done += count
                                onProgress(done, total)
                            }
                            output.fd.sync()
                        }
                    }
                    if (temp.length() < 900_000_000L) {
                        temp.delete()
                        error("The downloaded model file is incomplete.")
                    }
                    if (modelFile.exists()) modelFile.delete()
                    if (!temp.renameTo(modelFile)) {
                        temp.copyTo(modelFile, overwrite = true)
                        temp.delete()
                    }
                }
                onComplete(true, null)
            } catch (e: Exception) {
                File(appContext.filesDir, "jarvis-model.tmp").delete()
                onComplete(false, e.message ?: "Unable to download the AI model.")
            }
        }
    }

    fun load(onReady: () -> Unit, onError: (String) -> Unit) {
        worker.execute {
            if (conversation != null) {
                onReady()
                return@execute
            }
            if (loading) return@execute
            loading = true
            try {
                if (!isDownloaded()) error("Download the JARVIS AI model first.")
                val newEngine = Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = File(appContext.cacheDir, "litert-cache").absolutePath
                    )
                )
                newEngine.initialize()
                val config = ConversationConfig(
                    systemInstruction = Contents.of(
                        "You are JARVIS, a personal AI assistant. Speak in polished, natural British English. " +
                        "Be calm, discreet, intelligent, concise and helpful, with a subtle dry wit when appropriate. " +
                        "Address the user naturally. Never claim to control a device, access the internet, or perform an action unless the app actually provides that ability. " +
                        "You are running locally on the user's phone and may not have current information."
                    )
                )
                val newConversation = newEngine.createConversation(config)
                engine = newEngine
                conversation = newConversation
                loading = false
                onReady()
            } catch (e: Exception) {
                loading = false
                onError(e.message ?: "The on-device AI could not start.")
            }
        }
    }

    fun ask(prompt: String, onAnswer: (String) -> Unit, onError: (String) -> Unit) {
        worker.execute {
            try {
                val active = conversation ?: error("JARVIS AI is not loaded yet.")
                val answer = active.sendMessage(prompt).toString().trim()
                onAnswer(answer.ifBlank { "I beg your pardon, I couldn't formulate a response." })
            } catch (e: Exception) {
                onError(e.message ?: "The local AI encountered an error.")
            }
        }
    }

    fun close() {
        worker.execute {
            try { conversation?.close() } catch (_: Exception) {}
            conversation = null
            try { engine?.close() } catch (_: Exception) {}
            engine = null
            worker.shutdown()
        }
    }
}
