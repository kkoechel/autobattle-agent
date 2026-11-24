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
