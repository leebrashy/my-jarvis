package com.example.jarvis
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val bg = Color.rgb(2, 7, 18)
    private val panel = Color.rgb(5, 19, 36)
    private val cyan = Color.rgb(48, 211, 255)
    private val pale = Color.rgb(213, 240, 255)
    private val dim = Color.rgb(87, 139, 169)
    private lateinit var transcript: TextView
    private lateinit var transcriptScroll: ScrollView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var tts: TextToSpeech
    private var speech: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var appActive = false
    private var wakeMode = true
    private var recognitionBusy = false
    private lateinit var localAi: LocalAiManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        tts = TextToSpeech(this, this)
        localAi = LocalAiManager(this)
        buildUi()
        append("JARVIS", "Good evening. All primary systems are standing by. Say JARVIS to begin.")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 41)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun cardBg(): GradientDrawable = GradientDrawable().apply {
        setColor(panel); cornerRadius = dp(13).toFloat(); setStroke(dp(1), Color.rgb(17, 75, 108))
    }
    private fun smallLabel(value: String): TextView = TextView(this).apply {
        text = value; textSize = 9f; setTextColor(dim); letterSpacing = .12f
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; setBackgroundColor(bg) }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(17), dp(13), dp(17), dp(18)); setBackgroundColor(bg)
        }
        scroll.addView(page, ViewGroup.LayoutParams(-1, -2))

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "J.A.R.V.I.S."; textSize = 23f; letterSpacing = .20f; setTextColor(cyan)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, dp(43), 1f))
        header.addView(TextView(this).apply {
            text = "●  SYSTEMS ACTIVE"; textSize = 9f; letterSpacing = .08f; setTextColor(cyan); gravity = Gravity.CENTER
        })
        page.addView(header)
        page.addView(smallLabel("PERSONAL ARTIFICIAL INTELLIGENCE  /  MARK VII INTERFACE"), LinearLayout.LayoutParams(-1, dp(22)))
        page.addView(View(this).apply { setBackgroundColor(Color.rgb(18, 91, 126)) }, LinearLayout.LayoutParams(-1, dp(1)))

        val telemetry = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        telemetry.addView(infoTile("CORE", "ONLINE"), LinearLayout.LayoutParams(0, dp(55), 1f).apply { rightMargin = dp(5) })
        telemetry.addView(infoTile("VOICE", "ACTIVE"), LinearLayout.LayoutParams(0, dp(55), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        telemetry.addView(infoTile("NETWORK", "READY"), LinearLayout.LayoutParams(0, dp(55), 1f).apply { leftMargin = dp(5) })
        page.addView(telemetry, LinearLayout.LayoutParams(-1, dp(63)).apply { topMargin = dp(8) })

        val display = FrameLayout(this).apply { background = cardBg() }
        display.addView(HologramView(), FrameLayout.LayoutParams(-1, dp(280)))
        display.addView(TextView(this).apply {
            text = "J A R V I S"; textSize = 18f; letterSpacing = .28f; gravity = Gravity.CENTER
            setTextColor(pale); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }, FrameLayout.LayoutParams(-1, dp(280)))
        page.addView(display, LinearLayout.LayoutParams(-1, dp(280)).apply { topMargin = dp(12) })

        status = TextView(this).apply {
            text = "SAY  “JARVIS”  TO ACTIVATE"; textSize = 10f; letterSpacing = .14f
            gravity = Gravity.CENTER; setTextColor(cyan)
        }
        page.addView(status, LinearLayout.LayoutParams(-1, dp(35)))

        val logCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg(); setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        logCard.addView(smallLabel("COMMUNICATION CHANNEL  /  LIVE TRANSCRIPT"))
        transcript = TextView(this).apply {
            textSize = 13f; setTextColor(pale); setLineSpacing(dp(3).toFloat(), 1f); setPadding(0, dp(7), 0, dp(5))
        }
        val logScroll = ScrollView(this).apply {
            isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER
            addView(transcript, ViewGroup.LayoutParams(-1, -2))
        }
        transcriptScroll = logScroll
        logCard.addView(logScroll, LinearLayout.LayoutParams(-1, dp(118)))
        page.addView(logCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        input = EditText(this).apply {
            hint = "Type a message to JARVIS…"; setHintTextColor(dim); setTextColor(pale); textSize = 14f
            maxLines = 3; setPadding(dp(13), dp(10), dp(13), dp(10)); background = cardBg()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, _, _ -> sendMessage(text.toString()); true }
        }
        page.addView(input, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(9) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        actions.addView(actionButton("START BACKGROUND", false) { beginVoiceMode() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(5); topMargin = dp(7) })
        actions.addView(actionButton("SEND  ➤", true) { sendMessage(input.text.toString()) },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(5); rightMargin = dp(5); topMargin = dp(7) })
        actions.addView(actionButton("⚙", false) { showSettings() },
            LinearLayout.LayoutParams(dp(55), dp(48)).apply { leftMargin = dp(5); topMargin = dp(7) })
        page.addView(actions)
        page.addView(smallLabel("BACKGROUND WAKE PHRASE  /  USE NOTIFICATION TO STOP"),
            LinearLayout.LayoutParams(-1, dp(28)).apply { topMargin = dp(8) })
        setContentView(scroll)
    }

    private fun infoTile(caption: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = cardBg()
        addView(smallLabel(caption))
        addView(TextView(this@MainActivity).apply { text = value; textSize = 10f; setTextColor(cyan); letterSpacing = .07f })
    }

    private fun actionButton(caption: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = caption; textSize = 10f; isAllCaps = false; setTextColor(if (primary) bg else cyan)
        background = GradientDrawable().apply {
            setColor(if (primary) cyan else panel); cornerRadius = dp(11).toFloat()
            setStroke(dp(1), if (primary) cyan else Color.rgb(19, 91, 124))
        }
        minHeight = 0; minimumHeight = 0; stateListAnimator = null; setOnClickListener { action() }
    }

    private fun append(who: String, message: String) {
        runOnUiThread {
            if (!::transcript.isInitialized) return@runOnUiThread
            transcript.append((if (transcript.text.isNullOrEmpty()) "" else "\n\n") + "$who\n$message")
            if (::transcriptScroll.isInitialized) transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun beginVoiceMode() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 41)
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
            return
        }
        startBackgroundService()
    }

    private fun startBackgroundService() {
        val serviceIntent = Intent(this, VoiceForegroundService::class.java).setAction(VoiceForegroundService.ACTION_START)
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
            status.text = "BACKGROUND STANDBY  /  SAY JARVIS"
            append("JARVIS", "Background voice standby has started. You can leave the app; use the persistent notification to stop listening.")
        } catch (e: Exception) {
            status.text = "COULD NOT START BACKGROUND VOICE"
            append("JARVIS", "Android prevented background voice from starting: " + (e.message ?: "permission required"))
        }
    }
    private fun startWakeListening() {
        if (!appActive || isFinishing || recognitionBusy) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { status.text = "VOICE RECOGNITION UNAVAILABLE"; return }
        wakeMode = true
        startRecognition()
    }
    private fun startCommandListening() {
        if (!appActive || isFinishing || recognitionBusy) return
        wakeMode = false; status.text = "LISTENING  /  I'M LISTENING"; startRecognition()
    }
    private fun startRecognition() {
        if (recognitionBusy || !appActive) return
        recognitionBusy = true
        try {
            speech?.destroy()
            speech = SpeechRecognizer.createSpeechRecognizer(this)
            speech?.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    recognitionBusy = false
                    status.text = if (wakeMode) "STANDBY  /  SAY  “JARVIS”" else "LISTENING…"
                }
                override fun onBeginningOfSpeech() { status.text = if (wakeMode) "VOICE DETECTED…" else "LISTENING…" }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { status.text = "ANALYSING VOICE…" }
                override fun onError(error: Int) {
                    recognitionBusy = false
                    if (appActive) handler.postDelayed({ if (wakeMode) startWakeListening() else startCommandListening() }, 500)
                }
                override fun onResults(results: Bundle?) {
                    recognitionBusy = false
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                    if (wakeMode) {
                        val index = spoken.lowercase(Locale.UK).indexOf("jarvis")
                        if (index >= 0) {
                            val command = spoken.substring(index + 6).trim().trim(',', '.', '!', '?')
                            wakeMode = false
                            if (command.isNotBlank()) sendMessage(command)
                            else {
                                status.text = "AWAITING YOUR COMMAND"
                                tts.speak("Yes, I'm listening.", TextToSpeech.QUEUE_FLUSH, null, "wake_ack")
                            }
                        } else handler.postDelayed({ startWakeListening() }, 250)
                    } else if (spoken.isNotBlank()) sendMessage(spoken)
                    else { status.text = "VOICE STANDBY  /  TAP TO SPEAK" }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            speech?.startListening(intent)
        } catch (_: Exception) {
            recognitionBusy = false
            status.text = "VOICE STANDBY  /  TAP TO SPEAK"
        }
    }

    private fun sendMessage(message: String) {
        val prompt = message.trim()
        if (prompt.isEmpty()) { status.text = "READY  /  TAP TO SPEAK"; return }
        input.setText("")
        append("YOU", prompt)
        if (!localAi.isDownloaded()) {
            val notice = "My on-device AI has not been downloaded yet. Open settings and download the free model to begin."
            append("JARVIS", notice)
            status.text = "AI MODEL REQUIRED  /  OPEN SETTINGS"
            tts.speak(notice, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply")
            return
        }
        status.text = if (localAi.isReady()) "PROCESSING  /  LOCAL AI" else "STARTING LOCAL AI…"
        localAi.load(
            onReady = {
                runOnUiThread {
                    status.text = "PROCESSING  /  LOCAL AI"
                    localAi.ask(
                        prompt,
                        onAnswer = { answer -> runOnUiThread {
                            append("JARVIS", answer)
                            status.text = "JARVIS RESPONSE  /  SPEAKING"
                            tts.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply")
                        } },
                        onError = { error -> runOnUiThread {
                            append("JARVIS", "I encountered a problem: $error")
                            status.text = "LOCAL AI ERROR"
                        } }
                    )
                }
            },
            onError = { error -> runOnUiThread {
                append("JARVIS", "I couldn't start the local AI: $error")
                status.text = "AI STARTUP ERROR"
            } }
        )
    }

    private fun showSettings() {
        if (localAi.isDownloaded()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("JARVIS LOCAL AI")
                .setMessage("The free AI model is downloaded on this phone. It works without an API key or subscription. Load it now?")
                .setPositiveButton("LOAD AI") { _, _ ->
                    status.text = "LOADING LOCAL AI…"
                    localAi.load(
                        onReady = { runOnUiThread {
                            status.text = "LOCAL AI READY"
                            append("JARVIS", "Local intelligence is online. No API key required.")
                        } },
                        onError = { error -> runOnUiThread {
                            status.text = "AI LOAD ERROR"
                            append("JARVIS", "I couldn't load the local model: $error")
                        } }
                    )
                }
                .setNegativeButton("CLOSE", null)
                .show()
            return
        }

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = 0
        }
        val message = TextView(this).apply {
            text = "Download the free JARVIS AI model (approximately 850 MB). Wi-Fi is recommended. Once downloaded, it runs on this phone without an internet connection."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, dp(12))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(8))
            addView(message)
            addView(progress, LinearLayout.LayoutParams(-1, dp(12)))
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("DOWNLOAD FREE JARVIS AI")
            .setView(box)
            .setPositiveButton("DOWNLOAD", null)
            .setNegativeButton("CANCEL", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                message.text = "Downloading AI model… keep JARVIS open. This is a one-time download."
                status.text = "DOWNLOADING AI MODEL…"
                localAi.download(
                    onProgress = { done, total ->
                        val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
                        runOnUiThread {
                            progress.progress = pct
                            message.text = "Downloading AI model… $pct% complete. Keep JARVIS open."
                        }
                    },
                    onComplete = { ok, error ->
                        runOnUiThread {
                            if (ok) {
                                message.text = "Download complete. Loading your local AI…"
                                status.text = "AI MODEL DOWNLOADED"
                                dialog.dismiss()
                                localAi.load(
                                    onReady = { runOnUiThread {
                                        status.text = "LOCAL AI READY"
                                        append("JARVIS", "Local intelligence is online. No API key required.")
                                    } },
                                    onError = { reason -> runOnUiThread {
                                        status.text = "AI LOAD ERROR"
                                        append("JARVIS", "The model downloaded, but could not start: $reason")
                                    } }
                                )
                            } else {
                                message.text = "Download failed: " + (error ?: "Unknown error") + ". Please try again on a stable Wi-Fi connection."
                                status.text = "MODEL DOWNLOAD FAILED"
                                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                                dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                            }
                        }
                    }
                )
            }
        }
        dialog.show()
    }

    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) {
            // Prefer an installed British English masculine voice where available.
            val britishMaleVoice = tts.voices
                ?.filter { it.locale.language == "en" && it.locale.country == "GB" && !it.isNetworkConnectionRequired }
                ?.firstOrNull { it.name.contains("rjs", ignoreCase = true) || it.name.contains("male", ignoreCase = true) }
            if (britishMaleVoice != null) {
                tts.voice = britishMaleVoice
                tts.setPitch(0.88f)
            } else {
                tts.language = Locale.UK
                tts.setPitch(0.82f)
            }
            tts.setSpeechRate(0.88f)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    handler.post {
                        if (!appActive) return@post
                        when (utteranceId) {
                            "wake_ack" -> startCommandListening()
                            "jarvis_reply" -> { wakeMode = true; status.text = "SAY  “JARVIS”  TO CONTINUE"; handler.postDelayed({ startWakeListening() }, 450) }
                        }
                    }
                }
                override fun onError(utteranceId: String?) {
                    handler.post { if (appActive && utteranceId == "jarvis_reply") startWakeListening() }
                }
            })
        }
    }

    override fun onResume() {
        super.onResume(); appActive = true

    }
    override fun onPause() {
        appActive = false; handler.removeCallbacksAndMessages(null); recognitionBusy = false
        try { speech?.cancel() } catch (_: Exception) {}
        super.onPause()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
            } else startBackgroundService()
        } else if (requestCode == 42 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startBackgroundService()
        } else if (requestCode == 41 || requestCode == 42) {
            status.text = "PERMISSION REQUIRED"
            append("JARVIS", "Please allow microphone and notification permissions for background voice standby.")
        }
    }
    override fun onDestroy() {
        appActive = false; handler.removeCallbacksAndMessages(null); speech?.destroy()
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        if (::localAi.isInitialized) localAi.close()
        super.onDestroy()
    }

    private inner class HologramView : View(this@MainActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var angle = 0f
        private val ticker = object : Runnable {
            override fun run() { angle = (angle + 1.6f) % 360f; invalidate(); handler.postDelayed(this, 42) }
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); handler.post(ticker) }
        override fun onDetachedFromWindow() { handler.removeCallbacks(ticker); super.onDetachedFromWindow() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height / 2f; val r = min(width, height) * .31f
            if (r < 1f) return
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(1).toFloat(); p.color = Color.argb(24, 48, 211, 255)
            val step = dp(22).toFloat()
            var x = 0f
            while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), p); x += step }
            var y = 0f
            while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, p); y += step }
            p.color = Color.argb(42, 48, 211, 255)
            canvas.drawCircle(cx, cy, r * 1.52f, p); canvas.drawCircle(cx, cy, r * 1.34f, p)
            canvas.drawCircle(cx, cy, r * 1.02f, p); canvas.drawCircle(cx, cy, r * .78f, p)
            p.strokeWidth = dp(2).toFloat(); p.color = Color.argb(185, 48, 211, 255)
            val rect = RectF(cx-r, cy-r, cx+r, cy+r)
            canvas.drawArc(rect, angle, 72f, false, p); canvas.drawArc(rect, angle+150f, 35f, false, p)
            canvas.drawArc(rect, angle+245f, 54f, false, p)
            p.strokeWidth = dp(1).toFloat(); p.color = Color.argb(120, 48, 211, 255)
            for (i in 0 until 96) {
                val a = Math.toRadians(i * 3.75); val inner = r * if (i % 8 == 0) 1.06f else 1.12f
                val outer = r * if (i % 8 == 0) 1.24f else 1.16f
                canvas.drawLine(cx+cos(a).toFloat()*inner, cy+sin(a).toFloat()*inner,
                    cx+cos(a).toFloat()*outer, cy+sin(a).toFloat()*outer, p)
            }
            val pulse = 1f + .035f * sin(Math.toRadians(angle * 2.0)).toFloat()
            p.style = Paint.Style.FILL; p.color = Color.argb(17, 48, 211, 255)
            canvas.drawCircle(cx, cy, r*.72f*pulse, p); p.color = Color.argb(32, 48, 211, 255)
            canvas.drawCircle(cx, cy, r*.46f*pulse, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat(); p.color = Color.argb(205, 48, 211, 255)
            canvas.drawCircle(cx, cy, r*.28f*pulse, p)
            p.style = Paint.Style.FILL; p.color = Color.argb(230, 48, 211, 255)
            canvas.drawCircle(cx, cy, dp(3).toFloat(), p)
            p.color = Color.argb(150, 48, 211, 255); p.textAlign = Paint.Align.CENTER
            p.textSize = dp(9).toFloat(); p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            canvas.drawText("NEURAL INTERFACE  /  ACTIVE", cx, cy+r*1.62f, p)
            canvas.drawText("VOICE  •  INTELLIGENCE  •  CONTROL", cx, cy+r*1.62f+dp(14), p)
        }
    }
}
