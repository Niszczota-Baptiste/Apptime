package fr.montemps.domain

import fr.montemps.domain.model.Goal
import fr.montemps.domain.model.SubActivity
import fr.montemps.domain.model.TrackedActivity
import java.util.UUID

sealed interface CatalogResult {
    data class Ok(val activity: TrackedActivity) : CatalogResult
    data class Error(val message: String) : CatalogResult
}

sealed interface SubResult {
    data class Ok(val sub: SubActivity) : SubResult
    data class Error(val message: String) : SubResult
}

/**
 * Gestion des activités : création, renommage, couleur/icône, ordre, archivage.
 *
 * Renommer conserve l'identifiant : l'historique affiche donc le nouveau nom (choix documenté).
 * Archiver masque l'activité des raccourcis sans toucher à l'historique ; on ne peut pas archiver
 * l'activité en cours.
 */
class ActivityCatalog(
    private val store: TrackingStore,
    private val lock: WriteLock,
    private val listener: ChangeListener = ChangeListener { },
) {
    suspend fun create(
        name: String,
        color: Int,
        icon: String?,
        interruption: Boolean = false,
        goal: Goal? = null,
    ): CatalogResult = write {
        val clean = name.trim()
        validateName(clean, excludeId = null)?.let { return@write CatalogResult.Error(it) }
        val all = store.activities()
        val activity = TrackedActivity(
            id = UUID.randomUUID().toString(),
            name = clean,
            color = color,
            icon = icon?.takeIf { it.isNotBlank() },
            sortOrder = (all.maxOfOrNull { it.sortOrder } ?: -1) + 1,
            archived = false,
            interruption = interruption,
            goal = goal,
        )
        store.upsertActivity(activity)
        CatalogResult.Ok(activity)
    }

    suspend fun edit(
        id: String,
        name: String,
        color: Int,
        icon: String?,
        interruption: Boolean = false,
        goal: Goal? = null,
    ): CatalogResult = write {
        val existing = store.activity(id) ?: return@write CatalogResult.Error("Activité introuvable.")
        val clean = name.trim()
        validateName(clean, excludeId = id)?.let { return@write CatalogResult.Error(it) }
        val updated = existing.copy(
            name = clean,
            color = color,
            icon = icon?.takeIf { it.isNotBlank() },
            interruption = interruption,
            goal = goal,
        )
        store.upsertActivity(updated)
        CatalogResult.Ok(updated)
    }

    suspend fun setArchived(id: String, archived: Boolean): CatalogResult = write {
        val existing = store.activity(id) ?: return@write CatalogResult.Error("Activité introuvable.")
        if (archived && store.activeSession()?.activityId == id) {
            return@write CatalogResult.Error("Arrêtez d'abord le suivi de cette activité.")
        }
        val updated = existing.copy(archived = archived)
        store.upsertActivity(updated)
        CatalogResult.Ok(updated)
    }

    /** Déplace une activité non archivée d'un rang vers le haut (-1) ou le bas (+1). */
    suspend fun move(id: String, delta: Int): CatalogResult = write {
        val visible = store.activities().filter { !it.archived }.sortedBy { it.sortOrder }.toMutableList()
        val index = visible.indexOfFirst { it.id == id }
        if (index < 0) return@write CatalogResult.Error("Activité introuvable.")
        val target = (index + delta).coerceIn(0, visible.lastIndex)
        val item = visible.removeAt(index)
        visible.add(target, item)
        // Renumérotation compacte : les archivées gardent leur ordre, après les visibles.
        val archived = store.activities().filter { it.archived }.sortedBy { it.sortOrder }
        (visible + archived).forEachIndexed { i, a -> if (a.sortOrder != i) store.upsertActivity(a.copy(sortOrder = i)) }
        CatalogResult.Ok(item)
    }

    /**
     * Ajoute une sous-catégorie à [activityId]. Un nom déjà retiré est simplement rétabli (même
     * identifiant : l'historique qui y fait référence le retrouve).
     */
    suspend fun addSub(activityId: String, name: String): SubResult = writeSub {
        val activity = store.activity(activityId) ?: return@writeSub SubResult.Error("Activité introuvable.")
        val clean = name.trim()
        validateSubName(clean, activity.id, excludeId = null)?.let { return@writeSub SubResult.Error(it) }
        val siblings = store.subActivities().filter { it.activityId == activity.id }
        val retired = siblings.firstOrNull { it.archived && it.name.equals(clean, ignoreCase = true) }
        val sub = retired?.copy(archived = false, name = clean, sortOrder = nextSubOrder(siblings))
            ?: SubActivity(UUID.randomUUID().toString(), activity.id, clean, nextSubOrder(siblings))
        store.upsertSubActivity(sub)
        SubResult.Ok(sub)
    }

    suspend fun renameSub(id: String, name: String): SubResult = writeSub {
        val existing = store.subActivity(id) ?: return@writeSub SubResult.Error("Sous-catégorie introuvable.")
        val clean = name.trim()
        validateSubName(clean, existing.activityId, excludeId = id)?.let { return@writeSub SubResult.Error(it) }
        val updated = existing.copy(name = clean)
        store.upsertSubActivity(updated)
        SubResult.Ok(updated)
    }

    /** Retirer = archiver : la sous-catégorie n'est plus proposée, l'historique la garde. */
    suspend fun setSubArchived(id: String, archived: Boolean): SubResult = writeSub {
        val existing = store.subActivity(id) ?: return@writeSub SubResult.Error("Sous-catégorie introuvable.")
        val updated = existing.copy(archived = archived)
        store.upsertSubActivity(updated)
        SubResult.Ok(updated)
    }

    private fun nextSubOrder(siblings: List<SubActivity>) = (siblings.maxOfOrNull { it.sortOrder } ?: -1) + 1

    private suspend fun validateSubName(name: String, activityId: String, excludeId: String?): String? {
        if (name.isEmpty()) return "Le nom ne peut pas être vide."
        if (name.length > 30) return "Le nom est trop long (30 caractères maximum)."
        val duplicate = store.subActivities().any {
            it.activityId == activityId && it.id != excludeId && !it.archived && it.name.equals(name, ignoreCase = true)
        }
        return if (duplicate) "Cette sous-catégorie existe déjà." else null
    }

    private suspend fun writeSub(block: suspend () -> SubResult): SubResult {
        val result = lock.withLock { store.transaction { block() } }
        if (result is SubResult.Ok) listener.onDataChanged()
        return result
    }

    private suspend fun validateName(name: String, excludeId: String?): String? {
        if (name.isEmpty()) return "Le nom ne peut pas être vide."
        if (name.length > 40) return "Le nom est trop long (40 caractères maximum)."
        val duplicate = store.activities().any { it.id != excludeId && it.name.equals(name, ignoreCase = true) }
        return if (duplicate) "Une activité porte déjà ce nom." else null
    }

    private suspend fun write(block: suspend () -> CatalogResult): CatalogResult {
        val result = lock.withLock { store.transaction { block() } }
        if (result is CatalogResult.Ok) listener.onDataChanged()
        return result
    }
}
