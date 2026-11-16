package com.guans.widgets.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HistoryStoreTest {
    private val today = LocalDate.of(2026, 11, 16)

    @Test fun encodeRoundTrips() {
        val rates = mapOf("USD" to 1.0, "CNY" to 7.1234, "AUD" to 1.52)
        assertEquals(rates, HistoryStore.decode(HistoryStore.encode(rates)))
    }

    @Test fun keepsOnlyTheLast30Days() {
        val all = (0L..40L).associate { today.minusDays(it) to mapOf("USD" to 1.0) }
        val kept = HistoryStore.prune(all, today)
        assertEquals(RateHistory.DAYS, kept.size)
        assertEquals(today.minusDays(29), kept.keys.min())
        assertEquals(today, kept.keys.max())
    }

    @Test fun mergeKeepsCodesAlreadyStored() {
        val day = today.minusDays(1)
        val merged = HistoryStore.merge(mapOf(day to mapOf("USD" to 1.0, "CNY" to 7.1)), day, mapOf("AUD" to 1.5))
        assertEquals(mapOf("USD" to 1.0, "CNY" to 7.1, "AUD" to 1.5), merged[day])
    }

    @Test fun missingDatesAreThoseWithoutEveryCode() {
        val all = mapOf(
            today to mapOf("USD" to 1.0, "CNY" to 7.1),
            today.minusDays(1) to mapOf("USD" to 1.0),
        )
        val missing = HistoryStore.missingDates(all, setOf("USD", "CNY"), today)
        assertEquals(29, missing.size)
        assertTrue(today.minusDays(1) in missing)
        assertTrue(today !in missing)
    }

    @Test fun seriesIsTheCrossRateInDateOrder() {
        val all = mapOf(
            today to mapOf("USD" to 1.0, "CNY" to 7.2, "AUD" to 1.6),
            today.minusDays(2) to mapOf("USD" to 1.0, "CNY" to 7.0, "AUD" to 1.4),
            today.minusDays(45) to mapOf("USD" to 1.0, "CNY" to 9.0, "AUD" to 1.0),
        )
        val s = HistoryStore.series(all, "AUD/CNY", today)
        assertEquals(listOf(today.minusDays(2), today), s.map { it.date })
        assertEquals(7.0 / 1.4, s[0].rate, 1e-9)
        assertEquals(7.2 / 1.6, s[1].rate, 1e-9)
    }
}
