package com.floatingai.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.max

class FloatingService : Service() {

    companion object {
        const val ACTION_STOP = "com.floatingai.STOP"
        const val ACTION_PROJ = "com.floatingai.PROJ"
        const val ACTION_DENIED = "com.floatingai.DENIED"
        private const val CH = "fai_channel"
        private const val FGS_SPECIAL_USE = 0x40000000
        private const val FGS_MEDIA_PROJECTION = 0x20
        private const val DEFAULT_SHOT_PROMPT =
            "Jelaskan isi layar ini, lalu jawab pertanyaan atau tugas yang tampak beserta penjelasan singkat."

        @Volatile var running = false
        fun start(c: Context) = ContextCompat.startForegroundService(c, Intent(c, FloatingService::class.java))
    }

    internal val blue = 0xFF1A73E8.toInt()
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val ui = Handler(Looper.getMainLooper())
    internal lateinit var wm: WindowManager

    internal var bubble: TextView? = null
    internal var panel: PanelRoot? = null
    internal lateinit var bubbleLp: WindowManager.LayoutParams
    internal lateinit var panelLp: WindowManager.LayoutParams
    internal var expanded = true
    internal var hidden = false
    internal var busy = false

    internal lateinit var chat: LinearLayout
    internal lateinit var chatScroll: ScrollView
    internal lateinit var placeholder: TextView
    internal lateinit var input: EditText
    internal lateinit var btnSend: TextView
    internal lateinit var tvPct: TextView

    internal val history = mutableListOf<GeminiClient.Msg>()
    internal var job: Job? = null
    internal var liveActive = false
internal var liveSession: ScreenGrab.Session? = null
internal var liveJob: Job? = null
    internal var pending = 0
    internal var pendingPrompt = ""
    internal val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    internal fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    internal fun screen(): Point { val p = Point(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(p); return p }
    internal fun rounded(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = r.toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }
    internal fun clean(s: String) = s.replace("**", "").replace("`", "")
        .replace(Regex("(?m)^\\s*\\* "), "• ").replace(Regex("(?m)^#+\\s*"), "")

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "Wizz Ai", NotificationManager.IMPORTANCE_LOW))
        try { promote(false) } catch (e: Exception) { stopSelf(); return }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            toast("Izin overlay belum aktif"); stopSelf(); return
        }
        running = true
        history.addAll(Prefs.loadHistory(this))
        buildBubble()
        buildPanel()
        history.takeLast(20).forEach { addBubble(it.role == "user", it.text) }
        applyOpacity(Prefs.opacity(this))
        show(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_DENIED -> { pending = 0; toast("Izin tangkap layar ditolak") }
            ACTION_PROJ -> {
                val code = intent.getIntExtra("code", 0)
                @Suppress("DEPRECATION") val data = intent.getParcelableExtra<Intent>("data")
                handleProjection(code, data)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        job?.cancel(); GeminiClient.cancel(); scope.cancel()
        try { bubble?.let { wm.removeView(it) } } catch (_: Exception) { }
        try { panel?.let { wm.removeView(it) } } catch (_: Exception) { }
        try { recognizer.close() } catch (_: Exception) { }
        super.onDestroy()
    }

    internal fun promote(capturing: Boolean) {
        var t = 0
        if (Build.VERSION.SDK_INT >= 34) t = t or FGS_SPECIAL_USE
        if (capturing && Build.VERSION.SDK_INT >= 29) t = t or FGS_MEDIA_PROJECTION
        ServiceCompat.startForeground(this, 1, notif(), t)
    }

    internal fun notif(): Notification {
        val stop = PendingIntent.getService(this, 1,
            Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_ai)
            .setContentTitle("Wizz Ai aktif")
            .setContentText("Layar hanya diambil saat kamu menekan tombol Layar/OCR.")
            .setContentIntent(open).addAction(0, "Hentikan", stop)
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    internal fun show(expand: Boolean) {
        expanded = expand
        try { bubble?.let { wm.removeView(it) } } catch (_: Exception) { }
        try { panel?.let { wm.removeView(it) } } catch (_: Exception) { }
        try {
            if (expand) {
                panelLp.x = bubbleLp.x; panelLp.y = bubbleLp.y; clamp(panelLp, panelLp.width)
                wm.addView(panel, panelLp)
            } else {
                bubbleLp.x = panelLp.x; bubbleLp.y = panelLp.y; clamp(bubbleLp, bubbleLp.width)
                wm.addView(bubble, bubbleLp)
            }
        } catch (e: Exception) { toast("Gagal menampilkan jendela"); stopSelf() }
    }

    internal fun setOverlayVisible(v: Boolean) {
        val view: View? = if (expanded) panel else bubble
        view?.visibility = if (v) View.VISIBLE else View.INVISIBLE
    }

    internal fun applyOpacity(p: Int) {
        val a = 0.15f + 0.85f * p / 100f
        panel?.alpha = a
        bubble?.alpha = max(a, 0.4f)
        tvPct.text = "Transparansi $p%"
    }

    internal fun setHidden(h: Boolean) {
        hidden = h
        chatScroll.visibility = if (h) View.GONE else View.VISIBLE
        placeholder.visibility = if (h) View.VISIBLE else View.GONE
    }

    internal fun enableFocus() {
        val p = panel ?: return
        if (panelLp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) {
            panelLp.flags = panelLp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            try { wm.updateViewLayout(p, panelLp) } catch (_: Exception) { }
        }
        ui.postDelayed({
            input.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 120)
    }

    internal fun collapseKeyboard() {
        val p = panel ?: return
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
            panelLp.flags = panelLp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (p.isAttachedToWindow) wm.updateViewLayout(p, panelLp)
        } catch (_: Exception) { }
    }

    internal fun addBubble(user: Boolean, text: String): TextView {
        val tv = TextView(this).apply {
            this.text = clean(text); setTextColor(Color.WHITE); textSize = 14f
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(if (user) blue else 0x33FFFFFF, dp(12))
            setOnLongClickListener {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("AI", this.text))
                toast("Disalin"); true
            }
        }
        val lp = LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(4); bottomMargin = dp(4)
            gravity = if (user) Gravity.END else Gravity.START
            if (user) leftMargin = dp(36) else rightMargin = dp(36)
        }
        chat.addView(tv, lp)
        scrollDown()
        return tv
    }

    internal fun scrollDown() { chatScroll.post { chatScroll.scrollTo(0, chat.bottom) } }

    internal fun setBusy(b: Boolean) { busy = b; btnSend.text = if (b) "Stop" else "Kirim" }

    internal fun clearHistory() {
        job?.cancel(); GeminiClient.cancel()
        history.clear(); Prefs.clearHistory(this); chat.removeAllViews(); toast("Riwayat dihapus")
    }

    internal fun onSend() {
        if (busy) { job?.cancel(); GeminiClient.cancel(); return }
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText(""); collapseKeyboard()
        send(t, null, t)
    }

    internal fun send(prompt: String, image: ByteArray?, label: String) {
        val key = Prefs.apiKey(this)
        if (key.isBlank()) { addBubble(false, "Isi API key dulu di aplikasi utama Wizz Ai."); return }
        if (hidden) setHidden(false)
        addBubble(true, label)
        val ai = addBubble(false, "…")
        val ctx = history.takeLast(10).toList()
        history.add(GeminiClient.Msg("user", label))
        setBusy(true)
        job = scope.launch {
            val sb = StringBuilder(); var last = 0L
            try {
                GeminiClient.stream(key, Prefs.model(this@FloatingService), ctx, prompt, image).collect { chunk ->
                    sb.append(chunk)
                    val now = SystemClock.uptimeMillis()
                    if (now - last > 60) { last = now; ai.text = clean(sb.toString()); scrollDown() }
                }
            } catch (e: CancellationException) {
                if (sb.isEmpty()) sb.append("(dihentikan)")
            } catch (e: Exception) {
                val m = if (e is GeminiException) e.message ?: "Terjadi kesalahan" else "Gagal terhubung. Periksa koneksi internet."
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append("⚠ ").append(m)
            } finally {
                ai.text = clean(sb.toString()); scrollDown()
                if (sb.isNotEmpty()) history.add(GeminiClient.Msg("model", sb.toString()))
                Prefs.saveHistory(this@FloatingService, history)
                setBusy(false)
            }
        }
    }

    internal fun requestCapture(mode: Int) {
        if (busy) { toast("Tunggu jawaban selesai"); return }
        pending = mode
        pendingPrompt = input.text.toString().trim()
        collapseKeyboard()
        startActivity(Intent(this, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    internal fun handleProjection(code: Int, data: Intent?) {
        val mode = pending; pending = 0
        if (mode == 0 || data == null || code != Activity.RESULT_OK) return
        val prompt = pendingPrompt
        scope.launch {
            try { promote(true) } catch (e: Exception) { toast("Gagal memulai tangkap layar"); return@launch }
            setOverlayVisible(false)
            delay(500)
            val bmp = try { ScreenGrab.grab(this@FloatingService, screen(), code, data, ui) } catch (e: Exception) { null }
            setOverlayVisible(true)
            try { promote(false) } catch (_: Exception) { }
            if (bmp == null) {
                if (hidden) setHidden(false)
                addBubble(false, "Gagal mengambil layar. Saat dialog izin muncul, pilih 'Seluruh layar'.")
                return@launch
            }
            val small = withContext(Dispatchers.Default) { ScreenGrab.scaled(bmp, 1440) }
            if (mode == 1) {
                val jpg = withContext(Dispatchers.Default) { ScreenGrab.jpeg(small) }
                small.recycle()
                if (prompt.isNotEmpty()) input.setText("")
                send(prompt.ifBlank { DEFAULT_SHOT_PROMPT }, jpg, "📷 " + prompt.ifBlank { "Analisis layar" })
            } else {
                val text = ocr(small)
                small.recycle()
                if (hidden) setHidden(false)
                if (text.isBlank()) addBubble(false, "Tidak ada teks terdeteksi. Pastikan di dialog izin kamu memilih 'Seluruh layar'.")
                else {
                    addBubble(false, "Hasil OCR:\n$text")
                    input.setText(text.take(2000))
                    toast("Teks disalin ke kolom input. Tambahkan pertanyaan lalu Kirim.")
                }
            }
        }
    }

    internal suspend fun ocr(b: Bitmap): String = suspendCancellableCoroutine { c ->
        recognizer.process(InputImage.fromBitmap(b, 0))
            .addOnSuccessListener { if (c.isActive) c.resume(it.text) }
            .addOnFailureListener { if (c.isActive) c.resume("") }
    }
    
internal fun toggleLive() {
    if (liveActive) {
        stopLive()
    } else {
        requestCapture(3)
    }
}

internal fun stopLive() {
    liveActive = false
    liveJob?.cancel()
    liveJob = null
    liveSession?.close()
    liveSession = null

    try { promote(false) } catch (_: Exception) {}
    toast("Live Screen dihentikan")
}

internal fun startLiveSession(code: Int, data: Intent) {
    scope.launch {
        try {
            promote(true)
            setOverlayVisible(false)
            delay(500)

            val session = ScreenGrab.Session.start(
                this@FloatingService, screen(), code, data
            )

            setOverlayVisible(true)

            if (session == null) {
                promote(false)
                toast("Gagal memulai Live Screen")
                return@launch
            }

            liveSession?.close()
            liveSession = session
            liveActive = true
            toast("Live Screen aktif")

            liveJob = scope.launch {
                while (liveActive && running) {
                    var bitmap: Bitmap? = null

                    try {
                        bitmap = liveSession?.frame()

                        if (bitmap != null && !busy) {
                            val small = withContext(Dispatchers.Default) {
                                ScreenGrab.scaled(bitmap!!, 1280)
                            }
                            val jpg = withContext(Dispatchers.Default) {
                                ScreenGrab.jpeg(small)
                            }
                            small.recycle()

                            send(
                                "Analisis tampilan layar terbaru.",
                                jpg,
                                "◉ Live Screen"
                            )
                            job?.join()
                        } else {
                            bitmap?.recycle()
                        }
                    } catch (e: CancellationException) {
                        bitmap?.takeIf { !it.isRecycled }?.recycle()
                        break
                    } catch (e: Exception) {
                        bitmap?.takeIf { !it.isRecycled }?.recycle()
                    }

                    delay(5000)
                }
            }
        } catch (e: Exception) {
            setOverlayVisible(true)
            stopLive()
            toast("Live Screen gagal")
        }
    }
}

}
