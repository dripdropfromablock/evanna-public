package com.evanna.player.sources.plex

import com.evanna.player.network.CancelScope
import com.evanna.player.network.NetworkPolicy
import org.junit.Assert.*
import org.junit.Test

class PlexSafetyTest {
    @Test fun originalPartNeverCarriesCredentialsOrChangesOrigin() {
        val policy=NetworkPolicy("https://music.example:32400")
        assertEquals("https://music.example:32400/library/parts/12/34/file.flac",
            PlexClient.partUrl(policy,"/library/parts/12/34/file.flac").toString())
        for(key in listOf("https://other.example/library/parts/12/file.flac","//other.example/parts/12",
            "/library/parts/12/file.flac?X-Plex-Token=secret","/library/parts/../metadata/1",
            "/library/parts/12/%2e%2e/file.flac","/library/parts/12/file.flac#fragment")) {
            assertTrue(key,runCatching {PlexClient.partUrl(policy,key)}.isFailure)
        }
    }
    @Test fun serverConnectionsRequireBareApprovedOrigins() {
        assertEquals("https://music.example/",PlexClient.connectionUrl("https://music.example",false))
        for(url in listOf("https://user:secret@music.example","https://music.example/path",
            "https://music.example/?token=secret","https://music.example/#fragment","http://music.example")) {
            assertTrue(runCatching {PlexClient.connectionUrl(url,false)}.isFailure)
        }
    }
    @Test fun authAndRedirectErrorsAreExplicitAndDoNotIncludeResponseSecrets() {
        assertEquals("CONNECTION_LOGIN_REQUIRED",runCatching {plexStatus(401)}.exceptionOrNull()?.message)
        assertEquals("CONNECTION_LOGIN_REQUIRED",runCatching {plexStatus(403)}.exceptionOrNull()?.message)
        assertEquals("PLEX_REDIRECT_NOT_ALLOWED",runCatching {plexStatus(302)}.exceptionOrNull()?.message)
        assertTrue(runCatching {plexHeader("token\r\nLeak: secret")}.isFailure)
        assertTrue(runCatching {plexId("../other")}.isFailure)
    }
    @Test fun alreadyCancelledLoginNeverMakesARequest() {
        val cancel=CancelScope().apply {cancel()}
        assertEquals("SOURCE_CANCELLED",runCatching {PlexAuth().createPin("client",cancel)}.exceptionOrNull()?.message)
    }
    @Test fun deeplyNestedServerJsonIsRejectedBeforeParsing() {
        assertTrue(runCatching {plexJsonDepth("[".repeat(33)+"]".repeat(33))}.isFailure)
        plexJsonDepth("{\"title\":\"[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[\"}")
    }
}
