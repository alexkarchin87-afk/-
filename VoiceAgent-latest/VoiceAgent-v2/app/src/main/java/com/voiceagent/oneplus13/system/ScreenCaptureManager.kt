package com.voiceagent.oneplus13.system

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Захват экрана по требованию через MediaProjection.
 *
 * Создавать в onCreate() Activity (registerForActivityResult допустим только до STARTED):
 *   val capture = ScreenCaptureManager(this)
 *   capture.requestPermission { granted -> ... }          // системный диалог
 *   lifecycleScope.launch(Dispatchers.Default) { val bmp = capture.captureCurrentScreen() }
 *
 * Android 14+: проекция живёт только внутри foreground-сервиса типа mediaProjection, токен
 * одноразовый, createVirtualDisplay на проекцию вызывается один раз — поэтому VirtualDisplay
 * создаётся один раз за сессию, а кадр забирается из ImageReader по запросу.
 */
class ScreenCaptureManager(activity: ComponentActivity) {

    private val appContext = activity.applicationContext

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            pendingCode = result.resultCode
            pendingData = data
            // Проекцию создаёт сам сервис, уже после startForeground().
            ContextCompat.startForegroundService(appContext, Intent(appContext, ScreenCaptureService::class.java))
        } else {
            finishRequest(false)
        }
    }

    /** Показывает системный диалог; [onResult] вызывается, когда проекция готова (true) или отклонена (false). */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        if (session != null) { onResult(true); return }
        readyCallback = onResult
        val mpm = appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(mpm.createScreenCaptureIntent())
    }

    fun isReady(): Boolean = session != null

    /**
     * Снимок текущего экрана (≤ [MAX_SIDE_PX] по длинной стороне) или null, если разрешения нет.
     * БЛОКИРУЕТ поток до получения кадра — вызывать из фонового потока, не из main.
     */
    fun captureCurrentScreen(): Bitmap? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.e(TAG, "captureCurrentScreen() вызван из main-потока")
            return null
        }
        return session?.grab()
    }

    fun stop() {
        appContext.stopService(Intent(appContext, ScreenCaptureService::class.java))
        stopSession()
    }

    // ---------------------------------------------------------------- сессия

    private class Session(
        val projection: MediaProjection,
        val width: Int,
        val height: Int,
        dpi: Int,
    ) {
        private val thread = HandlerThread("screen-capture").apply { start() }
        private val handler = Handler(thread.looper)
        private val frames = Semaphore(0)
        private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        private val display: VirtualDisplay
        @Volatile private var last: Bitmap? = null

        init {
            // Android 14+: callback обязан быть зарегистрирован до createVirtualDisplay.
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSession() }
            }, handler)
            reader.setOnImageAvailableListener({ frames.release() }, handler)
            display = projection.createVirtualDisplay(
                "VoiceAgentCapture", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
        }

        @Synchronized
        fun grab(): Bitmap? {
            frames.drainPermits()
            var image = reader.acquireLatestImage()
            if (image == null) {
                // Кадры идут только при изменении экрана: ждём первый, иначе отдаём последний.
                frames.tryAcquire(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                image = reader.acquireLatestImage()
            }
            if (image == null) return last?.takeUnless { it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)
            try {
                val plane = image.planes[0]
                val rowPadding = plane.rowStride - plane.pixelStride * width
                val padded = Bitmap.createBitmap(width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val bitmap = if (rowPadding == 0) padded else
                    Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
                last = bitmap
                return bitmap
            } finally {
                image.close()
            }
        }

        @Volatile private var closed = false

        fun close() {
            if (closed) return
            closed = true
            runCatching { display.release() }
            runCatching { reader.close() }
            runCatching { projection.stop() }
            thread.quitSafely()
            last = null
        }
    }

    companion object {
        private const val TAG = "ScreenCapture"
        private const val MAX_SIDE_PX = 1568      // больше ИИ всё равно не использует; экономит память и токены
        private const val FRAME_TIMEOUT_MS = 1500L

        @Volatile private var session: Session? = null
        @Volatile private var pendingCode = 0
        @Volatile private var pendingData: Intent? = null
        @Volatile private var readyCallback: ((Boolean) -> Unit)? = null

        private fun finishRequest(ok: Boolean) {
            readyCallback?.invoke(ok)
            readyCallback = null
        }

        /** Вызывается из [ScreenCaptureService] после startForeground(). */
        internal fun startSession(context: Context): Boolean {
            val data = pendingData
            val ok = try {
                check(data != null) { "нет результата разрешения" }
                val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = mpm.getMediaProjection(pendingCode, data)
                val (w, h) = screenSize(context)
                val scale = min(1f, MAX_SIDE_PX / max(w, h).toFloat())
                session?.close()
                session = Session(
                    projection,
                    (w * scale).roundToInt().coerceAtLeast(1),
                    (h * scale).roundToInt().coerceAtLeast(1),
                    context.resources.displayMetrics.densityDpi
                )
                true
            } catch (e: Exception) {
                Log.e(TAG, "не удалось запустить проекцию", e)
                false
            } finally {
                pendingData = null // токен одноразовый
            }
            finishRequest(ok)
            return ok
        }

        internal fun stopSession() {
            session?.close()
            session = null
        }

        @Suppress("DEPRECATION")
        private fun screenSize(context: Context): Pair<Int, Int> {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = wm.currentWindowMetrics.bounds
                b.width() to b.height()
            } else {
                val m = android.util.DisplayMetrics()
                wm.defaultDisplay.getRealMetrics(m)
                m.widthPixels to m.heightPixels
            }
        }
    }
}

/** Foreground-сервис типа mediaProjection — обязателен на Android 10+ (на 14+ без него SecurityException). */
class ScreenCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Захват экрана", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("VoiceAgent: захват экрана активен")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, NOTIF_ID, notification, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)

        if (!ScreenCaptureManager.startSession(this)) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ScreenCaptureManager.stopSession()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL_ID = "screen_capture"
        const val NOTIF_ID = 1003
    }
}
