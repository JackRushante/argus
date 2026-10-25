package dev.argus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.argus.ui.R
import dev.argus.ui.presentation.BudgetFormat
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Parsing rigoroso dei campi della dialog. Convenzione al confine UI: campo vuoto = 0 = illimitato;
 * qualunque testo non numerico (anche «NaN», «Infinity», «1e5», segni) è `null` = errore, mai
 * «illimitato». Solo cifre ASCII; per il costo anche UN separatore decimale (virgola o punto).
 */
internal object BudgetLimitsInput {
    private val digits = Regex("[0-9]+")
    private val amount = Regex("[0-9]+(?:[.,][0-9]*)?|[.,][0-9]+")
    private const val MICROS_PER_UNIT = 1_000_000L

    /** Chiamate/ora e /giorno: intero >= 0 che sta in Int. */
    fun parseCount(text: String): Int? {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return 0
        if (!digits.matches(cleaned)) return null
        return cleaned.toIntOrNull()
    }

    /** Token/mese: intero >= 0 che sta in Long. */
    fun parseTokens(text: String): Long? {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return 0L
        if (!digits.matches(cleaned)) return null
        return cleaned.toLongOrNull()
    }

    /** USD con al più un separatore decimale -> micro-USD (arrotondati); fuori da Long -> null. */
    fun parseCostMicros(text: String): Long? {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return 0L
        if (!amount.matches(cleaned)) return null
        val normalized = cleaned.replace(',', '.').let { if (it.startsWith('.')) "0$it" else it }
        val micros = BigDecimal(normalized)
            .multiply(BigDecimal.valueOf(MICROS_PER_UNIT))
            .setScale(0, RoundingMode.HALF_UP)
        return if (micros > BigDecimal.valueOf(Long.MAX_VALUE)) null else micros.toLong()
    }
}

/**
 * Editor dei tetti budget globali. La dialog fa solo parse input → callback tipizzata unica
 * (nessuna matematica sui soldi qui). Convenzione al confine UI: 0 o campo vuoto = illimitato. Il
 * costo si inserisce in USD (vale per i provider a listino noto) e viene convertito in micro-USD
 * da [BudgetLimitsInput.parseCostMicros]; il tetto TOKEN mensile vale per i provider token-only
 * (Hermes/OpenRouter/Custom) e si inserisce come intero. Un campo non valido mostra l'errore e
 * blocca il salvataggio: mai un testo sbagliato silenziosamente trasformato in «illimitato».
 */
@Composable
fun BudgetLimitsDialog(
    initialMaxPerHour: Int?,
    initialMaxPerDay: Int?,
    initialMaxCostMonthMicros: Long?,
    initialMaxTokensMonth: Long?,
    onDismiss: () -> Unit,
    onSave: (maxPerHour: Int, maxPerDay: Int, maxCostMonthMicros: Long, maxTokensMonth: Long) -> Unit,
) {
    var hourText by rememberSaveable { mutableStateOf(initialMaxPerHour?.takeIf { it > 0 }?.toString() ?: "") }
    var dayText by rememberSaveable { mutableStateOf(initialMaxPerDay?.takeIf { it > 0 }?.toString() ?: "") }
    var costText by rememberSaveable {
        mutableStateOf(
            initialMaxCostMonthMicros?.takeIf { it > 0 }?.let { BudgetFormat.usdLabel(it).removePrefix("$") } ?: "",
        )
    }
    var tokensText by rememberSaveable {
        mutableStateOf(initialMaxTokensMonth?.takeIf { it > 0 }?.toString() ?: "")
    }

    // Vuoto = illimitato (0). Un testo non valido invalida solo quel campo.
    val hour = BudgetLimitsInput.parseCount(hourText)
    val day = BudgetLimitsInput.parseCount(dayText)
    val costMicros = BudgetLimitsInput.parseCostMicros(costText)
    val tokensMonth = BudgetLimitsInput.parseTokens(tokensText)
    val canSave = hour != null && day != null && costMicros != null && tokensMonth != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.budget_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = hourText,
                    onValueChange = { hourText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = hour == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.budget_calls_hour_label)) },
                    supportingText = {
                        Text(
                            stringResource(
                                if (hour == null) R.string.budget_error_integer else R.string.budget_unlimited_hint,
                            ),
                        )
                    },
                )
                OutlinedTextField(
                    value = dayText,
                    onValueChange = { dayText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = day == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.budget_calls_day_label)) },
                    supportingText = {
                        Text(
                            stringResource(
                                if (day == null) R.string.budget_error_integer else R.string.budget_unlimited_hint,
                            ),
                        )
                    },
                )
                OutlinedTextField(
                    value = costText,
                    onValueChange = { costText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = costMicros == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text(stringResource(R.string.budget_cost_label)) },
                    supportingText = {
                        Text(
                            stringResource(
                                if (costMicros == null) R.string.budget_error_cost else R.string.budget_cost_hint,
                            ),
                        )
                    },
                )
                OutlinedTextField(
                    value = tokensText,
                    onValueChange = { tokensText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = tokensMonth == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.budget_tokens_label)) },
                    supportingText = {
                        Text(
                            stringResource(
                                if (tokensMonth == null) R.string.budget_error_integer else R.string.budget_tokens_hint,
                            ),
                        )
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = {
                    // canSave garantisce che nessun campo sia null: niente fallback silenzioso a 0.
                    if (hour != null && day != null && costMicros != null && tokensMonth != null) {
                        onSave(hour, day, costMicros, tokensMonth)
                        onDismiss()
                    }
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
    )
}
