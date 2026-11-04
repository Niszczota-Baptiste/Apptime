# Procédure de test sur le POCO X8 Pro Max (HyperOS)

Durée : ≈ 45 min, plus une nuit pour le test long. Noter pour chaque étape : OK / KO + remarque.
Avant de commencer : installer l'APK, ouvrir l'app, aller dans Réglages et faire une sauvegarde JSON
si des données existent déjà. Vérifier aussi, dans Paramètres → Applications → Mon Temps, que le
**démarrage automatique** est autorisé (HyperOS peut sinon bloquer le retour de la notification après
un redémarrage, le rafraîchissement de minuit et le rappel).

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

## 12. Widgets d'écran d'accueil
1. Réglages → Écran d'accueil → « Widget Suivi en cours » : noter si HyperOS propose l'ajout direct
   (sinon : appui long sur l'écran d'accueil → Widgets → Mon Temps). Idem pour « Widget Activités ».
2. Démarrer Travail depuis l'app : les deux widgets se mettent à jour (nom, chronomètre qui avance,
   Travail en négatif dans « Activités »).
3. Toucher « Pause » dans le widget « Activités » : un seul appui suffit ; app, notification et
   widgets passent sur Pause.
4. Toucher « Arrêter » dans un widget : le suivi s'arrête partout.
5. Redimensionner « Suivi en cours » : en petit, nom et chronomètre seulement ; en grand, la ligne
   « Depuis… · aujourd'hui… » et les favorites apparaissent. Agrandir « Activités » : plus d'activités.
6. Thème sombre du système : les widgets suivent.
7. Redémarrer pendant un suivi : après déverrouillage, le chronomètre du widget est juste.

## 13. Tuile du centre de contrôle
1. Réglages → Écran d'accueil → « Tuile du centre de contrôle » (sinon : centre de contrôle → Modifier
   → Mon Temps). Noter si la tuile est disponible et comment HyperOS affiche son libellé.
2. Suivi en cours : la tuile est active et affiche l'activité ; un appui arrête le suivi.
3. Sans suivi : la tuile propose « Reprendre … » ; un appui reprend la dernière activité.

## 14. Rappel de session longue
1. Réglages → Suivi → « Session longue » : choisir 4 h. Démarrer une activité et la laisser tourner.
2. Après environ 4 h (l'alarme peut être un peu retardée) : notification « « … » toujours en cours ? »
   avec « Continuer » et « Corriger ». Un seul rappel pour cette session.
3. « Continuer » : la notification disparaît ; à la réouverture, aucune question pour cette session.
4. Remettre le seuil souhaité (10 h par défaut). Le rappel peut être désactivé dans Réglages → Suivi.

## 15. Annuler et démarrer plus tôt
1. Toucher Pause par erreur pendant Travail → toucher « Annuler » dans le message en bas : Travail
   reprend, comme si de rien n'était (historique, notification, widgets).
2. Appui long sur Sport → « Il y a 10 min » : Sport commence 10 min plus tôt, l'activité précédente
   s'arrête à ce moment. Vérifier que les durées proposées ne remontent jamais avant le début de la
   session en cours.

## 16. Reprendre après une pause
1. Travail, puis Pause : l'écran principal, la notification, le widget et la tuile proposent
   « Reprendre Travail ». Un appui reprend Travail.
2. Dans Réglages → Gérer les activités, marquer une autre activité comme « Interruption » et vérifier
   le même comportement.

## 17. Historique : frise et trous
1. Historique : la frise montre la journée ; les périodes « Non suivi » (≥ 5 min) apparaissent dans
   la liste. En toucher une : la saisie s'ouvre avec les bonnes heures ; choisir une activité, enregistrer.

## 18. Objectifs, tendances, séries
1. Donner un objectif à Coréen (30 min par jour) et à Sport (3 h par semaine).
2. Vérifier la progression dans « Aujourd'hui » et dans Bilans (jour et semaine), la comparaison
   avec la semaine précédente, la moyenne et les séries.

## 19. Rappels et résumé
1. Activer « Rappel “rien n'est suivi” » avec un délai d'1 h, arrêter le suivi, ne pas rouvrir
   l'app : une notification arrive environ 1 h plus tard (dans la plage horaire), une seule fois.
2. Activer le résumé du soir à une heure proche : notification silencieuse ; un appui ouvre les bilans.

## 20. Haut de l'écran (Android 16) et sauvegarde automatique
1. Pendant un suivi, vérifier si HyperOS affiche le chronomètre en haut de l'écran (puce / île).
   Sinon : Réglages → « Live Updates désactivés dans Android » le cas échéant. Noter le rendu exact.
2. Réglages → Sauvegarde automatique → choisir un dossier : un fichier `mon-temps-auto-…json` y
   apparaît aussitôt. Vérifier qu'il se restaure (Réglages → Restaurer une sauvegarde).

## 21. Affichage
- Thème sombre du système → l'app suit.
- Taille de police maximale (Paramètres → Affichage) → textes lisibles, raccourcis sur une colonne.
