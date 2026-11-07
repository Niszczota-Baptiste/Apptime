package fr.montemps.surfaces

import android.app.AlarmManager
import android.app.Application
import android.appwidget.AppWidgetManager
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.montemps.MonTempsApp
import fr.montemps.R
import fr.montemps.MainActivity
import fr.montemps.domain.model.DefaultActivities.BREAK
import fr.montemps.domain.model.DefaultActivities.MEAL
import fr.montemps.domain.model.DefaultActivities.SPORT
import fr.montemps.domain.model.DefaultActivities.WORK
import fr.montemps.notification.TrackingActionReceiver
import fr.montemps.testutil.FakeTimeSource
import fr.montemps.testutil.context
import fr.montemps.testutil.testContainer
import fr.montemps.testutil.useContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class SurfacesTest {
    private val time = FakeTimeSource()
    private val c = testContainer(time)
    private val widgets by lazy { shadowOf(AppWidgetManager.getInstance(context())) }

    @Before
    fun setUp() {
        useContainer(c)
        // La création d'un widget déclenche aussi une mise à jour asynchrone (onUpdate) : on
        // demande au gestionnaire simulé de reconstruire la vue à chaque mise à jour, comme un
        // lanceur qui applique les RemoteViews sur son propre fil, pour ne jamais lire une vue en
        // cours de modification.
        widgets.setAlwaysRecreateViewsDuringUpdate(true)
    }

    private fun waitFor(what: String, condition: suspend () -> Boolean) = runBlocking {
        repeat(150) {
            if (condition()) return@runBlocking
            ShadowLooper.idleMainLooper()
            delay(20)
        }
        fail("Condition non atteinte : $what")
    }

    private fun View.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun View.visible(id: Int) = findViewById<View>(id).visibility == View.VISIBLE

    /** Tous les boutons d'activité d'un widget (dans l'ordre d'affichage). */
    private fun buttons(root: View): List<View> {
        val out = mutableListOf<View>()
        fun walk(v: View) {
            if (v.id == R.id.widget_button) out += v
            if (v is ViewGroup) (0 until v.childCount).forEach { walk(v.getChildAt(it)) }
        }
        walk(root)
        return out
    }

    /** Clique comme un utilisateur, puis livre l'action émise au récepteur (cf. NotificationTest). */
    private fun click(view: View) {
        val app = shadowOf(context() as Application)
        val before = app.broadcastIntents.size
        assertTrue(view.performClick())
        ShadowLooper.idleMainLooper()
        val sent = app.broadcastIntents.drop(before)
        assertTrue("le clic doit émettre une action", sent.isNotEmpty())
        sent.forEach {
            assertEquals(TrackingActionReceiver::class.java.name, it.component!!.className)
            TrackingActionReceiver().onReceive(context(), it)
        }
    }

    @Test
    fun trackingWidgetFollowsTheActiveSessionWithANativeChronometer() {
        val id = widgets.createWidget(TrackingWidgetProvider::class.java, R.layout.widget_tracking)
        runBlocking { c.surfaces.refresh() }
        var v = widgets.getViewFor(id)
        assertTrue(v.visible(R.id.widget_idle_title))
        assertTrue(!v.visible(R.id.widget_chronometer))

        runBlocking { c.tracker.switchTo(WORK) } // le changement réaligne aussi les widgets
        v = widgets.getViewFor(id)
        assertEquals("Travail", v.text(R.id.widget_title))
        val chrono = v.findViewById<Chronometer>(R.id.widget_chronometer)
        assertTrue(v.visible(R.id.widget_chronometer))
        // Base monotone : session démarrée à l'instant → base = horloge monotone actuelle.
        assertEquals(time.elapsed, chrono.base)
        assertTrue(v.visible(R.id.widget_stop))
        assertEquals("Depuis 09:00 · aujourd'hui", v.text(R.id.widget_subtitle))
        assertTrue(v.visible(R.id.widget_info))
        // Favorites par défaut : Pause, Travail ; Travail est en cours donc seule Pause est proposée.
        assertEquals(listOf("Pause"), buttons(v).map { it.text(R.id.widget_button_label) })

        time.advance(Duration.ofHours(1))
        runBlocking { c.tracker.switchTo(BREAK) }
        v = widgets.getViewFor(id)
        assertEquals("Pause", v.text(R.id.widget_title))
        assertEquals(time.elapsed, v.findViewById<Chronometer>(R.id.widget_chronometer).base)
        // Total du jour animé : 1 h déjà suivie.
        assertEquals(time.elapsed - Duration.ofHours(1).toMillis(), v.findViewById<Chronometer>(R.id.widget_today).base)

        time.advance(Duration.ofMinutes(30))
        runBlocking { c.tracker.stop() }
        v = widgets.getViewFor(id)
        assertTrue(v.visible(R.id.widget_idle_title))
        assertEquals("Aujourd'hui 1 h 30", v.text(R.id.widget_subtitle))
    }

    @Test
    fun duringABreakWidgetAndTileOfferToResumeThePreviousActivity() {
        val id = widgets.createWidget(TrackingWidgetProvider::class.java, R.layout.widget_tracking)
        runBlocking {
            c.tracker.switchTo(WORK)
            time.advance(Duration.ofMinutes(45))
            c.tracker.switchTo(BREAK)
        }
        val labels = buttons(widgets.getViewFor(id)).map { it.text(R.id.widget_button_label) }
        assertEquals("Reprendre Travail", labels.first())
        val tile = TileModel.from(runBlocking { c.surfaces.load() })
        assertEquals(TileAction.Resume(WORK), tile.action)
        assertEquals("Reprendre Travail", tile.subtitle)
    }

    @Test
    fun activitiesWidgetListsShortcutsAndMarksTheActiveOne() {
        val id = widgets.createWidget(ActivitiesWidgetProvider::class.java, R.layout.widget_activities)
        runBlocking { c.tracker.switchTo(WORK) }
        val all = buttons(widgets.getViewFor(id))
        assertEquals(
            listOf("Travail", "Déplacement", "Pause", "Repas", "Coréen", "Sport", "Écriture", "Musique", "Temps libre", "Autre", "Sommeil"),
            all.map { it.text(R.id.widget_button_label) },
        )
        assertEquals("Travail, en cours", all[0].contentDescription)
        assertEquals("Démarrer Pause", all[2].contentDescription)
    }

    @Test
    fun widgetButtonsGoThroughTheSharedTrackingLogic() {
        val id = widgets.createWidget(ActivitiesWidgetProvider::class.java, R.layout.widget_activities)
        runBlocking { c.surfaces.refresh() }
        click(buttons(widgets.getViewFor(id)).first { it.text(R.id.widget_button_label) == "Repas" })
        waitFor("Repas démarré") { c.tracker.currentSession()?.activityId == MEAL }
        assertEquals("widget", runBlocking { c.tracker.currentSession() }!!.source)

        time.advance(Duration.ofMinutes(5))
        waitFor("widget rafraîchi") { widgets.getViewFor(id).visible(R.id.widget_stop) }
        click(widgets.getViewFor(id).findViewById(R.id.widget_stop))
        waitFor("suivi arrêté") { c.tracker.currentSession() == null }
    }

    @Test
    fun anActivityWithSubCategoriesOpensTheChooserInTheApp() {
        val id = widgets.createWidget(ActivitiesWidgetProvider::class.java, R.layout.widget_activities)
        runBlocking { c.surfaces.refresh() }
        val app = shadowOf(context() as Application)
        val broadcasts = app.broadcastIntents.size
        assertTrue(buttons(widgets.getViewFor(id)).first { it.text(R.id.widget_button_label) == "Sport" }.performClick())
        ShadowLooper.idleMainLooper()
        val opened = app.nextStartedActivity
        assertEquals(MainActivity::class.java.name, opened.component!!.className)
        assertEquals(SPORT, opened.getStringExtra(MainActivity.EXTRA_CHOOSE_ACTIVITY))
        assertEquals("rien ne démarre avant le choix", broadcasts, app.broadcastIntents.size)
        assertNull(runBlocking { c.tracker.currentSession() })
    }

    @Test
    fun surfacesShowTheSubCategoryAndResumeIt() {
        val id = widgets.createWidget(TrackingWidgetProvider::class.java, R.layout.widget_tracking)
        runBlocking { c.tracker.switchTo(WORK, subId = "work_pro") }
        assertEquals("Travail · Pro", widgets.getViewFor(id).text(R.id.widget_title))
        time.advance(Duration.ofMinutes(30))
        runBlocking { c.tracker.switchTo(BREAK, subId = "break_youtube") }
        val v = widgets.getViewFor(id)
        assertEquals("Pause · YouTube", v.text(R.id.widget_title))
        assertEquals("Reprendre Travail · Pro", buttons(v).first().text(R.id.widget_button_label))
        val tile = TileModel.from(runBlocking { c.surfaces.load() })
        assertEquals("Reprendre Travail · Pro", tile.subtitle)
        // « Reprendre » depuis le widget : même logique partagée, sous-catégorie comprise.
        time.advance(Duration.ofMinutes(5))
        click(buttons(v).first())
        waitFor("reprise de Travail · Pro") { c.tracker.currentSession()?.subId == "work_pro" }
        // Le widget est réaligné à la fin du traitement de l'action (refreshOutputs).
        waitFor("widget rafraîchi") { widgets.getViewFor(id).text(R.id.widget_title) == "Travail · Pro" }
    }

    @Test
    fun smallSizesOnlyShowWhatFits() {
        runBlocking { c.tracker.switchTo(WORK) }
        val state = runBlocking { c.surfaces.load() }
        val compact = WidgetRenderer.tracking(context(), state, WidgetSize(250, 80), time.elapsed).apply(context(), FrameLayout(context()))
        assertTrue(compact.visible(R.id.widget_chronometer))
        assertTrue(!compact.visible(R.id.widget_info))
        assertTrue(!compact.visible(R.id.widget_favorites))
        val medium = WidgetRenderer.tracking(context(), state, WidgetSize(250, 140), time.elapsed).apply(context(), FrameLayout(context()))
        assertTrue(medium.visible(R.id.widget_info))
        assertTrue(!medium.visible(R.id.widget_favorites))
        // 160 dp de haut : deux rangées de deux activités.
        val grid = WidgetRenderer.activities(context(), state, WidgetSize(300, 160)).apply(context(), FrameLayout(context()))
        assertEquals(4, buttons(grid).size)
    }

    @Test
    fun quickSettingsTileStopsThenResumesTheLastActivity() {
        val tile = Robolectric.setupService(TrackingTileService::class.java)
        runBlocking { c.tracker.switchTo(WORK) }
        time.advance(Duration.ofMinutes(10))
        tile.onClick()
        waitFor("arrêt depuis la tuile") { c.tracker.currentSession() == null }
        time.advance(Duration.ofMinutes(1))
        tile.onClick()
        waitFor("reprise de Travail depuis la tuile") { c.tracker.currentSession()?.activityId == WORK }
        assertEquals("tile", runBlocking { c.tracker.currentSession() }!!.source)
    }

    @Test
    fun midnightRefreshIsScheduledOnlyWhenUseful() {
        val alarms = shadowOf(context().getSystemService(AlarmManager::class.java))
        fun refreshAlarm() = alarms.scheduledAlarms.firstOrNull {
            shadowOf(it.operation).savedIntent.action == TrackingActionReceiver.ACTION_REFRESH
        }
        runBlocking { c.refreshOutputs() }
        assertNull(refreshAlarm())

        runBlocking { c.tracker.switchTo(WORK) }
        val expected = SurfacesController.nextMidnight(time.now, time.zone).plusSeconds(30)
        assertEquals(expected.toEpochMilli(), refreshAlarm()!!.triggerAtTime)
        assertEquals(AlarmManager.RTC, refreshAlarm()!!.type) // pas de réveil forcé de l'appareil

        time.advance(Duration.ofMinutes(5))
        runBlocking { c.tracker.stop() }
        assertNull(refreshAlarm())
    }
}
