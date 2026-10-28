package ai.scan3d.core.units

import java.util.Locale
import kotlin.math.abs

enum class LengthUnit(val symbol: String, val perMetre: Double) {
    MM("mm", 1000.0), CM("cm", 100.0), M("m", 1.0);

    fun fromMetres(m: Double) = m * perMetre
    fun toMetres(v: Double) = v / perMetre

    companion object {
        fun parse(s: String?): LengthUnit = entries.firstOrNull { it.name.equals(s, true) || it.symbol == s } ?: MM
    }
}

object Units {
    /** Formats a length in metres with a precision that does not overstate photogrammetric accuracy. */
    fun format(metres: Double, unit: LengthUnit, locale: Locale = Locale.getDefault()): String {
        val v = unit.fromMetres(metres)
        val decimals = when (unit) {
            LengthUnit.MM -> if (abs(v) < 10) 1 else 0
            LengthUnit.CM -> if (abs(v) < 10) 2 else 1
            LengthUnit.M -> if (abs(v) < 1) 3 else 2
        }
        return String.format(locale, "%.${decimals}f %s", v, unit.symbol)
    }

    /** Relative dimensions for uncalibrated scans: proportions only, longest side = 1.00. */
    fun formatRelative(value: Double, longest: Double, locale: Locale = Locale.getDefault()): String =
        if (longest <= 0) "–" else String.format(locale, "%.2f ×", value / longest)

    /** Parses user input like "12.5", "12,5". Returns metres or null. */
    fun parse(text: String, unit: LengthUnit): Double? {
        val v = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (v <= 0 || v.isNaN() || v.isInfinite()) return null
        return unit.toMetres(v)
    }
}
