package com.madebycli.aiusage.widget

import com.madebycli.aiusage.R
import com.madebycli.aiusage.Texts
import com.madebycli.aiusage.data.*

/** What a screen reader says for a widget: the same facts the picture shows, as sentences. */
internal object WidgetDescription {
    fun describe(config: WidgetConfig, accounts: List<WidgetAccount>, now: Long): String {
        val res = Texts.resources
        return when (config.kind) {
            WidgetKind.USAGE -> accounts.joinToString(". ") { a ->
                val parts = UsageWidgetDraw.slots(a, config, now).mapNotNull { slot ->
                    slot.used?.let { Texts.get(R.string.usage_window_a11y, Texts.get(if (slot.seconds == FIVE_HOURS) R.string.window_five else R.string.window_week), formatPercent(it)) }
                }
                "${a.title}: " + parts.joinToString(", ").ifEmpty { Texts.get(R.string.usage_no_reading) }
            }
            WidgetKind.STATS -> if (accounts.isEmpty()) "" else {
                val data = StatsWidgetDraw.data(accounts, config, now)
                "${Texts.get(config.kind.label)}, ${Texts.get(config.span.label)}: ${StatsWidgetDraw.number(data.summary.total, config.metric)} ${StatsWidgetDraw.unit(data.summary.total, config.metric)}"
            }
            WidgetKind.PLAN -> if (accounts.isEmpty()) "" else {
                val plan = PlanWidgetDraw.plan(accounts, now)
                val first = plan.order.firstOrNull()?.let { id -> accounts.firstOrNull { it.id == id } }
                if (first == null) "" else "${Texts.get(R.string.plan_use_now)}: ${first.title}"
            }
            WidgetKind.RESETS -> ResetsWidgetDraw.entries(accounts, config, now).take(3).joinToString(". ") { e ->
                "${e.account.title}, ${Texts.get(if (e.window.seconds == FIVE_HOURS) R.string.window_five else R.string.window_week)}: ${Fmt.clock(res, e.at, now)}"
            }
        }
    }
}
