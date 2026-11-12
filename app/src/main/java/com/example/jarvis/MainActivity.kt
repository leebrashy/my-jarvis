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
import android.view.WindowManager
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
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
    private val history = JSONArray()
    private val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    private val prefs by lazy {
        val key = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(this, "jarvis_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        tts = TextToSpeech(this, this)
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
        scroll.addView(page, ScrollView.LayoutParams(-1, -2))

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
            addView(transcript, ScrollView.LayoutParams(-1, -2))
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
        actions.addView(actionButton("VOICE / WAKE", false) { beginVoiceMode() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(5); topMargin = dp(7) })
        actions.addView(actionButton("SEND  ➤", true) { sendMessage(input.text.toString()) },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(5); rightMargin = dp(5); topMargin = dp(7) })
        actions.addView(actionButton("⚙", false) { showSettings() },
            LinearLayout.LayoutParams(dp(55), dp(48)).apply { leftMargin = dp(5); topMargin = dp(7) })
        page.addView(actions)
        page.addView(smallLabel("WAKE PHRASE LISTENING WHILE THIS APP IS OPEN"),
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
        wakeMode = true
        status.text = "SAY  “JARVIS”  TO ACTIVATE"
        startWakeListening()
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
                    else { wakeMode = true; handler.postDelayed({ startWakeListening() }, 400) }
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
            handler.postDelayed({ if (wakeMode) startWakeListening() else startCommandListening() }, 1000)
        }
    }

    private fun sendMessage(message: String) {
        val prompt = message.trim()
        if (prompt.isEmpty()) { if (appActive) handler.postDelayed({ startWakeListening() }, 400); return }
        input.setText("")
        append("YOU", prompt)
        val apiKey = try { prefs.getString("api_key", null) } catch (_: Exception) { null }
        if (apiKey.isNullOrBlank()) {
            val setup = "My AI connection is not configured yet. Please open Settings and add your OpenAI API key."
            append("JARVIS", setup); status.text = "SETUP REQUIRED  /  OPEN SETTINGS"
            tts.speak(setup, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply"); return
        }
        status.text = "PROCESSING  /  ANALYSING REQUEST"
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content",
            "You are JARVIS, a capable personal AI assistant. Speak naturally in clear British English with a calm, composed, discreet and confident manner. Be concise unless detail is requested. Be transparent about limitations and never claim to perform actions you cannot perform."))
        for (i in 0 until history.length()) msgs.put(history.getJSONObject(i))
        msgs.put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject().put("model", "gpt-4o-mini").put("messages", msgs).put("temperature", 0.7)
        val request = Request.Builder().url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey").addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    status.text = "CONNECTION ERROR  /  CHECK NETWORK"
                    append("JARVIS", "I couldn't reach the AI service. Please check your internet connection.")
                    wakeMode = true; handler.postDelayed({ startWakeListening() }, 800)
                }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    val parsed = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
                    val answer = if (it.isSuccessful) {
                        try { parsed.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content") }
                        catch (_: Exception) { "I received an unexpected response. Please try again." }
                    } else when (it.code) {
                        401 -> "The API key was not accepted. Please check the key in Settings."
                        429 -> "The AI service has reached a usage limit. Please check your API billing and limits."
                        else -> "The AI service returned an error (${it.code}). Please try again."
                    }
                    if (it.isSuccessful) {
                        history.put(JSONObject().put("role", "user").put("content", prompt))
                        history.put(JSONObject().put("role", "assistant").put("content", answer))
                    }
                    runOnUiThread {
                        status.text = "JARVIS RESPONSE  /  SPEAKING"; append("JARVIS", answer)
                        if (it.isSuccessful) tts.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply")
                        else { wakeMode = true; handler.postDelayed({ startWakeListening() }, 900) }
                    }
                }
            }
        })
    }

    private fun showSettings() {
        val field = EditText(this).apply {
            hint = "Paste your OpenAI API key"; inputType = 129; setSingleLine(true)
            try { setText(prefs.getString("api_key", "")) } catch (_: Exception) {}
        }
        val note = TextView(this).apply {
            text = "Your API key is stored encrypted on this phone. API usage is billed separately from ChatGPT and may incur charges. Never share your key."
            textSize = 12f; setTextColor(Color.DKGRAY); setPadding(0, dp(10), 0, 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0)
            addView(field, LinearLayout.LayoutParams(-1, dp(52))); addView(note)
        }
        android.app.AlertDialog.Builder(this).setTitle("JARVIS AI CONNECTION").setView(box)
            .setPositiveButton("SAVE") { _, _ ->
                try {
                    prefs.edit().putString("api_key", field.text.toString().trim()).apply()
                    append("JARVIS", "Connection settings saved. Say JARVIS when you're ready.")
                    status.text = "CONNECTION SAVED  /  READY"
                } catch (_: Exception) { append("JARVIS", "I couldn't save the connection settings.") }
            }.setNegativeButton("CANCEL", null).show()
    }

    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) {
            tts.language = Locale.UK
            tts.setSpeechRate(0.92f)
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
        if (::status.isInitialized && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            handler.postDelayed({ startWakeListening() }, 800)
    }
    override fun onPause() {
        appActive = false; handler.removeCallbacksAndMessages(null); recognitionBusy = false
        try { speech?.cancel() } catch (_: Exception) {}
        super.onPause()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            handler.postDelayed({ startWakeListening() }, 600)
        else if (requestCode == 41) { status.text = "MICROPHONE PERMISSION REQUIRED"; append("JARVIS", "Please allow microphone access to use the wake phrase.") }
    }
    override fun onDestroy() {
        appActive = false; handler.removeCallbacksAndMessages(null); speech?.destroy()
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
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
