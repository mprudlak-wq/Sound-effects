package pl.mejsiunub.bajki

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
    private lateinit var micButton: TextView
    private lateinit var micLabel: TextView
    private lateinit var historyText: TextView
    private var pulse: ObjectAnimator? = null
    private val history = mutableListOf<String>()
    private val prefs by lazy { getSharedPreferences("bajki", Context.MODE_PRIVATE) }

    // ---------- Wygląd ----------

    private val gold = "#FFC857"
    private val teal = "#5EEAD4"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: String, radiusDp: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(color)); cornerRadius = dp(radiusDp).toFloat()
    }

    private fun label(t: String, size: Float, color: String, bold: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded("#26FFFFFF", 20)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.parseColor("#1B1B3A")
        window.navigationBarColor = Color.parseColor("#3B2667")

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(32))
        }

        // Nagłówek
        content.addView(label("🌙", 44f, "#FFFFFF").apply { gravity = Gravity.CENTER })
        content.addView(label("Bajki z dźwiękami", 26f, "#FFFFFF", true).apply { gravity = Gravity.CENTER })
        content.addView(label("Czytaj bajkę, a ja dorzucę dźwięki", 14f, "#C9B8F0").apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })

        // Duży przycisk mikrofonu
        micButton = TextView(this).apply {
            text = "🎙️"; textSize = 56f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(gold)) }
            elevation = dp(10).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(150), dp(150)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(28)
            }
            setOnClickListener { if (listening) stopListening() else startListening() }
        }
        content.addView(micButton)
        micLabel = label("Dotknij, aby zacząć słuchać", 15f, "#FFFFFF", true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }
        content.addView(micLabel)

        // Status
        val statusCard = card()
        statusCard.addView(label("STATUS", 11f, "#C9B8F0", true))
        status = label("Powiedz np.: „dźwięk burzy”", 16f, "#FFFFFF").apply { setPadding(0, dp(4), 0, 0) }
        statusCard.addView(status)
        content.addView(statusCard)

        // Ostatnie dźwięki
        val historyCard = card()
        historyCard.addView(label("OSTATNIE DŹWIĘKI", 11f, "#C9B8F0", true))
        historyText = label("Tu pojawią się odtworzone dźwięki.", 15f, "#FFFFFF").apply {
            setPadding(0, dp(4), 0, 0)
        }
        historyCard.addView(historyText)
        content.addView(historyCard)

        // Podpowiedź
        val hintCard = card()
        hintCard.addView(label("JAK TO DZIAŁA", 11f, "#C9B8F0", true))
        hintCard.addView(label(
            "Powiedz „dźwięk” i dowolne słowo, np. „dźwięk burzy”, „dźwięk smoka”, „dźwięk samochód”. " +
                "Mów wyraźnie, z krótką pauzą przed i po komendzie. Zwykły tekst bajki jest ignorowany.",
            14f, "#FFFFFF"
        ).apply { setPadding(0, dp(4), 0, 0) })
        content.addView(hintCard)

        // Klucz API (zwijany)
        val keyCard = card()
        val keyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hasKey = !prefs.getString("key", "").isNullOrBlank()
        keyBox.visibility = if (hasKey) View.GONE else View.VISIBLE
        val keyHeader = label("⚙️  Klucz API Freesound", 14f, "#FFFFFF", true).apply {
            setOnClickListener {
                keyBox.visibility = if (keyBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        keyCard.addView(keyHeader)

        keyField = EditText(this).apply {
            hint = "Wklej klucz API"
            setText(prefs.getString("key", ""))
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#99FFFFFF"))
            background = rounded("#33FFFFFF", 12)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        keyBox.addView(keyField)

        keyBox.addView(Button(this).apply {
            text = "Zapisz klucz"
            isAllCaps = false
            setTextColor(Color.parseColor("#1B1B3A"))
            background = rounded(gold, 12)
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setOnClickListener {
                prefs.edit().putString("key", keyField.text.toString().trim()).apply()
                say("Klucz zapisany.")
                keyBox.visibility = View.GONE
            }
        })
        keyCard.addView(keyBox)
        content.addView(keyCard)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#1B1B3A"), Color.parseColor("#3B2667"), Color.parseColor("#6B3FA0"))
            )
            addView(content)
        }
        setContentView(scroll)
    }

    private fun say(msg: String) = main.post { status.text = msg }

    private fun addHistory(line: String) {
        history.add(0, line)
        while (history.size > 5) history.removeAt(history.size - 1)
        historyText.text = history.joinToString("\n")
    }

    private fun setListeningUi(on: Boolean) {
        (micButton.background as GradientDrawable).setColor(Color.parseColor(if (on) teal else gold))
        micLabel.text = if (on) "Słucham… powiedz „dźwięk burzy”" else "Dotknij, aby zacząć słuchać"
        pulse?.cancel()
        micButton.scaleX = 1f; micButton.scaleY = 1f
        if (on) {
            pulse = ObjectAnimator.ofPropertyValuesHolder(
                micButton,
                PropertyValuesHolder.ofFloat("scaleX", 1.1f),
                PropertyValuesHolder.ofFloat("scaleY", 1.1f)
            ).apply {
                duration = 800
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        }
    }

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
        setListeningUi(true)
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
        setListeningUi(false)
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
        val tokens = text.lowercase().split(" ").filter { it.isNotBlank() }
        val idx = tokens.indexOfLast { norm(it).startsWith(triggerWord) }
        if (idx < 0) return                       // brak słowa-klucza -> ignorujemy
        val words = tokens.drop(idx + 1).take(2)
        if (words.isEmpty()) return
        say("Komenda: \"${words.joinToString(" ")}\" -> szukam...")
        fetchAndPlay(words)
    }

    // Słownik (szybko, offline), a dla innych słów darmowe tłumaczenie PL -> EN.
    private fun translate(words: List<String>): String {
        for (w in words) {
            val n = norm(w)
            dictionary.firstOrNull { n.startsWith(it.first) }?.let { return it.second }
        }
        val phrase = words.joinToString(" ")
        try {
            val url = "https://api.mymemory.translated.net/get?langpair=pl%7Cen&q=" +
                URLEncoder.encode(phrase, "UTF-8")
            val t = JSONObject(httpGet(url)).getJSONObject("responseData").getString("translatedText")
            val clean = t.lowercase().replace(Regex("[^a-z ]"), " ").trim().replace(Regex(" +"), " ")
            if (clean.isNotEmpty() && !t.uppercase().contains("MYMEMORY")) return clean
        } catch (e: Exception) { /* przejdź do wyszukiwania po polsku */ }
        return norm(phrase)
    }

    private fun fetchAndPlay(words: List<String>) {
        val key = prefs.getString("key", "").orEmpty().trim()
        if (key.isEmpty()) { say("Najpierw wpisz i zapisz klucz API."); return }
        thread {
            try {
                val query = translate(words)
                say("Szukam: $query")
                val file = File(cacheDir, "snd_" + query.replace(Regex("[^a-z0-9]+"), "_") + ".mp3")
                if (!file.exists()) {
                    val filter = URLEncoder.encode("duration:[1 TO 10]", "UTF-8")
                    val url = "https://freesound.org/apiv2/search/text/" +
                        "?query=" + URLEncoder.encode(query, "UTF-8") +
                        "&filter=" + filter +
                        "&sort=rating_desc&fields=id,name,previews,duration&page_size=5" +
                        "&token=" + key
                    val results = JSONObject(httpGet(url)).getJSONArray("results")
                    if (results.length() == 0) { say("Nie znaleziono dźwięku: ${words.joinToString(" ")} (szukano: $query)"); return@thread }
                    val mp3 = results.getJSONObject(0).getJSONObject("previews").getString("preview-hq-mp3")
                    download(mp3, file)
                }
                main.post { play(file); addHistory("🔊 ${words.joinToString(" ")}  →  $query") }
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
        pulse?.cancel()
        recognizer?.destroy()
        player?.release()
        super.onDestroy()
    }
}
