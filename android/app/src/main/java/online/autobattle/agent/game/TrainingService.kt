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
        // Set only by the ban path, where the entered deck is illegal.
        val forced = intent?.getBooleanExtra(EXTRA_FORCED, false) ?: false
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
                val shell = directive.shell?.let { want ->
                    runCatching { readShell(c, want) }.getOrNull()
                }
                if (directive.shell != null && shell == null) {
                    note("That deck is no longer offered; building it from scratch…")
                }
                val outcome = Compete.train(c, handle, directive, shell = shell) { p ->
                    Training.report(p)
                    note(p.step + if (p.total > 1) "  (${p.done}/${p.total})" else "")
                }
                // ENTER IT, OR PUT IT ON THE BENCH.
                //
                // The same test the screen uses (ui/Arena.kt): a deck is
                // waiting for a result when the league's last round finished
                // BEFORE the entered deck was submitted. Asked here rather
                // than passed in, because the answer can change during a
                // build -- a six-minute climb can straddle a round -- and
                // the honest moment to ask is when the deck is ready.
                //
                // Three cases enter straight away. Forced: a ban has made
                // the entered deck ILLEGAL, so it must be replaced rather
                // than parked behind a decision. No deck yet: there is
                // nothing to protect. Already scored: the result the player
                // was waiting for has arrived, so nothing is lost.
                val entered = runCatching { c.me().optJSONObject("deck") }.getOrNull()
                val scoredAt = runCatching {
                    c.standings().optJSONObject("round")?.optString("finished_at")
                }.getOrNull().orEmpty()
                val submittedAt = entered?.optString("updated_at").orEmpty()
                val awaiting = submittedAt.isNotBlank() && scoredAt.isNotBlank() &&
                    scoredAt <= submittedAt
                if (forced || entered == null || !awaiting) {
                    note("Submitting…")
                    val rev = c.putDeck(outcome.name, outcome.cards, outcome.plan)
                    Training.Result(outcome, rev, null)
                } else {
                    val round = runCatching {
                        c.standings().optJSONObject("round")?.optInt("id") ?: 0
                    }.getOrDefault(0)
                    Workbench.save(this@TrainingService, Workbench(
                        name = outcome.name, cards = outcome.cards,
                        plan = outcome.plan, wins = outcome.wins,
                        opponents = outcome.opponents, builtAtRound = round))
                    Training.Result(outcome, 0, null, benched = true)
                }
            }.getOrElse { Training.Result(null, 0, readableError(it)) }
            Training.finish(r)
            // TELL THEM IT LANDED. The ongoing notification is the contract
            // with the system -- it is why the work is allowed to keep
            // running with the screen off -- but it vanishes with the
            // service, so a player who walked away got no sign that anything
            // had happened. This one is separate, dismissible, and survives
            // the service exiting.
            // ORDER MATTERS. Leave the foreground FIRST: while the service
            // is still in the foreground the system requires a notification
            // to be showing, so cancelling the working one there is either
            // ignored or immediately re-posted. Only once it has detached can
            // the id actually be cleared -- and only then is it safe to post
            // the finished one, which must not be mistaken for the ongoing.
            stopForeground(STOP_FOREGROUND_REMOVE)
            val nmgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nmgr.cancel(NOTIF_ID)
            done(r)
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
        // CLEAR THE WORKING NOTIFICATION FOR GOOD. stopForeground(REMOVE)
        // takes away the notification the service was STARTED with, but every
        // progress update since went out through NotificationManager.notify
        // under the same id, and that posting outlives the service -- leaving
        // a permanent "Your agent is working" in the shade after the build had
        // finished and been reported. Cancelling straight after stopForeground
        // was not enough either: it races the service detaching. Here the
        // service is already gone.
        //
        // Found by dumping the shade after a run, not by reading this code:
        // the build worked, the finished notification arrived, and the stale
        // one sat underneath it.
        // Belt and braces: if the run was cancelled rather than finished,
        // the block above never reached its own cancel.
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIF_ID)
        super.onDestroy()
    }

    private fun readableError(t: Throwable): String = when (t) {
        // SAY WHAT THE LEAGUE OBJECTED TO. The status alone sent me to the
        // server to find out that a shell held a card banned since the pool
        // was built; a player has no such option. PUT /deck answers with
        // deck_problem()'s own sentence -- "card N is banned", "deck must
        // hold exactly 100 cards" -- which is the useful half of the reply.
        is GameException -> {
            val why = runCatching {
                org.json.JSONObject(t.body).optString("error").takeIf { it.isNotBlank() }
            }.getOrNull()
            if (why != null) "The league said no: $why"
            else "The league said no (HTTP ${t.status})."
        }
        else -> t.message ?: t.javaClass.simpleName
    }

    private fun note(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notification(text))
    }

    /**
     * The one notification a player who left is meant to see.
     *
     * A separate id from the ongoing one, so it is not cancelled when the
     * foreground service stops, and DEFAULT importance rather than LOW so it
     * actually arrives rather than sitting silently in the shade. It says
     * what the deck did, because "finished" without a number is a reason to
     * open the app and find out, which is the thing being saved.
     */
    private fun done(r: Training.Result) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(DONE_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(DONE_CHANNEL, "Agent finished",
                    NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE)
        val title: String
        val text: String
        val o = r.outcome
        if (r.error != null || o == null) {
            title = "Your agent could not finish"
            text = r.error ?: "Something went wrong."
        } else {
            title = "${o.name} is ready"
            text = "Won ${"%.1f".format(o.wins)} of ${o.opponents} on fresh " +
                "matchups. Submitted as revision ${r.revision}."
        }
        nm.notify(DONE_ID, Notification.Builder(this, DONE_CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build())
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
        private const val DONE_CHANNEL = "training_done"
        private const val DONE_ID = 1002
        private const val EXTRA_FORCED = "forced"

        /**
         * @param forced the entered deck is illegal (a ban landed on it), so
         * the result must go straight into the league rather than onto the
         * bench -- there is no sense protecting a deck that cannot play.
         */
        fun start(ctx: Context, forced: Boolean = false) {
            ctx.startForegroundService(
                Intent(ctx, TrainingService::class.java)
                    .putExtra(EXTRA_FORCED, forced))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TrainingService::class.java))
        }
    }
}
