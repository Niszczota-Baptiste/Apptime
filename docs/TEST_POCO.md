# Procédure de test sur le POCO X8 Pro Max (HyperOS)

Durée : ≈ 45 min, plus une nuit pour le test long. Noter pour chaque étape : OK / KO + remarque.
Avant de commencer : installer l'APK, ouvrir l'app, aller dans Réglages et faire une sauvegarde JSON
si des données existent déjà.

## 1. Parcours de base
1. Ouvrir l'app → « Aucune activité en cours » s'affiche.
2. Toucher **Travail** → accepter la demande d'autorisation de notification.
3. Le chronomètre avance ; la tuile Travail affiche « En cours » et une coche.
4. Toucher **Pause** : un seul appui suffit, Travail s'arrête et Pause démarre (vérifier dans Historique : fin de Travail = début de Pause).
5. Toucher Pause plusieurs fois rapidement → aucune nouvelle ligne dans l'historique.

## 2. Écran éteint pendant le suivi
1. Démarrer Travail, noter l'heure, éteindre l'écran 20 min.
2. Rallumer : sur l'écran verrouillé, la notification « 💼 Travail — En cours depuis HH:MM » doit
   être visible **si** HyperOS autorise les notifications de Mon Temps sur l'écran verrouillé
   (sinon : Paramètres → Notifications → écran de verrouillage / Mon Temps ; les intitulés varient).
3. Le chronomètre de la notification et celui de l'app indiquent ≈ 20 min.
4. Activer « Masquer le nom sur l'écran verrouillé » (Réglages), verrouiller : seul « Suivi en
   cours » doit apparaître (selon le réglage « contenu sensible » du téléphone).

## 3. Actions rapides de la notification
1. Pendant Travail, déployer la notification : actions « ☕ Pause » et « Arrêter » (Travail, déjà
   actif, n'est pas proposé). Noter combien d'actions HyperOS affiche réellement.
2. Toucher « Pause » → titre de la notification = Pause ; l'app (si ouverte) se met à jour.
3. Toucher « Arrêter » → la notification disparaît ; l'historique montre la session terminée.
4. Tester les actions depuis l'écran verrouillé (HyperOS peut demander de déverrouiller).
5. Toucher la notification → l'app s'ouvre.
6. Balayer la notification pendant une session : elle ne revient pas toute seule ; l'écran
   Aujourd'hui propose « Réafficher ».

## 4. Autorisation refusée puis accordée
1. Paramètres Android → Applications → Mon Temps → Notifications : désactiver.
2. Revenir dans l'app : carte « Notifications désactivées » ; démarrer une activité → le suivi
   fonctionne, aucune notification.
3. Toucher « Autoriser » → réglages système → réactiver → revenir : la carte disparaît ; changer
   d'activité → la notification apparaît.

## 5. Redémarrage pendant une session
1. Démarrer Sport, redémarrer le téléphone.
2. Après déverrouillage (attendre jusqu'à 1–2 min) : la notification Sport doit revenir avec la
   durée correcte (sinon noter le délai ou l'absence — certaines surcouches retardent `BOOT_COMPLETED`).
3. Ouvrir l'app : boîte « Session toujours en cours ? » (redémarrage détecté). Tester « Continuer ».

## 6. Mode économie d'énergie
1. Activer l'économiseur de batterie (et, si disponible, le mode ultra).
2. Démarrer une activité, éteindre l'écran 15 min, rallumer : durée correcte, notification cohérente.
3. Utiliser les actions de notification sous économie d'énergie.
4. Noter le réglage « Économiseur de batterie » d'HyperOS pour Mon Temps (Pas de restriction /
   Économiseur) ; l'app ne demande aucune exemption et ne devrait pas en avoir besoin.

## 7. Application retirée des récentes
1. Démarrer Écriture, ouvrir les récentes, balayer Mon Temps.
2. La notification doit rester et le chronomètre continuer (noter si HyperOS la retire).
3. Utiliser une action de la notification après le balayage : elle doit fonctionner.
4. Rouvrir l'app : la session continue, durée correcte.

## 8. Arrêt forcé puis réouverture
1. Démarrer Musique. Paramètres → Applications → Mon Temps → **Forcer l'arrêt**.
2. Attendu : la notification disparaît (comportement Android). La session n'est pas perdue.
3. Rouvrir l'app : Musique toujours en cours depuis l'heure initiale ; la notification revient.

## 9. Corrections et bilans
1. Historique → « Ajouter une session » chevauchant une session existante → message de refus.
2. Ajouter une session passée valide → les bilans du jour sont mis à jour immédiatement.
3. Modifier l'heure de fin d'une session → total du jour mis à jour.
4. Ouvrir une session en cours → seuls activité et note sont modifiables (message explicatif).
5. Le soir, démarrer Temps libre avant minuit et l'arrêter après : le bilan de chaque jour reçoit sa part.

## 10. Export / restauration
1. Réglages → Exporter CSV → ouvrir le fichier sur ordinateur (Excel/LibreOffice) : colonnes séparées, accents corrects.
2. Créer une sauvegarde JSON. Modifier quelques données. Restaurer la sauvegarde → confirmation →
   données revenues à l'état sauvegardé.
3. Tenter de restaurer un fichier quelconque (ex. une photo/texte) → message d'erreur, données intactes.

## 11. Changement d'heure / fuseau (facultatif)
1. Paramètres → Date et heure → désactiver l'automatique, changer de fuseau (ex. Séoul) pendant une session.
2. La durée ne saute pas ; les heures affichées sont dans le nouveau fuseau ; la notification
   indique la nouvelle heure de début.
3. Reculer manuellement l'heure de 10 min : un avertissement « L'heure du téléphone a changé » apparaît.
4. Remettre l'heure automatique.

## 12. Affichage
- Thème sombre du système → l'app suit.
- Taille de police maximale (Paramètres → Affichage) → textes lisibles, raccourcis sur une colonne.
