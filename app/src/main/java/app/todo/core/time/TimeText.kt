package app.todo.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Date/time formatting and day arithmetic used by both the UI and the reminder
 * scheduler, so "today" means the same thing everywhere.
 */
object TimeText {

    // Formatters are built per call so a locale change is picked up immediately
    // (a static Locale.getDefault() would freeze the first locale).
    private fun formatter(pattern: String): DateTimeFormatter =
        DateTimeFormatter.ofPattern(pattern, Locale.getDefault())

    fun formatDateTime(millis: Long): String = formatter("yyyy-MM-dd HH:mm").format(toLocalDateTime(millis))

    fun formatTime(millis: Long): String = formatter("HH:mm").format(toLocalDateTime(millis))

    fun formatDate(millis: Long): String = formatter("yyyy-MM-dd").format(toLocalDateTime(millis))

    fun toLocalDateTime(millis: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())

    fun toMillis(dateTime: LocalDateTime): Long =
        dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun startOfDay(millis: Long): Long =
        toLocalDateTime(millis).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun endOfDay(millis: Long): Long = startOfDay(millis) + DAY_MILLIS - 1

    fun combine(date: LocalDate, time: LocalTime): Long =
        LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Replaces the date part of [base] (or 09:00 today when null) with [dateMillis]. */
    fun withDate(base: Long?, dateMillis: Long): Long {
        val date = toLocalDateTime(dateMillis).toLocalDate()
        val time = base?.let { toLocalDateTime(it).toLocalTime() } ?: LocalTime.of(9, 0)
        return combine(date, time)
    }

    /** Replaces the time part of [base] (or today when null). */
    fun withTime(base: Long?, hour: Int, minute: Int): Long {
        val date = base?.let { toLocalDateTime(it).toLocalDate() } ?: LocalDate.now()
        return combine(date, LocalTime.of(hour, minute))
    }

    fun daysBetweenDays(fromMillis: Long, toMillis: Long): Long =
        toLocalDateTime(toMillis).toLocalDate().toEpochDay() - toLocalDateTime(fromMillis).toLocalDate().toEpochDay()

    const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000
}
