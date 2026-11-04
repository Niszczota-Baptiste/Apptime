package fr.montemps.notification

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.montemps.MonTempsApp
import fr.montemps.domain.TransitionResult
import fr.montemps.domain.model.DefaultActivities.BREAK
import fr.montemps.domain.model.DefaultActivities.MEAL
import fr.montemps.domain.model.DefaultActivities.WORK
import fr.montemps.testutil.FakeTimeSource
import fr.montemps.testutil.context
import fr.montemps.testutil.testContainer
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
class NotificationTest {
    private val time = FakeTimeSource()
    private val c = testContainer(time)
    private val nm = context().getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        shadowOf(context() as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        (context() as MonTempsApp).container = c
        c.notifier.ensureChannel()
        nm.cancelAll()
    }

    private fun current(): Notification? = shadowOf(nm).getNotification(TrackingNotifier.NOTIFICATION_ID)

    @Test
    fun startingShowsSilentChronometerNotificationWithFavorites() = runBlocking {
        val s = (c.tracker.switchTo(WORK) as TransitionResult.Started).session
        val n = current()!!
        assertEquals("💼 Travail", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("En cours depuis 09:00", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(s.start.toEpochMilli(), n.`when`)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertEquals(Notification.CATEGORY_STOPWATCH, n.category)
        // Favorites par défaut : Pause et Travail ; Travail est actif donc seule Pause est proposée.
        assertEquals(listOf("☕ Pause", "Arrêter"), n.actions.map { it.title.toString() })
        val channel = nm.getNotificationChannel(TrackingNotifier.CHANNEL_ID)
        assertNull(channel.sound)
        assertTrue(!channel.shouldVibrate())
    }

    @Test
    fun notificationActionsSwitchAndStopThroughSharedLogic() = runBlocking {
        c.tracker.switchTo(WORK)
        time.advance(Duration.ofMinutes(20))
        val pause = current()!!.actions.first { it.title.contains("Pause") }
        repeat(3) { deliver(pause.actionIntent) } // appuis répétés
        assertEquals(BREAK, c.tracker.currentSession()?.activityId)
        assertEquals(2, c.store.allSessions().size)
        assertEquals("☕ Pause", current()!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString())

        val stop = current()!!.actions.first { it.title == "Arrêter" }
        time.advance(Duration.ofMinutes(5))
        deliver(stop.actionIntent)
        assertNull(c.tracker.currentSession())
        assertNull(current())
    }

    @Test
    fun hideNameOnLockScreenUsesPrivateVisibilityWithNeutralPublicVersion() = runBlocking {
        c.preferences.setHideNameOnLockScreen(true)
        c.tracker.switchTo(MEAL)
        val n = current()!!
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        val public = n.publicVersion
        assertNotNull(public)
        assertEquals("Suivi en cours", public.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(public.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
    }

    @Test
    fun dismissedNotificationIsNotRepostedUntilNextTransition() = runBlocking {
        val s = (c.tracker.switchTo(WORK) as TransitionResult.Started).session
        // Intent réellement attachée à la notification (deleteIntent).
        deliver(current()!!.deleteIntent)
        assertEquals(s.id, c.preferences.current().dismissedNotificationSessionId)
        nm.cancel(TrackingNotifier.NOTIFICATION_ID) // le système retire la notification balayée
        c.notifications.sync() // ex. réouverture de l'app ou redémarrage
        assertNull(current())
        time.advance(Duration.ofMinutes(1))
        c.tracker.switchTo(BREAK)
        assertNotNull(current())
    }

    @Test
    fun trackingWorksWithoutNotificationPermission() = runBlocking {
        shadowOf(context() as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        c.tracker.switchTo(WORK)
        assertNull(current())
        assertEquals(WORK, c.tracker.currentSession()?.activityId)
        // Autorisation accordée ensuite : la notification apparaît à la synchronisation suivante.
        shadowOf(context() as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        c.notifications.sync()
        assertNotNull(current())
    }

    @Test
    fun bootBroadcastRestoresNotificationFromDatabase() = runBlocking {
        c.tracker.switchTo(WORK)
        nm.cancelAll() // le redémarrage efface les notifications
        val receivers = context().packageManager.queryBroadcastReceivers(
            Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(context().packageName), 0,
        )
        assertTrue(receivers.any { it.activityInfo.name == SystemEventReceiver::class.java.name })
        SystemEventReceiver().onReceive(context(), Intent(Intent.ACTION_BOOT_COMPLETED))
        idle()
        assertNotNull(current())
    }

    /** Livre l'Intent d'une PendingIntent de notification à son récepteur, comme le ferait Android. */
    private fun deliver(pi: android.app.PendingIntent) {
        val intent = shadowOf(pi).savedIntent
        assertEquals(NotificationActionReceiver::class.java.name, intent.component!!.className)
        assertTrue(shadowOf(pi).isBroadcast)
        assertTrue("PendingIntent immuable", shadowOf(pi).flags and android.app.PendingIntent.FLAG_IMMUTABLE != 0)
        NotificationActionReceiver().onReceive(context(), intent)
        idle()
    }

    private fun idle() {
        // Laisse le récepteur (goAsync + coroutine) terminer.
        repeat(20) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(25)
        }
    }
}
