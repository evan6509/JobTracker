package com.evanchubbuck.jobtracker

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import com.journeyapps.barcodescanner.CaptureActivity

/** Keeps an on-screen exit available while the camera is open. */
class JobScannerActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = findViewById<ViewGroup>(android.R.id.content)
        val back = Button(this).apply {
            text = "← Back"
            contentDescription = "Exit QR scanner"
            setOnClickListener { finish() }
        }
        val margin = (16 * resources.displayMetrics.density).toInt()
        root.addView(back, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = margin
            leftMargin = margin
        })
    }
}
