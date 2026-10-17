package online.autobattle.agent.game

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where a training run's state lives, so it outlives the screen.
 *
 * It used to live in the composable's own coroutine scope, which meant
 * leaving the app threw the work away. A run is forty seconds to a couple of
 * minutes of solid computation, and a game you are not allowed to look away
 * from for two minutes is a game people put down. The service owns the work;
 * this is how the UI watches it, and the UI can come and go freely.
 *
 * A process-level singleton rather than a bound service: the UI only ever
 * reads, the service only ever writes, and binding would add a lifecycle to
 * get wrong for no benefit.
 */
object Training {

    data class Result(
        val outcome: Compete.Outcome?,
        val revision: Int,
        val error: String?,
    )

    private val _progress = MutableStateFlow<Compete.Progress?>(null)
    val progress: StateFlow<Compete.Progress?> = _progress

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    /** The last finished run, kept so the UI can show it after coming back. */
    private val _result = MutableStateFlow<Result?>(null)
    val result: StateFlow<Result?> = _result

    fun begin() {
        _result.value = null
        _running.value = true
        _progress.value = Compete.Progress("Starting", 0, 1)
    }

    fun report(p: Compete.Progress) {
        _progress.value = p
    }

    fun finish(r: Result) {
        _result.value = r
        _progress.value = null
        _running.value = false
    }

    fun clearResult() {
        _result.value = null
    }
}
