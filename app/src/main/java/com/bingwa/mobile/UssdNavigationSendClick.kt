package com.bingwa.mobile

import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicLong

/**
 * Fast, single-pass Send discovery for carrier USSD dialogs.
 *
 * The old implementation queried dozens of unrelated OEM resource IDs and then walked
 * the same accessibility tree several more times (including three independent scoring
 * walks). On Transsion/Infinix dialers that work happened synchronously on the service
 * thread and could be repeated again by the pending-send state machine.
 */
internal class UssdNavigationSendClick(private val service: UssdNavigationService) {
    private val attemptSequence = AtomicLong(0L)
    companion object {
        private const val TAG = "UssdNavSendClick"

        private val SEND_LABELS = listOf(
            "send", "ok", "tuma", "call", "sambaza", "enda", "confirm",
            "reply", "next", "continue", "submit", "proceed", "accept", "yes", "done",
            "sawa", "kubali", "endelea", "thibitisha",
            "confirmar", "envoyer", "suivant", "continuer", "oui", "go", "enter", "dial",
            "execute", "موافق", "إرسال"
        )
        private val CANCEL_LABELS = listOf(
            "cancel", "back", "dismiss", "close", "exit", "no", "annuler", "fermer",
            "non", "ghairi", "funga", "rudi", "hapana", "إلغاء", "خروج"
        )
    }

    private fun validateFieldBeforeSend(
        root: AccessibilityNodeInfo,
        field: AccessibilityNodeInfo?,
        expectedValue: String
    ): Boolean {
        if (field == null) return false

        val inSameWindow = field.windowId == root.windowId && field.windowId != -1
        var foundInRoot = inSameWindow
        if (!foundInRoot && field.windowId <= 0) {
            // Only walk parents if windowId was unassigned by the accessibility framework
            var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(field)
            var depth = 0
            while (current != null && depth < 4) {
                val active = current
                if (active == root || (active.windowId == root.windowId && active.windowId != -1)) {
                    foundInRoot = true
                    active.recycle()
                    current = null
                    break
                }
                val parent = runCatching { active.parent }.getOrNull()
                active.recycle()
                current = parent
                depth++
            }
            runCatching { current?.recycle() }
        }
        if (!foundInRoot) {
            Log.w(TAG, "validateFieldBeforeSend: field is outside the active popup (fieldWin=${field.windowId}, rootWin=${root.windowId})")
            return false
        }

        val pkg = root.packageName?.toString().orEmpty()
        val isUssdPkg = service.isPotentialUssdPackage(pkg) || pkg == "android" || pkg.isBlank()
        if (!isUssdPkg) {
            val lower = service.activePopupSnapshot?.dialogText?.lowercase()
                ?: service.extractDialogTextFromRoot(root).lowercase()
            if (!service.looksLikeUssdDialogFast(allTextLower = lower, windowPackageName = pkg)) {
                Log.w(TAG, "validateFieldBeforeSend: popup failed USSD validation")
                return false
            }
        }

        val hasFresh = service.hasFreshlyVerifiedInput(expectedValue)
        if (!hasFresh) {
            val fieldText = (service.readFieldTextFast(field) ?: runCatching { field.text?.toString() }.getOrNull()).orEmpty().trim()
            if (!service.isVerifiedFieldValue(fieldText, expectedValue)) {
                Log.w(TAG, "validateFieldBeforeSend: field read-back did not match expected value")
                return false
            }
        }
        service.rememberVerifiedInput(expectedValue)
        return true
    }

    internal var lastSuccessfulPath: String = ""
        private set

    fun clickSendButton(root: AccessibilityNodeInfo, preferredPath: String = ""): Boolean {
        val attemptId = attemptSequence.incrementAndGet()
        val startedAt = UssdPerformanceDiagnostics.mark()
        lastSuccessfulPath = ""
        var treeAlreadyTried = false

        // A previously confirmed tree-scan winner can be tried before cached IDs.
        if (preferredPath == "tree_scan") {
            val treeStartedAt = UssdPerformanceDiagnostics.mark()
            val treeAccepted = tryBestSendCandidate(root)
            treeAlreadyTried = true
            UssdPerformanceDiagnostics.record("send.tree_scan", treeStartedAt, "attempt=$attemptId preferred=true accepted=$treeAccepted")
            if (treeAccepted) {
                lastSuccessfulPath = "tree_scan"
                UssdPerformanceDiagnostics.record("send.discovery", startedAt, "attempt=$attemptId path=tree_scan accepted=true")
                UssdPerformanceDiagnostics.markSubmissionAccepted("tree_scan")
                return true
            }
        }

        val idStartedAt = UssdPerformanceDiagnostics.mark()
        if (tryCachedSendButtonId(root)) {
            lastSuccessfulPath = "resource_id"
            UssdPerformanceDiagnostics.record("send.resource_id", idStartedAt, "attempt=$attemptId accepted=true")
            UssdPerformanceDiagnostics.record("send.discovery", startedAt, "attempt=$attemptId path=resource_id accepted=true")
            UssdPerformanceDiagnostics.markSubmissionAccepted("resource_id")
            return true
        }
        UssdPerformanceDiagnostics.record("send.resource_id", idStartedAt, "attempt=$attemptId accepted=false")

        if (!treeAlreadyTried) {
            val treeStartedAt = UssdPerformanceDiagnostics.mark()
            val accepted = tryBestSendCandidate(root)
            UssdPerformanceDiagnostics.record("send.tree_scan", treeStartedAt, "attempt=$attemptId accepted=$accepted")
            if (accepted) {
                lastSuccessfulPath = "tree_scan"
                UssdPerformanceDiagnostics.record("send.discovery", startedAt, "attempt=$attemptId path=tree_scan accepted=true")
                UssdPerformanceDiagnostics.markSubmissionAccepted("tree_scan")
                return true
            }
        }

        UssdPerformanceDiagnostics.record("send.discovery", startedAt, "attempt=$attemptId path=none accepted=false")
        Log.w(TAG, "clickSendButton: no safe Send action found in the current popup (attempt=$attemptId)")
        return false
    }

    fun validateAndClickSend(
        root: AccessibilityNodeInfo,
        field: AccessibilityNodeInfo?,
        expectedValue: String,
        preferredPath: String = ""
    ): Boolean {
        if (!validateFieldBeforeSend(root, field, expectedValue)) return false
        return clickSendButton(root, preferredPath)
    }

    private fun tryCachedSendButtonId(root: AccessibilityNodeInfo): Boolean {
        val cached = service.cachedSendButtonId
        if (!cached.isNullOrBlank()) {
            if (trySendButtonWithId(root, cached)) return true
        }
        val pkg = root.packageName?.toString().orEmpty()
        if (cached != "android:id/button1" && trySendButtonWithId(root, "android:id/button1")) return true
        if (pkg.isNotBlank()) {
            val p1 = "$pkg:id/button1"
            if (cached != p1 && trySendButtonWithId(root, p1)) return true
            val p2 = "$pkg:id/btn_send"
            if (cached != p2 && trySendButtonWithId(root, p2)) return true
            val p3 = "$pkg:id/send"
            if (cached != p3 && trySendButtonWithId(root, p3)) return true
            val p4 = "$pkg:id/button_ok"
            if (cached != p4 && trySendButtonWithId(root, p4)) return true
        }
        for (commonId in UssdNavigationConfig.COMMON_USSD_SEND_VIEW_IDS) {
            if (commonId != cached && commonId != "android:id/button1" && trySendButtonWithId(root, commonId)) {
                return true
            }
        }
        return false
    }

    private fun trySendButtonWithId(root: AccessibilityNodeInfo, id: String): Boolean {
        val matches = runCatching {
            root.findAccessibilityNodeInfosByViewId(id)
        }.getOrNull() ?: return false
        try {
            val node = matches.firstOrNull { candidate ->
                runCatching { candidate.isEnabled && candidate.isVisibleToUser }.getOrDefault(false)
            } ?: return false
            val label = service.normalizeActionLabel(node.text?.toString())
            val desc = service.normalizeActionLabel(node.contentDescription?.toString())
            val idLabel = id.substringAfterLast(":id/").lowercase()
            val isCancel = CANCEL_LABELS.any { label == it || desc == it } ||
                UssdNavigationConfig.DISMISS_VIEW_ID_HINTS.any { idLabel.contains(it) }
            if (isCancel) return false

            if (executeReliableClick(node)) {
                service.cachedSendButtonId = id
                Log.d(TAG, "Send activated through resource ID $id")
                return true
            }
        } finally {
            matches.forEach { runCatching { it.recycle() } }
        }
        return false
    }

    private fun tryBestSendCandidate(root: AccessibilityNodeInfo): Boolean {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        service.collectActionCandidates(root, candidates)
        try {
            val rootBounds = Rect()
            runCatching { root.getBoundsInScreen(rootBounds) }
            val ranked = candidates.mapNotNull { node ->
                scoreSendCandidate(node, rootBounds)?.let { score -> node to score }
            }.sortedByDescending { it.second }

            // At most three candidates are attempted, and each is already in the one-pass
            // collection. A candidate click itself tries its clickable ancestor before a tap.
            for ((node, _) in ranked.take(3)) {
                if (executeReliableClick(node)) {
                    runCatching { node.viewIdResourceName }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { service.cachedSendButtonId = it }
                    Log.d(TAG, "Send activated through ranked accessibility candidate")
                    return true
                }
            }
            return false
        } finally {
            candidates.forEach { runCatching { it.recycle() } }
        }
    }

    private fun scoreSendCandidate(node: AccessibilityNodeInfo, rootBounds: Rect): Int? {
        val enabled = runCatching { node.isEnabled }.getOrDefault(true)
        val visible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            runCatching { node.isVisibleToUser }.getOrDefault(true)
        } else true
        if (!enabled || !visible) return null

        val label = service.normalizeActionLabel(node.text?.toString())
        val desc = service.normalizeActionLabel(node.contentDescription?.toString())
        val viewId = service.normalizeActionLabel(runCatching { node.viewIdResourceName }.getOrNull())
        if (CANCEL_LABELS.any { label == it || desc == it } ||
            UssdNavigationConfig.DISMISS_VIEW_ID_HINTS.any { viewId.contains(it) }
        ) return null

        val className = node.className?.toString().orEmpty()
        val buttonLike = className.contains("Button", ignoreCase = true) ||
            className.contains("ImageButton", ignoreCase = true)
        val exactLabel = SEND_LABELS.any { label == it || desc == it }
        val prefixLabel = SEND_LABELS.any { label.startsWith(it) || desc.startsWith(it) }
        val idMatch = UssdNavigationConfig.SEND_VIEW_ID_HINTS.any { viewId.contains(it) }

        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        val nearBottom = rootBounds.height() > 0 &&
            bounds.bottom >= rootBounds.top + (rootBounds.height() * 0.55f)
        val shortCaption = (label.ifBlank { desc }).length in 1..24

        // Never treat an arbitrary clickable menu row as Send. Unknown controls qualify
        // only when they are button-class controls in the lower action-bar region.
        if (!exactLabel && !prefixLabel && !idMatch && !(buttonLike && nearBottom && shortCaption)) {
            return null
        }

        var score = 0
        if (exactLabel) score += 1200
        else if (prefixLabel) score += 950
        if (idMatch) score += 900
        if (buttonLike) score += 240
        if (nearBottom) score += 100
        if (shortCaption) score += 40
        if (runCatching { node.isClickable }.getOrDefault(false)) score += 30
        return score
    }

    private fun executeReliableClick(node: AccessibilityNodeInfo): Boolean {
        if (runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
        ) return true

        // On many OEM dialers (Transsion/Samsung/Xiaomi), action text is nested in a clickable container.
        // Checking clickable ancestors first executes in <0.5ms directly via Binder without gesture delays.
        var current: AccessibilityNodeInfo? = runCatching { node.parent }.getOrNull()
        var depth = 0
        while (current != null && depth < 3) {
            val p = current
            if (runCatching { p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
                p.recycle()
                return true
            }
            val nextParent = runCatching { p.parent }.getOrNull()
            p.recycle()
            current = nextParent
            depth++
        }
        runCatching { current?.recycle() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && dispatchTapGesture(node)) {
            return true
        }

        return false
    }

    private fun dispatchTapGesture(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        if (!runCatching { node.getBoundsInScreen(bounds) }.isSuccess) return false
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        if (x <= 0f || y <= 0f) return false

        val path = android.graphics.Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(
            path,
            0L,
            UssdNavigationConfig.TAP_GESTURE_DURATION_MS
        )
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        return runCatching { service.dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }
}
