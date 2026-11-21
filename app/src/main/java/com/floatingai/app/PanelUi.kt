package com.floatingai.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min

internal fun FloatingService.buildBubble() {
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

internal fun FloatingService.chip(t: String, bg: Int, click: () -> Unit) = TextView(this).apply {
    text = t; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
    setPadding(dp(8), dp(9), dp(8), dp(9)); background = rounded(bg, dp(10)); setOnClickListener { click() }
}

internal fun FloatingService.lpW(w: Float = 1f, left: Int = 0) =
    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w).apply { leftMargin = left }

internal fun FloatingService.buildPanel() {
    val svc = this
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
        if (Prefs.autoHide(svc)) setHidden(true)
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
        max = 100; progress = Prefs.opacity(svc)
        val tint = ColorStateList.valueOf(blue)
        progressTintList = tint; thumbTintList = tint
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { applyOpacity(p) }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { Prefs.setOpacity(svc, progress) }
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

internal fun FloatingService.newLp(w: Int, h: Int) = WindowManager.LayoutParams(
    w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
    PixelFormat.TRANSLUCENT
).apply { gravity = Gravity.TOP or Gravity.START; softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN }

internal fun FloatingService.clamp(l: WindowManager.LayoutParams, w: Int) {
    val s = screen()
    l.x = l.x.coerceIn(-w + dp(60), s.x - dp(60))
    l.y = l.y.coerceIn(0, s.y - dp(60))
}

internal fun FloatingService.drag(v: View, lp: WindowManager.LayoutParams, w: Int, target: () -> View?, onTap: (() -> Unit)?) {
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
