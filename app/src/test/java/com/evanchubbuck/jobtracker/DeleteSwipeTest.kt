package com.evanchubbuck.jobtracker

import org.junit.Assert.assertEquals
import org.junit.Test

class DeleteSwipeTest {
    @Test fun longSwipeRequiresOptIn() {
        assertEquals(DeleteSwipeAction.CONFIRM, deleteSwipeAction(1f, false))
        assertEquals(DeleteSwipeAction.DELETE, deleteSwipeAction(0.71f, true))
    }

    @Test fun exactlySeventyPercentStillAsksFirst() {
        assertEquals(DeleteSwipeAction.CONFIRM, deleteSwipeAction(0.7f, true))
        assertEquals(DeleteSwipeAction.CONFIRM, deleteSwipeAction(0.5f, true))
    }

    @Test fun shortAndRightSwipesDoNotDelete() {
        assertEquals(DeleteSwipeAction.NONE, deleteSwipeAction(0.49f, true))
        assertEquals(DeleteSwipeAction.NONE, deleteSwipeAction(-1f, true))
    }
}
