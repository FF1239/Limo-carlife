package com.shilapi.xcertplay.carlife

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.AppAppearanceOwner
import com.shilapi.xcertplay.AppLocale
import com.shilapi.xcertplay.DiPlayPalette
import com.shilapi.xcertplay.InterfaceSize
import com.shilapi.xcertplay.SettingsLayoutPolicy
import com.shilapi.xcertplay.SettingsLayoutPreferences
import com.shilapi.xcertplay.SettingsReadiness
import com.shilapi.xcertplay.appDialogBuilder
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets
import com.shilapi.xcertplay.setup.DiLinkGeneration
import com.shilapi.xcertplay.setup.SetupGuide
import kotlin.math.roundToInt

/** The ten destinations of the settings rail, in the order DiPlay declares them. */
internal enum class SettingsCategory {
    OVERVIEW, CONNECTION, DISPLAY, AUDIO, NAVIGATION, VEHICLE, LANGUAGE, ABOUT, DIAGNOSTICS, ADVANCED,
}

/**
 * Limo's carbon palette expressed in DiPlayPalette's semantic slots.
 *
 * The ported screens keep DiPlay's spacing, type scale and control shapes; only the colour
 * source changes. DiPlay's blue accent becomes CarbonTheme.RED, and the surface/outline ramp
 * keeps the same contrast hierarchy so the shared card and rail styling still reads correctly.
 * [railSelected] is a red tint rather than DiPlay's blue one, so the selected destination
 * still reads as "the accent colour, dimmed" behind its 4dp indicator.
 */
private val CARBON_PALETTE = DiPlayPalette(
    background = CarbonTheme.BG,
    surface = CarbonTheme.PANEL,
    button = Color.parseColor("#1D2024"),
    outline = CarbonTheme.HAIRLINE,
    railSelected = Color.parseColor("#2A1116"),
    railSelectedOutline = Color.parseColor("#5A1622"),
    primaryText = CarbonTheme.TEXT,
    secondaryText = CarbonTheme.TEXT_DIM,
    accent = CarbonTheme.RED,
    onAccent = Color.WHITE,
    accentTrack = CarbonTheme.RED,
    trackOff = CarbonTheme.HAIRLINE,
    warning = Color.parseColor("#FF9500"),
    success = CarbonTheme.ONLINE,
    danger = Color.parseColor("#FF9500"),
    ripple = 0x33FFFFFF,
    focusRing = CarbonTheme.RED,
    systemBar = CarbonTheme.PANEL_DEEP,
    systemBarIconsAreDark = false,
    overlayBackground = CarbonTheme.PANEL_DEEP,
    overlayPrimaryText = CarbonTheme.TEXT,
    overlaySecondaryText = CarbonTheme.TEXT_DIM,
    overlayAccent = CarbonTheme.RED,
    overlayOnAccent = Color.WHITE,
    overlayAccentTrack = CarbonTheme.RED,
    overlayTrackOff = CarbonTheme.HAIRLINE,
    overlayDanger = Color.parseColor("#FF9500"),
)

/**
 * The CarLife port of DiPlay's application-level interface layer: one page state machine
 * (home / settings / about / connection / setup), the split headers, [home]'s control order,
 * the settings rail with its expanded shell, and the five-step setup guide.
 *
 * What changed against the CarPlay original is the transport, not the layout. The wireless
 * card, the USB button and every connect action start [CarLifeVideoActivity]; picture,
 * accent, day/night and behaviour controls persist through [CarLifePersistence]. Deliberately
 * absent, because they are CarPlay protocol concerns rather than interface concerns: iAP2/MFI
 * identity, the car's Wi-Fi P2P channel and hotspot-credential editors, the AirPlay icon
 * cropper, the CarPlay view-area dock picker, the BYD instrument-cluster surfaces, and the
 * in-app updater (Limo keeps its own [com.shilapi.xcertplay.AppUpdate]).
 *
 * Both launchers share this machine: [CarLifeHomeActivity] opens on the home page and
 * [CarLifeSettingsActivity] opens on the settings page, which is why the settings entry
 * buttons move between pages in place rather than starting another activity.
 */
abstract class CarLifeScreenActivity : Activity(), AppAppearanceOwner {

    private val handler = Handler(Looper.getMainLooper())

    private var page = initialPage
    private var appNight = true
    override val currentAppNight: Boolean get() = appNight

    private var status: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var rootScroll: ScrollView? = null
    private var settingsRailScroll: ScrollView? = null
    private var readinessCard: LinearLayout? = null
    private var renderedReadiness: SettingsReadiness? = null
    private var renderedPage: String? = null
    private var renderedSettingsCategory: SettingsCategory? = null
    private var pendingScrollY: Int? = null
    private var pendingRailScrollY: Int? = null

    private var settingsCategory = SettingsCategory.OVERVIEW
    private var connectionSettingsReturnCategory: SettingsCategory? = null
    private var setupStep = SetupGuide.STEP_CAR
    private var setupFromSettings = false

    protected abstract val initialPage: String

    protected val persistence by lazy { CarLifePersistence.get(this) }
    private val palette get() = CARBON_PALETTE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarbonTheme.load(this)
        installCrashCapture()
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Already-open home + a fresh USB plug-in: honour the auto-connect setting here too.
        if (this is CarLifeHomeActivity &&
            intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED &&
            persistence.autoConnect()
        ) {
            openProjection(wired = true)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    /** Writes any uncaught crash into the session log before the process dies. */
    private fun installCrashCapture() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            CarLifeSessionLog.log(
                this, "CRASH",
                "${thread.name}: ${android.util.Log.getStackTraceString(throwable)}",
            )
            previous?.uncaughtException(thread, throwable)
        }
    }

    // ---- page state machine ----

    private fun render() = render(appearanceOnly = false)

    private fun render(appearanceOnly: Boolean, preferredFocusTag: Any? = null) {
        refreshAppearance()
        applyWindowAppearance()
        val focusSnapshot = if (appearanceOnly) {
            currentFocus?.let { focused ->
                FocusSnapshot(
                    tag = preferredFocusTag ?: focused.tag,
                    contentDescription = focused.contentDescription?.toString(),
                    label = focusLabel(focused),
                )
            } ?: preferredFocusTag?.let { FocusSnapshot(it, null, null) }
        } else null
        // A restore still waiting for layout keeps its target: the old page was never laid out.
        val sameDestination = renderedPage == page &&
            (page != PAGE_SETTINGS || renderedSettingsCategory == settingsCategory)
        val previousScrollY = (pendingScrollY ?: rootScroll?.scrollY)?.takeIf { sameDestination }
        // The rail is one destination even when its selected category changes.
        val keepRailPosition = renderedPage == PAGE_SETTINGS && page == PAGE_SETTINGS
        val previousRailScrollY = (pendingRailScrollY ?: settingsRailScroll?.scrollY)
            ?.takeIf { keepRailPosition }
        settingsRailScroll = null
        status = null; connectButton = null; disconnectButton = null; lastRunning = null
        readinessCard = null
        val compact = isCompactLayout && when (page) {
            PAGE_SETTINGS -> !SettingsLayoutPreferences.isActive(this)
            else -> true
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(palette.background); isFillViewport = true; clipToPadding = false
        }
        rootScroll = scroll
        val content = column()
        scroll.addView(content)
        val header = if (page == PAGE_SETTINGS) settingsHeader(compact) else standardHeader(compact)
        val root: View = if (page == PAGE_SETTINGS && isExpandedSettingsLayout) {
            content.setPadding(0, 0, 0, dp(32))
            settingsCategoryContent(content)
            expandedSettingsShell(header, scroll)
        } else {
            if (compact) content.setPadding(dp(12), dp(10), dp(12), dp(12))
            else content.setPadding(dp(32), dp(24), dp(32), dp(32))
            content.addView(header)
            content.addView(
                space(
                    when {
                        compact && (page == PAGE_SETTINGS || page == PAGE_HOME) -> SETTINGS_BLOCK_GAP_DP
                        compact -> 8
                        else -> 24
                    },
                ),
            )
            when (page) {
                PAGE_CONNECTION -> connectionSetup(content)
                PAGE_SETUP -> setupGuide(content)
                PAGE_SETTINGS -> settingsCategoryContent(content)
                PAGE_ABOUT -> about(content)
                else -> home(content)
            }
            scroll
        }
        // Keep the background full screen, but all controls inside the camera and
        // visible system/keyboard bounds. Insets are physical pixels, not scaled dp.
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(
                baseLeft + safe.left, baseTop + safe.top,
                baseRight + safe.right, baseBottom + safe.bottom,
            )
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        renderedPage = page
        renderedSettingsCategory = settingsCategory.takeIf { page == PAGE_SETTINGS }
        refreshStatus()
        focusSnapshot?.let { snapshot ->
            root.post {
                descendants(root).firstOrNull { candidate ->
                    candidate.isFocusable && when {
                        snapshot.tag != null -> candidate.tag == snapshot.tag
                        snapshot.contentDescription != null ->
                            candidate.contentDescription?.toString() == snapshot.contentDescription
                        snapshot.label != null -> focusLabel(candidate) == snapshot.label
                        else -> false
                    }
                }?.requestFocus()
            }
        }
        pendingScrollY = previousScrollY
        // A stopped window still dispatches pre-draw but skips layout, so wait for a real post.
        previousScrollY?.let { y ->
            scroll.post {
                if (rootScroll === scroll) {
                    scroll.scrollTo(0, y)
                    pendingScrollY = null
                }
            }
        }
        val rail = settingsRailScroll
        pendingRailScrollY = previousRailScrollY.takeIf { rail != null }
        rail?.post {
            if (settingsRailScroll !== rail) return@post
            previousRailScrollY?.let { rail.scrollTo(0, it) }
            val destinations = rail.getChildAt(0) as ViewGroup
            val selected = (0 until destinations.childCount)
                .map(destinations::getChildAt).firstOrNull { it.isSelected }
            selected?.requestRectangleOnScreen(android.graphics.Rect(0, 0, selected.width, selected.height), true)
            pendingRailScrollY = null
        }
    }

    private data class FocusSnapshot(
        val tag: Any?,
        val contentDescription: String?,
        val label: String?,
    )

    private fun focusLabel(view: View): String? = when (view) {
        is TextView -> view.text?.toString()
        is ViewGroup -> descendants(view).filterIsInstance<TextView>()
            .firstOrNull { it.text.isNotEmpty() }?.text?.toString()
        else -> null
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    /** Window size only: in multi-window the configuration already reports the window's size. */
    private val isCompactLayout: Boolean
        get() = resources.configuration.screenWidthDp < 550 ||
            resources.configuration.screenHeightDp < 450

    private val isExpandedSettingsLayout: Boolean
        get() = SettingsLayoutPreferences.isActive(this) ||
            SettingsLayoutPolicy.isExpanded(
                resources.configuration.screenWidthDp,
                resources.configuration.screenHeightDp,
                resources.configuration.fontScale,
            )

    // ---- headers ----

    private fun standardHeader(compact: Boolean): LinearLayout = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        val logoSize = dp(if (compact) 28 else 36)
        addView(
            ImageView(this@CarLifeScreenActivity).apply {
                setImageResource(R.drawable.ic_limo_mark)
                contentDescription = getString(R.string.carlife_logo_description)
            },
            LinearLayout.LayoutParams(logoSize, logoSize),
        )
        addView(
            label(
                getString(R.string.carlife_channel_title),
                if (compact) 20 else 26, palette.primaryText, true,
            ).apply { setPadding(if (compact) dp(8) else dp(12), 0, 0, 0) },
            LinearLayout.LayoutParams(0, if (compact) dp(44) else dp(52), 1f),
        )
        addView(appearanceButton(), LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginEnd = dp(if (compact) 8 else 12)
        })
        addView(
            headerButton(
                if (page == PAGE_HOME) getString(R.string.carlife_car_home) else getString(R.string.back),
                if (page == PAGE_HOME) R.drawable.ic_dp_car else R.drawable.ic_dp_back,
                compact,
            ) {
                // DiPlay leaves the app for the car home here. Limo's own home already is the
                // launcher destination, so the button opens settings instead of nothing.
                if (page == PAGE_HOME) {
                    settingsCategory = SettingsCategory.OVERVIEW
                    page = PAGE_SETTINGS
                    render()
                } else {
                    navigateBack()
                }
            },
            LinearLayout.LayoutParams(-2, if (compact) dp(44) else dp(52)),
        )
    }

    private fun settingsHeader(compact: Boolean): LinearLayout = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(
            headerButton(getString(R.string.back), R.drawable.ic_dp_back, compact) { navigateBack() },
            LinearLayout.LayoutParams(-2, if (compact) dp(44) else dp(52)),
        )
        addView(
            label(
                getString(R.string.settings), if (compact) 20 else 26, palette.primaryText, true,
                centreGlyphs = resources.configuration.locales[0].language == "zh",
            ).apply { setPadding(dp(12), 0, dp(12), 0) },
            LinearLayout.LayoutParams(0, if (compact) dp(44) else dp(52), 1f),
        )
        addView(appearanceButton(), LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginEnd = dp(if (compact) 8 else 12)
        })
    }

    private fun appearanceButton(): ImageButton = iconButton(
        icon = if (appNight) R.drawable.ic_dp_night else R.drawable.ic_dp_day,
        description = getString(
            if (appNight) R.string.carlife_settings_switch_to_light
            else R.string.carlife_settings_switch_to_dark,
        ),
    ) {
        // Limo's day/night preference is a three-way (auto / day / night); the button steps
        // between the two explicit modes, which is what a single tap can meaningfully do.
        persistence.saveDayNightMode(
            if (persistence.dayNightMode() == CarLifePersistence.DAY_NIGHT_DAY) {
                CarLifePersistence.DAY_NIGHT_NIGHT
            } else {
                CarLifePersistence.DAY_NIGHT_DAY
            },
        )
        CarbonTheme.load(this)
        requestAppearanceRender(focusAppearanceButton = true)
    }.apply { tag = APPEARANCE_BUTTON_TAG }

    private fun navigateBack() {
        val returnCategory = connectionSettingsReturnCategory
        when {
            page == PAGE_ABOUT -> {
                page = PAGE_SETTINGS
                settingsCategory = SettingsCategory.OVERVIEW
            }

            page == PAGE_SETUP && setupStep > SetupGuide.STEP_CAR -> setupStep--
            page == PAGE_SETUP -> {
                closeSetupGuide()
                return
            }

            page == PAGE_CONNECTION && returnCategory != null -> {
                page = PAGE_SETTINGS
                settingsCategory = returnCategory
                connectionSettingsReturnCategory = null
            }

            page == PAGE_SETTINGS && !isExpandedSettingsLayout &&
                settingsCategory != SettingsCategory.OVERVIEW -> {
                settingsCategory = SettingsCategory.OVERVIEW
            }

            page == PAGE_HOME -> {
                // Nothing left to pop: this host is the settings entry point, so leave it.
                finish()
                return
            }

            else -> {
                // DiPlay's own settings live inside the home activity and fall back to the
                // home page here. Limo's settings are a separate launcher target, so leaving
                // settings must return to the home activity rather than render it in place.
                if (this is CarLifeSettingsActivity) {
                    finish()
                    return
                }
                page = PAGE_HOME
                connectionSettingsReturnCategory = null
            }
        }
        render()
    }

    @Deprecated("Kept so the page machine handles the back gesture on every API level")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (page == PAGE_HOME && this !is CarLifeSettingsActivity) {
            super.onBackPressed()
            return
        }
        navigateBack()
    }

    private fun openConnectionSetupFromSettings() {
        connectionSettingsReturnCategory = settingsCategory
        page = PAGE_CONNECTION
        render()
    }

    // ---- home ----

    private fun home(content: LinearLayout) {
        val compact = isCompactLayout
        fun homeButton(title: String, primary: Boolean, click: () -> Unit) =
            button(title, primary, click).apply { if (compact) cornerReferenceHeight = 62 }

        val wide = resources.configuration.screenWidthDp >= 850
        val body = column()
        val left = column()
        if (!compact) {
            left.addView(
                label(getString(R.string.carlife_eyebrow), 12, palette.accent, true)
                    .apply { letterSpacing = .16f },
            )
            left.addView(
                label(getString(R.string.carlife_headline), if (wide) 42 else 36, palette.primaryText, true)
                    .apply { setPadding(0, dp(12), 0, dp(10)) },
            )
            left.addView(label(getString(R.string.carlife_subtitle), 19, palette.secondaryText))
        }
        val card = card()
        card.addView(
            label(getString(R.string.carlife_wireless_label), 12, palette.accent, true)
                .apply { letterSpacing = .12f },
        )
        status = label(getString(R.string.ready_when_you_are), 24, palette.primaryText, true)
            .apply { setPadding(0, dp(10), 0, dp(16)) }
        card.addView(status)
        connectButton = homeButton(getString(R.string.connect_phone), true) { openProjection(wired = null) }
        card.addView(connectButton, matchButton(height = if (compact) 54 else 68))
        if (!compact) {
            card.addView(
                label(getString(R.string.carlife_connect_hint), 15, palette.secondaryText)
                    .apply { setPadding(0, dp(14), 0, 0) },
            )
        }
        // The phone picker is CarLife's own: the phone identifies itself over the channel.
        card.addView(
            homeButton(getString(R.string.carlife_choose_phone), false) { openProjection(wired = null) },
            matchButton(16, if (compact) 46 else 56),
        )
        disconnectButton = homeButton(getString(R.string.disconnect), false) {
            disconnectButton?.isEnabled = false
            CarLifeSessionService.stop(this)
            handler.postDelayed({ if (!isFinishing) refreshStatus() }, 400L)
        }.apply { visibility = View.GONE }
        card.addView(disconnectButton, matchButton(10, if (compact) 46 else 56))
        val right = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_limo_mark)
            contentDescription = getString(R.string.carlife_logo_description)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val branding = column().apply {
            gravity = Gravity.CENTER
            addView(logo, LinearLayout.LayoutParams(dp(96), dp(96)))
        }
        right.addView(
            homeButton(getString(R.string.connect_with_usb), false) { openProjection(wired = true) },
            matchButton(height = if (compact) 54 else 68),
        )
        if (!compact) {
            right.addView(
                label(getString(R.string.carlife_usb_hint), 14, palette.secondaryText).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(10), dp(8), dp(24))
                },
            )
        } else {
            right.addView(space(16))
        }
        right.addView(
            homeButton(getString(R.string.settings), false) {
                settingsCategory = SettingsCategory.OVERVIEW
                page = PAGE_SETTINGS
                render()
            },
            matchButton(height = if (compact) 54 else 68),
        )
        if (!compact) {
            right.addView(
                label(getString(R.string.carlife_settings_hint), 14, palette.secondaryText).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(10), 0, dp(24))
                },
            )
        } else {
            right.addView(space(12))
        }
        right.addView(
            label(getString(R.string.home_public_preview, version()), 12, palette.secondaryText)
                .apply { letterSpacing = .08f },
        )
        if (compact && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            val compactRight = column().apply {
                addView(space(8))
                addView(branding)
                addView(space(24))
                addView(right)
            }
            body.addView(
                row().apply {
                    gravity = Gravity.TOP
                    addView(card, LinearLayout.LayoutParams(0, -2, 1.6f))
                    addView(space(16), LinearLayout.LayoutParams(dp(16), 1))
                    addView(compactRight, LinearLayout.LayoutParams(0, -2, 1f))
                },
            )
        } else if (!compact && wide) {
            // Both rows share column widths, so the USB button starts at the wireless card's
            // top edge independently of hero wrapping or font scaling.
            fun columns(first: View, second: View, stretchSecond: Boolean = false) = row().apply {
                gravity = Gravity.TOP
                addView(first, LinearLayout.LayoutParams(0, -2, 1.6f))
                addView(space(40), LinearLayout.LayoutParams(dp(40), 1))
                addView(second, LinearLayout.LayoutParams(0, if (stretchSecond) -1 else -2, 1f))
            }
            body.addView(columns(left, branding, true))
            body.addView(space(26))
            body.addView(columns(card, right))
        } else {
            if (!compact) {
                body.addView(left)
                body.addView(space(26))
            }
            body.addView(card)
            body.addView(space(26))
            body.addView(branding)
            body.addView(space(24))
            body.addView(right)
        }
        content.addView(body)
    }

    // ---- settings shell ----

    /** Only the category scrolls, so the rail keeps showing where the driver is. */
    private fun expandedSettingsShell(header: View, categoryScroll: ScrollView): LinearLayout = column().apply {
        setBackgroundColor(palette.background)
        setPadding(dp(32), dp(24), dp(32), 0)
        addView(header)
        addView(space(24))
        val split = row().apply { gravity = Gravity.TOP }
        // The rail scrolls on its own only when the window is too short for every destination.
        split.addView(
            ScrollView(this@CarLifeScreenActivity).apply {
                addView(settingsRail())
                settingsRailScroll = this
            },
            LinearLayout.LayoutParams(dp(SettingsLayoutPolicy.railWidthDp(resources.configuration.fontScale)), -2),
        )
        split.addView(space(24), LinearLayout.LayoutParams(dp(24), 1))
        split.addView(categoryScroll, LinearLayout.LayoutParams(0, -1, 1f))
        addView(split, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun settingsRail(): LinearLayout = column().apply {
        background = rounded(palette.surface, palette.outline)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        SettingsCategory.entries.forEach { category ->
            addView(
                settingsRailDestination(category),
                matchButton(0, 52).apply { bottomMargin = dp(8) },
            )
        }
    }

    private fun settingsRailDestination(category: SettingsCategory): View {
        val selected = settingsCategory == category
        val title = settingsCategoryTitle(category)
        return row().apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            isSelected = selected
            foreground = focusRing(12)
            contentDescription = getString(R.string.carlife_settings_open_category, title)
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(palette.ripple),
                GradientDrawable().apply {
                    setColor(if (selected) palette.railSelected else Color.TRANSPARENT)
                    cornerRadius = dp(12).toFloat()
                    if (selected) setStroke(dp(1), palette.railSelectedOutline)
                },
                null,
            )
            setPadding(dp(8), 0, dp(12), 0)
            addView(
                View(this@CarLifeScreenActivity).apply {
                    background = GradientDrawable().apply {
                        setColor(if (selected) palette.accent else Color.TRANSPARENT)
                        cornerRadius = dp(2).toFloat()
                    }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(4), dp(34)).apply { marginEnd = dp(10) },
            )
            addView(
                ImageView(this@CarLifeScreenActivity).apply {
                    setImageResource(settingsCategoryIcon(category))
                    imageTintList = ColorStateList.valueOf(if (selected) palette.accent else palette.primaryText)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(12) },
            )
            addView(
                label(title, 16, if (selected) palette.accent else palette.primaryText, true),
                LinearLayout.LayoutParams(0, -1, 1f),
            )
            setOnClickListener { openSettingsCategory(category) }
        }
    }

    private fun settingsCategoryIcon(category: SettingsCategory): Int = when (category) {
        SettingsCategory.OVERVIEW -> R.drawable.ic_dp_overview
        SettingsCategory.CONNECTION -> R.drawable.ic_dp_connection
        SettingsCategory.DISPLAY -> R.drawable.ic_dp_display
        SettingsCategory.AUDIO -> R.drawable.ic_dp_audio
        SettingsCategory.NAVIGATION -> R.drawable.ic_dp_navigation
        SettingsCategory.VEHICLE -> R.drawable.ic_dp_vehicle
        SettingsCategory.DIAGNOSTICS -> R.drawable.ic_dp_diagnostics
        SettingsCategory.ADVANCED -> R.drawable.ic_dp_advanced
        SettingsCategory.LANGUAGE -> R.drawable.ic_dp_language
        SettingsCategory.ABOUT -> R.drawable.ic_dp_about
    }

    private fun settingsCategoryTitle(category: SettingsCategory): String = getString(
        when (category) {
            SettingsCategory.OVERVIEW -> R.string.carlife_settings_overview
            SettingsCategory.CONNECTION -> R.string.connection
            SettingsCategory.DISPLAY -> R.string.carlife_settings_display
            SettingsCategory.AUDIO -> R.string.audio
            SettingsCategory.NAVIGATION -> R.string.carlife_settings_navigation
            SettingsCategory.VEHICLE -> R.string.carlife_settings_vehicle
            SettingsCategory.DIAGNOSTICS -> R.string.diagnostics
            SettingsCategory.ADVANCED -> R.string.carlife_settings_advanced
            SettingsCategory.LANGUAGE -> R.string.language_section_title
            SettingsCategory.ABOUT -> R.string.about
        },
    )

    private fun openSettingsCategory(category: SettingsCategory) {
        if (category == SettingsCategory.ABOUT) page = PAGE_ABOUT else settingsCategory = category
        render()
    }

    private fun settingsCategoryContent(content: LinearLayout) {
        when (settingsCategory) {
            SettingsCategory.OVERVIEW -> settingsOverview(content)
            SettingsCategory.CONNECTION -> connectionSettings(content)
            SettingsCategory.DISPLAY -> displaySettings(content)
            SettingsCategory.AUDIO -> audioSettings(content)
            SettingsCategory.NAVIGATION -> navigationSettings(content)
            SettingsCategory.VEHICLE -> vehicleSettings(content)
            SettingsCategory.DIAGNOSTICS -> diagnosticsSettings(content)
            SettingsCategory.ADVANCED -> advancedSettings(content)
            SettingsCategory.LANGUAGE -> languageSettings(content)
            SettingsCategory.ABOUT -> about(content)
        }
    }

    private fun settingsPageTitle(content: LinearLayout, title: String, subtitle: String) {
        content.addView(label(title, if (isExpandedSettingsLayout) 34 else 26, palette.primaryText, true))
        content.addView(label(subtitle, 16, palette.secondaryText).apply { setPadding(0, dp(6), 0, dp(20)) })
    }

    private fun settingsSectionHeading(title: Int) =
        label(getString(title), 22, palette.primaryText, true).apply { setPadding(0, 0, 0, dp(10)) }

    private fun settingsSummaryCard(category: SettingsCategory, summary: String): LinearLayout = card().apply {
        setPadding(dp(16), dp(10), dp(16), dp(10))
        isClickable = true
        isFocusable = true
        foreground = rippleFocusRing()
        contentDescription = getString(R.string.carlife_settings_open_category, settingsCategoryTitle(category))
        addView(
            row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    ImageView(this@CarLifeScreenActivity).apply {
                        setImageResource(settingsCategoryIcon(category))
                        imageTintList = ColorStateList.valueOf(palette.accent)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) },
                )
                addView(
                    column().apply {
                        addView(label(settingsCategoryTitle(category), 17, palette.primaryText, true))
                        addView(label(summary, 13, palette.secondaryText).apply { setPadding(0, dp(3), 0, 0) })
                    },
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
            },
        )
        setOnClickListener { openSettingsCategory(category) }
    }

    private fun settingsOverview(content: LinearLayout) {
        settingsPageTitle(
            content,
            getString(R.string.carlife_settings_overview),
            getString(R.string.carlife_settings_overview_subtitle),
        )
        val readiness = card().apply {
            setPadding(dp(20), dp(if (isExpandedSettingsLayout) 16 else 12), dp(20), dp(if (isExpandedSettingsLayout) 16 else 12))
        }
        readinessCard = readiness
        renderedReadiness = null
        refreshReadiness()
        content.addView(readiness, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })
        content.addView(
            button(getString(R.string.carlife_setup_guide), false) { openSetupGuide(fromSettings = true) },
            LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(6) },
        )
        content.addView(
            button(getString(R.string.about), true) { openSettingsCategory(SettingsCategory.ABOUT) },
            LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(6) },
        )
        content.addView(
            label(getString(R.string.carlife_setup_guide_description), 14, palette.secondaryText)
                .apply { setPadding(0, 0, 0, dp(20)) },
        )
        content.addView(
            settingsSummaryCard(SettingsCategory.CONNECTION, getString(R.string.carlife_settings_connection_summary)),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
        )
        content.addView(
            settingsSummaryCard(SettingsCategory.DISPLAY, getString(R.string.carlife_settings_display_summary)),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
        )
        content.addView(
            settingsSummaryCard(SettingsCategory.AUDIO, getString(R.string.carlife_settings_audio_summary)),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
        )
        content.addView(
            settingsSummaryCard(SettingsCategory.NAVIGATION, getString(R.string.carlife_settings_navigation_summary)),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
        )
        content.addView(
            settingsSummaryCard(SettingsCategory.VEHICLE, getString(R.string.carlife_settings_vehicle_summary)),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
        )
        content.addView(
            settingsSummaryCard(SettingsCategory.LANGUAGE, getString(R.string.carlife_settings_language_summary)),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(SETTINGS_BLOCK_GAP_DP) },
        )
        content.addView(settingsSectionHeading(R.string.carlife_settings_quick_settings))
        content.addView(quickSettingsCard(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        content.addView(settingsUtilitiesCard(), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(SETTINGS_BLOCK_GAP_DP)
        })
    }

    // CarLife has no per-run setup failure to report the way CarPlay's iAP2 handshake does,
    // so the readiness ladder starts at the connect/choose-phone states.
    private fun currentReadiness() = SettingsReadiness.of(
        setupError = false,
        active = CarLifeSessionService.isRunning,
        running = CarLifeSessionService.isRunning,
        wireless = persistence.wirelessEnabled(),
        phoneChosen = persistence.lastPhone() != null,
    )

    private fun refreshReadiness() {
        val card = readinessCard ?: return
        val state = currentReadiness()
        if (state == renderedReadiness) return
        renderedReadiness = state
        card.removeAllViews()
        val title = getString(
            when (state) {
                SettingsReadiness.SETUP_ERROR, SettingsReadiness.HOTSPOT_SETUP ->
                    R.string.setup_needs_attention
                SettingsReadiness.CONNECTED -> R.string.carlife_connected
                SettingsReadiness.CONNECTING -> R.string.carlife_connecting_phone
                SettingsReadiness.CHOOSE_IPHONE -> R.string.carlife_settings_choose_phone_title
                SettingsReadiness.READY_WIRELESS, SettingsReadiness.READY_USB -> R.string.ready
            },
        )
        val detail = when (state) {
            SettingsReadiness.SETUP_ERROR -> getString(R.string.setup_needs_attention)
            SettingsReadiness.HOTSPOT_SETUP -> getString(R.string.carlife_connect_hint)
            SettingsReadiness.CONNECTED -> getString(R.string.carlife_settings_ready_connected)
            SettingsReadiness.CONNECTING -> getString(R.string.carlife_settings_connecting_description)
            SettingsReadiness.CHOOSE_IPHONE -> getString(R.string.carlife_settings_choose_phone_description)
            SettingsReadiness.READY_WIRELESS ->
                getString(R.string.carlife_settings_ready_wireless, persistence.lastPhone().orEmpty())
            SettingsReadiness.READY_USB -> getString(R.string.carlife_settings_ready_usb)
        }
        val tint = when {
            state.needsAction -> palette.warning
            state == SettingsReadiness.CONNECTED -> palette.success
            else -> palette.primaryText
        }
        card.addView(label(title, 22, tint, true))
        card.addView(label(detail, 15, palette.secondaryText).apply { setPadding(0, dp(6), 0, 0) })
        when (state) {
            SettingsReadiness.CHOOSE_IPHONE -> card.addView(
                button(getString(R.string.carlife_settings_choose_phone_action), true) {
                    openProjection(wired = null)
                },
                matchButton(12, 56),
            )
            SettingsReadiness.SETUP_ERROR, SettingsReadiness.HOTSPOT_SETUP -> card.addView(
                button(getString(R.string.open_connection_setup), true) { openConnectionSetupFromSettings() },
                matchButton(12, 56),
            )
            else -> Unit
        }
    }

    private fun quickSettingsCard(): LinearLayout = card().also(::buildQuickSettings).also(::normalizeSpacing)

    private fun buildQuickSettings(card: LinearLayout) {
        toggle(
            card,
            getString(R.string.settings_autoconnect),
            getString(R.string.carlife_settings_default_connection_description),
            persistence.autoConnect(),
        ) { persistence.saveAutoConnect(it) }
        appearanceControl(card)
        toggle(
            card,
            getString(R.string.settings_mic),
            getString(R.string.settings_mic),
            persistence.micUplink(),
        ) { persistence.saveMicUplink(it) }
    }

    /** The day/night preference as a setting row, matching DiPlay's night-mode choice. */
    private fun appearanceControl(parent: LinearLayout) {
        val modes = listOf(
            CarLifePersistence.DAY_NIGHT_SYSTEM to getString(R.string.settings_daynight_auto),
            CarLifePersistence.DAY_NIGHT_DAY to getString(R.string.settings_daynight_day),
            CarLifePersistence.DAY_NIGHT_NIGHT to getString(R.string.settings_daynight_night),
        )
        choice(
            parent,
            getString(R.string.settings_daynight),
            modes,
            persistence.dayNightMode(),
            save = { persistence.saveDayNightMode(it) },
        )
    }

    private fun settingsUtilitiesCard(): LinearLayout = card().apply {
        setPadding(0, 0, 0, 0)
        addView(
            settingsUtilityRow(
                SettingsCategory.DIAGNOSTICS,
                R.drawable.ic_dp_diagnostics,
                getString(R.string.carlife_settings_diagnostics_summary),
            ),
            LinearLayout.LayoutParams(-1, dp(72)),
        )
        addView(
            View(this@CarLifeScreenActivity).apply { setBackgroundColor(palette.outline) },
            LinearLayout.LayoutParams(-1, dp(1)).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            },
        )
        addView(
            settingsUtilityRow(
                SettingsCategory.ADVANCED,
                R.drawable.ic_dp_advanced,
                getString(R.string.carlife_settings_advanced_subtitle),
                warning = true,
            ),
            LinearLayout.LayoutParams(-1, dp(72)),
        )
    }

    private fun settingsUtilityRow(
        category: SettingsCategory,
        icon: Int,
        description: String,
        warning: Boolean = false,
    ): LinearLayout = row().apply {
        val tint = if (warning) palette.warning else palette.accent
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        contentDescription = getString(R.string.carlife_settings_open_category, settingsCategoryTitle(category))
        foreground = rippleFocusRing()
        setPadding(dp(16), 0, dp(16), 0)
        addView(
            ImageView(this@CarLifeScreenActivity).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(tint)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(14) },
        )
        addView(
            column().apply {
                addView(
                    label(
                        settingsCategoryTitle(category), 17,
                        if (warning) palette.warning else palette.primaryText, true,
                    ),
                )
                addView(label(description, 13, palette.secondaryText).apply { setPadding(0, dp(2), 0, 0) })
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            ImageView(this@CarLifeScreenActivity).apply {
                setImageResource(R.drawable.ic_dp_chevron)
                imageTintList = ColorStateList.valueOf(palette.accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(12) },
        )
        setOnClickListener { openSettingsCategory(category) }
    }

    // ---- settings categories ----

    private fun connectionSettings(content: LinearLayout) {
        settingsPageTitle(
            content,
            getString(R.string.connection),
            getString(R.string.carlife_settings_connection_summary),
        )
        section(content, getString(R.string.s_1_choose_your_connection)) { card ->
            transportControls(card)
        }
        section(content, getString(R.string.settings_behaviour)) { card ->
            toggle(
                card,
                getString(R.string.settings_wireless),
                getString(R.string.carlife_connect_hint),
                persistence.wirelessEnabled(),
            ) { persistence.saveWirelessEnabled(it) }
            toggle(
                card,
                getString(R.string.settings_demo_data),
                getString(R.string.settings_demo_data),
                persistence.demoData(),
            ) { persistence.saveDemoData(it) }
        }
        section(content, getString(R.string.review_app_permissions)) { card ->
            card.addView(label(getString(R.string.carlife_setup_phone_description), 16, palette.secondaryText))
            card.addView(
                button(getString(R.string.carlife_setup_phone_permissions), false) { openAppSettings() },
                matchButton(12, 60),
            )
        }
    }

    private fun displaySettings(content: LinearLayout) {
        settingsPageTitle(
            content,
            getString(R.string.carlife_settings_display),
            getString(R.string.carlife_settings_display_summary),
        )
        section(content, getString(R.string.settings_video)) { card -> resolutionControls(card) }
        section(content, getString(R.string.settings_picture)) { card -> pictureControls(card) }
    }

    private fun audioSettings(content: LinearLayout) {
        settingsPageTitle(
            content,
            getString(R.string.audio),
            getString(R.string.carlife_settings_audio_summary),
        )
        section(content, getString(R.string.settings_sound)) { card ->
            card.addView(
                volumeSlider(getString(R.string.settings_tts_volume), persistence.ttsVolume()) {
                    persistence.saveTtsVolume(it)
                },
            )
        }
    }

    private fun navigationSettings(content: LinearLayout) {
        settingsPageTitle(
            content,
            getString(R.string.carlife_settings_navigation),
            getString(R.string.carlife_settings_navigation_summary),
        )
        section(content, getString(R.string.carlife_quick_navi)) { card ->
            card.addView(
                volumeSlider(getString(R.string.settings_tts_volume), persistence.ttsVolume()) {
                    persistence.saveTtsVolume(it)
                },
            )
        }
    }

    private fun vehicleSettings(content: LinearLayout) {
        settingsPageTitle(
            content,
            getString(R.string.carlife_settings_vehicle),
            getString(R.string.carlife_settings_vehicle_summary),
        )
        section(content, getString(R.string.carlife_channel_title)) { card ->
            toggle(
                card,
                getString(R.string.settings_demo_data),
                getString(R.string.settings_demo_data),
                persistence.demoData(),
            ) { persistence.saveDemoData(it) }
            toggle(
                card,
                getString(R.string.settings_mic),
                getString(R.string.settings_mic),
                persistence.micUplink(),
            ) { persistence.saveMicUplink(it) }
        }
    }

    private fun diagnosticsSettings(content: LinearLayout) {
        settingsPageTitle(
            content,
            getString(R.string.diagnostics),
            getString(R.string.carlife_settings_diagnostics_summary),
        )
        section(content, getString(R.string.settings_session_log)) { card ->
            card.addView(
                label(
                    CarLifeSessionLog.currentFile()?.absolutePath
                        ?: getString(R.string.settings_log_none),
                    12, palette.secondaryText,
                ),
            )
            card.addView(
                button(getString(R.string.settings_export_log), false) { exportLog() },
                matchButton(12, 60),
            )
        }
    }

    private fun advancedSettings(content: LinearLayout) {
        settingsPageTitle(
            content,
            getString(R.string.carlife_settings_advanced),
            getString(R.string.carlife_settings_advanced_subtitle),
        )
        section(content, getString(R.string.settings_interface_size)) { card ->
            card.addView(label(getString(R.string.carlife_settings_interface_size_description), 14, palette.secondaryText))
            card.addView(
                button(
                    "${getString(R.string.settings_interface_size)} · " +
                        InterfaceSize.displayName(this, InterfaceSize.preference(this)),
                    false,
                ) { InterfaceSize.showPicker(this) },
                matchButton(12, 60),
            )
        }
    }

    private fun languageSettings(content: LinearLayout) {
        settingsPageTitle(
            content,
            getString(R.string.language_section_title),
            getString(R.string.carlife_settings_language_summary),
        )
        section(content, getString(R.string.language_section_title), R.drawable.ic_dp_language) { card ->
            card.addView(label(getString(R.string.language_hint), 14, palette.secondaryText))
            val current = AppLocale.preference(this)
            val languageButton = button(
                "${getString(R.string.language_app_language)} · ${AppLocale.displayName(this, current)}",
                false,
            ) {}
            languageButton.setOnClickListener { AppLocale.showPicker(this) }
            card.addView(languageButton, matchButton(12, 60))
        }
    }

    // ---- connection setup page ----

    private fun connectionSetup(content: LinearLayout) {
        content.addView(label(getString(R.string.connection_setup), 34, palette.primaryText, true))
        content.addView(
            label(getString(R.string.carlife_setup_connection_description), 17, palette.secondaryText)
                .apply { setPadding(0, dp(8), 0, dp(24)) },
        )
        section(content, getString(R.string.s_1_choose_your_connection)) { card ->
            transportControls(card)
        }
        // DiPlay's step 2 pairs an iPhone over Bluetooth. CarLife has no equivalent pairing
        // handshake: the phone appears on the channel once the transport is up.
        section(content, getString(R.string.carlife_setup_phone_title)) { card ->
            card.addView(label(getString(R.string.carlife_setup_phone_description), 16, palette.secondaryText))
            card.addView(
                button(
                    getString(R.string.carlife_choose_phone_prefix) +
                        (persistence.lastPhone() ?: getString(R.string.carlife_setup_phone_none)),
                    false,
                ) { openProjection(wired = null) },
                matchButton(12, 60),
            )
            card.addView(
                button(getString(R.string.carlife_setup_phone_permissions), false) { openAppSettings() },
                matchButton(12, 60),
            )
        }
    }

    private fun transportControls(parent: LinearLayout) {
        choice(
            parent,
            getString(R.string.carlife_settings_transport),
            listOf(
                TRANSPORT_WIRED to getString(R.string.carlife_wired_connect),
                TRANSPORT_WIRELESS to getString(R.string.carlife_wireless_connect),
            ),
            if (persistence.wirelessEnabled()) TRANSPORT_WIRELESS else TRANSPORT_WIRED,
        ) { persistence.saveWirelessEnabled(it == TRANSPORT_WIRELESS) }
        parent.addView(label(getString(R.string.carlife_settings_transport_hint), 15, palette.secondaryText))
    }

    private fun resolutionControls(parent: LinearLayout) {
        val presets = CarLifePersistence.RESOLUTION_PRESETS
        val wide = resources.configuration.screenWidthDp >= 850
        val choices = if (wide) row() else column()
        presets.forEachIndexed { index, preset ->
            val active = preset[0] == persistence.videoWidth() && preset[1] == persistence.videoHeight()
            choices.addView(
                button("${preset[0]}×${preset[1]}" + if (active) "  ✓" else "", active) {
                    persistence.saveVideo(preset[0], preset[1], persistence.videoFrameRate())
                    render()
                },
                if (wide) LinearLayout.LayoutParams(0, dp(60), 1f).apply {
                    topMargin = dp(12); if (index > 0) marginStart = dp(12)
                } else matchButton(12, 60),
            )
        }
        parent.addView(choices)
        parent.addView(
            button(
                getString(R.string.settings_custom_scale) + " · " +
                    scaleLabel(persistence.videoWidth(), persistence.videoHeight()),
                false,
            ) { showScalePicker() },
            matchButton(16, 60),
        )
    }

    private fun scaleLabel(w: Int, h: Int): String {
        val pct = (w * 100f / 1920).toInt()
        return "${w}×${h} · $pct%"
    }

    private fun showScalePicker() {
        val presets = CarLifePersistence.RESOLUTION_PRESETS
        val labels = presets.map { "${it[0]}×${it[1]}" }.toTypedArray()
        var selected = presets.indexOfFirst {
            it[0] == persistence.videoWidth() && it[1] == persistence.videoHeight()
        }
        appDialogBuilder()
            .setTitle(R.string.settings_custom_scale)
            .setSingleChoiceItems(labels, selected) { _, index -> selected = index }
            .setPositiveButton(R.string.save) { _, _ ->
                if (selected >= 0) {
                    val preset = presets[selected]
                    persistence.saveVideo(preset[0], preset[1], persistence.videoFrameRate())
                    render()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pictureControls(parent: LinearLayout) {
        var brightness = persistence.brightness()
        var contrast = persistence.contrast()
        var saturation = persistence.saturation()
        var warmth = persistence.warmth()
        val save = { persistence.savePicture(brightness, contrast, saturation, warmth) }
        parent.addView(offsetSlider(getString(R.string.settings_brightness), brightness) {
            brightness = it; save()
        })
        parent.addView(offsetSlider(getString(R.string.settings_contrast), contrast) {
            contrast = it; save()
        })
        parent.addView(offsetSlider(getString(R.string.settings_saturation), saturation) {
            saturation = it; save()
        })
        parent.addView(offsetSlider(getString(R.string.settings_warmth), warmth) {
            warmth = it; save()
        })
    }

    // ---- about ----

    private fun about(content: LinearLayout) {
        feedbackSection(content)
        content.addView(label(getString(R.string.carlife_channel_title), 40, palette.primaryText, true))
        content.addView(
            label(getString(R.string.carlife_about_tagline), 20, palette.secondaryText)
                .apply { setPadding(0, dp(8), 0, dp(24)) },
        )
        section(content, getString(R.string.about_public_preview_prefix, version())) { card ->
            card.addView(
                label(getString(R.string.carlife_about_description), 17, palette.primaryText),
            )
            card.addView(
                button(getString(R.string.settings_check_update), false) { checkUpdate() },
                matchButton(12, 60),
            )
        }
        section(content, getString(R.string.made_possible_by_open_source)) { card ->
            card.addView(label(getString(R.string.about_credit), 16, palette.secondaryText))
        }
    }

    /** One-tap bug report: device info + crash/session log tail, copy or share. */
    private fun feedbackSection(content: LinearLayout) {
        section(content, getString(R.string.feedback_title)) { card ->
            card.addView(label(getString(R.string.feedback_hint), 15, palette.secondaryText))
            card.addView(
                button(getString(R.string.feedback_detect), false) { showBugReport() },
                matchButton(12, 60),
            )
        }
    }

    private fun bugReportText(): String {
        val head = buildString {
            append("== Limo 反馈 ==\n")
            append("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
            append("系统: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
            append("应用: ${version()}\n")
            append("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())}\n")
        }
        val logs = StringBuilder()
        CarLifeSessionLog.currentFile()?.let { f ->
            runCatching {
                val tail = f.readText().split("\n").takeLast(120).joinToString("\n")
                logs.append("\n== CarLife 会话日志（尾 120 行，含崩溃记录）==\n").append(tail)
            }
        } ?: logs.append("\n== 会话日志: 暂无 ==")
        return head + logs
    }

    private fun showBugReport() {
        val report = bugReportText()
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.feedback_title))
            .setMessage(report.take(1600) + if (report.length > 1600) "\n…（完整内容随复制/分享带出）" else "")
            .setPositiveButton(getString(R.string.feedback_copy)) { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Limo 反馈", report))
                android.widget.Toast.makeText(this, R.string.feedback_copy, android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(getString(R.string.feedback_share)) { _, _ ->
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, report)
                }
                startActivity(android.content.Intent.createChooser(intent, getString(R.string.feedback_share)))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Limo keeps its own updater; the about page only reuses it. */
    private fun checkUpdate() {
        android.widget.Toast
            .makeText(this, getString(R.string.state_scanning), android.widget.Toast.LENGTH_SHORT)
            .show()
        com.shilapi.xcertplay.AppUpdate.check { release, error ->
            runOnUiThread {
                when {
                    release != null && com.shilapi.xcertplay.AppUpdate.isAvailable(release, this) -> {
                        // Same package name as the upstream app: show the release page
                        // instead of auto-installing over Limo.
                        android.app.AlertDialog.Builder(this)
                            .setTitle(getString(R.string.update_available_no_size, release.tag))
                            .setMessage(
                                com.shilapi.xcertplay.AppUpdate.plainNotes(release.notes, 500) +
                                    "\n\n" + getString(R.string.update_overwrite_note),
                            )
                            .setPositiveButton(getString(R.string.update_view_page)) { _, _ ->
                                runCatching {
                                    startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(release.notesUrl),
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }

                    release != null -> android.widget.Toast.makeText(
                        this, getString(R.string.up_to_date), android.widget.Toast.LENGTH_SHORT,
                    ).show()

                    else -> appDialogBuilder()
                        .setTitle(R.string.settings_check_update)
                        .setMessage("${getString(R.string.settings_check_update)}: ${error.orEmpty()}")
                        .setPositiveButton(R.string.settings_check_update, null)
                        .show()
                }
            }
        }
    }

    // ---- projection ----

    private fun openProjection(wired: Boolean?) {
        val intent = Intent(this, CarLifeVideoActivity::class.java)
        when (wired) {
            true -> intent.putExtra("mode", "wired")
            false -> intent.putExtra("mode", "wireless")
            null -> if (persistence.wirelessEnabled()) intent.putExtra("mode", "wireless")
        }
        startActivity(intent)
    }

    private fun openSystem(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure {
            android.widget.Toast.makeText(
                this,
                getString(R.string.carlife_settings_open_app_settings_failed),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun openAppSettings() {
        openSystem(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )
    }

    private fun version() =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
            ?: "0.1.0-beta.1"

    // ---- status ----

    private fun refreshStatus() {
        if (isFinishing || isDestroyed) return
        val running = CarLifeSessionService.isRunning
        status?.text = when {
            running -> getString(R.string.carlife_connected)
            else -> persistence.lastPhone()?.let {
                getString(R.string.carlife_status_ready_for_prefix) + it
            } ?: getString(R.string.ready_when_you_are)
        }
        if (lastRunning != running) {
            connectButton?.text = getString(
                if (running) R.string.carlife_open_session else R.string.connect_phone,
            )
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        refreshReadiness()
    }

    /** Copies the current session log into Downloads so it survives without adb. */
    private fun exportLog() {
        val src = CarLifeSessionLog.currentFile()
        if (src == null || !src.isFile) {
            android.widget.Toast.makeText(
                this, getString(R.string.log_export_failed), android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        runCatching {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS,
            ).apply { mkdirs() }
            val dst = java.io.File(dir, src.name)
            src.copyTo(dst, overwrite = true)
            android.widget.Toast.makeText(
                this, getString(R.string.log_exported, dst.name), android.widget.Toast.LENGTH_LONG,
            ).show()
        }.onFailure {
            android.widget.Toast.makeText(
                this, getString(R.string.log_export_failed), android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // ---- appearance ----

    private fun refreshAppearance(): Boolean {
        val resolved = persistence.dayNightMode() != CarLifePersistence.DAY_NIGHT_DAY ||
            nightModeOrNullCompat()
        if (resolved == appNight) return false
        appNight = resolved
        return true
    }

    /** True when the system asks for dark and the user has not forced day. */
    private fun nightModeOrNullCompat(): Boolean =
        resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun requestAppearanceRender(focusAppearanceButton: Boolean = false) {
        render(
            appearanceOnly = true,
            preferredFocusTag = APPEARANCE_BUTTON_TAG.takeIf { focusAppearanceButton },
        )
    }

    private fun applyWindowAppearance() {
        window.setBackgroundDrawable(ColorDrawable(palette.background))
        window.statusBarColor = palette.systemBar
        window.navigationBarColor = palette.systemBar
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = palette.systemBarIconsAreDark
            isAppearanceLightNavigationBars = palette.systemBarIconsAreDark
        }
    }

    // ---- setup guide ----

    private fun openSetupGuide(fromSettings: Boolean) {
        setupFromSettings = fromSettings
        setupStep = SetupGuide.STEP_CAR
        page = PAGE_SETUP
        render()
    }

    private fun closeSetupGuide(connectNow: Boolean = false) {
        SetupGuide.markSeen(this)
        page = if (setupFromSettings) PAGE_SETTINGS else PAGE_HOME
        setupFromSettings = false
        setupStep = SetupGuide.STEP_CAR
        render()
        if (connectNow) openProjection(wired = persistence.wirelessEnabled())
    }

    private fun dilinkLabel(generation: DiLinkGeneration) = getString(
        when (generation) {
            DiLinkGeneration.DILINK_3 -> R.string.carlife_dilink_3
            DiLinkGeneration.DILINK_4 -> R.string.carlife_dilink_4
            DiLinkGeneration.DILINK_5 -> R.string.carlife_dilink_5
            DiLinkGeneration.UNKNOWN -> R.string.carlife_dilink_unknown
        },
    )

    private fun setupGuide(content: LinearLayout) {
        val step = setupStep.coerceIn(SetupGuide.STEP_CAR, SetupGuide.STEP_DONE)
        content.addView(
            label(
                getString(R.string.carlife_setup_step_of, step + 1, SetupGuide.STEP_COUNT),
                13, palette.accent, true,
            ).apply { letterSpacing = .12f },
        )
        val progress = row().apply { setPadding(0, dp(10), 0, dp(20)) }
        repeat(SetupGuide.STEP_COUNT) { index ->
            progress.addView(
                View(this).apply {
                    background = GradientDrawable().apply {
                        setColor(if (index <= step) palette.accent else palette.button)
                        cornerRadius = dp(3).toFloat()
                    }
                },
                LinearLayout.LayoutParams(0, dp(6), 1f).apply { if (index > 0) marginStart = dp(8) },
            )
        }
        content.addView(progress)
        when (step) {
            SetupGuide.STEP_CAR -> setupCarStep(content)
            SetupGuide.STEP_CONNECTION -> setupConnectionStep(content)
            SetupGuide.STEP_IPHONE -> setupPhoneStep(content)
            SetupGuide.STEP_FEATURES -> setupFeaturesStep(content)
            else -> setupDoneStep(content)
        }
        if (step == SetupGuide.STEP_DONE) return
        val footer = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        footer.addView(
            button(getString(R.string.carlife_setup_skip), false) { closeSetupGuide() },
            LinearLayout.LayoutParams(0, dp(60), 1f),
        )
        footer.addView(space(16), LinearLayout.LayoutParams(dp(16), 1))
        footer.addView(
            button(getString(R.string.carlife_setup_next), true) { setupStep = step + 1; render() },
            LinearLayout.LayoutParams(0, dp(60), 1f),
        )
        content.addView(footer)
    }

    private fun setupTitle(content: LinearLayout, title: Int, description: String) {
        content.addView(label(getString(title), 34, palette.primaryText, true))
        content.addView(label(description, 17, palette.secondaryText).apply { setPadding(0, dp(8), 0, dp(24)) })
    }

    private fun setupCarStep(content: LinearLayout) {
        val detection = DiLinkGeneration.detect()
        val selected = DiLinkGeneration.confirmed(this) ?: detection.generation
        setupTitle(
            content, R.string.carlife_setup_car_title,
            when (detection.source) {
                DiLinkGeneration.Source.SYSTEM_VERSION ->
                    getString(R.string.carlife_setup_car_detected, dilinkLabel(detection.generation))
                DiLinkGeneration.Source.LIKELY ->
                    getString(R.string.carlife_setup_car_likely, dilinkLabel(detection.generation))
                DiLinkGeneration.Source.NONE -> getString(R.string.carlife_setup_car_unknown)
            },
        )
        val card = card()
        card.addView(label(getString(R.string.carlife_setup_car_choose), 22, palette.primaryText, true))
        val wide = resources.configuration.screenWidthDp >= 850
        val choices = if (wide) row() else column()
        DiLinkGeneration.entries.forEachIndexed { index, generation ->
            val chosen = generation == selected
            choices.addView(
                button("${if (chosen) "✓  " else ""}${dilinkLabel(generation)}", chosen) {
                    DiLinkGeneration.save(this, generation)
                    render()
                },
                if (wide) LinearLayout.LayoutParams(0, dp(60), 1f).apply {
                    topMargin = dp(12); if (index > 0) marginStart = dp(12)
                } else matchButton(12, 60),
            )
        }
        card.addView(choices)
        detection.evidence?.let {
            card.addView(
                label(getString(R.string.carlife_setup_car_evidence, it), 13, palette.secondaryText)
                    .apply { setPadding(0, dp(16), 0, 0) },
            )
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }

    private fun setupConnectionStep(content: LinearLayout) {
        setupTitle(
            content, R.string.carlife_setup_connection_title,
            getString(R.string.carlife_setup_connection_description),
        )
        val card = card()
        transportControls(card)
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }

    /** DiPlay's iPhone step, reduced to the connected phone plus a permissions jump. */
    private fun setupPhoneStep(content: LinearLayout) {
        setupTitle(
            content, R.string.carlife_setup_phone_title,
            getString(R.string.carlife_setup_phone_description),
        )
        val card = card()
        val phone = persistence.lastPhone()
        card.addView(
            label(
                if (phone != null) getString(R.string.carlife_setup_phone_connected, phone)
                else getString(R.string.carlife_setup_phone_none),
                22, if (phone != null) palette.success else palette.primaryText, true,
            ),
        )
        card.addView(
            button(
                getString(R.string.carlife_choose_phone_prefix) +
                    (phone ?: getString(R.string.carlife_setup_phone_none)),
                phone == null,
            ) { openProjection(wired = null) },
            matchButton(16, 60),
        )
        card.addView(
            button(getString(R.string.carlife_setup_phone_permissions), false) { openAppSettings() },
            matchButton(12, 60),
        )
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }

    /**
     * DiPlay lists one row per [SetupGuide] feature, most of which route to the BYD cluster.
     * CarLife keeps the same card-and-badge shape for the three that exist here.
     */
    private fun setupFeaturesStep(content: LinearLayout) {
        val generation = DiLinkGeneration.current(this)
        setupTitle(
            content, R.string.carlife_setup_features_title,
            getString(R.string.carlife_setup_features_description, dilinkLabel(generation)),
        )
        val card = card()
        val before = card.childCount
        toggle(
            card,
            getString(R.string.settings_autoconnect),
            getString(R.string.carlife_settings_default_connection_description),
            persistence.autoConnect(),
        ) { persistence.saveAutoConnect(it) }
        if (card.childCount > before) {
            card.addView(setupBadges(getString(R.string.carlife_setup_badge_tested), palette.success))
        }
        val beforeWireless = card.childCount
        toggle(
            card,
            getString(R.string.settings_wireless),
            getString(R.string.carlife_connect_hint),
            persistence.wirelessEnabled(),
        ) { persistence.saveWirelessEnabled(it) }
        if (card.childCount > beforeWireless) {
            card.addView(setupBadges(getString(R.string.carlife_setup_badge_experimental), palette.warning))
        }
        toggle(
            card,
            getString(R.string.settings_mic),
            getString(R.string.settings_mic),
            persistence.micUplink(),
        ) { persistence.saveMicUplink(it) }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }

    private fun setupBadges(text: String, color: Int): LinearLayout = row().apply {
        setPadding(0, dp(2), 0, dp(14))
        addView(
            label(text, 12, color, true).apply {
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), color)
                }
                setPadding(dp(10), dp(3), dp(10), dp(3))
            },
            LinearLayout.LayoutParams(-2, -2),
        )
    }

    private fun setupDoneStep(content: LinearLayout) {
        setupTitle(
            content, R.string.carlife_setup_done_title,
            getString(R.string.carlife_setup_done_description),
        )
        val card = card()
        card.addView(
            button(getString(R.string.connect_phone), true) { closeSetupGuide(connectNow = true) },
            matchButton(0, 60),
        )
        card.addView(
            button(getString(R.string.settings), false) {
                SetupGuide.markSeen(this)
                setupFromSettings = false
                setupStep = SetupGuide.STEP_CAR
                page = PAGE_SETTINGS
                render()
            },
            matchButton(12, 60),
        )
        card.addView(
            button(getString(R.string.done), false) { closeSetupGuide() },
            matchButton(12, 60),
        )
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }

    // ---- widgets ----

    private fun section(parent: LinearLayout, title: String, icon: Int? = null, build: (LinearLayout) -> Unit) {
        val card = card()
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(16)) }
        if (icon != null) {
            heading.addView(
                ImageView(this).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(palette.accent)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) },
            )
        }
        heading.addView(label(title, 22, palette.primaryText, true), LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(heading)
        build(card)
        normalizeSpacing(card)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }

    private fun card() = column().apply {
        background = rounded(palette.surface, palette.outline)
        setPadding(dp(24), dp(24), dp(24), dp(24))
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    /** CJK glyphs sit low in the font's line box; this centres them on the control. */
    private class HeaderTitleView(context: android.content.Context) : TextView(context) {
        private val glyphBounds = android.graphics.Rect()

        override fun onDraw(canvas: android.graphics.Canvas) {
            val value = text.toString()
            paint.getTextBounds(value, 0, value.length, glyphBounds)
            val checkpoint = canvas.save()
            if (!glyphBounds.isEmpty && baseline >= 0) {
                val glyphCentre = baseline + (glyphBounds.top + glyphBounds.bottom) / 2f
                val contentCentre = paddingTop + (height - paddingTop - paddingBottom) / 2f
                canvas.translate(0f, contentCentre - glyphCentre)
            }
            super.onDraw(canvas)
            canvas.restoreToCount(checkpoint)
        }
    }

    private fun label(
        value: String,
        size: Int,
        color: Int,
        bold: Boolean = false,
        centreGlyphs: Boolean = false,
    ) = (if (centreGlyphs) HeaderTitleView(this) else TextView(this)).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun headerButton(title: String, icon: Int, compact: Boolean, click: () -> Unit) =
        button(title, false, click).apply {
            if (compact) textSize = 16f
            includeFontPadding = false
            setTextColor(palette.primaryText)
            setSingleLine(true)
            gravity = Gravity.CENTER
            val iconSize = dp(if (compact) 20 else 24)
            val drawable = getDrawable(icon)?.mutate()?.apply {
                setTint(palette.primaryText)
                setBounds(0, 0, iconSize, iconSize)
            }
            setCompoundDrawablesRelative(drawable, null, null, null)
            compoundDrawablePadding = dp(8)
            post {
                // Centre the icon on the visible glyphs, rather than the font's line box.
                val textBounds = android.graphics.Rect()
                paint.getTextBounds(title, 0, title.length, textBounds)
                if (!textBounds.isEmpty && baseline >= 0) {
                    val glyphCentre = baseline + (textBounds.top + textBounds.bottom) / 2f
                    val drawableCentre = paddingTop + (height - paddingTop - paddingBottom) / 2f
                    val offset = (glyphCentre - drawableCentre).roundToInt()
                    drawable?.setBounds(0, offset, iconSize, iconSize + offset)
                    contentOffsetY = drawableCentre - glyphCentre
                    invalidate()
                }
            }
        }

    private fun button(title: String, primary: Boolean, click: () -> Unit) =
        SettingButton(this, palette.secondaryText, palette.accent).apply {
            isAllCaps = false
            textSize = 18f
            setTextColor(if (primary) palette.onAccent else palette.primaryText)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = ripple(if (primary) palette.accent else palette.button, if (primary) palette.accent else palette.outline)
            setPadding(dp(16), 0, dp(16), 0)
            minHeight = dp(56)
            stateListAnimator = null
            compoundDrawablePadding = dp(12)
            foreground = focusRing()
            text = title
            setOnClickListener { click() }
            post {
                // Short buttons retain the full-size button's corner proportions.
                val radius = minOf(dp(20).toFloat(), height * 20f / cornerReferenceHeight)
                background = ripple(
                    if (primary) palette.accent else palette.button,
                    if (primary) palette.accent else palette.outline,
                    radius,
                )
                foreground = focusRing((radius / resources.displayMetrics.density).roundToInt())
            }
        }

    private fun iconButton(icon: Int, description: String, click: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(palette.accent)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        stateListAnimator = null
        background = ripple(palette.button, palette.outline)
        foreground = focusRing(radiusDp = 20)
        setOnClickListener { click() }
    }

    /**
     * Text in the "Title · Value" form opens a choice, so it reads as a setting row:
     * start-aligned title, muted value, chevron. Anything else stays a centred action button.
     * The plain text is unchanged, so callers keep matching "Title · Value".
     */
    private inner class SettingButton(
        context: android.content.Context,
        private val muted: Int,
        private val accent: Int,
    ) : Button(context) {
        var cornerReferenceHeight = 56
        var contentOffsetY = 0f

        override fun onDraw(canvas: android.graphics.Canvas) {
            val checkpoint = canvas.save()
            canvas.translate(0f, contentOffsetY)
            super.onDraw(canvas)
            canvas.restoreToCount(checkpoint)
        }

        override fun setText(text: CharSequence?, type: BufferType?) {
            val split = text?.indexOf(VALUE_SEPARATOR) ?: -1
            if (text == null || split <= 0) {
                super.setText(text, type)
                gravity = Gravity.CENTER
                setCompoundDrawablesRelative(null, null, null, null)
                return
            }
            val styled = android.text.SpannableString(text).apply {
                setSpan(
                    android.text.style.ForegroundColorSpan(muted), split, text.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            super.setText(styled, BufferType.SPANNABLE)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                null, null,
                context.getDrawable(R.drawable.ic_dp_chevron)?.mutate()?.apply { setTint(accent) }, null,
            )
        }
    }

    /** Ripple plus focus ring, so a D-pad focus and a touch ripple do not fight. */
    private fun rippleFocusRing(): android.graphics.drawable.LayerDrawable =
        android.graphics.drawable.LayerDrawable(
            arrayOf(
                android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(palette.ripple), null, null,
                ) as android.graphics.drawable.Drawable,
                focusRing(12) as android.graphics.drawable.Drawable,
            ),
        )

    /** Remote and D-pad users need to see where they are; touch mode never shows it. */
    private fun focusRing(radiusDp: Int = 20) = android.graphics.drawable.StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_focused),
            GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(radiusDp).toFloat()
                setStroke(dp(3), palette.focusRing)
            },
        )
    }

    private fun ripple(color: Int, stroke: Int, radius: Float = dp(20).toFloat()) =
        android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(palette.ripple),
            rounded(color, stroke, radius),
            null,
        )

    private fun rounded(color: Int, stroke: Int, radius: Float = dp(20).toFloat()) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            setStroke(dp(1), stroke)
        }

    private fun matchButton(top: Int = 0, height: Int = 68) =
        LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(top) }

    private fun space(height: Int) =
        View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)); tag = SPACER }

    /**
     * One vertical rhythm for every card, whatever margins each control builder chose:
     * buttons are one height with one gap, a note under a button closes its group with a
     * larger gap, and the last item adds nothing to the card's own padding.
     */
    private fun normalizeSpacing(container: LinearLayout, isCard: Boolean = true) {
        if (container.orientation != LinearLayout.VERTICAL) return
        for (i in container.childCount - 1 downTo 0) {
            if (container.getChildAt(i).tag == SPACER) container.removeViewAt(i)
        }
        fun isNote(view: View?) = view is TextView && view !is Button
        fun isToggleRow(view: View?) = view is LinearLayout &&
            view.orientation == LinearLayout.HORIZONTAL &&
            (0 until view.childCount).any { view.getChildAt(it) is Switch }
        fun isControl(view: View?) = view is Button || isToggleRow(view)
        var previous: View? = null
        var last: View? = null
        var noteFollowsButton = false
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val next = container.getChildAt(i + 1)
            val lp = child.layoutParams as? LinearLayout.LayoutParams ?: continue
            when {
                child is Button -> {
                    lp.height = dp(CONTROL_HEIGHT_DP); lp.topMargin = 0
                    lp.bottomMargin = dp(if (isNote(next)) NOTE_ATTACH_GAP_DP else CONTROL_GAP_DP)
                }

                isToggleRow(child) -> {
                    // Toggle rows carried their own vertical padding, which doubled the gap.
                    child.setPadding(child.paddingLeft, 0, child.paddingRight, 0)
                    lp.topMargin = 0
                    lp.bottomMargin = dp(if (isNote(next)) NOTE_ATTACH_GAP_DP else CONTROL_GAP_DP)
                }

                child is TextView -> {
                    if (!isNote(previous)) noteFollowsButton = isControl(previous)
                    child.setPadding(child.paddingLeft, 0, child.paddingRight, 0)
                    lp.topMargin = 0
                    lp.bottomMargin = dp(
                        when {
                            isNote(next) -> NOTE_LINE_GAP_DP
                            noteFollowsButton -> GROUP_GAP_DP
                            else -> CONTROL_GAP_DP
                        },
                    )
                }

                child is LinearLayout && child.orientation == LinearLayout.VERTICAL -> {
                    normalizeSpacing(child, isCard = false)
                    lp.topMargin = 0; lp.bottomMargin = 0
                }

                // Any other row gets the same gap; the card heading keeps its padding.
                i > 0 || !isCard -> {
                    lp.topMargin = 0; lp.bottomMargin = dp(CONTROL_GAP_DP)
                }
            }
            child.layoutParams = lp
            if (child.visibility != View.GONE) last = child
            previous = child
        }
        // A nested group keeps its trailing gap so it does not touch the control after it.
        if (isCard && !(last is LinearLayout && last.orientation == LinearLayout.VERTICAL)) {
            (last?.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.bottomMargin = 0; last.layoutParams = it
            }
        }
    }

    private fun toggle(
        parent: LinearLayout,
        title: String,
        description: String,
        value: Boolean,
        enabled: Boolean = true,
        save: (Boolean) -> Unit,
    ): Switch {
        val result = SettingsWidgets.createSwitchRow(
            context = this,
            label = title,
            description = description,
            checked = value,
            theme = SettingsTheme.card(palette),
            contentDescription = title,
            enabled = enabled,
            onChanged = save,
        )
        parent.addView(result.rowView)
        return result.switch
    }

    /** A "Title · Value" row that opens a single-choice dialog, like DiPlay's choice(). */
    private fun <T> choice(
        parent: LinearLayout,
        title: String,
        options: List<Pair<T, String>>,
        current: T,
        save: (T) -> Unit,
    ) {
        var selection = current
        val button = button("$title · ${options.first { it.first == current }.second}", false) {}
        button.setOnClickListener {
            var pending = selection
            appDialogBuilder().setTitle(title)
                .setSingleChoiceItems(options.map { it.second }.toTypedArray(), options.indexOfFirst { it.first == selection }) { _, index ->
                    pending = options[index].first
                }
                .setPositiveButton(R.string.save) { _, _ ->
                    if (pending != selection) {
                        selection = pending
                        save(selection)
                        button.text = "$title · ${options.first { it.first == selection }.second}"
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        parent.addView(button, matchButton(0, 60))
        parent.addView(space(12))
    }

    /** Signed -50..50 picture adjustment, mirroring the original settings sliders. */
    private fun offsetSlider(labelText: String, value: Int, onChange: (Int) -> Unit): View {
        val caption = TextView(this).apply {
            text = "$labelText  %+d".format(value)
            setTextColor(palette.secondaryText)
            textSize = 13f
        }
        val bar = SeekBar(this).apply {
            max = 100
            progress = value + 50
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                caption.text = "$labelText  %+d".format(progress - 50)
                onChange(progress - 50)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
        return column().apply {
            addView(caption)
            addView(bar)
        }
    }

    /** Plain 0..100 slider for absolute volumes. */
    private fun volumeSlider(labelText: String, value: Int, onChange: (Int) -> Unit): View {
        val caption = TextView(this).apply {
            text = "$labelText  $value%"
            setTextColor(palette.secondaryText)
            textSize = 13f
        }
        val bar = SeekBar(this).apply {
            max = 100
            progress = value
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                caption.text = "$labelText  $progress%"
                onChange(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
        return column().apply {
            addView(caption)
            addView(bar, LinearLayout.LayoutParams(-1, -2))
        }
    }

    protected fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    protected fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        internal const val PAGE_HOME = "home"
        internal const val PAGE_SETTINGS = "settings"
        internal const val PAGE_ABOUT = "about"
        internal const val PAGE_CONNECTION = "connection"
        internal const val PAGE_SETUP = "setup"

        private const val TRANSPORT_WIRED = "wired"
        private const val TRANSPORT_WIRELESS = "wireless"

        private const val APPEARANCE_BUTTON_TAG = "app_appearance_button"
        private const val VALUE_SEPARATOR = " · "
        private const val SPACER = "spacer"
        private const val CONTROL_HEIGHT_DP = 60
        private const val CONTROL_GAP_DP = 12
        private const val GROUP_GAP_DP = 20
        private const val NOTE_LINE_GAP_DP = 4
        private const val NOTE_ATTACH_GAP_DP = 6
        private const val SETTINGS_BLOCK_GAP_DP = 18
    }
}

/**
 * CarLife-only home and launcher target. Also handles USB attach: a cable plugged in while
 * the app is already open starts projection when auto-connect is on.
 */
class CarLifeHomeActivity : CarLifeScreenActivity() {
    override val initialPage: String get() = PAGE_HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 0.2.13 lesson: a silent throttled update check; a newer release opens a dialog
        // that jumps straight to the settings entry.
        com.shilapi.xcertplay.AppUpdate.autoCheck(this) { release ->
            if (release != null && !isFinishing && !isDestroyed) {
                appDialogBuilder()
                    .setTitle(getString(R.string.update_available_no_size, release.tag))
                    .setMessage(com.shilapi.xcertplay.AppUpdate.plainNotes(release.notes, 600))
                    .setPositiveButton(R.string.home_settings) { _, _ ->
                        startActivity(Intent(this, CarLifeSettingsActivity::class.java))
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
        if (intent?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED &&
            CarLifePersistence.get(this).autoConnect()
        ) {
            openProjectionIfAttached()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED &&
            CarLifePersistence.get(this).autoConnect()
        ) {
            openProjectionIfAttached()
        }
    }

    private fun openProjectionIfAttached() {
        startActivity(
            Intent(this, CarLifeVideoActivity::class.java).putExtra("mode", "wired"),
        )
    }
}