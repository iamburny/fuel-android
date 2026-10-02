package uk.co.fuelprices.util

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.co.fuelprices.util.StationText.displayName

/** The same real feed values fuel-web's stationText tests pin, so both platforms agree. */
class StationTextTest {
    @Test
    fun `title-cases ALL-CAPS and all-lowercase names`() {
        assertEquals("Horspath Service Station Ltd", displayName("HORSPATH SERVICE STATION LTD"))
        assertEquals("Sainsburys Heyford Hill", displayName("SAINSBURYS HEYFORD HILL"))
        assertEquals("Ivor Miles Ltd", displayName("ivor miles ltd"))
    }

    @Test
    fun `leaves a name with deliberate casing alone`() {
        assertEquals("BP Yarnton", displayName("BP Yarnton"))
        assertEquals("Esso - Riffa Serviuce Station", displayName("Esso - Riffa Serviuce Station"))
    }

    @Test
    fun `keeps initialisms and codes uppercase but not brands written as words`() {
        assertEquals("MFG Cherwell", displayName("MFG CHERWELL"))
        assertEquals("EG on the Move", displayName("EG ON THE MOVE"))
        assertEquals("Asda Wheatley Superstore", displayName("ASDA WHEATLEY SUPERSTORE"))
        assertEquals("Esso A34 Northbound", displayName("ESSO A34 NORTHBOUND"))
    }

    @Test
    fun `handles hyphens, minor words, apostrophes and separators`() {
        assertEquals("Shell Co-op Cowley", displayName("SHELL CO-OP COWLEY"))
        assertEquals("Gulf-Nisa Hollinwood Service Station", displayName("GULF-NISA HOLLINWOOD SERVICE STATION"))
        assertEquals("Lakes and Dales Co-operative", displayName("LAKES AND DALES CO-OPERATIVE"))
        assertEquals("Station to Go On", displayName("STATION TO GO ON"))
        assertEquals("Tout's Nailsea", displayName("TOUT'S NAILSEA"))
        assertEquals("O'Brien Garage", displayName("O'BRIEN GARAGE"))
        assertEquals("D.J.Johnson & Sons Ltd", displayName("D.J.JOHNSON & SONS LTD"))
        assertEquals("Tout S Langford (Esso)", displayName("TOUT S LANGFORD (ESSO)"))
        assertEquals("54-56 Oxford Road", displayName("54-56 OXFORD ROAD"))
        assertEquals("1st Avenue Garage", displayName("1ST AVENUE GARAGE"))
    }

    @Test
    fun `collapses whitespace and handles empty values`() {
        assertEquals("Shell Co-op Whitemare Pool", displayName("SHELL  CO-OP WHITEMARE POOL "))
        assertEquals("", displayName(null))
        assertEquals("", displayName("   "))
    }
}
