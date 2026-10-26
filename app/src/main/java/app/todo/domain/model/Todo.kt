package app.todo.domain.model

/** Urgency of a task. The weight is the sort key, the wire name is the sync format. */
enum class Priority(val weight: Int, val wireName: String) {
    LOW(0, "low"),
    NORMAL(1, "normal"),
    HIGH(2, "high"),
    URGENT(3, "urgent"),
    ;

    companion object {
        fun fromWire(value: String?): Priority =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: NORMAL

        fun fromWeight(weight: Int): Priority =
            entries.firstOrNull { it.weight == weight } ?: NORMAL
    }
}

/** Reminder delivery channels, stored as a bit mask so a task can use both at once. */
object ReminderChannels {
    const val NONE = 0
    const val CLOCK = 1
    const val EMAIL = 2
    const val ALL = CLOCK or EMAIL

    fun has(mask: Int, channel: Int): Boolean = mask and channel != 0

    fun normalize(mask: Int): Int = mask and ALL

}

/** Common shape of everything that syncs, so merge logic exists exactly once. */
interface SyncRecord {
    val id: String
    val updatedAt: Long
    val deviceId: String
    val deletedAt: Long?
}

data class TodoList(
    override val id: String,
    val name: String,
    val colorArgb: Long,
    val sortOrder: Int,
    override val updatedAt: Long,
    override val deviceId: String,
    val createdAt: Long,
    override val deletedAt: Long? = null,
) : SyncRecord {
    val isDeleted: Boolean get() = deletedAt != null

    /** Payload comparison used by merge: identity/timestamp bookkeeping excluded. */
    fun sameContentAs(other: TodoList): Boolean =
        name == other.name &&
            colorArgb == other.colorArgb &&
            sortOrder == other.sortOrder &&
            createdAt == other.createdAt &&
            deletedAt == other.deletedAt
}

data class TodoItem(
    override val id: String,
    val listId: String,
    val title: String,
    val notes: String,
    val priority: Priority,
    val dueAt: Long?,
    val remindAt: Long?,
    val reminderChannels: Int,
    val pinned: Boolean,
    val done: Boolean,
    val doneAt: Long?,
    val createdAt: Long,
    override val updatedAt: Long,
    override val deviceId: String,
    override val deletedAt: Long? = null,
) : SyncRecord {
    val isDeleted: Boolean get() = deletedAt != null

    fun isOverdue(now: Long): Boolean = !done && dueAt != null && dueAt < now

    fun sameContentAs(other: TodoItem): Boolean =
        listId == other.listId &&
            title == other.title &&
            notes == other.notes &&
            priority == other.priority &&
            dueAt == other.dueAt &&
            remindAt == other.remindAt &&
            reminderChannels == other.reminderChannels &&
            pinned == other.pinned &&
            done == other.done &&
            doneAt == other.doneAt &&
            createdAt == other.createdAt &&
            deletedAt == other.deletedAt
}

data class SyncSnapshot(
    val lists: List<TodoList> = emptyList(),
    val items: List<TodoItem> = emptyList(),
) {
    /** Deterministic order, so two logically equal snapshots encode identically. */
    fun canonical(): SyncSnapshot = SyncSnapshot(
        lists = lists.sortedBy { it.id },
        items = items.sortedBy { it.id },
    )
}

enum class TodoSort { DUE, PRIORITY, CREATED }

/** Pure ordering used by the home screen; pinned tasks first, completed tasks last. */
object TodoOrdering {

    fun sort(items: List<TodoItem>, sort: TodoSort): List<TodoItem> = items.sortedWith(comparator(sort))

    private fun comparator(sort: TodoSort): Comparator<TodoItem> = Comparator { a, b ->
        if (a.done != b.done) return@Comparator if (a.done) 1 else -1
        if (a.pinned != b.pinned) return@Comparator if (a.pinned) -1 else 1
        when (sort) {
            TodoSort.DUE -> {
                val aDue = a.dueAt ?: Long.MAX_VALUE
                val bDue = b.dueAt ?: Long.MAX_VALUE
                if (aDue != bDue) aDue.compareTo(bDue) else comparePriority(a, b)
            }
            TodoSort.PRIORITY -> {
                val byPriority = comparePriority(a, b)
                if (byPriority != 0) byPriority else compareDue(a, b)
            }
            TodoSort.CREATED -> {
                val byCreated = b.createdAt.compareTo(a.createdAt)
                if (byCreated != 0) byCreated else a.title.compareTo(b.title)
            }
        }
    }

    private fun comparePriority(a: TodoItem, b: TodoItem): Int {
        val byWeight = b.priority.weight.compareTo(a.priority.weight)
        return if (byWeight != 0) byWeight else compareDue(a, b)
    }

    private fun compareDue(a: TodoItem, b: TodoItem): Int {
        val aDue = a.dueAt ?: Long.MAX_VALUE
        val bDue = b.dueAt ?: Long.MAX_VALUE
        if (aDue != bDue) return aDue.compareTo(bDue)
        return a.createdAt.compareTo(b.createdAt)
    }
}
