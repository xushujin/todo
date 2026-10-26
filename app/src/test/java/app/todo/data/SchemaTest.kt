package app.todo.data

import app.todo.data.db.Schema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The migration chain is data, not code: every `schema/NNNN_*.sql` resource is one
 * version, so these tests pin the contract the database layer relies on.
 */
class SchemaTest {

    @Test
    fun versionMatchesMigrationFiles() {
        assertEquals(2, Schema.version)
        assertEquals(listOf(1, 2), Schema.migrations.map { it.version })
    }

    @Test
    fun freshInstallRunsEveryMigrationInOrder() {
        val statements = Schema.statementsFor(0)
        assertTrue(statements.any { it.contains("CREATE TABLE IF NOT EXISTS todo_lists") })
        assertTrue(statements.any { it.contains("CREATE TABLE IF NOT EXISTS todo_items") })
        assertTrue(statements.any { it.contains("ADD COLUMN pinned") })
        assertEquals(7, statements.size)
    }

    @Test
    fun upgradingFromV1OnlyAppliesTheV2Migration() {
        val statements = Schema.statementsFor(1)
        assertEquals(1, statements.size)
        assertTrue(statements.single().contains("pinned"))
    }

    @Test
    fun nothingToDoWhenAlreadyCurrent() {
        assertTrue(Schema.statementsFor(Schema.version).isEmpty())
    }

    @Test
    fun migrationsNeverDestroyData() {
        Schema.statementsFor(0).forEach { statement ->
            val upper = statement.uppercase()
            assertFalse("destructive statement: $statement", upper.contains("DROP TABLE"))
            assertFalse("destructive statement: $statement", upper.contains("DROP COLUMN"))
            assertFalse("destructive statement: $statement", upper.contains("DELETE FROM"))
        }
    }

    @Test
    fun everyMigrationFileIsReadable() {
        Schema.migrations.forEach { migration ->
            assertTrue(migration.file, Schema.statements(migration.file).isNotEmpty())
        }
    }
}
