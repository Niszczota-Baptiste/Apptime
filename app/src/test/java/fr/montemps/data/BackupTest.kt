package fr.montemps.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.montemps.data.backup.BackupCodec
import fr.montemps.data.backup.CsvExporter
import fr.montemps.data.backup.DecodeResult
import fr.montemps.domain.SubResult
import fr.montemps.domain.model.DefaultActivities.BREAK
import fr.montemps.domain.model.DefaultActivities.WORK
import fr.montemps.testutil.FakeTimeSource
import fr.montemps.testutil.plusHours
import fr.montemps.testutil.testContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class BackupTest {
    private val time = FakeTimeSource(now = Instant.parse("2026-11-04T18:00:00Z"))
    private val morning = Instant.parse("2026-11-04T07:00:00Z")

    @Test
    fun exportThenRestoreRoundTrip() = runBlocking {
        val source = testContainer(time)
        source.editor.addPastSession(WORK, morning, morning.plusHours(2), "note; avec \"guillemets\"")
        source.catalog.create("Lecture", 0xFF3949AB.toInt(), "📚", goal = fr.montemps.domain.model.Goal(20, fr.montemps.domain.model.GoalPeriod.DAY))
        source.preferences.setEveningSummary(true)
        source.preferences.setIdleWindow(9 * 60, 21 * 60)
        source.catalog.setArchived(BREAK, true)
        source.preferences.setFavorites(listOf(WORK))
        source.preferences.setHideNameOnLockScreen(true)
        source.preferences.setBedtime(true)
        source.preferences.setBedtimeMinutes(22 * 60 + 30)
        source.preferences.setWakeDays(setOf(1, 2, 3, 4, 5))
        val study = (source.catalog.addSub(WORK, "Étude") as SubResult.Ok).sub
        source.catalog.setSubArchived("work_perso", true)
        source.editor.addPastSession(WORK, morning.plusHours(3), morning.plusHours(4), null, subId = "work_pro")
        time.now = time.now.plusSeconds(60)
        source.tracker.switchTo(WORK, subId = study.id)
        val json = source.backup.exportJson()
        assertTrue(json.contains("\"formatVersion\": ${BackupCodec.CURRENT_VERSION}"))

        val target = testContainer(time)
        target.editor.addPastSession(BREAK, morning.minus(Duration.ofDays(1)), morning.minus(Duration.ofDays(1)).plusHours(1), null)
        val decoded = target.backup.decode(json) as DecodeResult.Valid
        target.backup.restore(decoded.snapshot)

        assertEquals(source.store.allSessions(), target.store.allSessions())
        assertEquals(source.store.activities(), target.store.activities())
        assertEquals(source.store.subActivities(), target.store.subActivities())
        assertEquals(WORK, target.tracker.currentSession()?.activityId)
        assertEquals(study.id, target.tracker.currentSession()?.subId)
        assertTrue(target.store.subActivity("work_perso")!!.archived)
        val prefs = target.preferences.current()
        assertEquals(listOf(WORK), prefs.favoriteIds)
        assertTrue(prefs.hideNameOnLockScreen)
        assertTrue(prefs.eveningSummaryEnabled)
        assertEquals(9 * 60, prefs.idleWindowStartMinutes)
        assertTrue(prefs.bedtimeEnabled)
        assertEquals(22 * 60 + 30, prefs.bedtimeMinutes)
        assertEquals(setOf(1, 2, 3, 4, 5), prefs.wakeDays)
        val lecture = target.store.activities().first { it.name == "Lecture" }
        assertEquals(20, lecture.goal!!.minutes)
        assertTrue(target.store.activity(BREAK)!!.interruption)
    }

    @Test
    fun invalidBackupsAreRejectedWithoutTouchingData() = runBlocking {
        val c = testContainer(time)
        c.editor.addPastSession(WORK, morning, morning.plusHours(1), null, subId = "work_pro")
        val before = c.store.allSessions()
        val valid = c.backup.exportJson()

        val cases = mapOf(
            "pas du json" to "{ ceci n'est pas",
            "autre format" to valid.replace("mon-temps-backup", "autre-app"),
            "version future" to valid.replace("\"formatVersion\": ${BackupCodec.CURRENT_VERSION}", "\"formatVersion\": 99"),
            "sous-catégorie d'une autre activité" to valid.replace("\"subId\": \"work_pro\"", "\"subId\": \"sport_gym\""),
            "sous-catégorie inconnue" to valid.replace("\"subId\": \"work_pro\"", "\"subId\": \"fantome\""),
            "activité inconnue" to valid.replace("\"activityId\": \"work\"", "\"activityId\": \"fantome\""),
            "date invalide" to valid.replace("2026-11-04T07:00:00Z", "hier matin"),
            "session future" to valid.replace("2026-11-04T08:00:00Z", "2030-01-01T00:00:00Z"),
        )
        for ((label, text) in cases) {
            assertTrue("$label : le cas doit modifier le fichier", text != valid)
            assertTrue("$label devrait être refusé", c.backup.decode(text) is DecodeResult.Invalid)
        }
        assertEquals(before, c.store.allSessions())
    }

    @Test
    fun version1BackupIsStillReadable() {
        val v1 = """
            {"format":"mon-temps-backup","formatVersion":1,"exportedAt":"2026-11-04T18:00:00Z",
             "activities":[{"id":"work","name":"Travail","color":"#1E88E5","icon":"💼","sortOrder":0}],
             "sessions":[{"id":1,"activityId":"work","start":"2026-11-04T07:00:00Z","end":"2026-11-04T09:00:00Z","zoneId":"Europe/Paris"}],
             "preferences":{"favoriteIds":["work"],"notificationEnabled":true,"hideNameOnLockScreen":false,"forgottenThresholdHours":8}}
        """.trimIndent()
        val r = BackupCodec.decode(v1, time.now) as DecodeResult.Valid
        assertEquals(1, r.snapshot.sessions.size)
        assertEquals(8, r.snapshot.preferences.forgottenThresholdHours)
        // Champs apparus en v2/v3 : valeurs par défaut (Pause et Repas sont des interruptions).
        assertTrue(r.snapshot.preferences.longSessionReminder)
        assertTrue(!r.snapshot.activities.first { it.id == "work" }.interruption)
        // Champs apparus en v4 : Sommeil ajouté, sous-catégories par défaut des activités présentes.
        assertEquals(listOf("work", "sleep"), r.snapshot.activities.map { it.id })
        assertEquals(listOf("work_perso", "work_pro"), r.snapshot.subActivities.map { it.id })
        assertNull(r.snapshot.sessions.single().subId)
        assertTrue(!r.snapshot.preferences.bedtimeEnabled && !r.snapshot.preferences.wakeEnabled)
        val v1WithBreak = v1.replace("{\"id\":\"work\"", "{\"id\":\"break\",\"name\":\"Pause\",\"color\":\"#E3A33B\",\"sortOrder\":1},{\"id\":\"work\"")
        val r2 = BackupCodec.decode(v1WithBreak, time.now) as DecodeResult.Valid
        assertTrue(r2.snapshot.activities.first { it.id == "break" }.interruption)
    }

    @Test
    fun overlappingSessionsInBackupAreRejected() {
        val text = """
            {"format":"mon-temps-backup","formatVersion":1,"exportedAt":"2026-11-04T18:00:00Z",
             "activities":[{"id":"work","name":"Travail","color":"#1E88E5","sortOrder":0}],
             "sessions":[
               {"id":1,"activityId":"work","start":"2026-11-04T07:00:00Z","end":"2026-11-04T09:00:00Z","zoneId":"Europe/Paris"},
               {"id":2,"activityId":"work","start":"2026-11-04T08:00:00Z","end":"2026-11-04T10:00:00Z","zoneId":"Europe/Paris"}
             ]}
        """.trimIndent()
        val r = BackupCodec.decode(text, time.now)
        assertTrue(r is DecodeResult.Invalid && r.message.contains("chevauchent"))
    }

    @Test
    fun csvIsSpreadsheetFriendly() = runBlocking {
        val c = testContainer(time)
        c.editor.addPastSession(WORK, morning, morning.plusHours(2), "a;b \"c\"")
        c.editor.addPastSession(WORK, morning.plusHours(2), morning.plusHours(3), null, subId = "work_pro")
        val csv = c.backup.exportCsv()
        val lines = csv.removePrefix("\uFEFF").trimEnd().split("\r\n")
        assertEquals(CsvExporter.header.joinToString(";"), lines[0])
        assertEquals(
            "1;work;Travail;;2026-11-04 08:00:00;2026-11-04 10:00:00;Europe/Paris;2026-11-04T07:00:00Z;2026-11-04T09:00:00Z;7200;02:00:00;non;\"a;b \"\"c\"\"\"",
            lines[1],
        )
        assertEquals(
            "2;work;Travail;Pro;2026-11-04 10:00:00;2026-11-04 11:00:00;Europe/Paris;2026-11-04T09:00:00Z;2026-11-04T10:00:00Z;3600;01:00:00;non;",
            lines[2],
        )
    }
}
