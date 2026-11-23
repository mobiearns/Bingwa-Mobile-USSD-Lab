package com.bingwa.mobile

import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Writes one response to the input field selected from the currently validated USSD popup.
 * Submission is permitted only after exact read-back from that same field.
 */
internal class UssdNavigationValueWriter(private val service: UssdNavigationService) {
    companion object {
        private const val TAG = "UssdNavValueWriter"
    }

    enum class WriteResult {
        VERIFIED,
        ACCEPTED_AWAITING_VERIFY,
        REJECTED
    }

    fun writeValueToField(root: AccessibilityNodeInfo, value: String): Boolean {
        val field = service.findInputField(root) ?: return false
        return try {
            writeValueToNode(field, value)
        } finally {
            runCatching { field.recycle() }
        }
    }

    fun writeValueToNode(field: AccessibilityNodeInfo, value: String): Boolean {
        return writeValueResult(field, value) == WriteResult.VERIFIED
    }

    fun writeValueResult(field: AccessibilityNodeInfo, value: String): WriteResult {
        if (value.isBlank()) return WriteResult.REJECTED
        val inputStartedAt = UssdPerformanceDiagnostics.mark()

        val initialReadStartedAt = UssdPerformanceDiagnostics.mark()
        val initialText = runCatching { field.text?.toString() }.getOrNull()
        UssdPerformanceDiagnostics.record("input.initial_read", initialReadStartedAt, "available=${initialText != null}")
        if (service.isVerifiedFieldValue(initialText, value)) {
            service.rememberVerifiedInput(value)
            service.rememberProgress()
            UssdPerformanceDiagnostics.record("input.entry", inputStartedAt, "valueLength=${value.length} alreadyPresent=true verified=true")
            return WriteResult.VERIFIED
        }

        // A carrier dialer can accept ACTION_SET_TEXT and then clear/recreate its
        // EditText while moving between prompts. Retry only when the same validated
        // step's field is observably empty, after a short grace period, and within
        // a strict attempt limit. Never overwrite a different non-empty response.
        if (service.hasInputWriteAttemptForCurrentStep(value) &&
            !service.canRetryInputWriteForCurrentStep(value, initialText)
        ) {
            Log.w(TAG, "writeValueToNode: awaiting field refresh or safe retry")
            return WriteResult.REJECTED
        }

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }

        val setTextStartedAt = UssdPerformanceDiagnostics.mark()

        // Primary direct ACTION_SET_TEXT attempt (fast-path: 1 Binder IPC, ~12-14ms).
        // On Android 12 (SDK 31) and Infinix X669, ACTION_SET_TEXT sets the text directly
        // via accessibility without requiring the field to be pre-focused or clicked.
        // Attempting ACTION_SET_TEXT first avoids summoning the soft keyboard (IME)
        // and eliminates dialer UI-thread layout contention during the subsequent node refresh.
        val primarySetTextAccepted = runCatching {
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }.getOrElse {
            Log.w(TAG, "ACTION_SET_TEXT failed for current popup field", it)
            false
        }
        var accepted = primarySetTextAccepted
        var focusFallbackUsed = false
        var focusActionAccepted = false

        // Secondary fallback for OEM dialers that strictly require touch/focus activation before accepting text
        if (!accepted) {
            focusFallbackUsed = true
            val focusFallbackStartedAt = UssdPerformanceDiagnostics.mark()
            val focusAccepted = runCatching {
                field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }.getOrDefault(false)
            focusActionAccepted = focusAccepted
            if (!focusAccepted) {
                runCatching { field.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            }
            accepted = runCatching {
                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }.getOrDefault(false)
            if (!accepted) {
                // Tertiary fallback: paste via clipboard for custom/older OEM dialers where ACTION_SET_TEXT is restricted
                val pasteAccepted = runCatching {
                    val clipboard = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    if (clipboard != null) {
                        val clip = android.content.ClipData.newPlainText("ussd_val", value)
                        clipboard.setPrimaryClip(clip)
                        field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    } else false
                }.getOrDefault(false)
                if (pasteAccepted) accepted = true
            }
            UssdPerformanceDiagnostics.record(
                "keyboard.focus_fallback",
                focusFallbackStartedAt,
                "focusAccepted=$focusActionAccepted clickFallback=true accepted=$accepted"
            )
        }

        UssdPerformanceDiagnostics.record(
            "input.set_text_action",
            setTextStartedAt,
            "primaryAccepted=$primarySetTextAccepted accepted=$accepted focusFallback=$focusFallbackUsed"
        )

        // Read back even when the OEM reports false: some carrier dialogs apply the
        // text but return an unreliable action result. Exact field contents are decisive.
        // Record every ACTION_SET_TEXT attempt, including OEM-reported rejection.
        service.rememberInputWrite(value)

        val directReadStartedAt = UssdPerformanceDiagnostics.mark()
        val directText = runCatching { field.text?.toString() }.getOrNull()
        UssdPerformanceDiagnostics.record(
            "input.direct_readback",
            directReadStartedAt,
            "available=${directText != null}"
        )
        if (service.isVerifiedFieldValue(directText, value)) {
            service.rememberVerifiedInput(value)
            service.rememberProgress()
            UssdPerformanceDiagnostics.record("input.entry", inputStartedAt, "valueLength=${value.length} directRead=true verified=true")
            return WriteResult.VERIFIED
        }

        // Bounded fast read-back verification:
        // When ACTION_SET_TEXT is accepted, the dialer's EditText applies text within 1-2ms.
        // Rather than prematurely deferring to an event cycle or repeating futile Binder calls:
        // 1. Skip refresh entirely if ACTION_SET_TEXT was rejected by the OS (avoids futile 15ms Binder IPC).
        // 2. Bound to a single refresh pass when accepted: verify immediately if updated, otherwise await
        //    the asynchronous accessibility event without blocking the thread in a second 15ms Binder pass.
        if (accepted) {
            val refreshStartedAt = UssdPerformanceDiagnostics.mark()
            val refreshAccepted = runCatching { field.refresh() }.getOrDefault(false)
            UssdPerformanceDiagnostics.record("input.node_refresh", refreshStartedAt, "pass=1 accepted=$refreshAccepted")
            if (refreshAccepted) {
                val refreshedText = runCatching { field.text?.toString() }.getOrNull()
                    ?: service.readFieldTextFast(field)
                if (service.isVerifiedFieldValue(refreshedText, value)) {
                    service.rememberVerifiedInput(value)
                    service.rememberProgress()
                    UssdPerformanceDiagnostics.record(
                        "input.entry",
                        inputStartedAt,
                        "valueLength=${value.length} pass=1 refreshedRead=true verified=true"
                    )
                    return WriteResult.VERIFIED
                }
            }

            Log.d(TAG, "ACTION_SET_TEXT accepted; awaiting asynchronous verification via accessibility event")
            UssdPerformanceDiagnostics.record("input.entry", inputStartedAt, "valueLength=${value.length} accepted=true awaitingVerify=true")
            return WriteResult.ACCEPTED_AWAITING_VERIFY
        }

        Log.w(TAG, "ACTION_SET_TEXT rejected and value not present; awaiting bounded safe retry")
        UssdPerformanceDiagnostics.record("input.entry", inputStartedAt, "valueLength=${value.length} accepted=false verified=false")
        return WriteResult.REJECTED
    }
}
