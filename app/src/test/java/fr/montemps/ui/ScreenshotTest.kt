package fr.montemps.ui

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.montemps.MainActivity
import fr.montemps.MonTempsApp
import fr.montemps.domain.model.DefaultActivities
import fr.montemps.domain.model.Goal
import fr.montemps.domain.model.GoalPeriod
import fr.montemps.notification.TrackingNotifier
import fr.montemps.testutil.FakeTimeSource
import fr.montemps.testutil.context
import fr.montemps.testutil.testContainer
import fr.montemps.testutil.useContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Vérification visuelle : rend les vrais écrans (données réelles en base, horloge figée) et
 * enregistre des PNG dans docs/screenshots. Gabarit proche d'un écran de 6,83" (≈ 412 × 892 dp).
 * Le Redmi Note 17 Pro 5G (1280 × 2772 px) fait entre ≈ 393 et 465 dp de large selon la densité
 * choisie par HyperOS : le cas le plus étroit sert au test « grand texte ».
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h892dp-xhdpi")
class ScreenshotTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val paris = ZoneId.of("Europe/Paris")
    private fun at(s: String): Instant = LocalDateTime.parse(s).atZone(paris).toInstant()
    private val time = FakeTimeSource(now = at("2026-11-04T15:42:10"))
    private val c = testContainer(time)
    private val outDir = File(System.getProperty("montemps.screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    private fun seed(withActive: Boolean = true) = runBlocking {
        val e = c.editor
        // Objectifs : Coréen 30 min par jour, Sport 3 h par semaine.
        val korean = DefaultActivities.all.first { it.id == DefaultActivities.KOREAN }
        val sport = DefaultActivities.all.first { it.id == DefaultActivities.SPORT }
        c.catalog.edit(korean.id, korean.name, korean.color, null, goal = Goal(30, GoalPeriod.DAY))
        c.catalog.edit(sport.id, sport.name, sport.color, null, goal = Goal(180, GoalPeriod.WEEK))
        // Semaine précédente (pour la comparaison « au même moment »).
        e.addPastSession(DefaultActivities.WORK, at("2026-10-26T09:00"), at("2026-10-26T17:00"), null)
        e.addPastSession(DefaultActivities.WORK, at("2026-10-27T09:00"), at("2026-10-27T16:00"), null)
        e.addPastSession(DefaultActivities.SPORT, at("2026-10-27T18:00"), at("2026-10-27T19:00"), null)
        e.addPastSession(DefaultActivities.WORK, at("2026-10-28T09:00"), at("2026-10-28T12:00"), null)
        // Coréen trois jours d'affilée (série en cours).
        e.addPastSession(DefaultActivities.KOREAN, at("2026-11-02T07:00"), at("2026-11-02T07:35"), null)
        e.addPastSession(DefaultActivities.KOREAN, at("2026-11-04T07:00"), at("2026-11-04T07:30"), null)
        // Semaine en cours (lundi 2 → mercredi 4 novembre), dont une session qui traverse minuit.
        e.addPastSession(DefaultActivities.WORK, at("2026-11-02T08:30"), at("2026-11-02T12:15"), null)
        e.addPastSession(DefaultActivities.MEAL, at("2026-11-02T12:15"), at("2026-11-02T13:00"), null)
        e.addPastSession(DefaultActivities.WORK, at("2026-11-02T13:00"), at("2026-11-02T17:40"), null)
        e.addPastSession(DefaultActivities.SPORT, at("2026-11-02T18:30"), at("2026-11-02T19:30"), null)
        e.addPastSession(DefaultActivities.KOREAN, at("2026-11-03T07:00"), at("2026-11-03T07:30"), "Vocabulaire")
        e.addPastSession(DefaultActivities.WORK, at("2026-11-03T09:00"), at("2026-11-03T17:00"), null)
        e.addPastSession(DefaultActivities.MUSIC, at("2026-11-03T20:00"), at("2026-11-03T21:00"), null)
        e.addPastSession(DefaultActivities.FREE_TIME, at("2026-11-03T22:30"), at("2026-11-04T00:30"), "Film")
        e.addPastSession(DefaultActivities.DRIVING, at("2026-11-04T07:40"), at("2026-11-04T08:10"), null)
        e.addPastSession(DefaultActivities.WORK, at("2026-11-04T08:10"), at("2026-11-04T12:00"), null)
        e.addPastSession(DefaultActivities.MEAL, at("2026-11-04T12:00"), at("2026-11-04T12:45"), null)
        e.addPastSession(DefaultActivities.WORK, at("2026-11-04T12:45"), at("2026-11-04T15:00"), "Rapport mensuel")
        e.addPastSession(DefaultActivities.BREAK, at("2026-11-04T15:00"), at("2026-11-04T15:20"), null)
        if (withActive) {
            val now = time.now
            time.now = at("2026-11-04T15:20")
            c.tracker.switchTo(DefaultActivities.WORK)
            time.now = at("2026-11-04T15:20")
            time.advance(java.time.Duration.between(time.now, now)) // horloges calendaire et monotone cohérentes
        }
    }

    private fun launch() {
        shadowOf(context() as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        useContainer(c)
        c.notifier.ensureChannels()
        runBlocking { c.preferences.setNotificationPermissionAsked() }
        ActivityScenario.launch(MainActivity::class.java)
        rule.waitForIdle()
    }

    /**
     * Dessine toutes les fenêtres de l'app (activité puis éventuelles boîtes de dialogue, avec voile)
     * sur un même bitmap. On dessine les vues directement (rendu natif Robolectric).
     */
    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(300)
        rule.waitForIdle()
        draw(name)
    }

    /** Capture sans attendre le repos (horloge des animations figée par le test). */
    private fun shotNow(name: String) {
        ShadowLooper.idleMainLooper()
        draw(name)
    }

    private fun draw(name: String) {
        val views = rootViews()
        val base = views.first()
        val bmp = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        base.draw(canvas)
        views.drop(1).forEach { v ->
            canvas.drawColor(0x99000000.toInt())
            canvas.save()
            canvas.translate((base.width - v.width) / 2f, (base.height - v.height) / 2f)
            v.draw(canvas)
            canvas.restore()
        }
        save(bmp, name)
    }

    private fun shotDialog(name: String) = shot(name)

    @Suppress("UNCHECKED_CAST")
    private fun rootViews(): List<View> {
        val wmgClass = Class.forName("android.view.WindowManagerGlobal")
        val wmg = wmgClass.getMethod("getInstance").invoke(null)
        val field = wmgClass.getDeclaredField("mViews").apply { isAccessible = true }
        return (field.get(wmg) as List<View>).filter { it.isShown && it.width > 0 }
    }

    private fun save(bitmap: Bitmap, name: String) {
        FileOutputStream(File(outDir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tap(text: String) =
        tapOn(hasText(text) and (hasClickAction() or androidx.compose.ui.test.hasAnyAncestor(hasClickAction())), unmerged = true)

    private fun tapOn(matcher: androidx.compose.ui.test.SemanticsMatcher, unmerged: Boolean = false) {
        // Les données Room arrivent de façon asynchrone : attendre que le nœud existe.
        rule.waitUntil(5_000) {
            // Fait avancer l'horloge du looper principal (images, animations de navigation).
            org.robolectric.shadows.ShadowLooper.idleMainLooper(16, java.util.concurrent.TimeUnit.MILLISECONDS)
            rule.onAllNodes(matcher, unmerged).fetchSemanticsNodes().isNotEmpty()
        }
        val node = rule.onAllNodes(matcher, unmerged).onFirst()
        runCatching { node.performScrollTo() } // éléments plus bas dans une liste défilante
        node.performClick()
        rule.waitForIdle()
    }

    @Test
    fun mainScreensLight() {
        seed()
        launch()
        shot("01-aujourdhui")
        tap("Historique")
        shot("03-historique")
        tapOn(androidx.compose.ui.test.hasContentDescription("Travail, de 12:45", substring = true))
        shot("04-modifier-session")
        tap("Enregistrer") // aucune modification : retour à l'historique
        tap("Bilans")
        shot("05-bilan-jour")
        tap("Semaine")
        shot("06-bilan-semaine")
        tap("Réglages")
        shot("07-reglages")
        tap("Gérer les activités")
        shot("08-activites")
    }

    /** Pause en cours : « Reprendre Travail », objectifs du jour (page entière). */
    @Test
    @Config(qualifiers = "w412dp-h1500dp-xhdpi")
    fun breakWithResumeAndGoalsFullPage() {
        seed(withActive = false)
        runBlocking {
            val now = time.now
            time.now = at("2026-11-04T15:30")
            c.tracker.switchTo(DefaultActivities.BREAK)
            time.advance(java.time.Duration.between(time.now, now))
        }
        launch()
        shot("21-aujourdhui-pause-page-entiere")
    }

    /** Appui long : « Démarrer plus tôt », puis le message « Annuler ». */
    @Test
    fun startEarlierThenUndo() {
        seed()
        launch()
        val sport = androidx.compose.ui.test.hasContentDescription("Sport") and hasClickAction()
        rule.waitUntil(5_000) { rule.onAllNodes(sport).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(sport).onFirst().performTouchInput { longClick() }
        shotDialog("22-demarrer-plus-tot")
        tap("Il y a 10 min")
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Annuler")).fetchSemanticsNodes().isNotEmpty() }
        shot("23-annuler")
    }

    /** Historique : période non suivie → saisie préremplie. */
    @Test
    fun fillAGapFromHistory() {
        seed()
        launch()
        tap("Historique")
        tapOn(androidx.compose.ui.test.hasContentDescription("Non suivi de 00:30", substring = true))
        shot("24-combler-un-trou")
    }

    @Test
    @Config(qualifiers = "w412dp-h1500dp-xhdpi")
    fun dayReportFullPage() {
        seed()
        launch()
        tap("Bilans")
        shot("05b-bilan-jour-page-entiere")
    }

    @Test
    @Config(qualifiers = "w412dp-h2400dp-xhdpi")
    fun settingsFullPage() {
        seed()
        runBlocking {
            c.preferences.setIdleReminder(true)
            c.preferences.setEveningSummary(true)
        }
        launch()
        tap("Réglages")
        shot("07b-reglages-page-entiere")
    }

    @Test
    fun activityEditorWithGoal() {
        seed()
        launch()
        tap("Réglages")
        tap("Gérer les activités")
        // Le champ « Nom » de la boîte de dialogue peut recevoir le focus (mode non tactile du
        // simulateur) : son curseur clignote indéfiniment. On fige donc l'horloge des animations.
        rule.mainClock.autoAdvance = false
        tap("Coréen")
        rule.mainClock.advanceTimeBy(1_500)
        shotNow("25-modifier-activite")
    }

    @Test
    fun idleNotificationRendering() {
        val idle = c.notifier.buildIdle(
            fr.montemps.notification.IdleContent(
                "14:10",
                listOf(fr.montemps.notification.FavoriteAction("work", "Travail"), fr.montemps.notification.FavoriteAction("break", "Pause")),
            ),
        )
        val ib = Notification.Builder.recoverBuilder(context(), idle)
        renderRemoteViews(ib.createBigContentView() ?: ib.createContentView(), "26-notification-inactivite")
        // Le résumé du soir (style « texte long ») n'est pas rendu fidèlement par le simulateur :
        // son contenu est vérifié par ScheduledNotificationsTest.
    }

    @Test
    fun overlapIsRefusedInEditor() {
        seed()
        launch()
        tap("Historique")
        tap("Ajouter") // par défaut : 14:40 → 15:40, chevauche des sessions existantes
        tap("Choisir une activité")
        tap("Sport")
        tap("Enregistrer")
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Chevauche", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        shot("09-chevauchement-refuse")
    }

    @Test
    @Config(qualifiers = "w412dp-h892dp-night-xhdpi")
    fun darkTheme() {
        seed()
        launch()
        shot("02-aujourdhui-sombre")
        tap("Historique")
        shot("16-historique-sombre")
        tap("Bilans")
        tap("Semaine")
        shot("17-bilan-semaine-sombre")
    }

    /** Page « Semaine » entière (gabarit allongé) pour voir aussi la vue par jour. */
    @Test
    @Config(qualifiers = "w412dp-h1460dp-xhdpi")
    fun weeklyReportFullPage() {
        seed()
        launch()
        tap("Bilans")
        tap("Semaine")
        shot("06b-bilan-semaine-page-entiere")
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun todayLargeFont() {
        RuntimeEnvironment.setFontScale(1.8f)
        seed()
        launch()
        shot("10-aujourdhui-grand-texte")
    }

    @Test
    fun emptyStateOnFirstLaunch() {
        launch()
        shot("11-premier-lancement")
    }

    @Test
    fun forgottenSessionPrompt() {
        runBlocking {
            time.now = at("2026-11-03T21:05")
            c.tracker.switchTo(DefaultActivities.WORK)
            time.advance(java.time.Duration.between(time.now, at("2026-11-04T09:30")))
        }
        launch()
        shotDialog("12-session-oubliee")
        tap("Corriger l'heure de fin")
        shotDialog("13-session-oubliee-correction")
    }

    /**
     * Rendu de la notification elle-même (vue étendue construite par Android) via le vrai
     * [TrackingNotifier.build]. Le début est exprimé par rapport à l'horloge système simulée de
     * Robolectric pour que le chronomètre natif affiche une valeur réaliste (≈ 22 min).
     */
    @Test
    fun notificationRendering() {
        // Horloge murale *simulée* par Robolectric (celle que lit le chronomètre de la notification).
        val start = Instant.ofEpochMilli(org.robolectric.shadows.ShadowSystem.currentTimeMillis()).minusSeconds(22 * 60 + 10)
        val content = fr.montemps.notification.TrackingNotificationContent(
            sessionId = 1, activityName = "Travail", activityColor = 0xFF4A78E0.toInt(),
            start = start, startLabel = "15:20",
            favorites = listOf(fr.montemps.notification.FavoriteAction(DefaultActivities.BREAK, "Pause")),
            hideNameOnLockScreen = false,
        )
        val n = c.notifier.build(content)
        val builder = Notification.Builder.recoverBuilder(context(), n)
        renderRemoteViews(builder.createBigContentView() ?: builder.createContentView(), "14-notification")
    }

    /** Notification de rappel (session longue), telle que construite par Android. */
    @Test
    fun reminderNotificationRendering() {
        val n = c.notifier.buildReminder(
            fr.montemps.notification.ReminderContent(
                sessionId = 1, activityName = "Travail", sinceLabel = "hier 21:05", durationLabel = "12 h 25",
                hideNameOnLockScreen = false,
            ),
        )
        // Vue étendue : titre, texte et actions « Continuer » / « Corriger ».
        val builder = Notification.Builder.recoverBuilder(context(), n)
        renderRemoteViews(builder.createBigContentView() ?: builder.createContentView(), "15-notification-rappel")
    }

    @Test
    fun homeScreenWidgetsLight() = homeScreen("19-ecran-accueil-widgets", top = 0xFFE7E3DC.toInt(), bottom = 0xFFC3CAD3.toInt())

    @Test
    @Config(qualifiers = "w412dp-h892dp-night-xhdpi")
    fun homeScreenWidgetsDark() = homeScreen("20-ecran-accueil-widgets-sombre", top = 0xFF2B2F36.toInt(), bottom = 0xFF0E0F12.toInt())

    /**
     * Rendu réel des deux widgets (RemoteViews appliquées par le gestionnaire de widgets simulé),
     * posés sur un fond d'écran neutre. Le reste de l'écran d'accueil n'est pas simulé.
     */
    private fun homeScreen(name: String, top: Int, bottom: Int) {
        seed()
        useContainer(c)
        // Chronomètres natifs : base exprimée dans l'horloge monotone simulée de Robolectric.
        time.elapsed = android.os.SystemClock.elapsedRealtime()
        val awm = shadowOf(android.appwidget.AppWidgetManager.getInstance(context()))
        awm.setAlwaysRecreateViewsDuringUpdate(true)
        val trackingId = awm.createWidget(fr.montemps.surfaces.TrackingWidgetProvider::class.java, fr.montemps.R.layout.widget_tracking)
        val activitiesId = awm.createWidget(fr.montemps.surfaces.ActivitiesWidgetProvider::class.java, fr.montemps.R.layout.widget_activities)
        runBlocking {
            kotlinx.coroutines.delay(300) // laisse finir les mises à jour asynchrones déclenchées à la création
            c.surfaces.refresh()
        }
        val d = context().resources.displayMetrics.density
        val bmp = Bitmap.createBitmap((412 * d).toInt(), (892 * d).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, bmp.height.toFloat(), top, bottom, android.graphics.Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), paint)
        canvas.drawBitmap(renderView(awm.getViewFor(trackingId), 380, 190), 16 * d, 92 * d, null)
        canvas.drawBitmap(renderView(awm.getViewFor(activitiesId), 380, 338), 16 * d, 302 * d, null)
        save(bmp, name)
    }

    private fun renderView(view: View, widthDp: Int, heightDp: Int): Bitmap {
        val d = context().resources.displayMetrics.density
        val w = (widthDp * d).toInt()
        val h = (heightDp * d).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        return bmp
    }

    private fun renderRemoteViews(remote: android.widget.RemoteViews, name: String) {
        val parent = FrameLayout(context())
        val view = remote.apply(context(), parent)
        val width = (412 * 2)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bmp = Bitmap.createBitmap(view.measuredWidth.coerceAtLeast(1), view.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFFF3F3F3.toInt())
        view.draw(canvas)
        save(bmp, name)
    }
}
