package org.example.otppager

import android.os.Bundle
import android.view.WindowManager
import com.journeyapps.barcodescanner.CaptureActivity

/** Embedded, explicit-only scanner. Never opens a third-party barcode app. */
class PairingScannerActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.protectPrivateContent()
    }
}
