package com.shilapi.xcertplay.carlife

/**
 * CarLife settings, ported from DiPlay's settings half of the application-level interface layer.
 *
 * DiPlay hosts home, settings, about, connection and setup as pages of one activity, because
 * its settings entry buttons move between pages in place. Limo already declares a separate
 * settings activity in the manifest, and [CarLifeVideoActivity] opens it directly, so this class
 * keeps that entry point and simply starts the shared page machine on the settings page. The
 * rail, expanded shell, category dispatch, readiness card, setup guide and every control factory
 * live in [CarLifeScreenActivity], shared with the home screen.
 */
class CarLifeSettingsActivity : CarLifeScreenActivity() {
    override val initialPage: String get() = PAGE_SETTINGS
}