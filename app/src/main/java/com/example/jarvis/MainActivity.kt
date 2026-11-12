package com.example.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
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

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val bg = Color.rgb(4, 12, 25)
    private val panel = Color.rgb(9, 25, 44)
    private val cyan = Color.rgb(47, 207, 255)
    private val white = Color.rgb(225, 242, 255)
    private lateinit var transcript: TextView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var tts: TextToSpeech
    private var speech: SpeechRecognizer? = null
    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS).build()
    private val history = JSONArray()
    private val prefs by lazy {
        val key = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            this, "jarvis_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        buildUi()
        append("JARVIS", "Good evening. JARVIS systems online. Configure your AI connection in Settings to begin.")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 41)
        }
    }

    private fun buildUi() {
        val base = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 28, 20, 18)
            setBackgroundColor(bg)
        }
        val header = TextView(this).apply {
            text = "J A R V I S"
            textSize = 25f; setTextColor(cyan); gravity = Gravity.CENTER
            letterSpacing = .22f
        }
        base.addView(header, LinearLayout.LayoutParams(-1, 54))
        val sub = TextView(this).apply {
            text = "PERSONAL INTELLIGENCE SYSTEM  •  ONLINE"
            textSize = 10f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER
        }
        base.addView(sub, LinearLayout.LayoutParams(-1, 28))
        val orb = TextView(this).apply {
            text = "◉"
            textSize = 76f; setTextColor(cyan); gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
        }
        base.addView(orb, LinearLayout.LayoutParams(-1, 112))
        status = TextView(this).apply {
            text = "STANDBY"
            textSize = 11f; setTextColor(cyan); gravity = Gravity.CENTER
        }
        base.addView(status, LinearLayout.LayoutParams(-1, 28))

        transcript = TextView(this).apply {
            textSize = 14f; setTextColor(white); setPadding(14, 14, 14, 14)
            setBackgroundColor(panel)
            text = ""
        }
        val scroll = ScrollView(this).apply { addView(transcript) }
        base.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = 12; bottomMargin = 12
        })

        input = EditText(this).apply {
            hint = "Ask JARVIS anything…"
            setHintTextColor(Color.GRAY); setTextColor(white); textSize = 15f
            setSingleLine(false); maxLines = 3
            setPadding(14, 12, 14, 12)
            setBackgroundColor(panel)
        }
        base.addView(input, LinearLayout.LayoutParams(-1, -2))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val mic = makeButton("🎙  SPEAK") { listen() }
        val send = makeButton("SEND  ➤") { sendMessage(input.text.toString()) }
        val settings = makeButton("⚙") { showSettings() }
        actions.addView(mic, LinearLayout.LayoutParams(0, 54, 1f).apply { rightMargin = 5; topMargin = 10 })
        actions.addView(send, LinearLayout.LayoutParams(0, 54, 1f).apply { leftMargin = 5; rightMargin = 5; topMargin = 10 })
        actions.addView(settings, LinearLayout.LayoutParams(58, 54).apply { leftMargin = 5; topMargin = 10 })
        base.addView(actions)
        setContentView(base)
    }

    private fun makeButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 11f; setTextColor(cyan)
        setBackgroundColor(panel); setOnClickListener { action() }
    }

    private fun append(who: String, text: String) {
        runOnUiThread {
            transcript.append("\n$who\n$text\n")
            (transcript.parent as? ScrollView)?.post { (transcript.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "VOICE INPUT UNAVAILABLE"; return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 41); return
        }
        speech?.destroy()
        speech = SpeechRecognizer.createSpeechRecognizer(this)
        speech?.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { status.text = "LISTENING…" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { status.text = "THINKING…" }
            override fun onError(error: Int) { status.text = "STANDBY" }
            override fun onResults(results: Bundle?) {
                val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                input.setText(words)
                if (words.isNotBlank()) sendMessage(words)
                status.text = "STANDBY"
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to JARVIS")
        }
        speech?.startListening(intent)
    }

    private fun sendMessage(message: String) {
        val prompt = message.trim()
        if (prompt.isEmpty()) return
        input.setText("")
        append("YOU", prompt)
        val apiKey = prefs.getString("api_key", null)
        if (apiKey.isNullOrBlank()) {
            append("JARVIS", "Please open Settings and add your OpenAI API key first.")
            status.text = "SETUP REQUIRED"; return
        }
        status.text = "PROCESSING…"
        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content",
            "You are JARVIS, a capable, calm, concise personal AI assistant. Speak naturally in British English. Be helpful and transparent about limitations. Do not claim to control devices or perform actions you cannot perform."))
        for (i in 0 until history.length()) msgs.put(history.getJSONObject(i))
        msgs.put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject().put("model", "gpt-4o-mini").put("messages", msgs).put("temperature", 0.7)
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { status.text = "CONNECTION ERROR"; append("JARVIS", "I couldn't reach the AI service. Check your internet connection and API key.") }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    val answer = try {
                        JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getString("content")
                    } catch (_: Exception) {
                        "I couldn't process that response. Check your API key and account API access."
                    }
                    if (it.isSuccessful) {
                        history.put(JSONObject().put("role", "user").put("content", prompt))
                        history.put(JSONObject().put("role", "assistant").put("content", answer))
                    }
                    runOnUiThread {
                        status.text = "STANDBY"
                        append("JARVIS", answer)
                        if (it.isSuccessful) tts.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply")
                    }
                }
            }
        })
    }

    private fun showSettings() {
        val field = EditText(this).apply {
            hint = "OpenAI API key"; inputType = 129
            setText(prefs.getString("api_key", ""))
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(35, 15, 35, 0)
            addView(field)
            addView(TextView(this@MainActivity).apply {
                text = "Your key is stored encrypted on this device. API usage may incur charges. Never share your key."
                textSize = 12f; setTextColor(Color.DKGRAY)
            })
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("JARVIS connection")
            .setView(layout)
            .setPositiveButton("SAVE") { _, _ ->
                prefs.edit().putString("api_key", field.text.toString().trim()).apply()
                append("JARVIS", "Connection settings saved.")
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    override fun onInit(statusCode: Int) {
        if (statusCode == TextToSpeech.SUCCESS) {
            tts.language = Locale.UK
            tts.setSpeechRate(0.94f)
        }
    }

    override fun onDestroy() {
        speech?.destroy()
        tts.stop(); tts.shutdown()
        super.onDestroy()
    }
}
