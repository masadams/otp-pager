package org.example.otppager

import android.os.Build
import android.view.Window
import android.view.WindowManager

internal fun Window.protectPrivateContent() {
    addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    if (Build.VERSION.SDK_INT >= 31) setHideOverlayWindows(true)
}
