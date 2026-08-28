package uk.co.fuelprices.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import kotlinx.serialization.Serializable

/**
 * The `notice.viewChangeNotes` flag's variant payload — all fields optional so a partially (or
 * entirely empty) configured variant still resolves to sensible defaults per-field, rather than
 * the whole notice failing to decode. Mirrors fuel-ios's `ReleaseNoticeContent`.
 */
@Serializable
data class ReleaseNoticeContent(
    val text: String? = null,
    val buttonText: String? = null,
    val buttonUrl: String? = null,
    /** Free-form value (a date, a version tag — whatever) with no meaning beyond being this
     *  notice's dismiss identity: bump it in the variant to force the notice to show again, leave
     *  it as-is to tweak wording/URL without re-showing to everyone who already dismissed it. */
    val date: String? = null,
) {
    val resolvedText: String get() = text?.ifBlank { null } ?: DEFAULT_TEXT
    val resolvedButtonText: String get() = buttonText?.ifBlank { null } ?: DEFAULT_BUTTON_TEXT
    val resolvedButtonUrl: String get() = buttonUrl?.ifBlank { null } ?: DEFAULT_BUTTON_URL

    /**
     * Dismiss identity — driven solely by [date], not the display text/button/url, so editing
     * copy alone never re-shows a notice someone already dismissed; only bumping [date] does.
     * Falls back to a fixed constant when the variant doesn't set [date] at all.
     */
    val dismissKey: String get() = date?.ifBlank { null } ?: "default"

    companion object {
        const val FLAG_NAME = "notice.viewChangeNotesAndroid"
        const val DEFAULT_TEXT = "Latest full release notes are available on the fuel tracker website"
        const val DEFAULT_BUTTON_TEXT = "View Release Notes"
        const val DEFAULT_BUTTON_URL = "https://fueltracker.uk/release-notes"
    }
}

/**
 * Body text + single CTA button shown once per distinct [ReleaseNoticeContent], modeled directly
 * on [CoffeeSupportDialog]. [onConfirm] fires when the user taps the CTA (the caller opens the
 * URL); [onDismiss] fires for "Close" and outside-taps. Both mark the content dismissed.
 */
@Composable
fun ReleaseNoticeDialog(
    content: ReleaseNoticeContent,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What's New") },
        text = { Text(content.resolvedText) },
        confirmButton = {
            Button(onClick = onConfirm) { Text(content.resolvedButtonText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
