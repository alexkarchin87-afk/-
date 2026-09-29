package com.voiceagent.oneplus13.system

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager

/**
 * Reads what's currently playing via the system's active media sessions — any app
 * that plays audio (Yandex Music, YouTube Music, Spotify, podcasts...) exposes one,
 * so this isn't tied to a single player.
 *
 * [MediaSessionManager.getActiveSessions] requires the caller to have an *enabled*
 * NotificationListenerService component — we already have one ([NotificationReader],
 * needed for reading incoming messages), so this rides on the same permission the
 * user already grants in system settings. No extra permission screen needed.
 */
class MediaSessionReader(private val context: Context) {

    data class NowPlaying(val title: String, val artist: String?, val packageName: String)

    private val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerComponent = ComponentName(context, NotificationReader::class.java)

    /** First active session with usable title metadata, or null if nothing is
     *  playing or Notification Access hasn't been granted yet. */
    fun currentTrack(): NowPlaying? {
        val controllers = activeControllers() ?: return null
        for (controller in controllers) {
            val metadata = controller.metadata ?: continue
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: continue
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            return NowPlaying(title, artist, controller.packageName)
        }
        return null
    }

    /** True if the given app currently has an active (playing-or-paused) media session —
     *  used before attempting to tap its "like" button, so we don't fumble around in an
     *  app that isn't actually the one playing anything. */
    fun hasActiveSession(packageName: String): Boolean =
        activeControllers()?.any { it.packageName == packageName } == true

    private fun activeControllers(): List<MediaController>? =
        runCatching { manager.getActiveSessions(listenerComponent) }.getOrNull()
}
