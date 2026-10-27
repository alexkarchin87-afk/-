package com.voiceagent.oneplus13.system
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
class SystemControllerLaunchTest {
    @Test fun acceptedLaunchUsesNewTaskExactlyOnce() {
        mockConstruction(MediaSessionReader::class.java).use {
            val context=mock(Context::class.java);val pm=mock(PackageManager::class.java)
            val intent=mock(Intent::class.java,RETURNS_SELF)
            `when`(context.packageManager).thenReturn(pm)
            `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(mock(AudioManager::class.java))
            `when`(pm.getLaunchIntentForPackage("org.telegram.messenger")).thenReturn(intent)
            assertEquals(SystemController.AppOpenResult.REQUESTED,SystemController(context).openAppResult("org.telegram.messenger"))
            verify(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);verify(context,times(1)).startActivity(intent)
        }
    }
    @Test fun permissionRejectionBecomesExplicitDeniedResult() {
        mockConstruction(MediaSessionReader::class.java).use {
            val context=mock(Context::class.java);val pm=mock(PackageManager::class.java);val intent=mock(Intent::class.java,RETURNS_SELF)
            `when`(context.packageManager).thenReturn(pm)
            `when`(context.getSystemService(Context.AUDIO_SERVICE)).thenReturn(mock(AudioManager::class.java))
            `when`(pm.getLaunchIntentForPackage("org.telegram.messenger")).thenReturn(intent)
            doThrow(SecurityException("synthetic policy")).`when`(context).startActivity(intent)
            assertEquals(SystemController.AppOpenResult.DENIED,SystemController(context).openAppResult("org.telegram.messenger"))
        }
    }
}
