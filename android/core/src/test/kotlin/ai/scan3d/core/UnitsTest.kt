package ai.scan3d.core

import ai.scan3d.core.units.LengthUnit
import ai.scan3d.core.units.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class UnitsTest {
    @Test fun formats() {
        assertEquals("120 mm", Units.format(0.1202, LengthUnit.MM, Locale.US))
        assertEquals("8.1 mm", Units.format(0.00805, LengthUnit.MM, Locale.US))
        assertEquals("12.0 cm", Units.format(0.1202, LengthUnit.CM, Locale.US))
        assertEquals("0.120 m", Units.format(0.1202, LengthUnit.M, Locale.US))
        assertEquals("0.50 ×", Units.formatRelative(1.0, 2.0, Locale.US))
    }

    @Test fun parses() {
        assertEquals(0.0125, Units.parse("12,5", LengthUnit.MM)!!, 1e-12)
        assertEquals(0.5, Units.parse("50", LengthUnit.CM)!!, 1e-12)
        assertNull(Units.parse("-3", LengthUnit.MM))
        assertNull(Units.parse("abc", LengthUnit.MM))
    }
}
