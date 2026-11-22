package com.vinz.music.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.vinz.music.R
import com.vinz.music.data.CheckStatus
import com.vinz.music.data.LiveProbe
import com.vinz.music.data.SelfTest
import com.vinz.music.data.SelfTestCheck
import com.vinz.music.data.SelfTestChecks
import com.vinz.music.data.SelfTestRow
import com.vinz.music.data.SelfTestText
import com.vinz.music.tr
import com.vinz.music.ui.theme.VinzIcons

/**
 * "Is playback working?" (1.8.7, plan §9): the sheet the Diagnostics row opens. It runs [checks] at once, one after
 * another, each check a row whose value line says how it went (and on a problem, what to do); a problem with the
 * battery has the button to Android's page for it under its row. Once every check is done, "Share the result" hands the
 * result to [share] (which adds it to what "Share diagnostics" shares) and "Check again" runs them all again. Closing the
 * sheet stops the checks.
 */
@Composable
internal fun SelfTestSheet(checks: () -> List<SelfTestCheck>, share: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf<List<SelfTestRow>>(emptyList()) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) { SelfTest(checks()).run { rows = it } }
    val done = rows.isNotEmpty() && rows.none { it.result.status == CheckStatus.WAITING || it.result.status == CheckStatus.RUNNING }
    RowsSheet(tr(R.string.selftest_title), onDismiss, info = tr(R.string.selftest_info)) {
        for (row in rows) {
            val name = row.check.title
            val problem = row.result.status == CheckStatus.PROBLEM
            // A check is a line of the result, not a control: a tap does nothing (the battery's button is its own row).
            LinkRow(name, value = SelfTestText.value(row.result), icon = selfTestIcon(row.result.status), action = true) {}
            if (row.check.key == SelfTestChecks.BATTERY && problem) {
                val allow = tr(R.string.selftest_allow_background)
                LinkRow(allow, external = true) { LiveProbe.openBatteryPage(context) }
            }
        }
        val shareTitle = tr(R.string.selftest_share)
        LinkRow(shareTitle, action = true, enabled = done) { share(SelfTestText.report(rows)) }
        val again = tr(R.string.selftest_again)
        LinkRow(again, action = true, enabled = done) { round++ }
    }
}

/**
 * The mark before a check's row, so a problem is told from a pass at a glance and not only by its words (seen on the
 * A16, 2026-11-15: "Works" and "Problem" were the same grey line).
 */
internal fun selfTestIcon(status: CheckStatus) = when (status) {
    CheckStatus.OK -> VinzIcons.Check
    CheckStatus.PROBLEM -> VinzIcons.Close
    CheckStatus.SKIPPED -> VinzIcons.Remove
    CheckStatus.WAITING, CheckStatus.RUNNING -> VinzIcons.Sync
}