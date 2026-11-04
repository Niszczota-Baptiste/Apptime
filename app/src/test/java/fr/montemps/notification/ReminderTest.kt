package fr.montemps.notification

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.montemps.MonTempsApp
import fr.montemps.domain.TransitionResult
import fr.montemps.domain.model.DefaultActivities.BREAK
import fr.montemps.domain.model.DefaultActivities.WORK
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

@RunWith(AndroidJUnit4::class)
class ReminderTest {
    private val time = FakeTimeSource()
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

    private fun reminder(): Notification? = shadowOf(nm).getNotification(TrackingNotifier.REMINDER_ID)
    private fun reminderAlarm() = alarms.scheduledAlarms.firstOrNull {
        shadowOf(it.operation).savedIntent.action == TrackingActionReceiver.ACTION_REMINDER
    }

    @Test
    fun longSessionReminderIsScheduledPostedOnceAndAcknowledged() = runBlocking {
        val s = (c.tracker.switchTo(WORK) as TransitionResult.Started).session
        val alarm = reminderAlarm()!!
        assertEquals(s.start.plus(Duration.ofHours(10)).toEpochMilli(), alarm.triggerAtTime)
        assertEquals(AlarmManager.RTC, alarm.type)

        // Alarme reçue trop tôt (heure modifiée…) : rien.
        c.reminders.fire(s.id)
        assertNull(reminder())

        time.advance(Duration.ofHours(10).plusMinutes(1))
        deliver(alarm.operation!!)
        val n = reminder()!!
        assertEquals("« Travail » toujours en cours ?", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("En cours depuis 09:00 · 10 h 01"))
        assertEquals(listOf("Continuer", "Corriger"), n.actions.map { it.title.toString() })
        assertEquals(Notification.CATEGORY_REMINDER, n.category)

        // « Continuer » : plus de rappel, ni de question à la réouverture, pour cette session.
        deliver(n.actions[0].actionIntent)
        assertNull(reminder())
        assertEquals(s.id, c.preferences.current().acknowledgedSessionId)
        c.refreshOutputs()
        assertNull(reminderAlarm())
    }

    @Test
    fun switchingActivityCancelsTheReminderOfThePreviousSession() = runBlocking {
        val s = (c.tracker.switchTo(WORK) as TransitionResult.Started).session
        time.advance(Duration.ofHours(11))
        c.reminders.fire(s.id)
        assertNotNull(reminder())
        val next = (c.tracker.switchTo(BREAK) as TransitionResult.Started).session
        assertNull(reminder())
        assertEquals(next.start.plus(Duration.ofHours(10)).toEpochMilli(), reminderAlarm()!!.triggerAtTime)
        time.advance(Duration.ofMinutes(5))
        c.tracker.stop()
        assertNull(reminderAlarm())
    }

    @Test
    fun disabledReminderSchedulesNothing() = runBlocking {
        c.preferences.setLongSessionReminder(false)
        val s = (c.tracker.switchTo(WORK) as TransitionResult.Started).session
        assertNull(reminderAlarm())
        time.advance(Duration.ofHours(12))
        c.reminders.fire(s.id)
        assertNull(reminder())
    }

    private fun deliver(pi: android.app.PendingIntent) {
        val intent = shadowOf(pi).savedIntent
        assertEquals(TrackingActionReceiver::class.java.name, intent.component!!.className)
        TrackingActionReceiver().onReceive(context(), intent)
        runBlocking {
            repeat(25) {
                ShadowLooper.idleMainLooper()
                delay(20)
            }
        }
    }

}
