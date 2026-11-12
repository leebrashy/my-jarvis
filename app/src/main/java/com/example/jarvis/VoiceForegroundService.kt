package com.example.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Persistent microphone foreground service. Start it while JARVIS is visible;
 * Android keeps the microphone service alive when the Activity is backgrounded.
 */
class VoiceForegroundService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_START = "com.example.jarvis.action.START_VOICE"
        const val ACTION_STOP = "com.example.jarvis.action.STOP_VOICE"
        private const val CHANNEL_ID = "jarvis_voice_channel"
        private const val NOTIFICATION_ID = 7001
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ai: LocalAiManager? = null
    private var listening = false
    private var commandMode = false
    private var speaking = false
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = TextToSpeech(this, this)
        ai = LocalAiManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification("Listening for the wake phrase")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (!listening && !speaking) scheduleListen(500)
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "JARVIS voice standby", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Shows when JARVIS is listening for its wake phrase"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, VoiceForegroundService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openIntent = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("JARVIS is on standby")
            .setContentText(text)
            .setContentIntent(openPending(openIntent))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "Stop listening", stopPending)
            .build()
    }

    private fun openPending(pending: PendingIntent) = pending

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun scheduleListen(delay: Long = 350) {
        if (destroyed || speaking) return
        handler.removeCallbacks(listenRunnable)
        handler.postDelayed(listenRunnable, delay)
    }

    private val listenRunnable = Runnable { beginRecognition() }

    private fun beginRecognition() {
        if (destroyed || speaking || listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Speech recognition is unavailable on this phone")
            scheduleListen(5000)
            return
        }
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    listening = true
                    updateNotification(if (commandMode) "Listening for your command" else "Listening for “JARVIS”")
                }
                override fun onBeginningOfSpeech() {
                    updateNotification(if (commandMode) "Hearing your command" else "Voice detected")
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { listening = false; updateNotification("Processing speech") }
                override fun onError(error: Int) {
                    listening = false
                    scheduleListen(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200 else 650)
                }
                override fun onResults(results: android.os.Bundle?) {
                    listening = false
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    processRecognised(spoken)
                }
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
            val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            recognizer?.startListening(request)
        } catch (_: Exception) {
            listening = false
            scheduleListen(1200)
        }
    }

    private fun processRecognised(text: String) {
        val phrase = text.trim()
        if (!commandMode) {
            val idx = phrase.lowercase(Locale.UK).indexOf("jarvis")
            if (idx >= 0) {
                val rest = phrase.substring(idx + 6).trim().trim(',', '.', '!', '?')
                commandMode = true
                if (rest.isNotBlank()) answer(rest) else speak("Yes, I'm listening.", "wake_ack")
            } else scheduleListen(250)
        } else if (phrase.isNotBlank()) {
            answer(phrase)
        } else {
            commandMode = false
            scheduleListen(350)
        }
    }

    private fun answer(prompt: String) {
        updateNotification("Thinking locally")
        val manager = ai
        if (manager == null || !manager.isDownloaded()) {
            speak("My local intelligence has not been downloaded yet. Please open JARVIS and complete the one-time setup.", "jarvis_reply")
            return
        }
        manager.load(
            onReady = {
                manager.ask(prompt,
                    onAnswer = { reply -> handler.post { speak(reply, "jarvis_reply") } },
                    onError = { error -> handler.post { speak("I encountered a problem. $error", "jarvis_reply") } }
                )
            },
            onError = { error -> handler.post { speak("I could not start my local intelligence. $error", "jarvis_reply") } }
        )
    }

    private fun speak(text: String, id: String) {
        val engine = tts
        if (engine == null || !ttsReady) {
            updateNotification(text.take(100))
            commandMode = false
            scheduleListen(900)
            return
        }
        speaking = true
        listening = false
        try { recognizer?.cancel() } catch (_: Exception) {}
        updateNotification(if (id == "wake_ack") "Waiting for your command" else "Speaking response")
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    @Volatile private var ttsReady = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            val voice = tts?.voices
                ?.filter { it.locale.language == "en" && it.locale.country == "GB" && !it.isNetworkConnectionRequired }
                ?.firstOrNull { it.name.contains("male", true) || it.name.contains("rjs", true) }
            if (voice != null) tts?.voice = voice else tts?.language = Locale.UK
            tts?.setPitch(0.84f)
            tts?.setSpeechRate(0.88f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    handler.post {
                        speaking = false
                        if (utteranceId == "wake_ack") {
                            commandMode = true
                            updateNotification("Waiting for your command")
                        } else {
                            commandMode = false
                            updateNotification("Listening for “JARVIS”")
                        }
                        scheduleListen(500)
                    }
                }
                override fun onError(utteranceId: String?) {
                    handler.post { speaking = false; commandMode = false; scheduleListen(700) }
                }
            })
            scheduleListen(700)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The foreground service is intentionally independent of the Activity task.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ai?.close()
        ai = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
