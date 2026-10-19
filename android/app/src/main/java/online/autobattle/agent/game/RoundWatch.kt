package online.autobattle.agent.game

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import online.autobattle.agent.data.Secrets
import java.util.concurrent.TimeUnit

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

    /** How many rounds to sit out. Three is about forty-five minutes. */
    const val ROUNDS = 3

    fun start(ctx: Context) {
        // KEEP, not REPLACE: asking twice should not restart the clock and
        // push the answer further away.
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            // Fifteen minutes is both WorkManager's floor and the league's
            // own cadence, so there is no point asking for finer.
            PeriodicWorkRequestBuilder<Worker>(15, TimeUnit.MINUTES).build(),
        )
    }

    fun stop(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
    }

    class Worker(ctx: Context, params: WorkerParameters) :
        CoroutineWorker(ctx, params) {

        override suspend fun doWork(): Result {
            val ctx = applicationContext
            val mem = Memory.load(ctx)
            val target = mem.waitUntilRound
            // Nothing to wait for -- the player built again, or this already
            // fired. Stop rather than keep waking up.
            if (target <= 0) {
                stop(ctx)
                return Result.success()
            }
            val token = Secrets.load(ctx, Secrets.SLOT_GAME_TOKEN)
                ?: return Result.success()

            val st = runCatching { GameClient(token).standings() }
                .getOrNull() ?: return Result.retry()
            val round = st.optJSONObject("round")?.optInt("id") ?: 0
            if (round < target) return Result.success()   // still waiting

            val handle = Secrets.handle(ctx)
            val rows = st.optJSONArray("standings")
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

            Memory.save(ctx, mem.copy(waitUntilRound = 0))
            stop(ctx)
            return Result.success()
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
}
