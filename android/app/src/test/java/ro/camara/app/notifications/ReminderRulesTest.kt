package ro.camara.app.notifications

import java.time.LocalDate
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test
import ro.camara.app.data.PantryItem

class ReminderRulesTest {
    @Test fun waitsUntilChosenHourAndDoesNotRepeatTheSameDay() {
        val settings = ReminderSettings()
        val before = ZonedDateTime.parse("2026-10-31T18:59:00+02:00[Europe/Bucharest]")
        val after = before.plusMinutes(2)
        assertFalse(ReminderRules.eligible(settings, before, null))
        assertTrue(ReminderRules.eligible(settings, after, null))
        assertFalse(ReminderRules.eligible(settings, after, "2026-10-31"))
    }

    @Test fun keepsLocalHourAcrossDaylightSavingChange() {
        val now = ZonedDateTime.parse("2026-10-24T19:30:00+03:00[Europe/Bucharest]")
        val next = ReminderRules.nextTime(ReminderSettings(), now)
        assertEquals(19, next.hour)
        assertEquals(LocalDate.of(2026, 10, 25), next.toLocalDate())
        assertEquals(7200, next.offset.totalSeconds)
    }

    @Test fun includesExpiredAndSoonButExcludesUndatedAndDistantItems() {
        val dates = listOf("2026-10-30", "2026-11-03", "2026-11-04", null)
        val items = dates.mapIndexed { i, date -> PantryItem("$i", "Aliment $i", 1.0, expires_on = date) }
        assertEquals(listOf("0", "1"), ReminderRules.dueItems(items, LocalDate.of(2026, 10, 31), 3).map { it.id })
    }
}
