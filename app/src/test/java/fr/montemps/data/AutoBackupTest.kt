package fr.montemps.data

import android.app.AlarmManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.montemps.data.backup.AutoBackupController
import fr.montemps.data.backup.BackupCodec
import fr.montemps.data.backup.DecodeResult
import fr.montemps.domain.model.DefaultActivities.WORK
import fr.montemps.notification.TrackingActionReceiver
import fr.montemps.testutil.FakeTimeSource
import fr.montemps.testutil.MemoryBackupFolder
import fr.montemps.testutil.context
import fr.montemps.testutil.testContainer
import fr.montemps.testutil.useContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class AutoBackupTest {
    private val time = FakeTimeSource()
    private val folder = MemoryBackupFolder()
    private val c = testContainer(time, backupFolderFor = { folder })
    private val alarms by lazy { shadowOf(context().getSystemService(AlarmManager::class.java)) }
    private fun alarm() = alarms.scheduledAlarms.firstOrNull { shadowOf(it.operation).savedIntent.action == TrackingActionReceiver.ACTION_AUTO_BACKUP }

    @Test
    fun weeklyBackupWritesARestorableFileAndKeepsTheLastEight() = runBlocking {
        useContainer(c)
        c.tracker.switchTo(WORK)
        c.preferences.setAutoBackupFolder("content://dossier")
        c.refreshOutputs() // jamais sauvegardé : sauvegarde immédiate
        assertEquals(listOf("mon-temps-auto-2026-11-04.json"), folder.names())
        val restored = BackupCodec.decode(folder.files.values.single(), time.now)
        assertTrue(restored is DecodeResult.Valid)
        // Prochaine dans 7 jours (alarme inexacte, sans réveil).
        assertEquals(time.now.plus(Duration.ofDays(7)).toEpochMilli(), alarm()!!.triggerAtTime)
        assertEquals(AlarmManager.RTC, alarm()!!.type)

        // Pas avant l'échéance.
        time.advance(Duration.ofDays(3))
        c.refreshOutputs()
        assertEquals(1, folder.names().size)

        // Dix semaines plus tard : seules les 8 copies les plus récentes restent.
        repeat(10) {
            time.advance(Duration.ofDays(7))
            c.refreshOutputs()
        }
        assertEquals(AutoBackupController.KEEP, folder.names().size)
        assertTrue(folder.names().none { it == "mon-temps-auto-2026-11-04.json" })
        assertNull(c.preferences.current().autoBackupLastError)
    }

    @Test
    fun failureIsReportedAndRetriedTheNextDay() = runBlocking {
        useContainer(c)
        folder.failing = true
        c.preferences.setAutoBackupFolder("content://dossier")
        c.refreshOutputs()
        assertEquals("Échec : dossier inaccessible", c.preferences.current().autoBackupLastError)
        assertEquals(time.now.plus(Duration.ofDays(1)).toEpochMilli(), alarm()!!.triggerAtTime)

        folder.failing = false
        time.advance(Duration.ofDays(1))
        c.refreshOutputs()
        assertEquals(1, folder.names().size)
        assertNull(c.preferences.current().autoBackupLastError)
    }

    @Test
    fun disabledByDefault() = runBlocking {
        useContainer(c)
        c.refreshOutputs()
        assertTrue(folder.names().isEmpty())
        assertNull(alarm())
    }
}
