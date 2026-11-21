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
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.content.res.ColorStateList
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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

    private val blue = 0xFF1A73E8.toInt()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var bubble: TextView? = null
    private var panel: PanelRoot? = null
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var panelLp: WindowManager.LayoutParams
    private var expanded = true
    private var hidden = false
    private var busy = false

    private lateinit var chat: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var placeholder: TextView
    private lateinit var input: EditText
    private lateinit var btnSend: TextView
    private lateinit var tvPct: TextView

    private val history = mutableListOf<GeminiClient.Msg>()
    private var job: Job? = null
    private var pending = 0
    private var pendingPrompt = ""
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun screen(): Point { val p = Point(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(p); return p }
    private fun rounded(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = r.toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }
    private fun clean(s: String) = s.replace("**", "").replace("`", "")
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

    private fun promote(capturing: Boolean) {
        var t = 0
        if (Build.VERSION.SDK_INT >= 34) t = t or FGS_SPECIAL_USE
        if (capturing && Build.VERSION.SDK_INT >= 29) t = t or FGS_MEDIA_PROJECTION
        ServiceCompat.startForeground(this, 1, notif(), t)
    }

    private fun notif(): Notification {
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

    private fun newLp(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN }

    private fun clamp(l: WindowManager.LayoutParams, w: Int) {
        val s = screen()
        l.x = l.x.coerceIn(-w + dp(60), s.x - dp(60))
        l.y = l.y.coerceIn(0, s.y - dp(60))
    }

    private fun drag(v: View, lp: WindowManager.LayoutParams, w: Int, target: () -> View?, onTap: (() -> Unit)?) {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (!moved && (abs(dx) > dp(6) || abs(dy) > dp(6))) moved = true
                    if (moved) {
                        lp.x = ox + dx.toInt(); lp.y = oy + dy.toInt(); clamp(lp, w)
                        target()?.let { try { wm.updateViewLayout(it, lp) } catch (_: Exception) { } }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) onTap?.invoke(); true }
                else -> false
            }
        }
    }

    private fun show(expand: Boolean) {
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

    private fun setOverlayVisible(v: Boolean) {
        val view: View? = if (expanded) panel else bubble
        view?.visibility = if (v) View.VISIBLE else View.INVISIBLE
    }

    private fun applyOpacity(p: Int) {
        val a = 0.15f + 0.85f * p / 100f
        panel?.alpha = a
        bubble?.alpha = max(a, 0.4f)
        tvPct.text = "Transparansi $p%"
    }

    private fun buildBubble() {
        val s = screen(); val size = dp(52)
        bubble = TextView(this).apply {
            text = "AI"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(blue); setStroke(dp(2), Color.WHITE) }
            elevation = dp(6).toFloat()
        }
        bubbleLp = newLp(size, size).apply { x = s.x - size - dp(8); y = s.y / 3 }
        drag(bubble!!, bubbleLp, size, { bubble }) { show(true) }
    }

    private fun chip(t: String, bg: Int, click: () -> Unit) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
        setPadding(dp(8), dp(9), dp(8), dp(9)); background = rounded(bg, dp(10)); setOnClickListener { click() }
    }

    private fun lpW(w: Float = 1f, left: Int = 0) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w).apply { leftMargin = left }

    private fun buildPanel() {
        val s = screen()
        val w = min(dp(360), s.x - dp(24)); val h = min(dp(420), (s.y * 0.55f).toInt())
        val root = PanelRoot(this) { collapseKeyboard() }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF2101216.toInt(), dp(18), 0xFF1A73E8.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "✦ Wizz Ai"; setTextColor(blue); textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sq = dp(34)
        fun hb(t: String, click: () -> Unit) = chip(t, 0x22FFFFFF, click).apply { setPadding(0, 0, 0, 0) }
        header.addView(hb("👁") { setHidden(!hidden) }, LinearLayout.LayoutParams(sq, sq))
        header.addView(hb("–") {
            if (Prefs.autoHide(this)) setHidden(true)
            collapseKeyboard(); show(false)
        }, LinearLayout.LayoutParams(sq, sq).apply { leftMargin = dp(6) })
        header.addView(hb("✕") { stopSelf() }, LinearLayout.LayoutParams(sq, sq).apply { leftMargin = dp(6) })
        box.addView(header, LinearLayout.LayoutParams(-1, -2))

        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(chat) }
        placeholder = TextView(this).apply {
            text = "Konten disembunyikan.\nKetuk 👁 untuk menampilkan."; setTextColor(0xFF9AA0A6.toInt())
            gravity = Gravity.CENTER; textSize = 13f; visibility = View.GONE
        }
        val area = FrameLayout(this)
        area.addView(chatScroll, FrameLayout.LayoutParams(-1, -1))
        area.addView(placeholder, FrameLayout.LayoutParams(-1, -1))
        box.addView(area, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6); bottomMargin = dp(6) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Tanya sesuatu…"; setHintTextColor(0xFF777777.toInt()); setTextColor(Color.WHITE); textSize = 14f
            maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(12), dp(8), dp(12), dp(8)); background = rounded(0x33FFFFFF, dp(12))
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_DOWN) enableFocus()
                false
            }
        }
        row.addView(input, lpW(1f))
        btnSend = chip("Kirim", blue) { onSend() }
        row.addView(btnSend, LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        box.addView(row, LinearLayout.LayoutParams(-1, -2))

        val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        acts.addView(chip("📷 Layar", 0x33FFFFFF) { requestCapture(1) }, lpW())
        acts.addView(chip("🔤 OCR", 0x33FFFFFF) { requestCapture(2) }, lpW(1f, dp(6)))
        acts.addView(chip("🗑 Hapus", 0x33FFFFFF) { clearHistory() }, lpW(1f, dp(6)))
        box.addView(acts, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        tvPct = TextView(this).apply { setTextColor(0xFFBBBBBB.toInt()); textSize = 11f }
        val seek = SeekBar(this).apply {
            max = 100; progress = Prefs.opacity(this@FloatingService)
            val tint = ColorStateList.valueOf(blue)
            progressTintList = tint; thumbTintList = tint
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { applyOpacity(p) }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { Prefs.setOpacity(this@FloatingService, progress) }
            })
        }
        val sr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sr.addView(tvPct, LinearLayout.LayoutParams(dp(96), -2))
        sr.addView(seek, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(sr, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        root.addView(box, FrameLayout.LayoutParams(-1, -1))
        panel = root
        panelLp = newLp(w, h).apply { x = (s.x - w) / 2; y = dp(48) }
        drag(header, panelLp, w, { panel }, null)
    }

    private fun setHidden(h: Boolean) {
        hidden = h
        chatScroll.visibility = if (h) View.GONE else View.VISIBLE
        placeholder.visibility = if (h) View.VISIBLE else View.GONE
    }

    private fun enableFocus() {
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

    private fun collapseKeyboard() {
        val p = panel ?: return
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
            panelLp.flags = panelLp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (p.isAttachedToWindow) wm.updateViewLayout(p, panelLp)
        } catch (_: Exception) { }
    }

    private fun addBubble(user: Boolean, text: String): TextView {
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

    private fun scrollDown() { chatScroll.post { chatScroll.scrollTo(0, chat.bottom) } }

    private fun setBusy(b: Boolean) { busy = b; btnSend.text = if (b) "Stop" else "Kirim" }

    private fun clearHistory() {
        job?.cancel(); GeminiClient.cancel()
        history.clear(); Prefs.clearHistory(this); chat.removeAllViews(); toast("Riwayat dihapus")
    }

    private fun onSend() {
        if (busy) { job?.cancel(); GeminiClient.cancel(); return }
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText(""); collapseKeyboard()
        send(t, null, t)
    }

    private fun send(prompt: String, image: ByteArray?, label: String) {
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
                if (sb.isNotEmpty()) history.add(
