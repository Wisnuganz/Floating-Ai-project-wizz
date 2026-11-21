package com.floatingai.app

import android.content.Context
import android.view.KeyEvent
import android.widget.FrameLayout

class PanelRoot(ctx: Context, private val onBack: () -> Unit) : FrameLayout(ctx) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
