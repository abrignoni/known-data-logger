package com.abrignoni.knowndatalogger

import androidx.compose.ui.state.ToggleableState
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckAllTest {
    private val listed = listOf("a", "b", "c")

    @Test fun noneCheckedIsOff() = assertEquals(ToggleableState.Off, checkAllState(listed, emptyList()))

    @Test fun someCheckedIsIndeterminate() = assertEquals(ToggleableState.Indeterminate, checkAllState(listed, listOf("b")))

    @Test fun allCheckedIsOn() = assertEquals(ToggleableState.On, checkAllState(listed, listOf("c", "a", "b")))

    // An app kept from before that is no longer on the launcher list does not count toward the listed ones.
    @Test fun unlistedChoiceIgnored() = assertEquals(ToggleableState.Off, checkAllState(listed, listOf("gone")))

    @Test fun emptyListIsOff() = assertEquals(ToggleableState.Off, checkAllState(emptyList(), listOf("a")))
}
