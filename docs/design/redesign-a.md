# Redesign A · tema e componenti condivisi (0.7.0, fase 1)

Fondamenta per il ridisegno delle schermate (fase 2). Sorgente visiva: il canvas «Argus · Redesign A — Evoluzione»
(tavola «Sistema di design A» e tavole di schermata). Il redesign cambia l'aspetto, non i testi di sicurezza né le
funzioni: il copy di sicurezza resta quello letterale di `docs/design/README.md` §7 e gli invarianti 1-8 di `CLAUDE.md`
restano validi.

**Regola d'oro per la fase 2:** una schermata non contiene colori, raggi, spaziature, font o stili di testo propri.
Usa i token del tema e i componenti di `ui/.../components/`. Se manca un componente, si aggiunge qui (con anteprima nei
due temi e una riga in questo documento), non nella schermata.

## 1. Tema

`ArgusTheme { ... }` segue il tema del sistema (`isSystemInDarkTheme()`): nessuna scelta in Impostazioni. Fornisce
`MaterialTheme` (colori, tipografia, forme) più due insiemi Argus letti con `ArgusTheme.semantic` e `ArgusTheme.surfaces`.

| File | Contenuto |
|---|---|
| `theme/Color.kt` | Palette grezza (`internal`) e gli schemi M3 `ArgusDarkColors` / `ArgusLightColors` |
| `theme/SemanticColors.kt` | `ArgusSemantic` (coppie di stato), `ArgusSurfaces` (alias delle superfici, hero, scrim, disabilitati) |
| `theme/Type.kt` | `ArgusSans`, `ArgusMono`, `ArgusType` (scala M3), `ArgusCode`, `ArgusCodeSmall` |
| `theme/Theme.kt` | `ArgusTheme`, `ArgusRadius`, `ArgusSpacing`, `ArgusShapes` |
| `preview/ArgusPreviews.kt` | `@ArgusThemePreviews` (chiaro + scuro) e `ArgusPreviewSurface` |

### 1.1 Colore: cosa usare per cosa

| Token | Uso |
|---|---|
| `colorScheme.background` | Fondo schermata = alias `page` (lo dipinge lo `Scaffold`) |
| `colorScheme.surfaceVariant` | Card = alias `card` (compatibilità con le schermate di oggi; nel codice nuovo usare `ArgusTheme.surfaces.card`) |
| `colorScheme.onSurface` / `onSurfaceVariant` | Testo principale / secondario (`ColorScheme.secondaryText()` = `onSurfaceVariant`). Mai `outline` per il testo |
| `colorScheme.primary` / `onPrimary` | L'unico pulsante pieno, segmento scelto, interruttore acceso, testo-link, indicatore di focus |
| `colorScheme.primaryContainer` / `onPrimaryContainer` | Contenitore trigger «normale», bolle dell'utente in chat |
| `colorScheme.secondaryContainer` / `onSecondaryContainer` | Hero del riepilogo, pulsante tonale, voce attiva della nav, scelta in un modulo, nodi di flusso |
| `colorScheme.tertiary*` | Riservato (lilla del grafo); le regole generative usano `semantic.generative` |
| `colorScheme.error` / `errorContainer` | Azioni distruttive di sistema, badge `!` della nav. Per i testi di errore e gli stati vedi `semantic.error` |
| `colorScheme.outline` | Contorni di controlli (≥ 3:1): campo disabilitato, pulsante contorno, interruttore spento, chip a contorno |
| `colorScheme.outlineVariant` | Divisori e filetti |
| `colorScheme.scrim` | Non usato direttamente: lo scrim dei fogli è `surfaces.scrim` (già con opacità) |

Superfici (`ArgusTheme.surfaces`):

| Alias | Scuro → Chiaro (ruolo M3) | Uso |
|---|---|---|
| `page` | `surface` → `surfaceContainer` | Fondo schermata |
| `card` | `surfaceContainer` → `surfaceContainerLowest` | Righe, gruppi, pannelli |
| `card2` | `…High` → `…Low` | Annidati, chip a riposo, segmenti non scelti, campo disabilitato |
| `field` | `…Highest` → `…Highest` | Campi, binari, interruttore spento |
| `nav` | `…Low` → `…Lowest` | Barra di navigazione, footer di azione |
| `sheet` | `…Low` → `…Low` | Bottom sheet |
| `codeSurface` | `#0A0E13` → `#E9EDF3` | Blocco shell, dettaglio tecnico |
| `scrim`, `disabledContainer`, `disabledContent` | — | Oscuramento (64% / 42%), pulsante disabilitato |
| `heroTile`, `heroLine`, `heroSecondary` | — | Riquadri e testi secondari del riepilogo |

Coppie di stato (`ArgusTheme.semantic`, ognuna `RolePair(fg, bg)`): `armed` (verde), `pending` (ambra, aspetta te),
`needsReview` (arancio), `error` (rosso), `disabled` (grigio), `generative` (lilla), `cloud` (azzurro-verde). Il colore
non è mai l'unico segnale: ogni stato ha icona e testo propri. Scala di gravità: ambra → arancio → rosso. In più:
`codeText` (testo del codice) e il ruolo caldo `attention` / `onAttention` / `attentionContainer` / `onAttentionContainer`
(badge di conteggio, contatore «in attesa», etichetta shell, pulsante «Correggi»).

Contrasto: ogni coppia testo/sfondo ≥ 4,5:1 e ogni controllo non testuale ≥ 3:1, nei due temi, verificato da
`DesignContrastTest` sui valori dei token. Se aggiungi un token o un uso nuovo, aggiungi la coppia al test.

### 1.2 Tipografia

| Stile | Dimensione/interlinea · peso | Uso |
|---|---|---|
| `headlineMedium` | 28/36 · 700 | Titolo di schermata (`ArgusLargeTopBar`) |
| `headlineSmall` | 26/34 · 700 | Titoli di onboarding |
| `titleLarge` | 24/32 · 700 | Titolo della regola nel dettaglio |
| `titleMedium` | 22/28 · 600 | Titolo di foglio / sezione grande |
| `titleSmall` | 16/22 · 600 | Titolo di riga |
| `labelLarge` | 15/20 · 600 (700 nei pulsanti pieni/tonali) | Pulsanti, titoli di tessera |
| `bodyLarge` | 16/24 · 400 | Testo corrente, messaggi |
| `bodyMedium` | 14/20 · 400 | Sottotitoli, descrizioni |
| `bodySmall` | 13/18 · 400 | Note, motivi di un'azione bloccata |
| `labelMedium` | 12/16 · 600 | Chip, etichette di campo |
| `labelSmall` | 11/16 · 700 · +0,08 em | Intestazioni di sezione in maiuscolo (`SectionLabel`) |
| `ArgusCode` / `ArgusCodeSmall` | 13/20 · 12,5/19 (mono) | Comandi, endpoint, modelli, dettagli tecnici |

Tutto in `sp`. I testi vanno a capo: nessuna riga fissa a una sola riga tranne dove la tavola lo dice.

**Font (decisione).** Nessun font scaricato a runtime: i font scaricabili di Google Play Services non vanno bene per
F-Droid. Entrambi i font sono nel pacchetto (`ui/src/main/res/font/`), licenza SIL OFL 1.1 (testi in
`docs/third-party/fonts/`):

- **Roboto** v3, quattro istanze statiche 400/500/600/700 ricavate dal font variabile ufficiale di Google Fonts e ridotte a
  Latin (≈ 66 KB l'una). Il design usa il peso 600, che i font di sistema OEM (OnePlus Sans, SamsungOne…) non garantiscono.
- **JetBrains Mono** 400/500, ridotto a Latin (≈ 91 KB l'uno) e **senza ligature** (`calt`/`liga` tolte dal file): in un
  comando shell `->`, `!=`, `=>` restano i caratteri scritti (invariante 6). Distingue 0/O e 1/l/I.
- Costo totale ≈ 450 KB nell'APK. I caratteri fuori dal sottoinsieme (cirillico, CJK, emoji…) ricadono sul font di sistema.
- Riprodurre: `fontTools.varLib.instancer` (`wght`, `wdth=100`) + `fontTools.subset` con
  `U+0020-007E, U+00A0-024F, U+0300-036F, U+1E00-1EFF, U+2000-206F, U+20A0-20CF, U+2100-214F, U+2190-21FF,
  U+2200-22FF, U+25A0-25FF, U+2713-2714` e feature `kern, liga (solo Roboto), ccmp, locl, mark, mkmk`. I due OFL non
  dichiarano Reserved Font Name, quindi la riduzione è consentita.

### 1.3 Forme e spaziature

`ArgusRadius`: `Xs` 6 (angoli interni dei gruppi), `Sm` 10 (icone piccole), `Md` 14 (campi, contenitori trigger), `Lg` 20
(tessere, nodi), `Xl` 24 (esterno dei gruppi, pannelli), `Xxl` 28 (riepilogo, card bozza, sheet). «Full» = pillola
(`CircleShape`). Gli slot M3 puntano qui (`extraSmall`=6 … `extraLarge`=28).

`ArgusSpacing`: `GroupGap` 3 (tra gli elementi di un gruppo), `Unit` 4, `Small` 8, `Medium` 12 (padding righe, icona-testo,
tra gruppi), `Large` 16 (margine laterale schermo, padding pannelli), `Section` 24 (tra sezioni, inset sotto i footer),
`TouchTarget` 48. Area tattile minima 48 dp per ogni controllo (i componenti la garantiscono).

## 2. Componenti (`ui/.../components/`)

Tutti hanno `@ArgusThemePreviews` (chiaro e scuro) nel proprio file. Le firme sono abbreviate: i parametri omessi sono
`modifier` e quelli con default.

### Chip di stato · `Badges.kt`
- `StatusChip(role: RolePair, icon, text, modifier, size: ChipSize = Compact)`: chip generico.
- `StatusBadgeChip(status: StatusBadge, modifier, size)`: armata / in approvazione / disattivata / da rivedere.
- `GenerativeTag(modifier, size)` e `CloudTag(modifier, size)`: SEMPRE in coppia su una regola generativa (invariante 5).
- `LiveConfirmTag(modifier, size)` / `OutlineChip(icon, text, modifier, size)`: chip neutro a contorno.
- `WarningCountChip(text, modifier, size)`: «1 avviso».
- `ChipSize`: `Compact` (24 dp, righe) e `Regular` (26 dp, intestazioni di dettaglio).

### Card e gruppi · `Cards.kt`
- `ArgusCard(modifier, shape = 24, containerColor = surfaces.card, border, contentPadding = 16, onClick?) { ColumnScope }`.
- `ArgusCardGroup(items: List<T>, modifier, outerRadius = 24, innerRadius = 6) { item -> }`: gruppo segmentato (3 dp di
  fessura, angoli grandi in testa e in coda, piccoli in mezzo). Il contenuto gestisce il proprio padding.
- `GroupPosition { Only, First, Middle, Last }`, `groupPositionOf(index, count)`, `groupShape(position, outer, inner)`:
  per le liste pigre (`LazyColumn`): `ArgusCard(shape = groupShape(groupPositionOf(i, n)), contentPadding = PaddingValues(0.dp))`
  con `Arrangement.spacedBy(ArgusSpacing.GroupGap)`. Il Log usa 22/5, «ultime esecuzioni» 18/4.

### Riga lista e contenitori icona · `ListRows.kt`
- `ArgusListRow(title, subtitle?, leading?, titleSuffix?, meta?, trailing?, onClick?, onClickLabel?, titleColor, subtitleColor)`:
  l'area tappabile è tutto tranne `trailing` (che ha il suo tocco: interruttore). Altezza minima 56. Righe «rumore utile»:
  `titleColor = onSurfaceVariant`, mai trasparenza.
- `TriggerContainer(icon, shape: TriggerShape, tone: TriggerTone)`: 44 dp. La forma dice il tipo di trigger (`Circle` orario,
  `Squircle` notifica, `Drop` geofence, `Rhombus` telefono/SMS, `Arch` connettività, `Cookie` sensore/immediato), il colore
  dice lo stato (`Normal` blu, `Pending` ambra, `NeedsReview` arancio, `Disabled` grigio).
- `IconBadge(icon, container, content, size = 32, iconSize = 18, shape = CircleShape, contentDescription?)`: icona di stato
  nelle righe del log (cerchio 30-32), nodi (28), cerchi dei banner.
- `WarningMark(contentDescription)`: triangolo «Ha avvisi» per `titleSuffix`.
- `SectionLabel(text)`: intestazione di sezione maiuscola, esposta a TalkBack come titolo.

### Barra di navigazione · `NavigationBar.kt`
- `ArgusNavigationBar(items: List<ArgusNavItem>, selectedRoute, onSelect)`.
- `ArgusNavItem(route, label, icon, badge: NavBadge? = null, badgeDescription: String? = null, testTag: String? = null)`;
  `NavBadge`: `Count(n)` pillola ambra, `Alert` (`!` rosso), `Dot` (punto rosso). Con un badge dare SEMPRE `badgeDescription`
  (la voce viene letta «Automazioni, 2 in approvazione»). Già usata da `ArgusNavHost`.

### Top bar · `TopBars.kt`
- `ArgusLargeTopBar(title, actions)`: titolo grande (Automazioni, Log, Sistema).
- `ArgusDetailTopBar(title, onBack, backContentDescription, actions)`: freccia indietro 48 dp + titolo breve.
- `ArgusChatTopBar(title, subtitle?, leading?, actions)`.
- Non aggiungono gli inset delle barre di sistema: li mette lo `Scaffold` dell'host.

### Pulsanti e interruttore · `Buttons.kt`
- `ArgusButton(text, onClick, style: ButtonStyle = Filled, icon?, enabled = true, disabledReason?, fillWidth = false, minHeight = 48)`.
  `ButtonStyle`: `Filled` (UNO solo per stato), `Tonal`, `Outlined`, `Text`, `Destructive` (contorno e testo rossi; apre
  SEMPRE una conferma). Disabilitato: se c'è `disabledReason` il motivo è scritto sotto il pulsante, visibile e letto da
  TalkBack come stato (mai un tooltip). Nei footer il motivo sta sopra i pulsanti: `ArgusActionFooter`.
- `ReasonText(text)`: il testo del motivo.
- `ArgusIconButton(icon, contentDescription, onClick, enabled, tint)`: 48 x 48, descrizione obbligatoria.
- `ArgusSwitch(checked, onCheckedChange, contentDescription, enabled)`: 52 x 32, solo armata/disattivata; la descrizione dice
  l'azione («Disattiva X» / «Riattiva X»).

### Campi · `Fields.kt`
- `ArgusTextField(value, onValueChange, label, placeholder?, supportingText?, errorText?, monospace = false, enabled, readOnly,
  singleLine, labelAccessory?, trailing?, visualTransformation, keyboardOptions, keyboardActions)`. Etichetta sopra, campo da
  52 dp, linea `primary` in focus, `error` in errore, fondo `card2` con lucchetto se disabilitato/di sola lettura.
  Una chiave segreta non rientra mai nel campo: il chiamante lo tiene vuoto.

### Blocco di codice · `CodeBlock.kt`
- `CodeBlock(text: String, label?, labelIcon?, labelColor, borderColor?, style = ArgusCodeSmall)` e la versione
  `CodeBlock(parts: List<CodePart>, …)` con `CodePart(text, tone: CodeTone)`; `CodeTone`: `Plain`, `External` (ambra: valore
  esterno non attendibile), `Local`. Monospace, INTEGRALE, va a capo anche dentro i token lunghi, nessuna ellissi, nessun
  scroll nascosto (invariante 6). `ShellCommandBlock(cmd)` (in `RuleCard.kt`) è questo blocco con l'etichetta dei privilegi.

### Banner e tessere di salute · `Banners.kt`, `HealthTile.kt`
- `HealthBanner(text, tone: BannerTone, icon, onClick?, onClickLabel?)`: tutto il banner è un pulsante ≥ 56 dp.
  `BannerTone`: `Warning` ambra (degrada), `Blocking` rosso (blocca la chat). `EngineBannerBar(banner, onClick)` lo mappa dai
  testi invariati di `strings.xml`. `WarningBanner(w: UiWarning)` per gli avvisi di validazione.
- `HealthTile(icon, title, subtitle, level: HealthTileLevel, onClick?, actionLabel?, okContentDescription?)`: riga di Sistema;
  `Warn` ambra con «Correggi», `Ok` con spunta, `Neutral` grigia (con pulsante opt-in «Attiva»).
- `LatencyIndicator`, `EmptyState` restano come erano (nuovi colori dal tema).

### Riepilogo (hero) · `SummaryHero.kt`
- `SummaryHero(counters: List<HeroCounter>, tiles: List<HeroTile>)`. `HeroCounter(value, label, tone: HeroTone,
  contentDescription, onClick?)`; `HeroTile(icon, label, value, detail?, valueTone, contentDescription, onClick?)`;
  `HeroTone`: `Primary`, `Attention`, `NeedsReview`, `Error`, `Neutral`. Solo componente: i dati li calcola il ViewModel.

### Segmenti e filtri · `SegmentedFilter.kt`
- `ArgusSegmentedFilter(options: List<T>, selected, onSelect, label: (T) -> String, groupDescription, segmentHeight = 40)`:
  scelta esclusiva che filtra una lista; scorre in orizzontale se non entra. Movimento: molla 0,9 · 1400, istantaneo con
  «Rimuovi animazioni».
- `ArgusChoiceChip(text, selected, onClick, role = RadioButton, enabled)`: scelta in un modulo (provider, ragionamento).

### Foglio e footer · `BottomSheet.kt`, `ActionFooter.kt`
- `ArgusBottomSheet(onDismiss, title, sheetState, content: ColumnScope)`: scrim, maniglia, angoli 28, contenuto scorrevole.
  `ArgusActionRow { }` per «Annulla» (testo) + conferma (pieno), allineati a destra.
- `ArgusActionFooter(disabledReason?) { ColumnScope }`: footer fisso su `nav`, filetto in alto, motivo SOPRA i pulsanti.

## 3. Regole

1. Un solo pulsante pieno per stato di una schermata. Elimina, Esegui ora e Revoca consenso aprono una conferma; le azioni
   distruttive sono `ButtonStyle.Destructive`.
2. Un'azione disabilitata dice perché, in testo visibile (`disabledReason` o il motivo del footer). «Arma» è l'unico pulsante
   pieno del Dettaglio in approvazione e con un ERROR è disabilitato col motivo (invarianti 1-2).
3. Un comando shell è sempre un `CodeBlock`/`ShellCommandBlock`: integrale, mai troncato (invariante 6).
4. Una regola generativa porta sempre `GenerativeTag` + `CloudTag` (invariante 5).
5. Il colore non è mai l'unico segnale: icona e testo accompagnano ogni stato.
6. Un valore assente in un riepilogo/log si scrive «n/d», mai 0 (invariante 8).
7. Ogni icona con significato ha `contentDescription`; le decorative `null`. Target ≥ 48 dp.
8. Le liste sono gruppi di card (`ArgusCardGroup` o `groupShape`), non card separate da spazio.
9. Il testo secondario è `onSurfaceVariant` (mai `outline`, mai trasparenza); le righe attenuate usano ancora
   `ATTENUATED_ROW_ALPHA`.
10. Anteprime: `@ArgusThemePreviews` per ogni stato, nei due temi. `ArgusPreviewSurface` dà tema e fondo `page`.

## 4. Cosa resta alla fase 2

Le schermate non sono ridisegnate: leggono i nuovi token (via `MaterialTheme.colorScheme`, `ArgusTheme.semantic`) e il
layout è quello di prima. Ogni worker sostituisce gli stili locali con i componenti sopra. Già adottati in fase 1:
`ArgusNavigationBar` (nav host), `HealthBanner` (via `EngineBannerBar`), `CodeBlock` (via `ShellCommandBlock`, ora a capo
invece che a scorrimento orizzontale), i chip di `Badges.kt`, `JetBrains Mono` per tutto il monospace.

## 5. Fase 2 · blocco approvazione e dettaglio (0.7.0)

Componenti aggiunti per le tavole «Approvazione · generativa», «Approvazione · P4 con shell» e «Dettaglio · armata»
(ognuno con anteprima nei due temi, prefisso stringhe `rda_ap_`):

- `ApprovalRiskPanel(title, warnings)` (`ApprovalRisk.kt`): riepilogo rischi sopra la regola, con i conteggi errori/avvisi,
  la nota privacy e, per un ERROR, l'etichetta «Errore» (il colore non è l'unico segnale). Mai collassato.
- `ApprovalRuleTimeline(rule, geofenceLabel, estimate, appInfo, onChangeApp)` (`ApprovalRule.kt`): QUANDO / SOLO SE /
  VARIABILI / ALLORA come linea del tempo. Il contenitore del trigger ha la forma del tipo; il comando shell è un `CodeBlock`
  integrale; l'albero del programma è tutto espanso (nessun nodo si chiude). `AppRefLine` mostra il nome dell'app, il package e
  «Cambia app»; l'app non installata ha il suo chip ambra con icona e testo.
- `ModelDescriptionNote(text)` (`ApprovalNotes.kt`): la prosa del modello come commento subordinato (tratteggiato, attenuato,
  etichettato), sempre dopo la regola.
- `ApprovalAppPickerSheet(choices, currentPackage, armedRule, onPick, onDismiss)` (`ApprovalAppPicker.kt`): elenco delle app
  installate (nome + icona letta in modo pigro) con ricerca. Sostituisce il package nella bozza.

Regole del blocco: «Arma» è l'unico pulsante pieno; con un ERROR è disabilitato e il motivo sta sopra i pulsanti
(`ArgusActionFooter`). Nel dettaglio salvato «Disattiva» è tonale, Elimina è distruttivo (conferma) e «Esegui ora» resta visibile,
disabilitato col suo motivo. Una regola armata non si modifica mai da qui: «Cambia app» ne fa una bozza di modifica da riapprovare.
