package com.bingwa.mobile.ui.editoffer

// Bingwa Mobile — Edit Offer screen (single file).
// Needs: material-icons-extended, activity-compose, ui-tooling-preview (for @Preview).
// Set windowSoftInputMode="adjustResize" and enableEdgeToEdge() so imePadding() works.
// Fonts: swap Head/Body/Mono for Space Grotesk / Inter / JetBrains Mono (res/font).

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bingwa.mobile.C
import com.bingwa.mobile.UiDimens
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

// ───────────── Public model ─────────────
enum class SigStatus { NONE, LEARNING, LEARNED, FAILED }

enum class ValidityUnit(val one: String, val many: String, val adverb: String) {
    HOURS("Hour", "Hours", "Hourly"), DAYS("Day", "Days", "Daily"),
    WEEKS("Week", "Weeks", "Weekly"), MONTHS("Month", "Months", "Monthly")
}

enum class OnChange(val label: String, val hint: String) {
    ADJUST("Adjust", "Re-learn the new text and keep running."),
    STOP("Stop", "Halt execution and flag the offer for review.")
}

data class OfferType(val name: String, val restricted: Boolean, val maxPerDay: Int)

data class SignatureState(val status: SigStatus, val text: String? = null, val learnedAt: String? = null)

data class OfferDraft(
    val name: String = "Daily 1GB",
    val network: String = "Safaricom",
    val price: String = "20",
    val size: String = "1 GB",
    val validityNum: String = "24",
    val validityUnit: ValidityUnit = ValidityUnit.HOURS,
    val category: String = "Data",
    val type: String = "Bingwa Sokoni",
    val types: List<OfferType> = listOf(
        OfferType("Bingwa Sokoni", true, 1), OfferType("Pata More", false, 0),
        OfferType("Tunukiwa", true, 1), OfferType("SMS", false, 0)
    ),
    val ussd: String = "*180*2*1*pn*2*3#",
    val sigEnabled: Boolean = true,
    val onChange: OnChange = OnChange.ADJUST,
    val active: Boolean = true,
    val sim: String = "SIM 1",
    val mode: String = "SIMPLE"
)

// ───────────── Option lists ─────────────
internal val CATEGORIES = listOf("Data", "Airtime", "SMS", "Minutes")
internal val SIMS = listOf("GENERAL SIM", "SIM 1", "SIM 2")
internal val MODES = listOf("SIMPLE", "ADVANCED")

// ───────────── Validation ─────────────
private val USSD_RE = Regex("""^\*[0-9]+(\*([0-9]+|pn))*#$""")
private val PRICE_RE = Regex("""^\d+(\.\d{1,2})?$""")

internal fun ussdError(c: String): String? = when {
    c.isEmpty() -> "USSD code can’t be empty."
    !c.startsWith("*") -> "Code must begin with *"
    !c.endsWith("#") -> "Incomplete — must end with #"
    !USSD_RE.matches(c) -> "Use only digits, * and pn."
    else -> null
}

internal data class OfferErrors(val name: Boolean, val price: Boolean, val validity: Boolean, val ussd: String?) {
    val any: Boolean get() = name || price || validity || ussd != null
}

internal fun OfferDraft.validate() = OfferErrors(
    name = name.isBlank(),
    price = !PRICE_RE.matches(price) || (price.toDoubleOrNull() ?: 0.0) <= 0.0,
    validity = (validityNum.toIntOrNull() ?: 0) <= 0,
    ussd = ussdError(ussd)
)

// ───────────── Display helpers ─────────────
internal fun unitLabel(u: ValidityUnit, n: String) = if (n.toIntOrNull() == 1) u.one else u.many
internal fun typeRule(t: OfferType) = if (t.restricted) "Restricted · max ${t.maxPerDay}/day" else "Multiple per day"
internal fun ussdParts(code: String): List<String> = code.removePrefix("*").removeSuffix("#").split("*")

// ───────────── Type list edits ─────────────
internal fun OfferDraft.withType(index: Int, name: String, restricted: Boolean, max: String): OfferDraft {
    val t = OfferType(name.trim(), restricted, if (restricted) max.toIntOrNull() ?: 1 else 0)
    return if (index !in types.indices) copy(types = types + t, type = t.name)
    else copy(
        types = types.mapIndexed { i, o -> if (i == index) t else o },
        type = if (type == types[index].name) t.name else type
    )
}

internal fun OfferDraft.withoutType(index: Int): OfferDraft {
    val gone = types.getOrNull(index)?.name ?: return this
    val left = types.filterIndexed { i, _ -> i != index }
    return copy(types = left, type = if (type == gone) left.firstOrNull()?.name.orEmpty() else type)
}

// ───────────── Saver (survives rotation / process death) ─────────────
private fun OfferDraft.toJson() = JSONObject().apply {
    put("name", name); put("network", network); put("price", price); put("size", size)
    put("vn", validityNum); put("vu", validityUnit.name); put("cat", category); put("type", type)
    put("types", JSONArray().also { a ->
        types.forEach { a.put(JSONObject().put("n", it.name).put("r", it.restricted).put("m", it.maxPerDay)) }
    })
    put("ussd", ussd); put("sig", sigEnabled); put("oc", onChange.name)
    put("active", active); put("sim", sim); put("mode", mode)
}

private fun JSONObject.toDraft() = OfferDraft(
    name = getString("name"), network = getString("network"), price = getString("price"), size = getString("size"),
    validityNum = getString("vn"), validityUnit = ValidityUnit.valueOf(getString("vu")),
    category = getString("cat"), type = getString("type"),
    types = getJSONArray("types").let { a ->
        List(a.length()) { i -> a.getJSONObject(i).let { OfferType(it.getString("n"), it.getBoolean("r"), it.getInt("m")) } }
    },
    ussd = getString("ussd"), sigEnabled = getBoolean("sig"), onChange = OnChange.valueOf(getString("oc")),
    active = getBoolean("active"), sim = getString("sim"), mode = getString("mode")
)

val OfferDraftSaver: Saver<OfferDraft, String> = Saver(
    save = { it.toJson().toString() },
    restore = { runCatching { JSONObject(it).toDraft() }.getOrNull() }
)

// ───────────── Tokens (dispatch-terminal) ─────────────
// Fonts: swap Head/Body/Mono for Space Grotesk / Inter / JetBrains Mono (res/font).
internal val Bg get() = com.bingwa.mobile.C.bg
internal val S2 get() = com.bingwa.mobile.C.card
internal val Ln get() = com.bingwa.mobile.C.border
internal val Tx get() = com.bingwa.mobile.C.t1
internal val Mu get() = com.bingwa.mobile.C.t2
internal val Am get() = com.bingwa.mobile.C.amber
internal val Cy get() = com.bingwa.mobile.C.cyan
internal val Rd get() = com.bingwa.mobile.C.red
internal val Ink get() = com.bingwa.mobile.C.bg
internal val OnAm get() = com.bingwa.mobile.C.bg
internal val CardTop get() = com.bingwa.mobile.C.cardHi
internal val CardBottom get() = com.bingwa.mobile.C.surface
internal val DialogBg get() = com.bingwa.mobile.C.surface
internal val ToastBg get() = com.bingwa.mobile.C.greenDim
internal val SigText get() = com.bingwa.mobile.C.t2
internal val Head = FontFamily.SansSerif
internal val Body = FontFamily.SansSerif
internal val Mono = FontFamily.Monospace
internal val R8 = RoundedCornerShape(8.dp)
internal val R10 = RoundedCornerShape(10.dp)
internal val R12 = RoundedCornerShape(12.dp)
internal val R16 = RoundedCornerShape(16.dp)

// ───────────── Layout blocks ─────────────
@Composable
internal fun Panel(accent: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = UiDimens.SectionSpacing).clip(RoundedCornerShape(UiDimens.CardRadius))
            .background(Brush.verticalGradient(listOf(CardTop, CardBottom)))
            .border(1.25.dp, com.bingwa.mobile.C.borderHi, RoundedCornerShape(UiDimens.CardRadius))
            .then(if (accent != null) Modifier.drawBehind { drawRect(accent, size = Size(4.dp.toPx(), size.height)) } else Modifier)
            .padding(UiDimens.CardPadding),
        content = content
    )
}

@Composable
internal fun SectionTitle(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).background(Am.copy(.1f), R8), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Am, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), fontFamily = Head, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp, color = Tx,)
    }
    Spacer(Modifier.height(10.dp)); HairLine(); Spacer(Modifier.height(12.dp))
}

@Composable
internal fun HairLine() = Box(Modifier.fillMaxWidth().height(1.dp).background(Ln))

@Composable
internal fun DialogSurface(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.clip(RoundedCornerShape(UiDimens.DialogRadius)).background(DialogBg).border(1.dp, Ln, RoundedCornerShape(UiDimens.DialogRadius)).padding(UiDimens.DialogPadding), content = content)

// ───────────── Text blocks ─────────────
@Composable
internal fun FieldLabel(text: String) =
    Text(text, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Mu, modifier = Modifier.padding(bottom = 6.dp))

@Composable
internal fun HintText(text: String, error: Boolean, color: Color = Mu) =
    Text(text, fontFamily = Body, fontSize = 12.sp, color = if (error) Rd else color,
        overflow = TextOverflow.Clip, modifier = Modifier.padding(top = 8.dp))

@Composable
internal fun Pill(text: String, color: Color) =
    Text(text, color = color, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = .8.sp,
        modifier = Modifier.clip(CircleShape).background(color.copy(.07f)).border(1.dp, color.copy(.35f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp))

@Composable
internal fun TagChip(text: String) =
    Text(text, fontFamily = Mono, fontSize = 11.sp, color = Mu,
        modifier = Modifier .background(C.cardHi, R8).border(1.dp, Ln, R8).padding(horizontal = 7.dp, vertical = 3.dp))

@Composable
internal fun UssdStar(s: String = "*", c: Color = Am) =
    Text(s, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = c, modifier = Modifier.padding(horizontal = 3.dp))

@Composable
internal fun FixedChip(text: String) =
    Text(text, fontFamily = Mono, fontSize = 12.sp, color = Tx,
        modifier = Modifier.background(S2, R8).border(1.dp, Ln, R8).padding(horizontal = 7.dp, vertical = 4.dp))

@Composable
internal fun DynamicChip(text: String) =
    Text(text, fontFamily = Mono, fontSize = 12.sp, color = Cy,
        modifier = Modifier.dashed(Cy).background(Cy.copy(.09f), R8).padding(horizontal = 7.dp, vertical = 4.dp))

internal fun Modifier.dashed(color: Color, radius: Dp = 6.dp) = drawBehind {
    drawRoundRect(color, cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
}

// ───────────── Inputs ─────────────
@Composable
internal fun FieldBox(
    value: String, onChange: (String) -> Unit, editing: Boolean, modifier: Modifier = Modifier,
    prefix: String? = null, error: Boolean = false, keyboard: KeyboardType = KeyboardType.Text
) {
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().heightIn(min = UiDimens.ControlMinHeight).clip(R10).background(Ink)
            .border(1.5.dp, if (error) Rd else if (focused) Am else Ln, R10).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (prefix != null) { Text(prefix, fontFamily = Mono, fontSize = 12.sp, color = Mu); Spacer(Modifier.width(6.dp)) }
        BasicTextField(
            value, onChange, enabled = editing, singleLine = true, cursorBrush = SolidColor(Am),
            textStyle = TextStyle(color = Tx, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused }
        )
    }
}

@Composable
internal fun <T> Segmented(
    options: List<T>, selected: T, label: (T) -> String, enabled: Boolean,
    compact: Boolean = false, onSelect: (T) -> Unit
) {
    Row(Modifier.fillMaxWidth().clip(R10).background(Ink).border(1.dp, Ln, R10).padding(3.dp)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(R8).background(if (on) Am else Color.Transparent)
                    .selectable(selected = on, enabled = enabled, role = Role.RadioButton) { onSelect(o) },
                contentAlignment = Alignment.Center
            ) {
                Text(label(o), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 11.sp else 12.sp,
                    color = if (on) OnAm else Mu,)
            }
        }
    }
}

@Composable
internal fun SignalSwitch(checked: Boolean, enabled: Boolean = true, accent: Color = Cy, onChange: (Boolean) -> Unit) {
    val x by animateDpAsState(if (checked) 29.dp else 3.dp, label = "thumb")
    Box(
        Modifier.size(58.dp, 32.dp).alpha(if (enabled) 1f else .35f).clip(CircleShape)
            .background(if (checked) accent.copy(.13f) else S2)
            .border(1.5.dp, if (checked) accent else Ln, CircleShape)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
    ) {
        Box(Modifier.align(Alignment.CenterStart).offset(x = x).size(23.dp).background(if (checked) accent else Mu, CircleShape))
    }
}

@Composable
internal fun DropField(title: String, hint: String?, expanded: Boolean, editing: Boolean, minHeight: Dp = 52.dp, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = minHeight).clip(R12).background(Ink).border(1.5.dp, Ln, R12)
            .clickable(enabled = editing, role = Role.DropdownList, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Tx,)
            if (hint != null) Text(hint, fontFamily = Body, fontSize = 11.5.sp, color = Mu, overflow = TextOverflow.Clip)
        }
        if (editing) Icon(Icons.Rounded.ExpandMore, null, tint = Am, modifier = Modifier.rotate(if (expanded) 180f else 0f))
    }
}

@Composable
internal fun MenuBox(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(R12).background(S2).border(1.dp, Ln, R12), content = content)

@Composable
internal fun MenuRow(title: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(if (selected) Am.copy(.08f) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(title, fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (selected) Am else Tx,)
        Text(hint, fontFamily = Body, fontSize = 11.5.sp, color = Mu, overflow = TextOverflow.Clip)
    }
}

// ───────────── Buttons ─────────────
@Composable
internal fun IconAction(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) =
    Box(Modifier.size(UiDimens.IconTouchTarget).clip(RoundedCornerShape(UiDimens.ButtonRadius)).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(18.dp))
    }

@Composable
internal fun OutlineButton(
    text: String, modifier: Modifier = Modifier, icon: ImageVector? = null,
    color: Color = Tx, enabled: Boolean = true, onClick: () -> Unit
) = Row(
    modifier.heightIn(min = UiDimens.ControlMinHeight).alpha(if (enabled) 1f else .4f).clip(RoundedCornerShape(UiDimens.ButtonRadius)).background(S2)
        .border(1.dp, if (color == Rd) Rd.copy(.27f) else Ln, RoundedCornerShape(UiDimens.ButtonRadius))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
) {
    if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
    Text(text, fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = color,)
}

@Composable
internal fun PrimaryButton(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Box(
        modifier.heightIn(min = UiDimens.ControlMinHeight).alpha(if (enabled) 1f else .4f).clip(RoundedCornerShape(UiDimens.ButtonRadius)).background(Am)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = OnAm,) }

// ───────────── Signature widgets ─────────────
@Composable
internal fun InfoCell(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Tx) =
    Column(modifier.clip(R8).border(1.dp, Ln, R8).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(label, fontFamily = Body, fontSize = 11.sp, color = Mu,)
        Text(value, fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = valueColor, overflow = TextOverflow.Clip)
    }

private data class StatusLook(val color: Color, val icon: ImageVector, val title: String, val sub: String)

@Composable
internal fun SigStatusRow(status: SigStatus) {
    val look = when (status) {
        SigStatus.NONE -> StatusLook(Am, Icons.Rounded.Sensors, "Not learned", "No signature recorded yet.")
        SigStatus.LEARNING -> StatusLook(Am, Icons.Rounded.Sync, "Learning…", "Recording USSD dialog text…")
        SigStatus.LEARNED -> StatusLook(Cy, Icons.Rounded.Check, "Successfully learned", "Saved and ready for execution.")
        SigStatus.FAILED -> StatusLook(Rd, Icons.Rounded.Close, "Learning failed", "Nothing was captured. Try again.")
    }
    Row(
        Modifier.fillMaxWidth().clip(R10).background(S2).border(1.dp, Ln, R10).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(34.dp).background(look.color.copy(.07f), CircleShape).border(1.dp, look.color.copy(.35f), CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(look.icon, null, tint = look.color, modifier = Modifier.size(18.dp))
        }
        Column {
            Text(look.title, fontFamily = Head, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Tx,)
            Text(look.sub, fontFamily = Body, fontSize = 12.sp, color = Mu, overflow = TextOverflow.Clip)
        }
    }
}


private sealed interface Dlg {
    object Leave : Dlg
    object DeleteSig : Dlg
    data class DeleteType(val i: Int) : Dlg
    data class TypeForm(val i: Int, val name: String, val restricted: Boolean, val max: String) : Dlg
}

@Stable
private class Menus {
    var type by mutableStateOf(false)
    var unit by mutableStateOf(false)
    var change by mutableStateOf(false)
    fun close() { type = false; unit = false; change = false }
}

// ───────────── Screen ─────────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditOfferScreen(
    initial: OfferDraft,
    signature: SignatureState,
    onSave: (OfferDraft) -> Boolean,
    modifier: Modifier = Modifier,
    onStartLearning: (OfferDraft) -> Unit = {},
    onRelearn: (OfferDraft) -> Unit = {},
    onDeleteSignature: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    var saved by rememberSaveable(initial, stateSaver = OfferDraftSaver) { mutableStateOf(initial) }
    var d by rememberSaveable(initial, stateSaver = OfferDraftSaver) { mutableStateOf(initial) }
    var editing by rememberSaveable { mutableStateOf(false) }          // always opens in View mode
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var backToOffersAfterDiscard by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf(false) }
    var sigOpen by remember { mutableStateOf(false) }
    val menus = remember { Menus() }
    val focus = LocalFocusManager.current

    val dirty = d != saved
    val err = remember(d) { d.validate() }
    val canSave = dirty && !err.any
    val learned = signature.status == SigStatus.LEARNED
    val sigOn = learned && d.sigEnabled

    fun setEditing(on: Boolean) { editing = on; menus.close(); focus.clearFocus() }
    fun handleBack() {
        if (editing && dirty) {
            backToOffersAfterDiscard = true
            dlg = Dlg.Leave
        } else {
            onBack()
        }
    }
    BackHandler(editing) { if (dirty) dlg = Dlg.Leave else setEditing(false) }
    LaunchedEffect(toast) { if (toast) { delay(2600); toast = false } }

    Box(
        modifier.fillMaxSize().background(Bg).imePadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            Modifier.widthIn(max = UiDimens.ScreenContentMaxWidth).fillMaxWidth()
                .verticalScroll(rememberScrollState()).statusBarsPadding()
                .padding(start = UiDimens.ScreenHorizontal, end = UiDimens.ScreenHorizontal, top = UiDimens.ScreenVertical, bottom = 120.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier.size(40.dp).clip(R12).background(S2)
                        .border(1.dp, Ln, R12)
                        .clickable(role = Role.Button, onClick = ::handleBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back to offers", tint = Tx, modifier = Modifier.size(19.dp))
                }
                Text("Back to offers", color = Mu, fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
            HeaderCard(d, editing, err.name) { d = it }
            PricingCard(d, editing, err, menus, { d = it }) { dlg = it }
            UssdCard(d, editing, err.ussd) { d = it }
            SignatureCard(
                d, signature, editing, sigOpen, { sigOpen = true },
                onRelearn, onStartLearning, { dlg = Dlg.DeleteSig }
            )
            ExecutionCard(d, learned, sigOn, editing, menus, { sigOpen = false; d = it })
            SettingsCard(d, editing) { d = it }
        }

        ModeFab(editing, Modifier.align(Alignment.BottomEnd)) { setEditing(!editing) }

        if (editing) ActionBar(
            Modifier.align(Alignment.BottomCenter), dirty, canSave,
            onCancel = { if (dirty) dlg = Dlg.Leave else setEditing(false) },
            onReset = { d = saved; menus.close() },
            onSave = { if (onSave(d)) { saved = d; setEditing(false); toast = true } }
        )

        AnimatedVisibility(toast, Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp)) {
            Row(
                Modifier.clip(R12).background(ToastBg).border(1.dp, Cy, R12).padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Rounded.Check, null, tint = Cy, modifier = Modifier.size(16.dp))
                Text("Offer saved", fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Cy)
            }
        }
    }

    when (val x = dlg) {
        Dlg.Leave -> ConfirmDialog("Discard changes?", "You have unsaved changes to this offer.", "Keep editing", "Discard",
            onDismiss = { dlg = null; backToOffersAfterDiscard = false }) {
            d = saved
            dlg = null
            if (backToOffersAfterDiscard) {
                backToOffersAfterDiscard = false
                onBack()
            } else {
                setEditing(false)
            }
        }
        Dlg.DeleteSig -> ConfirmDialog("Delete learned signature?",
            "This permanently removes the recorded signature. To keep it, just switch execution off instead.", "Keep it", "Delete",
            onDismiss = { dlg = null }) { onDeleteSignature(); dlg = null }
        is Dlg.DeleteType -> ConfirmDialog("Delete “${d.types.getOrNull(x.i)?.name.orEmpty()}”?",
            "This removes the type from the list. Save to apply.", "Keep", "Delete",
            onDismiss = { dlg = null }) { d = d.withoutType(x.i); dlg = null }
        is Dlg.TypeForm -> TypeFormDialog(x, d.types, update = { dlg = it }, dismiss = { dlg = null }) { f ->
            d = d.withType(f.i, f.name, f.restricted, f.max); dlg = null; menus.type = false
        }
        null -> {}
    }
}

// ───────────── Sections ─────────────
@Composable
private fun HeaderCard(d: OfferDraft, editing: Boolean, nameErr: Boolean, onDraft: (OfferDraft) -> Unit) = Panel {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = d.name, onValueChange = { onDraft(d.copy(name = it.take(40))) }, enabled = editing, singleLine = true,
                textStyle = TextStyle(color = Tx, fontFamily = Head, fontWeight = FontWeight.Bold, fontSize = 21.sp),
                cursorBrush = SolidColor(Am),
                modifier = Modifier.fillMaxWidth().drawBehind {
                    if (editing) drawLine(
                        if (nameErr) Rd else Ln, Offset(0f, size.height), Offset(size.width, size.height), 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                    )
                }.padding(end = 24.dp, top = 4.dp, bottom = 6.dp)
            )
            if (editing) Icon(Icons.Rounded.Edit, null, tint = Mu, modifier = Modifier.align(Alignment.CenterEnd).size(15.dp))
        }
        Pill(if (editing) "EDIT MODE" else "VIEW MODE", if (editing) Am else Mu)
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        TagChip(d.network); TagChip(d.category); TagChip("KSh ${d.price}")
        Pill(if (d.active) "● ACTIVE" else "● INACTIVE", if (d.active) Cy else Mu)
    }
}

@Composable
private fun PricingCard(
    d: OfferDraft, editing: Boolean, err: OfferErrors, menus: Menus,
    onDraft: (OfferDraft) -> Unit, onDialog: (Dlg) -> Unit
) = Panel {
    SectionTitle(Icons.Rounded.Sell, "Offer & Pricing")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            FieldLabel("PRICE")
            FieldBox(d.price, { onDraft(d.copy(price = it.filter { c -> c.isDigit() || c == '.' }.take(10))) }, editing,
                prefix = "KSh", error = err.price, keyboard = KeyboardType.Decimal)
        }
        Column(Modifier.weight(1f)) {
            FieldLabel("BUNDLE SIZE")
            FieldBox(d.size, { onDraft(d.copy(size = it.take(20))) }, editing)
        }
    }
    HintText(if (err.price) "Enter a price above 0, e.g. 20 or 49.50" else "Matches payments of KSh ${d.price}.", err.price)

    Spacer(Modifier.height(12.dp)); FieldLabel("VALIDITY")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        FieldBox(d.validityNum, { onDraft(d.copy(validityNum = it.filter(Char::isDigit).take(3))) }, editing,
            modifier = Modifier.width(90.dp), error = err.validity, keyboard = KeyboardType.Number)
        Column(Modifier.weight(1f)) {
            DropField(unitLabel(d.validityUnit, d.validityNum), null, menus.unit, editing, 46.dp) { menus.unit = !menus.unit }
            AnimatedVisibility(menus.unit && editing) {
                MenuBox {
                    ValidityUnit.values().forEach { u ->
                        MenuRow(unitLabel(u, d.validityNum), u.adverb, u == d.validityUnit) {
                            onDraft(d.copy(validityUnit = u)); menus.unit = false
                        }
                    }
                }
            }
        }
    }
    HintText(
        if (err.validity) "Enter a validity above 0" else "Valid for ${d.validityNum} ${unitLabel(d.validityUnit, d.validityNum).lowercase()}.",
        err.validity
    )

    Spacer(Modifier.height(12.dp)); FieldLabel("OFFER TYPE")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            val t = d.types.firstOrNull { it.name == d.type }
            DropField(t?.name ?: "No type", t?.let(::typeRule) ?: "Tap + to add a type", menus.type, editing) { menus.type = !menus.type }
            AnimatedVisibility(menus.type && editing) {
                MenuBox {
                    d.types.forEachIndexed { i, x ->
                        val on = x.name == d.type
                        Row(Modifier.fillMaxWidth().background(if (on) Am.copy(.08f) else Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier.weight(1f).clickable(role = androidx.compose.ui.semantics.Role.Button) {
                                    onDraft(d.copy(type = x.name)); menus.type = false
                                }.padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Text(x.name, fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (on) Am else Tx,)
                                Text(typeRule(x), fontFamily = Body, fontSize = 11.5.sp, color = Mu,)
                            }
                            IconAction(Icons.Rounded.Edit, "Configure ${x.name}", Mu) {
                                onDialog(Dlg.TypeForm(i, x.name, x.restricted, x.maxPerDay.coerceAtLeast(1).toString()))
                            }
                            IconAction(Icons.Rounded.Close, "Delete ${x.name}", Rd) { onDialog(Dlg.DeleteType(i)) }
                        }
                    }
                }
            }
        }
        if (editing) Box(
            Modifier.size(52.dp).clip(R12).background(Am.copy(.1f)).border(1.5.dp, Am, R12)
                .clickable(role = androidx.compose.ui.semantics.Role.Button) { onDialog(Dlg.TypeForm(-1, "", false, "1")) },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.Add, "Add offer type", tint = Am) }
    }

    Spacer(Modifier.height(12.dp)); FieldLabel("CATEGORY")
    Segmented(CATEGORIES, d.category, { it }, editing, compact = true) { onDraft(d.copy(category = it)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UssdCard(d: OfferDraft, editing: Boolean, error: String?, onDraft: (OfferDraft) -> Unit) = Panel {
    SectionTitle(Icons.Rounded.Tag, "USSD Code Configuration")
    val clip = LocalClipboardManager.current
    UssdField(d.ussd, editing, error != null, { onDraft(d.copy(ussd = it.trim())) }) { clip.setText(AnnotatedString(d.ussd)) }

    val parts = remember(d.ussd) { ussdParts(d.ussd) }
    FlowRow(
        Modifier.padding(top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        UssdStar()
        parts.forEachIndexed { i, p ->
            if (p == "pn") DynamicChip("pn") else FixedChip(p.ifEmpty { "…" })
            if (i < parts.lastIndex) UssdStar()
        }
        if (d.ussd.endsWith("#")) UssdStar("#") else UssdStar("?", Rd)
    }
    val dynamic = parts.count { it == "pn" }
    if (error != null) HintText(error, true) else HintText("✓ Valid sequence · $dynamic dynamic value(s)", false, Cy)

    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).dashed(Ln))
    Spacer(Modifier.height(12.dp)); FieldLabel("PLACEHOLDERS")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DynamicChip("pn")
        Text("Customer phone number", fontFamily = Body, fontSize = 12.5.sp, color = Mu,)
    }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FixedChip("fixed")
        Text("Fixed digits", fontFamily = Body, fontSize = 12.5.sp, color = Mu,)
    }
}

@Composable
private fun UssdField(value: String, editing: Boolean, error: Boolean, onChange: (String) -> Unit, onCopy: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    Row(
        Modifier.fillMaxWidth().clip(R12).background(Ink)
            .border(1.5.dp, if (error) Rd else if (focused) Am else Ln, R12).height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.fillMaxHeight().width(22.dp).border(1.dp, Ln), contentAlignment = Alignment.Center) {
            Text("START", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = Mu, letterSpacing = 1.sp,
                modifier = Modifier.rotate(-90f), softWrap = true)
        }
        BasicTextField(
            value = value, onValueChange = onChange, enabled = editing, singleLine = true, cursorBrush = SolidColor(Am),
            textStyle = TextStyle(color = Tx, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 16.dp).onFocusChanged { focused = it.isFocused }
        )
        IconAction(Icons.Rounded.ContentCopy, "Copy USSD code", Mu, onCopy)
    }
}

@Composable
private fun SignatureCard(
    d: OfferDraft, signature: SignatureState, editing: Boolean, sigOpen: Boolean, onShow: () -> Unit,
    onRelearn: (OfferDraft) -> Unit, onStartLearning: (OfferDraft) -> Unit, onDelete: () -> Unit
) = Panel(accent = Cy) {
    val learned = signature.status == SigStatus.LEARNED
    val collapsed = learned && !d.sigEnabled && !sigOpen
    var textOpen by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current

    SectionTitle(Icons.Rounded.GraphicEq, "Learned USSD Signature")
    if (!collapsed) SigStatusRow(signature.status)
    when {
        collapsed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("● Learned · saved, not in use", fontFamily = Body, fontSize = 12.5.sp, color = Mu,
                overflow = TextOverflow.Clip, modifier = Modifier.weight(1f))
            OutlineButton("Show", Modifier.height(38.dp), onClick = onShow)
        }
        learned -> Column {
            Spacer(Modifier.height(12.dp))
            Row { FieldLabel("RECORDED SIGNATURE DATA"); Text("  · SAMPLE", fontFamily = Mono, fontSize = 10.sp, color = Am) }
            Text(
                signature.text.orEmpty(), fontFamily = Mono, fontSize = 12.sp, lineHeight = 19.sp, color = SigText, overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth().clip(R10).background(Ink).border(1.dp, Ln, R10).padding(12.dp).animateContentSize()
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton(if (textOpen) "Collapse" else "View full text", Modifier.weight(1f)) { textOpen = !textOpen }
                OutlineButton("Copy", Modifier.weight(1f), Icons.Rounded.ContentCopy) { clip.setText(AnnotatedString(signature.text.orEmpty())) }
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoCell("Learning status", "Complete", Modifier.weight(1f))
                InfoCell("Learned on", signature.learnedAt ?: "—", Modifier.weight(1f))
            }
            InfoCell("Available for execution", if (d.sigEnabled) "Yes — in use" else "Saved, not in use",
                Modifier.padding(top = 8.dp).fillMaxWidth(), if (d.sigEnabled) Cy else Am)
            if (editing) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton("Re-learn", Modifier.weight(1f), onClick = { onRelearn(d) })
                OutlineButton("Delete signature", Modifier.weight(1f), color = Rd, onClick = onDelete)
            }
        }
        editing -> PrimaryButton(
            when (signature.status) { SigStatus.FAILED -> "Try again"; SigStatus.LEARNING -> "Learning…"; else -> "Start learning" },
            signature.status != SigStatus.LEARNING, Modifier.fillMaxWidth().padding(top = 8.dp)) { onStartLearning(d) }
    }
}

@Composable
private fun ExecutionCard(
    d: OfferDraft, learned: Boolean, sigOn: Boolean, editing: Boolean, menus: Menus, onDraft: (OfferDraft) -> Unit
) = Panel(accent = if (sigOn) Cy else Mu) {
    SectionTitle(Icons.Rounded.Bolt, "Signature Execution")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (sigOn) "Enabled" else "Disabled", fontFamily = Head, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Tx)
            Text(if (learned) "Use the recorded signature when running." else "Learn a signature first.",
                fontFamily = Body, fontSize = 12.sp, color = Mu, overflow = TextOverflow.Clip)
        }
        SignalSwitch(sigOn, enabled = learned && editing) { menus.change = false; onDraft(d.copy(sigEnabled = it)) }
    }
    Text(
        when { !learned -> "No learned signature yet."; sigOn -> "Learned signature is in use."; else -> "Saved, but not used while off." },
        fontFamily = Body, fontSize = 12.sp, color = Mu, overflow = TextOverflow.Clip,
        modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(R8).background(S2).padding(horizontal = 10.dp, vertical = 9.dp)
    )
    AnimatedVisibility(sigOn) {
        Column(Modifier.padding(top = 12.dp)) {
            FieldLabel("WHEN SIGNATURE CHANGE IS DETECTED")
            DropField(d.onChange.label, d.onChange.hint, menus.change, editing) { menus.change = !menus.change }
            AnimatedVisibility(menus.change && editing) {
                MenuBox {
                    OnChange.values().forEach { o ->
                        MenuRow(o.label, o.hint, o == d.onChange) { onDraft(d.copy(onChange = o)); menus.change = false }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(d: OfferDraft, editing: Boolean, onDraft: (OfferDraft) -> Unit) = Panel {
    SectionTitle(Icons.Rounded.Tune, "Execution Settings")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Offer active", fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = Tx)
            Text("Available for automatic dispatch", fontFamily = Body, fontSize = 12.sp, color = Mu,)
        }
        SignalSwitch(d.active, editing, Am) { onDraft(d.copy(active = it)) }
    }
    AnimatedVisibility(d.active) {
        Column {
            Spacer(Modifier.height(12.dp)); HairLine(); Spacer(Modifier.height(12.dp))
            SettingTitle("SIM")
            Segmented(SIMS, d.sim, { it }, editing) { onDraft(d.copy(sim = it)) }
            Spacer(Modifier.height(12.dp)); HairLine(); Spacer(Modifier.height(12.dp))
            SettingTitle("Execution mode")
            Segmented(MODES, d.mode, { it }, editing) { onDraft(d.copy(mode = it)) }
        }
    }
}

@Composable
private fun SettingTitle(text: String) {
    Text(text, fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = Tx)
    Spacer(Modifier.height(8.dp))
}

// ───────────── Floating controls ─────────────
@Composable
private fun ModeFab(editing: Boolean, modifier: Modifier, onClick: () -> Unit) =
    Text(
        if (editing) "▤ View" else "✎ Edit", fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Mu,
        modifier = modifier.padding(end = 14.dp, bottom = 92.dp).clip(CircleShape).background(S2).border(1.dp, Ln, CircleShape)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    )

@Composable
private fun ActionBar(
    modifier: Modifier, dirty: Boolean, canSave: Boolean,
    onCancel: () -> Unit, onReset: () -> Unit, onSave: () -> Unit
) = Row(
    modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Bg, Bg)))
        .navigationBarsPadding().padding(start = 14.dp, end = 14.dp, top = 22.dp, bottom = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp)
) {
    OutlineButton("Cancel", onClick = onCancel)
    OutlineButton("Reset", enabled = dirty, onClick = onReset)
    PrimaryButton("Save Changes", canSave, Modifier.weight(1f), onSave)
}

// ───────────── Dialogs ─────────────
@Composable
private fun ConfirmDialog(
    title: String, text: String, keep: String, confirm: String,
    onDismiss: () -> Unit, onConfirm: () -> Unit
) = Dialog(onDismissRequest = onDismiss) {
    DialogSurface {
        Text(title, fontFamily = Head, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Tx)
        Spacer(Modifier.height(6.dp))
        Text(text, fontFamily = Body, fontSize = 13.sp, color = Mu)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineButton(keep, Modifier.weight(1f), onClick = onDismiss)
            OutlineButton(confirm, Modifier.weight(1f), color = Rd, onClick = onConfirm)
        }
    }
}

@Composable
private fun TypeFormDialog(
    form: Dlg.TypeForm, all: List<OfferType>,
    update: (Dlg.TypeForm) -> Unit, dismiss: () -> Unit, save: (Dlg.TypeForm) -> Unit
) {
    val dup = all.withIndex().any { (j, o) -> j != form.i && o.name.equals(form.name.trim(), ignoreCase = true) }
    val bad = form.name.isBlank() || dup || (form.restricted && (form.max.toIntOrNull() ?: 0) <= 0)
    Dialog(onDismissRequest = dismiss) {
        DialogSurface {
            Text(if (form.i < 0) "Add offer type" else "Configure type", fontFamily = Head, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Tx)
            Spacer(Modifier.height(14.dp)); FieldLabel("NAME")
            FieldBox(form.name, { update(form.copy(name = it.take(30))) }, true, error = dup)
            if (dup) HintText("A type with this name already exists", true)
            Spacer(Modifier.height(12.dp)); FieldLabel("PURCHASE RULE")
            Segmented(listOf(false, true), form.restricted, { if (it) "Restricted" else "Multiple / day" }, true) {
                update(form.copy(restricted = it))
            }
            if (form.restricted) {
                Spacer(Modifier.height(12.dp)); FieldLabel("MAX PURCHASES PER DAY")
                FieldBox(form.max, { update(form.copy(max = it.filter(Char::isDigit).take(3))) }, true, keyboard = KeyboardType.Number)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton("Cancel", Modifier.weight(1f), onClick = dismiss)
                PrimaryButton(if (form.i < 0) "Add type" else "Done", !bad, Modifier.weight(1f)) { save(form) }
            }
        }
    }
}

// ───────────── Preview ─────────────
@Preview(showBackground = true, backgroundColor = 0xFF0F1317, widthDp = 380, heightDp = 1400)
@Composable
private fun EditOfferPreview() = EditOfferScreen(
    initial = OfferDraft(),
    signature = SignatureState(SigStatus.LEARNED, "[SAMPLE TEXT — not real recorded data]\\nStep 1 dialog text\\nStep 2 option list\\nFinal confirmation text", "sample date/time"),
    onSave = { true }
)
