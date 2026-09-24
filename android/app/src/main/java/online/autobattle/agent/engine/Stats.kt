package online.autobattle.agent.engine

/**
 * The gate that separates advice from superstition.
 *
 * Two independent requirements, and both were learned the hard way:
 *
 *  - Statistical: a paired t over shared seeds. Testing only the SIGN of a
 *    difference accepts coin flips — at 21 seeds the standard error is ~1.7
 *    wins, so a "+0.4W, therefore better" rule banks noise, and stacking a few
 *    such steps tunes a deck to its seed block rather than to the game.
 *
 *  - Practical: the gain must be big enough to matter. With enough seeds the
 *    harness resolves a 0.1-win difference honestly, but a single cohort has a
 *    ~2.2-win SD, so 0.1 wins is a twentieth of the noise in placement — the
 *    only outcome a player ever sees. It cannot change where they finish; it
 *    can only churn their deck.
 *
 * Measured over 131 validations on a tuned deck, the distribution of gains was
 * symmetric about zero (+0.0 x59, -0.0 x24, +0.1 x20, -0.1 x10) — what a
 * search finds when there is nothing left to find. Six of eight changes
 * accepted in that period were +0.1 or +0.2.
 */
object Stats {

    const val MIN_GAIN = 0.4
    const val MIN_T = 2.0

    /** (mean win difference a-b, t statistic) over seeds both played. */
    fun pairedT(a: Score, b: Score): Pair<Double, Double> {
        val shared = a.winsBySeed.keys.intersect(b.winsBySeed.keys).sorted()
        if (shared.size < 2) return 0.0 to 0.0
        val diffs = shared.map { (a.winsBySeed[it]!! - b.winsBySeed[it]!!).toDouble() }
        val mean = diffs.average()
        val sd = Math.sqrt(diffs.sumOf { (it - mean) * (it - mean) } / (diffs.size - 1))
        if (sd == 0.0) return mean to (if (mean != 0.0) Double.POSITIVE_INFINITY else 0.0)
        return mean to mean / (sd / Math.sqrt(diffs.size.toDouble()))
    }

    /** Big enough to matter AND clear enough to believe. */
    fun accept(gain: Double, t: Double, minGain: Double = MIN_GAIN, minT: Double = MIN_T) =
        gain >= minGain && t >= minT
}
