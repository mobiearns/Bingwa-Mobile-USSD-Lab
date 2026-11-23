package com.bingwa.mobile

import android.app.Service
import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.animation.ObjectAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Touch-modal overlay used only while a real balance-threshold alarm is active.
 * It never starts its own service: BalanceAlarmService owns its lifetime and audio.
 */
internal class BalanceAlarmOverlay(private val service: Service) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null

    fun show(snapshot: BalanceAlarmSnapshot) {
        if (!canShow(service) || (!snapshot.airtimeActive && !snapshot.tokensActive)) return
        val current = root
        if (current != null) {
            render(current, snapshot)
            return
        }

        val view = FrameLayout(service).apply {
            setBackgroundColor(AndroidColor.argb(218, 5, 9, 16))
            isClickable = true
            isFocusable = true
        }
        render(view, snapshot)
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.48f
            title = "Bingwa Mobile balance alarm"
        }
        try {
            windowManager.addView(view, params)
            root = view
        } catch (_: SecurityException) {
            root = null
        } catch (_: WindowManager.BadTokenException) {
            root = null
        }
    }

    fun update(snapshot: BalanceAlarmSnapshot) {
        if (!snapshot.airtimeActive && !snapshot.tokensActive) remove() else show(snapshot)
    }

    fun remove() {
        val view = root ?: return
        root = null
        runCatching { windowManager.removeViewImmediate(view) }
    }

    private fun render(host: FrameLayout, state: BalanceAlarmSnapshot) {
        host.removeAllViews()
        val count = (if (state.airtimeActive) 1 else 0) + (if (state.tokensActive) 1 else 0)
        val snoozeMinutes = service.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getInt("balance_alarm_snooze_minutes", 5).coerceIn(1, 180)

        val scroll = ScrollView(service).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(22), dp(28), dp(22), dp(28))
        }
        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(25), dp(22), dp(22))
            background = rounded(0xFF121D2B.toInt(), 28)
            elevation = dp(18).toFloat()
        }
        scroll.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        host.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val pulse = TextView(service).apply {
            text = "!"
            textSize = 32f
            setTextColor(0xFF101722.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFFB547.toInt())
                setStroke(dp(5), 0x33FFFFFF)
            }
            elevation = dp(8).toFloat()
        }
        card.addView(pulse, LinearLayout.LayoutParams(dp(76), dp(76)))
        ObjectAnimator.ofFloat(pulse, View.SCALE_X, 1f, 1.13f).apply {
            duration = 900L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(pulse, View.SCALE_Y, 1f, 1.13f).apply {
            duration = 900L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        card.alpha = 0f
        card.translationY = dp(34).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(420L)
            .setInterpolator(AccelerateDecelerateInterpolator()).start()

        card.addView(space(18), matchWrap())
        card.addView(label("BINGWA MOBILE  •  BALANCE GUARD", 11, 0xFFFFB547.toInt(), true).apply {
            letterSpacing = 0.12f
        }, matchWrap())
        card.addView(space(15), matchWrap())
        card.addView(label(
            when (count) {
                2 -> "BOTH BALANCES ARE LOW"
                else -> if (state.airtimeActive) "AIRTIME IS RUNNING LOW" else "TOKENS ARE RUNNING LOW"
            }, 25, AndroidColor.WHITE, true
        ).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER }, matchWrap())
        card.addView(space(9), matchWrap())
        card.addView(label(
            "Bingwa detected a balance at or below your alert threshold.",
            14, 0xFFB6C2D1.toInt(), false
        ).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; setLineSpacing(dp(3).toFloat(), 1f) }, matchWrap())
        card.addView(space(22), matchWrap())

        if (state.airtimeActive) {
            card.addView(balanceCard("AVAILABLE AIRTIME", state.airtimeBalance, "Alert threshold · KSh ${state.airtimeThreshold}", 0xFFFFB547.toInt()), matchWrap())
            card.addView(space(12), matchWrap())
        }
        if (state.tokensActive) {
            card.addView(balanceCard("AVAILABLE TOKENS", state.tokenBalance.toString(), "Alert threshold · ${state.tokenThreshold} tokens", 0xFFFF7185.toInt()), matchWrap())
            card.addView(space(22), matchWrap())
        } else {
            card.addView(space(10), matchWrap())
        }

        card.addView(actionButton("STOP ALARM", 0xFFFF667D.toInt(), AndroidColor.WHITE) {
            BalanceAlarmController.dismiss(service)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)))
        card.addView(space(10), matchWrap())
        card.addView(actionButton("SNOOZE  ·  $snoozeMinutes MIN", 0xFF26364A.toInt(), AndroidColor.WHITE) {
            BalanceAlarmController.snooze(service)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        card.addView(space(15), matchWrap())
        card.addView(label(
            "This alert is from Bingwa Mobile. Stop or snooze it to return to what you were doing.",
            11, 0xFF8F9FB2.toInt(), false
        ).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER }, matchWrap())
    }

    private fun balanceCard(title: String, value: String, threshold: String, accent: Int): LinearLayout =
        LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(15), dp(17), dp(15))
            background = rounded(0xFF1B2939.toInt(), 20)
            addView(label(title, 10, accent, true), matchWrap())
            addView(space(8), matchWrap())
            addView(label(value, 29, AndroidColor.WHITE, true), matchWrap())
            addView(space(5), matchWrap())
            addView(label(threshold, 12, 0xFFAAB8C8.toInt(), false), matchWrap())
        }

    private fun actionButton(text: String, backgroundColor: Int, textColor: Int, action: () -> Unit): TextView =
        TextView(service).apply {
            this.text = text
            textSize = 14f
            setTextColor(textColor)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = rounded(backgroundColor, 16)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun label(text: String, size: Int, color: Int, bold: Boolean) =
        TextView(service).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            typeface = android.graphics.Typeface.create(
                "sans-serif", if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
        }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun space(height: Int): View = View(service).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun matchWrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()

    companion object {
        fun canShow(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(context)
    }
}
