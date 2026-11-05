package fr.montemps.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import fr.montemps.domain.workouts.Workout
import fr.montemps.domain.workouts.WorkoutAvailability
import fr.montemps.domain.workouts.WorkoutSource
import java.time.Instant

/**
 * Séances d'exercice lues dans Health Connect (où Garmin Connect copie celles de la montre).
 * Lecture seule, uniquement à la demande de l'utilisateur (écran « Séances sportives ») :
 * aucune lecture en arrière-plan, rien n'est écrit dans Health Connect.
 */
class HealthConnectWorkouts(private val context: Context) : WorkoutSource {

    override fun availability(): WorkoutAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> WorkoutAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> WorkoutAvailability.NEEDS_UPDATE
        else -> WorkoutAvailability.UNAVAILABLE
    }

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    override suspend fun hasPermission(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)

    override suspend fun workouts(from: Instant, to: Instant): List<Workout> {
        val result = mutableListOf<Workout>()
        var token: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(from, to), pageToken = token),
            )
            response.records.mapTo(result) { r ->
                Workout(
                    id = r.metadata.id,
                    start = r.startTime,
                    end = r.endTime,
                    type = typeLabel(r.exerciseType),
                    title = r.title,
                    source = sourceLabel(r.metadata.dataOrigin.packageName),
                )
            }
            token = response.pageToken
        } while (token != null)
        return result
    }

    companion object {
        /** Seule autorisation demandée : lire les séances d'exercice. */
        val PERMISSIONS: Set<String> = setOf(HealthPermission.getReadPermission(ExerciseSessionRecord::class))

        const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"

        fun sourceLabel(packageName: String): String = when (packageName) {
            "com.garmin.android.apps.connectmobile" -> "Garmin Connect"
            "com.mi.health", "com.xiaomi.wearable" -> "Mi Fitness"
            "com.google.android.apps.fitness" -> "Google Fit"
            "com.samsung.android.app.health" -> "Samsung Health"
            "com.strava" -> "Strava"
            else -> packageName
        }

        fun typeLabel(type: Int): String = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Course à pied"
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Course sur tapis"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Marche"
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Randonnée"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Vélo"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Vélo d'intérieur"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "Natation en piscine"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Natation en eau libre"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Renforcement musculaire"
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Musculation"
            ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "Poids du corps"
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "Fractionné (HIIT)"
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
            ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "Pilates"
            ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> "Étirements"
            ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "Elliptique"
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING -> "Aviron"
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "Rameur"
            ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING, ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> "Escaliers"
            ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> "Ski"
            ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING -> "Snowboard"
            ExerciseSessionRecord.EXERCISE_TYPE_PADDLING -> "Pagaie"
            ExerciseSessionRecord.EXERCISE_TYPE_SURFING -> "Surf"
            ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "Football"
            ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> "Basket"
            ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL -> "Handball"
            ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL -> "Volley"
            ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "Tennis"
            ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS -> "Tennis de table"
            ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON -> "Badminton"
            ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> "Boxe"
            ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS -> "Arts martiaux"
            ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "Danse"
            ExerciseSessionRecord.EXERCISE_TYPE_GOLF -> "Golf"
            ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP, ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS -> "Cours collectif"
            else -> "Séance"
        }
    }
}
