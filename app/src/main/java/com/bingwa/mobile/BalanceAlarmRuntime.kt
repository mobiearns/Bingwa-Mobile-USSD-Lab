package com.bingwa.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

private const val BALANCE_ALARM_CHANNEL = "bingwa_balance_alarm"
private const val BALANCE_ALARM_NOTIFICATION_ID = 7314
private const val ACTION_START_BALANCE_ALARM = "com.bingwa.mobile.action.START_BALANCE_ALARM"
private const val ACTION_SNOOZE_BALANCE_ALARM = "com.bingwa.mobile.action.SNOOZE_BALANCE_ALARM"
private const val ACTION_DISMISS_BALANCE_ALARM = "com.bingwa.mobile.action.DISMISS_BALANCE_ALARM"
private const val ACTION_BALANCE_ALARM_SNOOZE_DUE = "com.bingwa.mobile.action.BALANCE_ALARM_SNOOZE_DUE"
internal const val ACTION_BALANCE_ALARM_STATE_CHANGED = "com.bingwa.mobile.action.BALANCE_ALARM_STATE_CHANGED"
private const val ALARM_AIRTIME = "AIRTIME"
private const val ALARM_TOKENS = "TOKENS"

data class BalanceAlarmSnapshot(
    val airtimeActive: Boolean,
    val tokensActive: Boolean,
    val airtimeBalance: String,
    val tokenBalance: Int,
    val airtimeThreshold: Int,
    val tokenThreshold: Int
)

object BalanceAlarmController {
    private const val KEY_AIRTIME_ENABLED = "balance_alarm_airtime_enabled"
    private const val KEY_TOKENS_ENABLED = "balance_alarm_tokens_enabled"
    private const val KEY_AIRTIME_THRESHOLD = "balance_alarm_airtime_threshold"
    private const val KEY_TOKENS_THRESHOLD = "balance_alarm_tokens_threshold"
    private const val KEY_SNOOZE_MINUTES = "balance_alarm_snooze_minutes"
    private const val SNOOZE_REQUEST_CODE = 7315
    fun areAnyEnabled(context: Context): Boolean {
        val p = prefs(context)
        return p.getBoolean(KEY_AIRTIME_ENABLED, false) || p.getBoolean(KEY_TOKENS_ENABLED, false)
    }
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private fun activeKey(type: String) = if (type == ALARM_AIRTIME) "balance_alarm_airtime_active" else "balance_alarm_tokens_active"
    private fun dismissedKey(type: String) = if (type == ALARM_AIRTIME) "balance_alarm_airtime_dismissed" else "balance_alarm_tokens_dismissed"
    private fun snoozedKey(type: String) = if (type == ALARM_AIRTIME) "balance_alarm_airtime_snoozed_until" else "balance_alarm_tokens_snoozed_until"

    fun evaluateAirtime(context: Context, balance: Double) {
        if (balance < 0.0) return
        val p = prefs(context)
        evaluate(context, ALARM_AIRTIME, balance <= p.getInt(KEY_AIRTIME_THRESHOLD, 50).toDouble(), p.getBoolean(KEY_AIRTIME_ENABLED, false))
    }

    fun evaluateTokens(context: Context, balance: Int) {
        if (balance < 0) return
        val p = prefs(context)
        val hasUnlimitedPlan = runCatching { UnlimitedManager(context.applicationContext).isActive() }.getOrDefault(false)
        evaluate(context, ALARM_TOKENS, !hasUnlimitedPlan && balance <= p.getInt(KEY_TOKENS_THRESHOLD, 5), p.getBoolean(KEY_TOKENS_ENABLED, false))
    }

    private fun evaluate(context: Context, type: String, low: Boolean, enabled: Boolean) {
        val app = context.applicationContext
        val p = prefs(app)
        if (!enabled || !low) {
            p.edit().putBoolean(activeKey(type), false).putBoolean(dismissedKey(type), false).putLong(snoozedKey(type), 0L).apply()
            stopIfIdle(app)
            publish(app)
            return
        }
        val now = System.currentTimeMillis()
        if (!p.getBoolean(dismissedKey(type), false) && p.getLong(snoozedKey(type), 0L) <= now && !p.getBoolean(activeKey(type), false)) {
            p.edit().putBoolean(activeKey(type), true).putLong(snoozedKey(type), 0L).apply()
            startService(app)
        }
        publish(app)
    }

    fun evaluateSavedBalances(context: Context) {
        val p = prefs(context)
        val checkedAt = p.getLong("last_check_timestamp", 0L)
        val amount = p.getFloat("last_balance_amount", -1f).toDouble()
        if (checkedAt > 0L && System.currentTimeMillis() - checkedAt <= TimeUnit.MINUTES.toMillis(30) && amount >= 0.0) evaluateAirtime(context, amount)
        evaluateTokens(context, TokenManager(context.applicationContext).getBalance())
    }

    fun snapshot(context: Context): BalanceAlarmSnapshot {
        val p = prefs(context)
        return BalanceAlarmSnapshot(
            p.getBoolean(activeKey(ALARM_AIRTIME), false),
            p.getBoolean(activeKey(ALARM_TOKENS), false),
            BalanceChecker.getLastKnownBalanceDisplay(context).ifBlank { "Unavailable" },
            TokenManager(context).getBalance(),
            p.getInt(KEY_AIRTIME_THRESHOLD, 50),
            p.getInt(KEY_TOKENS_THRESHOLD, 5)
        )
    }

    fun dismiss(context: Context) {
        val p = prefs(context)
        val edit = p.edit()
        listOf(ALARM_AIRTIME, ALARM_TOKENS).forEach { type ->
            if (p.getBoolean(activeKey(type), false)) edit.putBoolean(activeKey(type), false).putBoolean(dismissedKey(type), true).putLong(snoozedKey(type), 0L)
        }
        edit.apply()
        stopAlarmService(context)
        publish(context)
    }

    fun snooze(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        val minutes = p.getInt(KEY_SNOOZE_MINUTES, 5).coerceIn(1, 180)
        val until = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes.toLong())
        val edit = p.edit()
        var hadActive = false
        listOf(ALARM_AIRTIME, ALARM_TOKENS).forEach { type ->
            if (p.getBoolean(activeKey(type), false)) {
                hadActive = true
                edit.putBoolean(activeKey(type), false).putBoolean(dismissedKey(type), false).putLong(snoozedKey(type), until)
            }
        }
        edit.apply()
        stopAlarmService(app)
        if (hadActive) {
            val pi = PendingIntent.getBroadcast(app, SNOOZE_REQUEST_CODE, Intent(app, BalanceAlarmTriggerReceiver::class.java).setAction(ACTION_BALANCE_ALARM_SNOOZE_DUE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            AlarmCompat.scheduleRtcWakeup(app, until, pi, preferExact = true, allowWhileIdle = true)
        }
        publish(app)
    }

    fun stopFromNotification(context: Context, snooze: Boolean) {
        if (snooze) snooze(context) else dismiss(context)
    }

    private fun startService(context: Context) {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, BalanceAlarmService::class.java).setAction(ACTION_START_BALANCE_ALARM)) }
            .onFailure { Log.e("BalanceAlarm", "Unable to start alarm service", it) }
    }

    private fun stopAlarmService(context: Context) { runCatching { context.applicationContext.stopService(Intent(context, BalanceAlarmService::class.java)) } }
    private fun stopIfIdle(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(activeKey(ALARM_AIRTIME), false) && !p.getBoolean(activeKey(ALARM_TOKENS), false)) stopAlarmService(context)
    }
    private fun publish(context: Context) { context.applicationContext.sendBroadcast(Intent(ACTION_BALANCE_ALARM_STATE_CHANGED).setPackage(context.packageName)) }
}

class BalanceAlarmTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_BALANCE_ALARM_SNOOZE_DUE) BalanceAlarmController.evaluateSavedBalances(context)
    }
}

class BalanceAlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_SNOOZE_BALANCE_ALARM -> BalanceAlarmController.stopFromNotification(context, true)
            ACTION_DISMISS_BALANCE_ALARM -> BalanceAlarmController.stopFromNotification(context, false)
        }
    }
}

class BalanceAlarmService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var delayedRing: Runnable? = null
    private val alarmOverlay by lazy { BalanceAlarmOverlay(this) }
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val snapshot = BalanceAlarmController.snapshot(this@BalanceAlarmService)
            if (!snapshot.airtimeActive && !snapshot.tokensActive) {
                alarmOverlay.remove()
                stopSelf()
                return
            }
            startForeground(BALANCE_ALARM_NOTIFICATION_ID, buildNotification(snapshot))
            if (BalanceAlarmActivity.isVisible) alarmOverlay.remove() else alarmOverlay.update(snapshot)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(BALANCE_ALARM_CHANNEL, "Bingwa Mobile balance alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Full-screen alerts when Bingwa Mobile airtime or tokens are low"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        ContextCompat.registerReceiver(this, stateReceiver, IntentFilter(ACTION_BALANCE_ALARM_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != null && intent.action != ACTION_START_BALANCE_ALARM) { stopSelf(startId); return START_NOT_STICKY }
        val snap = BalanceAlarmController.snapshot(this)
        if (!snap.airtimeActive && !snap.tokensActive) { stopSelf(startId); return START_NOT_STICKY }
        startForeground(BALANCE_ALARM_NOTIFICATION_ID, buildNotification(snap))
        if (BalanceAlarmActivity.isVisible) alarmOverlay.remove() else alarmOverlay.show(snap)
        startAlarmOutput()
        return START_STICKY
    }

    private fun buildNotification(s: BalanceAlarmSnapshot): Notification {
        val title = when {
            s.airtimeActive && s.tokensActive -> "Bingwa Mobile · Low balances"
            s.airtimeActive -> "Bingwa Mobile · Airtime low"
            else -> "Bingwa Mobile · Tokens low"
        }
        val body = when {
            s.airtimeActive && s.tokensActive -> "Your Bingwa Mobile airtime and token balances have reached their alert thresholds."
            s.airtimeActive -> "Your Bingwa Mobile airtime balance has reached its alert threshold."
            else -> "Your Bingwa Mobile token balance has reached its alert threshold."
        }
        val fullScreen = PendingIntent.getActivity(this, 7314, Intent(this, BalanceAlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snooze = PendingIntent.getBroadcast(this, 7316, Intent(this, BalanceAlarmActionReceiver::class.java).setAction(ACTION_SNOOZE_BALANCE_ALARM), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismiss = PendingIntent.getBroadcast(this, 7317, Intent(this, BalanceAlarmActionReceiver::class.java).setAction(ACTION_DISMISS_BALANCE_ALARM), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val fullScreenAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else true
        return NotificationCompat.Builder(this, BALANCE_ALARM_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreen, fullScreenAllowed).setContentIntent(fullScreen)
            .setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true).setSound(null)
            .addAction(android.R.drawable.ic_lock_idle_alarm, "Snooze", snooze)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop alarm", dismiss).build()
    }

    private fun startAlarmOutput() {
        stopAlarmOutput()
        val mode = getSharedPreferences("app_settings", Context.MODE_PRIVATE).getString("balance_alarm_sound_mode", "RING_AND_VIBRATE") ?: "RING_AND_VIBRATE"
        if (mode == "VIBRATE" || mode == "RING_AND_VIBRATE" || mode == "VIBRATE_THEN_RING") startVibration()
        if (mode == "VIBRATE_THEN_RING") delayedRing = Runnable { stopVibration(); startRingtone() }.also { handler.postDelayed(it, 12_000L) }
        else if (mode == "RING" || mode == "RING_AND_VIBRATE") startRingtone()
    }

    private fun startRingtone() {
        val saved = getSharedPreferences("app_settings", Context.MODE_PRIVATE).getString("balance_alarm_ringtone_uri", null)
        val uri = saved?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ringtone = runCatching { RingtoneManager.getRingtone(this, uri) }.getOrNull()
        ringtone?.let { tone ->
            runCatching {
                tone.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) tone.isLooping = true
                tone.play()
            }.onFailure { Log.e("BalanceAlarm", "Could not play selected alarm tone", it) }
        }
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        runCatching {
            val v = vibrator ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 700L, 350L), 0))
            else { @Suppress("DEPRECATION") v.vibrate(longArrayOf(0L, 700L, 350L), 0) }
        }.onFailure { Log.w("BalanceAlarm", "Vibration unavailable", it) }
    }

    private fun stopVibration() { runCatching { vibrator?.cancel() } }
    private fun stopAlarmOutput() {
        delayedRing?.let(handler::removeCallbacks); delayedRing = null
        runCatching { ringtone?.stop() }; ringtone = null; stopVibration()
    }
    override fun onDestroy() {
        runCatching { unregisterReceiver(stateReceiver) }
        alarmOverlay.remove()
        stopAlarmOutput()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

class BalanceAlarmActivity : ComponentActivity() {
    companion object {
        @Volatile
        var isVisible: Boolean = false
            private set
    }

    override fun onStart() {
        super.onStart()
        isVisible = true
        sendBroadcast(Intent(ACTION_BALANCE_ALARM_STATE_CHANGED).setPackage(packageName))
    }

    override fun onStop() {
        isVisible = false
        sendBroadcast(Intent(ACTION_BALANCE_ALARM_STATE_CHANGED).setPackage(packageName))
        super.onStop()
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else { @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                var state by remember { mutableStateOf(BalanceAlarmController.snapshot(this)) }
                DisposableEffect(Unit) {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent?) {
                            state = BalanceAlarmController.snapshot(this@BalanceAlarmActivity)
                            if (!state.airtimeActive && !state.tokensActive) finish()
                        }
                    }
                    ContextCompat.registerReceiver(this@BalanceAlarmActivity, receiver, android.content.IntentFilter(ACTION_BALANCE_ALARM_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
                    onDispose { runCatching { unregisterReceiver(receiver) } }
                }
                BalanceAlarmScreen(
                    state,
                    getSharedPreferences("app_settings", Context.MODE_PRIVATE).getInt("balance_alarm_snooze_minutes", 5),
                    { BalanceAlarmController.snooze(this); finish() },
                    { BalanceAlarmController.dismiss(this); finish() }
                )
            }
        }
    }
}

@Composable
private fun BalanceAlarmScreen(state: BalanceAlarmSnapshot, snoozeMinutes: Int, onSnooze: () -> Unit, onStop: () -> Unit) {
    val amber = Color(0xFFFFB547)
    val red = Color(0xFFFF667D)
    val count = (if (state.airtimeActive) 1 else 0) + (if (state.tokensActive) 1 else 0)
    val accent = if (state.tokensActive) red else amber
    val infinite = rememberInfiniteTransition(label = "alarmPulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "warningPulse"
    )
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val entrance by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "alarmEntrance"
    )

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF18283B), Color(0xFF090D14), Color(0xFF080B11)))
        ).padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .graphicsLayer { alpha = entrance; translationY = (1f - entrance) * 38f },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.size(88.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }
                    .background(accent.copy(alpha = 0.13f), RoundedCornerShape(50)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(66.dp).background(
                        Brush.linearGradient(listOf(amber, red)),
                        RoundedCornerShape(50)
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("!", color = Color(0xFF111722), fontSize = 42.sp, fontWeight = FontWeight.Black)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("BINGWA MOBILE  /  BALANCE GUARD", color = amber, fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold, letterSpacing = 2.2.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                when (count) { 2 -> "BOTH BALANCES\nARE LOW"; else -> if (state.airtimeActive) "AIRTIME IS\nRUNNING LOW" else "TOKENS ARE\nRUNNING LOW" },
                color = Color.White, fontSize = 29.sp, lineHeight = 33.sp,
                fontWeight = FontWeight.Black, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(9.dp))
            Text(
                if (count == 2) "Your airtime and token balances have reached their alert thresholds."
                else if (state.airtimeActive) "Your airtime balance has reached your alert threshold."
                else "Your token balance has reached your alert threshold.",
                color = Color(0xFFB6C1D0), fontSize = 14.sp, lineHeight = 21.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp)
            )
            Spacer(Modifier.height(24.dp))
            if (state.airtimeActive) AlarmBalanceCard(
                "AVAILABLE AIRTIME", state.airtimeBalance,
                "Alert threshold  ·  KSh " + state.airtimeThreshold, amber
            )
            if (state.tokensActive) {
                if (state.airtimeActive) Spacer(Modifier.height(12.dp))
                AlarmBalanceCard(
                    "AVAILABLE TOKENS", state.tokenBalance.toString(),
                    "Alert threshold  ·  " + state.tokenThreshold + " tokens", red
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(19.dp),
                colors = ButtonDefaults.buttonColors(containerColor = red)
            ) {
                Text("STOP ALARM", fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
            }
            Spacer(Modifier.height(11.dp))
            OutlinedButton(
                onClick = onSnooze,
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(19.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text("SNOOZE  ·  $snoozeMinutes MIN", fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Bingwa will keep monitoring your balance. The alert can return if it remains below your threshold.",
                color = Color(0xFF8190A4), fontSize = 11.sp, lineHeight = 16.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp)
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AlarmBalanceCard(title: String, balance: String, threshold: String, accent: Color) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(23.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF172334))
    ) {
        Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(4.dp).height(66.dp)
                    .background(accent, RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = accent, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp)
                Text(balance, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text(threshold, color = Color(0xFFAAB8C9), fontSize = 12.sp)
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = accent.copy(alpha = 0.14f)
            ) {
                Text("LOW", modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                    color = accent, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp)
            }
        }
    }
}
