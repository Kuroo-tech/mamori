package com.reed.fcmguard.execution

import android.content.Context

/**
 * Turns operation outcomes into a single user-facing line. The wording comes from
 * [OutcomeExplanations], which is also what the result dialog uses, so the notification
 * and the dashboard cannot drift apart.
 */
object OutcomeText {

    @JvmStatic
    fun repair(context: Context, outcome: RepairOutcome): String =
        OutcomeExplanations.repair(outcome).headline(context)

    @JvmStatic
    fun restore(context: Context, outcome: RestoreOutcome): String =
        OutcomeExplanations.restore(outcome).headline(context)
}
