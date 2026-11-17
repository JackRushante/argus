package dev.argus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.argus.ui.R
import dev.argus.ui.components.ArgusButton
import dev.argus.ui.components.ArgusCard
import dev.argus.ui.components.ArgusDetailTopBar
import dev.argus.ui.components.ArgusSegmentedFilter
import dev.argus.ui.components.ButtonStyle
import dev.argus.ui.components.CloudTag
import dev.argus.ui.components.GenerativeTag
import dev.argus.ui.components.ImportTextSheet
import dev.argus.ui.components.OutlineChip
import dev.argus.ui.components.TriggerContainer
import dev.argus.ui.components.TriggerTone
import dev.argus.ui.components.triggerShapeFor
import dev.argus.ui.model.TemplateBlankUi
import dev.argus.ui.model.TemplateCardUi
import dev.argus.ui.model.TemplateCategoryUi
import dev.argus.ui.model.TemplateGalleryCallbacks
import dev.argus.ui.model.TemplateGalleryState
import dev.argus.ui.model.TemplateNeedUi
import dev.argus.ui.model.iconFor
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// 0.7.2 · Galleria dei modelli (tavola «Modelli»), raggiunta da Automazioni. Ogni modello è una regola pronta
// che entra come bozza importata: si legge e si approva come tutte le altre (invariante 1). La card dice cosa
// serve (Shizuku o root, l'AI, un permesso) e cosa resta da completare; una regola generativa porta sempre la
// coppia di chip «generativa» + «esce verso il cloud» (invariante 5). Stateless: (state, callbacks).
// =============================================================================

/** I modelli della categoria scelta, nell'ordine del catalogo; `null` = tutti. */
internal fun visibleTemplates(state: TemplateGalleryState): List<TemplateCardUi> =
    state.filter?.let { category -> state.templates.filter { it.category == category } } ?: state.templates

/** Le categorie offerte dai chip: solo quelle che hanno almeno un modello, nell'ordine fisso. */
internal fun templateFilters(state: TemplateGalleryState): List<TemplateCategoryUi?> =
    listOf<TemplateCategoryUi?>(null) + TemplateCategoryUi.entries.filter { category ->
        state.templates.any { it.category == category }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplateGalleryScreen(
    state: TemplateGalleryState,
    callbacks: TemplateGalleryCallbacks,
    modifier: Modifier = Modifier,
) {
    var showImport by rememberSaveable { mutableStateOf(false) }
    Surface(color = ArgusTheme.surfaces.page, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ArgusDetailTopBar(
                title = stringResource(R.string.list_title),
                onBack = callbacks::onBack,
                backContentDescription = stringResource(R.string.action_back),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ArgusSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Large),
            ) {
                Text(
                    stringResource(R.string.tpl_title),
                    modifier = Modifier.semantics { heading() },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    stringResource(R.string.tpl_intro),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                val filters = templateFilters(state)
                val labels = mapOf(
                    null to stringResource(R.string.tpl_filter_all),
                    TemplateCategoryUi.HOME to stringResource(R.string.tpl_filter_home),
                    TemplateCategoryUi.WORK to stringResource(R.string.tpl_filter_work),
                    TemplateCategoryUi.BATTERY to stringResource(R.string.tpl_filter_battery),
                    TemplateCategoryUi.MESSAGES to stringResource(R.string.tpl_filter_messages),
                )
                if (filters.size > 1) {
                    ArgusSegmentedFilter(
                        options = filters,
                        selected = state.filter,
                        onSelect = callbacks::onFilter,
                        label = { labels.getValue(it) },
                        groupDescription = stringResource(R.string.tpl_filter_cd),
                        contentPadding = PaddingValues(0.dp),
                    )
                }
                when {
                    state.loading -> Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    state.templates.isEmpty() -> Text(
                        stringResource(R.string.tpl_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium)) {
                        visibleTemplates(state).forEach { template ->
                            TemplateCard(template, onUse = { callbacks.onUseTemplate(template.id) })
                        }
                    }
                }
                ImportRow(onClick = { showImport = true })
                Spacer(Modifier.height(ArgusSpacing.Section))
            }
        }
    }
    if (showImport) {
        ImportTextSheet(
            preview = state.textPreview,
            onTextChange = callbacks::onImportTextChange,
            onConfirm = { text ->
                showImport = false
                callbacks.onImportText(text)
            },
            onDismiss = {
                showImport = false
                callbacks.onImportTextChange("")
            },
            onPickFile = {
                showImport = false
                callbacks.onImportTextChange("")
                callbacks.onImportFile()
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateCard(template: TemplateCardUi, onUse: () -> Unit) {
    ArgusCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium), verticalAlignment = Alignment.CenterVertically) {
                TriggerContainer(iconFor(template.triggerIconKey), triggerShapeFor(template.triggerIconKey), TriggerTone.Normal)
                Text(
                    template.title,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                template.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            val hasChips = template.isGenerative || template.needs.isNotEmpty() || template.blanks.isNotEmpty()
            if (hasChips) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Invariante 5: generativa ⇒ «generativa» + «esce verso il cloud», sempre in coppia.
                    if (template.isGenerative) {
                        GenerativeTag()
                        CloudTag()
                    }
                    template.needs.forEach { need -> OutlineChip(needIcon(need), stringResource(needText(need))) }
                    template.blanks.forEach { blank -> OutlineChip(blankIcon(blank), stringResource(blankText(blank))) }
                }
            }
            ArgusButton(
                text = stringResource(R.string.tpl_use),
                onClick = onUse,
                style = ButtonStyle.Tonal,
                fillWidth = true,
            )
        }
    }
}

@Composable
private fun ImportRow(onClick: () -> Unit) {
    ArgusCard(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = ArgusSpacing.Large, vertical = 0.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.FileDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Text(
                stringResource(R.string.tpl_import_row),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private fun needIcon(need: TemplateNeedUi): ImageVector = when (need) {
    TemplateNeedUi.PRIVILEGED -> Icons.Rounded.AdminPanelSettings
    TemplateNeedUi.DND_ACCESS -> Icons.Rounded.DoNotDisturbOn
    TemplateNeedUi.WRITE_SETTINGS -> Icons.Rounded.Settings
    TemplateNeedUi.LOCATION -> Icons.Rounded.LocationOn
    TemplateNeedUi.NOTIFICATION_ACCESS -> Icons.Rounded.NotificationsActive
    TemplateNeedUi.NOTIFICATIONS -> Icons.Rounded.Notifications
    TemplateNeedUi.SMS -> Icons.Rounded.Sms
    TemplateNeedUi.CALLS -> Icons.Rounded.Call
    TemplateNeedUi.BLUETOOTH -> Icons.Rounded.Bluetooth
    TemplateNeedUi.USAGE_ACCESS -> Icons.Rounded.BarChart
    TemplateNeedUi.ACCESSIBILITY -> Icons.Rounded.Accessibility
}

internal fun needText(need: TemplateNeedUi): Int = when (need) {
    TemplateNeedUi.PRIVILEGED -> R.string.tpl_need_privileged
    TemplateNeedUi.DND_ACCESS -> R.string.tpl_need_dnd
    TemplateNeedUi.WRITE_SETTINGS -> R.string.tpl_need_write_settings
    TemplateNeedUi.LOCATION -> R.string.tpl_need_location
    TemplateNeedUi.NOTIFICATION_ACCESS -> R.string.tpl_need_notification_access
    TemplateNeedUi.NOTIFICATIONS -> R.string.tpl_need_notifications
    TemplateNeedUi.SMS -> R.string.tpl_need_sms
    TemplateNeedUi.CALLS -> R.string.tpl_need_calls
    TemplateNeedUi.BLUETOOTH -> R.string.tpl_need_bluetooth
    TemplateNeedUi.USAGE_ACCESS -> R.string.tpl_need_usage
    TemplateNeedUi.ACCESSIBILITY -> R.string.tpl_need_accessibility
}

private fun blankIcon(blank: TemplateBlankUi): ImageVector =
    if (blank == TemplateBlankUi.CURRENT_LOCATION) Icons.Rounded.MyLocation else Icons.Rounded.Edit

internal fun blankText(blank: TemplateBlankUi): Int = when (blank) {
    TemplateBlankUi.CHAT -> R.string.tpl_blank_chat
    TemplateBlankUi.SENDER -> R.string.tpl_blank_sender
    TemplateBlankUi.PHONE_NUMBER -> R.string.tpl_blank_number
    TemplateBlankUi.NETWORK -> R.string.tpl_blank_network
    TemplateBlankUi.PLACE -> R.string.tpl_blank_place
    TemplateBlankUi.SECRET -> R.string.tpl_blank_secret
    TemplateBlankUi.CURRENT_LOCATION -> R.string.tpl_blank_current_location
}

// =============================================================================
// Preview
// =============================================================================

private object NoopGalleryCallbacks : TemplateGalleryCallbacks {
    override fun onBack() {}
    override fun onFilter(category: TemplateCategoryUi?) {}
    override fun onUseTemplate(id: String) {}
    override fun onImportFile() {}
    override fun onImportTextChange(text: String) {}
    override fun onImportText(text: String) {}
}

private val previewTemplates = listOf(
    TemplateCardUi(
        id = "battery-low-saver",
        category = TemplateCategoryUi.BATTERY,
        title = "Batteria al 20%: risparmio",
        description = "Quando la batteria scende sotto il 20% accende il risparmio energetico e abbassa la luminosità.",
        triggerIconKey = "battery",
        isGenerative = false,
        needs = listOf(TemplateNeedUi.PRIVILEGED, TemplateNeedUi.WRITE_SETTINGS),
    ),
    TemplateCardUi(
        id = "messages-ai-busy-reply",
        category = TemplateCategoryUi.MESSAGES,
        title = "Occupato? Risponde l'AI",
        description = "Per una chat WhatsApp che scegli tu: l'AI legge il nuovo messaggio e risponde che sei occupato.",
        triggerIconKey = "notification",
        isGenerative = true,
        needs = listOf(TemplateNeedUi.NOTIFICATION_ACCESS),
        blanks = listOf(TemplateBlankUi.CHAT),
    ),
    TemplateCardUi(
        id = "home-leave-wifi-off",
        category = TemplateCategoryUi.HOME,
        title = "Esco di casa: Wi-Fi spento",
        description = "Quando esci di casa spegne il Wi-Fi.",
        triggerIconKey = "geofence",
        isGenerative = false,
        needs = listOf(TemplateNeedUi.PRIVILEGED, TemplateNeedUi.LOCATION),
        blanks = listOf(TemplateBlankUi.CURRENT_LOCATION),
    ),
)

@ArgusThemePreviews
@Composable
private fun TemplateGalleryPreview() {
    ArgusTheme {
        TemplateGalleryScreen(TemplateGalleryState(templates = previewTemplates, loading = false), NoopGalleryCallbacks)
    }
}

@ArgusThemePreviews
@Composable
private fun TemplateGalleryFilteredPreview() {
    ArgusTheme {
        TemplateGalleryScreen(
            TemplateGalleryState(templates = previewTemplates, filter = TemplateCategoryUi.MESSAGES, loading = false),
            NoopGalleryCallbacks,
        )
    }
}
