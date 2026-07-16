package uk.co.fuelprices.ui.screens.favourites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import uk.co.fuelprices.data.api.FuelTypes
import uk.co.fuelprices.ui.theme.fuelLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavouritesScreen(
    onStationClick: (Int) -> Unit,
    viewModel: FavouritesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Favourites") })
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        if (!state.isLoggedIn) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Sign in to use favourites",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Save your favourite stations and get notified about price drops.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            return@Scaffold
        }

        if (state.error != null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Error: ${state.error}", color = MaterialTheme.colorScheme.error)
            }
            return@Scaffold
        }

        if (state.favourites.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No favourites yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap the heart icon on a station to add it here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(state.favourites, key = { it.id }) { fav ->
                SwipeToDismissBox(
                    state = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                viewModel.removeFavourite(fav.id)
                                true
                            } else false
                        }
                    ),
                    backgroundContent = {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
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
                        modifier = Modifier.clickable { onStationClick(fav.stationId) },
                        headlineContent = {
                            Text("Station #${fav.stationId}", fontWeight = FontWeight.Medium)
                        },
                        supportingContent = {
                            Text(fuelLabel(fav.fuelType))
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.Favorite,
                                null,
                                tint = FuelTypes.color(fav.fuelType),
                            )
                        },
                        trailingContent = {
                            if (fav.notifyOnDrop) {
                                Text(
                                    "Alerts on",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
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
