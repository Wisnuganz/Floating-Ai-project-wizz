package com.floatingai.app

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {
    private val blue = 0xFF1A73E8.toInt()
    private lateinit var status: TextView
    private lateinit var keyIn: EditText
    private lateinit var modelIn: EditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(t: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun field(hint: String, pwd: Boolean) = EditText(this).apply {
        this.hint = hint; setHintTextColor(0xFF777777.toInt()); setTextColor(Color.WHITE); textSize = 14f
        setSingleLine(true)
        inputType = if (pwd) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { setColor(0xFF15181D.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0xFF2A2F37.toInt()) }
    }

    private fun btn(t: String, filled: Boolean, click: () -> Unit) = MaterialButton(
        this, null,
        if (filled) com.google.android.material.R.attr.materialButtonStyle
        else com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        text = t; isAllCaps = false
        if (filled) { setBackgroundColor(blue); setTextColor(Color.WHITE) } else setTextColor(Color.WHITE)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(32), dp(20), dp(32)) }
        root.addView(tv("Wizz Ai", 30f, Color.WHITE, true))
        root.addView(tv("Asisten belajar AI yang melayang di atas aplikasi lain. Layar hanya diambil saat kamu menekan tombol dan menyetujui dialog sistem.", 13f, 0xFF9AA0A6.toInt(), false))
        status = tv("", 13f, Color.WHITE, false)
        root.addView(status)

        root.addView(tv("Gemini API key", 13f, blue, true))
        keyIn = field("Tempel API key dari aistudio.google.com", true).also { it.setText(Prefs.apiKey(this)) }
        root.addView(keyIn)
        root.addView(tv("Model", 13f, blue, true))
        modelIn = field("gemini-2.5-flash", false).also { it.setText(Prefs.model(this)) }
        root.addView(modelIn)

        root.addView(SwitchCompat(this).apply {
            text = "Sembunyikan jawaban saat jendela diperkecil"; setTextColor(Color.WHITE); textSize = 13f
            isChecked = Prefs.autoHide(this@MainActivity)
            setPadding(0, dp(14), 0, dp(6))
            setOnCheckedChangeListener { _, c -> Prefs.setAutoHide(this@MainActivity, c) }
        })

        root.addView(btn("1. Izinkan tampil di atas aplikasi lain", false) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(btn("2. Izinkan notifikasi (Android 13+)", false) {
            if (Build.VERSION.SDK_INT >= 33)
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            else toast("Tidak perlu di versi Android ini")
        })
        root.addView(btn("Simpan pengaturan", false) { save(); toast("Tersimpan") })
        root.addView(btn("Mulai Wizz Ai", true) {
            save()
            when {
                !Settings.canDrawOverlays(this) -> toast("Izinkan overlay dulu (langkah 1)")
                Prefs.apiKey(this).isBlank() -> toast("Isi API key dulu")
                else -> { FloatingService.start(this); moveTaskToBack(true) }
            }
        })
        root.addView(btn("Hentikan Wizz Ai", false) {
            startService(Intent(this, FloatingService::class.java).setAction(FloatingService.ACTION_STOP))
        })
        root.addView(btn("Hapus riwayat percakapan", false) {
            Prefs.clearHistory(this); toast("Riwayat dihapus")
        })
        root.addView(tv("Privasi: API key terenkripsi di perangkat & tidak pernah ditulis ke log. Screenshot hanya di memori, tidak disimpan ke galeri, dan langsung dibuang setelah dianalisis. Notifikasi tetap tampil selama layanan berjalan.", 12f, 0xFF9AA0A6.toInt(), false).apply { setPadding(0, dp(16), 0, 0) })

        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(root) })
    }

    override fun onResume() {
        super.onResume()
        val ov = Settings.canDrawOverlays(this)
        status.text = (if (ov) "✓ Izin overlay aktif" else "✗ Izin overlay belum aktif") +
            "\n" + (if (FloatingService.running) "● Wizz Ai sedang berjalan" else "○ Wizz Ai belum berjalan")
    }

    private fun save() {
        Prefs.setApiKey(this, keyIn.text.toString())
        Prefs.setModel(this, modelIn.text.toString())
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
