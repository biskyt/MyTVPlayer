package com.biskyt.mytvplayer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.data.Pin
import com.biskyt.mytvplayer.data.db.CategoryEntity
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * Asks for a [Pin.LENGTH]-digit PIN: typed with the remote's digit keys, or chosen on the number pad (D-pad or touch).
 * [onEntered] gets each whole PIN typed and says whether it was right; a wrong one shows [wrong] and starts again.
 */
@Composable
fun PinPanel(title: String, onDismiss: () -> Unit, help: String? = null, wrong: String? = null, onEntered: suspend (String) -> Boolean) {
    val scope = rememberCoroutineScope()
    var digits by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    fun type(d: Char) {
        if (digits.length >= Pin.LENGTH) return
        digits += d
        failed = false
        if (digits.length == Pin.LENGTH) {
            val pin = digits
            scope.launch {
                // Cleared either way: a panel that stays open (e.g. asking for a new PIN again) starts empty.
                failed = !onEntered(pin)
                digits = ""
            }
        }
    }
    val delete = { digits = digits.dropLast(1) }
    BoardPanel(onDismiss) {
        Column(
            Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                val d = e.key.digit()
                when {
                    e.type != KeyEventType.KeyDown -> false
                    d != null -> true.also { type('0' + d) }
                    e.key == Key.Backspace || e.key == Key.Delete -> true.also { delete() }
                    else -> false
                }
            },
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BoardPanelTitle(title)
            help?.let { BoardPanelSubtitle(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(Pin.LENGTH) { i ->
                    val filled = i < digits.length
                    Box(
                        Modifier.size(18.dp).clip(CircleShape)
                            .background(if (filled) LocalAccent.current.color else Palette.surface)
                            .border(2.dp, Palette.textDim, CircleShape)
                    )
                }
            }
            Text(
                if (failed) wrong ?: stringResource(R.string.parental_pin_wrong) else " ",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.warning
            )
            val rows = listOf("123", "456", "789")
            rows.forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { d ->
                        PadKey(d.toString(), if (r == 0 && d == '1') rememberFirstFocus() else Modifier) { type(d) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PadKey("⌫", onClick = delete)
                PadKey("0") { type('0') }
                Box(Modifier.size(PAD_KEY))
            }
        }
    }
}

@Composable
private fun PadKey(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // Filled with the accent colour while it has focus, as a panel's options are (see BoardPanel).
    TvCard(onClick, modifier.size(PAD_KEY), shape = CircleShape, fill = true) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleLarge)
        }
    }
}

private val PAD_KEY = 64.dp

/**
 * Runs actions that need the parental controls PIN once it's entered. [PinGateHost] shows the PIN panel while one is
 * asked for.
 */
@Stable
class PinGate {
    internal var asking by mutableStateOf<(() -> Unit)?>(null)

    /** Runs [action] once the PIN is entered: at once when [needed] is false (e.g. no PIN is set). */
    fun run(needed: Boolean, action: () -> Unit) {
        if (needed) asking = action else action()
    }
}

@Composable
fun rememberPinGate() = remember { PinGate() }

/** The PIN panel for [gate], while it asks. Entering the PIN also unlocks restricted content until the app is left. */
@Composable
fun PinGateHost(gate: PinGate) {
    val action = gate.asking ?: return
    val parental = LocalContext.current.container.parental
    PinPanel(stringResource(R.string.parental_pin_enter), { gate.asking = null }) { pin ->
        parental.unlock(pin).also {
            if (it) {
                gate.asking = null
                action()
            }
        }
    }
}

/**
 * Whether the category [categoryId] (of [categories]) is restricted while restricted content is hidden: its list then
 * shows [LockedCategory] instead (see [LockedCategory]).
 */
@Composable
fun isLockedCategory(categories: List<CategoryEntity>, categoryId: String?): Boolean {
    val locked by LocalContext.current.container.parental.locked.collectAsState()
    return locked && categoryId != null && categories.any { it.categoryId == categoryId && it.restricted }
}

/**
 * In place of a restricted category's list while locked: says so, with a button for the PIN panel. Entering the PIN
 * shows the list.
 */
@Composable
fun LockedCategory() {
    val gate = rememberPinGate()
    Column(
        Modifier.padding(top = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(AppIcons.Lock, null, tint = Palette.textDim, modifier = Modifier.size(40.dp))
        Text(stringResource(R.string.parental_restricted_notice), style = MaterialTheme.typography.titleMedium)
        PillButton(stringResource(R.string.parental_pin_enter), { gate.run(true) {} }, primary = true)
    }
    PinGateHost(gate)
}

/** The small lock shown beside a restricted category. */
@Composable
fun RestrictedMark(modifier: Modifier = Modifier) {
    Icon(AppIcons.Lock, stringResource(R.string.parental_restricted), tint = cardColor(Palette.textDim), modifier = modifier.size(16.dp))
}
