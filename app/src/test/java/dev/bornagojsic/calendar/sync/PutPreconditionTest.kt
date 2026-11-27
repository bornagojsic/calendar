package dev.bornagojsic.calendar.sync

import io.ktor.http.HttpHeaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PutPreconditionTest {
    @Test fun neverUploadedCreatesOnly() {
        assertEquals("*", putPrecondition(uploaded = false, etag = null)!![HttpHeaders.IfNoneMatch])
    }

    @Test fun uploadedMatchesEtag() {
        val h = putPrecondition(uploaded = true, etag = "abc")!!
        assertEquals("\"abc\"", h[HttpHeaders.IfMatch])
        assertNull(h[HttpHeaders.IfNoneMatch])
    }

    @Test fun unknownEtagIsNeverUnconditional() {
        assertNull(putPrecondition(uploaded = true, etag = null))
    }
}
