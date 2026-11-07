package fr.montemps.garmin

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.montemps.domain.model.CommandSource
import fr.montemps.domain.model.DefaultActivities.BREAK
import fr.montemps.domain.model.DefaultActivities.SPORT
import fr.montemps.domain.model.DefaultActivities.WORK
import fr.montemps.domain.watch.WatchCommand
import fr.montemps.notification.TrackingActionReceiver
import fr.montemps.notification.TrackingIntents
import fr.montemps.testutil.FakeTimeSource
import fr.montemps.testutil.FakeWatchLink
import fr.montemps.testutil.context
import fr.montemps.testutil.testContainer
import fr.montemps.testutil.useContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** Montre Garmin côté téléphone : activation, envoi de l'état, commandes reçues de la montre. */
@RunWith(AndroidJUnit4::class)
class WatchIntegrationTest {
    private val time = FakeTimeSource(now = Instant.parse("2026-11-05T08:00:00Z"))
    private val link = FakeWatchLink()
    private val c = testContainer(time, watchLink = link)
    private val app = context() as Application
    private val component = ComponentName(context(), WatchController.BINDING_SERVICE)

    @Before
    fun setUp() {
        useContainer(c)
    }

    private fun active(message: Map<String, Any?>?) = message?.get("a") as Map<*, *>?

    /** Remet au récepteur les commandes relayées par la liaison, comme le ferait Android. */
    private fun deliverWatchBroadcasts() {
        val intents = shadowOf(app).broadcastIntents.filter { it.action == TrackingActionReceiver.ACTION_WATCH }
        shadowOf(app).clearBroadcastIntents()
        intents.forEach {
            assertEquals("diffusion explicite vers le récepteur non exporté", TrackingActionReceiver::class.java.name, it.component?.className)
            TrackingActionReceiver().onReceive(context(), it)
        }
    }

    private fun waitFor(what: String, condition: suspend () -> Boolean) = runBlocking {
        repeat(150) {
            if (condition()) return@runBlocking
            ShadowLooper.idleMainLooper()
            delay(20)
        }
        fail("Condition non atteinte : $what")
    }

    @Test
    fun linkIsOffAndTheSdkServiceDisabledUntilTheUserEnablesIt() = runBlocking {
        val info = context().packageManager.getServiceInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
        assertFalse("service exporté du SDK désactivé dans le manifeste", info.enabled)
        assertFalse(c.watch.isEnabled())
        c.watch.start()
        assertFalse(link.started)
        c.watch.requestSync(force = true)
        c.watch.awaitSync()
        assertTrue("rien n'est envoyé tant que la liaison est coupée", link.sent.isEmpty())

        c.watch.setEnabled(true)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, context().packageManager.getComponentEnabledSetting(component))
        assertTrue(link.started)
        assertEquals(WatchLinkStatus.Ready("Forerunner 965"), c.watch.status.value)

        c.watch.setEnabled(false)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, context().packageManager.getComponentEnabledSetting(component))
        assertFalse(link.started)
    }

    @Test
    fun stateIsSentWhenItChangesOnly() = runBlocking {
        c.watch.setEnabled(true)
        c.watch.requestSync(force = true)
        c.watch.awaitSync()
        assertEquals(1, link.sent.size)
        assertNull(active(link.sent.last()))

        c.refreshOutputs()
        c.watch.awaitSync()
        time.advance(Duration.ofMinutes(3))
        c.refreshOutputs()
        c.watch.awaitSync()
        assertEquals("état inchangé (seul le temps passe) : rien de renvoyé", 1, link.sent.size)

        c.tracker.switchTo(WORK)
        waitFor("nouvel état envoyé") { active(link.sent.lastOrNull())?.get("id") == WORK }
        assertEquals(2, link.sent.size)
    }

    @Test
    fun commandFromTheWatchSwitchesActivityAndRepliesWithTheNewState() = runBlocking {
        c.watch.setEnabled(true)
        link.receive(mapOf("v" to 1, "c" to "switch", "id" to WORK, "at" to time.now.epochSecond.toInt()))
        deliverWatchBroadcasts()
        waitFor("Travail démarré depuis la montre") { c.store.activeSession()?.activityId == WORK }
        assertEquals(CommandSource.Watch.id, c.store.activeSession()!!.source)
        waitFor("état renvoyé à la montre") { active(link.sent.lastOrNull())?.get("id") == WORK }

        // Commande répétée (ex. renvoyée après une coupure) : sans effet, mais l'état est renvoyé.
        val sent = link.sent.size
        link.receive(mapOf("c" to "switch", "id" to WORK))
        deliverWatchBroadcasts()
        waitFor("état renvoyé") { link.sent.size > sent }
        assertEquals(1, c.store.sessionsOverlapping(Instant.EPOCH, time.now.plusSeconds(1)).size)
    }

    @Test
    fun offlineCommandIsAppliedAtItsOriginalTime() = runBlocking {
        c.tracker.switchTo(WORK) // 09:00
        time.advance(Duration.ofHours(2)) // 11:00
        c.watch.setEnabled(true)
        // Sortie sans téléphone : Sport choisi sur la montre à 10:20, transmis au retour à 11:00.
        val at = time.now.minus(Duration.ofMinutes(40))
        link.receive(mapOf("c" to "switch", "id" to SPORT, "at" to at.epochSecond.toInt()))
        deliverWatchBroadcasts()
        waitFor("Sport démarré à 10:20") { c.store.activeSession()?.activityId == SPORT }
        assertEquals(at, c.store.activeSession()!!.start)
        assertEquals("Travail se termine à l'heure du changement", at, c.store.recentEndedSessions(1).single().end)
    }

    @Test
    fun outdatedOfflineCommandIsRefusedWithAMessage() = runBlocking {
        c.watch.setEnabled(true)
        val stopAt = time.now.plus(Duration.ofMinutes(30)) // 09:30 sur la montre, hors ligne
        time.advance(Duration.ofHours(1))
        c.tracker.switchTo(WORK) // 10:00 sur le téléphone : plus récent que l'arrêt demandé
        time.advance(Duration.ofMinutes(5))
        link.receive(mapOf("c" to "stop", "at" to stopAt.epochSecond.toInt()))
        deliverWatchBroadcasts()
        waitFor("refus renvoyé à la montre") { link.sent.any { it["msg"] == "Ignorée : changement plus récent sur le téléphone" } }
        assertEquals("le téléphone fait foi", WORK, c.store.activeSession()?.activityId)
    }

    @Test
    fun resumeAfterABreakReturnsToTheInterruptedActivity() = runBlocking {
        c.tracker.switchTo(WORK)
        time.advance(Duration.ofHours(1))
        c.tracker.switchTo(BREAK)
        time.advance(Duration.ofMinutes(10))
        c.watch.setEnabled(true)
        link.receive(mapOf("c" to "resume"))
        deliverWatchBroadcasts()
        waitFor("Travail repris") { c.store.activeSession()?.activityId == WORK }
    }

    @Test
    fun subCategoryChosenOnTheWatchIsAppliedOrRefused() = runBlocking {
        c.watch.setEnabled(true)
        link.receive(mapOf("c" to "switch", "id" to WORK, "sub" to "work_pro"))
        deliverWatchBroadcasts()
        waitFor("Travail · Pro démarré depuis la montre") { c.store.activeSession()?.subId == "work_pro" }
        waitFor("état renvoyé avec le nom complet") { active(link.sent.lastOrNull())?.get("n") == "Travail · Pro" }
        assertEquals("work_pro", active(link.sent.last())!!["sub"])

        // Sous-catégorie retirée sur le téléphone entre-temps : refus expliqué, rien ne change.
        c.catalog.setSubArchived("work_perso", true)
        link.receive(mapOf("c" to "switch", "id" to WORK, "sub" to "work_perso"))
        deliverWatchBroadcasts()
        waitFor("refus renvoyé à la montre") { link.sent.any { it["msg"] == "Sous-catégorie indisponible" } }
        assertEquals("work_pro", c.store.activeSession()!!.subId)
        // Le sous-menu de la montre ne propose plus la sous-catégorie retirée.
        val work = (link.sent.last()["acts"] as List<*>).map { it as Map<*, *> }.first { it["id"] == WORK }
        assertEquals(listOf("work_pro"), (work["subs"] as List<*>).map { (it as Map<*, *>)["id"] })
    }

    @Test
    fun commandsSurviveTheTripThroughTheReceiverIntent() {
        val at = Instant.parse("2026-11-05T07:20:00Z")
        listOf(
            WatchCommand.Status,
            WatchCommand.Expired,
            WatchCommand.Switch("work"),
            WatchCommand.Switch("sport", at),
            WatchCommand.Switch("sport", at, "sport_running"),
            WatchCommand.Switch("work", null, "work_pro"),
            WatchCommand.Stop(),
            WatchCommand.Stop(at),
            WatchCommand.Resume(at),
        ).forEach { command ->
            assertEquals(command, TrackingIntents.watchCommandOf(TrackingIntents.watchCommand(context(), command)))
        }
    }

    @Test
    fun realSdkReportsThatGarminConnectIsMissing() {
        // Vrai SDK Connect IQ : Garmin Connect n'est pas installé dans l'environnement de test.
        val real = ConnectIqLink(context())
        real.start(onMessage = {}, onWatchConnected = {})
        ShadowLooper.idleMainLooper()
        assertEquals(WatchLinkStatus.GarminConnectMissing, real.status.value)
        real.stop()
        assertEquals(WatchLinkStatus.Off, real.status.value)
    }
}
