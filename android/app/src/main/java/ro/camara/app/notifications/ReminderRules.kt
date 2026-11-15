package ro.camara.app.notifications

import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import ro.camara.app.data.PantryItem

data class ReminderSettings(val enabled: Boolean = true, val hour: Int = 19, val minute: Int = 0, val days: Int = 3, val revision: Int = 0)

object ReminderRules {
    fun dueItems(items: List<PantryItem>, today: LocalDate, days: Int): List<PantryItem> = items.filter {
        it.expires_on?.let { raw -> runCatching { LocalDate.parse(raw) <= today.plusDays(days.toLong()) }.getOrDefault(false) } ?: false
    }.sortedBy { it.expires_on }

    fun eligible(settings: ReminderSettings, now: ZonedDateTime, lastSent: String?): Boolean = settings.enabled &&
        !now.toLocalTime().isBefore(java.time.LocalTime.of(settings.hour, settings.minute)) && lastSent != now.toLocalDate().toString()

    fun nextTime(settings: ReminderSettings, now: ZonedDateTime): ZonedDateTime {
        val today = now.toLocalDate().atTime(settings.hour, settings.minute).atZone(now.zone)
        return if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(settings.hour, settings.minute).atZone(now.zone)
    }
}
