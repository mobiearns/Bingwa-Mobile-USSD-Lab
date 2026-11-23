package com.bingwa.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

internal const val USSD_SIM_SELECTION_SLOT_1 = -1
internal const val USSD_SIM_SELECTION_SLOT_2 = -2
internal const val USSD_SIM_SELECTION_BOTH = -3

internal data class UssdSimTarget(
    val subId: Int,
    val slotIndex: Int
)

internal fun normalizeUssdSimSelection(rawSelection: Int, sims: List<SubscriptionInfo>): Int {
    return when (rawSelection) {
        USSD_SIM_SELECTION_SLOT_1,
        USSD_SIM_SELECTION_SLOT_2 -> rawSelection
        USSD_SIM_SELECTION_BOTH -> USSD_SIM_SELECTION_SLOT_1
        else -> {
            val matched = sims.firstOrNull { it.subscriptionId == rawSelection }
            when (matched?.simSlotIndex) {
                1 -> USSD_SIM_SELECTION_SLOT_2
                else -> USSD_SIM_SELECTION_SLOT_1
            }
        }
    }
}

internal fun currentUssdSimSelection(context: Context): Int {
    val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    val sims = getAvailableSims(context)
    return normalizeUssdSimSelection(
        rawSelection = prefs.safeGetInt("selected_sim_id", USSD_SIM_SELECTION_SLOT_1),
        sims = sims
    )
}

internal fun resolvePreferredUssdSubId(context: Context, selectionOverride: Int? = null): Int? =
    resolveUssdSimTargets(context, selectionOverride).firstOrNull()?.subId

internal fun resolveUssdSimTargets(
    context: Context,
    selectionOverride: Int? = null,
    forceRefresh: Boolean = false
): List<UssdSimTarget> {
    val sims = getAvailableSims(context, forceRefresh = forceRefresh).sortedBy { it.simSlotIndex }
    val rawSelection = selectionOverride ?: context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        .safeGetInt("selected_sim_id", USSD_SIM_SELECTION_SLOT_1)
    val explicitSubscription = sims.firstOrNull { it.subscriptionId == rawSelection }
    val normalizedSelection = normalizeUssdSimSelection(rawSelection, sims)
    val hasSlotMetadata = sims.any { it.simSlotIndex >= 0 }

    val slot1 = if (hasSlotMetadata) {
        sims.firstOrNull { it.simSlotIndex == 0 }
    } else {
        sims.getOrNull(0)
    }
    val slot2 = if (hasSlotMetadata) {
        sims.firstOrNull { it.simSlotIndex == 1 }
    } else {
        sims.getOrNull(1)
    }

    val hasPhoneStatePermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_PHONE_STATE
    ) == PackageManager.PERMISSION_GRANTED

    return when {
        explicitSubscription != null -> listOf(
            UssdSimTarget(
                subId = explicitSubscription.subscriptionId,
                slotIndex = explicitSubscription.simSlotIndex
            )
        )
        rawSelection == USSD_SIM_SELECTION_BOTH -> {
            val list = listOfNotNull(slot1, slot2).map {
                UssdSimTarget(subId = it.subscriptionId, slotIndex = it.simSlotIndex)
            }
            if (list.isNotEmpty() || hasPhoneStatePermission) list
            else fallbackDefaultTargets()
        }
        normalizedSelection == USSD_SIM_SELECTION_SLOT_2 -> {
            if (slot2 != null) {
                listOf(UssdSimTarget(subId = slot2.subscriptionId, slotIndex = slot2.simSlotIndex))
            } else if (hasPhoneStatePermission) {
                // Slot 2 was explicitly requested, do not fall back to Slot 1.
                emptyList()
            } else {
                emptyList()
            }
        }
        normalizedSelection == USSD_SIM_SELECTION_SLOT_1 -> {
            if (slot1 != null) {
                listOf(UssdSimTarget(subId = slot1.subscriptionId, slotIndex = slot1.simSlotIndex))
            } else if (sims.size == 1 && sims[0].simSlotIndex == 1 && selectionOverride == null && rawSelection == USSD_SIM_SELECTION_SLOT_1) {
                // Single active SIM inserted in Slot 2 while user never customized Slot 1 vs Slot 2.
                // Only allow when general default is used; explicit Slot 1 selection stays strict if Slot 1 is empty.
                emptyList()
            } else if (hasPhoneStatePermission) {
                emptyList()
            } else {
                fallbackDefaultTargets()
            }
        }
        else -> {
            val primary = slot1 ?: slot2
            if (primary != null) {
                listOf(UssdSimTarget(subId = primary.subscriptionId, slotIndex = primary.simSlotIndex))
            } else if (hasPhoneStatePermission) {
                emptyList()
            } else {
                fallbackDefaultTargets()
            }
        }
    }
}

private fun fallbackDefaultTargets(): List<UssdSimTarget> {
    val defaultSubId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        SubscriptionManager.getDefaultVoiceSubscriptionId().takeIf {
            it != SubscriptionManager.INVALID_SUBSCRIPTION_ID
        } ?: SubscriptionManager.getDefaultSubscriptionId().takeIf {
            it != SubscriptionManager.INVALID_SUBSCRIPTION_ID
        } ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
    } else {
        -1
    }
    return listOf(UssdSimTarget(subId = defaultSubId, slotIndex = 0))
}

internal fun describeUssdSimSelection(selection: Int, sims: List<SubscriptionInfo>): String {
    val hasSlotMetadata = sims.any { it.simSlotIndex >= 0 }
    val slot1 = if (hasSlotMetadata) sims.firstOrNull { it.simSlotIndex == 0 } else sims.getOrNull(0)
    val slot2 = if (hasSlotMetadata) sims.firstOrNull { it.simSlotIndex == 1 } else sims.getOrNull(1)

    return when (normalizeUssdSimSelection(selection, sims)) {
        USSD_SIM_SELECTION_SLOT_2 -> formatUssdSlotLabel(2, slot2)
        else -> formatUssdSlotLabel(1, slot1)
    }
}

private fun formatUssdSlotLabel(slotNumber: Int, simInfo: SubscriptionInfo?): String {
    val name = simInfo?.displayName?.toString()?.trim().orEmpty()
    return if (name.isBlank()) {
        "Slot $slotNumber"
    } else {
        "Slot $slotNumber · $name"
    }
}
