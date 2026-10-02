# Grand nettoyage — prompt de lancement

**Statut** : à lancer. Cette session est une **analyse + proposition**, pas une implémentation : aucun fichier source ne change avant que j'aie validé le plan.

## Ce que je veux

Mon Claude, je veux un grand nettoyage / refactor de notre mod : structure des packages **et** code lui-même. Je suis prêt à un très gros refactor si c'est justifié, mais le but n'est pas de tout casser pour le plaisir : trouve les **grosses** choses qui méritent un redesign, une unification ou simplement une meilleure organisation.

Le but final, c'est que **toi** tu sois efficace :
- explorer le code doit te coûter peu de contexte (des fichiers courts, un système = un endroit) ;
- une modification d'un comportement doit se faire **à un seul endroit**, pas dans 50 classes ;
- deux logiques censées faire la même chose ne doivent jamais exister en parallèle et diverger.

Exemple de ce que j'entends par là : on vient de centraliser toute la lecture de chunks dans `ChunkReader`, et toutes les questions de terrain dans `BlockRules` / `Footing` / `ColumnGround`. Avant, chaque système avait sa propre copie, légèrement différente. C'est ce genre de travail que je veux, partout où ça vaut le coup.

Je ne suis pas développeur : c'est à toi de trouver quoi améliorer et de me dire franchement si de grosses modifications sont nécessaires.

## Contexte de la session précédente (2026-10-02)

- La doc a été refaite : `CLAUDE.md` racine (règles, index des systèmes, tableau des « autorités uniques », invariants transversaux) + un `CLAUDE.md` par package de sous-système. Lis la racine. Ne lis les `CLAUDE.md` de dossier que pour les systèmes que tu analyses.
- Un refactor léger est déjà fait (dossiers vides et code mort supprimés, `mount/` sorti de l'IA, messages réseau `home/` → `zone/` + `equip/`, `leadfollow/` → `group/`).
- Le travail reporté est en mémoire (`project_deferred_work`), y compris les points de structure déjà repérés.

## Pistes déjà repérées (à vérifier, pas à prendre pour acquises)

- **Classes géantes** : `EntityTraveller` (~5 200 lignes), `EntityTravellerWorker` (~4 200), `VirtualTravellerManager` (~2 800), `EntityAIFollowLeader` (~2 500), `CommandTravellerDebug` (~2 100), `EntityAITravel` (~2 000), `TravellerMountSupport` (~2 000), `FollowFormationCoordinator` (~1 600), `GuiWorkerFormationHub` (~1 400).
- **Systèmes éparpillés par couche** (`client/`, `server/`, `entity/`, `world/`) au lieu d'être regroupés par fonctionnalité : montures, visite de village, dialogue, zones… Un `CLAUDE.md` de dossier ne se charge que si l'agent touche ce dossier, donc l'éparpillement coûte cher.
- **Trois ordonnanceurs d'A\*** : `PathScheduler` (follow), `TravelPathScheduler`, `CombatPathScheduler` — candidat évident à l'unification.
- **Pathing** : plusieurs couches de « où aller / est-ce que ça passe » (`TravellerNavigateGround`, `TravelTerrain`, `TravelRescue`, `FollowRecoveryHelper`, `MaterializePositionResolver`…) : y a-t-il encore des doublons ?
- **`server/`** contient de la logique de jeu (groupes, dialogue, worldmap), ce qui rend le nom trompeur ; `combat/` et `entity/entityai/combat/` portent le même nom ; `world/data/` est un fourre-tout.
- **Config** : `TravellersConfig` fait ~3 400 lignes (46 catégories, ~800 champs). Nouvelle règle : un champ de config n'existe que si un joueur ou un admin voudrait vraiment le régler, sinon c'est une constante dans le code. Des champs morts existent (ex. `GROUP_FOLLOW`).
- **GUI** : vérifier les doublons de logique entre écrans (marqueurs, listes, sélection).

## Comment travailler

1. **Inventaire mesuré, pas d'impression** : tailles de fichiers, doublons réels (montre les deux copies), code mort (vérifié par recherche d'usages), dépendances entre packages. Tu peux utiliser des sous-agents `Explore` pour l'inventaire, afin de garder ton propre contexte léger.
2. **Discute avec moi avant de figer quoi que ce soit** (`AskUserQuestion`, option recommandée en premier). Chaque choix de conception passe par moi.
3. **Livrable** : un plan dans `docs/grand-nettoyage-plan.md`, au format de nos plans (statut en tête, but, décisions prises avec moi, étapes avec un statut chacune). Le plan doit être :
   - **découpé en phases indépendantes**, chacune qui compile (`./gradlew build`) et laisse le mod jouable ;
   - **ordonné par gain / risque**, les gros gains à faible risque en premier ;
   - **concret** : pour chaque phase, quels fichiers bougent, ce qui fusionne, ce qui est supprimé, et ce que je pourrais remarquer en jeu (idéalement : rien).

## Contraintes à ne jamais casser

- **Sauvegardes** : clés NBT, ids d'entités, clés `WorldSavedData` (`"TravellersLoneBarracks"` notamment), noms de catégories et champs de config (mes surcharges `.properties` sauvegardées), format des records virtuels. Une sauvegarde Barrack doit rester compatible. Le système montures + Stable n'est pas encore publié, il peut casser.
- **Réseau** : l'ordre d'enregistrement des messages doit rester cohérent entre client et serveur.
- **Mixins** : déplacer une classe ciblée ou un mixin impose de mettre à jour les configs mixin (early vs normale).
- **Comportement** : chargé et virtuel doivent rester identiques ; aucun refactor ne doit changer ce que voit le joueur sans que je l'aie décidé.
- **Doc** : quand une phase déplace un système, son `CLAUDE.md` de dossier déménage avec lui et l'index « Code map » du `CLAUDE.md` racine est mis à jour (en me proposant les lignes, selon nos règles de doc).

## Notes user
Le but est aussi d'avoir un mod qui soit consistent en termes de performances, tu le sais déjà et n'a pas grand chose à voir avec ce qu'on va faire mais la centralisation des choses rendra l'optimisation de la logique plus facile.
