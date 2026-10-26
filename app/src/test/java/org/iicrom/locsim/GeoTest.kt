package org.iicrom.locsim

import org.iicrom.locsim.util.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    @Test
    fun latitudeRangeIsValidated() {
        assertTrue(Geo.isValidLatitude(0.0))
        assertTrue(Geo.isValidLatitude(39.9087))
        assertTrue(Geo.isValidLatitude(-90.0))
        assertTrue(Geo.isValidLatitude(90.0))
        assertFalse(Geo.isValidLatitude(90.0001))
        assertFalse(Geo.isValidLatitude(-90.0001))
        assertFalse(Geo.isValidLatitude(Double.NaN))
        assertFalse(Geo.isValidLatitude(Double.POSITIVE_INFINITY))
    }

    @Test
    fun longitudeRangeIsValidated() {
        assertTrue(Geo.isValidLongitude(116.3975))
        assertTrue(Geo.isValidLongitude(180.0))
        assertTrue(Geo.isValidLongitude(-180.0))
        assertFalse(Geo.isValidLongitude(180.0001))
        assertFalse(Geo.isValidLongitude(-180.0001))
    }

    @Test
    fun formatsCoordinatesWithSixDigits() {
        assertEquals("39.908700, 116.397500", Geo.formatLatLng(39.9087, 116.3975))
        assertEquals("39.908700", Geo.formatLatitude(39.9087))
        assertEquals("116.397500", Geo.formatLongitude(116.3975))
    }

    @Test
    fun parsesCoordinatePairs() {
        assertEquals(39.9087 to 116.3975, Geo.parseCoordinates("39.9087, 116.3975"))
        assertEquals(39.9087 to 116.3975, Geo.parseCoordinates("39.9087，116.3975"))
        assertEquals(39.9087 to 116.3975, Geo.parseCoordinates(" 39.9087  116.3975 "))
        assertEquals(-33.8688 to 151.2093, Geo.parseCoordinates("-33.8688,151.2093"))
    }

    @Test
    fun rejectsInvalidCoordinatePairs() {
        assertNull(Geo.parseCoordinates(""))
        assertNull(Geo.parseCoordinates("39.9087"))
        assertNull(Geo.parseCoordinates("39.9087, 116.3975, 3"))
        assertNull(Geo.parseCoordinates("91, 116"))
        assertNull(Geo.parseCoordinates("39.9, 181"))
        assertNull(Geo.parseCoordinates("beijing"))
    }

    @Test
    fun guessesPlaceNameFromAddress() {
        assertEquals("北京市东城区东长安街", Geo.suggestedName("北京市东城区东长安街, 东城区, 北京市"))
        assertEquals("我的地点", Geo.suggestedName("  "))
        assertEquals("A".repeat(24), Geo.suggestedName("A".repeat(40)))
    }
}
