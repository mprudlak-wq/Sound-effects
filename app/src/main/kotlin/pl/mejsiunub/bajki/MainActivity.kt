package pl.mejsiunub.bajki

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import kotlin.concurrent.thread

class MainActivity : Activity() {

    // Słowo-klucz: aplikacja reaguje tylko na frazy zaczynające się od tego słowa.
    private val triggerWord = "dzwiek"

    // Polskie słowa (początki) -> angielskie hasła do wyszukiwania we Freesound.
    private val dictionary = listOf(
        "burz" to "thunderstorm", "grzmot" to "thunder", "piorun" to "thunder",
        "deszcz" to "rain", "wiatr" to "wind", "drzwi" to "door creak",
        "krok" to "footsteps", "wilk" to "wolf howl", "wycie" to "wolf howl",
        "sow" to "owl", "ptak" to "birds", "ptas" to "birds", "ogien" to "fire crackling",
        "ogni" to "fire crackling", "morz" to "ocean waves", "fal" to "ocean waves",
        "las" to "forest ambience", "kot" to "cat meow", "pies" to "dog bark",
        "smok" to "dragon roar", "kon" to "horse", "dzwon" to "bell",
        "smiech" to "laugh", "czar" to "magic spell", "magi" to "magic spell",
        "kruk" to "raven", "zaba" to "frog", "swierszcz" to "crickets",
        "strumien" to "stream water", "rzek" to "river", "krow" to "cow",
        "swinia" to "pig", "owca" to "sheep", "kura" to "chicken", "kogut" to "rooster"
    )

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var player: MediaPlayer? = null
    private lateinit var status: TextView
    private lateinit var keyField: EditText
    private lateinit var toggle: Button
    private val prefs by lazy { getSharedPreferences("bajki", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        root.addView(TextView(this).apply { text = "Bajki z dźwiękami"; textSize = 24f })

        keyField = EditText(this).apply {
            hint = "Klucz API Freesound"
            setText(prefs.getString("key", ""))
            setSingleLine()
        }
        root.addView(keyField)

        root.addView(Button(this).apply {
            text = "Zapisz klucz"
            setOnClickListener {
                prefs.edit().putString("key", keyField.text.toString().trim()).apply()
                say("Klucz zapisany.")
            }
        })

        toggle = Button(this).apply {
            text = "Start"
            setOnClickListener { if (listening) stopListening() else startListening() }
        }
        root.addView(toggle)

        status = TextView(this).apply {
            textSize = 16f
            text = "Powiedz np.: \"dźwięk burzy\""
        }
        root.addView(ScrollView(this).apply { addView(status) })
        setContentView(root)
    }

    private fun say(msg: String) = main.post { status.text = msg }

    // ---------- Nasłuchiwanie ----------

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say("Na tym telefonie brak usługi rozpoznawania mowy (zainstaluj/aktualizuj aplikację Google).")
            return
        }
        listening = true
        toggle.text = "Stop"
        say("Słucham...")
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(listener)
            }
        }
        listenOnce()
    }

    private fun stopListening() {
        listening = false
        toggle.text = "Start"
        main.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        say("Zatrzymano.")
    }

    private fun listenOnce() {
        if (!listening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        recognizer?.startListening(intent)
    }

    private fun restart(delayMs: Long) {
        if (listening) main.postDelayed({ listenOnce() }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) handleText(text)
            restart(200)
        }
        override fun onError(error: Int) {
            // Cisza / brak dopasowania to normalne zdarzenia, po prostu słuchamy dalej.
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                recognizer?.cancel(); restart(1000)
            } else restart(400)
        }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
        else say("Bez zgody na mikrofon aplikacja nie zadziała.")
    }

    // ---------- Komenda -> dźwięk ----------

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase().replace('ł', 'l'), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")

    private fun handleText(text: String) {
        val n = norm(text)
        val idx = n.lastIndexOf(triggerWord)
        if (idx < 0) return                       // brak słowa-klucza -> ignorujemy
        val words = n.substring(idx + triggerWord.length)
            .trim().split(" ").filter { it.isNotBlank() }.take(2)
        if (words.isEmpty()) return
        val query = words.firstNotNullOfOrNull { w ->
            dictionary.firstOrNull { w.startsWith(it.first) }?.second
        } ?: words.joinToString(" ")
        say("Komenda: \"${words.joinToString(" ")}\" -> szukam: $query")
        fetchAndPlay(query)
    }

    private fun fetchAndPlay(query: String) {
        val key = prefs.getString("key", "").orEmpty().trim()
        if (key.isEmpty()) { say("Najpierw wpisz i zapisz klucz API."); return }
        thread {
            try {
                val file = File(cacheDir, "snd_" + query.replace(Regex("[^a-z0-9]+"), "_") + ".mp3")
                if (!file.exists()) {
                    val filter = URLEncoder.encode("duration:[1 TO 10]", "UTF-8")
                    val url = "https://freesound.org/apiv2/search/text/" +
                        "?query=" + URLEncoder.encode(query, "UTF-8") +
                        "&filter=" + filter +
                        "&sort=rating_desc&fields=id,name,previews,duration&page_size=5" +
                        "&token=" + key
                    val results = JSONObject(httpGet(url)).getJSONArray("results")
                    if (results.length() == 0) { say("Nie znaleziono dźwięku: $query"); return@thread }
                    val mp3 = results.getJSONObject(0).getJSONObject("previews").getString("preview-hq-mp3")
                    download(mp3, file)
                }
                main.post { play(file) }
            } catch (e: Exception) {
                say("Błąd: ${e.message}")
            }
        }
    }

    private fun httpGet(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        if (c.responseCode != 200) throw RuntimeException("Freesound: HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun download(u: String, dest: File) {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        c.inputStream.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
    }

    private fun play(f: File) {
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            setOnPreparedListener { it.start() }
            setOnCompletionListener { it.release(); if (player === it) player = null }
            prepareAsync()
        }
    }

    override fun onDestroy() {
        listening = false
        recognizer?.destroy()
        player?.release()
        super.onDestroy()
    }
}
