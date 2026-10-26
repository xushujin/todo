package app.todo.core.ids

import java.util.UUID

fun interface IdGenerator {
    fun newId(): String
}

object UuidIdGenerator : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}

/** Stable per-install identifier used as the last-writer tie breaker during sync. */
object DeviceIdFactory {
    fun newDeviceId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
}
