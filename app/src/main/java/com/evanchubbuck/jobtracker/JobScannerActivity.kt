package com.evanchubbuck.jobtracker

import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/** Keeps an on-screen exit available while the camera is open. */
class JobScannerActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView = super.initializeContent().apply {
        viewFinder.setLaserVisibility(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        val root = findViewById<ViewGroup>(android.R.id.content)
        root.setBackgroundColor(Color.BLACK)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(safeArea.left, safeArea.top, safeArea.right, safeArea.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val back = Button(this).apply {
            text = "Back"
            isAllCaps = false
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.rgb(228, 237, 240))
            includeFontPadding = false
            gravity = Gravity.CENTER
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(16), 0, dp(20), 0)
            compoundDrawablePadding = dp(8)
            val arrow = ContextCompat.getDrawable(this@JobScannerActivity, R.drawable.ic_arrow_back)?.apply {
                setBounds(0, 0, dp(20), dp(20))
            }
            setCompoundDrawablesRelative(arrow, null, null, null)
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(242, 27, 41, 51))
                setStroke(dp(1), Color.argb(100, 181, 199, 206))
            }
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(65, 143, 217, 181)), shape, null)
            backgroundTintList = null
            stateListAnimator = null
            contentDescription = "Exit QR scanner"
            setOnClickListener { finish() }
        }
        val margin = dp(16)
        root.addView(back, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(48), Gravity.TOP or Gravity.START).apply {
            topMargin = margin
            leftMargin = margin
        })
    }
}
