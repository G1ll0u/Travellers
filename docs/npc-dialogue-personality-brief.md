# Brief : rédiger des fiches de personnalité pour le système de dialogue NPC de Travellers

On t'a demandé de rédiger (ou d'améliorer) des **fiches de personnalité** pour la fonctionnalité de
dialogue NPC du mod Travellers. **Tu n'as pas accès au code source du mod ni à ses notes de projet
internes — seulement ce document, le `README.md` du projet, `CLAUDE.md`, et les éventuelles fiches
d'exemple qu'on t'aura données en plus de ce brief.** Ce n'est pas un problème : ce document est
entièrement autosuffisant et contient tout ce dont tu as besoin sur le système, exemples inclus — tu
n'as jamais besoin de demander un accès au code ou au système de fichiers pour bien faire ce travail.
La section "NPC dialogue system" de `CLAUDE.md` donne le même tableau vu côté code, si tu veux un
second angle sur un point précis.

## 1. Ce qu'est ce système

Faire un clic droit sur un traveller NPC ordinaire (non engagé, non hostile) ouvre une vraie fenêtre
de chat. Le joueur tape du texte libre ; le NPC répond **en restant dans son personnage**, généré en
direct par un modèle de langage — une instance Ollama tournant en local sur **l'ordinateur du joueur
lui-même** (le serveur de jeu ne parle jamais à un service d'IA quelconque). Chaque traveller a un
`cultureId` (normand, japonais, inuit, byzantin, etc. — selon les cultures Millénaire installées) et
un `personalityId`, tiré une seule fois à son apparition et conservé pour toute sa vie.

**Ta tâche est étroite et précise** : écrire le fichier texte qui indique au modèle *comment parle une
personnalité donnée* — tempérament, façon de parler, tics. Tu ne touches pas au code, tu ne décides
pas quelles cultures reçoivent quelles personnalités, et tu ne construis rien d'autre. Le reste de ce
document détaille le système pour que tes fiches s'y intègrent correctement, mais le livrable, ce sont
juste des fichiers `.md` de texte de "voix" de personnage.

## 2. Format de fichier (mécanique exacte)

- **Chemin** : `assets/travellers/personalities/<lang>/<personalityId>.md`
  `<lang>` est la langue parlée par la *culture* (actuellement `en` et `fr` sont utilisées activement ;
  une culture sans fichier dans sa langue n'a simplement aucun texte de personnalité — jamais de repli
  vers une autre langue). `<personalityId>` est un identifiant court en minuscules, ex. `gilles_fou`,
  `afraid`, `veteran_amnesique`.
- **Un même personalityId peut exister dans plusieurs dossiers de langue** (ex. `gilles_fou.md` à la
  fois sous `en/` et `fr/`). Les deux versions ne sont pas une traduction littérale l'une de l'autre —
  écris chacune comme un texte naturel et idiomatique dans sa langue, mais elles doivent se lire comme
  *la voix du même personnage*.
- **Pas de fichier = pas de texte de personnalité.** Si la langue d'une culture n'a pas de fiche pour
  un id donné, le NPC n'a simplement pas de section "tempérament" dans son prompt — jamais d'erreur,
  jamais de repli vers une autre langue.
- **Structure** (exactement celle-ci, trois sections `##`, rien de plus) :
  ```markdown
  ## Temperament
  <2-4 phrases : les traits de personnalité centraux>

  ## How they speak
  <2-3 phrases : rythme, ton, vocabulaire, tics verbaux>

  ## Quirks
  <1-3 phrases : sujets récurrents, private jokes, choses qu'il/elle aborde ou demande toujours>
  ```
  (Les en-têtes `##` eux-mêmes restent en anglais, tel quel — c'est une convention fixe du système,
  pas du texte affiché au joueur. Seul le contenu sous chaque en-tête est dans la langue du dossier.)
- **Longueur plafonnée à 1200 caractères**, en-têtes et espaces compris. Au-delà, le texte est coupé en
  plein milieu d'une phrase avec `...` ajouté — pas refusé, juste moche. Reste largement en dessous :
  **environ 700–1000 caractères est la zone idéale.** C'est voulu — la fenêtre de contexte d'un modèle
  local est précieuse, et une fiche de personnalité est un court paragraphe ou deux, pas une biographie.
- **Prose simple uniquement.** Pas de listes imbriquées, pas de gras/italique décoratif, pas de blocs
  de code, pas de tableaux. Ce texte est collé tel quel dans un system prompt de LLM.
- Dans ce projet, les commentaires de code sont en anglais uniquement et le contenu français y est
  traduit — **sauf ce cas précis** : le *contenu* des fiches `fr/*.md` est destiné à être lu par un
  modèle jouant un personnage francophone et doit rester en français. Le fichier lui-même, et ce brief,
  suivent sinon la convention normale du projet.

Si on t'a donné des fichiers de fiches d'exemple en plus de ce brief, utilise-les comme référence de
style principale. Sinon, voici trois fiches réelles, déjà en production (en anglais), montrant la forme
exacte attendue — courtes, trois sections, chaque trait exprimé comme une *voix*, jamais comme une
action :

Je t'ai mis en attachement les personnalités que je trouve les plus drôles (gilles_fou + silva)

Remarque : aucune des deux ne dit jamais ce que fait le personnage — seulement comment il/elle sonne.

## 3. La règle la plus importante : **décrire la voix, jamais l'action**

Le system prompt partagé (du code auquel tu n'as pas besoin de toucher) dit déjà au modèle, pour
chaque personnalité, à chaque fois : *« Only write the words you actually say out loud, in plain
sentences — nothing else. Never write actions, gestures, descriptions of your body or face, or any
narration (no asterisks, no italics, no third-person description of yourself). If a trait makes you
nervous or excitable, show it through your wording and tone alone, not through a description of what
you are doing. »*

En clair : ta fiche ne doit **jamais** décrire un comportement physique comme quelque chose à mettre
en scène — pas de « *jette des regards nerveux autour de lui* », pas de « triture son manteau », pas
de « yeux qui fuient ». Traduis chaque tic physique en tic **verbal** à la place :

| À ne pas écrire (une action) | À écrire à la place (un trait verbal) |
|---|---|
| « Regarde sans cesse par-dessus son épaule » | « Voix tendue par le malaise, phrases qui s'interrompent » |
| « Triture ses mains » | « Parle par courtes phrases hachées » |
| « Rit nerveusement » | « Ponctue ses phrases d'un rire forcé, trop rapide » |

Si une fiche en cours de rédaction glisse vers des didascalies, réécris-la en ton/formulation avant de
la livrer — c'est déjà arrivé deux fois dans ce projet (voir l'historique de `en/afraid.md` : à
l'origine elle décrivait le personnage en train de regarder autour de lui, et a été réécrite en « voix
tendue par le malaise » à la place).

## 4. Ce que le traveller sait réellement — référence complète

C'est la partie qu'il te faut avec le plus de détails, car une bonne fiche de personnalité joue *avec*
ce que le personnage peut plausiblement savoir ou mal interpréter, sans jamais le contredire.

**Tout ce qui suit est calculé à neuf, côté serveur, au moment précis où le joueur ouvre la fenêtre de
chat — jamais mis à jour en direct pendant la conversation.** C'est transmis au modèle comme system
prompt, construit en trois paliers additifs que chaque joueur règle pour lui-même (`MINIMAL` /
`STANDARD` / `RICH` — chaque palier inclut tout ce que le palier en dessous a, plus davantage). **Ta
fiche n'a aucun contrôle sur ce palier** — écris-la en supposant que n'importe quel palier peut être
actif ; n'écris jamais un trait qui n'a de sens que si une info réservée à RICH est présente.

### Toujours présent (à tous les paliers, y compris MINIMAL)

- Le **nom** et la **culture** du traveller lui-même (« a traveller of the norman people »).
- **Texte d'ambiance culturelle** — le texte de lore officiel de Millénaire pour cette culture, repris
  tel quel (pas rédigé par toi). C'est la voix de la *culture*, distincte de la personnalité.
- **Ta fiche de personnalité**, reprise telle quelle, introduite par « Your temperament and manner of
  speaking: ».
- **Activité actuelle**, un parmi exactement quatre états, formulé comme un fait simple :
  - *En route vers un village nommé* — nom de la destination, sa culture, et (village vs. un simple
    « bâtiment isolé »)
  - *En train de rester dans un village un moment* (déjà arrivé, sur place)
  - *En train de voyager avec quelqu'un d'autre* (dans un groupe d'escorte — « ne peut pas s'arrêter
    longtemps pour parler »)
  - *En train d'errer, sans destination fixe pour l'instant*
- **Villages à proximité** (une courte liste triée, limitée par config, du plus proche au plus
  lointain) : pour chacun, son nom, sa culture, une **direction** approximative (8 points cardinaux) et
  une **distance** approximative, exprimée en mot (« a short walk » / « a fair distance » / « a long
  way » / « a very long way ») — **jamais un nombre de blocs.** Cette formulation est calibrée sur
  l'espacement réel des villages sur ce monde précis (le « loin » d'un petit monde n'est pas celui d'un
  grand monde) — tu n'as pas besoin de penser au calcul, sache juste que ces mots sont toujours
  relatifs, jamais littéraux.

### Le palier STANDARD ajoute

- **D'où il vient** : nom du village d'origine, et une courte liste des noms de villages traversés
  récemment (son propre historique de voyage réel, le plus récent en premier).
- **Détail supplémentaire sur le village de destination/actuel** : taille approximative (« a tiny
  hamlet » à « a very large village » — jamais un chiffre), relation entre ce village et le village
  *d'origine* du traveller (« hostile » / « tense » / « neutral » / « friendly » / « allied »), et —
  seulement pendant qu'il s'y rend vraiment — un mot de distance pour cette destination aussi.
- **Proximité physique du moment présent**, indépendamment de ce qui précède : le traveller peut se
  trouver debout, là, tout de suite, dans un village *différent* de celui vers lequel il se dirige ou
  dans lequel il séjourne, ou juste à côté d'un — indiqué comme un fait séparé quand c'est le cas.
- **État du monde** : moment de la journée (aube/jour/crépuscule/nuit), météo (clair/pluie/orage), le
  nom du biome actuel, une description sommaire du terrain (« flat and open » / « hilly » / « steep
  and rugged », plus « with water nearby » le cas échéant), si un paysage nettement différent se trouve
  juste après l'horizon, le fait d'être dans la neige, et des points de repère lointains notables (un
  autre type de biome, direction + distance formulées de la même façon vague).
- **L'état du traveller lui-même** : un mot de santé (mentionné seulement quand il *n'est pas* en pleine
  forme — « wounded » / « badly hurt »), s'il est en train de monter/mener une monture, de camper pour
  la nuit, ou encore secoué par une échauffourée récente.
- **Qui est à proximité** : animaux au pâturage (un mot de quantité approximative, « a few sheep » —
  jamais un chiffre exact), autres joueurs, les propres henchmen *du joueur qui parle* spécifiquement,
  d'autres travellers/henchmen qui passent, et la présence éventuelle d'un raider qui rôde (les petits
  effectifs de *personnes* sont donnés en chiffres exacts ici — c'est quelque chose que n'importe qui
  sur place verrait et compterait naturellement, contrairement aux données de population/relation qui
  décrivent des choses invisibles à un simple témoin).
- **« On s'est déjà rencontrés ? »** — un simple fait oui/non, indépendant du reste, si ce joueur précis
  a déjà parlé à ce traveller précis.
- **Faits mémorisés sur ce joueur** — une courte liste de faits distillés issus de *conversations
  passées avec ce joueur précis*, seulement si la fonctionnalité de mémoire du serveur est activée sur
  ce monde. Traite-les comme n'importe quel autre fait donné : référence-les naturellement, n'en invente
  jamais de nouveaux par-dessus.

### Le palier RICH ajoute

- **L'équipement porté/tenu du traveller lui-même** : nom et un mot d'état approximatif par pièce
  (« Iron Sword (worn) » / « Leather Boots (about to break) » / « Wooden Bow (like new) ») — pour les
  deux mains et les quatre emplacements d'armure, ceux qui sont occupés. L'état est exprimé de façon
  relative, comme la distance : jamais un pourcentage exact.
- **L'état du joueur lui-même** : un mot de santé apparente et un mot de faim (ex. « The person you're
  talking to looks wounded and hungry »).
- **L'équipement porté/tenu du joueur lui-même**, même format nom+état que pour le traveller.

### Contraintes strictes intégrées à tous les paliers, toutes les personnalités (ne va jamais à l'encontre de ceci dans ta fiche)

- **Échange en face à face, en direct, pas le récit d'une relation existante** : le traveller vient
  tout juste de rencontrer ce joueur précis et ne le connaît pas forcément — jamais un compagnon de longue date,
  jamais « ton compagnon de route ».
- **Cadre** : monde peu technologique, d'ère médiévale, « dans l'esprit de l'an 1100 » — ne mentionne
  jamais de jeu vidéo, de joueur, ou le fait de ne pas être réel.
- **Ambiance quotidienne** (toujours vraie, indépendante de l'état en direct) : les travellers campent
  parfois, pêchent, chantent ensemble, ou s'arrêtent dans des villages — des choses ordinaires, sans
  rien de remarquable à moins qu'on lui pose la question directement.
- **Discours bref et naturel** : une phrase ou deux par réponse, pas un discours.
- **Aucune affirmation inventée sur le joueur** : ne prétend jamais remarquer quelque chose sur les
  vêtements, l'équipement, ou la fatigue du joueur *sauf si ça a été réellement donné ci-dessus* (donc
  seulement au palier RICH, et seulement ce qui est vrai) — les remarques vagues sur le monde en général
  sont acceptables, les affirmations précises sur ce qui se trouve physiquement en face du modèle ne le
  sont pas, sauf si elles sont fondées sur un fait donné.
- **Jamais de chiffres exacts** : pas de nombre de blocs, pas de coordonnées, pas de pourcentages —
  seulement les mots vagues/directions décrits plus haut.
- **Répond dans la langue que le joueur utilise pour écrire**, quelle que soit la langue de la fiche.
- **« Tu es arrêté en train de parler à cette personne, vous ne voyagez pas ensemble »** — renforcé
  explicitement pour que le modèle n'imagine pas marcher aux côtés du joueur.

Rien de tout ça n'a besoin d'être répété dans ta fiche — c'est déjà imposé ailleurs, pour chaque
personnalité, automatiquement. **Ta fiche ne doit jamais le contredire** (ex. n'écris pas une
personnalité qui « se souvient d'avoir voyagé des semaines avec le joueur » — c'est structurellement
faux et le modèle se retrouvera avec deux instructions contradictoires à suivre en même temps).

## 5. Deux autres mécanismes à connaître (ne conçois pas autour, sois juste au courant)

- **Appels d'outils optionnels** : un joueur *peut* (option client, désactivée par défaut, ne
  fonctionne qu'avec des modèles locaux capables d'appeler des outils) laisser son traveller consulter
  deux choses à la demande en pleine conversation : la relation entre deux villages *nommés*, et —
  seulement si la fonctionnalité de mémoire du monde est activée — aller chercher un souvenir plus
  ancien, moins récent, sur le joueur actuel. Ce n'est pas fiable pour construire une fiche autour :
  beaucoup de joueurs n'auront ni l'option activée ni un modèle assez capable. Une personnalité
  naturellement curieuse/bavarde sur la politique des villages est un bon fit *si* on le lui demande,
  mais ne suppose jamais que l'outil s'est déclenché.
- **Rétention de mémoire pilotée par la personnalité** : deux personnalités existantes
  (`veteran_amnesique`, `gilles_fou`) ont un multiplicateur de capacité mémoire spécial, *codé en dur*
  (oublie anormalement vite / anormalement lentement) via une petite liste d'exceptions dans le code
  serveur — **ce n'est pas quelque chose que ta fiche `.md` contrôle.** Si une nouvelle personnalité
  que tu écris est thématiquement liée à l'oubli ou à une mémoire exceptionnelle, signale cette
  intention clairement en rendant ton travail (ex. « celle-ci devrait sans doute avoir le même
  traitement d'oubli rapide que `veteran_amnesique` ») — ça nécessite une petite modification de code
  ailleurs, pas une modification de fiche.

## 6. Checklist avant de considérer une fiche terminée

- [ ] Exactement trois sections `##` : `Temperament`, `How they speak`, `Quirks`.
- [ ] Moins de ~1000 caractères au total (plafond dur à 1200, coupure moche au-delà).
- [ ] Chaque trait est décrit comme *formulation/ton*, jamais comme une action ou un geste physique.
- [ ] Ne prétend pas déjà connaître le joueur ou voyager avec lui.
- [ ] N'invente pas un « sens » que le personnage ne pourrait pas avoir (distances exactes,
      coordonnées, chiffres).
- [ ] Se lit naturellement à voix haute dans la langue cible — pas un écho traduction-mot-à-mot de
      l'autre fichier de langue.
- [ ] Si une variante de langue manque (ex. tu as écrit `en/` mais cette personnalité devrait aussi
      exister pour une culture francophone), signale-le plutôt que de laisser silencieusement
      uniquement la version anglaise.

## Notes utilisateur
L'ambience de ce mod est un peu rigolotte, j'aime bien avoir des personnalités complètement différentes les unes des autres.
Par exemple, la personnalité Silva est l'une des plus drôles avec son humour noir et sa méchanceté gratuite,
Il ne faut pas hésiter à créer des personnalités qui soient réellement drôles, stupides, méchantes, très bêtes, même des personnalités qui sont totalement incompréhensibles par le joueur.

Attention au fait qu'on utilise des LLM locaux, il ne faut pas non plus qu'il commence à trop halluciner et partir dans tous les sens.

N'oublie pas que cela se passe dans un contexte moyen-âgeux (11-12ème siècle) le monde est cru et tout le monde a une vie dure mais pourtant c'est la norme.
Les gens ont des "couilles" si tu vois ce que je veux dire.

Blagues que j'aime bien :
"Ce soir quand je rentre à la maison je vais aller engueuler ma femme"
"J'ai abandonné mes enfants dans la forêt là-bas ils me cassaient les pieds"

Prends en compte que les travellers sont conscients de différentes choses que ce soit l'environnement, le terrain, les villages, les entités autour d'eux etc.
J'aime les personnalités épicées quoi. A toi de me suggérer des personnalités originales et intéressantes.

Avant tout, dis-moi ce que tu me proposes avant de générer les .md
N'hésite pas à y aller sur la grossièreté/vulgarité c'est important.