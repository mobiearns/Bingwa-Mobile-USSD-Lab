package com.bingwa.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compact, reusable outer offer summary card.
 *
 * It deliberately owns no navigation, persistence, networking or business logic.
 * The caller supplies display-ready values and handles the optional click.
 */
data class OuterOfferCardUi(
    val name: String,
    val network: String,
    val category: String,
    val bundleAmount: String,
    val price: String,
    val validity: String,
    val availability: String,
    val isAvailable: Boolean = true,
    val supportingInfo: String? = null,
)

@Composable
fun OuterOfferCard(
    offer: OuterOfferCardUi,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(22.dp)
    val statusColor = if (offer.isAvailable) C.green else C.red
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = C.card,
        shape = shape,
        border = BorderStroke(1.dp, C.borderHi),
    ) {
        Column(
            modifier = Modifier
                .background(C.card)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        offer.category.uppercase(),
                        color = C.cyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        softWrap = true,
                        overflow = TextOverflow.Clip,
                    )
                    Text(
                        offer.name,
                        color = C.t1,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        softWrap = true,
                        overflow = TextOverflow.Clip,
                    )
                    Text(
                        offer.network,
                        color = C.t2,
                        fontSize = 12.sp,
                        softWrap = true,
                        overflow = TextOverflow.Clip,
                    )
                }
                Text(
                    offer.availability,
                    color = statusColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    softWrap = true,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier
                        .background(if (offer.isAvailable) C.greenDim else C.redDim, RoundedCornerShape(999.dp))
                        .border(1.dp, statusColor, RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SummaryMetric("BUNDLE", offer.bundleAmount, Modifier.weight(1f))
                SummaryMetric("PRICE", offer.price, Modifier.weight(1f), C.cyan)
                SummaryMetric("VALIDITY", offer.validity, Modifier.weight(1f))
            }

            if (!offer.supportingInfo.isNullOrBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    offer.supportingInfo,
                    color = C.t2,
                    fontSize = 12.sp,
                    softWrap = true,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = C.t1,
) {
    Column(
        modifier = modifier
            .background(C.surface, RoundedCornerShape(13.dp))
            .border(1.dp, C.border, RoundedCornerShape(13.dp))
            .padding(horizontal = 9.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            label,
            color = C.t3,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = .6.sp,
            softWrap = true,
        )
        Text(
            value,
            color = valueColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            softWrap = true,
            overflow = TextOverflow.Clip,
        )
    }
}
