package fr.montemps.notification

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.montemps.domain.model.DefaultActivities.BREAK
import fr.montemps.domain.model.DefaultActivities.KOREAN
import fr.montemps.domain.model.DefaultActivities.WORK
import fr.montemps.domain.model.Goal
import fr.montemps.domain.model.GoalPeriod
import fr.montemps.testutil.FakeTimeSource
import fr.montemps.testutil.context
import fr.montemps.testutil.testContainer
import fr.montemps.testutil.useContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class ScheduledNotificationsTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun at(s: String): Instant = LocalDateTime.parse(s).atZone(paris).toInstant()
    private val time = FakeTimeSource(now = at("2026-11-04T09:00"))
    private val c = testContainer(time)
    private val nm by lazy { context().getSystemService(NotificationManager::class.java) }
    private val alarms by lazy { shadowOf(context().getSystemService(AlarmManager::class.java)) }

    @Before
    fun setUp() {
        shadowOf(context() as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        useContainer(c)
        c.notifier.ensureChannels()
        nm.cancelAll()
    }

    private fun notification(id: Int): Notification? = shadowOf(nm).getNotification(id)
    private fun alarm(action: String) = alarms.scheduledAlarms.firstOrNull { shadowOf(it.operation).savedIntent.action == action }

    @Test
    fun idleReminderFiresOncePerIdlePeriodAndClearsWhenTrackingResumes() = runBlocking {
        c.preferences.setIdleReminder(true)
        c.tracker.switchTo(WORK)
        time.advance(Duration.ofMinutes(70)) // 10:10
        c.tracker.stop()
        val a = alarm(TrackingActionReceiver.ACTION_IDLE)!!
        assertEquals(at("2026-11-04T12:10").toEpochMilli(), a.triggerAtTime) // fin + 2 h

        time.now = at("2026-11-04T12:15")
        c.idle.fire()
        val n = notification(TrackingNotifier.IDLE_ID)!!
        assertEquals("Rien n'est suivi depuis 10:10", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        // Actions : reprendre la dernière activité, puis les favorites (sans doublon).
        assertEquals(listOf("Travail", "Pause"), n.actions.map { it.title.toString() })
        // Une seule fois par période : prochaine échéance le lendemain.
        assertEquals(at("2026-11-05T10:00").toEpochMilli(), alarm(TrackingActionReceiver.ACTION_IDLE)!!.triggerAtTime)

        c.tracker.switchTo(BREAK)
        assertNull(notification(TrackingNotifier.IDLE_ID))
        assertNull(alarm(TrackingActionReceiver.ACTION_IDLE))
    }

    @Test
    fun openingTheAppRestartsTheIdlePeriod() = runBlocking {
        c.preferences.setIdleReminder(true)
        c.tracker.switchTo(WORK)
        time.advance(Duration.ofMinutes(10))
        c.tracker.stop() // 09:10
        time.now = at("2026-11-04T10:30")
        c.idle.onAppOpened()
        assertEquals(at("2026-11-04T12:30").toEpochMilli(), alarm(TrackingActionReceiver.ACTION_IDLE)!!.triggerAtTime)
    }

    @Test
    fun idleReminderIsOffByDefault() = runBlocking {
        c.tracker.switchTo(WORK)
        time.advance(Duration.ofMinutes(10))
        c.tracker.stop()
        assertNull(alarm(TrackingActionReceiver.ACTION_IDLE))
    }

    @Test
    fun eveningSummaryIsSilentDailyAndSkipsEmptyDays() = runBlocking {
        c.catalog.edit(KOREAN, "Coréen", 0xFF8E7CC3.toInt(), null, goal = Goal(30, GoalPeriod.DAY))
        c.preferences.setEveningSummary(true)
        c.refreshOutputs()
        assertEquals(at("2026-11-04T21:00").toEpochMilli(), alarm(TrackingActionReceiver.ACTION_SUMMARY)!!.triggerAtTime)

        // Jour vide : pas de notification, mais le jour est consommé.
        time.now = at("2026-11-04T21:01")
        c.summary.fire()
        assertNull(notification(TrackingNotifier.SUMMARY_ID))
        assertEquals(at("2026-11-05T21:00").toEpochMilli(), alarm(TrackingActionReceiver.ACTION_SUMMARY)!!.triggerAtTime)

        time.now = at("2026-11-05T08:00")
        c.tracker.switchTo(KOREAN)
        time.advance(Duration.ofMinutes(35))
        c.tracker.switchTo(WORK)
        time.now = at("2026-11-05T21:00:05")
        c.summary.fire()
        val n = notification(TrackingNotifier.SUMMARY_ID)!!
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TITLE).toString().startsWith("Aujourd'hui : "))
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Objectifs du jour : 1/1 atteints"))
        assertEquals(TrackingNotifier.SUMMARY_CHANNEL_ID, n.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel(TrackingNotifier.SUMMARY_CHANNEL_ID).importance)
        // Ouvre les bilans.
        assertEquals(TrackingIntents.DESTINATION_REPORTS, shadowOf(n.contentIntent).savedIntent.getStringExtra(fr.montemps.MainActivity.EXTRA_DESTINATION))

        nm.cancelAll()
        c.summary.fire() // même jour : rien de plus
        assertNull(notification(TrackingNotifier.SUMMARY_ID))
    }

    @Test
    fun trackingNotificationRequestsLiveUpdateAndOffersResumeDuringABreak() = runBlocking {
        c.tracker.switchTo(WORK)
        var n = notification(TrackingNotifier.NOTIFICATION_ID)!!
        assertTrue(NotificationCompat.isRequestPromotedOngoing(n))
        // Critères documentés d'un « Live Update » (la méthode de la plateforme dépend d'un drapeau
        // de fonctionnalité absent du simulateur) : en cours, titre, pas de couleur de fond, pas de
        // vue personnalisée, pas de résumé de groupe, style standard.
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(!n.extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank())
        assertTrue(!n.extras.getBoolean(Notification.EXTRA_COLORIZED))
        assertNull(n.contentView)
        assertTrue(n.flags and Notification.FLAG_GROUP_SUMMARY == 0)
        assertNull(n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertTrue(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) // chronomètre dans la puce

        time.advance(Duration.ofMinutes(50))
        c.tracker.switchTo(BREAK)
        n = notification(TrackingNotifier.NOTIFICATION_ID)!!
        assertEquals(listOf("Reprendre Travail", "Arrêter"), n.actions.map { it.title.toString() })
        // Un appui sur « Reprendre » (5 min plus tard) passe par la logique commune.
        time.advance(Duration.ofMinutes(5))
        val intent = shadowOf(n.actions[0].actionIntent).savedIntent
        TrackingActionReceiver().onReceive(context(), intent)
        repeat(25) {
            ShadowLooper.idleMainLooper()
            delay(20)
        }
        assertEquals(WORK, c.tracker.currentSession()?.activityId)

        // Réglage désactivé : plus de demande de promotion.
        c.preferences.setPromotedNotification(false)
        c.notifications.sync()
        assertTrue(!NotificationCompat.isRequestPromotedOngoing(notification(TrackingNotifier.NOTIFICATION_ID)!!))
        assertNotNull(notification(TrackingNotifier.NOTIFICATION_ID))
    }
}
