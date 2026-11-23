package dev.alder

import android.content.Context

/**
 * Carries a profile's player/display settings (quality, dub, audio and subtitle language, picture, text size) from
 * device to device through the account server.
 *
 * Each device holds a local copy in [Settings]; the server holds one set per account (the active profile). The server's
 * last-modified time is the single source of truth for ordering — never the device clock — so two devices with skewed
 * clocks still agree on which copy is newer.
 *
 * - [pull] runs at a profile's start: it applies the server's copy when that is newer than what this device last
 *   incorporated, or (when this device has unsynced changes) pushes the local copy up. Returns true when it changed
 *   local values, so the caller can recreate to show them.
 * - [changed] runs after the person edits a setting: it marks the copy dirty and pushes in the background, remembering
 *   the server's new revision. A failed push stays dirty and is retried on the next start.
 */
object SettingsSync {

    /** Pull this profile's settings from the server into prefs. Call off the main thread. True if local values changed. */
    fun pull(c: Context, authKey: String): Boolean {
        if (authKey.isBlank()) return false
        val snap = runCatching { Stremio.appSettings(authKey) }.getOrNull() ?: return false
        val dirty = Settings.settingsDirty(c)
        // Local changes not yet on the server win: push them, then record the new revision.
        if (dirty) {
            val at = Stremio.saveAppSettings(authKey, Settings.syncableSettings(c))
            if (at != null) { Settings.setSettingsRevision(c, at); Settings.setSettingsDirty(c, false) }
            return false
        }
        // Nothing synced yet and nothing on the server: a brand-new account keeps its local defaults.
        if (snap.values.isEmpty()) return false
        // Apply only when the server copy is newer than what this device already incorporated.
        if (snap.lastModified > Settings.settingsRevision(c)) {
            val changed = Settings.applySyncedSettings(c, snap.values)
            Settings.setSettingsRevision(c, snap.lastModified)
            return changed
        }
        return false
    }

    /** Record that the person changed a setting and push the new copy to the server. Call off the main thread. */
    fun changed(c: Context, authKey: String) {
        if (authKey.isBlank()) return
        Settings.setSettingsDirty(c, true)
        val at = Stremio.saveAppSettings(authKey, Settings.syncableSettings(c))
        if (at != null) { Settings.setSettingsRevision(c, at); Settings.setSettingsDirty(c, false) }
    }
}