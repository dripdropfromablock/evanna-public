package com.evanna.player.webmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WebSiteRecipeTest {
    @Test fun catalogTermsOccupyOneEncodedQueryParameter() {
        val site = webSiteRecipe("Music site", "https://example.com/find?format=audio&q={query}", "name")
        val url = requireNotNull(site.searchUrl(CatalogRecording("id", "A & B", "Björk / Live", null, null, null)))
        assertEquals("A & B Björk / Live", url.queryParameter("q"))
        assertEquals("audio", url.queryParameter("format"))
        assertEquals(setOf("format", "q"), url.queryParameterNames)
    }

    @Test fun linkSitesUseTheBrowserPageWithoutSendingCatalogTerms() {
        val site = webSiteRecipe("Link site", "https://example.com/tools", "link")
        assertEquals("https://example.com/tools", site.address.toString())
        assertNull(site.searchUrl(CatalogRecording("id", "Song", "Artist", null, null, null)))
    }

    @Test fun namesAndTemplatesRejectUnsafeOrAmbiguousValues() {
        for (template in listOf(
            "http://example.com/search?q={query}", "https://user@example.com/?q={query}",
            "https://127.0.0.1/?q={query}", "https://home.local/?q={query}",
            "https://example.com/{query}", "https://example.com/?q=prefix{query}",
            "https://example.com/?q={query}&q=fixed", "https://example.com/?q={query}#other",
            "https://example.com/?q={query}&other={query}", "https://example.com/?q=fixed",
        )) assertThrows(template, WebSiteListError::class.java) { webSiteRecipe("Site", template, "name") }
        assertThrows(WebSiteListError::class.java) {
            webSiteRecipe("A\u202eB", "https://example.com/?q={query}", "name")
        }
        assertThrows(WebSiteListError::class.java) {
            webSiteRecipe("Site", "https://example.com/?q={query}", "link")
        }
    }
}
