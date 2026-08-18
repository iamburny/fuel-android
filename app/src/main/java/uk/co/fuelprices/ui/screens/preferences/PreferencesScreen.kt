package uk.co.fuelprices.ui.screens.preferences

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import uk.co.fuelprices.BuildConfig
import uk.co.fuelprices.data.api.FuelTypes
import uk.co.fuelprices.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PreferencesScreen(
    onSignIn: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    viewModel: PreferencesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // Re-check the signed-in state each time this screen enters composition, so a sign-in/out done
    // on the Auth screen is reflected on return (TokenStore exposes no reactive Flow).
    LaunchedEffect(Unit) { viewModel.refreshAccount() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Preferences") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── Buy me a coffee ── (flag: shared.buy-me-a-coffee)
            if (state.showBuyMeCoffee) {
                Card(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/iamburny"))
                            )
                        } catch (_: Exception) {
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.LocalCafe, contentDescription = null, tint = Color(0xFFFFDD00))
                        Column {
                            Text("Buy me a coffee", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Fuel Tracker UK is free and ad-free — support keeps it running",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }

            // ── Also available on the web ── (flag: fuel-android.also-available-on-web)
            if (state.showAlsoAvailableOnWeb) {
                Card(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://fueltracker.uk"))
                            )
                        } catch (_: Exception) {
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("Also available on the web", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "fueltracker.uk — same account, same favourites",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }

            // ── Account ──
            Text("Account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Card(Modifier.fillMaxWidth()) {
                if (state.isLoggedIn) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Signed in" + (state.email?.let { " as $it" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { viewModel.signOut() }) { Text("Sign out") }
                    }
                } else {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Sign in to save favourite stations and get price-drop alerts.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onSignIn) { Text("Sign in") }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "Your usual fuel",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FuelTypes.ALL.forEach { type ->
                    FilterChip(
                        selected = state.fuelType == type,
                        onClick = { viewModel.setFuelType(type) },
                        label = {
                            Text(
                                if (state.useLongFuelNames) FuelTypes.longLabel(type)
                                else FuelTypes.shortLabel(type)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = FuelTypes.color(type),
                            selectedLabelColor = Color.White,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Long fuel names", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Show \"Unleaded (E10)\" instead of \"E10\" throughout the app",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Switch(
                    checked = state.useLongFuelNames,
                    onCheckedChange = { viewModel.setUseLongFuelNames(it) },
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "Appearance",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            val themeOptions = listOf(
                ThemeMode.SYSTEM to "System",
                ThemeMode.LIGHT to "Light",
                ThemeMode.DARK to "Dark",
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                themeOptions.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = state.themeMode == mode.name,
                        onClick = { viewModel.setThemeMode(mode.name) },
                        shape = SegmentedButtonDefaults.itemShape(index, themeOptions.size),
                        label = { Text(label) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "Your car",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Used to estimate whether driving to a cheaper station is actually worth it, " +
                    "factoring in the fuel it takes to get there.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            OutlinedTextField(
                value = state.mpgText,
                onValueChange = { viewModel.setMpgText(it) },
                label = { Text("Average MPG") },
                placeholder = { Text("e.g. 45") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.tankCapacityText,
                onValueChange = { viewModel.setTankCapacityText(it) },
                label = { Text("Tank capacity (litres)") },
                placeholder = { Text("e.g. 55") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Preferences save automatically as they're changed (see PreferencesViewModel) — this
            // is just a transient confirmation, not a trigger.
            if (state.justSaved) {
                Text(
                    "Saved",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // Hidden entry point to the Diagnostics screen — mirrors Android's own "tap the
            // build number 7 times" developer-options gesture. Not discoverable by accident, but
            // easy to talk a reporting user through over chat/email when something like the map
            // not loading needs more device info than a screenshot can show.
            var versionTapCount by remember { mutableStateOf(0) }
            var lastTapAt by remember { mutableStateOf(0L) }
            Text(
                "Fuel Tracker UK v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .clickable {
                        val now = System.currentTimeMillis()
                        // Scattered taps more than 2s apart don't accumulate, same as stray
                        // taps elsewhere in a session shouldn't eventually trip this by accident.
                        versionTapCount = if (now - lastTapAt > 2_000L) 1 else versionTapCount + 1
                        lastTapAt = now
                        val remaining = 7 - versionTapCount
                        when {
                            remaining <= 0 -> {
                                versionTapCount = 0
                                onOpenDiagnostics()
                            }
                            remaining <= 3 -> Toast.makeText(
                                context,
                                "$remaining more tap${if (remaining == 1) "" else "s"} for diagnostics",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
            )
        }
    }
}
