package online.autobattle.agent.game

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import online.autobattle.agent.data.Secrets

/**
 * Runs one training job as a foreground service.
 *
 * Android freezes background CPU work -- aggressively so on Samsung -- and
 * this is forty seconds to a couple of minutes of flat-out simulation. Doing
 * it in the Activity's scope meant switching apps killed it silently.
 *
 * The notification is not decoration: it is the contract. The system allows
 * sustained background CPU precisely because the user can see it happening
 * and stop it, and a game that quietly burns a phone's battery with nothing
 * on screen deserves to be killed.
 */
class TrainingService : Service() {

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    /** One shell out of GET /shells, by name, or null if it is not offered. */
    private fun readShell(c: GameClient, want: String): Compete.Shell? =
        Compete.readShells(c).firstOrNull { it.name == want }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive == true) return START_NOT_STICKY
        startForeground(NOTIF_ID, notification("Starting…"))
        Training.begin()

        val token = Secrets.load(this, Secrets.SLOT_GAME_TOKEN)
        val handle = Secrets.handle(this)
        val directive = Directive.load(this)

        job = scope.launch {
            val r = runCatching {
                require(token != null) { "not joined yet" }
                val c = GameClient(token)
                // RESOLVED HERE, NOT STORED. The directive keeps the shell's
                // NAME; the list is fetched now, so a rebuild starts from the
                // current measurement of that idea rather than a copy taken
                // when the player first chose it. If the pool no longer has
                // it -- an archetype can stop being buildable when a card is
                // banned -- this falls through to an ordinary build rather
                // than failing, which is the same deck the player asked for,
                // arrived at the long way.
                val shell = directive.shellName?.let { want ->
                    runCatching { readShell(c, want) }.getOrNull()
                }
                if (directive.shellName != null && shell == null) {
                    note("That deck is no longer offered; building it from scratch…")
                }
                val outcome = Compete.train(c, handle, directive, shell = shell) { p ->
                    Training.report(p)
                    note(p.step + if (p.total > 1) "  (${p.done}/${p.total})" else "")
                }
                note("Submitting…")
                val rev = c.putDeck(outcome.name, outcome.cards, outcome.plan)
                Training.Result(outcome, rev, null)
            }.getOrElse { Training.Result(null, 0, readableError(it)) }
            Training.finish(r)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        // NOT sticky: a restarted service would start a NEW run the user did
        // not ask for, on a phone they may have put down.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.coroutineContext[Job]?.cancel()
        if (Training.running.value) {
            Training.finish(Training.Result(null, 0, "Training was stopped."))
        }
        super.onDestroy()
    }

    private fun readableError(t: Throwable): String = when (t) {
        is GameException -> "The league said no (HTTP ${t.status})."
        else -> t.message ?: t.javaClass.simpleName
    }

    private fun note(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Agent training",
                    NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Your agent is working")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "training"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, TrainingService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TrainingService::class.java))
        }
    }
}
