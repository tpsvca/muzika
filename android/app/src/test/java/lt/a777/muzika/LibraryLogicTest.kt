package lt.a777.muzika

import lt.a777.muzika.data.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The artist-splitting rule, which once turned "G&G Sindikatas" into "G".
 * Pure logic, so no Android runtime needed.
 */
class LibraryLogicTest {

    private fun artistOf(credit: String) =
        Track(id = "x", title = "t", artist = credit).primaryArtist

    @Test fun keepsNamesThatContainTheirOwnPunctuation() {
        assertEquals("AC/DC", artistOf("AC/DC"))
        assertEquals("G&G Sindikatas", artistOf("G&G Sindikatas"))
        assertEquals("Anti-Flag", artistOf("Anti-Flag"))
    }

    @Test fun splitsOnRealSeparators() {
        assertEquals("Eric Gales", artistOf("Eric Gales, Christone Ingram"))
        assertEquals("Queen", artistOf("Queen & David Bowie"))
        assertEquals("Jay-Z", artistOf("Jay-Z feat. Alicia Keys"))
        assertEquals("Run DMC", artistOf("Run DMC x Aerosmith"))
    }

    @Test fun handlesEmptyAndWhitespace() {
        assertEquals("", artistOf(""))
        assertEquals("", artistOf("   "))
    }
}
