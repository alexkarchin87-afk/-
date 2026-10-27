package com.voiceagent.oneplus13.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.vosk.Model
import org.vosk.android.StorageService
import java.io.IOException
import java.util.concurrent.Executors

/** Uses the existing Vosk sync implementation/path. Serializes copies by the two STT engines.
 * Unlike StorageService.unpack, reports runtime/native-link failures as well as IOExceptions.
 */
internal object VoskModelLoader {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "VoskModelLoader").apply { isDaemon = true } }
    fun load(context: Context, assetPath: String, ready: (Model) -> Unit, failed: (Throwable) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        worker.execute {
            var stage = "assets"
            val result = try {
                val missing = VoskModelFiles.missing { relative ->
                    try { context.assets.open("$assetPath/$relative").use { it.read() != -1 } }
                    catch (_: IOException) { false }
                }
                if (missing.isNotEmpty()) throw IOException("STT model assets incomplete")
                stage = "storage"
                val external = context.getExternalFilesDir(null) ?: throw IOException("STT storage unavailable")
                if (VoskModelFiles.invalidateIncompleteCache(VoskModelFiles.cacheDirectory(external, assetPath)))
                    Log.i("VA_STT", "cache=incomplete recopy=true")
                val path = StorageService.sync(context, assetPath, VoskModelFiles.STORAGE_PATH)
                stage = "native"
                Model(path)
            } catch (e: Exception) {
                Log.w("VA_STT", "model=failed stage=$stage kind=exception")
                main.post { failed(e) }
                return@execute
            } catch (e: LinkageError) {
                Log.w("VA_STT", "model=failed stage=$stage kind=native_link")
                main.post { failed(e) }
                return@execute
            }
            Log.i("VA_STT", "model=ready")
            if (!main.post { ready(result) }) result.close()
        }
    }
}
