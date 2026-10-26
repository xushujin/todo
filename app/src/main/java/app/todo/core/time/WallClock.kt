package app.todo.core.time

/** Injectable clock so that merge, scheduling and tests share one notion of "now". */
fun interface WallClock {
    fun nowMillis(): Long
}

object SystemWallClock : WallClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
