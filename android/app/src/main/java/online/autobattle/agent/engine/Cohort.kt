package online.autobattle.agent.engine

import mobile.Mobile

/**
 * Running a cohort, independent of which engine runs it.
 *
 * WHY THIS EXISTS. The Go engine is compiled into engine.aar and can only
 * change in a Play release, so every engine bump leaves the app measuring with
 * one engine while the league scores with another. That is not hypothetical:
 * the AAR was 4.72 while the league scored 4.73, and most of v4.53 -> v4.73 has
 * been the same treadmill. Google's self-update rule forbids downloading native
 * code but carves out interpreted code in a WebView, so the JS engine is the
 * only one of the two that is ALLOWED to stay current without a release.
 *
 * The two are interchangeable, measured rather than assumed: the served
 * js/battle-engine.js reproduces all eleven CLI-derived fixture digests in
 * androidTest/assets exactly, including payload.json at 1,491 matches
 * (1b5e48ac...). JsEngineParityTest is that check, kept running.
 *
 * Both implementations take and return exactly what `Mobile.run` does -- a
 * payload from [PayloadBuilder] in, an envelope of {"results":[...],"hash":"..."}
 * out -- so Aggregator and every caller are unchanged.
 */
/**
 * The cohort did not finish in time.
 *
 * Distinct from every other engine failure ON PURPOSE. A truncated engine or a
 * WebView that will not start says nothing about the payload, so falling back
 * to the other engine is right. A timeout says the opposite: these matches may
 * not be simulable at all, and [GoRunner] cannot be stopped -- `Mobile.run` is
 * a blocking JNI call with no cancellation -- so retrying there turns a bounded
 * failure into a thread that never comes back. Draft #2424 is what that looks
 * like: one match whose board doubled every turn, 120 permanents at t24 and
 * 6,167 at t36. Matching on an exception message would be the fragile way to
 * tell these apart, so they are different types.
 */
class CohortTimeout(message: String) : RuntimeException(message)

interface CohortRunner {
    /** Short name for the engine that ran, for labelling a measurement. */
    val label: String

    /**
     * Plays every match in [payload] and returns the envelope. Blocking, and
     * on a cohort of any size it is long -- never call it on the main thread.
     */
    fun run(payload: String, workers: Int): String
}

/**
 * The engine compiled into engine.aar.
 *
 * `Mobile.run` is a synchronous JNI call with no `recover()` behind it: a Go
 * panic crossing the bridge aborts the process rather than raising, which is
 * why nothing here tries to catch one. See android/app/build.gradle.
 */
object GoRunner : CohortRunner {
    override val label: String get() = "go " + version

    /** The AAR's own version, e.g. "4.72". */
    val version: String get() = Mobile.version()

    override fun run(payload: String, workers: Int): String =
        Mobile.run(payload, workers.toLong())
}

/**
 * Payload in, scores out -- the three statements every caller used to repeat.
 *
 * There were two copies of this, byte for byte identical and unshared:
 * chat/Lab.kt's `simulate` and game/Compete.kt's private `score`, with four
 * more sites inlining the same thing. That is how a call site silently keeps
 * running the old engine after everything else has moved, so there is now one.
 *
 * Which engine actually runs is [Engines]' decision, not the caller's.
 */
fun simulate(
    cat: Catalogue, arms: List<Deck>, opponents: List<Deck>,
    seeds: List<Int>, workers: Int,
): Map<String, Score> {
    val payload = PayloadBuilder(cat.raw).build(arms, opponents, seeds)
    val env = org.json.JSONObject(Engines.run(payload, workers))
    return Aggregator.aggregate(
        env.getJSONArray("results").toString(), arms, seeds, opponents.size)
}
