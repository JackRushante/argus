package dev.argus.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.argus.ui.R
import dev.argus.ui.model.AppChoiceUi
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusCodeSmall
import dev.argus.ui.theme.ArgusSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

// =============================================================================
// «Cambia app»: elenco delle app installate (nome + icona) in un bottom sheet. Sceglierne una sostituisce il
// package nella bozza: la bozza cambia impronta e va riapprovata; una regola armata non si modifica mai senza
// passare dall'approvazione (il ViewModel ne fa una bozza di modifica). L'elenco resta sul telefono.
// =============================================================================

/** Voci che corrispondono alla ricerca (nome o package, senza badare alle maiuscole). Query vuota = tutte. */
internal fun filterAppChoices(choices: List<AppChoiceUi>, query: String): List<AppChoiceUi> {
    val needle = query.trim().lowercase(Locale.ROOT)
    if (needle.isEmpty()) return choices
    return choices.filter {
        it.label.lowercase(Locale.ROOT).contains(needle) || it.packageName.lowercase(Locale.ROOT).contains(needle)
    }
}

/**
 * Foglio con l'elenco delle app.
 *
 * @param choices app avviabili installate; `null` = ancora in caricamento.
 * @param currentPackage package oggi nella regola (segnato «in uso» e non scegliibile).
 * @param armedRule la regola è già armata: la nota dice che nasce una bozza di modifica.
 */
@Composable
fun ApprovalAppPickerSheet(
    choices: List<AppChoiceUi>?,
    currentPackage: String?,
    armedRule: Boolean,
    onPick: (AppChoiceUi) -> Unit,
    onDismiss: () -> Unit,
) {
    ArgusBottomSheet(onDismiss = onDismiss, title = stringResource(R.string.rda_ap_picker_title)) {
        ApprovalAppPickerContent(choices, currentPackage, armedRule, onPick)
    }
}

@Composable
internal fun ApprovalAppPickerContent(
    choices: List<AppChoiceUi>?,
    currentPackage: String?,
    armedRule: Boolean,
    onPick: (AppChoiceUi) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium)) {
        ReasonText(
            stringResource(if (armedRule) R.string.rda_ap_picker_note_armed else R.string.rda_ap_picker_note_draft),
        )
        when {
            choices == null -> Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = ArgusSpacing.Large),
                horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.rda_ap_picker_loading),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            else -> {
                ArgusTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = stringResource(R.string.rda_ap_picker_search),
                )
                val shown = filterAppChoices(choices, query)
                if (shown.isEmpty()) {
                    Text(
                        stringResource(R.string.rda_ap_picker_empty),
                        modifier = Modifier.padding(vertical = ArgusSpacing.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    // Altezza limitata: il foglio scorre già, la lista pigra deve avere un massimo finito.
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(shown, key = { it.packageName }) { choice ->
                            AppChoiceRow(choice, current = choice.packageName == currentPackage, onPick = onPick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppChoiceRow(choice: AppChoiceUi, current: Boolean, onPick: (AppChoiceUi) -> Unit) {
    val description = stringResource(R.string.rda_ap_picker_pick_cd, choice.label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                if (current) {
                    Modifier
                } else {
                    Modifier
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(role = Role.Button) { onPick(choice) }
                        .semantics { contentDescription = description }
                },
            )
            .padding(horizontal = ArgusSpacing.Small, vertical = ArgusSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
    ) {
        AppIcon(choice.packageName, choice.label)
        Column(Modifier.weight(1f)) {
            Text(choice.label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
            Text(choice.packageName, color = MaterialTheme.colorScheme.onSurfaceVariant, style = ArgusCodeSmall)
        }
        if (current) {
            Text(
                stringResource(R.string.rda_ap_picker_current),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** Icona dell'app letta dal telefono, in modo pigro; senza icona (o in anteprima) c'è l'iniziale. */
@Composable
private fun AppIcon(packageName: String, label: String, size: Dp = 40.dp) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName, px) {
        value = if (inspection) null else withContext(Dispatchers.IO) { loadAppIcon(context, packageName, px) }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(
            Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label.firstOrNull()?.uppercase() ?: "?",
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

/** L'icona può mancare (app non visibile, errore del sistema): in quel caso `null`, mai un'eccezione. */
private fun loadAppIcon(context: Context, packageName: String, sizePx: Int): ImageBitmap? = runCatching {
    val drawable = context.packageManager.getApplicationIcon(packageName)
    val side = sizePx.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, side, side)
    drawable.draw(canvas)
    bitmap.asImageBitmap()
}.getOrNull()

@ArgusThemePreviews
@Composable
private fun ApprovalAppPickerPreview() {
    val apps = listOf(
        AppChoiceUi("com.android.chrome", "Chrome"),
        AppChoiceUi("com.google.android.youtube", "YouTube"),
        AppChoiceUi("com.whatsapp", "WhatsApp"),
        AppChoiceUi("org.mozilla.firefox", "Firefox"),
    )
    ArgusPreviewSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            ApprovalAppPickerContent(apps, currentPackage = "com.google.android.youtube", armedRule = false, onPick = {})
            ApprovalAppPickerContent(null, currentPackage = null, armedRule = true, onPick = {})
        }
    }
}
