package uk.co.fuelprices.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val BuyMeACoffeeYellow = Color(0xFFFFDD00)

/**
 * A dismissable "support the developer" prompt. [onConfirm] fires when the user taps the coffee
 * button (the caller opens the Buy Me a Coffee page and records the pause); [onDismiss] fires for
 * "Maybe later" and outside-taps.
 */
@Composable
fun CoffeeSupportDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.LocalCafe, contentDescription = null) },
        title = { Text("Keep Fuel Tracker free?") },
        text = {
            Text(
                "I want to keep this app free. Google charges to host apps on the Play Store — " +
                    "has it saved you money? Buy me a coffee to help!"
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BuyMeACoffeeYellow,
                    contentColor = Color.Black,
                ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.LocalCafe, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Buy me a coffee")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Maybe later") }
        },
    )
}
