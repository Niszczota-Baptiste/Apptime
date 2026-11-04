package fr.montemps.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.montemps.data.backup.BackupCodec
import fr.montemps.data.backup.CsvExporter
import fr.montemps.data.backup.DecodeResult
import fr.montemps.domain.model.DefaultActivities.BREAK
import fr.montemps.domain.model.DefaultActivities.WORK
import fr.montemps.testutil.FakeTimeSource
import fr.montemps.testutil.plusHours
import fr.montemps.testutil.testContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        source.catalog.create("Lecture", 0xFF3949AB.toInt(), "📚")
        source.catalog.setArchived(BREAK, true)
        source.preferences.setFavorites(listOf(WORK))
        source.preferences.setHideNameOnLockScreen(true)
        time.now = time.now.plusSeconds(60)
        source.tracker.switchTo(WORK)
        val json = source.backup.exportJson()
        assertTrue(json.contains("\"formatVersion\": 1"))

        val target = testContainer(time)
        target.editor.addPastSession(BREAK, morning.minus(Duration.ofDays(1)), morning.minus(Duration.ofDays(1)).plusHours(1), null)
        val decoded = target.backup.decode(json) as DecodeResult.Valid
        target.backup.restore(decoded.snapshot)

        assertEquals(source.store.allSessions(), target.store.allSessions())
        assertEquals(source.store.activities(), target.store.activities())
        assertEquals(WORK, target.tracker.currentSession()?.activityId)
        val prefs = target.preferences.current()
        assertEquals(listOf(WORK), prefs.favoriteIds)
        assertTrue(prefs.hideNameOnLockScreen)
    }

    @Test
    fun invalidBackupsAreRejectedWithoutTouchingData() = runBlocking {
        val c = testContainer(time)
        c.editor.addPastSession(WORK, morning, morning.plusHours(1), null)
        val before = c.store.allSessions()
        val valid = c.backup.exportJson()

        val cases = mapOf(
            "pas du json" to "{ ceci n'est pas",
            "autre format" to valid.replace("mon-temps-backup", "autre-app"),
            "version future" to valid.replace("\"formatVersion\": 1", "\"formatVersion\": 99"),
            "activité inconnue" to valid.replace("\"activityId\": \"work\"", "\"activityId\": \"fantome\""),
            "date invalide" to valid.replace("2026-11-04T07:00:00Z", "hier matin"),
            "session future" to valid.replace("2026-11-04T08:00:00Z", "2030-01-01T00:00:00Z"),
        )
        for ((label, text) in cases) {
            assertTrue("$label devrait être refusé", c.backup.decode(text) is DecodeResult.Invalid)
        }
        assertEquals(before, c.store.allSessions())
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
        val csv = c.backup.exportCsv()
        val lines = csv.removePrefix("\uFEFF").trimEnd().split("\r\n")
        assertEquals(CsvExporter.header.joinToString(";"), lines[0])
        assertEquals(
            "1;work;Travail;2026-11-04 08:00:00;2026-11-04 10:00:00;Europe/Paris;2026-11-04T07:00:00Z;2026-11-04T09:00:00Z;7200;02:00:00;non;\"a;b \"\"c\"\"\"",
            lines[1],
        )
    }
}
