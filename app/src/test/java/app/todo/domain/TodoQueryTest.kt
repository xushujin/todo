package app.todo.domain

import app.todo.core.time.TimeText
import app.todo.domain.model.HomeFilter
import app.todo.domain.model.HomeMode
import app.todo.domain.model.Priority
import app.todo.domain.model.TodoItem
import app.todo.domain.model.TodoQuery
import app.todo.domain.model.TodoSort
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoQueryTest {

    private val now = TimeText.combine(LocalDate.of(2026, 3, 4), LocalTime.NOON)

    private fun at(daysFromNow: Long, hour: Int = 18): Long =
        TimeText.combine(LocalDate.of(2026, 3, 4).plusDays(daysFromNow), LocalTime.of(hour, 0))

    private fun item(
        id: String,
        priority: Priority = Priority.NORMAL,
        dueAt: Long? = null,
        done: Boolean = false,
        pinned: Boolean = false,
        deletedAt: Long? = null,
        listId: String = "inbox",
        createdAt: Long = 1_000,
    ) = TodoItem(
        id = id,
        listId = listId,
        title = id,
        notes = "",
        priority = priority,
        dueAt = dueAt,
        remindAt = null,
        reminderChannels = 0,
        pinned = pinned,
        done = done,
        doneAt = if (done) 5_000 else null,
        createdAt = createdAt,
        updatedAt = 1_000,
        deviceId = "dev-a",
        deletedAt = deletedAt,
    )

    private val items = listOf(
        item("overdue", dueAt = at(-1)),
        item("today", dueAt = at(0)),
        item("tomorrow", dueAt = at(1)),
        item("urgent", priority = Priority.URGENT),
        item("done", done = true),
        item("deleted", deletedAt = 9_000),
        item("other-list", listId = "work"),
        item("pinned", pinned = true, dueAt = at(3)),
    )

    @Test
    fun hidesCompletedAndDeletedByDefault() {
        val visible = TodoQuery.visible(items, HomeFilter(), showDone = false, sort = TodoSort.DUE, now = now)
        val ids = visible.map { it.id }

        assertTrue(ids.contains("today"))
        assertTrue(!ids.contains("done"))
        assertTrue(!ids.contains("deleted"))
    }

    @Test
    fun showsCompletedWhenAskedAndKeepsThemLast() {
        val visible = TodoQuery.visible(items, HomeFilter(), showDone = true, sort = TodoSort.DUE, now = now)
        assertEquals("done", visible.last().id)
    }

    @Test
    fun todayFilterIncludesOverdueAndTodayOnly() {
        val visible = TodoQuery.visible(
            items,
            HomeFilter(mode = HomeMode.TODAY),
            showDone = false,
            sort = TodoSort.DUE,
            now = now,
        )
        assertEquals(listOf("overdue", "today"), visible.map { it.id })
    }

    @Test
    fun urgentFilterKeepsOnlyUrgentTasks() {
        val visible = TodoQuery.visible(
            items,
            HomeFilter(mode = HomeMode.URGENT),
            showDone = false,
            sort = TodoSort.PRIORITY,
            now = now,
        )
        assertEquals(listOf("urgent"), visible.map { it.id })
    }

    @Test
    fun listFilterNarrowsToOneList() {
        val visible = TodoQuery.visible(
            items,
            HomeFilter(listId = "work"),
            showDone = false,
            sort = TodoSort.DUE,
            now = now,
        )
        assertEquals(listOf("other-list"), visible.map { it.id })
    }

    @Test
    fun pinnedTasksComeFirstAndMissingDueDatesGoLast() {
        val visible = TodoQuery.visible(items, HomeFilter(), showDone = false, sort = TodoSort.DUE, now = now)
        assertEquals("pinned", visible.first().id)
        val withoutDue = visible.filter { it.dueAt == null }.map { it.id }
        assertEquals(listOf("urgent", "other-list"), withoutDue)
        assertNull(visible.last().dueAt)
    }

    @Test
    fun prioritySortOrdersUrgentBeforeNormal() {
        val visible = TodoQuery.visible(
            items.filter { it.dueAt == null },
            HomeFilter(),
            showDone = false,
            sort = TodoSort.PRIORITY,
            now = now,
        )
        assertEquals("urgent", visible.first().id)
    }

    @Test
    fun createdSortIsNewestFirst() {
        val list = listOf(
            item("old", createdAt = 1_000),
            item("new", createdAt = 3_000),
            item("middle", createdAt = 2_000),
        )
        val visible = TodoQuery.visible(list, HomeFilter(), showDone = false, sort = TodoSort.CREATED, now = now)
        assertEquals(listOf("new", "middle", "old"), visible.map { it.id })
    }

    @Test
    fun timeHelpersCombineDateAndTime() {
        val base = at(0, hour = 9)
        val withNewTime = TimeText.withTime(base, 21, 30)
        assertEquals(21, TimeText.toLocalDateTime(withNewTime).hour)
        assertEquals(30, TimeText.toLocalDateTime(withNewTime).minute)
        assertEquals(LocalDate.of(2026, 3, 4), TimeText.toLocalDateTime(withNewTime).toLocalDate())

        val withNewDate = TimeText.withDate(base, at(5, hour = 0))
        assertEquals(LocalDate.of(2026, 3, 9), TimeText.toLocalDateTime(withNewDate).toLocalDate())
        assertEquals(9, TimeText.toLocalDateTime(withNewDate).hour)
    }

    @Test
    fun dayBoundariesCoverWholeDay() {
        val noon = at(0, hour = 12)
        assertEquals(at(0, hour = 0), TimeText.startOfDay(noon))
        assertEquals(at(0, hour = 0) + 86_400_000L - 1, TimeText.endOfDay(noon))
        assertEquals(1L, TimeText.daysBetweenDays(noon, at(1, hour = 1)))
        assertEquals(-2L, TimeText.daysBetweenDays(noon, at(-2, hour = 23)))
    }
}
