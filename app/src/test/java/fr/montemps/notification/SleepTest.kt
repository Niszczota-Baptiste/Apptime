package fr.montemps.notification

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.montemps.domain.model.DefaultActivities.SLEEP
import fr.montemps.domain.model.DefaultActivities.WORK
import fr.montemps.testutil.FakeTimeSource
import fr.montemps.testutil.context
import fr.montemps.testutil.testContainer
import fr.montemps.testutil.useContainer
import fr.montemps.ui.settings.SettingsViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Coucher et réveil automatiques, de bout en bout : réglage (comme l'écran Réglages), alarme
 * inexacte, diffusion reçue par [TrackingActionReceiver], suivi via TimeTracker, notification et
 * ses actions « Annuler » / « Je dors encore ».
 */
@RunWith(AndroidJUnit4::class)
class SleepTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun at(s: String): Instant = LocalDateTime.parse(s).atZone(paris).toInstant()
    private val time = FakeTimeSource(now = at("2026-11-04T20:00"))
    private val c = testContainer(time)
    private val nm by lazy { context().getSystemService(NotificationManager::class.java) }
    private val alarms by lazy { shadowOf(context().getSystemService(AlarmManager::class.java)) }
    private val settings by lazy { SettingsViewModel(c) }

    @Before
    fun setUp() {
        shadowOf(context() as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        useContainer(c)
        c.notifier.ensureChannels()
        nm.cancelAll()
    }

    private fun waitFor(what: String, condition: suspend () -> Boolean) = runBlocking {
        repeat(150) {
            if (condition()) return@runBlocking
            ShadowLooper.idleMainLooper()
            delay(20)
        }
        fail("Condition non atteinte : $what")
    }

    private fun notification(id: Int): Notification? = shadowOf(nm).getNotification(id)
    private fun alarm(action: String) = alarms.scheduledAlarms.firstOrNull { shadowOf(it.operation).savedIntent.action == action }
    private fun alarmAt(action: String): Instant? = alarm(action)?.let { Instant.ofEpochMilli(it.triggerAtTime) }

    /** Livre une diffusion (alarme ou action de notification) au récepteur, comme Android. */
    private fun deliver(pending: PendingIntent?) = TrackingActionReceiver().onReceive(context(), shadowOf(pending!!).savedIntent)

    /**
     * Réglage modifié comme depuis l'écran Réglages ([SettingsViewModel]), puis attente de la fin de
     * son enregistrement et de la reprogrammation : aucune coroutine du fil principal ne doit rester
     * en cours quand le test bloque ce fil (runBlocking).
     */
    private fun set(change: SettingsViewModel.() -> Job) {
        val job = settings.change()
        waitFor("réglage enregistré") { job.isCompleted }
    }

    private fun current() = runBlocking { c.tracker.currentSession() }
    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    private fun Notification.action(title: String) = actions.first { it.title.toString() == title }.actionIntent

    @Test
    fun bedtimeStartsSleepAtTheScheduledTimeAndCanBeUndone() {
        runBlocking { c.tracker.switchTo(WORK, subId = "work_perso") }
        set { setBedtimeMinutes(23 * 60) }
        set { setBedtime(true) }
        waitFor("coucher programmé à 23:00") { alarmAt(TrackingActionReceiver.ACTION_BEDTIME) == at("2026-11-04T23:00") }
        assertEquals("alarme inexacte, sans réveil forcé", AlarmManager.RTC, alarm(TrackingActionReceiver.ACTION_BEDTIME)!!.type)
        assertNull("réveil désactivé par défaut", alarm(TrackingActionReceiver.ACTION_WAKE))

        // L'alarme arrive en retard (économie d'énergie) : l'heure prévue est enregistrée.
        val bedtime = alarm(TrackingActionReceiver.ACTION_BEDTIME)!!.operation
        time.advance(Duration.between(time.now, at("2026-11-04T23:07")))
        deliver(bedtime)
        waitFor("Sommeil démarré") { current()?.activityId == SLEEP }
        val sleep = current()!!
        assertEquals(at("2026-11-04T23:00"), sleep.start)
        assertEquals("schedule", sleep.source)
        val work = runBlocking { c.store.allSessions() }.first { it.activityId == WORK }
        assertEquals(at("2026-11-04T23:00"), work.end)
        waitFor("notification du coucher") { notification(TrackingNotifier.BEDTIME_ID) != null }
        val n = notification(TrackingNotifier.BEDTIME_ID)!!
        assertEquals("Sommeil démarré", n.title())
        assertEquals("Depuis 23:00 · bonne nuit.", n.text())
        assertEquals(listOf("Annuler"), n.actions.map { it.title.toString() })
        val channel = nm.getNotificationChannel(TrackingNotifier.SLEEP_CHANNEL_ID)
        assertNull("canal sans son…", channel.sound)
        assertTrue("…ni vibration", !channel.shouldVibrate())
        // Échéance suivante : le lendemain ; la même alarme livrée deux fois ne fait rien de plus.
        waitFor("coucher suivant") { alarmAt(TrackingActionReceiver.ACTION_BEDTIME) == at("2026-11-05T23:00") }
        deliver(bedtime)

        // « Annuler » : Travail · Perso reprend comme si rien ne s'était passé.
        time.advance(Duration.ofMinutes(2))
        deliver(n.action("Annuler"))
        waitFor("Travail repris") { current()?.activityId == WORK }
        assertEquals(work.id, current()!!.id)
        assertEquals("work_perso", current()!!.subId)
        assertTrue(runBlocking { c.store.allSessions() }.none { it.activityId == SLEEP })
        waitFor("notification retirée") { notification(TrackingNotifier.BEDTIME_ID) == null }
    }

    @Test
    fun wakeStopsSleepAtTheScheduledTimeAndCanBeUndone() {
        time.now = at("2026-11-04T23:30")
        runBlocking { c.tracker.switchTo(SLEEP) }
        set { setWakeMinutes(7 * 60) }
        set { setWake(true) }
        waitFor("réveil programmé à 07:00") { alarmAt(TrackingActionReceiver.ACTION_WAKE) == at("2026-11-05T07:00") }
        val wake = alarm(TrackingActionReceiver.ACTION_WAKE)!!.operation

        time.advance(Duration.between(time.now, at("2026-11-05T07:20")))
        deliver(wake)
        waitFor("Sommeil arrêté") { current() == null }
        val slept = runBlocking { c.store.allSessions() }.single()
        assertEquals(at("2026-11-05T07:00"), slept.end)
        waitFor("notification du réveil") { notification(TrackingNotifier.WAKE_ID) != null }
        val n = notification(TrackingNotifier.WAKE_ID)!!
        assertEquals("Bonjour", n.title())
        assertEquals("Sommeil : 7 h 30 (23:30 – 07:00).", n.text())
        // Deux favorites pour enchaîner, puis « Je dors encore ».
        assertEquals(listOf("Pause", "Travail", "Je dors encore"), n.actions.map { it.title.toString() })

        deliver(n.action("Je dors encore"))
        waitFor("Sommeil repris") { current()?.activityId == SLEEP }
        assertEquals(slept.id, current()!!.id)
        waitFor("notification retirée") { notification(TrackingNotifier.WAKE_ID) == null }
    }

    @Test
    fun whatTheUserDidMeanwhileAlwaysWins() {
        set { setBedtimeMinutes(23 * 60) }
        set { setBedtime(true) }
        set { setWakeMinutes(7 * 60) }
        set { setWake(true) }
        waitFor("alarmes programmées") {
            alarmAt(TrackingActionReceiver.ACTION_BEDTIME) == at("2026-11-04T23:00") &&
                alarmAt(TrackingActionReceiver.ACTION_WAKE) == at("2026-11-05T07:00")
        }
        val bedtime = alarm(TrackingActionReceiver.ACTION_BEDTIME)!!.operation
        val wake = alarm(TrackingActionReceiver.ACTION_WAKE)!!.operation

        // Travail démarré à 23:10 ; l'alarme de 23:00 n'arrive qu'à 23:20 : rien ne change.
        time.now = at("2026-11-04T23:10")
        runBlocking { c.tracker.switchTo(WORK) }
        time.now = at("2026-11-04T23:20")
        deliver(bedtime)
        waitFor("coucher traité") { alarmAt(TrackingActionReceiver.ACTION_BEDTIME) == at("2026-11-05T23:00") }
        assertEquals(WORK, current()!!.activityId)
        assertNull(notification(TrackingNotifier.BEDTIME_ID))

        // Réveil sans Sommeil en cours : rien n'est arrêté.
        time.now = at("2026-11-05T07:01")
        deliver(wake)
        waitFor("réveil traité") { alarmAt(TrackingActionReceiver.ACTION_WAKE) == at("2026-11-06T07:00") }
        assertEquals(WORK, current()!!.activityId)
        assertNull(notification(TrackingNotifier.WAKE_ID))
    }

    @Test
    fun changingTheSettingIsNeverRetroactiveAndDisablingCancels() {
        // 23:30 : le coucher est réglé sur 23:00 (déjà passé) → pas de Sommeil rétroactif.
        time.now = at("2026-11-04T23:30")
        runBlocking { c.tracker.switchTo(WORK) }
        set { setBedtimeMinutes(23 * 60) }
        set { setBedtime(true) }
        waitFor("prochain coucher demain") { alarmAt(TrackingActionReceiver.ACTION_BEDTIME) == at("2026-11-05T23:00") }
        assertEquals(WORK, current()!!.activityId)

        // Jours de semaine seulement : vendredi 6 → lundi 9 novembre.
        time.now = at("2026-11-06T23:30")
        set { setBedtimeDays((1..5).toSet()) }
        waitFor("coucher du lundi") { alarmAt(TrackingActionReceiver.ACTION_BEDTIME) == at("2026-11-09T23:00") }

        set { setBedtime(false) }
        waitFor("alarme annulée") { alarm(TrackingActionReceiver.ACTION_BEDTIME) == null }
    }

    @Test
    fun anArchivedSleepActivityIsRestored() {
        runBlocking { c.catalog.setArchived(SLEEP, true) }
        set { setBedtimeMinutes(22 * 60) }
        set { setBedtime(true) }
        waitFor("coucher programmé") { alarmAt(TrackingActionReceiver.ACTION_BEDTIME) == at("2026-11-04T22:00") }
        val bedtime = alarm(TrackingActionReceiver.ACTION_BEDTIME)!!.operation
        time.now = at("2026-11-04T22:00")
        deliver(bedtime)
        waitFor("Sommeil démarré") { current() != null }
        val activity = runBlocking { c.store.activity(current()!!.activityId) }!!
        assertEquals("même activité, désarchivée", SLEEP, activity.id)
        assertTrue(!activity.archived)
    }
}
