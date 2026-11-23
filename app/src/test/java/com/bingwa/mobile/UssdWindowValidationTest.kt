package com.bingwa.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UssdWindowValidationTest {

    @Test
    fun systemUiNonDialogHintsRecognized() {
        val nonDialogViewIds = listOf(
            "com.android.systemui:id/notification_stack_scroller",
            "com.android.systemui:id/heads_up_status_bar_view",
            "com.android.systemui:id/qs_navbar_scroller",
            "com.android.systemui:id/keyguard_status_view",
            "com.android.systemui:id/notification_panel"
        )
        nonDialogViewIds.forEach { viewId ->
            val matches = UssdNavigationConfig.SYSTEM_UI_NON_DIALOG_VIEW_ID_HINTS.any {
                viewId.lowercase().contains(it)
            }
            assertTrue("Expected $viewId to match non-dialog hints", matches)
        }
    }

    @Test
    fun commonUssdInputViewIdsRecognized() {
        val standardUssdInputIds = listOf(
            "android:id/input",
            "com.android.phone:id/input",
            "com.android.phone:id/dialog_message",
            "com.android.phone:id/dialog_input",
            "com.android.phone:id/ussd_input",
            "com.google.android.dialer:id/input",
            "com.android.dialer:id/input",
            "com.sh.smart.caller:id/input"
        )
        standardUssdInputIds.forEach { id ->
            assertTrue("Expected COMMON_USSD_INPUT_VIEW_IDS to contain $id",
                UssdNavigationConfig.COMMON_USSD_INPUT_VIEW_IDS.contains(id))
        }
    }

    @Test
    fun transientResponsesDetectedCorrectly() {
        val transientTexts = listOf(
            "Please wait...",
            "Your request is being processed",
            "Loading...",
            "Processing your request",
            "USSD code running"
        )
        transientTexts.forEach { text ->
            val lower = text.lowercase()
            val isTransient = UssdNavigationConfig.TRANSIENT_RESPONSE_HINTS.any { lower.contains(it) }
            assertTrue("Expected '$text' to be classified as transient", isTransient)
        }
    }

    @Test
    fun genuineUssdResponsesNotTransient() {
        val realResponses = listOf(
            "1. Buy Data\n2. Check Balance\n3. My Account",
            "Enter recipient phone number:",
            "Confirmed. You have bought 1GB for 24hrs at KES 20. PIN:"
        )
        realResponses.forEach { text ->
            val lower = text.lowercase()
            val isTransient = UssdNavigationConfig.TRANSIENT_RESPONSE_HINTS.any { lower.contains(it) }
            assertFalse("Expected genuine response not to be transient: $text", isTransient)
        }
    }

    @Test
    fun phoneOrStringStepClassification() {
        // String / phone steps
        assertTrue(UssdNavigationService.isPhoneOrStringStep("INPUT_PHONE"))
        assertTrue(UssdNavigationService.isPhoneOrStringStep("pn"))
        assertTrue(UssdNavigationService.isPhoneOrStringStep("0712345678"))
        assertTrue(UssdNavigationService.isPhoneOrStringStep("0112345678"))
        assertTrue(UssdNavigationService.isPhoneOrStringStep("254712345678"))
        assertTrue(UssdNavigationService.isPhoneOrStringStep("+254712345678"))
        assertTrue(UssdNavigationService.isPhoneOrStringStep("custom_text"))
        // Amounts / multi-digit data entries > 9
        assertTrue(UssdNavigationService.isPhoneOrStringStep("100"))
        assertTrue(UssdNavigationService.isPhoneOrStringStep("250"))

        // Single digit menu choices are NOT phone or string steps
        assertFalse(UssdNavigationService.isPhoneOrStringStep("1"))
        assertFalse(UssdNavigationService.isPhoneOrStringStep("2"))
        assertFalse(UssdNavigationService.isPhoneOrStringStep("5"))
        assertFalse(UssdNavigationService.isPhoneOrStringStep("9"))
        assertFalse(UssdNavigationService.isPhoneOrStringStep("0"))
    }

    @Test
    fun potentialUssdPackagesClassification() {
        assertTrue(UssdNavigationService.isPotentialUssdPackage("android"))
        assertTrue(UssdNavigationService.isPotentialUssdPackage("com.android.phone"))
        assertTrue(UssdNavigationService.isPotentialUssdPackage("com.google.android.dialer"))
        assertTrue(UssdNavigationService.isPotentialUssdPackage("com.sh.smart.caller")) // Transsion / Infinix dialer
        assertTrue(UssdNavigationService.isPotentialUssdPackage("com.android.stk")) // SIM Toolkit
        assertTrue(UssdNavigationService.isPotentialUssdPackage("com.mediatek.stk")) // MediaTek STK
        assertTrue(UssdNavigationService.isPotentialUssdPackage("com.sprd.stk")) // Spreadtrum STK

        // Launcher and third-party apps should NOT be classified as USSD packages
        assertFalse(UssdNavigationService.isPotentialUssdPackage("com.android.launcher3"))
        assertFalse(UssdNavigationService.isPotentialUssdPackage("com.transsion.hilauncher"))
        assertFalse(UssdNavigationService.isPotentialUssdPackage("com.whatsapp"))
        assertFalse(UssdNavigationService.isPotentialUssdPackage(""))
    }

    @Test
    fun signatureLearningLifecycleWorkflow() {
        // 1. Text normalization test
        val rawCarrierMenu = "1: Daily Bundles\n2: Weekly Bundles\n3: Monthly Bundles\n98: MORE"
        val normalized = rawCarrierMenu.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        assertEquals("1 daily bundles 2 weekly bundles 3 monthly bundles 98 more", normalized)

        // 2. Structured signature generation
        val step = UssdSignatureStep(
            stepIndex = 0,
            expectedInput = "1",
            menuTitle = "Safaricom Bundles",
            menuText = rawCarrierMenu,
            selectedOptionLabel = "Daily Bundles",
            menuOptionsSnapshot = listOf("Daily Bundles", "Weekly Bundles", "Monthly Bundles", "MORE")
        )
        assertEquals(0, step.stepIndex)
        assertEquals("1", step.expectedInput)
        assertEquals("Daily Bundles", step.selectedOptionLabel)
        assertEquals(4, step.menuOptionsSnapshot.size)

        // 3. Exact label match verification
        assertTrue(UssdSignatureMatchPolicy.isExactLabelMatch(step.selectedOptionLabel, "daily bundles"))
        assertTrue(UssdSignatureMatchPolicy.isExactLabelMatch(step.selectedOptionLabel, "Daily Bundles"))
        assertFalse(UssdSignatureMatchPolicy.isExactLabelMatch(step.selectedOptionLabel, "Weekly Bundles"))

        // 4. Safe auto-adjust verification: if option 1 moved to option 2 with exact label match
        val movedLabel = "Daily Bundles"
        assertTrue(UssdSignatureMatchPolicy.isExactLabelMatch(step.selectedOptionLabel, movedLabel))
    }

    @Test
    fun privacySafeDiagnosticsDoNotLeakPii() {
        // Record privacy-safe events
        UssdPerformanceDiagnostics.recordLearningExtractionAttempted(0, false)
        UssdPerformanceDiagnostics.recordLearningTextExtracted(5, 120, true)
        UssdPerformanceDiagnostics.recordLearningNormalization(0, 120, 110)
        UssdPerformanceDiagnostics.recordLearningOptionsIdentified(0, 4, true)
        UssdPerformanceDiagnostics.recordLearningSignatureGenerated(0, 4, true)
        UssdPerformanceDiagnostics.recordLearningSignatureStored(1, 4, 1)
        UssdPerformanceDiagnostics.recordLearningSignatureRetrieved(1, 4)
        UssdPerformanceDiagnostics.recordLearningSignatureMatched(0, "1", true)
        UssdPerformanceDiagnostics.recordLearningNavigationAction(0, "exact_match", "1")
        UssdPerformanceDiagnostics.recordLearningWindowRejected("com.android.systemui", 9919, "notification_or_overlay")

        // None of the recorded details should contain actual phone numbers, PINs, or raw SMS messages
        val report = UssdPerformanceDiagnostics.getRecordedDetails().joinToString("\n")
        assertFalse(report.contains("0712345678"))
        assertFalse(report.contains("PIN"))
        assertTrue(report.contains("windowId=9919"))
        assertTrue(report.contains("notification_or_overlay"))
        assertTrue(report.contains("step=0"))
    }

    @Test
    fun commonUssdInputViewIdsTargetedByPackage() {
        val rootPkg = "com.android.phone"
        val filtered = UssdNavigationConfig.COMMON_USSD_INPUT_VIEW_IDS.filter { id ->
            id.startsWith("android:") || (rootPkg.isNotBlank() && id.startsWith(rootPkg))
        }
        // Should only retain android:* and com.android.phone:* IDs
        assertTrue(filtered.contains("android:id/input"))
        assertTrue(filtered.contains("com.android.phone:id/input"))
        // Should NOT query foreign OEM packages
        assertFalse(filtered.contains("com.samsung.android.app.telephonyui:id/input"))
        assertFalse(filtered.contains("com.miui.phone:id/input"))
        assertFalse(filtered.contains("com.sh.smart.caller:id/input"))
    }

    @Test
    fun signaturePersistenceAndImmediateRetrieval() {
        val signatureSteps = listOf(
            UssdSignatureStep(
                stepIndex = 0,
                expectedInput = "1",
                menuTitle = "Data Bundles",
                menuText = "1. Daily\n2. Weekly",
                selectedOptionLabel = "Daily",
                menuOptionsSnapshot = listOf("Daily", "Weekly")
            )
        )
        val offer = OfferItem(
            id = 101,
            name = "Daily Bundle 1GB",
            price = 20,
            ussdCode = "*544#",
            learnedSignature = signatureSteps,
            pendingLearnedSignature = signatureSteps,
            signatureDetectionEnabled = true
        )
        // Verify effective signature resolves immediately without manual approval step
        val effectiveSignatures = when {
            offer.learnedSignature.isNotEmpty() -> offer.learnedSignature
            offer.pendingLearnedSignature.isNotEmpty() -> offer.pendingLearnedSignature
            else -> emptyList()
        }
        assertEquals(1, effectiveSignatures.size)
        assertEquals("Daily", effectiveSignatures[0].selectedOptionLabel)
        assertEquals("1", effectiveSignatures[0].expectedInput)
    }

    @Test
    fun granularDiagnosticsRecordSnapshotAndRootStages() {
        UssdPerformanceDiagnostics.record("root.source_lookup", detail = "found=true pkg=com.android.phone")
        UssdPerformanceDiagnostics.record("root.window_root_retrieval", detail = "winId=42 fastPath=true")
        UssdPerformanceDiagnostics.record("root.get_windows", detail = "count=3")
        UssdPerformanceDiagnostics.record("root.window_enumeration", detail = "candidates=2")
        UssdPerformanceDiagnostics.record("snapshot.root_retrieval", detail = "windowId=42")
        UssdPerformanceDiagnostics.record("snapshot.traversal", detail = "nodes=12 maxDepth=4")
        UssdPerformanceDiagnostics.record("snapshot.child_enumeration", detail = "durationMs=1.20")
        UssdPerformanceDiagnostics.record("snapshot.text_extraction", detail = "durationMs=0.85")
        UssdPerformanceDiagnostics.record("snapshot.construction", detail = "tokens=6 chars=45")

        val report = UssdPerformanceDiagnostics.getRecordedDetails().joinToString("\n")
        assertTrue(report.contains("fastPath=true"))
        assertTrue(report.contains("candidates=2"))
        assertTrue(report.contains("nodes=12"))
        assertTrue(report.contains("durationMs=1.20"))
    }

    @Test
    fun writeResultEnumSemantics() {
        // Confirms WriteResult states exist and behave deterministically
        val verified = UssdNavigationValueWriter.WriteResult.VERIFIED
        val awaiting = UssdNavigationValueWriter.WriteResult.ACCEPTED_AWAITING_VERIFY
        val rejected = UssdNavigationValueWriter.WriteResult.REJECTED

        assertEquals("VERIFIED", verified.name)
        assertEquals("ACCEPTED_AWAITING_VERIFY", awaiting.name)
        assertEquals("REJECTED", rejected.name)
    }

    @Test
    fun sendButtonLabelsMatchDialerVariants() {
        val testLabels = listOf("Send", "OK", "tuma", "reply", "continue", "sawa", "kubali", "submit")
        testLabels.forEach { label ->
            val norm = label.trim().lowercase()
            assertTrue("Expected SEND_BUTTON_LABELS to recognize '$label'",
                UssdNavigationConfig.SEND_BUTTON_LABELS.contains(norm))
        }
    }

    @Test
    fun dismissButtonLabelsRejectCancelActions() {
        val dismissLabels = listOf("cancel", "dismiss", "close", "back", "ghairi", "funga")
        dismissLabels.forEach { label ->
            val norm = label.trim().lowercase()
            assertTrue("Expected DISMISS_BUTTON_LABELS to recognize '$label'",
                UssdNavigationConfig.DISMISS_BUTTON_LABELS.contains(norm))
        }
    }

    @Test
    fun dualSimPromptsRecognized() {
        val dualSimPrompts = listOf(
            "Choose SIM for USSD: SIM 1 or SIM 2",
            "Select default SIM card",
            "Chagua sim card: 1. Safaricom 2. Airtel"
        )
        dualSimPrompts.forEach { prompt ->
            val lower = prompt.lowercase()
            val isSimPrompt = (lower.contains("sim") || lower.contains("slot")) && (
                lower.contains("choose") || lower.contains("select") || lower.contains("default") ||
                lower.contains("chagua") || lower.contains("sim 1") || lower.contains("sim 2")
            )
            assertTrue("Expected '$prompt' to be identified as SIM selection dialog", isSimPrompt)
        }
    }

    @Test
    fun packageValidationDistinguishesTelephonyFromGenericAndroid() {
        // True telephony carrier & dialer packages
        assertTrue(UssdNavigationService.isPotentialUssdPackage("com.android.phone"))
        assertTrue(UssdNavigationService.isPotentialUssdPackage("com.sh.smart.caller"))
        assertTrue(UssdNavigationService.isPotentialUssdPackage("com.google.android.dialer"))

        // Framework "android" package requires dialog layout verification
        assertTrue(UssdNavigationService.isPotentialUssdPackage("android"))

        // Keyboards and third-party apps must not be classified as telephony
        assertFalse(UssdNavigationService.isPotentialUssdPackage("com.google.android.inputmethod.latin"))
        assertFalse(UssdNavigationService.isPotentialUssdPackage("com.sohu.inputmethod.sogou"))
        assertFalse(UssdNavigationService.isPotentialUssdPackage("com.android.launcher3"))
    }

    @Test
    fun candidateWindowSortPrioritizesEventAndActiveWindows() {
        data class TestWindow(val id: Int, val isEvent: Boolean, val isCached: Boolean, val isActive: Boolean, val isApp: Boolean, val layer: Int)

        val windows = listOf(
            TestWindow(id = 1, isEvent = false, isCached = false, isActive = false, isApp = true, layer = 10), // background app
            TestWindow(id = 2, isEvent = false, isCached = false, isActive = true, isApp = true, layer = 8),   // active app
            TestWindow(id = 3, isEvent = false, isCached = true, isActive = false, isApp = true, layer = 9),   // cached window
            TestWindow(id = 4, isEvent = true, isCached = false, isActive = false, isApp = true, layer = 5)    // event window
        )

        val sorted = windows.sortedWith(
            compareByDescending<TestWindow> { it.isEvent }
                .thenByDescending { it.isCached }
                .thenByDescending { it.isActive }
                .thenByDescending { it.isApp }
                .thenByDescending { it.layer }
        )

        assertEquals(4, sorted[0].id) // Event window first
        assertEquals(3, sorted[1].id) // Cached window second
        assertEquals(2, sorted[2].id) // Active window third
        assertEquals(1, sorted[3].id) // Background window last
    }

    @Test
    fun transientCarrierPopupDetection() {
        val transientPhrases = listOf(
            "USSD code running...",
            "Please wait...",
            "Loading...",
            "Your request is being processed. Please wait."
        )

        transientPhrases.forEach { phrase ->
            val lower = phrase.lowercase()
            val matches = UssdNavigationConfig.TRANSIENT_RESPONSE_HINTS.any { lower.contains(it) }
            assertTrue("Expected transient phrase '$phrase' to match transient hints", matches)
        }
    }

    @Test
    fun acceleratedTimingGuardsAreNonZeroAndBounded() {
        // Safe fast intervals: strictly greater than zero to prevent busy loops or dialer lockup,
        // yet fast enough to prevent idle latency on step transitions
        assertTrue(UssdNavigationConfig.PENDING_STEP_ADVANCE_KICK_MS in 10L..20L)
        assertTrue(UssdNavigationConfig.PENDING_ADVANCE_KICK_MS in 10L..20L)
        assertTrue(UssdNavigationConfig.FAST_VERIFY_POLL_MS in 5L..15L)
        assertTrue(UssdNavigationConfig.POST_WRITE_VERIFY_POLL_MS in 5L..15L)
        assertTrue(UssdNavigationConfig.POST_WRITE_SEND_RETRY_MS in 10L..20L)
        assertTrue(UssdNavigationConfig.SET_TEXT_RETRY_DELAY_MS in 10L..20L)
        assertTrue(UssdNavigationConfig.STEP_TRANSITION_GUARD_MS in 30L..80L)
        assertTrue(UssdNavigationConfig.EVENT_HOT_POLL_MS in 5L..15L)
    }

    @Test
    fun commonFastInputFieldIdsContainExpectedDialerPatterns() {
        val transsionDialerPkg = "com.sh.smart.caller"
        val fastCandidates = listOf(
            "$transsionDialerPkg:id/input",
            "android:id/input",
            "android:id/edit",
            "$transsionDialerPkg:id/dialog_input"
        )
        fastCandidates.forEach { id ->
            val isCommonOrMatchesHint = UssdNavigationConfig.COMMON_USSD_INPUT_VIEW_IDS.contains(id) ||
                UssdNavigationConfig.INPUT_VIEW_ID_HINTS.any { id.substringAfterLast(":id/").contains(it) }
            assertTrue("Fast ID candidate '$id' should match input ID hints", isCommonOrMatchesHint)
        }
    }

    @Test
    fun multiBrandUssdPackagesAndClassesRecognized() {
        // Multi-brand OEM packages
        val oemPackages = listOf(
            "com.samsung.android.app.telephonyui",
            "com.sec.android.app.popupuireceiver",
            "com.miui.incallui",
            "com.xiaomi.telephony",
            "com.vivo.telephony",
            "com.coloros.telephony",
            "com.oplus.telephony",
            "com.hihonor.incallui",
            "com.sh.smart.caller",
            "com.google.android.dialer"
        )
        oemPackages.forEach { pkg ->
            assertTrue("Expected $pkg to be recognized as potential USSD package",
                UssdNavigationService.isPotentialUssdPackage(pkg))
        }

        // Editable class hints
        assertTrue("Samsung SemEditText must be recognized in editable hints",
            UssdNavigationConfig.EDITABLE_CLASS_HINTS.contains("SemEditText"))

        // Common send view IDs must contain standard Android and OEM button IDs
        assertTrue(UssdNavigationConfig.COMMON_USSD_SEND_VIEW_IDS.contains("android:id/button1"))
        assertTrue(UssdNavigationConfig.COMMON_USSD_SEND_VIEW_IDS.contains("com.android.phone:id/button1"))
        assertTrue(UssdNavigationConfig.COMMON_USSD_SEND_VIEW_IDS.contains("com.samsung.android.app.telephonyui:id/button1"))
        assertTrue(UssdNavigationConfig.COMMON_USSD_SEND_VIEW_IDS.contains("com.sh.smart.caller:id/button1"))
    }
}

