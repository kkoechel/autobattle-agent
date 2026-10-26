package online.autobattle.agent.game

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Intent
import online.autobattle.agent.data.Secrets

/**
 * "I'll be patient -- tell me how it does."
 *
 * The screen that offers this has four other choices and every one of them
 * either spends the phone's battery or throws the deck away. There was no way
 * to simply let a deck play, which is the thing the agent spends most of its
 * breath recommending. Now there is, and the agent comes back on its own
 * rather than leaving the player to remember to look.
 *
 * WHY A WORKER AND NOT A TIMER IN THE APP. Waiting three rounds is
 * three-quarters of an hour, and the point of saying "I'll be patient" is to
 * put the phone down. A check that only runs while the screen is on would be
 * a check that never runs.
 */
object RoundWatch {

    private const val WORK = "round-watch"
    private const val CHANNEL = "agent-rounds"
    private const val NOTIF_ID = 4201

    /** The default, when something needs one. */
    const val ROUNDS = 3

    /**
     * What the player may choose, with the time each works out to.
     *
     * Rounds, not minutes, because rounds are the unit the league moves in --
     * and the cadence could change, at which point a menu of durations would
     * quietly start lying. The gloss is there because nobody thinks in
     * fifteen-minute multiples.
     *
     * IT SAYS "OF PLAY", NOT "AND I WILL TELL YOU THEN". How long the rounds
     * take is ours to state; when the news reaches the player is not. A
     * sleeping phone restricts both alarms and network -- measured here, the
     * check woke in deep Doze and could not reach the server, then went back
     * to sleep -- so the honest promise is that the agent will come and find
     * them, not that it will do so on the quarter hour.
     */
    val CHOICES: List<Pair<Int, String>> = listOf(
        1 to "a quarter hour of play",
        3 to "about an hour of play",
        8 to "a couple of hours of play",
        24 to "half a day of play",
        96 to "a day of play",
    )

    /**
     * WHY AN ALARM AND NOT WorkManager.
     *
     * WorkManager was the obvious choice and it does not keep the promise.
     * Measured on the device, screen off, exactly as a player would leave it:
     * the phone reached Doze (mState=IDLE), the job's constraints all read
     * SATISFIED, and it still did not run -- two league rounds passed with no
     * notification. Doze defers jobs to maintenance windows that grow from
     * half-hourly to hourly to several hours, which is fine for sync and
     * useless for "tell me in fifteen minutes".
     *
     * setAndAllowWhileIdle is the mechanism meant for this. It is inexact --
     * Doze still rate-limits it to about once every nine minutes -- which is
     * well inside a fifteen-minute round, and it needs no special permission,
     * unlike the exact variants on Android 12 and up.
     */
    private fun alarm(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, Alarm::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun start(ctx: Context) {
        // SOON, not in fifteen minutes. The baseline is established by the
        // first check, so if that check waited a full round then "one round"
        // would mean two: a quarter of an hour to notice where we are, and
        // another for the round itself. A minute is long enough to be off
        // the UI thread and short enough that the count starts from now.
        schedule(ctx, 60_000L)
    }

    /** Next look, by default a little after the next round is due. */
    fun schedule(ctx: Context, inMs: Long = CHECK_EVERY_MS) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + inMs, alarm(ctx))
    }

    fun stop(ctx: Context) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .cancel(alarm(ctx))
    }

    /**
     * A round is scored every half hour, so looking more often is waste.
     *
     * WIDENED WITH THE SERVER (2026-10-26). The league ran every fifteen
     * minutes until the field reached 39 decks and a round stopped fitting
     * the window; the timer moved to thirty. Left at fifteen this would wake
     * the phone twice per round to be told nothing had happened.
     */
    private const val CHECK_EVERY_MS = 30L * 60L * 1000L

    /**
     * Woken by the alarm, including in Doze.
     *
     * goAsync() because the check needs one HTTP call and a broadcast
     * receiver is otherwise killed the moment onReceive returns. The work is
     * a single request either way, well inside the budget.
     */
    class Alarm : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent?) {
            val done = goAsync()
            Thread {
                try { look(ctx.applicationContext) } finally { done.finish() }
            }.start()
        }
    }

    private fun look(ctx: Context) {
        val mem = Memory.load(ctx)
        // Nothing to wait for -- the player built again, or this already
        // fired. Stop rather than keep waking up.
        if (mem.waitRounds <= 0) { stop(ctx); return }

        val token = Secrets.load(ctx, Secrets.SLOT_GAME_TOKEN)
        if (token == null) { stop(ctx); return }

        // EVERY PATH THAT IS STILL WAITING MUST RE-ARM. setAndAllowWhileIdle
        // schedules exactly one firing, so a missed re-arm is a promise that
        // silently never comes back -- the same shape of failure as the
        // deferred job this replaced, just arrived at differently.
        val st = runCatching { GameClient(token).standings() }.getOrNull()
        val round = st?.optJSONObject("round")?.optInt("id") ?: 0
        if (round <= 0) { schedule(ctx); return }      // offline, try again

        // THE BASELINE IS SET HERE, FROM LIVE STANDINGS, not in the UI. The
        // screen's idea of the current round is whatever it last fetched, and
        // agreeing to wait while that view was stale set a target already in
        // the past -- the agent promised three rounds and reported back two
        // minutes later, on a round that had already been scored.
        var target = mem.waitUntilRound
        if (target <= 0) {
            target = round + mem.waitRounds
            Memory.save(ctx, mem.copy(waitUntilRound = target))
            schedule(ctx)
            return
        }
        if (round < target) { schedule(ctx); return }

        val handle = Secrets.handle(ctx)
        val rows = st!!.optJSONArray("standings")
        val me = (0 until (rows?.length() ?: 0))
            .map { rows!!.getJSONObject(it) }
            .firstOrNull { it.optString("handle") == handle }
        val voice = Voice.forAvatar(
            ctx.getSharedPreferences("agent_look", Context.MODE_PRIVATE)
                .getString("avatar", null))

        notify(ctx, if (me != null)
            voice.scored(round, me.optInt("placement"),
                st.optJSONObject("round")?.optInt("decks") ?: 0,
                me.optInt("wins"), me.optInt("losses"), me.optInt("draws"))
        else voice.scoredBare(round))

        Memory.save(ctx, mem.copy(waitUntilRound = 0, waitRounds = 0))
        stop(ctx)
    }

    private fun notify(ctx: Context, text: String) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Round results",
                        NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = PendingIntent.getActivity(
                ctx, 0,
                ctx.packageManager.getLaunchIntentForPackage(ctx.packageName),
                PendingIntent.FLAG_IMMUTABLE)
            nm.notify(NOTIF_ID, Notification.Builder(ctx, CHANNEL)
                .setContentTitle("Your agent has news")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
    }
}
