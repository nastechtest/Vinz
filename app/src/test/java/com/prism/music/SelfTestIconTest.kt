package com.vinz.music

import com.vinz.music.data.CheckStatus
import com.vinz.music.ui.screens.settings.selfTestIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 1.8.7, "Is playback working?": a problem's row is marked apart from a pass, and every status has its own mark or shares it only with waiting. */
class SelfTestIconTest {
    @Test fun aProblemIsNotMarkedLikeAPass() {
        assertNotEquals(selfTestIcon(CheckStatus.OK), selfTestIcon(CheckStatus.PROBLEM))
        assertNotEquals(selfTestIcon(CheckStatus.SKIPPED), selfTestIcon(CheckStatus.PROBLEM))
        assertNotEquals(selfTestIcon(CheckStatus.SKIPPED), selfTestIcon(CheckStatus.OK))
    }

    @Test fun waitingAndRunningShareTheirMarkAndNoResultUsesIt() {
        assertEquals(selfTestIcon(CheckStatus.WAITING), selfTestIcon(CheckStatus.RUNNING))
        for (done in listOf(CheckStatus.OK, CheckStatus.PROBLEM, CheckStatus.SKIPPED)) {
            assertNotEquals(selfTestIcon(CheckStatus.RUNNING), selfTestIcon(done))
        }
    }
}
