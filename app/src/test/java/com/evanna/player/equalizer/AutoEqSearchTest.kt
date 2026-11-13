package com.evanna.player.equalizer

import org.junit.Assert.*
import org.junit.Test

class AutoEqSearchTest {
    // Lines in the layout of AutoEq's results/INDEX.md.
    private val index = """
        # Index
        - [1MORE Aero (ANC Off)](./HypetheSonics/GRAS%20RA0045%20in-ear/1MORE%20Aero%20(ANC%20Off)) by HypetheSonics on GRAS RA0045
        - [Sennheiser HD 600](./oratory1990/over-ear/Sennheiser%20HD%20600) by oratory1990
        - [Sennheiser HD 600](./crinacle/GRAS%2043AG-7%20over-ear/Sennheiser%20HD%20600) by crinacle on GRAS 43AG-7
        - [Sennheiser HD 6XX](./oratory1990/over-ear/Sennheiser%20HD%206XX) by oratory1990
        - [Bad](./../../etc/passwd) by nobody
        - [Bad 2](./a/b?x=1/c) by nobody
        - [Sennheiser HD 600](./oratory1990/over-ear/Sennheiser%20HD%20600) by oratory1990
    """.trimIndent()

    @Test fun readsTheIndexAndDropsUnsafeOrRepeatedPaths() {
        val entries = parseAutoEqIndex(index)
        assertEquals(4, entries.size)
        assertEquals(AutoEqEntry("1MORE Aero (ANC Off)", "HypetheSonics/GRAS%20RA0045%20in-ear/1MORE%20Aero%20(ANC%20Off)",
            "HypetheSonics on GRAS RA0045"), entries[0])
    }

    @Test fun searchMatchesEveryWordAndPutsNameStartsFirst() {
        val entries = parseAutoEqIndex(index)
        assertEquals(listOf("Sennheiser HD 600", "Sennheiser HD 600"), searchAutoEq(entries, "hd 600").map { it.name })
        assertEquals(listOf("crinacle on GRAS 43AG-7"), searchAutoEq(entries, "600 crinacle").map { it.source })
        assertEquals(3, searchAutoEq(entries, "sennheiser").size)
        assertTrue(searchAutoEq(entries, "  ").isEmpty())
        assertEquals(1, searchAutoEq(entries, "sennheiser", limit = 1).size)
    }

    @Test fun profileUrlPointsAtTheParametricFile() {
        assertEquals("https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/oratory1990/over-ear/Sennheiser%20HD%20600/Sennheiser%20HD%20600%20ParametricEQ.txt",
            autoEqProfileUrl(AutoEqEntry("Sennheiser HD 600", "oratory1990/over-ear/Sennheiser%20HD%20600", "oratory1990")))
    }
}
