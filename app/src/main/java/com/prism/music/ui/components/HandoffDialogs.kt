package com.prism.music.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.music.ui.theme.LocalContainer

/**
 * Prism Connect's pairing, wherever Prism is: on the device that's moving its music, a box for the code the other one
 * shows; on the receiving device, that code, big.
 */
@Composable
fun HandoffDialogs() {
    val handoff = LocalContainer.current.handoff
    val asking by handoff.askingCodeFor.collectAsState()
    val shown by handoff.shownCode.collectAsState()
    asking?.let { them ->
        var code by remember(them) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { handoff.submitCode(null) },
            title = { Text("Pair with $them") },
            text = {
                Column {
                    Text("$them is showing a six-digit code. Type it here (only needed the first time).", style = MaterialTheme.typography.bodyMedium)
                    com.prism.music.ui.components.PrismTextField(
                        value = code,
                        onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                        singleLine = true,
                        placeholder = { Text("000000") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        textStyle = TextStyle(fontSize = 24.sp, letterSpacing = 6.sp, textAlign = TextAlign.Center),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    )
                }
            },
            confirmButton = { PrismTextButton(onClick = { handoff.submitCode(code) }, enabled = code.length == 6) { Text("Pair") } },
            dismissButton = { PrismTextButton(onClick = { handoff.submitCode(null) }) { Text("Cancel") } },
        )
    }
    shown?.let { s ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Pair with ${s.from}?") },
            text = {
                Column {
                    Text("${s.from} wants to play music here. To let it, type this code on ${s.from}:", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${s.code.take(3)} ${s.code.drop(3)}",
                        style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Medium, letterSpacing = 4.sp, textAlign = TextAlign.Center),
                        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                    )
                    Text("It goes away once they're paired, or after three minutes.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp))
                }
            },
            confirmButton = { PrismTextButton(onClick = { handoff.hideCode() }) { Text("Not now") } },
        )
    }
}
