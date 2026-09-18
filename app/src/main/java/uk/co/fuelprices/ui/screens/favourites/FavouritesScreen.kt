package uk.co.fuelprices.ui.screens.favourites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import uk.co.fuelprices.data.api.AlertSubscriptionDto
import uk.co.fuelprices.data.api.FavouriteDto
import uk.co.fuelprices.data.api.FuelTypes
import uk.co.fuelprices.ui.theme.fuelColor
import uk.co.fuelprices.ui.theme.fuelLabel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FavouritesScreen(
    onStationClick: (Int) -> Unit,
    onSignIn: () -> Unit,
    viewModel: FavouritesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Reload whenever the screen is (re)entered — e.g. returning from the auth screen after login.
    LaunchedEffect(Unit) { viewModel.load() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    if (showCreateDialog) {
        CreateAlertDialog(
            onCreate = { radius, fuel ->
                showCreateDialog = false
                viewModel.createAlertNearMe(radius, fuel)
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    var editingFuelTypeFor by remember { mutableStateOf<uk.co.fuelprices.data.api.FavouriteDto?>(null) }
    editingFuelTypeFor?.let { fav ->
        FuelTypePickerDialog(
            currentFuelType = fav.fuelType,
            onSelect = { newType ->
                editingFuelTypeFor = null
                viewModel.updateFuelType(fav, newType)
            },
            onDismiss = { editingFuelTypeFor = null },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Favourites") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            state.isLoading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            !state.isLoggedIn -> {
                LoggedOutCta(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    onSignIn = onSignIn,
                )
            }

            state.error != null -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("Error: ${state.error}", color = MaterialTheme.colorScheme.error)
                }
            }

            else -> {
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    item {
                        AreaAlertsSection(
                            alerts = state.alerts,
                            creating = state.creatingAlert,
                            onAdd = { showCreateDialog = true },
                            onDelete = viewModel::removeAlert,
                        )
                        HorizontalDivider()
                        Text(
                            "Favourite stations",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
                        )
                    }

                    if (state.favourites.isEmpty()) {
                        item {
                            Text(
                                "No favourites yet. Tap the heart icon on a station to add it here.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(16.dp, 4.dp),
                            )
                        }
                    } else {
                        items(state.favourites, key = { it.id }) { fav ->
                            SwipeToDismissBox(
                                state = rememberSwipeToDismissBoxState(
                                    confirmValueChange = { value ->
                                        if (value == SwipeToDismissBoxValue.EndToStart) {
                                            viewModel.removeFavourite(fav.id, fav.stationId)
                                            true
                                        } else false
                                    }
                                ),
                                backgroundContent = {
                                    Box(
                                        Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                        contentAlignment = Alignment.CenterEnd,
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            "Remove favourite",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                },
                                enableDismissFromStartToEnd = false,
                            ) {
                                ListItem(
                                    modifier = Modifier.clickable {
                                        viewModel.trackStationClick(fav.stationId)
                                        onStationClick(fav.stationId)
                                    },
                                    headlineContent = {
                                        Text(
                                            fav.station?.name ?: "Station #${fav.stationId}",
                                            fontWeight = FontWeight.Medium,
                                        )
                                    },
                                    supportingContent = {
                                        // A real clickable Text nested inside the row's own
                                        // Modifier.clickable — Compose (unlike SwiftUI) correctly
                                        // routes a tap to the most specific consuming node, so this
                                        // is safe alongside the row's own navigate-to-Detail click
                                        // and the bell IconButton already nested the same way.
                                        val isPending = fav.id in state.pendingUpdateIds
                                        Text(
                                            fuelLabel(fav.fuelType),
                                            textDecoration = TextDecoration.Underline,
                                            color = if (isPending) MaterialTheme.colorScheme.outline else Color.Unspecified,
                                            // Gated the same way as the bell IconButton below —
                                            // without this, reopening the picker while a previous
                                            // fuel-type/notify PATCH for this row is still in
                                            // flight would silently no-op in the ViewModel (its own
                                            // pendingUpdateIds guard) with zero feedback, since a
                                            // plain Modifier.clickable has no built-in disabled
                                            // state the way IconButton's `enabled` does.
                                            modifier = Modifier.clickable(enabled = !isPending) { editingFuelTypeFor = fav },
                                        )
                                    },
                                    leadingContent = {
                                        Icon(
                                            Icons.Default.Favorite,
                                            null,
                                            tint = fuelColor(fav.fuelType),
                                        )
                                    },
                                    trailingContent = {
                                        IconButton(
                                            onClick = { viewModel.toggleNotify(fav) },
                                            enabled = fav.id !in state.pendingUpdateIds,
                                        ) {
                                            Icon(
                                                if (fav.notifyOnDrop) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                                                if (fav.notifyOnDrop) "Mute price-drop alerts" else "Enable price-drop alerts",
                                                tint = if (fav.notifyOnDrop) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                            )
                                        }
                                    },
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoggedOutCta(modifier: Modifier, onSignIn: () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                Icons.Default.NotificationsActive,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Sign up to receive notifications of price drops in your area",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Create a free account to save favourite stations and get alerted when fuel " +
                    "prices drop near you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                Text("Sign up / Log in")
            }
        }
    }
}

@Composable
private fun AreaAlertsSection(
    alerts: List<AlertSubscriptionDto>,
    creating: Boolean,
    onAdd: () -> Unit,
    onDelete: (Int) -> Unit,
) {
    Column(Modifier.padding(16.dp)) {
        Text(
            "Area alerts",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Get notified when prices drop near a location.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(8.dp))

        alerts.forEach { alert ->
            ListItem(
                leadingContent = {
                    Icon(Icons.Default.NotificationsActive, null, tint = fuelColor(alert.fuelType))
                },
                headlineContent = {
                    Text("${fuelLabel(alert.fuelType)} within ${alert.radiusMiles.toInt()} mi")
                },
                supportingContent = {
                    Text("%.3f, %.3f".format(alert.latitude, alert.longitude))
                },
                trailingContent = {
                    IconButton(onClick = { onDelete(alert.id) }) {
                        Icon(Icons.Default.Delete, "Remove alert")
                    }
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onAdd,
            enabled = !creating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (creating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Notify me of drops near me")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateAlertDialog(
    onCreate: (radiusMiles: Double, fuelType: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var radius by remember { mutableStateOf(10f) }
    var fuel by remember { mutableStateOf("E10") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alert me near my location") },
        text = {
            Column {
                Text("Fuel type", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FuelTypes.ALL.forEach { type ->
                        FilterChip(
                            selected = fuel == type,
                            onClick = { fuel = type },
                            label = { Text(fuelLabel(type)) },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Radius: ${radius.toInt()} miles", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = radius,
                    onValueChange = { radius = it },
                    valueRange = 1f..50f,
                    steps = 48,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(radius.toDouble(), fuel) }) { Text("Create alert") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Reuses [CreateAlertDialog]'s fuel-type chip row, just without the radius slider — lets the
 *  user change which fuel type an existing favourite tracks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FuelTypePickerDialog(
    currentFuelType: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Track a different fuel type") },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FuelTypes.ALL.forEach { type ->
                    FilterChip(
                        selected = currentFuelType == type,
                        onClick = { onSelect(type) },
                        label = { Text(fuelLabel(type)) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
