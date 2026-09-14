package com.example.codexlimits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LimitParserTest {
    @Test fun matchesObservedWindowsAndRemainingPercent() {
        val snapshot = LimitParser.parse(
            """{"rate_limit":{"primary_window":{"used_percent":72,"limit_window_seconds":18000,"reset_at":1789390920},"secondary_window":{"used_percent":11,"limit_window_seconds":604800,"reset_at":1789977720}}}""",
            123L
        )
        assertEquals(28, snapshot.fiveHours?.remainingPercent)
        assertEquals(89, snapshot.weekly?.remainingPercent)
        assertEquals(1789390920L, snapshot.fiveHours?.resetAtSeconds)
        assertEquals(1789977720L, snapshot.weekly?.resetAtSeconds)
        assertEquals(123L, snapshot.fetchedAtMillis)
    }

    @Test fun selectsExplicitCodexBucketAndMissingWeeklyIsUnavailable() {
        val snapshot = LimitParser.parse(
            """{"rate_limit":{"primary_window":{"used_percent":1,"limit_window_seconds":18000,"reset_at":100}},"additional_rate_limits":{"codex_other":{"primary_window":{"used_percent":80,"limit_window_seconds":18000,"reset_at":200}},"codex":{"limit_id":"codex","primary_window":{"used_percent":35,"limit_window_seconds":18000,"reset_at":300}}}}"""
        )
        assertEquals("codex", snapshot.bucketId)
        assertEquals(65, snapshot.fiveHours?.remainingPercent)
        assertEquals(300L, snapshot.fiveHours?.resetAtSeconds)
        assertNull(snapshot.weekly)
    }

    @Test fun ignoresWrongDurationAndAcceptsDocumentedMinuteFields() {
        val snapshot = LimitParser.parse(
            """{"rateLimitsByLimitId":{"codex":{"limitId":"codex","primary":{"usedPercent":25,"windowDurationMins":15,"resetsAt":100},"secondary":{"usedPercent":40,"windowDurationMins":10080,"resetsAt":200}}}}"""
        )
        assertNull(snapshot.fiveHours)
        assertEquals(60, snapshot.weekly?.remainingPercent)
    }
}
