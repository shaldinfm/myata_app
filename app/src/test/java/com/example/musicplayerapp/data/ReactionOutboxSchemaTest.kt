package com.example.musicplayerapp.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The migration SQL against the schema Room actually exported.
 *
 * A migration builds a table by hand and Room validates it on the next open, so the
 * two have to say the same thing character for character. The instrumentation test
 * proves that on a device; this proves it in `./gradlew test`, with no emulator,
 * which is where the mistake is cheap to find.
 *
 * The mistake it catches is not a typo - it is the ordinary one: somebody adds a
 * column to [ReactionOutboxEntry], the exported schema follows because KSP rewrites
 * it, and the migration does not, because nothing forces it to. Then the app builds,
 * ships, and refuses to open on every phone that upgrades.
 *
 * Reading the exported JSON as text rather than parsing it keeps this test free of a
 * JSON dependency; the `createSql` strings are unambiguous enough to find that way.
 */
class ReactionOutboxSchemaTest {

    /** Unit tests run with the module directory as the working directory. */
    private val exported = File("schemas/com.example.musicplayerapp.data.AppDatabase/3.json")

    private fun schemaText(): String {
        assertTrue(
            "no exported schema at ${exported.absolutePath} - has version 3 been built?",
            exported.isFile,
        )
        return exported.readText()
    }

    /**
     * Every `createSql` in the export, with Room's `${TABLE_NAME}` placeholder
     * already resolved to the name it stands for.
     */
    private fun createStatements(): List<String> = schemaText()
        .split("\"createSql\": \"")
        .drop(1)
        .map { it.substringBefore("\"") }
        .map { it.replace("\${TABLE_NAME}", tableOf(it)) }

    /**
     * Which table a statement belongs to. The export writes the name as a
     * placeholder, so it has to come from the columns - which is fine, because the
     * two tables share no column layout.
     */
    private fun tableOf(createSql: String): String = when {
        "`event_id`" in createSql || "reaction_outbox" in createSql -> "reaction_outbox"
        else -> "track_reaction"
    }

    @Test
    fun the_migration_creates_exactly_the_table_room_expects() {
        val expected = createStatements().single { it.startsWith("CREATE TABLE") && "`event_id`" in it }

        assertEquals(expected, ReactionMigration.CREATE_REACTION_OUTBOX)
    }

    @Test
    fun the_migration_creates_exactly_the_index_room_expects() {
        val expected = createStatements().single { it.startsWith("CREATE INDEX") }

        assertEquals(expected, ReactionMigration.CREATE_REACTION_OUTBOX_INDEX)
    }

    @Test
    fun the_exported_schema_is_version_three_and_still_has_the_reaction_table() {
        val text = schemaText()
        assertTrue(text.contains("\"version\": 3"))
        assertTrue(text.contains("\"tableName\": \"track_reaction\""))
        assertTrue(text.contains("\"tableName\": \"reaction_outbox\""))
    }
}
