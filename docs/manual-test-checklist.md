# Checklist de test manuel

Aide-mémoire pour tester le mod avant de le sortir aux joueurs. Coche au fur et à mesure
(`- [ ]` → `- [x]`). Pas besoin de tout faire d'une traite : chaque section peut être une
session de jeu séparée. Les sections 1 à 5 sont **prioritaires** (systèmes récents, jamais
encore testés en jeu réel). Les sections 6+ sont une passe de régression plus légère.

Astuce avant de commencer : commit ou stash l'état actuel du code (beaucoup de changements
non commités en ce moment) pour pouvoir revenir en arrière facilement si un test révèle
un gros bug.

## 0. Préparation

- [ ] `./gradlew build` passe sans erreur
- [ ] Le client démarre sans exception dans les logs
- [ ] Le serveur dédié démarre sans exception dans les logs (mixins déjà confirmés OK)
- [ ] `/travellersconfigreload` ne casse rien après un changement de config

## 1. Montures (priorité haute — gros morceau récent)

- [ ] Un traveller/raider/worker spawn parfois monté (`MOUNTS.travellerMountChancePercent` etc.)
- [ ] Trajet monté : chevauchée directe fluide sur terrain simple, pas de zigzag
- [ ] Virage serré → pivot sur place, pas de dérapage bizarre
- [ ] Saut en trajet monté (franchissement d'un obstacle bas)
- [ ] **Sous un surplomb bas et solide (pierre, terre — pas des feuilles) : le cavalier détoure
      toujours, ne passe jamais dessous** (correctif du 26/09 : c'était auparavant possible dans de
      rares cas où tous les autres chemins coûtaient plus cher — désormais un refus systématique)
- [ ] **Passage à pied (LED) déclenché par terrain infranchissable** — le cavalier met pied à
      terre et mène le cheval par la bride
- [ ] **LED déclenché par de l'eau** devant le chemin
- [ ] **LED déclenché par cheval blessé** (sous ~35% de vie) — reste à pied tant que le
      cheval n'a pas assez récupéré
- [ ] **Dismount en mêlée** : le monté descend et combat à pied à l'approche d'un ennemi
- [ ] Remonte automatiquement une fois au sec, immobile, stable (`remountStableTicks`)
- [ ] Ne remonte jamais dans l'eau
- [ ] **Mort du cheval monté** : pas de téléportation-puis-snap-back visuel du cavalier
- [ ] Cheval perdu par mort du cavalier : reste sur place, orphelin, récupérable par un joueur
      ou par un autre worker recruté (`EntityAIClaimLooseMount`)
- [ ] **Vol de monture** : un joueur (pas le propriétaire) ne peut pas monter un cheval lié
      (`MOUNTS.preventPlayerTheft`) — idéalement testé à 2 joueurs
- [ ] Suivi monté (escorte de worker ou groupe traveller/raider) : les suiveurs montés gardent
      le rythme sans "tourner sur place" quand le leader ralentit/s'arrête
- [ ] Suivi monté sur terrain vallonné : pas de blocage répété (correctif du 26/09 : le pas direct
      des groupes est maintenant plafonné en distance comme celui du leader, et retente le chemin
      récent du leader avant d'abandonner)
- [ ] **Beaucoup de groupes montés en même temps sur terrain accidenté** → surveiller le TPS
      spécifiquement au moment où des suiveurs passent en LED/terrain difficile (c'est le
      scénario que le correctif du 26/09 cible ; comparer le ressenti avant/après si possible)

Debug utile : `/travellersdebug mount`, `/travellersdebug trace start <nom> move,path,steer,mount,ai,follow`

## 2. Virtualisation / offscreen (priorité haute)

- [ ] Un traveller/worker s'éloigne du joueur → disparaît proprement (virtualisé), pas de
      despawn brutal
- [ ] Il réapparaît (matérialise) correctement quand le joueur revient, à une position
      sensée (pas dans un mur, pas englouti dans un arbre/canopée)
- [ ] **Serveur dédié, 0 joueur connecté** : laisser tourner le serveur seul ~1 min, vérifier
      qu'aucune entité ne se retrouve figée en pile à la limite d'une zone chargée au retour
      d'un joueur
- [ ] Un worker envoyé en mission loin (go/equip/transport) continue de progresser hors-écran
- [ ] Une bataille virtuelle en cours matérialise les participants à la bonne position quand
      on s'approche

## 3. Follow AI — groupes & escortes (priorité haute)

- [ ] Groupe de travellers/raiders (même culture) : les suiveurs gardent leur formation
- [ ] Un suiveur très en retard rattrape le groupe sans téléportation (juste plus vite)
- [ ] Le leader meurt / se virtualise loin → les suiveurs ne restent pas bloqués indéfiniment
- [ ] Escorte de worker recruté : suit le joueur, reste en formation, ne se coince pas dans
      un couloir étroit ou sur un escalier
- [ ] Un suiveur coincé finit par être secouru (petit saut, pas de téléportation map-wide)

## 4. Dialogue NPC (Ollama)

- [ ] Clic droit sur un traveller neutre ouvre bien la fenêtre de dialogue
- [ ] La réponse arrive en streaming (mot par mot), pas d'un bloc
- [ ] Le NPC répond dans la langue utilisée par le joueur
- [ ] Le NPC ne mentionne jamais de coordonnées exactes, seulement des directions vagues
- [ ] Fermer la fenêtre en pleine génération ne casse rien (la réponse suivante reste fluide)
- [ ] **Couper Ollama** (ou pointer vers un port invalide) → message de repli propre, pas de
      crash/écran figé
- [ ] Activer le tool calling (option client) et poser une question qui nécessite une
      recherche (relation de village par ex.) → le NPC répond avec l'info réelle
- [ ] Activer la mémoire (`DIALOGUE.memoryEnabled`) : parler à un traveller, fermer, revenir
      plus tard → il se souvient d'un fait mentionné
- [ ] Activer le résumé de vie (`DIALOGUE.memorySummaryEnabled`) et vérifier qu'il évolue
      après plusieurs conversations
- [ ] Le métier annoncé par le NPC correspond à son apparence/culture réelle
- [ ] La raison de voyage donnée reste cohérente d'une conversation à l'autre (même NPC)
- [ ] Personnalité : le ton du texte correspond à la voix jouée (à surveiller particulièrement
      chez les raiders, un mismatch voix/culture est un bug connu non corrigé — voir section 11)

Debug utile : `/travellersdebug dialogue memory dump <nom>`

## 5. Stable (enclos à chevaux) — système non publié, changements OK

- [ ] Créer une Stable valide (coffre + clôture + lit) via le Summoning Wand
- [ ] Un worker assigné en service Stable ("palfrenier") apprivoise/selle un cheval sauvage
      dans l'enclos
- [ ] Reproduction déclenchée par carottes dorées dans le coffre
- [ ] Le palfrenier va dormir dans son lit assigné la nuit
- [ ] "Get Equipment" attribue bien un cheval du pool à tout le groupe, pas juste au worker
      qui a reçu l'ordre
- [ ] Supprimer une Stable rend les chevaux du pool en chevaux apprivoisés normaux (pas de
      disparition)
- [ ] Le pool ne descend jamais sous la réserve minimale même en cas de demandes répétées

Debug utile : `/travellersdebug stable list|dump <n>|log <n>|verify <n>`

## 6. Missions worker (go / équiper / transport / recruter)

- [ ] Un worker en mission porte visiblement le sac à dos, le retire une fois la mission finie
- [ ] Lancer une mission détache bien le worker de son ancien groupe
- [ ] Un worker qui revient avec du butin colle sur le côté du joueur sans tourner autour
- [ ] Vente/don reçoit bien un toast récapitulatif (total + top objets)
- [ ] Notification d'arrivée/d'échec d'équipement bien reçue

## 7. Worker Hub / gestion de groupe

- [ ] Créer un groupe, désigner un leader, faire "revenir" un membre / tout le groupe
- [ ] Fusionner deux groupes, faire suivre un leader par un autre
- [ ] Basculer un leader entre "suit le joueur" et "reste sur place"
- [ ] Donner un ordre à un worker occupé → refusé proprement avec un avertissement
- [ ] Le nombre de suiveurs affiché dans le Hub est correct (loaded + virtuels)

## 8. Régression générale (passe plus légère)

- [ ] Spawn/population : villages se peuplent progressivement de travellers/raiders/workers
- [ ] Combat : difficulté paisible bloque bien toute agression
- [ ] Un traveller/raider ne reste jamais bloqué "en combat" après avoir tué sa cible
      (pas de gel permanent)
- [ ] Portes/barrières : ouvertes au passage, refermées derrière, pas de porte qui reste
      ouverte indéfiniment sans raison
- [ ] Village fortifié : les travellers passent par la porte du mur, ne restent pas coincés
      dans un coin intérieur
- [ ] Camp events : un traveller isolé campe parfois, construit un abri visible
      progressivement, pas instantanément
- [ ] Élytres (si équipés) : décollage/vol/atterrissage sans plantage, dégâts de chute annulés
      pendant le flare
- [ ] Worldmap : tuiles se chargent, marqueurs (villages, camps, batailles) cohérents,
      brouillard de guerre sur les unités étrangères
- [ ] Notifications : toasts à l'écran + journal persistant accessible
- [ ] Zone joueur Barrack : logement de worker fonctionne comme avant (pas de régression du
      système générique)
- [ ] Apparence des travellers/workers cohérente avec leur culture (têtes dans le Hub incluses)
- [ ] Son/chorale : les travellers chantent en groupe, spatialisé correctement
- [ ] Écran de config (`/travellersconfig`) : les changements s'appliquent et persistent

## 9. Spécifique multijoueur / serveur dédié

- [ ] Deux joueurs connectés en même temps : pas de désync visuelle entre eux
- [ ] Déclarer un joueur hostile (Behavior GUI) → invisible pour lui jusqu'à ce qu'un de tes
      workers le frappe, puis devient mutuel et il est notifié
- [ ] Couleur/style de marqueur d'un joueur visible correctement pour les autres sur la carte
- [ ] Reconnexion après un moment déconnecté : les workers/travellers de ce joueur sont
      cohérents au retour (pas de doublons, pas de perte)

## 10. Points connus à surveiller spécialement

Ces éléments sont identifiés comme pas encore corrigés ou pas encore testés en jeu réel :

- Un raider peut avoir une voix qui ne correspond pas à sa culture/personnalité réelle
  (mismatch identifié, pas corrigé)
- Juste après matérialisation près d'un joueur, un groupe hors-écran peut brièvement changer
  de formation avant de se stabiliser dans son vrai style — normal une fraction de seconde,
  suspect si ça persiste
- Sous grosse charge (beaucoup de suiveurs qui demandent un chemin en même temps), certains
  peuvent rester sans réponse plus longtemps que prévu — à surveiller si des workers/travellers
  semblent bloqués sans raison avec beaucoup de monde à l'écran

## 11. En cas de bug trouvé

- Lance une trace sur l'entité concernée : `/travellersdebug trace start <nom>` (ou attends —
  une capture automatique se déclenche déjà si elle reste bloquée trop longtemps)
- Le fichier généré est dans `logs/travellers/trace/`
- Pour un problème de monture : `/travellersdebug mount`
- Pour un problème de Stable : `/travellersdebug stable dump <n>` et `log <n>`
- Note les coordonnées, l'heure en jeu, et ce que tu faisais juste avant
