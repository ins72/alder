package dev.alder

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import java.util.Locale
import java.text.Normalizer
import java.util.concurrent.Future

/** More complete title matches lead; ties retain the installed add-ons' order. */
internal fun <T> rankSearchTitles(items: List<T>, query: String, rating: (T) -> Double? = { null }, title: (T) -> String): List<T> {
    fun words(s: String) = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    val wanted = words(query)
    if (wanted.isEmpty()) return items
    val articles = setOf("the", "a", "an")
    // "matrix" finds "The Matrix" as fully as the short film "Matrix"; then the better-rated title goes first.
    return items.sortedWith(compareBy<T> { item ->
        val actual = words(title(item)).let { if (wanted.first() !in articles && it.firstOrNull() in articles) it.drop(1) else it }
        when {
            actual == wanted -> 0
            actual.take(wanted.size) == wanted -> 1
            wanted.all { it in actual } -> 2
            actual.joinToString(" ").contains(wanted.joinToString(" ")) -> 3
            else -> 4
        }
    }.thenByDescending { rating(it) ?: 0.0 })
}

/**
 * What a render of [sections] shows, in order: the same key means the same rows, so a late add-on that adds nothing
 * redraws nothing. Each card is given by its id, poster and name, so a preferred add-on's answer with another poster
 * for the same title still redraws.
 */
internal fun searchRenderKey(sections: Map<String, List<String>>, query: String): String =
    query + "|" + sections.entries.joinToString("|") { (label, cards) -> label + ":" + cards.joinToString(",") }

internal fun <T> searchAnswersInAddonOrder(answers: Array<List<T>?>): List<T> = answers.filterNotNull().flatten()

/** House Lights search: remote keyboard on the left, focused title and poster rows on the right. */
class SearchActivity : Screen() {
    override val navTab: String? get() = "search"
    override val showsBackArrow: Boolean get() = false // the bottom bar's Home gets you back
    private lateinit var art: ImageView
    private lateinit var artBack: ImageView
    private lateinit var input: EditText
    private lateinit var clearSearch: View
    private lateinit var resultCount: TextView
    private lateinit var recentBox: LinearLayout
    // Portrait handheld, or any tablet: the phone's one-column, system-keyboard search page (iPads match phones).
    private val upright by lazy { Handheld.narrow(this) || (Handheld.isHandheld(this) && !Handheld.isPhone(this)) }
    private lateinit var results: LinearLayout
    private lateinit var resultsScroll: ScrollView
    private lateinit var hero: View
    private lateinit var heroLogo: ImageView
    private lateinit var heroName: TextView
    private lateinit var heroFacts: TextView
    private lateinit var heroAbout: TextView
    private lateinit var heroRatings: LinearLayout
    private lateinit var keyboard: LinearLayout
    private lateinit var field: LinearLayout
    private lateinit var headerBar: View
    private lateinit var nav: LinearLayout
    private var navTabIds = emptyList<String>()
    private var searchTab: View? = null
    private val characterKeys = mutableListOf<TextView>()
    private val rightEdgeKeys = mutableSetOf<View>()
    private val cards = mutableListOf<MediaCardView>()
    private var firstKey: View? = null
    private var lastKey: View? = null
    private var lastKeyboardFocus: View? = null
    private var firstResult: View? = null
    private var resultsAction: View? = null
    private var focused: Meta? = null
    private var rendered: String? = null // [searchRenderKey] of what's on screen
    private var numeric = false
    private var request = 0
    private var userMovedFocus = false
    private val searchTasks = mutableListOf<Future<*>>()
    private val typed = Runnable { search(false) }
    override val backdrop get() = art

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = authKey ?: return finish()
        art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        artBack = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val width = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val height = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(BASE); clipChildren = false }
        root.addView(artBack, FrameLayout.LayoutParams(-1, -1))
        root.addView(art, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xA60E0C0A.toInt(), 0x8C0E0C0A.toInt(), 0x660E0C0A, 0x1A0E0C0A))
        }, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xF70E0C0A.toInt(), 0xBF0E0C0A.toInt(), 0x000E0C0A))
        }, FrameLayout.LayoutParams(-1, dp(260), Gravity.BOTTOM))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x8C0E0C0A.toInt(), 0x000E0C0A))
        }, FrameLayout.LayoutParams(-1, dp(80), Gravity.TOP))
        headerBar = header()
        root.addView(headerBar, FrameLayout.LayoutParams(-1, dp(76)))

        field = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(10), 0)
        }
        field.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = ColorStateList.valueOf(TEXT_2)
        }, LinearLayout.LayoutParams(dp(15), dp(15)))
        input = EditText(this).apply {
            id = View.generateViewId()
            hint = getString(R.string.nav_search)
            setHintTextColor(TEXT_3)
            setTextColor(TEXT)
            textSize = BODY
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            showSoftInputOnFocus = Handheld.isHandheld(this@SearchActivity) // a TV has its own keys; a phone's keyboard is better
            background = null
            setPadding(dp(10), 0, dp(5), 0)
            setSelectAllOnFocus(false)
        }
        // The field's own caret shows only while it has focus, and focus lives on our keys: a tungsten bar after the
        // last letter stands in for it.
        val inputBox = FrameLayout(this)
        inputBox.addView(input, FrameLayout.LayoutParams(-1, -1))
        cursor = View(this).apply { setBackgroundColor(TUNGSTEN) }
        inputBox.addView(cursor, FrameLayout.LayoutParams(dp(1), dp(15), Gravity.CENTER_VERTICAL))
        field.addView(inputBox, LinearLayout.LayoutParams(0, dp(34), 1f))
        resultCount = text("", SMALL, color = TEXT_3)
        field.addView(resultCount)
        val clear = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(TEXT_2)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            contentDescription = getString(R.string.search_clear)
            visibility = View.GONE
            rowFocus(11)
            setOnClickListener {
                input.setText("")
                main.removeCallbacks(typed)
                search(false)
                firstKey?.requestFocus()
            }
        }
        field.addView(clear, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(4) })
        clearSearch = clear
        val fieldRest = glassOver(field, 17, shade = FROST_DENSE)
        val fieldFocused = glass(17, focused = true)
        field.background = fieldRest
        input.setOnFocusChangeListener { _, has ->
            field.background = if (has) fieldFocused else fieldRest
            placeCursor()
            if (!has) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
            }
        }
        if (Handheld.isHandheld(this)) field.setOnClickListener {
            input.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        root.addView(field, FrameLayout.LayoutParams(if (upright) -1 else dp(284), dp(34)).apply { leftMargin = dp(PAD); topMargin = dp(88); if (upright) rightMargin = dp(PAD) })

        keyboard = column(Gravity.START).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = glassOver(this, 14, shade = FROST_DENSE)
        }
        buildKeyboard()
        if (!upright) root.addView(keyboard, FrameLayout.LayoutParams(dp(284), dp(190)).apply { leftMargin = dp(PAD); topMargin = dp(134) })

        recentBox = column(Gravity.START).apply { clipChildren = false; clipToPadding = false }
        root.addView(recentBox, FrameLayout.LayoutParams(if (upright) -1 else dp(284), -2).apply { leftMargin = dp(PAD); topMargin = dp(if (upright) 136 else 345); if (upright) rightMargin = dp(PAD) })
        showRecent()

        heroLogo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_START
            adjustViewBounds = true
            maxHeight = dp(55)
        }
        heroName = text("", SERIF_HERO, serif = true).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        heroFacts = text("", SMALL, color = TEXT_2).onArt()
        heroRatings = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heroAbout = text("", BODY, color = TEXT).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        hero = column(Gravity.START).apply {
            addView(heroLogo, LinearLayout.LayoutParams(-2, dp(55)))
            addView(heroName, LinearLayout.LayoutParams(-1, -2))
            addView(LinearLayout(this@SearchActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(heroFacts)
                addView(heroRatings)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(heroAbout, LinearLayout.LayoutParams(dp(HERO_W), -2).apply { topMargin = dp(6) })
            visibility = View.INVISIBLE
        }
        if (!upright) root.addView(hero, FrameLayout.LayoutParams(dp((width - 408).toInt()), -2).apply {
            leftMargin = dp(360); topMargin = dp(84)
        })
        results = column(Gravity.START).apply { setPadding(0, 0, 0, dp(32)) }
        resultsScroll = object : ScrollView(this) {
            override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect) = 0
        }.apply {
            isVerticalScrollBarEnabled = false
            outlineProvider = ViewOutlineProvider.BOUNDS
            clipToOutline = true
            addView(results)
        }
        // Runs to the screen's right edge, as Home's rows do: stopping 48 dp short sliced the last poster in half.
        if (upright) root.addView(resultsScroll, FrameLayout.LayoutParams(-1, -1).apply { leftMargin = dp(PAD - 8); topMargin = dp(132) })
        else root.addView(resultsScroll, FrameLayout.LayoutParams(dp((width - 360).toInt()), dp((height - 232).toInt())).apply {
            leftMargin = dp(360); topMargin = dp(232)
        })
        window.setBackgroundDrawable(null)
        setContentView(root)
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ ->
            userMovedFocus = true
            lastKey?.let { key ->
                if (!key.hasFocus()) updateKeyStyle(key, false)
            }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                placeCursor()
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                if (upright) recentBox.visibility = if (s.isNullOrEmpty()) View.VISIBLE else View.GONE
                repositionResults()
                ++request
                searchTasks.forEach { it.cancel(true) }
                searchTasks.clear()
                main.removeCallbacks(typed)
                main.postDelayed(typed, TYPING_PAUSE)
            }
        })
        input.setOnEditorActionListener { _, _, _ ->
            main.removeCallbacks(typed)
            search(true)
            true
        }
        // Our keyboard first: the field never takes focus on opening, so the Android keyboard stays shut until the keyboard key.
        if (upright) {
            // No custom keys upright: the phone's own keyboard opens on the field.
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            input.requestFocus()
        } else {
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        firstKey?.post { firstKey?.requestFocus() }
        }
        search(false)
        repositionResults()
    }

    private lateinit var cursor: View

    private fun placeCursor() {
        val empty = input.text.isNullOrEmpty()
        cursor.visibility = if (empty || input.hasFocus()) View.GONE else View.VISIBLE
        cursor.translationX = input.paddingStart + input.paint.measureText(input.text.toString())
    }

    /**
     * On a portrait phone the recent searches sit at the top and the popular rows must start below them, not on top.
     * The recent box's height varies (0–4 chips), so the results' top is measured from its bottom once it's laid out.
     */
    private fun repositionResults() {
        if (!upright) return
        val lp = resultsScroll.layoutParams as? FrameLayout.LayoutParams ?: return
        if (input.text.isNullOrEmpty() && recentBox.visibility == View.VISIBLE) {
            recentBox.post {
                if (isDestroyed) return@post
                val top = if (recentBox.height > 0) recentBox.bottom + dp(14) else dp(132)
                if (lp.topMargin != top) { lp.topMargin = top; resultsScroll.layoutParams = lp }
            }
        } else if (lp.topMargin != dp(132)) {
            lp.topMargin = dp(132)
            resultsScroll.layoutParams = lp
        }
    }

    override fun onResume() {
        super.onResume()
        if (::nav.isInitialized) updateNavTabs()
    }

    // The add-ons' catalog types, for Live and the per-type tabs that Home shows too.
    private var catalogTypes: List<String> = emptyList()

    /** A top-bar item's size in design dp: the TV's own, or on a phone what puts [realDp] under a finger (as on Home). */
    private fun barDp(realDp: Float, tv: Int) = if (Handheld.isPhone(this)) Handheld.designDp(this, realDp) else tv

    private fun header(): View {
        val profile = Profiles.active(this)
        val avatarSize = barDp(40f, 30)
        val bar = FrameLayout(this).apply { setPadding(dp(PAD), dp(18), dp(PAD), dp(6)) }
        val avatar = text(profile?.name?.take(1)?.uppercase() ?: "A", SMALL, bold = true, color = TUNGSTEN).apply {
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.nav_profile)
            val rest = glassOver(this, 20)
            fun style(focused: Boolean) {
                background = if (focused) glass(20, true) else rest
                setTextColor(TUNGSTEN)
                grow(focused).start()
            }
            style(false)
            isFocusable = true
            setOnFocusChangeListener { _, has -> style(has) }
            pressable()
            setOnClickListener {
                val items = buildList {
                    add(getString(R.string.menu_switch_profile))
                    add(getString(R.string.menu_settings))
                }
                choose(profile?.name ?: getString(R.string.nav_profile), items) { picked ->
                    startActivity(Intent(this@SearchActivity,
                        if (picked == 0) ProfilesActivity::class.java else SettingsActivity::class.java)
                        .apply { if (picked == 0) {
                            putExtra("pick", true)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        } })
                }
            }
        }
        val avatarBox = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            addView(avatar, FrameLayout.LayoutParams(dp(avatarSize), dp(avatarSize)))
        }
        if (!Handheld.isPhone(this)) bar.addView(avatarBox, FrameLayout.LayoutParams(dp(avatarSize), dp(avatarSize), Gravity.START or Gravity.CENTER_VERTICAL))
        nav = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val searchTab = FrameLayout(this).apply {
            isFocusable = true
            contentDescription = getString(R.string.nav_search)
            addView(ImageView(this@SearchActivity).apply {
                setImageResource(R.drawable.ic_search)
                imageTintList = ColorStateList.valueOf(TEXT)
            }, FrameLayout.LayoutParams(dp(17), dp(17), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(4) })
            addView(View(this@SearchActivity).apply { background = rounded(TUNGSTEN, 3) },
                FrameLayout.LayoutParams(dp(3), dp(3), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(2) })
            setOnFocusChangeListener { _, has -> background = if (has) glass(18, true) else null }
            setOnClickListener { input.requestFocus() }
            pressable()
        }
        this.searchTab = searchTab
        // A phone has Search in its bottom bar already, so the icon tab is TV/tablet-only.
        if (!Handheld.isPhone(this)) nav.addView(searchTab, LinearLayout.LayoutParams(dp(barDp(44f, 44)), dp(barDp(40f, 30))))
        updateNavTabs()
        background({ Stremio.savedAddons(authKey.orEmpty()).orEmpty().flatMap { a -> a.catalogs.map { it.type } } }) { r ->
            r.getOrNull()?.takeIf { it.isNotEmpty() }?.let { catalogTypes = it; updateNavTabs() }
        }
        if (Handheld.isPhone(this)) {
            // The same inset frosted capsule the Home page uses: the bar is inset by PAD, the tabs scroll inside it.
            val side = dp(4)
            bar.addView(android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isHorizontalFadingEdgeEnabled = true // the tabs fade out at the edge instead of being cut mid-word
                setFadingEdgeLength(dp(28))
                background = glassOver(this, 22, shade = 0.22f, keepPrevious = false)
                setPadding(side, 0, side, 0)
                addView(nav)
            }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
            return bar
        }
        nav.background = glassOver(nav, 22, shade = 0.22f, keepPrevious = false)
        bar.addView(nav, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        bar.addView(TextClock(this).apply {
            format12Hour = "h:mm a"
            format24Hour = "HH:mm"
            textSize = SMALL
            setTextColor(TEXT_2)
            typeface = text("", SMALL).typeface
            if (Handheld.isPhone(this@SearchActivity)) visibility = View.GONE // the phone's status bar has the time
        }, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        return bar
    }

    private fun updateNavTabs() {
        // The page goes to MainActivity by its English name; the bar shows it in the viewer's language.
        val pages = mapOf(
            "home" to ("Home" to getString(R.string.tab_home)),
            "discover" to ("Discover" to getString(R.string.tab_discover)),
            "shows" to ("Shows" to getString(R.string.tab_shows)),
            "movies" to ("Movies" to getString(R.string.tab_movies)),
            "live" to ("Live" to getString(R.string.tab_live)),
            "library" to ("My Alder" to bt(R.string.tab_my_list)),
        )
        val visible = orderTabs(Settings.tabOrder(this), tabsFor(catalogTypes)).filter { Settings.tabShown(this, it) }
        // A phone keeps Home, My Alder and the channel catalog in its bottom bar / profile menu, so they stay off this bar.
        val handheld = Handheld.isPhone(this)
        val ids = (listOf("home") + visible).filter { id ->
            !handheld || (id != "home" && id != "library" && !id.removePrefix("type:").equals("channel", ignoreCase = true))
        }
        if (ids == navTabIds) return
        val st = searchTab
        val base = if (st != null && nav.indexOfChild(st) == 0) 1 else 0
        val focusedId = nav.focusedChild?.let { navTabIds.getOrNull(nav.indexOfChild(it) - base) }
        navTabIds = ids
        nav.removeViews(base, nav.childCount - base)
        ids.forEach { id ->
            // A per-type tab ("type:anime") goes to Home's page of that type, which Home names by the type.
            val (page, words) = pages[id] ?: id.removePrefix("type:").let { it to typeTabName(this, it) }
            nav.addView(text(words, BODY, color = TEXT_2).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                isFocusable = true
                pressable()
                setOnFocusChangeListener { _, has ->
                    background = if (has) glass(18, true) else null
                    setTextColor(if (has) TEXT else TEXT_2)
                }
                setOnClickListener {
                    startActivity(Intent(this@SearchActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("page", page))
                    finish()
                }
            }, LinearLayout.LayoutParams(-2, dp(barDp(40f, 30))).apply { marginStart = dp(4) })
        }
        if (focusedId != null) nav.getChildAt(ids.indexOf(focusedId) + base).requestFocus()
    }

    private fun buildKeyboard() {
        val letterRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val letters = letterRows.joinToString("")
        val rows = mutableListOf<List<View>>()
        letterRows.forEach { rowLetters ->
            val row = keyRow()
            val built = mutableListOf<View>()
            rowLetters.forEachIndexed { column, letter ->
                val index = characterKeys.size
                val key = key(letter.toString(), row, 1, column == rowLetters.lastIndex) {
                    append(characterKeys[index].text.toString())
                }
                characterKeys += key
                built += key
            }
            rows += built
        }
        val controls = keyRow()
        val mode = key("123", controls, 3, false) {
            numeric = !numeric
            val symbols = "1234567890@#$%&*()!?.,:/+"
            characterKeys.forEachIndexed { index, view ->
                view.text = if (numeric) symbols.getOrNull(index)?.toString() ?: "·" else letters[index].toString()
            }
            modeLabel?.text = if (numeric) "ABC" else "123"
        }
        modeLabel = mode
        val hyphen = key("−", controls, 2, false) { append("-") }
        val backspace = key("⌫", controls, 5, true) { delete() }
        rows += listOf(mode, hyphen, backspace)

        val last = keyRow()
        val speechAvailable = SpeechRecognizer.isRecognitionAvailable(this)
        val space = key(getString(R.string.search_space).uppercase(), last, if (speechAvailable) 8 else 10, !speechAvailable) { append(" ") }
        if (speechAvailable) {
            val mic = ImageView(this).apply {
                id = View.generateViewId()
                setImageResource(R.drawable.ic_mic)
                imageTintList = ColorStateList.valueOf(TEXT_2)
                scaleType = ImageView.ScaleType.CENTER
                isFocusable = true
                contentDescription = getString(R.string.nav_search)
                setOnFocusChangeListener { v, has ->
                    if (has) lastKeyboardFocus = v
                    updateKeyStyle(v, has)
                    imageTintList = ColorStateList.valueOf(if (has) TEXT else TEXT_2)
                }
                setOnClickListener {
                    runCatching {
                        startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        }, VOICE_REQUEST)
                    }
                }
            }
            rightEdgeKeys += mic
            last.addView(mic, LinearLayout.LayoutParams(dp(49), -1).apply { marginStart = dp(3) })
            rows += listOf(space, mic)
        } else {
            rows += listOf(space)
        }

        val r0 = rows[0]
        val r1 = rows[1]
        val r2 = rows[2]
        val r3 = rows[3]
        val r4 = rows[4]
        r0.forEachIndexed { i, v ->
            v.nextFocusUpId = input.id
            v.nextFocusDownId = r1[(i * r1.size) / r0.size].id
        }
        r1.forEachIndexed { i, v ->
            v.nextFocusUpId = r0[(i * r0.size + r1.size / 2) / r1.size].id
            v.nextFocusDownId = r2[(i - 1).coerceIn(0, r2.lastIndex)].id
        }
        r2.forEachIndexed { i, v ->
            v.nextFocusUpId = r1[(i + 1).coerceIn(0, r1.lastIndex)].id
            v.nextFocusDownId = r3[when { i <= 1 -> 0; i <= 3 -> 1; else -> 2 }].id
        }
        r3[0].nextFocusUpId = r2[0].id
        r3[1].nextFocusUpId = r2[2].id
        r3[2].nextFocusUpId = r2[5].id
        r3.forEachIndexed { i, v ->
            v.nextFocusDownId = if (r4.size > 1 && i == 2) r4[1].id else r4[0].id
        }
        r4[0].nextFocusUpId = r3[1].id
        if (r4.size > 1) r4[1].nextFocusUpId = r3[2].id
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE_REQUEST && resultCode == RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim() ?: return
            input.setText(spoken)
            input.setSelection(spoken.length)
            main.removeCallbacks(typed)
            search(true)
        }
    }

    private var modeLabel: TextView? = null

    private fun keyRow() = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        keyboard.addView(this, LinearLayout.LayoutParams(-1, dp(30)).apply { if (keyboard.childCount > 0) topMargin = dp(4) })
    }

    private fun updateKeyStyle(view: View, has: Boolean) {
        view.background = if (has) rounded(GLASS_FOCUSED, 7) else null
        if (view is TextView) view.setTextColor(if (has) TEXT else TEXT_2)
    }

    private fun key(label: String, row: LinearLayout, span: Int, rightEdge: Boolean, click: () -> Unit): TextView {
        val view = text(label, if (label.length > 1) SMALL else BODY, color = TEXT_2).apply {
            id = View.generateViewId()
            gravity = Gravity.CENTER
            isFocusable = true
        }
        view.setOnFocusChangeListener { v, has ->
            if (has) lastKeyboardFocus = view
            updateKeyStyle(v, has)
        }
        view.setOnClickListener {
            val previous = lastKey
            lastKey = view
            if (previous != null && previous !== view && !previous.hasFocus()) updateKeyStyle(previous, false)
            click()
        }
        row.addView(view, LinearLayout.LayoutParams(dp(23 * span + 3 * (span - 1)), -1).apply {
            if (row.childCount > 0) marginStart = dp(3)
        })
        if (firstKey == null) firstKey = view
        if (rightEdge) rightEdgeKeys += view
        return view
    }

    private fun append(value: String) {
        val at = input.selectionStart.coerceIn(0, input.length())
        input.text.insert(at, value)
        input.setSelection(at + value.length)
    }

    private fun delete() {
        val at = input.selectionStart.coerceIn(0, input.length())
        if (at > 0) {
            input.text.delete(at - 1, at)
            input.setSelection(at - 1)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focus = currentFocus
            if (event.keyCode == KeyEvent.KEYCODE_BACK &&
                (resultsScroll.hasFocus() || recentBox.hasFocus() || headerBar.hasFocus() || input.hasFocus() || clearSearch.hasFocus())) {
                if ((lastKeyboardFocus ?: firstKey)?.requestFocus() == true) return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && focus === input && clearSearch.visibility == View.VISIBLE && clearSearch.requestFocus()) return true
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && focus === clearSearch && input.requestFocus()) return true
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && (focus === input || focus === clearSearch)) {
                if ((lastKeyboardFocus ?: firstKey)?.requestFocus() == true) return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && focus in rightEdgeKeys) {
                if ((firstResult ?: resultsAction)?.requestFocus() == true) return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && cards.any { it === focus }) {
                if (firstResult === focus || cards.indexOfFirst { it === focus } == 0) {
                    if ((lastKeyboardFocus ?: firstKey)?.requestFocus() == true) return true
                }
            }
            if (event.keyCode == KeyEvent.KEYCODE_DEL) { delete(); return true }
            if (event.keyCode == KeyEvent.KEYCODE_SPACE) { append(" "); return true }
            val char = event.unicodeChar.takeIf { it in 32..126 }?.toChar()
            if (char != null && !event.isCtrlPressed && !event.isAltPressed) {
                append(char.toString())
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun recentKey() = "recent:${Profiles.active(this)?.id.orEmpty()}"

    private fun recent() = getSharedPreferences("search", MODE_PRIVATE).getString(recentKey(), "").orEmpty()
        .split("\n").filter { it.isNotBlank() }

    private fun remember(query: String) {
        val values = (listOf(query) + recent().filterNot { it.equals(query, ignoreCase = true) }).take(RECENT)
        getSharedPreferences("search", MODE_PRIVATE).edit().putString(recentKey(), values.joinToString("\n")).apply()
        showRecent()
    }

    private fun showRecent(restoreFocus: Boolean = false) {
        recentBox.removeAllViews()
        val values = recent().take(RECENT)
        if (values.isEmpty()) {
            if (restoreFocus) (lastKeyboardFocus ?: firstKey)?.requestFocus()
            return
        }
        recentBox.addView(caps(R.string.search_recent, TEXT_3), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        var firstChip: View? = null
        values.chunked(2).forEach { pair ->
            val line = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
            pair.forEach { query ->
                val chipView = chip(query).apply {
                    textSize = SMALL
                    maxWidth = dp(110)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    contentDescription = getString(R.string.search_recent_hint, query)
                    setOnClickListener {
                        input.setText(query)
                        input.setSelection(query.length)
                        main.removeCallbacks(typed)
                        search(true)
                    }
                    setOnLongClickListener {
                        choose(getString(R.string.search_remove_recent),
                            listOf(getString(R.string.search_remove_query, query), getString(R.string.action_cancel))) { picked ->
                            if (picked == 0) {
                                getSharedPreferences("search", MODE_PRIVATE).edit()
                                    .putString(recentKey(), recent().filterNot { it == query }.joinToString("\n")).apply()
                                showRecent(restoreFocus = true)
                            }
                        }
                        true
                    }
                }
                if (firstChip == null) firstChip = chipView
                line.addView(chipView, LinearLayout.LayoutParams(-2, dp(CHIP_H)).apply { if (line.childCount > 0) marginStart = dp(5) })
            }
            recentBox.addView(line, LinearLayout.LayoutParams(-1, dp(CHIP_H + 3)))
        }
        recentBox.addView(text(R.string.search_hold_to_remove, SMALL, color = TEXT_3),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        if (restoreFocus) (firstChip ?: lastKeyboardFocus ?: firstKey)?.requestFocus()
    }

    private fun resetBackdrop() {
        art.animate().cancel()
        art.tag = null
        art.setImageDrawable(null)
        artBack.setImageDrawable(null)
        art.alpha = 0f
        refrost()
    }

    private fun showLoading() {
        resultsAction = null
        results.removeAllViews()
        results.addView(ring(), LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            topMargin = dp(24)
            marginStart = dp(16)
        })
    }

    private fun showEmpty() {
        resetBackdrop()
        hero.visibility = View.INVISIBLE
        results.removeAllViews()
        val clearBtn = pill(R.string.search_clear).apply {
            setOnClickListener {
                input.setText("")
                main.removeCallbacks(typed)
                search(false)
                firstKey?.requestFocus()
            }
            setOnKeyListener { _, code, event ->
                if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT)
                    (lastKeyboardFocus ?: firstKey)?.requestFocus() == true
                else false
            }
        }
        resultsAction = clearBtn
        results.addView(column(Gravity.START,
            text(R.string.search_no_matches, BODY, color = TEXT),
            clearBtn.marginTop(14),
        ).apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = glassOver(this, 14, shade = FROST_DENSE)
        })
    }

    private fun showFailed(message: String, retry: () -> Unit): View {
        val card = failed(message, retry)
        val btn = card.getChildAt(1)?.apply {
            setOnKeyListener { _, code, event ->
                if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT)
                    (lastKeyboardFocus ?: firstKey)?.requestFocus() == true
                else false
            }
        }
        resultsAction = btn
        results.addView(card)
        return card
    }

    private fun search(moveFocus: Boolean, previous: Failure? = null) {
        val query = input.text.toString().trim()
        val token = ++request
        userMovedFocus = false
        searchTasks.forEach { it.cancel(true) }
        searchTasks.clear()
        cards.forEach { it.unload() }
        firstResult = null
        resultsAction = null
        cards.clear()
        focused = null
        rendered = null
        hero.visibility = View.INVISIBLE
        resetBackdrop()
        resultsScroll.scrollTo(0, 0)
        resultCount.text = ""
        showLoading()
        if (query.isEmpty()) {
            background({
                val addons = Stremio.savedAddons(authKey.orEmpty()).orEmpty().ifEmpty { Stremio.addons(authKey.orEmpty()) }
                // Popular or trending catalogs first, then any other; one that fails or comes back empty gives way to
                // the next. Kept per type so the landing page shows popular movies and a popular series side by side.
                var lastError: Throwable? = null
                fun popularFor(type: String): List<Meta> =
                    addons.flatMap { it.catalogs }.filter { it.type == type }
                        .sortedByDescending { it.name.contains("popular", true) || it.name.contains("trending", true) }
                        .take(4).asSequence()
                        .map { c ->
                            runCatching { c.addon.savedCatalog(c)?.takeIf { it.isNotEmpty() } ?: c.addon.catalog(c) }
                                .onFailure { lastError = it }
                                .getOrDefault(emptyList())
                                .filterNot { isAddonMessage(it.name) }.take(PER_TYPE)
                        }
                        .firstOrNull { it.isNotEmpty() }.orEmpty()
                val movies = popularFor("movie")
                val series = popularFor("series")
                if (movies.isEmpty() && series.isEmpty() && lastError != null) throw lastError!!
                linkedMapOf(
                    getString(R.string.search_popular_movies) to movies,
                    getString(R.string.search_popular_series) to series,
                ).filterValues { it.isNotEmpty() }
            }) { answer ->
                if (token != request) return@background
                val error = answer.exceptionOrNull()
                if (error != null) FailureLog.record(FailureLog.Operation.SEARCH, failureOf(error))
                val sections = answer.getOrNull().orEmpty()
                if (sections.isEmpty()) {
                    results.removeAllViews()
                    val msg = error?.let { failureMessage(R.string.search_popular_unavailable, failureOf(it)) }
                        ?: getString(R.string.search_popular_unavailable)
                    showFailed(msg) { search(false) }
                } else render(sections, "", false)
            }
            return
        }
        background({ Stremio.savedAddons(authKey.orEmpty()) ?: Stremio.addons(authKey.orEmpty()) }) { loaded ->
            if (token != request) return@background
            val addons = loaded.getOrElse {
                val failure = failureOf(it)
                FailureLog.record(FailureLog.Operation.SEARCH, failure)
                results.removeAllViews()
                showFailed(failureMessage(R.string.search_unavailable, failure, source = "Stremio")) { search(true, failure) }
                return@background
            }
            val answers = arrayOfNulls<List<Meta>>(addons.size)
            var completed = 0
            val failures = arrayOfNulls<Failure>(addons.size)
            var finished = false
            fun update(final: Boolean = false) {
                if (token != request || finished) return
                val found = searchAnswersInAddonOrder(answers)
                    .filter { it.type == "movie" || it.type == "series" }
                    .filterNot { isAddonMessage(it.name) }
                    .distinctBy { it.type to it.id }
                val ranked = rankSearchTitles(found, query, rating = { it.rating?.toDoubleOrNull() }) { it.name }
                val sections = linkedMapOf(getString(R.string.tab_movies) to ranked.filter { it.type == "movie" }.take(PER_TYPE),
                    getString(R.string.tab_shows) to ranked.filter { it.type == "series" }.take(PER_TYPE)).filterValues { it.isNotEmpty() }
                val settled = final || completed == addons.size
                if (sections.isNotEmpty()) render(sections, query, moveFocus && settled && !userMovedFocus)
                if (!settled) return
                val failure = searchFailure(failures.filterNotNull(), completed, addons.size)
                if (sections.isEmpty()) {
                    results.removeAllViews()
                    resultCount.text = ""
                }
                if (failure != null) {
                    FailureLog.record(FailureLog.Operation.SEARCH, failure)
                    val message = if (sections.isEmpty() && answers.all { it == null }) R.string.search_unavailable else R.string.search_incomplete
                    showFailed(failureMessage(message, failure)) { search(true, failure) }
                    if (moveFocus && !userMovedFocus && sections.isEmpty()) resultsAction?.requestFocus()
                } else {
                    previous?.let { FailureLog.record(FailureLog.Operation.SEARCH, it, recovered = true) }
                    if (sections.isEmpty()) showEmpty()
                }
            }
            if (addons.isEmpty()) { update(true); return@background }
            main.postDelayed({ if (!isDestroyed && token == request) { update(true); finished = true } }, SEARCH_WAIT)
            addons.forEachIndexed { index, addon ->
                searchTasks += searchPool.submit {
                    val answer = runCatching { addon.searchCatalogs(query) }
                    main.post {
                        if (isDestroyed || token != request || finished) return@post
                        completed++
                        answer.fold({ (titles, error) ->
                            answers[index] = titles
                            failures[index] = error?.let(::failureOf)
                        }, { failures[index] = failureOf(it) })
                        update()
                        if (completed == addons.size) finished = true
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        searchTasks.forEach { it.cancel(true) }
        cards.forEach { it.unload() }
        super.onDestroy()
    }

    private fun render(sections: Map<String, List<Meta>>, query: String, moveFocus: Boolean) {
        val selected = (currentFocus?.tag as? String)?.takeIf { resultsScroll.hasFocus() }
        // Each add-on's answer calls this. Rebuilding cards that didn't change cleared their posters (blank tiles while
        // they loaded again) and could move the remote; the same rows stay as they are.
        // ponytail: any change still rebuilds the whole pane; diff rows in place if late add-ons reorder a lot.
        val key = searchRenderKey(sections.mapValues { (_, list) -> list.map { "${it.type}:${it.id}:${it.poster}:${it.background}:${it.name}" } }, query)
        if (key == rendered) {
            if (selected == null && moveFocus) firstResult?.requestFocus()
            return
        }
        rendered = key
        val posters = mutableListOf<() -> Unit>()
        cards.forEach { it.unload() }
        cards.clear()
        firstResult = null
        resultsAction = null
        results.removeAllViews()
        val total = sections.values.sumOf { it.size }
        resultCount.text = if (total == 0 || query.isEmpty()) "" else plural(R.plurals.search_titles, total) // not for Popular
        sections.forEach { (label, list) ->
            val group = column(Gravity.START)
            group.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(PAD), 0)
                addView(text(label, BODY, bold = true).onArt())
                addView(text(list.size.toString(), SMALL, color = TEXT_3).onArt(),
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(9) })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            val caption = caption("", shade = FROST_DENSE).apply {
                visibility = View.INVISIBLE
                maxWidth = dp(280)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            val rail = HorizontalCardRail(
                screen = this,
                cardGapPx = dp(CARD_GAP_RAIL),
                paddingStartPx = dp(8),
                paddingTopPx = dp(10),
                paddingEndPx = dp(PAD),
                paddingBottomPx = dp(8),
                rightFadePx = dp(30),
                stopAtStart = false,
                cullLeft = true,
            ).apply {
                onScroll = {
                    cards.getOrNull(this.selected)?.takeIf { it.hasFocus() }?.let { card ->
                        caption.translationX = (card.left - scroller.scrollX).toFloat()
                            .coerceIn(0f, (scroller.width - dp(PAD) - caption.width).coerceAtLeast(0).toFloat())
                    }
                }
            }
            list.forEachIndexed { index, meta ->
                rail.appendCard(card(meta, caption, rail, index, index == 0, posters))
            }
            group.addView(rail.scroller, LinearLayout.LayoutParams(-1, dp(POSTER_H + 18)))
            group.addView(caption, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            results.addView(group, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) })
        }
        // The first [Images.THREADS] requests start at once on idle threads; the rest wait in a newest-first queue. So the
        // first cards (the ones on screen) go first, then the rest last card first, which puts the next on-screen card
        // at the front of the queue.
        (posters.take(Images.THREADS) + posters.drop(Images.THREADS).asReversed()).forEach { it() }
        sections.values.firstOrNull()?.firstOrNull()?.let(::show)
        val target = if (selected != null) cards.firstOrNull { it.tag == selected } ?: firstResult else if (moveFocus) firstResult else null
        target?.post { target.requestFocus() }
    }

    private fun card(meta: Meta, caption: TextView, rail: HorizontalCardRail, index: Int, firstInRow: Boolean, posters: MutableList<() -> Unit>): MediaCardView {
        val card = MediaCardView(this, meta.name, meta.poster, meta.background, fadeIn = false, retryOnFailure = true).apply {
            tag = "${meta.type}:${meta.id}"
            dim(true, animate = false)
            setOnFocusChanged { has ->
                dim(!has)
                if (has) {
                    (rail.scroller.parent as? View)?.let { group ->
                        resultsScroll.smoothScrollTo(0, group.top)
                    }
                    caption.text = meta.name + (meta.year?.let { "$DOT${it.take(4)}" } ?: "")
                    caption.visibility = View.VISIBLE
                    rail.scrollToCard(index)
                    caption.post { rail.onScroll?.invoke() }
                    show(meta)
                } else caption.visibility = View.INVISIBLE
            }
            setOnClickListener {
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) remember(query)
                startActivity(Intent(this@SearchActivity, DetailsActivity::class.java)
                    .putExtra("type", meta.type).putExtra("id", meta.id).putExtra("name", meta.name)
                    .putExtra("poster", meta.poster).putExtra("background", meta.background))
            }
            if (firstInRow) setOnKeyListener { _, code, event ->
                if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT)
                    (lastKeyboardFocus ?: firstKey)?.requestFocus() == true
                else false
            }
        }
        cards += card
        if (firstResult == null) firstResult = card
        posters += { card.load() }
        return card
    }

    private fun show(meta: Meta) {
        if (focused?.type == meta.type && focused?.id == meta.id) return
        focused = meta
        hero.visibility = View.VISIBLE
        val url = meta.background ?: meta.poster
        if (url != null && url != art.tag) {
            art.animate().cancel()
            if (art.drawable != null) artBack.setImageDrawable(art.drawable)
            art.alpha = 0f
            Images.load(url, art, resources.displayMetrics.widthPixels, urgent = true, failed = {
                if (focused === meta) {
                    art.setImageDrawable(artBack.drawable)
                    art.alpha = 1f
                    refrost()
                }
            }) {
                if (focused === meta) {
                    art.enter(PAGE_MS).alpha(1f).withEndAction {
                        if (focused === meta) artBack.setImageDrawable(null)
                    }.start()
                    refrost()
                }
            }
        } else if (url == null) {
            resetBackdrop()
        }
        heroName.text = meta.name
        heroLogo.tag = null
        heroLogo.setImageDrawable(null)
        heroName.visibility = View.VISIBLE
        heroLogo.visibility = View.GONE
        Images.load(meta.logo ?: Images.logoFor(meta.id), heroLogo, dp(260), urgent = true) {
            if (focused === meta) { heroLogo.visibility = View.VISIBLE; heroName.visibility = View.GONE }
        }
        val length = if (hasEpisodes(meta.type, meta.videos)) {
            meta.videos.map { it.season }.filter { it > 0 }.distinct().size.takeIf { it > 0 }?.let { plural(R.plurals.seasons, it) }
        } else meta.runtime?.let(::runtime)
        val factsText = listOfNotNull(meta.year, meta.genres.firstOrNull(), length).joinToString(DOT)
        val ratings = shownRatings(Settings.ratings(this), meta.rating, null)
        heroFacts.text = if (factsText.isNotEmpty() && ratings.isNotEmpty()) "$factsText$DOT" else factsText
        heroRatings.removeAllViews()
        addRatings(heroRatings, ratings, firstGap = 0)
        val description = meta.description.orEmpty()
        heroAbout.text = description
        heroAbout.visibility = if (meta.description.isNullOrBlank()) View.GONE else View.VISIBLE
        heroAbout.post {
            if (focused !== meta || heroAbout.text.toString() != description) return@post
            val layout = heroAbout.layout ?: return@post
            if (layout.lineCount >= 2 && layout.getEllipsisCount(1) > 0) {
                val end = layout.getLineStart(1) + layout.getEllipsisStart(1)
                heroAbout.text = synopsisAtSentence(description, end)
            }
        }
    }

    private companion object {
        const val RECENT = 4
        const val PER_TYPE = 12
        const val TYPING_PAUSE = 500L
        const val SEARCH_WAIT = 6000L
        const val VOICE_REQUEST = 101
        val searchPool = Images.newestFirst(8)
    }
}
