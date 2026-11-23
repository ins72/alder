package dev.alder

import android.content.Context

/**
 * Alder's own version name, as Settings, the "What's new" screen and crash reports show it.
 *
 * Alder no longer updates itself: the GitHub self-updater was removed (Google Play rejects an app that
 * downloads and installs other APKs), so there is nothing here to check, download or install.
 */
internal object Updates {
    fun mine(c: Context): String = c.packageManager.getPackageInfo(c.packageName, 0).versionName.orEmpty()
}