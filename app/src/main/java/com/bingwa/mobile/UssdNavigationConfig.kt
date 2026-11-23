package com.bingwa.mobile

internal object UssdNavigationConfig {
    const val STEP_DELAY_MS = 0L
    // Accessibility trees and carrier USSD sessions update asynchronously. These intervals
    // avoid hammering OEM dialers while reacting immediately to real popup changes.
    const val EVENT_HOT_POLL_MS = 10L
    const val ACCESSIBILITY_NOTIFICATION_TIMEOUT_MS = 16L
    const val DUPLICATE_EVENT_WINDOW_MS = 20L
    const val FAST_VERIFY_POLL_MS = 8L
    const val HOT_SEND_RETRY_DELAY_MS = 15L
    const val SEND_RETRY_DELAY_MS = 25L
    const val POST_WRITE_VERIFY_POLL_MS = 10L
    // Retry ACTION_SET_TEXT only after the dialer has had time to rebuild/clear
    // its field. Retries are bounded and require an empty current-step field.
    const val SET_TEXT_RETRY_DELAY_MS = 15L
    const val MAX_SET_TEXT_ATTEMPTS = 3
    const val POST_WRITE_SEND_RETRY_MS = 12L
    const val STEP_TIMEOUT_MS = 25_000L
    const val STARTUP_STEP_TIMEOUT_MS = 20_000L
    const val FINAL_RESPONSE_TIMEOUT_MS = 25_000L
    const val PENDING_STEP_TIMEOUT_MS = 25_000L
    const val PENDING_ADVANCE_TIMEOUT_MS = 15_000L
    const val ROOT_REACQUIRE_TIMEOUT_MS = 12_000L
    const val PENDING_STEP_ADVANCE_TIMEOUT_MS = 25_000L
    const val PENDING_STEP_ADVANCE_KICK_MS = 15L
    const val POST_SEND_STABILIZE_MS = 0L
    const val VERIFY_POLL_MS = 15L
    const val RAPID_POST_POPUP_POLL_MS = 12L
    const val RAPID_POST_POPUP_VERIFY_MS = 10L
    const val RAPID_POST_POPUP_SEND_RETRY_MS = 15L
    const val MAX_VERIFY_ATTEMPTS = 5
    const val MAX_SEND_ATTEMPTS = 4
    const val NO_FIELD_PATIENCE = 2
    const val RECENT_INPUT_GRACE_MS = 8_000L
    const val RECENT_VERIFIED_INPUT_GRACE_MS = 8_000L
    const val RECENT_UI_EVENT_GRACE_MS = 300L
    const val RECENT_USSD_CONTEXT_WINDOW_MS = 500L
    const val STEP_RETRY_DELAY_MS = 20L
    const val MAX_STEP_RETRIES = 3
    const val GESTURE_SETTLE_MS = 0L
    const val POPUP_STABILITY_DELAY_MS = 0L
    const val REDIAL_COOLDOWN_MS = 2_000L
    const val PENDING_ADVANCE_KICK_MS = 12L
    const val ROOT_REACQUIRE_RETRY_DELAY_MS = 20L
    const val DIALOG_DISMISS_SETTLE_MS = 40L
    const val UI_KEEP_VISIBLE_INTERVAL_MS = 250L
    const val STARTUP_UI_KEEP_VISIBLE_MS = 1_000L
    const val STEP_TRANSITION_GUARD_MS = 50L
    const val MIN_STEP_TRANSITION_MS = 50L
    const val CHANNEL_ID = "bingwa_ussd"
    const val NOTIFICATION_ID = 2001

    const val FORCEFUL_WRITE_PASSES = 5
    const val WRITE_VERIFICATION_PASSES = 5
    const val WRITE_VERIFICATION_SETTLE_MS = 40L
    const val SET_TEXT_BURST_ATTEMPTS = 1
    const val PASTE_BURST_ATTEMPTS = 1
    const val SETTLE_BETWEEN_WRITE_PASSES_MS = 60L
    const val PRIME_TARGET_SETTLE_MS = 120L
    const val CHAR_GESTURE_STAGGER_MS = 10L
    const val CHAR_GESTURE_DURATION_MS = 15L
    const val CHAR_GESTURE_SPREAD_X = 22
    const val CHAR_GESTURE_SPREAD_Y = 22
    const val TAP_GESTURE_RETRY_SETTLE_MS = 0L
    const val TAP_GESTURE_DURATION_MS = 6L
    const val POST_GESTURE_WAIT_MS = 120L
    const val INPUT_TARGET_DEPTH = 4
    const val INPUT_DESCENT_DEPTH = 3
    const val INPUT_NEARBY_SCOPE_DEPTH = 2
    const val MAX_CANDIDATE_NODES = 8
    const val MENU_PARSE_CACHE_TTL_MS = 500L

    val MULTI_SPACE_REGEX = Regex("\\s+")
    val NON_ALPHANUMERIC_REGEX = Regex("[^a-z0-9]+")
    val MENU_OPTION_REGEX = Regex("""^(\d+)\s*[\)\].:\-]?\s*(.+)$""")
    val MENU_OPTION_NUMBER_ONLY_REGEX = Regex("""^(\d+)\s*[\)\].:\-]?$""")

    val USSD_PACKAGES = setOf(
        "android",
        "com.android.phone", "com.android.server.telecom", "com.google.android.dialer",
        "com.samsung.android.incallui", "com.samsung.android.app.telephonyui",
        "com.samsung.android.dialer", "com.samsung.android.phone", "com.sec.phone",
        "com.sec.android.app.popupuireceiver", "com.android.incallui", "com.android.dialer",
        "com.mediatek.phone", "com.transsion.phone", "com.infinix.phone",
        "com.tecno.phone", "com.itel.phone", "com.transsion.incallui",
        "com.mediatek.incallui", "com.android.contacts", "com.huawei.contacts",
        "com.huawei.incallui", "com.vivo.contacts", "com.vivo.dialer", "com.bbk.dialer",
        "com.bbk.telephony", "com.hihonor.dialer", "com.heytap.dialer", "com.coloros.dialer",
        "com.coloros.telephony", "com.oplus.dialer", "com.oplus.telephony", "com.oneplus.dialer",
        "com.realme.dialer", "com.miui.securitycenter", "com.miui.phone", "com.android.mms",
        "com.google.android.apps.tachyon", "com.sprd.contacts",
        "com.android.server.telecom.ui", "com.android.phone.incallui",
        "com.secure.android.phone", "com.asus.android.dialer", "com.asus.dialer",
        "com.huawei.android.dialer", "com.oppo.dialer",
        "com.miui.dialer", "com.google.android.apps.legacy.dialer",
        "com.android.contacts.activities", "com.android.phone.common",
        "com.mediatek.telecom", "com.sprd.incallui", "com.sprd.dialer",
        "com.transsion.dialer", "com.transsion.contacts",
        "com.itel.dialer", "com.tecno.dialer", "com.infinix.dialer",
        "com.sh.smart.caller", "com.sh.smart.caller.ui", "com.transsion.smartcaller",
        "com.infinix.smartcaller", "com.transsion.telephony",
        "com.realme.telephony", "com.oneplus.phone",
        "com.nothing.phone", "com.nothing.dialer",
        "com.sharp.android.dialer", "com.sony.ericsson.dialer", "com.sonymobile.android.dialer",
        "com.motorola.dialer", "com.motorola.contacts",
        "com.lenovo.dialer", "com.zui.dialer",
        "com.xiaomi.dialer", "com.xiaomi.contacts", "com.xiaomi.telephony", "com.miui.incallui",
        "com.vivo.telephony", "com.vivo.incallui",
        "com.hihonor.contacts", "com.hihonor.incallui",
        "com.sec.android.app.telephonyui",
        "cn.nubia.dialer", "com.meizu.media.telephony",
        "com.zte.dialer", "com.zte.incallui",
        "com.android.stk", "com.android.stk2", "com.mediatek.stk", "com.sprd.stk",
        "com.qualcomm.qti.stk", "com.android.stk.StkDialogActivity", "com.android.stk.StkMenuActivity"
    )
    val USSD_PACKAGE_HINTS = listOf(
        "phone", "dialer", "telecom", "incall", "callui", "telephony", "telephonyui", "ussd", "stk", "simtoolkit", "carrier",
        "miui", "hyperos", "coloros", "heytap", "oplus", "honor", "magic", "transsion", "vivo", "bbk", "realme", "redmi",
        "samsung", "huawei", "infinix", "tecno", "itel", "mediatek", "sprd",
        "oppo", "oneplus", "nubia", "redmagic", "nothing", "sharp", "sony",
        "motorola", "lenovo", "xiaomi", "zui", "asus", "smart.caller", "smartcaller", "caller", "xos", "hios", "pobox", "flyme"
    )
    val BLOCKED_PACKAGES = setOf(
        "com.bingwa.mobile", "com.android.launcher",
        "com.google.android.apps.nexuslauncher", "com.miui.home", "com.miui.launcher",
        "com.huawei.android.launcher", "com.hihonor.launcher", "com.vivo.launcher",
        "com.bbk.launcher", "com.oplus.launcher", "com.coloros.launcher",
        "com.realme.launcher", "com.transsion.launcher", "com.infinix.launcher",
        "com.tecno.launcher", "com.itel.launcher"
    )
    val LAUNCHER_PACKAGES = setOf(
        "com.android.launcher", "com.google.android.apps.nexuslauncher",
        "com.miui.home", "com.miui.launcher", "com.huawei.android.launcher",
        "com.hihonor.launcher", "com.vivo.launcher", "com.bbk.launcher",
        "com.oplus.launcher", "com.coloros.launcher", "com.realme.launcher",
        "com.transsion.launcher", "com.infinix.launcher", "com.tecno.launcher",
        "com.itel.launcher"
    )
    val USSD_HINTS = listOf(
        "enter", "ussd", "choose", "select", "option", "menu", "number",
        "amount", "sambaza", "tuma", "reply", "continue", "confirm", "send",
        "weka", "ingiza", "jaza", "kiasi", "namba", "nambari", "simu", "mteja",
        "chagua", "endelea", "thibitisha", "sawa", "kubali", "jibu", "bando",
        "tafadhali", "mpokeaji", "malipo", "nunua", "huduma",
        "bando", "salio", "okoa", "minutes", "dakika", "chagua", "huduma",
        "account", "statement", "airtime", "voucher", "pin"
    )
    val SEND_BUTTON_LABELS = listOf(
        "send", "ok", "tuma", "call", "sambaza", "enda", "confirm",
        "reply", "next", "continue", "submit", "proceed", "accept", "yes", "done",
        "sawa", "kubali", "endelea", "thibitisha",
        "confirmar", "envoyer", "suivant", "continuer", "oui", "go", "enter", "dial",
        "execute", "موافق", "إرسal", "enviar", "valider", "tuma sasa"
    )
    val SEND_VIEW_ID_HINTS = listOf(
        "send", "ok", "confirm", "submit", "proceed", "accept", "done", "next", "continue",
        "button1", "button_ok", "btn_send", "send_btn", "send_button", "coloros_send", "btn_ok",
        "positive", "positive_btn", "positivebutton", "dialog_button", "action_button"
    )
    val INPUT_VIEW_ID_HINTS = listOf(
        "input", "edit", "reply", "ussd_reply", "phone", "number", "amount", "entry", "text", "field", "value", "content", "response"
    )
    val INPUT_FIELD_HINTS = listOf(
        "enter", "input", "reply", "phone", "number", "amount", "weka", "ingiza", "jaza", "kiasi", "namba", "nambari", "simu"
    )
    val PHONE_INPUT_HINTS = listOf(
        "phone", "number", "msisdn", "mobile", "subscriber", "recipient", "customer",
        "beneficiary", "destination", "namba", "nambari", "simu", "mteja", "anayepokea",
        "mpokeaji", "tuma kwa", "weka", "ingiza", "b-number", "target", "account"
    )
    val DISMISS_BUTTON_LABELS = listOf(
        "ok", "close", "dismiss", "cancel", "back", "exit", "ghairi", "funga", "rudi", "hapana"
    )
    val DISMISS_VIEW_ID_HINTS = listOf(
        "ok", "close", "dismiss", "cancel", "back", "exit"
    )
    val NON_USSD_DIALOG_HINTS = listOf(
        "want to access", "allow to make", "permission request", "permission required",
        "draw over other apps", "display over other apps", "turn on battery saver",
        "turn on wifi", "connect to wifi", "turn on bluetooth", "system alert window"
    )
    val TRANSIENT_RESPONSE_HINTS = listOf(
        "ussd running", "ussd code running", "running", "processing", "processed", "being processed",
        "please wait", "wait", "loading", "requesting", "sending request", "sending", "fetching",
        "working", "in progress", "tafadhali subiri", "subiri kidogo", "inaendelea", "inafanyiwa kazi",
        "inachakatwa", "inashughulikiwa", "ombi lako linashughulikiwa", "inaomba", "ombi linaendelea"
    )
    val EDITABLE_CLASS_HINTS = listOf(
        "EditText", "TextInputEditText", "AutoCompleteTextView",
        "MultiAutoCompleteTextView", "ExtractEditText",
        "com.samsung.android.widget.SamsungEditText",
        "com.samsung.android.widget.SemEditText",
        "SemEditText", "SemAutoCompleteTextView",
        "android.widget.EditText"
    )
    val errorKeywords = listOf(
        "connection problem", "invalid mmi", "error", "failed", "try again",
        "unavailable", "not registered", "maintenance",
        "imekataa", "haijafanikiwa", "haikufaulu", "jaribu tena", "si sahihi",
        "haipatikani", "umeshindwa", "ombi halijafanikiwa", "namba si sahihi",
        "huduma haipatikani"
    )

    val COMMON_USSD_INPUT_VIEW_IDS = listOf(
        "android:id/input",
        "com.android.phone:id/input",
        "com.android.phone:id/dialog_message",
        "com.android.phone:id/dialog_input",
        "com.android.phone:id/ussd_input",
        "com.sh.smart.caller:id/input",
        "com.transsion.smartcaller:id/input",
        "com.samsung.android.app.telephonyui:id/input",
        "com.sec.android.app.popupuireceiver:id/input",
        "com.miui.phone:id/input",
        "com.google.android.dialer:id/input",
        "com.android.dialer:id/input",
        "com.coloros.telephony:id/input",
        "com.oplus.telephony:id/input",
        "com.vivo.dialer:id/input",
        "com.huawei.android.dialer:id/input",
        "com.hihonor.dialer:id/input",
        "com.android.phone:id/dialog_edit_text",
        "android:id/edit",
        "android:id/message"
    )

    val COMMON_USSD_SEND_VIEW_IDS = listOf(
        "android:id/button1",
        "com.android.phone:id/button1",
        "com.sh.smart.caller:id/button1",
        "com.transsion.smartcaller:id/button1",
        "com.samsung.android.app.telephonyui:id/button1",
        "com.sec.android.app.popupuireceiver:id/button1",
        "com.google.android.dialer:id/button1",
        "com.android.dialer:id/button1",
        "com.miui.phone:id/button1",
        "com.coloros.telephony:id/button1",
        "com.oplus.telephony:id/button1",
        "com.vivo.dialer:id/button1",
        "com.huawei.android.dialer:id/button1",
        "com.hihonor.dialer:id/button1",
        "android:id/send",
        "com.android.phone:id/send"
    )

    val SYSTEM_UI_NON_DIALOG_VIEW_ID_HINTS = listOf(
        "notification", "heads_up", "status_bar", "statusbar", "quick_settings", "qs_",
        "shade", "shelf", "keyguard", "lockscreen", "volume", "power", "pip",
        "navigation_bar", "navbar", "carrier_label", "expanded_status_bar", "scroller",
        "row", "notification_stack", "header", "actions"
    )

    val SYSTEM_UI_NON_DIALOG_CLASS_HINTS = listOf(
        "Notification", "StatusBar", "Keyguard", "Lockscreen", "Shelf", "Shade",
        "HeadsUp", "Volume", "NavigationBar", "Pip"
    )

    val SYSTEM_UI_NON_DIALOG_WINDOW_TITLES = listOf(
        "notificationshade", "statusbar", "volume", "navigationbar", "headsup",
        "notification", "quicksettings", "shade", "screenshot", "edge panel",
        "smart panel", "assist", "floating", "panel"
    )
}
