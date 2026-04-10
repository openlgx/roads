package org.openlgx.roads.processing.ondevice

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.openlgx.roads.data.local.db.entity.LocationSampleEntity
import org.openlgx.roads.data.local.db.entity.SensorSampleEntity

/** Mirrors [analysis.roughness_lab.features] bearing + heading rate. */
object HeadingRateEstimator {
    fun bearingDeg(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dl)
        val brng = Math.toDegrees(atan2(y, x))
        return (brng + 360.0) % 360.0
    }

    /** Times (ms) and heading rate (deg/s) at interior points — matches Python interior loop. */
    fun bearingRateSeries(loc: List<LocationSampleEntity>): Pair<LongArray, DoubleArray> {
        if (loc.size < 3) return LongArray(0) to DoubleArray(0)
        val times = mutableListOf<Long>()
        val rates = mutableListOf<Double>()
        val lat = loc.map { it.latitude }.toDoubleArray()
        val lon = loc.map { it.longitude }.toDoubleArray()
        val t = loc.map { it.wallClockUtcEpochMs }.toLongArray()
        for (i in 1 until loc.size - 1) {
            val b0 = bearingDeg(lat[i - 1], lon[i - 1], lat[i], lon[i])
            val b1 = bearingDeg(lat[i], lon[i], lat[i + 1], lon[i + 1])
            var db = b1 - b0
            if (db > 180) db -= 360.0
            if (db < -180) db += 360.0
            val dtS = (t[i + 1] - t[i - 1]) / 2000.0
            if (dtS > 1e-3) {
                rates.add(db / dtS)
                times.add(t[i])
            }
        }
        return times.toLongArray() to rates.toDoubleArray()
    }

    fun headingRateInWindow(
        tRate: LongArray,
        rRate: DoubleArray,
        t0: Double,
        t1: Double,
    ): Triple<Double, Double, Double> {
        if (tRate.isEmpty()) return Triple(Double.NaN, Double.NaN, Double.NaN)
        val inside = tRate.indices.filter { tRate[it] >= t0 && tRate[it] < t1 }
        if (inside.isEmpty()) return Triple(Double.NaN, Double.NaN, Double.NaN)
        val slice = inside.map { rRate[it] }
        val mean = slice.average()
        val std = stdDev(slice)
        val absMean = slice.map { kotlin.math.abs(it) }.average()
        return Triple(mean, std, absMean)
    }

    private fun stdDev(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val mean = values.average()
        val varSum = values.sumOf { (it - mean) * (it - mean) }
        return sqrt(varSum / values.size)
    }
}

/** Linear accel (10) preferred, else accelerometer (1). */
fun List<SensorSampleEntity>.motionLinearOrAccel(): List<SensorSampleEntity> {
    val lin = filter { it.sensorType == 10 }
    if (lin.isNotEmpty()) return lin
    return filter { it.sensorType == 1 }
}

fun List<SensorSampleEntity>.gyroSamples(): List<SensorSampleEntity> = filter { it.sensorType == 4 }

data class MotionWindowStats(
    val rmsMag: Double,
    val peakMag: Double,
    val energyX: Double,
    val energyY: Double,
    val energyZ: Double,
    val n: Int,
)

fun windowStatsMotion(
    motion: List<SensorSampleEntity>,
    t0: Double,
    t1: Double,
): MotionWindowStats {
    val sub = motion.filter { it.wallClockUtcEpochMs.toDouble() >= t0 && it.wallClockUtcEpochMs.toDouble() < t1 }
    if (sub.isEmpty()) return MotionWindowStats(0.0, 0.0, 0.0, 0.0, 0.0, 0)
    var sumSq = 0.0
    var peak = 0.0
    var ex = 0.0
    var ey = 0.0
    var ez = 0.0
    val n = sub.size
    for (s in sub) {
        val x = s.x.toDouble()
        val y = s.y.toDouble()
        val z = s.z.toDouble()
        val mag = sqrt(x * x + y * y + z * z)
        sumSq += mag * mag
        peak = max(peak, mag)
        ex += x * x
        ey += y * y
        ez += z * z
    }
    return MotionWindowStats(
        rmsMag = sqrt(sumSq / n),
        peakMag = peak,
        energyX = ex / n,
        energyY = ey / n,
        energyZ = ez / n,
        n = n,
    )
}

data class GyroWindowStats(
    val rms: Double,
    val peak: Double,
    val n: Int,
)

fun windowStatsGyro(
    gyro: List<SensorSampleEntity>,
    t0: Double,
    t1: Double,
): GyroWindowStats {
    val sub = gyro.filter { it.wallClockUtcEpochMs.toDouble() >= t0 && it.wallClockUtcEpochMs.toDouble() < t1 }
    if (sub.isEmpty()) return GyroWindowStats(Double.NaN, Double.NaN, 0)
    var sumSq = 0.0
    var peak = 0.0
    for (s in sub) {
        val x = s.x.toDouble()
        val y = s.y.toDouble()
        val z = s.z.toDouble()
        val mag = sqrt(x * x + y * y + z * z)
        sumSq += mag * mag
        peak = max(peak, mag)
    }
    val n = sub.size
    return GyroWindowStats(rms = sqrt(sumSq / n), peak = peak, n = n)
}

/**
 * Lightweight high-frequency proxy without FFT: RMS of deviation from window mean magnitude.
 * Documented deviation from Python `spectral_high_fraction`.
 */
fun highFrequencyEnergyApprox(
    motion: List<SensorSampleEntity>,
    t0: Double,
    t1: Double,
): Double {
    val sub =
        motion.filter { it.wallClockUtcEpochMs.toDouble() >= t0 && it.wallClockUtcEpochMs.toDouble() < t1 }
    if (sub.size < 8) return Double.NaN
    val mags = sub.map { sqrt(it.x * it.x + it.y * it.y + it.z * it.z).toDouble() }
    val mean = mags.average()
    val rmsDev = sqrt(mags.map { (it - mean) * (it - mean) }.average())
    return min(1.0, rmsDev / (mean + 1e-6))
}

/** Port of [analysis.roughness_lab.heuristics]. */
data class HeuristicScores(
    val predPrimary: String,
    val scoreCornering: Double,
    val scoreVerticalShock: Double,
    val scoreStableCruise: Double,
)

fun scoreHeuristics(
    hStd: Double,
    hAbsMean: Double,
    ex: Double,
    ey: Double,
    ez: Double,
    spd: Double,
    peak: Double,
    rms: Double,
): HeuristicScores {
    val ezPos = max(ez, 1e-9)
    val lateral = ex + ey
    val ratioLat = lateral / ezPos
    val pr = if (rms > 1e-6) peak / rms else Double.NaN

    val corner =
        (java.lang.Double.isFinite(hStd) && hStd > 4.0 && java.lang.Double.isFinite(ratioLat) && ratioLat > 0.85 &&
            java.lang.Double.isFinite(spd) && spd > 2.0) ||
            (java.lang.Double.isFinite(hAbsMean) && hAbsMean > 12.0 && java.lang.Double.isFinite(spd) && spd > 2.0)

    val shock =
        java.lang.Double.isFinite(pr) && pr > 3.5 && java.lang.Double.isFinite(peak) && peak > 6.0 &&
            (!corner || pr > 6.0)

    val pred =
        when {
            shock && !corner -> "vertical_impact"
            corner -> "cornering"
            java.lang.Double.isFinite(rms) && rms < 2.0 && java.lang.Double.isFinite(spd) && spd > 3.0 ->
                "stable_cruise"
            else -> "unknown_high_energy"
        }

    val cornerScoreParts = mutableListOf<Double>()
    if (java.lang.Double.isFinite(hStd)) cornerScoreParts.add(min(1.0, max(0.0, hStd / 15.0)))
    if (java.lang.Double.isFinite(hAbsMean)) cornerScoreParts.add(min(1.0, max(0.0, hAbsMean / 25.0)))
    if (java.lang.Double.isFinite(ratioLat) && ratioLat > 0) {
        cornerScoreParts.add(min(1.0, max(0.0, (ratioLat - 0.5) / 1.5)))
    }
    if (java.lang.Double.isFinite(spd) && spd > 0) cornerScoreParts.add(min(1.0, spd / 15.0))
    val cornerScore =
        if (cornerScoreParts.isEmpty()) Double.NaN else cornerScoreParts.average()

    val shockScore =
        if (!java.lang.Double.isFinite(pr) || !java.lang.Double.isFinite(peak)) {
            Double.NaN
        } else {
            val a = min(1.0, max(0.0, (pr - 2.0) / 5.0))
            val b = min(1.0, max(0.0, (peak - 3.0) / 12.0))
            a * b
        }

    val stable =
        if (java.lang.Double.isFinite(rms) && java.lang.Double.isFinite(spd)) {
            min(1.0, max(0.0, (2.0 - rms) / 2.0)) * min(1.0, spd / 15.0)
        } else {
            Double.NaN
        }

    return HeuristicScores(
        predPrimary = pred,
        scoreCornering = cornerScore,
        scoreVerticalShock = shockScore,
        scoreStableCruise = stable,
    )
}

fun compassLabelFromDeg(deg: Double?): String {
    if (deg == null || deg.isNaN()) return "?"
    val x = ((deg % 360.0) + 360.0) % 360.0
    val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val idx = ((x + 22.5) / 45.0).toInt() % 8
    return dirs[idx]
}
