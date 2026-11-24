package dev.alder

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

// Profile picker, Stremio sign-in, and setting up Watchly.
// The app starts here; with a single profile it goes straight on to Home.
class ProfilesActivity : Screen() {
    // The sign-in code being waited on; anything else stops its polling loop.
    private var waitingCode: String? = null
    private var signingIn = false
    private var draftName = ""
    private var draftColor = Profiles.COLORS[0]
    private var pickerGeneration = 0
    private var signInGeneration = 0
    private var nameInput: EditText? = null
    // The household manager is one persistent dialog: navigating inside it swaps its content, so it stays open through
    // interactions and only closes when the user leaves the household view or closes it.
    private var familyDialog: android.app.Dialog? = null
    private var familyOpen = false
    private var familyNotice: String? = null
    // The unlocked profile the picker was opened from (via "pick"); null otherwise so Back cannot bypass a PIN.
    private var originProfileId: String? = null
    // Where Back goes from the profile menu's screens (PIN, rename); null elsewhere.
    private var backTo: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Branding.init(this)
        // If the viewer left the player in Picture-in-Picture and is now tapping the app icon from home,
        // bring the active player back instead of showing the profile picker / home.
        val prefs = getSharedPreferences("pip_resume", Context.MODE_PRIVATE)
        if (prefs.getBoolean("active", false)) {
            prefs.edit().putBoolean("active", false).apply()
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra("type", prefs.getString("type", "movie"))
                putExtra("id", prefs.getString("metaId", ""))
                putExtra("videoId", prefs.getString("videoId", ""))
                putExtra("name", prefs.getString("name", ""))
                putExtra("picked", true)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
            finish()
            return
        }
        // Pull the operator's branding (product name, sign-in lines) so this screen reads "<name>" not a hardcoded
        // one; it is cached for next time and refreshed here when the network answers.
        background({ runCatching { Stremio.config() }.getOrNull() }) { r ->
            r.getOrNull()?.let { Branding.update(this, it.brand) }
        }
        val pick = intent.getBooleanExtra("pick", false)
        // Settings asks before Clear storage: any locked profile's PIN answers it. Only the result goes back, so
        // another app that sends this extra gets nothing cleared.
        if (intent.getBooleanExtra(UNLOCK, false)) {
            val pins = Profiles.all(this).mapNotNull { it.pin }
            if (pins.isEmpty()) { setResult(RESULT_OK); return finish() }
            return askPin(getString(R.string.clear_storage_title), { setResult(RESULT_CANCELED); finish() }) { typed ->
                (typed in pins).also { ok -> if (ok) { setResult(RESULT_OK); finish() } }
            }
        }
        // Pull the profile list synced from the person's other devices before deciding what to show, so a profile
        // added, renamed or removed elsewhere is reflected here on launch. Off the main thread; a no-op when there
        // is no signed-in account or the device is offline, and the decision runs on the main thread when it returns.
        background({
            ProfileSync.pull(this)
            // Bring the household onto this device and prune stale cards before the picker reads the list, so a member
            // who left (or a card healed from a blank/"null" name) is already reflected here.
            activeKey()?.let { ProfileSync.materializeHousehold(this, it) }
        }) {
            // A plan that shrank below the household's size blocks here until enough members are removed to fit.
            enforceSeatLimit { routeProfiles(pick) }
        }
    }

    /** Choose the first screen from the (already synced) local profile list. Runs on the main thread. */
    private fun routeProfiles(pick: Boolean) {
        // "Add member" from the household hub: go straight to naming the new profile, then sign in / register.
        if (intent.getBooleanExtra("add", false)) { showName(add = true); return }
        val profiles = Profiles.all(this)
        // Home found this profile signed out: straight to its sign-in, the profile and its settings kept.
        // The activity is exported, so the extra is untrusted: enter() still asks for the PIN, and a profile that
        // isn't signed out just opens.
        val again = intent.getStringExtra("signIn")?.let { id -> profiles.firstOrNull { it.id == id } }
        when {
            again != null -> enter(again)
            profiles.isEmpty() -> showName()
            profiles.size == 1 && !pick -> enter(profiles[0])
            else -> {
                if (pick) {
                    val storedActiveId = getSharedPreferences("account", MODE_PRIVATE).getString("active", null)
                    originProfileId = pickerReturnProfile(profiles, storedActiveId)?.id
                }
                showPicker()
            }
        }
    }

    /** The signed-in account on this device: the active profile's key, or the only profile's, else null. */
    private fun activeKey(): String? {
        val prefs = getSharedPreferences("account", MODE_PRIVATE)
        val activeId = prefs.getString("active", null)
        val list = Profiles.all(this)
        return list.firstOrNull { it.id == activeId }?.authKey?.takeIf { it.isNotEmpty() }
            ?: list.singleOrNull()?.authKey?.takeIf { it.isNotEmpty() }
    }

    /**
     * When the signed-in owner/manager's plan shrank below the household's current size (a downgrade, or members who
     * joined while a larger plan was active), block the launch with a "remove someone to fit" prompt until the
     * household is back within its seats. Only an account that can manage members is held here: a plain member cannot
     * prune the household, and the owner is never removable, so the removable joined members always cover the overflow.
     * [then] runs once the household fits (or the check can't apply: offline, no plan, or a non-managing member).
     */
    private fun enforceSeatLimit(then: () -> Unit) {
        val key = activeKey()
        if (key == null) { then(); return }
        background({ Stremio.household(key) }) { result ->
            val h = result.getOrNull()
            if (h == null || !h.active || !h.canManage) { then(); return@background }
            if (ProfileGate.overLimit(h.used, h.seats) <= 0) { then(); return@background }
            showSeatOverflow(h, key, then)
        }
    }

    /** The non-dismissible "N seats over your limit, pick who to remove" dialog; repeats until the plan fits. */
    private fun showSeatOverflow(h: Stremio.Household, key: String, then: () -> Unit) {
        val over = ProfileGate.overLimit(h.used, h.seats)
        val removable = h.members.filter { it.joined }
        // Owner is never removable; if somehow still over with no one to drop, let the person through rather than lock.
        if (over <= 0 || removable.isEmpty()) { then(); return }
        val dialog = android.app.Dialog(this).apply { requestWindowFeature(android.view.Window.FEATURE_NO_TITLE) }
        val list = column(Gravity.START).apply { setPadding(dp(12), dp(14), dp(12), dp(14)) }
        list.addView(column(Gravity.START,
            text(getString(R.string.family_over_title), BODY, bold = true),
            text(getString(R.string.family_over_hint, over), SMALL, color = TEXT_2).apply { marginTop(6) }
        ).apply { setPadding(dp(16), 0, dp(16), dp(10)) }, LinearLayout.LayoutParams(-1, -2))
        val rows = removable.map { m ->
            text(m.name?.takeIf { it.isNotBlank() } ?: m.email, BODY).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                rowFocus()
                setOnClickListener {
                    background({ Stremio.inviteRemove(key, m.email) }) { result ->
                        val r = result.getOrNull()
                        if (r == null || !r.ok) { dialog.dismiss(); householdError(r?.error); return@background }
                        // Drop this member's card too, then re-check the household; repeat until it fits.
                        Profiles.remove(this@ProfilesActivity, "h" + m.email.hashCode().toUInt().toString(16))
                        dialog.dismiss()
                        background({ Stremio.household(key) }) { r2 ->
                            val h2 = r2.getOrNull()
                            if (h2 == null || !h2.active) { then(); return@background }
                            showSeatOverflow(h2, key, then)
                        }
                    }
                }
                list.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setContentView(object : android.widget.ScrollView(this) {
            override fun onMeasure(w: Int, h2: Int) =
                super.onMeasure(w, MeasureSpec.makeMeasureSpec((resources.displayMetrics.heightPixels * 0.72f).toInt(), MeasureSpec.AT_MOST))
        }.apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(44))
            background = glassOver(this, 16, shade = FROST_READING)
            addView(list)
        })
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setLayout(minOf(dp(340), resources.displayMetrics.widthPixels - dp(32)), -2)
            setDimAmount(0.6f)
        }
        dialog.show()
        rows.firstOrNull()?.requestFocus()
    }

    // The first screen has nowhere to go back to; the steps after it do.
    override val showsBackArrow: Boolean get() = backTo != null

    override fun onDestroy() {
        waitingCode = null
        familyOpen = false
        familyDialog?.dismiss()
        familyDialog = null
        super.onDestroy()
    }

    @Deprecated("Android back callback")
    override fun onBackPressed() {
        backTo?.let { return it() }
        if (signingIn) showName() else super.onBackPressed()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val input = nameInput
        if (input != null && !input.hasFocus() && event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_DEL) {
                if (input.length() > 0) input.text.delete(input.length() - 1, input.length())
                return true
            }
            val character = event.unicodeChar.takeIf { it in 32..126 }?.toChar()
            if (character != null) { input.text.append(character); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Opens a profile, asking for its PIN first when it has one; a signed-out one signs in again first. */
    private fun enter(p: Profile) = withPin(p) { if (Profiles.signedOut(this, p.id)) showSignIn(p.name, p) else openHome(p) }

    private fun openHome(profile: Profile) {
        Profiles.setActive(this, profile.id)
        val home = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        // Opened from the Google TV home's Continue Watching: Home, with that title's page on top of it.
        // A fresh task, so an app already open in the background doesn't end up with two Homes.
        if (intent.hasExtra("id")) startActivities(arrayOf(home,
                Intent(intent).setClass(this, DetailsActivity::class.java).setFlags(0)))
        else linkedTitle()?.let { startActivities(arrayOf(home, it)) } ?: startActivity(home)
        finish()
    }

    /**
     * A title opened from a Stremio or magnet link, or a shared http(s) link: pulls an IMDb id out of
     * whatever came in and hands it to [DetailsActivity] on top of Home. Returns null for anything without
     * one (a magnet or bare video link), which just opens Alder normally.
     */
    private fun linkedTitle(): Intent? {
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return null
        val id = Regex("tt\\d{6,}").find(text)?.value ?: return null
        val type = if (Regex("series|show|tv", RegexOption.IGNORE_CASE).containsMatchIn(text)) "series" else "movie"
        return Intent(this, DetailsActivity::class.java).putExtra("id", id).putExtra("type", type).setFlags(0)
    }

    // ---------- who's watching ----------

    private fun showPicker() {
        ++signInGeneration // a sign-in still on the way is left behind
        val profiles = Profiles.all(this)
        // Opened from inside an unlocked profile (Switch profile, Manage profiles): Back returns to it instead of leaving Alder.
        val current = pickerReturnProfile(profiles, originProfileId)
        backTo = current?.let { { enter(it) } }
        nameInput = null
        // Left a sign-in: stop its polling, or approving the abandoned code would still change the profile.
        signingIn = false
        waitingCode = null
        val generation = ++pickerGeneration
        val art = onboardingBackdrop(false).apply { alpha = 0.42f }
        var selected = profiles.firstOrNull { it.id == Profiles.active(this)?.id } ?: profiles.first()
        // Top-aligned, so Add (no "last watched" line) sits level with the portraits; padding above for the 10% lift.
        val tiles = LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP; clipChildren = false; clipToPadding = false
            setPadding(0, dp(LIFT_ROOM), 0, 0)
        }
        fun showArt(p: Profile, item: org.json.JSONObject?) {
            if (generation != pickerGeneration || selected.id != p.id) return
            val url = item?.optString("_id")?.let(Images::backgroundFor) ?: return
            Images.load(url, art, resources.displayMetrics.widthPixels, urgent = true) {
                val bitmap = (art.drawable as? BitmapDrawable)?.bitmap ?: return@load
                background({ Images.frost(bitmap, 160) }) { result ->
                    if (generation == pickerGeneration && selected.id == p.id && art.tag == url)
                        result.getOrNull()?.let { art.setImageBitmap(it); art.alpha = 0.5f }
                }
            }
        }
        val recent = mutableMapOf<String, org.json.JSONObject?>()
        fun focus(p: Profile) {
            selected = p
            if (p.id !in recent) { art.tag = null; art.setImageResource(R.drawable.onboarding_scene); art.alpha = 0.42f }
            else showArt(p, recent[p.id])
        }
        profiles.forEach { p ->
            val signedOut = Profiles.signedOut(this, p.id).takeIf { it }
                ?.let { text(bt(R.string.profiles_sign_in_again), SMALL, color = TUNGSTEN).apply { gravity = Gravity.CENTER } }
            tiles.addView(tile(p, signedOut) { focus(p) }.apply {
                setOnClickListener { enter(p) }
                setOnLongClickListener { showMenu(p); true }
            }, LinearLayout.LayoutParams(dp(124), -2).apply { marginStart = dp(10); marginEnd = dp(10) })
            background({
                val items = Stremio.savedLibrary(p.authKey) ?: Stremio.library(p.authKey)
                Library.continueWatching(items).firstOrNull()
                    ?: items.filter { !it.optJSONObject("state")?.optString("lastWatched").isNullOrBlank() }
                        .maxByOrNull { it.optJSONObject("state")?.optString("lastWatched").orEmpty() }
            }) { result ->
                if (generation != pickerGeneration) return@background
                val item = result.getOrNull()
                recent[p.id] = item
                showArt(p, item)
            }
        }
        if (profiles.size < Profiles.MAX) {
            tiles.addView(tile(null, null) {}.apply { setOnClickListener { addProfile() } },
                LinearLayout.LayoutParams(dp(124), -2).apply { marginStart = dp(10); marginEnd = dp(10) })
        }
        val content = FrameLayout(this).apply {
            addView(brand(), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(if (Handheld.narrow(this@ProfilesActivity)) 54 else PAD); topMargin = dp(31) // upright: right of the back arrow
            })
            if (!Handheld.narrow(this@ProfilesActivity)) addView(TextClock(this@ProfilesActivity).apply {
                format12Hour = "h:mm a"; format24Hour = "HH:mm"; textSize = SMALL; setTextColor(TEXT_2)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                rightMargin = dp(PAD); topMargin = dp(39)
            })
            addView(text(R.string.profiles_whos_here, if (Handheld.narrow(this@ProfilesActivity)) 28f else 38f, serif = true).onArt().apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(118) })
            // Upright, the portraits scroll sideways: four of them are wider than the screen.
            if (Handheld.narrow(this@ProfilesActivity)) addView(android.widget.HorizontalScrollView(this@ProfilesActivity).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = true
                clipChildren = false
                clipToPadding = false
                addView(tiles)
            }, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(190 - LIFT_ROOM) })
            else addView(tiles, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(190 - LIFT_ROOM) })
            addView(chip(R.string.manage_profiles).apply {
                setOnClickListener { openFamilyHub() }
                // When the signed-in account shares a plan, this is the household manager; otherwise the plain list.
                background({ Profiles.active(this@ProfilesActivity)?.authKey?.let { Stremio.household(it)?.active == true } ?: false }) { r ->
                    if (r.getOrDefault(false)) text = getString(R.string.family_manage_title_short)
                }
            }, FrameLayout.LayoutParams(-2, dp(maxOf(30, CHIP_H)), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(50) })
            if (Handheld.isTv(this@ProfilesActivity)) addView(keyHint(getString(R.string.profiles_hold_ok), getString(R.string.profiles_hold_ok_does)),
                FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(20) })
        }
        setWindowed(content, art)
        tiles.getChildAt(profiles.indexOf(selected)).requestFocus()
    }

    private fun tile(profile: Profile?, last: TextView?, focused: () -> Unit): View {
        val add = profile == null
        val face = text(if (add) "+" else profile?.name?.take(1)?.uppercase().orEmpty(), if (add) 42f else 48f,
            serif = !add, color = if (add) TEXT_2 else BASE).apply {
            gravity = Gravity.CENTER
            alpha = 0.6f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(profile?.color ?: GLASS)
                if (add) setStroke(dp(1), GLASS_EDGE, dp(2).toFloat(), dp(3).toFloat())
            }
        }
        val circle = FrameLayout(this).apply {
            clipChildren = false
            addView(face, FrameLayout.LayoutParams(dp(100), dp(100), Gravity.CENTER))
        }
        // No ring: like the top bar, a dot under the name marks the remote's portrait.
        val dot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(TUNGSTEN) }
            visibility = View.INVISIBLE
        }
        val name = text(if (add) getString(R.string.action_add) else profile?.name.orEmpty().replaceFirstChar { it.titlecase() } + if (profile?.pin != null) "  🔒" else "",
            BODY, color = TEXT_2).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        return column(Gravity.CENTER_HORIZONTAL).apply {
            addView(circle, LinearLayout.LayoutParams(dp(112), dp(112)))
            addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            if (last != null) addView(last, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            addView(dot, LinearLayout.LayoutParams(dp(5), dp(5)).apply { topMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL })
            isFocusable = true
            clipChildren = false
            setOnFocusChangeListener { _, has ->
                dot.visibility = if (has) View.VISIBLE else View.INVISIBLE
                circle.grow(has, 1.1f).start()
                face.alpha = if (has) 1f else 0.6f
                name.setTextColor(if (has) TEXT else TEXT_2)
                if (has) focused()
            }
        }
    }

    // ---------- edit a profile ----------

    /**
     * The per-profile actions (rename, PIN, colour, remove). With [familyBack] set it is opened inside the household
     * manager: it renders into the persistent family dialog, keeps a trailing Back row, and colour/PIN changes stay in
     * the menu (re-rendered) instead of dropping to the picker. Without it, it is the avatar long-press menu.
     */
    private fun showMenu(p: Profile, familyBack: (() -> Unit)? = null) {
        // Re-render this menu with the freshly-saved profile (colour/PIN), keeping whatever context it was opened in.
        fun again() = showMenu(Profiles.all(this).firstOrNull { it.id == p.id } ?: p, familyBack)
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items.add(getString(R.string.profiles_rename) to {
            familyBack?.let { closeFamily() } // rename is a full screen: leave the household view for it
            withPin(p) { rename(p) }
        })
        items.add(getString(if (p.pin == null) R.string.profiles_set_pin else R.string.profiles_change_pin) to {
            familyBack?.let { closeFamily() }
            withPin(p) { setPin(p) }
        })
        items.add(getString(R.string.profiles_colour) to { withPin(p) {
            choose(getString(R.string.profiles_portrait, p.name), listOf(R.string.colour_tungsten, R.string.colour_clay,
                R.string.colour_sage, R.string.colour_lavender, R.string.colour_honey).map(::getString),
                checked = Profiles.COLORS.indexOf(p.color)) { i ->
                // Apply, rebuild the picker so the new colour shows on the avatar immediately (even behind the
                // household dialog), then re-render the menu so it stays open.
                Profiles.update(this, p.id, color = Profiles.COLORS[i])
                showPicker()
                again()
            }
        } })
        if (p.pin != null) items.add(getString(R.string.profiles_remove_pin) to {
            withPin(p) {
                Profiles.update(this, p.id, clearPin = true)
                showPicker()
                again()
            }
        })
        items.add(getString(R.string.profiles_remove) to {
            familyBack?.let { closeFamily() }
            withPin(p) { showPicker(); confirmRemove(p) }
        })
        if (familyBack != null) items.add(getString(R.string.family_back) to { familyBack() })
        val labels = items.map { it.first }
        val pick: (Int) -> Unit = { i -> items[i].second() }
        if (familyBack != null) familyRender(p.name, labels, pick = pick) else choose(p.name, labels, pick = pick)
    }

    /** Runs [then] straight away, or once the profile's PIN is typed, so a locked profile can't be changed around it. */
    private fun withPin(p: Profile, then: () -> Unit) {
        val pin = p.pin ?: return then()
        askPin(getString(R.string.profiles_enter_pin, p.name), ::showPicker) { (it == pin).also { ok -> if (ok) then() } }
    }

    private fun setPin(p: Profile) {
        askPin(getString(R.string.profiles_choose_pin, p.name), ::showPicker) { first ->
            askPin(getString(R.string.profiles_pin_again), ::showPicker, getString(R.string.profiles_pin_mismatch)) { second ->
                (second == first).also { same ->
                    if (same) { Profiles.update(this, p.id, pin = first); showPicker() }
                }
            }
            true
        }
    }

    private fun rename(p: Profile) {
        ++pickerGeneration
        backTo = ::showPicker
        val previewName = text(p.name, BODY, bold = true).apply { gravity = Gravity.CENTER }
        val avatar = onboardingAvatar(p.name.firstOrNull()?.uppercase().orEmpty(), 100, p.color)
        val error = text("", SMALL, color = TUNGSTEN)
        val (name, keyboard, firstKey) = nameEntry(p.name) { typed ->
            previewName.text = typed
            avatar.text = typed.firstOrNull()?.uppercase().orEmpty()
            error.text = ""
        }
        val save = onboardingButton(getString(R.string.profiles_save))
        val go = {
            name.text.toString().trim().takeIf { it.isNotEmpty() }
                ?.let { Profiles.update(this, p.id, name = it); showPicker() }
                ?: run { error.text = getString(R.string.profiles_name_to_save) }
        }
        name.setOnEditorActionListener { _, _, _ -> go(); true }
        save.setOnClickListener { go() }
        val copy = column(Gravity.START,
            text(getString(R.string.profiles_rename_title, p.name), SERIF_TITLE, serif = true),
            text(R.string.profiles_rename_desc, BODY, color = TEXT_2).marginTop(4),
            name.apply { layoutParams = LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(16) } },
            keyboard.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) } },
            error.marginTop(5),
            save.marginTop(7),
        ).apply { gravity = Gravity.CENTER_VERTICAL }
        val preview = column(Gravity.CENTER, avatar, previewName.marginTop(16)).apply { gravity = Gravity.CENTER }
        setWindowed(onboardingFrame("", 0, 0, copy, preview, dp(65), 1.05f, 0.95f, rightFirst = true), onboardingBackdrop())
        if (Handheld.narrow(this)) {
            // Upright, the phone's own keyboard replaces the remote's letter grid.
            keyboard.visibility = View.GONE
            name.showSoftInputOnFocus = true
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            name.requestFocus()
        } else {
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            firstKey.requestFocus()
        }
    }

    /**
     * Four digit boxes for a TV remote: Up/Down turn the focused digit, Left/Right move between boxes, number keys
     * type and move on. OK (or the fourth number key) hands the PIN to [check]; false shakes the boxes and clears them.
     */
    private fun askPin(title: String, back: () -> Unit, errorMessage: String = getString(R.string.profiles_pin_wrong), check: (String) -> Boolean) {
        backTo = back
        val digits = IntArray(4) { -1 }
        var at = 0
        val boxes = List(4) {
            text("", 34f, bold = true).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(76)).apply { marginStart = dp(8); marginEnd = dp(8) }
            }
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER; isFocusable = true; boxes.forEach { addView(it) } }
        val error = text("", BODY, color = TEXT)
        fun draw() = boxes.forEachIndexed { i, b ->
            b.text = when {
                digits[i] < 0 -> ""
                i == at -> "${digits[i]}"
                else -> "•"
            }
            b.background = glass(14, i == at)
        }
        fun submit() {
            if (digits.any { it < 0 }) return
            if (check(digits.joinToString("") { "$it" })) return
            digits.fill(-1); at = 0; draw()
            error.text = errorMessage
            row.shake()
        }
        row.setOnKeyListener { _, code, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            error.text = ""
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> digits[at] = (digits[at] + 1).mod(10)
                KeyEvent.KEYCODE_DPAD_DOWN -> digits[at] = if (digits[at] <= 0) 9 else digits[at] - 1
                KeyEvent.KEYCODE_DPAD_LEFT -> at = maxOf(0, at - 1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (digits[at] >= 0) at = minOf(3, at + 1)
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                    digits[at] = code - KeyEvent.KEYCODE_0
                    if (at < 3) at++ else { draw(); submit(); return@setOnKeyListener true }
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { submit(); return@setOnKeyListener true }
                else -> return@setOnKeyListener false
            }
            draw()
            true
        }
        draw()
        // A phone has no number keys or D-pad: a keypad under the boxes types the same digits.
        val keypad = if (!Handheld.isHandheld(this)) null else LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fun key(label: String, press: () -> Unit) = text(label, 22f, bold = true).apply {
                gravity = Gravity.CENTER
                background = glass(12)
                isFocusable = true
                setOnClickListener { error.text = ""; press(); draw() }
            }
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫")).forEach { keys ->
                addView(LinearLayout(this@ProfilesActivity).apply {
                    keys.forEach { k ->
                        if (k.isEmpty()) addView(View(context), LinearLayout.LayoutParams(dp(54), dp(40)).apply { setMargins(dp(5), dp(3), dp(5), dp(3)) })
                        else addView(key(k) {
                            if (k == "⌫") {
                                if (digits[at] >= 0) digits[at] = -1 else if (at > 0) { at--; digits[at] = -1 }
                            } else {
                                digits[at] = k.toInt()
                                if (at < 3) at++ else { draw(); submit() }
                            }
                        }, LinearLayout.LayoutParams(dp(54), dp(40)).apply { setMargins(dp(5), dp(3), dp(5), dp(3)) })
                    }
                })
            }
        }
        val content = column(Gravity.CENTER_HORIZONTAL,
            text(title, SERIF_TITLE, bold = true, serif = true),
            row.marginTop(if (keypad != null) 16 else 36),
            error.marginTop(8),
            keypad ?: text(R.string.profiles_pin_keys, SMALL, color = TEXT_2).marginTop(28),
        ).apply { gravity = Gravity.CENTER }
        setWindowed(content, onboardingBackdrop())
        row.requestFocus()
    }

    private fun confirmRemove(p: Profile) {
        choose(getString(R.string.profiles_remove_title, p.name), listOf(getString(R.string.action_cancel), getString(R.string.action_remove)),
            bt(R.string.profiles_remove_desc, p.name)) { i ->
            if (i == 0) return@choose
            removeLocally(p)
            // If this profile was a member of the owner's household, drop its seat too, so the group stays in step.
            val ownerKey = Profiles.active(this)?.authKey
            background({
                val email = runCatching { Stremio.email(p.authKey) }.getOrNull()?.trim().orEmpty()
                if (ownerKey != null && email.isNotEmpty()) runCatching { Stremio.inviteRemove(ownerKey, email) }
            }) {}
            if (Profiles.all(this).isEmpty()) showName() else showPicker()
        }
    }

    /**
     * Permanently remove a profile from this device: a tombstone (so the delete syncs to the person's other devices),
     * cleared active selection, and forgotten caches/progress; the account is signed out if no other profile here uses it.
     * This is the shared core of every removal path (avatar menu, profiles list, household member list).
     */
    private fun removeLocally(p: Profile) {
        Profiles.remove(this, p.id)
        if (p.id == originProfileId) originProfileId = null
        background({ Http.forget() }) {}
        // Best effort; the TV forgets the profile either way. A sign-in another profile still uses stays signed in.
        if (Profiles.all(this).none { it.authKey == p.authKey }) {
            ProgressSaves.forget(p.authKey)
            background({ Stremio.logout(p.authKey) }) {}
        }
    }

    // ---------- household (the family plan's members, shown where profiles are managed) ----------

    /**
     * Renders a list into the one persistent household dialog. It creates the dialog on first use (and on a re-open
     * after it was dismissed), then swaps its content on every call, so navigating the household never closes the
     * modal. Does nothing once the household view has been left (familyOpen false).
     */
    private fun familyRender(title: String, items: List<String>, message: String? = null, checked: Int = -1,
                             focus: Int = maxOf(checked, 0), pick: (Int) -> Unit) {
        if (!familyOpen) return
        var dialog = familyDialog
        if (dialog == null || !dialog.isShowing) {
            dialog = android.app.Dialog(this).apply { requestWindowFeature(android.view.Window.FEATURE_NO_TITLE) }
            dialog.setCancelable(true)
            dialog.setCanceledOnTouchOutside(true)
            dialog.setOnDismissListener { familyOpen = false; familyDialog = null }
            familyDialog = dialog
            dialog.show()
        }
        renderChoose(dialog, title, items, message, checked, focus,
            widthDp = if (items.size > 8) 300 else 340, dismissOnPick = false, onClose = { closeFamily() }, pick = pick)
    }

    /** Close the household dialog: the user pressed Close, left the household view, or dismissed it. */
    private fun closeFamily() {
        familyOpen = false
        familyDialog?.dismiss()
        familyDialog = null
    }

    /** The household hub: navigable views for billing, members, profiles and adding a member. */
    private fun openFamilyHub() {
        val owner = Profiles.active(this) ?: return
        familyOpen = true
        background({ Stremio.household(owner.authKey) }) { result ->
            if (!familyOpen) return@background
            val h = result.getOrNull()
            if (h == null || !h.active) { closeFamily(); showProfilesManage(); return@background }
            showHub(h, owner.authKey)
        }
    }

    /**
     * The household hub list: household management first (billing overview, members & roles), then the profiles (tap one
     * for the same actions as a long-press), then Add member (name → sign in / register). Each row opens a view with a
     * Back row, so the whole household is managed from here.
     */
    private fun showHub(h: Stremio.Household, key: String) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        labels.add(getString(R.string.family_billing)); actions.add { showBilling(h, key) }
        // Everyone in the household sees the member list; only owners/managers can act on it (see showHousehold).
        labels.add(getString(R.string.family_members_roles)); actions.add { showHousehold() }
        labels.add(getString(R.string.family_profiles_count, Profiles.all(this).size)); actions.add { showProfilesList(h, key) }
        if (h.canManage) {
            labels.add(getString(R.string.family_add_member)); actions.add { startAddMember() }
        }
        if (h.isMember && !h.canManage) {
            labels.add(getString(R.string.family_leave)); actions.add { showMemberHousehold(h, key) }
        }
        labels.add(getString(R.string.family_close)); actions.add { closeFamily() }
        familyRender(getString(R.string.family_manage_title_short), labels, getString(R.string.family_manage_hint)) { i -> actions[i]() }
    }

    /** The profiles on this device; tapping one opens the same actions a long-press shows (rename, PIN, colour, remove). */
    private fun showProfilesList(h: Stremio.Household, key: String) {
        val profiles = Profiles.all(this)
        if (profiles.isEmpty()) { startAddMember(); return }
        val activeId = Profiles.active(this)?.id
        val labels = profiles.map { p -> p.name + if (p.id == activeId) "  \u2022" else "" }.toMutableList()
        labels.add(getString(R.string.family_back))
        familyRender(getString(R.string.family_profiles_count, profiles.size), labels) { i ->
            // Tap a profile: open its actions inside the same dialog, with Back returning to this list.
            if (i < profiles.size) showMenu(profiles[i]) { showProfilesList(h, key) }
            else showHub(h, key)
        }
    }

    /** Start adding a member: name first, then the sign-in / register flow (which links them into the household). */
    private fun startAddMember() {
        closeFamily()
        // Same seat gate as the "+" tile: block before the name screen if the household is over its plan's seats.
        gateAdd {
            signingIn = true
            draftName = ""
            draftColor = Profiles.COLORS.random()
            showName(add = true)
        }
    }

    /** Billing overview for the household: plan, status, seats, available plans, and a way to the payment page. */
    private fun showBilling(h: Stremio.Household, key: String) {
        background({ Stremio.billingStatus(key) }) { result ->
            val b = result.getOrNull()
            val plan = when {
                !h.planName.isNullOrBlank() && !h.planId.isNullOrBlank() -> "${h.planName} · ${h.planId}"
                !h.planName.isNullOrBlank() -> h.planName
                else -> getString(R.string.family_plan_none)
            }
            val status = when {
                b == null || !b.paywallEnabled -> getString(R.string.family_billing_open)
                b.active -> getString(R.string.family_billing_active)
                else -> getString(R.string.family_billing_required)
            }
            val detail = buildString {
                append(getString(R.string.family_billing_plan, plan))
                append("\n").append(getString(R.string.family_billing_status, status))
                if (h.canManage) append("\n").append(getString(R.string.family_seats, h.used, h.seats))
                b?.plans?.takeIf { it.isNotEmpty() }?.let { ps ->
                    append("\n\n").append(ps.joinToString("\n") { p -> "${p.name} · ${p.priceLabel}" })
                }
            }
            // "Pay on web" builds a real checkout session tied to this account (like the player's paywall), never the
            // raw webUrl: the auth key stays out of the browser URL and an unpaid/unknown plan gets a clear message.
            val hasPay = b?.webUrl != null
            val checkoutPlanId = b?.plans?.firstOrNull()?.id
            val options = buildList {
                if (hasPay) add(getString(R.string.family_billing_pay))
                add(getString(R.string.family_back))
            }
            familyRender(getString(R.string.family_billing), options, detail) { i ->
                if (i == 0 && hasPay) {
                    background({ runCatching { Stremio.billingCheckout(key, checkoutPlanId) }.getOrNull() }) { r ->
                        val url = r.getOrNull()
                        if (url != null) runCatching {
                            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                        } else choose(getString(R.string.upgrade_unavailable), listOf(getString(R.string.action_ok)),
                            getString(R.string.family_billing)) {}
                    }
                } else showHub(h, key)
            }
        }
    }

    /** One clean line for a member row: the role (Manager/Member), with the status appended only when not active. */
    private fun memberDescriptor(m: Stremio.Member): String {
        val role = getString(if (m.isManager) R.string.family_manager else R.string.family_member)
        return when {
            m.joined -> role
            m.expired -> "$role · ${getString(R.string.family_expired)}"
            else -> "$role · ${getString(R.string.family_pending)}"
        }
    }

    /** The household's members (joined profiles + pending invites): everyone sees the list; owners/managers act on it. */
    private fun showHousehold() {
        val owner = Profiles.active(this) ?: return
        background({ Stremio.household(owner.authKey) }) { result ->
            if (!familyOpen) return@background
            val h = result.getOrNull()
            if (h == null || !h.active) { closeFamily(); showProfilesManage(); return@background }
            // Owner first (the person who pays), then the invited members. Counts include the owner.
            val labels = mutableListOf<String>()
            h.owner?.let { o ->
                labels.add((o.name?.takeIf { n -> n.isNotBlank() } ?: o.email) + "  —  " + getString(R.string.family_owner))
            }
            h.members.forEach { m ->
                labels.add((m.name?.takeIf { it.isNotBlank() } ?: m.email) + "  —  " + memberDescriptor(m))
            }
            labels.add(getString(R.string.family_back))
            val backAt = labels.size - 1
            val notice = familyNotice.also { familyNotice = null }
            // Managers get the action hint; members just see who is in the household, so no misleading hint.
            val hint = notice ?: if (h.canManage) getString(R.string.family_manage_hint) else null
            familyRender(getString(R.string.family_manage_title, h.used, h.seats), labels, hint) { i ->
                if (i == backAt) { showHub(h, owner.authKey); return@familyRender }
                // A plain member can look but not touch: the list is read-only, only the owner/manager acts on a row.
                if (!h.canManage) return@familyRender
                if (h.owner != null && i == 0) { showBilling(h, owner.authKey); return@familyRender }
                val memberIndex = i - (if (h.owner != null) 1 else 0)
                householdMemberActions(h.members[memberIndex], owner.authKey, h.isOwner)
            }
        }
    }

    /** A plain member's household: who shared it, and a Leave option. */
    private fun showMemberHousehold(h: Stremio.Household, key: String) {
        familyRender(getString(R.string.family_sharing),
            listOf(getString(R.string.family_leave), getString(R.string.family_back)),
            getString(R.string.family_member_desc, h.sharedBy ?: "")) { i ->
            if (i == 0) {
                background({ Stremio.leavePlan(key) }) { result ->
                    if (result.getOrNull()?.ok == true) { closeFamily(); showProfilesManage() }
                    else householdError(result.getOrNull()?.error)
                }
            } else showHub(h, key)
        }
    }

    private fun householdMemberActions(m: Stremio.Member, key: String, isOwner: Boolean) {
        val options = mutableListOf(getString(R.string.family_rename))
        if (isOwner) options.add(getString(if (m.isManager) R.string.family_make_member else R.string.family_make_manager))
        if (!m.joined && !m.expired) options.add(getString(R.string.family_resend))
        if (isOwner && m.joined) options.add(getString(R.string.family_transfer))
        options.add(getString(R.string.family_remove))
        options.add(getString(R.string.family_back))
        val makeManagerAt = if (isOwner) 1 else -1
        val resendAt = options.indexOf(getString(R.string.family_resend))
        val transferAt = options.indexOf(getString(R.string.family_transfer))
        val removeAt = options.indexOf(getString(R.string.family_remove))
        familyRender(m.email, options, getString(R.string.family_member_hint)) { j ->
            when (j) {
                0 -> householdRename(m, key)
                makeManagerAt -> householdRole(m, key)
                resendAt -> householdResend(m, key)
                transferAt -> householdTransfer(m, key)
                removeAt -> householdRemove(m, key)
                else -> showHousehold()
            }
        }
    }

    private fun householdRename(m: Stremio.Member, key: String) {
        askOnPhone(R.string.family_rename_title, initial = m.name ?: "", done = { typed ->
            background({ Stremio.inviteUpdate(key, m.email, name = typed.trim()) }) { result ->
                val r = result.getOrNull()
                if (r == null || !r.ok) { householdError(r?.error); return@background }
                showHousehold()
            }
        })
    }

    private fun householdRole(m: Stremio.Member, key: String) {
        val newRole = if (m.isManager) "member" else "manager"
        background({ Stremio.inviteRole(key, m.email, newRole) }) { result ->
            val r = result.getOrNull()
            if (r == null || !r.ok) { householdError(r?.error); return@background }
            showHousehold()
        }
    }

    private fun householdResend(m: Stremio.Member, key: String) {
        background({ Stremio.inviteResend(key, m.email) }) { result ->
            val r = result.getOrNull()
            if (r == null || !r.ok) { householdError(r?.error); return@background }
            showHousehold()
        }
    }

    private fun householdTransfer(m: Stremio.Member, key: String) {
        choose(m.email, listOf(getString(R.string.action_cancel), getString(R.string.family_transfer)),
            getString(R.string.family_transfer_hint)) { i ->
            if (i != 1) return@choose
            background({ Stremio.inviteTransfer(key, m.email) }) { result ->
                val r = result.getOrNull()
                if (r == null || !r.ok) { householdError(r?.error); return@background }
                showHousehold()
            }
        }
    }

    private fun householdRemove(m: Stremio.Member, key: String) {
        choose(m.email, listOf(getString(R.string.action_cancel), getString(R.string.family_remove)),
            getString(R.string.family_remove_hint)) { i ->
            if (i != 1) return@choose
            background({
                // Free the seat on the server first; only then drop the local card, so a failure doesn't orphan it.
                val r = Stremio.inviteRemove(key, m.email)
                if (r.ok) {
                    val hId = "h" + m.email.hashCode().toUInt().toString(16)
                    val email = m.email.trim().lowercase()
                    // Remove every local profile that IS this member — the materialized h-card, or a real profile the
                    // owner signed in under the same account — so removal here is as permanent and synced as the others.
                    Profiles.entries(this).filter { p ->
                        !p.deleted && p.authKey != key && (
                            p.id == hId ||
                                runCatching { Stremio.email(p.authKey) }.getOrNull()?.trim()?.lowercase() == email
                        )
                    }.forEach { removeLocally(it) }
                }
                r
            }) { result ->
                val r = result.getOrNull()
                if (r == null || !r.ok) { householdError(r?.error); return@background }
                showHousehold()
            }
        }
    }

    private fun householdError(reason: String?) {
        familyNotice = getString(when (reason) {
            "already_invited" -> R.string.family_already
            "seat_limit_reached" -> R.string.family_no_seats
            "invalid_email" -> R.string.family_bad_email
            "cannot_invite_self" -> R.string.family_self
            "managers_only", "owners_only" -> R.string.family_not_allowed
            "not_joined" -> R.string.family_not_joined
            "target_has_plan" -> R.string.family_target_has_plan
            "email_disabled", "email_limit_reached" -> R.string.family_email_unavailable
            else -> R.string.family_failed
        })
        // Re-render the member list in place, so the modal stays open and the reason shows as the hint line.
        showHousehold()
    }

    /** The plain profile manager, shown when the account has no active plan (nothing to share). */
    private fun showProfilesManage() {
        val profiles = Profiles.all(this)
        choose(getString(R.string.manage_profiles), profiles.map { it.name }) { i -> showMenu(profiles[i]) }
    }

    // ---------- add a profile ----------

    /**
     * Adding a profile is capped by the plan: a free account (plans on sale) gets one, a paid account its seat count.
     * Checks the active account's billing + household seats in the background; on Allow, continues to the name screen,
     * on Upgrade shows the paywall, on Full says the household is full. Offline or no plans → allowed.
     */
    private fun addProfile() = gateAdd { showName() }

    /**
     * Adding a profile is capped by the plan: a free account (plans on sale) gets one, a paid account its seat count.
     * Checks the active account's billing + household seats in the background; on Allow runs [then], on Upgrade shows
     * the paywall, on Full says the household is full. Offline or no plans → allowed. Shared by the "+" tile and the
     * household's Add member, so both block the same way.
     */
    private fun gateAdd(then: () -> Unit) {
        val key = Profiles.active(this)?.authKey
        background({
            val status = key?.let { runCatching { Stremio.billingStatus(it) }.getOrNull() }
            val seats = key?.let { runCatching { Stremio.household(it)?.takeIf { h -> h.active }?.seats }.getOrNull() }
            ProfileGate.check(Profiles.all(this).size, status, seats)
        }) { result ->
            when (val d: ProfileGate.Decision? = result.getOrNull()) {
                is ProfileGate.Decision.Upgrade -> upgradeDialog(d.status)
                is ProfileGate.Decision.Full -> choose(getString(R.string.profiles_limit_title, d.seats),
                    listOf(getString(R.string.action_ok)), getString(R.string.profiles_limit_desc, d.seats)) {}
                else -> then()
            }
        }
    }

    private fun showName(add: Boolean = false) {
        ++pickerGeneration
        ++signInGeneration // a sign-in still on the way is left behind
        backTo = if (Profiles.all(this).isEmpty() && !add) null else ::showPicker
        signingIn = false
        waitingCode = null
        val first = Profiles.all(this).isEmpty()
        val previewName = text(draftName, BODY, bold = true).apply { gravity = Gravity.CENTER }
        val avatar = onboardingAvatar(draftName.firstOrNull()?.uppercase().orEmpty(), 100, draftColor)
        val error = text("", SMALL, color = TUNGSTEN)
        val (name, keyboard, firstKey) = nameEntry(draftName) { typed ->
            previewName.text = typed
            avatar.text = typed.firstOrNull()?.uppercase().orEmpty()
            error.text = ""
        }
        val next = onboardingButton(getString(R.string.profiles_continue))
        val go = {
            name.text.toString().trim().takeIf { it.isNotEmpty() }?.replaceFirstChar { it.titlecase() }
                ?.let { draftName = it; showSignIn(it) }
                ?: run { error.text = getString(R.string.profiles_name_to_continue) }
        }
        name.setOnEditorActionListener { _, _, _ -> go(); true }
        next.setOnClickListener { go() }
        val swatches = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val marks = mutableListOf<TextView>()
        Profiles.COLORS.forEachIndexed { index, color ->
            val mark = text(if (draftColor == color) "✓" else "", SMALL, bold = true, color = BASE).apply {
                gravity = Gravity.CENTER
                isFocusable = true
                fun face(focused: Boolean) = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(color)
                    if (focused) setStroke(dp(2), TUNGSTEN)
                }
                background = face(false)
                setOnFocusChangeListener { view, focused -> view.background = face(focused) }
                setOnClickListener {
                    draftColor = color
                    avatar.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
                    marks.forEachIndexed { i, view -> view.text = if (i == index) "✓" else "" }
                }
            }
            marks += mark
            swatches.addView(mark, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(8) })
        }
        val headline = Branding.loginHeadline(this).ifBlank { bt(if (first) R.string.profiles_welcome else R.string.profiles_new) }
        val copy = column(Gravity.START,
            text(headline, SERIF_TITLE, serif = true),
            text(R.string.profiles_name_colour, BODY, color = TEXT_2).marginTop(4),
            name.apply { layoutParams = LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(16) } },
            keyboard.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) } },
            caps(R.string.profiles_portrait_colour, TUNGSTEN).marginTop(13),
            swatches.marginTop(7),
            error.marginTop(5),
            next.marginTop(7),
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
            // The operator's sign-in subtitle, just under the headline (only when they set one).
            Branding.loginSubtitle(this@ProfilesActivity).takeIf { it.isNotBlank() }?.let {
                addView(text(it, BODY, color = TEXT_2).marginTop(4), 1)
            }
        }
        val preview = column(Gravity.CENTER,
            avatar,
            previewName.marginTop(16),
        ).apply { gravity = Gravity.CENTER }
        val content = onboardingFrame(step(1, if (first) 2 else 3), 1, if (first) 2 else 3, copy, preview, dp(65), 1.05f, 0.95f)
        setWindowed(content, onboardingBackdrop())
        if (Handheld.isHandheld(this)) {
            // A phone or tablet has a touch keyboard, so it replaces the remote's letter grid (as when renaming a profile);
            // only a TV, driven by a remote, needs the on-screen keys. Dropping the tall grid also keeps Continue on screen.
            keyboard.visibility = View.GONE
            name.showSoftInputOnFocus = true
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            name.requestFocus()
        } else {
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            firstKey.requestFocus()
        }
    }

    /**
     * The name box and its remote keyboard: letters, a one-shot ⇧ (on while the box is empty, so names start with a
     * capital), space, ⌫ and the system keyboard. [changed] gets the text after every edit.
     */
    private fun nameEntry(initial: String, changed: (String) -> Unit): Triple<EditText, View, View> {
        val name = EditText(this).apply {
            hint = getString(R.string.profiles_your_name)
            textSize = BODY
            setTextColor(TEXT)
            setHintTextColor(TEXT_3)
            setPadding(dp(14), 0, dp(14), 0)
            background = glass(8)
            setOnFocusChangeListener { v, has -> v.background = glass(8, has) }
            setSingleLine()
            showSoftInputOnFocus = false
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setText(initial)
            setSelection(initial.length)
        }
        nameInput = name
        val keyboard = column(Gravity.START).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8)); background = glass(10)
        }
        val keys = mutableListOf<TextView>()
        fun key(label: String, row: LinearLayout, width: Int = 1, action: () -> Unit) = text(label, SMALL, color = TEXT_2).apply {
            gravity = Gravity.CENTER
            rowFocus(6)
            setOnClickListener { action() }
            row.addView(this, LinearLayout.LayoutParams(0, -1, width.toFloat()).apply {
                if (row.childCount > 0) marginStart = dp(3)
            })
            keys += this
        }
        var upper = false
        val letterKeys = mutableListOf<TextView>()
        fun caps(on: Boolean) {
            upper = on
            letterKeys.forEach { it.text = if (on) it.text.toString().uppercase() else it.text.toString().lowercase() }
        }
        "abcdefghijklmnopqrstuvwxyz".chunked(6).forEach { letters ->
            keyboard.addView(LinearLayout(this).apply {
                letters.forEach { letter -> letterKeys += key(letter.toString(), this) {
                    val typed = if (upper) letter.uppercaseChar() else letter
                    if (upper) caps(false)
                    name.text.append(typed)
                } }
                // "yz" keeps the grid: empty cells fill the row instead of two keys stretched across it.
                repeat(6 - letters.length) { addView(View(context), LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(3) }) }
            }, LinearLayout.LayoutParams(-1, dp(KEY_H)).apply { if (keyboard.childCount > 0) topMargin = dp(3) })
        }
        keyboard.addView(LinearLayout(this).apply {
            key("⇧", this) { caps(!upper) }
            key(getString(R.string.search_space).uppercase(), this, 3) { name.text.append(" ") }
            key("⌫", this) { if (name.length() > 0) name.text.delete(name.length() - 1, name.length()) }
            key("⌨", this) {
                name.showSoftInputOnFocus = true
                name.requestFocus()
                name.post { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(name, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
            }
        }, LinearLayout.LayoutParams(-1, dp(KEY_H)).apply { topMargin = dp(3) })
        caps(initial.isEmpty())
        name.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s.isNullOrEmpty()) caps(true)
                changed(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        return Triple(name, keyboard, keys.first())
    }

    /** Sign-in with a code: for a new profile called [name], or for [again], a profile Stremio signed out. */
    private fun showSignIn(name: String, again: Profile? = null, viaLink: Boolean = false) {
        ++pickerGeneration
        nameInput = null
        backTo = if (again != null) ::showPicker else ::showName
        signingIn = true
        val request = ++signInGeneration
        if (Handheld.isHandheld(this) && !viaLink) return showPasswordSignIn(name, again, request)
        val qr = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val status = text(R.string.profiles_getting_code, SMALL, color = TEXT_2)
        val countdown = ring(1f)
        val codeTiles = (0 until 6).map {
            text("–", 36f, bold = true).apply {
                gravity = Gravity.CENTER
                background = glass(10)
            }
        }
        val codeRow = LinearLayout(this).apply {
            codeTiles.forEach { tile -> addView(tile, LinearLayout.LayoutParams(dp(52), dp(62)).apply {
                if (childCount > 0) marginStart = dp(7)
            }) }
        }
        val back = chip(R.string.profiles_back)
        back.setOnClickListener { waitingCode = null; if (again != null) showPicker() else showName() }
        val left = column(Gravity.CENTER,
            FrameLayout(this).apply {
                setPadding(dp(18), dp(18), dp(18), dp(18))
                background = rounded(TEXT, 12)
                addView(qr, FrameLayout.LayoutParams(-1, -1))
            }.apply { layoutParams = LinearLayout.LayoutParams(dp(236), dp(236)) },
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(onboardingAvatar(name.take(1).uppercase(), 26, again?.color ?: draftColor))
                addView(text(name.replaceFirstChar { it.titlecase() }, BODY, bold = true).apply { setPadding(dp(8), 0, 0, 0) })
            }.marginTop(14),
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(15), dp(20), dp(15), dp(20))
            background = glass(14)
        }
        // The host the QR points at, filled in once the server answers (Stremio.createLink) — never a hardcoded
        // "link.stremio.com", so an operator's own pairing address shows here.
        val orGoTo = text("", BODY, color = TEXT_2).onArt()
        fun renderOrGoTo(host: String) {
            val line = getString(R.string.profiles_or_go_to, host)
            val at = line.indexOf(host)
            orGoTo.text = if (at >= 0) android.text.SpannableString(line).apply {
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), at, at + host.length, 0)
                setSpan(android.text.style.ForegroundColorSpan(TEXT), at, at + host.length, 0)
            } else line
        }
        val steps = column(Gravity.START,
            text(bt(if (again != null) R.string.profiles_sign_in_again else R.string.profiles_connect), 40f, serif = true).onArt(),
            text(bt(if (again != null) R.string.profiles_signed_out else R.string.profiles_scan, name), BODY).onArt().marginTop(10),
            orGoTo.marginTop(22),
            codeRow.marginTop(8),
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(countdown, LinearLayout.LayoutParams(dp(20), dp(20)))
                addView(status, wrap().apply { marginStart = dp(9) })
            }.marginTop(17),
            // Two people approving on one phone sign each other out: switching accounts there can end the other's sign-in.
            text(bt(R.string.profiles_sharing_phone), SMALL, color = TEXT_2).onArt().marginTop(24),
            text(bt(R.string.profiles_new_to_stremio), SMALL, color = TEXT_3).onArt().marginTop(10),
        ).apply { gravity = Gravity.CENTER_VERTICAL }
        // On a phone the code's other device is this one: open Stremio's page here, and the poll below finishes the sign-in.
        val openLink = chip(R.string.profiles_open_link).apply { visibility = View.GONE }
        if (Handheld.isHandheld(this)) steps.addView(openLink, LinearLayout.LayoutParams(-2, dp(36)).apply { topMargin = dp(16) })
        val first = Profiles.all(this).isEmpty()
        val total = if (again != null || first) 2 else 3
        val content = onboardingFrame(if (again != null) getString(R.string.profiles_step_sign_in) else step(2, total),
            if (again != null) 1 else 2, total, left, steps, dp(55), 0f, 1f, dp(300))
        content.addView(back, FrameLayout.LayoutParams(-2, dp(maxOf(32, CHIP_H)), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(PAD); bottomMargin = dp(40)
        })
        setWindowed(content, onboardingBackdrop())
        back.requestFocus()

        waitingCode = null
        var pollFailure: Failure? = null
        background({ Stremio.createLink() }) { result ->
            if (!signingIn || request != signInGeneration) return@background
            result.onSuccess { link ->
                qr.setImageBitmap(qrBitmap(link.url, dp(200)))
                // Show the real pairing host (host:port) the QR encodes, not a hardcoded domain.
                renderOrGoTo(android.net.Uri.parse(link.url).let { it.authority ?: it.host ?: link.url })
                openLink.visibility = View.VISIBLE
                openLink.setOnClickListener {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) }
                }
                codeTiles.forEachIndexed { i, tile -> tile.text = link.code.getOrNull(i)?.toString() ?: "" }
                val started = SystemClock.elapsedRealtime()
                fun tick() {
                    if (!signingIn || request != signInGeneration || waitingCode != link.code) return
                    val leftSeconds = ((5 * 60_000L - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0) / 1000).toInt()
                    status.text = pollFailure?.let { failureMessage(R.string.profiles_unreachable, it, source = "Stremio", advice = false) }
                        ?: getString(R.string.profiles_waiting, "${leftSeconds / 60}:${"%02d".format(leftSeconds % 60)}")
                    countdown.progress = leftSeconds * 1000 / 300
                    main.postDelayed(::tick, 1_000)
                }
                waitingCode = link.code
                tick()
                poll(name, link.code, started, again) { failure ->
                    if (failure != null) FailureLog.record(FailureLog.Operation.SIGN_IN, failure)
                    else pollFailure?.let { FailureLog.record(FailureLog.Operation.SIGN_IN, it, recovered = true) }
                    pollFailure = failure
                }
            }.onFailure {
                val failure = failureOf(it)
                FailureLog.record(FailureLog.Operation.SIGN_IN, failure)
                status.text = failureMessage(R.string.profiles_unreachable, failure, source = "Stremio", advice = false)
                main.postDelayed({ if (signingIn && request == signInGeneration) showSignIn(name, again, viaLink = true) }, 5_000)
            }
        }
    }

    private fun onboardingBackdrop(useLastFilm: Boolean = true) = ImageView(this).apply {
        setImageResource(R.drawable.onboarding_scene)
        scaleType = ImageView.ScaleType.CENTER_CROP
        // Dim, as the doc asks: the copy and code sit over it. A darkening filter, not alpha: the window has no
        // background, so a see-through backdrop would show the last frame instead of darkening.
        if (useLastFilm) setColorFilter(android.graphics.Color.argb(166, 14, 12, 10), android.graphics.PorterDuff.Mode.SRC_ATOP)
        if (useLastFilm) Profiles.active(this@ProfilesActivity)?.let { profile ->
            background({ Stremio.savedLibrary(profile.authKey)?.let(Library::continueWatching)?.firstOrNull()?.optString("_id") }) { result ->
                result.getOrNull()?.let(Images::backgroundFor)?.let { url -> Images.load(url, this, resources.displayMetrics.widthPixels) }
            }
        }
    }

    /** "step 1 of 3", the words above the dots of a setup. */
    private fun step(current: Int, total: Int) = getString(R.string.profiles_step, current, total)

    private fun onboardingFrame(step: String, current: Int, total: Int, left: View, right: View, gap: Int, leftWeight: Float,
                                rightWeight: Float, leftWidth: Int = 0, rightFirst: Boolean = false) =
        android.widget.FrameLayout(this).apply {
            val upright = Handheld.narrow(this@ProfilesActivity)
            val body = LinearLayout(this@ProfilesActivity).apply {
                orientation = if (upright) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = if (upright) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
                setPadding(dp(PAD), dp(68), dp(PAD), dp(35))
                if (upright) {
                    // Upright the two halves stack, each as wide as the screen allows.
                    val order = if (rightFirst) listOf(right, left) else listOf(left, right)
                    order.forEach { part ->
                        addView(part, LinearLayout.LayoutParams(if (part === left && leftWidth > 0) leftWidth else -1, -2)
                            .apply { if (childCount > 0) topMargin = gap / 2 })
                    }
                } else {
                    addView(left, if (leftWidth > 0)
                        LinearLayout.LayoutParams(leftWidth, -2).apply { marginEnd = gap }
                    else LinearLayout.LayoutParams(0, -1, leftWeight).apply { marginEnd = gap })
                    addView(right, LinearLayout.LayoutParams(0, -1, rightWeight))
                }
            }
            if (upright) addView(android.widget.ScrollView(this@ProfilesActivity).apply {
                isVerticalScrollBarEnabled = false
                isFillViewport = true
                addView(body)
            }, android.widget.FrameLayout.LayoutParams(-1, -1))
            else addView(body, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(LinearLayout(this@ProfilesActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(if (upright) 54 else PAD), dp(31), dp(PAD), 0)
                addView(brand(), LinearLayout.LayoutParams(0, -2, 1f))
                if (step.isEmpty()) return@apply // a profile's own screens (rename) aren't part of a setup
                addView(text(getString(if (total > 2) R.string.profiles_step_new else R.string.profiles_step_welcome, step), SMALL, color = TEXT_2))
                repeat(total) { index -> addView(View(this@ProfilesActivity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setColor(if (index < current) TUNGSTEN else GLASS_EDGE)
                    }
                }, LinearLayout.LayoutParams(dp(5), dp(5)).apply { marginStart = dp(5) }) }
            }, android.widget.FrameLayout.LayoutParams(-1, dp(58), Gravity.TOP))
        }

    private fun onboardingButton(label: String) = pill(label)

    private fun onboardingAvatar(initial: String, size: Int, color: Int = draftColor) = text(initial, if (size > 50) 48f else 19f,
        serif = true, color = BASE).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    /**
     * A phone signs in where it stands: Stremio email and password into two fields (the system keyboard, with autofill),
     * or "Use a link instead" for accounts made with Facebook or Apple, which have no password. The code and QR page is
     * for a TV, whose approving device is another one.
     */
    private fun showPasswordSignIn(name: String, again: Profile?, request: Int) {
        // A text box as wide as the screen's margins allow (up to 360 dp) and tall enough for a finger.
        fun fieldParams(top: Int) = LinearLayout.LayoutParams(if (Handheld.narrow(this)) -1 else dp(360), dp(ROW_H)).apply { topMargin = dp(top) }
        fun field(hintRes: Int, type: Int) = android.widget.EditText(this).apply {
            hint = getString(hintRes)
            setHintTextColor(TEXT_3)
            setTextColor(TEXT)
            textSize = BODY
            setSingleLine()
            inputType = type
            background = glass(8)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val email = field(R.string.profiles_email, android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
            .apply { imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI }
        val password = field(R.string.profiles_password, android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
            .apply { imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI }
        val error = text("", SMALL, color = TEXT)
        val go = pill(R.string.profiles_sign_in_action)
        if (android.os.Build.VERSION.SDK_INT >= 26) { // the password manager can fill both
            email.setAutofillHints(View.AUTOFILL_HINT_EMAIL_ADDRESS)
            password.setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
        }
        // Sign in / Sign up switch: a returning profile is signing back in, so the switch is only for a new one.
        val showTabs = again == null
        var signingUp = false
        val signInTab = chip(R.string.profiles_sign_in_action, selected = true)
        val signUpTab = chip(R.string.profiles_sign_up_action)
        val consent = text(R.string.profiles_sign_up_consent, CAPS, color = TEXT_3).apply { visibility = View.GONE }
        fun mode(signup: Boolean) {
            signingUp = signup
            go.text = getString(if (signup) R.string.profiles_sign_up_action else R.string.profiles_sign_in_action)
            signInTab.select(getString(R.string.profiles_sign_in_action), !signup)
            signUpTab.select(getString(R.string.profiles_sign_up_action), signup)
            consent.visibility = if (signup) View.VISIBLE else View.GONE
            error.text = ""
        }
        signInTab.setOnClickListener { mode(false) }
        signUpTab.setOnClickListener { mode(true) }
        fun submit() {
            val e = email.text.toString().trim()
            val p = password.text.toString()
            if (e.isEmpty() || p.isEmpty()) return
            go.isEnabled = false
            error.text = ""
            val signup = signingUp
            background({ if (signup) Stremio.register(e, p) else Stremio.login(e, p) }) { result ->
                if (request != signInGeneration) return@background
                go.isEnabled = true
                result.onSuccess { key ->
                    signingIn = false
                    approved(name, key, again)
                }
                    .onFailure {
                        error.text = when {
                            it is Stremio.ApiException && signup -> getString(R.string.profiles_sign_up_failed)
                            it is Stremio.ApiException -> getString(R.string.profiles_login_failed)
                            else -> failureMessage(R.string.profiles_unreachable, failureOf(it), source = "Stremio", advice = false)
                        }
                    }
            }
        }
        password.setOnEditorActionListener { _, _, _ -> submit(); true }
        go.setOnClickListener { submit() }
        val link = chip(R.string.profiles_use_link).apply { setOnClickListener { showSignIn(name, again, viaLink = true) } }
        val back = chip(R.string.profiles_back).apply { setOnClickListener { backTo?.invoke() } }
        val tabs = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(signInTab)
            addView(signUpTab, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
            if (!showTabs) visibility = View.GONE
        }
        val content = column(Gravity.CENTER_HORIZONTAL,
            text(bt(if (again != null) R.string.profiles_sign_in_again else R.string.profiles_connect), 30f, serif = true).onArt(),
            text(name.replaceFirstChar { it.titlecase() }, BODY, color = TEXT_2).onArt().marginTop(6),
            tabs.apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(if (showTabs) 10 else 0) } },
            email.apply { layoutParams = fieldParams(if (showTabs) 8 else 16) },
            password.apply { layoutParams = fieldParams(8) },
            consent.apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) } },
            error.marginTop(8),
            LinearLayout(this).apply {
                gravity = Gravity.CENTER
                if (Handheld.narrow(this@ProfilesActivity)) {
                    orientation = LinearLayout.VERTICAL
                    addView(go)
                    LinearLayout(this@ProfilesActivity).apply {
                        gravity = Gravity.CENTER
                        addView(back)
                        addView(link, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
                    }.let { addView(it, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) }) }
                } else {
                    addView(back)
                    addView(link, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
                    addView(go, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
                }
            }.marginTop(14),
        ).apply { gravity = Gravity.CENTER; setPadding(dp(if (Handheld.narrow(this@ProfilesActivity)) PAD else 120), 0, dp(if (Handheld.narrow(this@ProfilesActivity)) PAD else 120), 0) }
        setWindowed(content, onboardingBackdrop())
        email.requestFocus()
    }

    private fun poll(name: String, code: String, startedAt: Long, again: Profile?, onStatus: (Failure?) -> Unit) {
        main.postDelayed({
            if (waitingCode != code) return@postDelayed
            background({ Stremio.readLink(code) }) { result ->
                if (waitingCode != code) return@background
                onStatus(result.exceptionOrNull()?.let(::failureOf))
                val key = result.getOrNull()
                when {
                    key != null -> {
                        waitingCode = null
                        signingIn = false
                        approved(name, key, again)
                    }
                    // ponytail: Stremio doesn't say when a code expires (waiting and expired look the same),
                    // so a fresh code every 5 minutes. Read the expiry if their API ever exposes it.
                    SystemClock.elapsedRealtime() - startedAt > 5 * 60_000 -> showSignIn(name, again, viaLink = true)
                    else -> poll(name, code, startedAt, again, onStatus)
                }
            }
        }, 3_000)
    }

    /**
     * A code was approved. When that account is already another profile here (approved on a phone still signed in to
     * the other person), say so before going on. Each sign-in has its own key, so the accounts are compared by user id.
     */
    private fun approved(name: String, key: String, again: Profile?) {
        background({ Stremio.startSession(key) }) {} // register this sign-in with our server so the account shows in People/stats
        val others = Profiles.all(this).filter { it.id != again?.id }
        val request = signInGeneration
        background({
            others.firstOrNull { it.authKey == key } ?: Stremio.userId(key).let { account ->
                others.firstOrNull { runCatching { Stremio.userId(it.authKey) }.getOrNull() == account }
            }
        }) { result ->
            if (request != signInGeneration) return@background // Back during the check: no profile is made
            val same = result.getOrNull() ?: return@background signedIn(name, key, again) // unknown: don't hold anyone up
            choose(bt(R.string.profiles_same_account, same.name),
                listOf(getString(R.string.profiles_other_account), getString(R.string.profiles_continue_anyway)),
                bt(R.string.profiles_same_account_desc, name, same.name)) { i ->
                if (i == 0) showSignIn(name, again) else signedIn(name, key, again)
            }
        }
    }

    private fun signedIn(name: String, key: String, again: Profile?) {
        if (again != null) {
            Profiles.signIn(this, again.id, key)
            return openHome(Profiles.all(this).first { it.id == again.id })
        }
        val ownerKey = Profiles.active(this)?.authKey
        // Someone already in the household (the owner or a joined/pending member) must not get a second, duplicate
        // profile: check the household first and only then add. Offline or unknown → allow (best-effort, like linking).
        if (ownerKey != null && ownerKey != key) {
            background({
                val email = runCatching { Stremio.email(key) }.getOrNull()?.trim().orEmpty()
                val h = runCatching { Stremio.household(ownerKey) }.getOrNull()
                val taken = email.isNotEmpty() && h != null && buildList {
                    h.owner?.let { add(it) }
                    addAll(h.members)
                }.any { it.email.trim().equals(email, ignoreCase = true) }
                if (taken) email else null
            }) { result ->
                val dupe = result.getOrNull()
                if (dupe != null) {
                    choose(getString(R.string.family_sharing), listOf(getString(R.string.action_ok)),
                        getString(R.string.family_email_in_household, dupe)) { showPicker() }
                    return@background
                }
                completeSignIn(name, key, ownerKey)
            }
            return
        }
        completeSignIn(name, key, ownerKey)
    }

    /** Finish a brand-new sign-in: add the profile, best-effort link it into the household, and open the home screen. */
    private fun completeSignIn(name: String, key: String, ownerKey: String?) {
        val others = Profiles.all(this)
        // The account active before this sign-in is the plan owner; linking the new profile's email to its household is
        // best-effort and never blocks going home (no active plan, no free seat, or offline just skips it).
        val color = draftColor
        val profile = Profiles.add(this, name, key, color)
        draftName = ""
        draftColor = Profiles.COLORS[0]
        if (ownerKey != null && ownerKey != key) background({ HouseholdLink.linkMember(ownerKey, name, color, key) }) {}
        // A member signing in on a new device sees the whole family: materialize the household's joined members as cards.
        background({ ProfileSync.materializeHousehold(this, key) }) {}
        // Each profile brings its own add-ons (stream add-ons are shared in Settings, never copied);
        // the others' lists only tell where Watchly lives, so the new profile can set up its own.
        if (others.isEmpty()) openHome(profile)
        else background({ others.flatMap { runCatching { Stremio.addons(it.authKey) }.getOrDefault(emptyList()) } }) {
            showWatchly(profile, it.getOrDefault(emptyList()))
        }
    }

    // ---------- recommendations ----------

    /** Watchly is per person: the new profile sets up its own, connected to its own Stremio account. */
    private fun showWatchly(profile: Profile, sourceAddons: List<Addon>) {
        backTo = { openHome(profile) }
        val watchly = sourceAddons.firstOrNull { it.name.contains("watchly", ignoreCase = true) }
            ?: return showReady(profile)
        val configure = Uri.parse(watchly.transportUrl).let { "${it.scheme}://${it.authority}/configure" }
        val qr = ImageView(this).apply { setImageBitmap(qrBitmap(configure, dp(220))) }
        val done = onboardingButton(bt(R.string.profiles_continue_to_alder)).apply { setOnClickListener { openHome(profile) } }
        val steps = column(Gravity.START,
            text(getString(R.string.profiles_recommendations, profile.name), SERIF_TITLE, bold = true, serif = true),
            text(bt(R.string.profiles_watchly_steps, profile.name), BODY, color = TEXT_3).marginTop(16),
            done.marginTop(32),
        )
        setWindowed(onboardingFrame(step(3, 3), 3, 3, column(Gravity.CENTER,
            qr.apply { background = rounded(TEXT, 12) },
            text(profile.name, BODY, bold = true).marginTop(12)
        ).apply { gravity = Gravity.CENTER; setPadding(dp(22), dp(22), dp(22), dp(22)); background = glass(14) },
            steps, dp(52), 0.9f, 1.1f), onboardingBackdrop())
        done.requestFocus()
    }

    private fun showReady(profile: Profile) {
        backTo = { openHome(profile) }
        val done = onboardingButton(bt(R.string.profiles_continue_to_alder)).apply { setOnClickListener { openHome(profile) } }
        setWindowed(onboardingFrame(step(3, 3), 3, 3, column(Gravity.CENTER,
            onboardingAvatar(profile.name.take(1).uppercase(), 100, profile.color),
            text(profile.name, SERIF_TITLE, serif = true).marginTop(12)
        ).apply { gravity = Gravity.CENTER }, column(Gravity.START,
            text(R.string.profiles_all_set, 40f, serif = true),
            text(getString(R.string.profiles_ready, profile.name), BODY, color = TEXT_2).marginTop(12),
            done.marginTop(24)
        ).apply { gravity = Gravity.CENTER_VERTICAL }, dp(55), 0.9f, 1.1f), onboardingBackdrop())
        done.requestFocus()
    }

    companion object {
        const val UNLOCK = "unlock"
        const val LIFT_ROOM = 8 // dp above the portraits for the focused one's 10% lift and ring
        const val KEY_H = 25 // dp a key row on the name step, so Continue fits under the colours
    }

}

/**
 * The unlocked profile the picker may return to on Back, or null when no explicit unlocked origin exists.
 * Never falls back to a lone remaining profile or returns a PIN-locked one, so removing or locking the origin
 * (or launching the exported picker externally) cannot bypass a PIN.
 */
internal fun pickerReturnProfile(profiles: List<Profile>, originId: String?): Profile? =
    originId?.let { id -> profiles.firstOrNull { it.id == id && it.pin == null } }
