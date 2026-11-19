// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
// Grippy: rewritten for Grippy's four Frez-style tabs.

package run.nuri.getagrip.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/// Grippy's tabs: Home, Workout, Measure, History. Settings and Benchmarks open from Home.
class TabIconTests {

    /// **Order is the tab bar's contract.**
    @Test
    fun theFourTabsAreInTheirOrder() {
        assertEquals(listOf(Tab.Home, Tab.Workout, Tab.Measure, Tab.History), Tab.entries)
    }

    /// Outlined at rest, filled when selected.
    @Test
    fun selectionSwapsToTheFilledTwin() {
        for (tab in Tab.entries) assertNotEquals(tab.icon, tab.selectedIcon, "${tab.name} should fill when selected")
    }

    /// Four tabs, four different glyphs, in both states.
    @Test
    fun noTwoTabsShareAGlyph() {
        assertEquals(Tab.entries.size, Tab.entries.map { it.icon }.toSet().size)
        assertEquals(Tab.entries.size, Tab.entries.map { it.selectedIcon }.toSet().size)
    }

    /// The label is resolved on read; without a lookup installed `L10n.tr` echoes the key.
    @Test
    fun everyTabResolvesItsLabelOnRead() {
        assertEquals(listOf("Home", "Workout", "Measure", "History"), Tab.entries.map { it.label })
    }
}
