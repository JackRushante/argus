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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.argus.ui.presentation.BudgetFormat

/**
 * Editor dei tetti budget globali. La dialog fa solo parse input → callback tipizzata (nessuna
 * matematica sui soldi qui). Convenzione al confine UI: 0 o campo vuoto = illimitato. Il costo si
 * inserisce in USD (vale per i provider a listino noto) e viene convertito in micro-USD via
 * [BudgetFormat.parseUsdToMicros]; il tetto TOKEN mensile vale per i provider token-only
 * (Hermes/OpenRouter/Custom) e si inserisce come intero via [BudgetFormat.parseTokens].
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

    // Vuoto = illimitato (0). Un intero non parsabile invalida solo quel campo.
    val hour = hourText.trim().ifEmpty { "0" }.toIntOrNull()
    val day = dayText.trim().ifEmpty { "0" }.toIntOrNull()
    val costMicros = BudgetFormat.parseUsdToMicros(costText)
    val tokensMonth = BudgetFormat.parseTokens(tokensText)
    val canSave = hour != null && hour >= 0 && day != null && day >= 0 &&
        costMicros != null && tokensMonth != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Limiti budget LLM") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = hourText,
                    onValueChange = { hourText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("Chiamate / ora") },
                    supportingText = { Text("0 o vuoto = illimitato") },
                )
                OutlinedTextField(
                    value = dayText,
                    onValueChange = { dayText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("Chiamate / giorno") },
                    supportingText = { Text("0 o vuoto = illimitato") },
                )
                OutlinedTextField(
                    value = costText,
                    onValueChange = { costText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = costMicros == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text("Costo / mese (USD)") },
                    supportingText = { Text("Provider a listino (OpenAI/Anthropic/Gemini) · 0 o vuoto = illimitato") },
                )
                OutlinedTextField(
                    value = tokensText,
                    onValueChange = { tokensText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = tokensMonth == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("Token / mese") },
                    supportingText = { Text("Provider token-only (Hermes/OpenRouter/Custom) · 0 o vuoto = illimitato") },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annulla") }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = {
                    onSave(hour ?: 0, day ?: 0, costMicros ?: 0L, tokensMonth ?: 0L)
                    onDismiss()
                },
            ) { Text("Salva") }
        },
    )
}
