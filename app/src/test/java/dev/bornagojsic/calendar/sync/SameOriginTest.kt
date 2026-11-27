package dev.bornagojsic.calendar.sync

import at.bitfire.dav4jvm.ktor.exception.DavException
import io.ktor.client.request.get
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SameOriginTest {
    private val server = Url("https://dav.example.com/remote.php/dav/")

    @Test fun sameOrigin() {
        assertTrue(sameOrigin(server, Url("https://DAV.example.com:443/calendars/me/")))
        assertFalse(sameOrigin(server, Url("http://dav.example.com/calendars/me/")))
        assertFalse(sameOrigin(server, Url("https://dav.example.com:8443/calendars/me/")))
        assertFalse(sameOrigin(server, Url("https://evil.example.com/calendars/me/")))
        assertFalse(sameOrigin(server, Url("https://dav.example.com.evil.net/")))
    }

    @Test fun clientRefusesOtherOrigins() {
        davClient("user", "password", server).use { client ->
            assertThrows(DavException::class.java) { runBlocking { client.get("https://evil.example.com/") } }
        }
    }
}
