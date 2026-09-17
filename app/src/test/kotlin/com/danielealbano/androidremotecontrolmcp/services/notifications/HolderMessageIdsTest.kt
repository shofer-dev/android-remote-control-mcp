package com.danielealbano.androidremotecontrolmcp.services.notifications

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The id is what the OS replaces a notification row BY, so "every message gets its own" is a
 * correctness property of the posting path, not a nicety: a fixed id would silently overwrite a
 * message the holder had not read.
 */
@DisplayName("HolderMessageIds")
class HolderMessageIdsTest {
    @Test
    @DisplayName("successive ids are distinct and increasing")
    fun successiveIdsAreDistinctAndIncreasing() {
        val ids = List(100) { HolderMessageIds.next() }

        assertEquals(ids.size, ids.toSet().size, "every posted message needs its own row")
        ids.zipWithNext { a, b -> assertEquals(a + 1, b) }
    }

    @Test
    @DisplayName("ids stay clear of the fixed service and status rows")
    fun idsStayClearOfTheFixedRows() {
        val id = HolderMessageIds.next()

        assertTrue(id >= HolderMessageIds.FIRST_ID, "id $id collides with the 1001-1004 block")
    }
}
