package dev.alder

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout

/**
 * Alder on a phone or tablet: the TV's own screens, fitted to a handheld and driven by touch.
 *
 * The layouts are drawn for a 960 x 480 dp TV. A handheld gets a density that makes its short side that tall, so every
 * screen keeps its proportions (rows show the same number of posters, text keeps its place) instead of overflowing a
 * phone's 360 dp. The price is that controls are smaller than Android's usual 48 dp, which Settings › Text and poster
 * size (up to 115%) buys back in part. Held upright, a handheld keeps its own 344 dp width instead ([narrow]), and the
 * screens with a narrow form stack their panes.
 */
internal object Handheld {
    /** The short side, in dp, the TV layouts are drawn for. */
    const val DESIGN_SHORT_DP = 480

    /** The width, in dp, a portrait phone's screens are laid out in: an ordinary phone's own, so controls keep their size. */
    const val PORTRAIT_WIDTH_DP = 344

    /** The back arrow's touch target, in dp: a 34 dp disc in the left margin that [Screen.PAD] leaves free. */
    private const val ARROW_DP = 48
    private const val ARROW_TOP_DP = 6

    /** A TV, including Android TV boxes and Fire TV: the screens keep their 10-foot sizing and remote-only controls. */
    fun isTv(context: Context): Boolean =
        (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    fun isHandheld(context: Context) = !isTv(context)

    /**
     * A phone (or small tablet) held upright: a handheld whose short side is under the 600 dp phone/tablet breakpoint,
     * the same width that keeps a portrait phone's screens phone-sized. Tablets and anything wider fall back to the
     * original TV-style nav (top bar with avatar, search icon, clock) even on a handheld-sized screen.
     */
    fun isPhone(context: Context): Boolean =
        isHandheld(context) && context.resources.configuration.smallestScreenWidthDp < 600

    /** The density (dpi) that makes a screen whose short side is [shortSidePx] pixels [DESIGN_SHORT_DP] dp tall. */
    fun fittedDpi(shortSidePx: Int): Int = (shortSidePx * 160f / DESIGN_SHORT_DP).toInt().coerceIn(100, 800)

    /**
     * Multiplier on every text size ([Screen.text]) so a handheld held sideways, where the 480 dp design squeezes the
     * text to about 0.76 of its size, still reads at 12 sp or more for the smallest text. 1 on a TV and upright.
     */
    var textBoost = 1f

    /**
     * Extra size a screen asks of the density on a phone (see [Screen.handheldZoom]): the player, which is always
     * sideways, gets about a quarter more so its controls and panels are finger-sized. 1 for every other screen.
     */
    var zoom = 1f

    /** Posters across a grid page (My List): six on a TV, three on an upright phone. */
    var columns = 6

    /** The width of Home's one-line story under the title, in dp. */
    var synopsisW = 445

    /** Sets the shared layout numbers (side margin, hero width, grid columns) for the screen about to be built. */
    fun layoutFor(context: Context) {
        val narrow = narrow(context)
        Screen.PAD = if (narrow) 20 else 48
        Screen.HERO_W = if (narrow) PORTRAIT_WIDTH_DP - 40 else 520
        columns = if (narrow) 3 else 6
        synopsisW = if (narrow) PORTRAIT_WIDTH_DP - 40 else 445
        touchSizes(context)
        textBoost = if (isTv(context)) 1f else (12f / (Screen.SMALL * realDpPerDesignDp(context))).coerceIn(1f, 1.3f)
    }

    /**
     * How many real dp one design dp is on this screen: 1 on a phone held upright, about 0.76 on a typical phone held
     * sideways (the 480 dp design height is squeezed into the short side). Settings › Text and poster size grows it.
     */
    fun realDpPerDesignDp(context: Context): Float {
        val real = android.content.res.Resources.getSystem().displayMetrics.densityDpi.toFloat()
        return fittedDpi(context) * Settings.uiScale(context) * zoom / real
    }

    /** The design dp that make [realDp] real dp on this screen: a top-bar item that must be 40 dp under a finger. */
    fun designDp(context: Context, realDp: Float): Int = Math.ceil((realDp / realDpPerDesignDp(context)).toDouble()).toInt()

    /**
     * Control heights in design dp. A TV keeps its own. A handheld raises each so it reaches a finger-sized number of
     * real dp (buttons 44, chips 36, rows 48), but never beyond 1.75 times the TV size, so a screen still fits.
     */
    private fun touchSizes(context: Context) {
        fun grow(tv: Int, minRealDp: Float): Int {
            if (isTv(context)) return tv
            val need = Math.ceil((minRealDp / realDpPerDesignDp(context)).toDouble()).toInt()
            return maxOf(tv, minOf(need, (tv * 1.75f).toInt()))
        }
        Screen.BUTTON_H = grow(34, 44f)
        Screen.ICON_BUTTON_H = grow(38, 44f)
        Screen.CHIP_H = grow(24, 36f)
        Screen.ROW_H = grow(42, 48f)
        Screen.ROW_H2 = grow(48, 56f)
    }

    /** Taller than wide: the screens stack their panes (see [narrow]) instead of sitting side by side. */
    fun isPortrait(context: Context): Boolean {
        val m = context.resources.displayMetrics
        return m.heightPixels > m.widthPixels
    }

    /** A handheld held upright, where a 960 dp-wide TV layout cannot fit and each screen has its own narrow form. */
    fun narrow(context: Context) = isHandheld(context) && isPortrait(context)

    fun fittedDpi(context: Context): Int {
        val m = context.resources.displayMetrics
        return if (m.heightPixels > m.widthPixels) (m.widthPixels * 160f / PORTRAIT_WIDTH_DP).toInt().coerceIn(100, 800)
            // Sideways: the short side fits the design height, but never so large that the long side drops under 900 dp.
            else minOf(fittedDpi(minOf(m.widthPixels, m.heightPixels)), (maxOf(m.widthPixels, m.heightPixels) * 160f / 900).toInt()).coerceIn(100, 800)
    }

    /** What the system bars and the display cutout take from each edge, in pixels: left, top, right, bottom. */
    @Suppress("DEPRECATION")
    internal fun edges(insets: WindowInsets): IntArray =
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            intArrayOf(i.left, i.top, i.right, i.bottom)
        } else {
            intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }

    /** The player's picture edge to edge: status and navigation bars hidden, a swipe from the edge brings them back. */
    @Suppress("DEPRECATION")
    fun immersive(activity: Activity) {
        // The picture runs under a camera notch too (landscape: into the short edge), as every video app's does.
        if (android.os.Build.VERSION.SDK_INT >= 28) activity.window.attributes = activity.window.attributes.apply {
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            activity.window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    /**
     * [content] for a handheld: kept clear of the status bar, navigation bar and camera cutout (Android 15 draws every
     * app under them), with a back arrow over its top corner when [arrow] is set, since a phone has no remote with a
     * Back key. [padInsets] false lets the content run under the bars (the player's picture).
     */
    fun wrap(activity: Activity, content: View, arrow: Boolean, padInsets: Boolean, hidden: Boolean = false,
             nav: View? = null, onArrow: (View) -> Unit = {}, back: () -> Unit): View {
        val density = activity.resources.displayMetrics.density
        val arrowPx = (maxOf(ARROW_DP, minOf(Math.ceil((48f / realDpPerDesignDp(activity)).toDouble()).toInt(), ARROW_DP * 3 / 2)) * density).toInt()
        val frame = FrameLayout(activity)
        frame.addView(content, FrameLayout.LayoutParams(-1, -1))
        val root = FrameLayout(activity)
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))
        val button = BackArrow(activity).apply {
            contentDescription = activity.getString(R.string.key_back)
            setOnClickListener { back() }
        }
        if (hidden) button.visibility = View.GONE // the player shows it with its controls
        onArrow(button)
        val navH = (BottomNav.HEIGHT_DP * density).toInt()
        if (nav != null) root.addView(nav, FrameLayout.LayoutParams(-1, navH, Gravity.BOTTOM))
        if (arrow) root.addView(button, FrameLayout.LayoutParams(arrowPx, arrowPx,
            Gravity.START or Gravity.TOP).apply { topMargin = (ARROW_TOP_DP * density).toInt() })
        root.setOnApplyWindowInsetsListener { v, insets ->
            val e = edges(insets)
            // The tab bar sits on the bottom inset; it steps aside while the keyboard is up.
            val keyboard = android.os.Build.VERSION.SDK_INT >= 30 && insets.isVisible(WindowInsets.Type.ime())
            val bar = if (nav != null && !keyboard) navH + e[3] else 0
            if (nav != null) {
                nav.visibility = if (bar > 0) View.VISIBLE else View.GONE
                nav.setPadding(0, 0, 0, e[3])
                (nav.layoutParams as FrameLayout.LayoutParams).height = navH + e[3]
                nav.requestLayout()
            }
            if (padInsets) frame.setPadding(e[0], e[1], e[2], if (nav != null && !keyboard) navH + e[3] else e[3])
            val params = button.layoutParams as? FrameLayout.LayoutParams
            if (arrow && params != null) {
                params.marginStart = if (v.layoutDirection == View.LAYOUT_DIRECTION_RTL) e[2] else e[0]
                params.topMargin = e[1] + (ARROW_TOP_DP * density).toInt()
                button.layoutParams = params
            }
            insets
        }
        return root
    }
}

/** A round dark-glass back arrow, drawn so it needs no font glyph, flipped for right-to-left languages. */
internal class BackArrow(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB30E0C0A.toInt() }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density; color = 0x33F3EEE6 }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Screen.TEXT
    }
    private val chevron = Path()

    init { isFocusable = false } // touch only; a keyboard or remote already has Back

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = 17f * density
        canvas.drawCircle(cx, cy, r, disc)
        canvas.drawCircle(cx, cy, r - density / 2f, rim)
        if (layoutDirection == LAYOUT_DIRECTION_RTL) canvas.scale(-1f, 1f, cx, cy)
        val a = 5f * density
        chevron.reset()
        chevron.moveTo(cx + a * 0.6f, cy - a * 1.4f)
        chevron.lineTo(cx - a * 0.7f, cy)
        chevron.lineTo(cx + a * 0.6f, cy + a * 1.4f)
        canvas.drawPath(chevron, line)
    }
}


/**
 * Phone-only tab bar under Home (held upright): Search, Home, Profile, each one glass icon button like the hero's menu
 * button, with no label. Home sits in the middle; the current one is lit in tungsten. Settings lives in the profile menu.
 */
internal class BottomNav(private val screen: Screen, private val onPick: (String) -> Unit) : android.widget.LinearLayout(screen) {
    private class Item(val id: String, val label: Int, val icon: Int)

    private val items = buildList {
        add(Item("search", R.string.nav_search, R.drawable.ic_search))
        add(Item("home", R.string.tab_home, R.drawable.ic_home))
        add(Item("profile", R.string.nav_profile, R.drawable.ic_person))
    }
    private val cells = mutableMapOf<String, android.widget.TextView>()
    private var selected: String? = null

    init {
        orientation = HORIZONTAL
        setBackgroundColor(0xF20E0C0A.toInt())
        elevation = 8f * resources.displayMetrics.density
        items.forEach { item ->
            val holder = arrayOfNulls<android.widget.TextView>(1)
            val button = screen.iconButton("", screen.getString(item.label), item.icon, onFocus = { has ->
                // Keep the current tab lit in tungsten once focus leaves; the glass face still brightens on focus.
                if (!has) holder[0]?.setTextColor(if (selected == item.id) Screen.TUNGSTEN else Screen.TEXT_2)
            }) { onPick(item.id) }
            holder[0] = button
            cells[item.id] = button
            addView(android.widget.LinearLayout(screen).apply {
                gravity = Gravity.CENTER
                addView(button)
            }, android.widget.LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    /** Lights [id] (search, home or profile); the others dim. */
    fun select(id: String?) {
        selected = id
        cells.forEach { (key, button) -> button.setTextColor(if (key == id) Screen.TUNGSTEN else Screen.TEXT_2) }
    }

    companion object {
        const val HEIGHT_DP = 56
    }
}
