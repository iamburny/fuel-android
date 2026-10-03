package uk.co.fuelprices.ui.screens.detail

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import uk.co.fuelprices.data.api.FuelTypes
import uk.co.fuelprices.data.api.PublicRatingDto
import uk.co.fuelprices.data.api.RatingSummaryDto
import uk.co.fuelprices.data.api.StationDto
import uk.co.fuelprices.ui.components.AccuracyWarningAmber
import uk.co.fuelprices.ui.theme.fuelLabel

private val MatchGreen = Color(0xFF16A34A)

/**
 * Driver reports for one station: the score, how often drivers found the pump price matched, and
 * their moderated comments. All of it comes from signed-in drivers, never from the Fuel Finder
 * data, and the section says so. Renders nothing while the shared.station-ratings flag is off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StationRatingsSection(
    station: StationDto,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StationRatingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(station.prices) {
        val listed = station.prices.map { it.fuelType }.distinct()
        viewModel.setStationFuelTypes(FuelTypes.ALL.filter { it in listed } + listed.filter { it !in FuelTypes.ALL })
    }
    LifecycleResumeEffect(Unit) {
        viewModel.refreshUserState()
        onPauseOrDispose {}
    }
    LaunchedEffect(state.signInRequested) {
        if (state.signInRequested) {
            viewModel.consumeSignInRequest()
            onSignIn()
        }
    }

    if (!state.enabled) return

    Column(modifier.padding(16.dp, 12.dp, 16.dp, 12.dp)) {
        Text("Driver reports", style = MaterialTheme.typography.titleMedium)
        Text(
            "From signed-in drivers who used this station. Not part of the official Fuel Finder price data.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        val summary = station.ratingSummary
        if (summary != null) {
            RatingSummaryCard(summary)
        } else {
            Text(
                noScoreMessage(station.ratingMinRaters),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Rating itself starts from the button beside "Get directions"; this shows where the
        // user's own rating stands.
        Spacer(Modifier.height(12.dp))
        ownRatingStatus(state.mine)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        state.visibleComments.forEach { rating ->
            Spacer(Modifier.height(12.dp))
            RatingItem(
                rating = rating,
                busy = rating.id in state.pendingCommentIds,
                message = state.commentMessages[rating.id],
                isOwn = rating.id == state.mine?.rating?.id,
                onReport = { viewModel.startReport(rating.id) },
                onHide = { viewModel.hideReviewer(rating.id) },
            )
        }

        val hidden = state.hiddenComments.size
        if (hidden > 0) {
            FlowRow(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(
                    "$hidden comment${if (hidden == 1) "" else "s"} from reviewers you've hidden.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                TextButton(onClick = { viewModel.showHiddenReviewers() }) { Text("Show them again") }
            }
        }

        if (state.comments.size < state.total) {
            OutlinedButton(
                onClick = { viewModel.loadMore() },
                enabled = !state.loadingMore,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(if (state.loadingMore) "Loading…" else "Show more comments")
            }
        }
    }
    HorizontalDivider()

    state.reportingCommentId?.let { id ->
        ReportDialog(onSend = { reason -> viewModel.report(id, reason) }, onDismiss = { viewModel.cancelReport() })
    }

    if (state.sheet != null) {
        RateStationSheet(stationName = station.name, state = state, viewModel = viewModel)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RatingSummaryCard(summary: RatingSummaryDto) {
    Card(Modifier.fillMaxWidth()) {
        FlowRow(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SummaryFigure(
                figure = "%.1f %s".format(summary.avgStars, starString(summary.avgStars)),
                figureDescription = "%.1f out of 5 stars".format(summary.avgStars),
                caption = "Average from ${driverCount(summary.raterCount)}",
            )
            summary.priceMatchPct?.let { pct ->
                SummaryFigure(
                    figure = "$pct%",
                    caption = if (summary.priceCheckCount < summary.raterCount) {
                        "of the ${summary.priceCheckCount} who bought fuel found the pump price matched"
                    } else "found the pump price matched",
                )
            }
            val gap = summary.avgGapPence
            if (gap != null && gap != 0.0) {
                SummaryFigure(
                    figure = signedPence(gap),
                    caption = "on average when it didn't (${gapPhrase(gap)})",
                )
            }
        }
    }
}

@Composable
private fun SummaryFigure(figure: String, caption: String, figureDescription: String? = null) {
    Column {
        Text(
            figure,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = if (figureDescription != null) {
                Modifier.clearAndSetSemantics { contentDescription = figureDescription }
            } else Modifier,
        )
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RatingItem(
    rating: PublicRatingDto,
    busy: Boolean,
    message: String?,
    /** The viewer's own comment, which they can't report or hide. */
    isOwn: Boolean,
    onReport: () -> Unit,
    onHide: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    starString(rating.stars.toDouble()),
                    color = AccuracyWarningAmber,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .clearAndSetSemantics { contentDescription = "${rating.stars} out of 5 stars" },
                )
                rating.fuelType?.let { fuel ->
                    Text(
                        fuelLabel(fuel),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
                rating.priceMatched?.let { matched ->
                    val tint = if (matched) MatchGreen else AccuracyWarningAmber
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = tint.copy(alpha = 0.15f),
                        modifier = Modifier.align(Alignment.CenterVertically),
                    ) {
                        Text(
                            priceMatchLabel(matched, rating.gapPence),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Text(
                "Verified driver · ${formatRatingDate(rating.createdAt)}" + if (rating.edited) " · edited" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            rating.comment?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            }
            if (isOwn) {
                Text(
                    "Your comment",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                FlowRow {
                    TextButton(onClick = onReport, enabled = !busy) { Text("Report") }
                    TextButton(onClick = onHide, enabled = !busy) { Text("Hide comments from this reviewer") }
                }
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReportDialog(onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf(REPORT_REASONS.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Why are you reporting this?") },
        text = {
            Column(Modifier.selectableGroup()) {
                REPORT_REASONS.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = reason == option, onClick = { reason = option }, role = Role.RadioButton)
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == option, onClick = null)
                        Text(option, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSend(reason) }) { Text("Send report") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RateStationSheet(stationName: String, state: StationRatingsUiState, viewModel: StationRatingsViewModel) {
    val sheet = state.sheet ?: return
    val mode = state.sheetMode ?: return
    val editing = mode is RateSheetMode.Form && mode.existing != null

    ModalBottomSheet(
        onDismissRequest = { viewModel.closeSheet() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (editing) "Edit your rating" else "Rate $stationName",
                style = MaterialTheme.typography.titleLarge,
            )
            if (editing) {
                Text(stationName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            when (mode) {
                RateSheetMode.Loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                RateSheetMode.Unavailable -> {
                    Text("Ratings aren't available right now. Please try again later.")
                    SheetActions(onClose = { viewModel.closeSheet() })
                }
                is RateSheetMode.Cooldown -> {
                    Text(
                        (mode.ratedOn?.let { "You rated this station on ${formatRatingDate(it)}. " } ?: "") +
                            "You can rate it again from ${formatRatingDate(mode.canRateAt)}.",
                    )
                    SheetActions(onClose = { viewModel.closeSheet() })
                }
                RateSheetMode.VerifyEmail -> VerifyEmailPanel(sheet, viewModel)
                is RateSheetMode.Blocked -> {
                    Text(mode.message)
                    SheetActions(onClose = { viewModel.closeSheet() })
                }
                is RateSheetMode.Form -> RatingForm(sheet, mode, viewModel)
                is RateSheetMode.Saved -> {
                    Text(savedMessage(mode.rating))
                    SheetActions(onClose = { viewModel.closeSheet() }, closeLabel = "Done")
                }
            }
        }
    }
}

@Composable
private fun SheetActions(
    onClose: () -> Unit,
    closeLabel: String = "Close",
    primary: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        TextButton(onClick = onClose) { Text(closeLabel) }
        primary?.invoke()
    }
}

@Composable
private fun VerifyEmailPanel(sheet: RateSheetUiState, viewModel: StationRatingsViewModel) {
    Text(
        "To keep ratings trustworthy, we need to confirm your email address before you can rate. " +
            "We'll send you a link; open it on any device, then come back here.",
    )
    if (sheet.verifyStatus == VerifyEmailStatus.SENT) {
        Text(
            "Check your inbox (and spam folder) for the verification link. It expires in 24 hours.",
            color = MaterialTheme.colorScheme.primary,
        )
    }
    sheet.verifyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    SheetActions(onClose = { viewModel.closeSheet() }) {
        Button(
            onClick = { viewModel.sendVerificationEmail() },
            enabled = sheet.verifyStatus != VerifyEmailStatus.SENDING && sheet.verifyStatus != VerifyEmailStatus.SENT,
        ) {
            Text(
                when (sheet.verifyStatus) {
                    VerifyEmailStatus.SENDING -> "Sending…"
                    VerifyEmailStatus.SENT -> "Email sent"
                    else -> "Send verification email"
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RatingForm(sheet: RateSheetUiState, mode: RateSheetMode.Form, viewModel: StationRatingsViewModel) {
    val form = sheet.form
    // Without fuel there was no pump price to check, so that question doesn't apply.
    val checksPrice = form.fuelType != null

    Text("Which fuel did you buy?", style = MaterialTheme.typography.labelLarge)
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        form.fuelTypes.forEach { type ->
            FilterChip(
                selected = form.fuelType == type,
                onClick = { viewModel.setFuelType(type) },
                label = { Text(fuelLabel(type)) },
            )
        }
        FilterChip(
            selected = !checksPrice,
            onClick = { viewModel.setFuelType(null) },
            label = { Text("None — I didn't buy fuel") },
        )
    }

    if (checksPrice) {
        Text("Did the pump price match the price shown?", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = form.priceMatched == true,
                onClick = { viewModel.setPriceMatched(true) },
                label = { Text("Yes, it matched") },
            )
            FilterChip(
                selected = form.priceMatched == false,
                onClick = { viewModel.setPriceMatched(false) },
                label = { Text("No, it was different") },
            )
        }
    }

    if (checksPrice && form.priceMatched == false) {
        OutlinedTextField(
            value = form.paidText,
            onValueChange = { viewModel.setPaidText(it) },
            label = { Text("What did you pay per litre? (optional, in pence)") },
            placeholder = { Text("e.g. 152.9") },
            singleLine = true,
            isError = !form.paidValid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = {
                Text(
                    if (form.paidValid) "Pence per litre, as shown on the pump."
                    else "Enter a price between 50 and 400 pence.",
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Text("Overall, how would you rate this station?", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.selectableGroup()) {
        (1..5).forEach { n ->
            val filled = form.stars != null && n <= form.stars
            IconButton(
                onClick = { viewModel.setStars(n) },
                modifier = Modifier.semantics { selected = form.stars == n },
            ) {
                Icon(
                    if (filled) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (n == 1) "1 star" else "$n stars",
                    tint = AccuracyWarningAmber,
                )
            }
        }
    }

    OutlinedTextField(
        value = form.comment,
        onValueChange = { viewModel.setComment(it) },
        label = { Text("Comment (optional)") },
        placeholder = { Text("e.g. The pump charged 4p more than the sign said") },
        minLines = 3,
        supportingText = {
            Text(
                "${form.comment.length}/$RATING_COMMENT_MAX. Comments are checked before they appear. " +
                    "Please don't include names, phone numbers, number plates or links.",
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )

    if (mode.needsTerms) {
        // The whole row toggles, so the target isn't just the checkbox itself.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .toggleable(
                    value = form.termsAccepted,
                    role = Role.Checkbox,
                    onValueChange = { viewModel.setTermsAccepted(it) },
                )
                .padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Checkbox(checked = form.termsAccepted, onCheckedChange = null, modifier = Modifier.padding(12.dp))
            Text(
                buildAnnotatedString {
                    append("I agree to the ")
                    withLink(
                        LinkAnnotation.Url(
                            REVIEWS_TERMS_URL,
                            TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)),
                        ),
                    ) { append("reviews content policy") }
                    append(". My rating is my honest experience at this station.")
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    mode.existing?.let { existing ->
        Text(
            "You can edit this rating until ${formatRatingDate(existing.editableUntil)} " +
                "(${existing.editsRemaining} edit${if (existing.editsRemaining == 1) "" else "s"} left).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    sheet.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    SheetActions(onClose = { viewModel.closeSheet() }, closeLabel = "Cancel") {
        Button(onClick = { viewModel.submit() }, enabled = viewModel.canSubmit()) {
            Text(
                when {
                    sheet.submitting -> "Saving…"
                    mode.existing != null -> "Save changes"
                    else -> "Submit rating"
                },
            )
        }
    }
}
