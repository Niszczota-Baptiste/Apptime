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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.montemps.MainActivity
import fr.montemps.MonTempsApp
import fr.montemps.domain.model.DefaultActivities
import fr.montemps.notification.TrackingNotifier
import fr.montemps.testutil.FakeTimeSource
import fr.montemps.testutil.context
import fr.montemps.testutil.testContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Vérification visuelle : rend les vrais écrans (données réelles en base, horloge figée) et
 * enregistre des PNG dans docs/screenshots. Gabarit proche d'un écran de 6,83" (≈ 412 × 892 dp).
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
        (context() as MonTempsApp).container = c
        c.notifier.ensureChannel()
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

    @Test
    fun overlapIsRefusedInEditor() {
        seed()
        launch()
        tap("Historique")
        tap("Ajouter une session") // par défaut : 14:40 → 15:40, chevauche des sessions existantes
        tap("Choisir une activité")
        tap("Sport")
        tap("Enregistrer")
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Chevauche", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        shot("09-chevauchement-refuse")
    }

    @Test
    @Config(qualifiers = "w412dp-h892dp-night-xhdpi")
    fun todayDark() {
        seed()
        launch()
        shot("02-aujourdhui-sombre")
    }

    @Test
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
        val start = Instant.ofEpochMilli(System.currentTimeMillis()).minusSeconds(22 * 60 + 10)
        val content = fr.montemps.notification.TrackingNotificationContent(
            sessionId = 1, activityName = "Travail", activityIcon = "💼", activityColor = 0xFF1E88E5.toInt(),
            start = start, startLabel = "15:20",
            favorites = listOf(fr.montemps.notification.FavoriteAction(DefaultActivities.BREAK, "☕ Pause")),
            hideNameOnLockScreen = false,
        )
        val n = c.notifier.build(content)
        val builder = Notification.Builder.recoverBuilder(context(), n)
        renderRemoteViews(builder.createBigContentView() ?: builder.createContentView(), "14-notification")
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
