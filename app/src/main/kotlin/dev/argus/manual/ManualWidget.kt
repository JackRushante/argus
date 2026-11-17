package dev.argus.manual

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import dev.argus.R
import dev.argus.automation.manual.ManualEntry
import dev.argus.automation.manual.ManualIntents
import dev.argus.automation.modes.ModeWidgetEntry
import kotlinx.coroutines.runBlocking

/**
 * Widget con l'elenco delle regole manuali armate, ognuna col suo pulsante (F8). Il pulsante apre il
 * trampolino non esportato con id e token di QUELLA regola (compilati dal `RemoteViewsFactory`); il
 * receiver del widget è esportato solo perché il sistema deve poterlo aggiornare e non esegue
 * nessuna regola.
 */
class ManualWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        refresh(context, manager, appWidgetIds)
    }

    companion object {
        /** Ridisegna tutti i widget Argus e ricarica la lista (regole armate o pausa cambiate). */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ManualWidgetProvider::class.java))
            if (ids.isNotEmpty()) refresh(context, manager, ids)
        }

        private fun refresh(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val paused = context.manualEntryPoint().pause().isPaused()
            ids.forEach { id -> manager.updateAppWidget(id, views(context, id, paused)) }
            manager.notifyAppWidgetViewDataChanged(ids, R.id.manual_widget_list)
        }

        private fun views(context: Context, appWidgetId: Int, paused: Boolean): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.manual_widget)
            val adapter = Intent(context, ManualWidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            // Dati unici per widget: il sistema non condivide la factory fra due widget.
            adapter.data = Uri.parse(adapter.toUri(Intent.URI_INTENT_SCHEME))
            views.setRemoteAdapter(R.id.manual_widget_list, adapter)
            views.setEmptyView(R.id.manual_widget_list, R.id.manual_widget_empty)
            views.setViewVisibility(R.id.manual_widget_note, if (paused) View.VISIBLE else View.GONE)
            // Modello per i pulsanti: esplicito verso il trampolino non esportato. FLAG_MUTABLE serve
            // perché ogni riga aggiunge (fill-in) id e token; il componente resta quello fissato qui.
            val template = PendingIntent.getActivity(
                context,
                0,
                Intent(ManualIntents.ACTION_RUN).setClassName(context.packageName, ManualIntents.RUN_ACTIVITY_CLASS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            views.setPendingIntentTemplate(R.id.manual_widget_list, template)
            return views
        }
    }
}

/** Fornisce le righe del widget: una per regola manuale armata, col suo token nel fill-in intent. */
class ManualWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
        /** 0.8: prima le modalità (quella attiva con la spunta), poi le regole manuali. */
        private sealed interface Row {
            data class Mode(val entry: ModeWidgetEntry) : Row
            data class Rule(val entry: ManualEntry) : Row
        }

        @Volatile
        private var entries: List<Row> = emptyList()

        override fun onCreate() = Unit

        // Chiamato su un thread binder: può bloccare finché ha letto modalità e regole.
        override fun onDataSetChanged() {
            val entry = context.manualEntryPoint()
            val modes = runCatching { runBlocking { entry.modes().widgetEntries() } }.getOrDefault(emptyList())
            val rules = runCatching { runBlocking { entry.catalog().entries() } }.getOrDefault(emptyList())
            entries = modes.map(Row::Mode) + rules.map(Row::Rule)
        }

        override fun onDestroy() {
            entries = emptyList()
        }

        override fun getCount(): Int = entries.size

        override fun getViewAt(position: Int): RemoteViews? {
            val row = entries.getOrNull(position) ?: return null
            return RemoteViews(context.packageName, R.layout.manual_widget_item).apply {
                when (row) {
                    is Row.Mode -> {
                        val name = row.entry.name
                        setTextViewText(
                            R.id.manual_widget_item,
                            if (row.entry.active) context.getString(R.string.mode_widget_active, name) else name,
                        )
                        // Come le regole: il pulsante porta il token dei pulsanti modalità, il trampolino lo verifica.
                        setOnClickFillInIntent(
                            R.id.manual_widget_item,
                            Intent()
                                .putExtra(ManualIntents.EXTRA_MODE, name)
                                .putExtra(ManualIntents.EXTRA_TOKEN, row.entry.token),
                        )
                    }
                    is Row.Rule -> {
                        setTextViewText(R.id.manual_widget_item, row.entry.name)
                        setOnClickFillInIntent(
                            R.id.manual_widget_item,
                            Intent()
                                .putExtra(ManualIntents.EXTRA_RULE_ID, row.entry.id)
                                .putExtra(ManualIntents.EXTRA_TOKEN, row.entry.token),
                        )
                    }
                }
            }
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 1

        override fun getItemId(position: Int): Long = when (val row = entries.getOrNull(position)) {
            null -> -1L
            is Row.Mode -> "mode:${row.entry.name}".hashCode().toLong()
            is Row.Rule -> row.entry.id.hashCode().toLong()
        }

        override fun hasStableIds(): Boolean = true
    }
}
