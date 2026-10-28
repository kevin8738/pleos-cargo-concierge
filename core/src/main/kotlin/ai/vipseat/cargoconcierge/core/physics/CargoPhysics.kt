package ai.vipseat.cargoconcierge.core.physics

/**
 * The back-of-envelope physics behind the per-category acceleration budgets in `CargoPreset`
 * (sources and worked numbers: `submission/근거_EVIDENCE.md`). Every function returns g.
 *
 * These are deliberately simple, conservative bounds — the 11/1–11/8 field measurements
 * replace them with observed slide/spill onsets for a real cake box, cup carrier and soup tub.
 */
object CargoPhysics {
    /**
     * Quasi-static spill. Under a steady lateral acceleration a the free surface of a liquid tilts by
     * atan(a/g) (rigid-body fluid motion). In a container of diameter d filled to h below the rim the
     * liquid reaches the rim when (d/2)·(a/g) ≥ h, i.e. a/g = 2h/d.
     */
    fun spillStaticG(freeboardCm: Double, diameterCm: Double): Double = 2.0 * freeboardCm / diameterCm

    /**
     * Sudden onset. A step change of acceleration makes a lightly damped slosh mode overshoot the
     * static tilt by up to 2× (classic step response of an undamped oscillator), so the budget that
     * survives an abrupt steering input is half the static threshold.
     */
    fun spillTransientG(freeboardCm: Double, diameterCm: Double, overshoot: Double = 2.0): Double =
        spillStaticG(freeboardCm, diameterCm) / overshoot

    /** A box on the seat starts sliding when a ≥ μs·g. */
    fun slideG(muStatic: Double): Double = muStatic

    /** A rigid upright object tips when a/g ≥ (base/2) / h_com (moment about the downhill edge). */
    fun tipG(baseWidthCm: Double, comHeightCm: Double): Double = (baseWidthCm / 2.0) / comHeightCm

    /** Steady lateral acceleration on a curve: v² / R. */
    fun curveG(speedKph: Double, radiusM: Double): Double {
        val v = speedKph / 3.6
        return v * v / radiusM / GEstimator.G
    }

    /** m/s² → g, for comparing against ride-comfort literature. */
    fun mps2ToG(mps2: Double): Double = mps2 / GEstimator.G
}
