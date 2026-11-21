package com.floatingai.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

class CaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val m = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(m.createScreenCaptureIntent(), 7)
        } catch (e: Exception) { deny() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            startService(Intent(this, FloatingService::class.java)
                .setAction(FloatingService.ACTION_PROJ)
                .putExtra("code", resultCode)
                .putExtra("data", data))
        } else deny()
        finish()
        overridePendingTransition(0, 0)
    }

    private fun deny() {
        startService(Intent(this, FloatingService::class.java).setAction(FloatingService.ACTION_DENIED))
        finish()
    }
}
