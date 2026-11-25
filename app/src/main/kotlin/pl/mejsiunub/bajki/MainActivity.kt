package pl.mejsiunub.bajki

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import kotlin.concurrent.thread

class MainActivity : Activity() {

    // Słowo-klucz: aplikacja reaguje tylko na frazy zaczynające się od tego słowa.
    // Tryb językowy: "pl" (komendy „dźwięk ...”, tłumaczenie na angielski) albo
    // "en" (komendy „sound ...”, szukanie dokładnie tego słowa bez tłumaczenia).
    private var lang = "pl"
    private val triggerWord get() = if (lang == "en") "sound" else "dzwiek"
    private val stopWords get() =
        if (lang == "en") listOf("stop", "silence", "quiet", "mute") else listOf("stop", "cisz", "przestan", "koniec")
    private val nextWords get() =
        if (lang == "en") listOf("next", "another", "other", "different") else listOf("inn", "nastepn", "kolejn", "dalej")

    // Polskie słowa (początki) -> angielskie hasła do wyszukiwania we Freesound.
    private val dictionary = listOf(
        "burz" to "thunderstorm", "grzmot" to "thunder", "piorun" to "thunder",
        "deszcz" to "rain", "wiatr" to "wind", "drzwi" to "door creak",
        "krok" to "footsteps", "wilk" to "wolf howl", "wycie" to "wolf howl",
        "sow" to "owl", "ptak" to "birds", "ptas" to "birds", "ogien" to "fire crackling",
        "ogni" to "fire crackling", "morz" to "ocean waves", "fal" to "ocean waves",
        "las" to "forest ambience", "kot" to "cat meow", "pies" to "dog bark",
        "smok" to "dragon roar", "kon" to "horse", "dzwonek" to "bell", "dzwon" to "bell", "les" to "forest ambience",
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
    private lateinit var heard: TextView
    private lateinit var langBtn: TextView
    private lateinit var keyField: EditText
    private lateinit var micButton: FrameLayout
    private lateinit var micRing: GradientDrawable
    private lateinit var micIcon: IconView
    private lateinit var micLabel: TextView
    private lateinit var historyText: TextView
    private var pulse: ObjectAnimator? = null
    private val history = mutableListOf<String>()
    private var lastQuery: String? = null
    private var firedIdx = -1               // numer słowa-klucza już obsłużonego w tej wypowiedzi
    private var pendingKey = ""
    private var partialFire: Runnable? = null
    private var lastCmd = ""
    private var lastCmdTime = 0L
    private var lastLabel: String = ""
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

    private class Collapsible(val card: LinearLayout, val body: LinearLayout, val setOpen: (Boolean) -> Unit)

    // Karta z nagłówkiem, który zwija i rozwija zawartość (▾ otwarta, ▸ zwinięta).
    private fun collapsible(title: String, open: Boolean, onToggle: (Boolean) -> Unit = {}): Collapsible {
        val card = card()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val header = label("", 14f, "#FFFFFF", true).apply { setPadding(0, dp(4), 0, dp(4)) }
        fun setOpen(o: Boolean) {
            body.visibility = if (o) View.VISIBLE else View.GONE
            header.text = (if (o) "▾  " else "▸  ") + title
        }
        setOpen(open)
        header.setOnClickListener {
            val o = body.visibility != View.VISIBLE
            setOpen(o)
            onToggle(o)
        }
        card.addView(header)
        card.addView(body)
        return Collapsible(card, body, ::setOpen)
    }

    private fun commandItem(cmd: String, desc: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(label(cmd, 15f, gold, true))
            addView(label(desc, 13f, "#FFFFFF").apply { setPadding(0, dp(2), 0, 0) })
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lang = prefs.getString("lang", "pl") ?: "pl"
        window.statusBarColor = Color.parseColor("#1B1B3A")
        cacheDir.listFiles()?.filter { it.name.startsWith("snd_") }?.forEach { it.delete() }
        window.navigationBarColor = Color.parseColor("#3B2667")

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(32))
        }

        // Pasek z menu
        val menuBtn = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1AFFFFFF"))
                setStroke(dp(2), Color.parseColor("#80FFFFFF"))
            }
            addView(IconView(context, IconView.MENU), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener { showLibrary() }
        }
        langBtn = label(lang.uppercase(), 14f, "#FFFFFF", true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1AFFFFFF"))
                cornerRadius = dp(22).toFloat()
                setStroke(dp(2), Color.parseColor("#80FFFFFF"))
            }
            setOnClickListener { setLang(if (lang == "pl") "en" else "pl") }
        }
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.END
            addView(langBtn, LinearLayout.LayoutParams(dp(56), dp(44)).apply { rightMargin = dp(10) })
            addView(menuBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        })

        // Nagłówek
        content.addView(label("🌙", 44f, "#FFFFFF").apply { gravity = Gravity.CENTER })
        content.addView(label("Bajki z dźwiękami", 26f, "#FFFFFF", true).apply { gravity = Gravity.CENTER })
        content.addView(label("Czytaj bajkę, a ja dorzucę dźwięki", 14f, "#C9B8F0").apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })

        // Duży przycisk mikrofonu (ikona liniowa)
        micRing = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#1AFFFFFF"))
            setStroke(dp(3), Color.parseColor(gold))
        }
        micIcon = IconView(this, IconView.MIC).apply { color = Color.parseColor(gold) }
        micButton = FrameLayout(this).apply {
            background = micRing
            addView(micIcon, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(150), dp(150)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(20)
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
        heard = label("", 12f, "#C9B8F0").apply { setPadding(0, dp(6), 0, 0) }
        statusCard.addView(heard)
        content.addView(statusCard)

        // Ostatnie dźwięki
        val historyCard = card()
        historyCard.addView(label("OSTATNIE DŹWIĘKI", 11f, "#C9B8F0", true))
        historyText = label("Tu pojawią się odtworzone dźwięki.", 15f, "#FFFFFF").apply {
            setPadding(0, dp(4), 0, 0)
        }
        historyCard.addView(historyText)
        content.addView(historyCard)

        // Jak to działa (zwijane, zapamiętuje stan)
        val help = collapsible("Jak to działa", prefs.getBoolean("help_open", true)) {
            prefs.edit().putBoolean("help_open", it).apply()
        }
        help.body.addView(commandItem(
            "„dźwięk burza”",
            "Odtwarza dźwięk dla dowolnego słowa. Możesz podać do dwóch słów, np. „dźwięk latający komar”."
        ))
        help.body.addView(commandItem(
            "„dźwięk inny”",
            "Inny wariant ostatniego dźwięku (też: następny, kolejny, dalej). Wybrany wariant zostaje zapamiętany."
        ))
        help.body.addView(commandItem(
            "„dźwięk stop”",
            "Ucisza odtwarzany dźwięk (też: cisza, przestań, koniec)."
        ))
        help.body.addView(commandItem(
            "Przycisk PL / EN (u góry)",
            "Tryb EN: mówisz po angielsku „sound fireplace”, „sound next”, „sound stop”. Aplikacja szuka dokładnie tego słowa, bez tłumaczenia."
        ))
        help.body.addView(label(
            "Wskazówki",
            11f, "#C9B8F0", true
        ).apply { setPadding(0, dp(10), 0, dp(2)) })
        help.body.addView(label(
            "• Po komendzie zrób krótką pauzę (ok. pół sekundy), żeby aplikacja wiedziała, gdzie się kończy.\n" +
                "• Zwykły tekst bajki jest ignorowany, reaguje tylko na słowo „dźwięk”.\n" +
                "• Dźwięki trwają do 10 s i zapisują się w telefonie, więc kolejny raz grają od razu.\n" +
                "• Menu (trzy kreski w prawym górnym rogu): lista zapisanych dźwięków, odsłuch i usuwanie.",
            13f, "#FFFFFF"
        ))
        content.addView(help.card)

        // Klucz API (zwijany)
        val hasKey = !prefs.getString("key", "").isNullOrBlank()
        val keyCol = collapsible("Klucz API Freesound", !hasKey)
        val keyBox = keyCol.body

        keyField = EditText(this).apply {
            hint = "Wklej klucz API"
            setText(prefs.getString("key", ""))
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#99FFFFFF"))
            background = rounded("#33FFFFFF", 12)
            setPadding(dp(14), dp(10), dp(14), dp(10))
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
                keyCol.setOpen(false)
            }
        })
        content.addView(keyCol.card)

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

    // ---------- Biblioteka dźwięków ----------

    private class Snd(val file: File, val query: String, val idx: Int)

    private fun listSounds(): List<Snd> {
        val rx = Regex("^snd_(.+)_(\\d+)\\.mp3$")
        return (filesDir.listFiles() ?: emptyArray()).mapNotNull { f ->
            val m = rx.find(f.name) ?: return@mapNotNull null
            Snd(f, m.groupValues[1].replace('_', ' '), m.groupValues[2].toInt())
        }.sortedWith(compareBy({ it.query }, { it.idx }))
    }

    private fun deleteSound(snd: Snd) {
        snd.file.delete()
        // Usunięty wariant był wybrany? Następnym razem weź kolejny.
        val k = "pick_${snd.query}"
        if (prefs.getInt(k, 0) == snd.idx) prefs.edit().putInt(k, snd.idx + 1).apply()
    }

    private fun pill(text: String, bg: String, fg: String) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.parseColor(fg))
        background = rounded(bg, 12)
        stateListAnimator = null
    }

    private fun showLibrary() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#2A1F4D", 24)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        box.addView(label("Moje dźwięki", 20f, "#FFFFFF", true))
        val summary = label("", 13f, "#C9B8F0").apply { setPadding(0, dp(2), 0, dp(6)) }
        box.addView(summary)

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(
            ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        fun refresh() {
            list.removeAllViews()
            val sounds = listSounds()
            val total = sounds.sumOf { it.file.length() }
            summary.text = if (sounds.isEmpty()) "Brak zapisanych dźwięków"
            else "${sounds.size} plików · ${"%.1f".format(total / 1048576.0)} MB w pamięci telefonu"
            if (sounds.isEmpty()) {
                list.addView(label("Powiedz np. „dźwięk burza” — dźwięk zapisze się tutaj.", 14f, "#FFFFFF")
                    .apply { setPadding(0, dp(12), 0, 0) })
            }
            for (snd in sounds) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded("#1AFFFFFF", 14)
                    setPadding(dp(14), dp(8), dp(6), dp(8))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) }
                    setOnClickListener { play(snd.file) }
                }
                val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                texts.addView(label(snd.query, 16f, "#FFFFFF", true))
                texts.addView(label("wariant ${snd.idx + 1} · ${snd.file.length() / 1024} KB", 12f, "#C9B8F0"))
                row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(label("▶", 18f, gold).apply {
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    setOnClickListener { play(snd.file) }
                })
                row.addView(label("✕", 18f, "#FF8A8A").apply {
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setMessage("Usunąć „${snd.query}” (wariant ${snd.idx + 1})?")
                            .setPositiveButton("Usuń") { _, _ -> deleteSound(snd); refresh() }
                            .setNegativeButton("Anuluj", null)
                            .show()
                    }
                })
                list.addView(row)
            }
        }
        refresh()

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        buttons.addView(pill("Wyczyść wszystko", "#33FF8A8A", "#FFB3B3").apply {
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("Usunąć wszystkie zapisane dźwięki?")
                    .setPositiveButton("Usuń") { _, _ ->
                        listSounds().forEach { it.file.delete() }
                        val e = prefs.edit()
                        prefs.all.keys.filter { it.startsWith("pick_") }.forEach { e.remove(it) }
                        e.apply()
                        refresh()
                    }
                    .setNegativeButton("Anuluj", null)
                    .show()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        buttons.addView(pill("Zamknij", gold, "#1B1B3A").apply {
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(buttons)

        dialog.setContentView(box)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        val dm = resources.displayMetrics
        dialog.window?.setLayout((dm.widthPixels * 0.92).toInt(), (dm.heightPixels * 0.75).toInt())
    }

    private fun addHistory(line: String) {
        history.add(0, line)
        while (history.size > 5) history.removeAt(history.size - 1)
        historyText.text = history.joinToString("\n")
    }

    private fun listenHint() =
        if (lang == "en") "Słucham… powiedz „sound rain”" else "Słucham… powiedz „dźwięk burzy”"

    private fun setLang(l: String) {
        lang = l
        prefs.edit().putString("lang", l).apply()
        langBtn.text = l.uppercase()
        say(if (l == "en") "Tryb angielski: powiedz np. „sound fireplace”" else "Tryb polski: powiedz np. „dźwięk burza”")
        if (listening) {
            micLabel.text = listenHint()
            cancelPending()
            firedIdx = -1
            recognizer?.cancel()
            restart(300)
        }
    }

    private fun setListeningUi(on: Boolean) {
        val c = Color.parseColor(if (on) teal else gold)
        micRing.setStroke(dp(3), c)
        micIcon.color = c
        micLabel.text = if (on) listenHint() else "Dotknij, aby zacząć słuchać"
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (lang == "en") "en-US" else "pl-PL")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
        }
        recognizer?.startListening(intent)
    }

    private fun restart(delayMs: Long) {
        if (listening) main.postDelayed({ listenOnce() }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            all?.firstOrNull()?.let { heard.text = "Usłyszałem: …" + it.takeLast(60) }
            val text = pickText(all)
            cancelPending()
            if (!text.isNullOrBlank()) handleText(text)
            firedIdx = -1
            restart(100)
        }
        override fun onError(error: Int) {
            cancelPending()
            firedIdx = -1
            // Cisza / brak dopasowania to normalne zdarzenia, po prostu słuchamy dalej.
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                recognizer?.cancel(); restart(1000)
            } else restart(300)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = pickText(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
            if (!text.isNullOrBlank()) schedulePartial(text)
        }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun cancelPending() {
        partialFire?.let { main.removeCallbacks(it) }
        partialFire = null
        pendingKey = ""
    }

    // Komenda z wyników częściowych: odpala się, gdy słowa po "dźwięk" (maks. 2) są
    // niezmienione przez ~0,6 s, więc nie trzeba czekać na koniec całej wypowiedzi.
    private fun schedulePartial(text: String) {
        val tokens = text.lowercase().split(" ").filter { it.isNotBlank() }
        val idx = tokens.indexOfLast { isTrigger(norm(it)) }
        if (idx < 0 || idx <= firedIdx || idx + 1 >= tokens.size) return
        val words = tokens.drop(idx + 1).take(2)
        val key = "$idx:" + words.joinToString(" ")
        if (key == pendingKey) return
        cancelPending()
        pendingKey = key
        val r = Runnable {
            partialFire = null
            pendingKey = ""
            firedIdx = idx
            runCommand(words)
        }
        partialFire = r
        main.postDelayed(r, 600)
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

    // Odległość edycyjna (ile liter trzeba zmienić, dodać lub usunąć).
    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(
                d[i - 1][j] + 1, d[i][j - 1] + 1,
                d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            )
        }
        return d[a.length][b.length]
    }

    // Słowo-klucz, także gdy telefon przekręci je o jedną literę (np. "dziwek", "dzwik").
    private fun isTrigger(n: String): Boolean =
        if (lang == "en") n == "sound" || n == "sounds"      // po angielsku bez "zgadywania" (np. found, round)
        else n.startsWith(triggerWord) || (n.length in 5..8 && distance(n, triggerWord) <= 1)

    // Rozpoznawanie podaje kilka wersji tego, co usłyszało. Bierzemy pierwszą ze słowem-kluczem.
    private fun pickText(list: List<String>?): String? =
        list?.firstOrNull { t -> t.lowercase().split(" ").any { isTrigger(norm(it)) } }

    private val endings = setOf("", "a", "e", "i", "u", "y", "o", "em", "ie", "ow", "om", "ami", "ach", "ia", "iem")

    // Słowo pasuje do hasła ze słownika, jeśli to ono samo albo ono z polską końcówką.
    // Dzięki temu "laser" nie jest już traktowany jak "las".
    private fun matchesStem(word: String, stem: String): Boolean =
        word.startsWith(stem) && word.substring(stem.length) in endings

    private fun handleText(text: String) {
        val tokens = text.lowercase().split(" ").filter { it.isNotBlank() }
        val idx = tokens.indexOfLast { isTrigger(norm(it)) }
        if (idx < 0 || idx <= firedIdx || idx + 1 >= tokens.size) return   // brak (nowej) komendy
        firedIdx = idx
        runCommand(tokens.drop(idx + 1).take(2))
    }

    private fun runCommand(words: List<String>) {
        val phrase = words.joinToString(" ")
        val n = norm(words.first())
        val now = System.currentTimeMillis()
        if (norm(phrase) == lastCmd && now - lastCmdTime < 2000) return   // ta sama komenda z wyniku częściowego i końcowego
        lastCmd = norm(phrase)
        lastCmdTime = now
        when {
            stopWords.any { n.startsWith(it) } -> {
                stopSound()
                say("Dźwięk zatrzymany.")
            }
            nextWords.any { n.startsWith(it) } -> {
                say("Szukam innego dźwięku...")
                fetchAndPlay(words, true)
            }
            else -> {
                say("Komenda: \"$phrase\" -> szukam...")
                fetchAndPlay(words, false)
            }
        }
    }

    private fun stopSound() {
        player?.release()
        player = null
    }

    // Słownik (szybko, offline), a dla innych słów darmowe tłumaczenie PL -> EN.
    private fun translate(words: List<String>): String {
        if (lang == "en") {   // tryb EN: szukaj dokładnie tego, co powiedziałeś, bez tłumaczenia
            val q = words.joinToString(" ").lowercase().replace(Regex("[^a-z0-9 ]"), " ").trim().replace(Regex(" +"), " ")
            return if (q.isNotEmpty()) q else norm(words.joinToString(" "))
        }
        for (w in words) {
            val n = norm(w)
            dictionary.firstOrNull { matchesStem(n, it.first) }?.let { return it.second }
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

    private fun soundFile(query: String, idx: Int) =
        File(filesDir, "snd_" + query.replace(Regex("[^a-z0-9]+"), "_") + "_" + idx + ".mp3")

    private fun search(query: String, key: String): JSONArray {
        val filter = URLEncoder.encode("duration:[1 TO 10]", "UTF-8")
        val url = "https://freesound.org/apiv2/search/text/" +
            "?query=" + URLEncoder.encode(query, "UTF-8") +
            "&filter=" + filter +
            "&sort=rating_desc&fields=id,name,previews,duration&page_size=15" +
            "&token=" + key
        return JSONObject(httpGet(url)).getJSONArray("results")
    }

    // other = true: ten sam dźwięk co ostatnio, ale kolejny wynik z bazy ("dźwięk inny").
    private fun fetchAndPlay(words: List<String>, other: Boolean) {
        val key = prefs.getString("key", "").orEmpty().trim()
        if (key.isEmpty()) { say("Najpierw wpisz i zapisz klucz API."); return }
        thread {
            try {
                var query = (if (other) lastQuery else translate(words))
                if (query == null) {
                    say("Najpierw poproś o jakiś dźwięk, np. „dźwięk komar”.")
                    return@thread
                }
                val label = if (other) lastLabel else words.joinToString(" ")
                val step = if (other) 1 else 0
                var idx = prefs.getInt("pick_$query", 0) + step
                say("Szukam: $query" + if (idx > 0) " (wariant ${idx + 1})" else "")

                var file = soundFile(query, idx)
                if (!file.exists()) {
                    var results = search(query, key)
                    if (results.length() == 0 && query.contains(" ")) {
                        // Brak wyników dla całej frazy -> spróbuj samego ostatniego słowa (zwykle rzeczownik).
                        query = query.substringAfterLast(" ")
                        idx = prefs.getInt("pick_$query", 0) + step
                        file = soundFile(query, idx)
                        say("Szukam: $query")
                        if (!file.exists()) results = search(query, key)
                    }
                    if (!file.exists()) {
                        if (results.length() == 0) {
                            say("Nie znaleziono dźwięku: $label (szukano: $query)")
                            return@thread
                        }
                        if (idx >= results.length()) {      // skończyły się warianty -> od początku
                            idx = 0
                            file = soundFile(query, 0)
                        }
                        if (!file.exists()) {
                            val mp3 = results.getJSONObject(idx).getJSONObject("previews").getString("preview-hq-mp3")
                            download(mp3, file)
                        }
                    }
                }
                prefs.edit().putInt("pick_$query", idx).apply()   // zapamiętaj wybrany wariant
                lastQuery = query
                lastLabel = label
                val f = file
                val n = idx + 1
                val q = query
                main.post { play(f); addHistory("🔊 $label  →  $q (#$n)") }
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


/** Ikony rysowane linią (bez plików graficznych): mikrofon i menu. */
class IconView(context: Context, private val kind: Int) : View(context) {
    companion object {
        const val MIC = 0
        const val MENU = 1
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    var color: Int = Color.WHITE
        set(value) { field = value; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.color = color
        if (kind == MIC) {
            paint.strokeWidth = w * 0.07f
            val bodyW = w * 0.32f
            c.drawRoundRect(RectF(w / 2 - bodyW / 2, h * 0.08f, w / 2 + bodyW / 2, h * 0.58f), bodyW / 2, bodyW / 2, paint)
            c.drawArc(RectF(w * 0.22f, h * 0.30f, w * 0.78f, h * 0.76f), 0f, 180f, false, paint)
            c.drawLine(w / 2, h * 0.76f, w / 2, h * 0.92f, paint)
            c.drawLine(w * 0.34f, h * 0.92f, w * 0.66f, h * 0.92f, paint)
        } else {
            paint.strokeWidth = h * 0.09f
            c.drawLine(w * 0.12f, h * 0.25f, w * 0.88f, h * 0.25f, paint)
            c.drawLine(w * 0.12f, h * 0.50f, w * 0.88f, h * 0.50f, paint)
            c.drawLine(w * 0.12f, h * 0.75f, w * 0.60f, h * 0.75f, paint)
        }
    }
}
