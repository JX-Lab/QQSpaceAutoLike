package io.github.yanganqi.qqspaceautolike.service

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.yanganqi.qqspaceautolike.R

class StopOverlayController(
    private val context: Context,
    private val onStopRequested: () -> Unit,
) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var overlayView: View? = null

    fun show() {
        if (windowManager == null || overlayView != null) return

        val density = context.resources.displayMetrics.density
        val paddingHorizontal = (14 * density).toInt()
        val paddingVertical = (10 * density).toInt()
        val view = TextView(context).apply {
            text = context.getString(R.string.overlay_action_stop)
            contentDescription = text
            setTextColor(ContextCompat.getColor(context, R.color.white))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f * density
                setColor(ContextCompat.getColor(context, R.color.accent))
                setStroke((1 * density).toInt().coerceAtLeast(1), ContextCompat.getColor(context, R.color.accent))
            }
            elevation = 10f * density
            isClickable = true
            isFocusable = false
            setOnClickListener { onStopRequested() }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (16 * density).toInt()
            y = (84 * density).toInt()
        }

        runCatching {
            windowManager.addView(view, params)
            overlayView = view
        }
    }

    fun hide() {
        val view = overlayView ?: return
        overlayView = null
        runCatching {
            windowManager?.removeView(view)
        }
    }
}
