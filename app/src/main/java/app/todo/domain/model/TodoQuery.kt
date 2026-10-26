package app.todo.domain.model

import app.todo.core.time.TimeText

enum class HomeMode { ALL, TODAY, URGENT }

data class HomeFilter(
    val mode: HomeMode = HomeMode.ALL,
    val listId: String? = null,
)

/**
 * Pure query used by the home screen (and unit tested): deletion, completion,
 * list and mode filtering happen here, ordering is delegated to [TodoOrdering].
 */
object TodoQuery {

    fun visible(
        items: List<TodoItem>,
        filter: HomeFilter,
        showDone: Boolean,
        sort: TodoSort,
        now: Long,
    ): List<TodoItem> {
        val endOfToday = TimeText.endOfDay(now)
        val filtered = items.filter { item ->
            when {
                item.isDeleted -> false
                !showDone && item.done -> false
                filter.listId != null && item.listId != filter.listId -> false
                filter.mode == HomeMode.TODAY -> item.dueAt != null && item.dueAt <= endOfToday
                filter.mode == HomeMode.URGENT -> item.priority == Priority.URGENT
                else -> true
            }
        }
        return TodoOrdering.sort(filtered, sort)
    }
}
