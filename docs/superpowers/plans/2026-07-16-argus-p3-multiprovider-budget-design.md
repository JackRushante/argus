<!-- Prodotto da orchestrazione subagent (workflow argus-multiprovider-design): 4 ricercatori Opus 4.8 xhigh (codebase Brain/transport + budget, API OpenAI/Anthropic/Gemini/z.ai) + sintesi Fable 5 xhigh. Da rivedere con Lorenzo prima dell'implementazione. -->

# Piano implementativo P3 — #48 Onboarding multi-provider + #49 Controllo uso/costi/limiti

Allineato al decision record P3 (§3.3 loop provider-agnostico via transport comune, nessun provider hardcoded nel dominio, token per-installazione mai nell'APK; §7.1 Brain configurabile Hermes O provider diretto). Ancorato ai findings su codebase (Finding 1–2) e contratti API 2026 (Finding 3–4).

---

## 1. Architettura proposta

### 1.1 Il contratto transport comune: `AgentTransport` (in `brain-android`)

Oggi l'unica astrazione di dominio è `Brain` (`engine-core/src/main/kotlin/dev/argus/engine/brain/Brain.kt:25`) e l'unico transport concreto è `CliBridgeTransport` (`brain-android/src/main/kotlin/dev/argus/brain/CliBridgeTransport.kt:220`), istanziato hardcoded in `ConfiguredBridgeBrain.currentTransport()` (`ConfiguredBridgeBrain.kt:86-95`). Il contratto `AgentTurnTransport` del decision record §3.1 **non esiste in codice** (grep negativo confermato dal Finding 1).

Si introduce in `brain-android` (non in `engine-core`: parla HTTP, non è dominio puro):

```
interface AgentTransport {
    val providerId: ProviderId
    suspend fun compile(request: CompileRequest): CompileOutcome
    suspend fun act(request: ActRequest): ActOutcome            // v1
    suspend fun actV2(request: ActV2Request): ActOutcome
    suspend fun health(): TransportHealth
}
```

- `ActOutcome` trasporta `text | error` **più** `usage: TurnUsage?` (`inputTokens`, `outputTokens`, `cachedTokens?`, `model`) — è la base della feature #49.
- `ProviderId` = value class/enum: `HERMES`, `OPENAI`, `ANTHROPIC`, `GEMINI`, `ZAI` (+ `CUSTOM_OPENAI_COMPAT`, vedi §7).
- `CliBridgeTransport` **implementa** `AgentTransport` (riuso, non riscrittura): i metodi `compile` (`:261`), `act` (`:292`), `actV2` (`:352`), `health` (`:415`) matchano già la forma; serve solo dichiarare l'interfaccia e mappare gli envelope.
- Il fallback `/chat` resta escluso come da docstring `CliBridgeTransport.kt:214-219`.

### 1.2 Adapter Brain: da `HermesBrain` a `TransportBackedBrain`

`HermesBrain` (`brain-android/.../HermesBrain.kt:19`) oggi è tipizzato sul concreto `CliBridgeTransport`. Si generalizza in `TransportBackedBrain(transport: AgentTransport)` riusando la mappatura `BridgeException → metaError` (`HermesBrain.kt:25-31`), estesa a una gerarchia `TransportException(kind: PROTOCOL|AUTH|NETWORK|RATE_LIMIT|BUDGET)` comune a tutti i transport. L'invariante XOR di `ActResult` (`Brain.kt:14`) resta; si aggiunge un campo **opzionale** `usage: TurnUsage?` a `ActResult` (`Brain.kt:9`) senza toccare l'XOR text/metaError.

### 1.3 Factory e seam di sostituzione

- **`TransportFactory`** (fun interface in `brain-android`): `create(config: ProviderConfig): AgentTransport`. Impl `DefaultTransportFactory` con `when(config.providerId)` → `CliBridgeTransport` | `OpenAICompatTransport` | `AnthropicMessagesTransport`. Questo `when` è l'**unico** punto dell'app che conosce i provider concreti: il dominio (engine-core, lane, Engine) continua a vedere solo `Brain`.
- **Seam**: `ConfiguredBridgeBrain.currentTransport()` (`ConfiguredBridgeBrain.kt:86-95`) sostituisce il `CliBridgeTransport(...)` cablato (`:90-93`) con `factory.create(store.selectedProviderConfig())`. La cache per-URL (`:34, :88`) diventa keyed su `(providerId, baseUrl, model)`.
- **`ProviderCatalog`** (statico, in APK — non contiene segreti, solo metadati): per ogni `ProviderId` → display name, base URL di default, stile auth (`BEARER` | `X_API_KEY`), lista modelli di default, quirks (vedi §2), tabella prezzi (vedi §5). È l'anti-hardcoding: i transport sono parametrizzati dal catalog, non da costanti sparse.

### 1.4 DI e readiness (quasi zero-touch)

- `ArgusModule.kt:353-369`: `bridgeConfiguration()` diventa `providerConfigStore()`; `configuredBrain()` (`:358-366`) riceve la factory; **`brain()` (`:368-369`) resta invariato** — tutto il grafo a valle (es. `androidGenerativeLane` `:421-439`) dipende solo da `Brain`.
- Il gate generativo è **già provider-agnostico**: `AndroidCapabilityProbe.kt:219-220` richiede solo `bridgeConfigured && privacyAccepted && batteryOptimizationExempt`, e `AndroidGenerativeRuntimeReadiness` (`GenerativeRuntimeReadiness.kt:20`) legge `bearerToken() != null`. Basta che `configured()` rifletta il provider **selezionato**. Rinominare `bridgeConfigured` → `transportConfigured` è cosmetico e opzionale.

---

## 2. Gemini / z.ai: riuso di `OpenAICompatTransport` — sì, con quirks

Verdetto dal Finding 4: **un solo `OpenAICompatTransport` copre OpenAI, Gemini e z.ai**; **Anthropic richiede un adapter dedicato**.

| Provider | Transport | Base URL | Auth |
|---|---|---|---|
| OpenAI | `OpenAICompatTransport` | `https://api.openai.com/v1` | `Authorization: Bearer` |
| Gemini | `OpenAICompatTransport` | `https://generativelanguage.googleapis.com/v1beta/openai/` (shim ufficiale Google) | `Authorization: Bearer` |
| z.ai | `OpenAICompatTransport` | `https://api.z.ai/api/paas/v4` | `Authorization: Bearer` |
| Anthropic | `AnthropicMessagesTransport` | `https://api.anthropic.com` | `x-api-key` + header `anthropic-version: 2023-06-01` obbligatorio |

**Quirks flags nel `ProviderCatalog`** (mai `if (provider == ...)` dentro il transport):
- `forceToolChoiceAuto` — z.ai supporta solo `tool_choice: "auto"` (Finding 4 §2A).
- `outputCapParam` — OpenAI gpt-5.x **rigetta** `max_tokens`, vuole `max_completion_tokens`; Gemini shim e z.ai usano `max_tokens` standard (Finding 3 §1).
- `extraBodyPassthrough` — hook per `extra_body.google.*` di Gemini (thinking/safety), non usato in v1.

**Strategia tool-calling comune** (dal seam-table del Finding 3 §4):
1. Normalizzare al confine transport: args OpenAI/Gemini/z.ai arrivano come **stringa JSON** (`function.arguments`), Anthropic come **oggetto già parsato** (`input`) → il modello di dominio porta sempre un oggetto parsato + un `id` opaco (che il transport rimappa in `tool_call_id` / `tool_use_id` al submit).
2. Echo-back: OpenAI richiede il messaggio assistant con `tool_calls` verbatim + un messaggio `role:"tool"` per call; Anthropic richiede l'array `content` verbatim (incluso il blocco `tool_use`) + user message con `tool_result`. Entrambi gestiti internamente al transport.
3. Usare **Chat Completions, non Responses API**: modello mentale stateless identico ad Anthropic Messages (Finding 3 §4, punto 4), ed è la superficie che Gemini/z.ai mimano.
4. **`compile()` su provider diretti**: Hermes fa la compilazione server-side; sui provider diretti va fatta client-side. Strategia: prompt template nell'app + output strutturato. Meccanismo comune = un tool `emit_draft` con schema del draft; su z.ai (no `tool_choice` forzato) fallback a `response_format: json_object` + istruzione nel prompt. Questo è il pezzo a rischio più alto (vedi §7).
5. **Streaming fuori scope v1**: il contratto `Brain` è one-shot; il chip roadmap "Streaming (OpenAI-compat)" (`SettingsScreen.kt:414-423`) resta.

Un adapter Gemini **nativo** (`generateContent`, `x-goog-api-key`) si aggiunge solo se in futuro servono grounding/safety/thinking nativi o Vertex OAuth (Finding 4 §3).

---

## 3. Storage config e sicurezza chiavi

Generalizzare `BridgeConfigurationStore` (`brain-android/.../BridgeConfigurationStore.kt:18`) in **`ProviderConfigStore`**:

- **Modello**: `selectedProviderId` + mappa `providerId → ProviderConfig(baseUrl, model, hasKey)`. La chiave API non transita mai nel modello UI: solo `AuthState` (già previsto in `UiContracts.kt:215`).
- **Persistenza**: stesse `SharedPreferences("argus_bridge_private")`, chiavi namespaced `provider.<id>.baseUrl|model|key`. Le API key riusano **identico** il codec esistente: `AesGcmTokenCodec` (`BridgeConfigurationStore.kt:132`) + chiave AES-GCM non esportabile in Android Keystore (`encryptionKey()` `:94-110`, lettura suspend come `bearerToken()` `:85-92`).
- **Migrazione**: al primo avvio, `KEY_BASE_URL` + token esistenti → `provider.hermes.*`, `selectedProviderId = HERMES`. Nessuna perdita per gli utenti attuali.
- **Invarianti da preservare**: HTTPS-only + normalizzazione (`:122-127`), no credenziali in URL (`CliBridgeTransport.kt:254-258`), bound di validazione key (riadattare `:129-130` per-provider: prefissi `sk-` / `sk-ant-` / `AIza` come hint soft, mai bloccanti).
- **Mai nell'APK / mai nei log**: il `ProviderCatalog` in APK contiene solo metadati; le chiavi sono inserite dall'utente in onboarding (per-installazione, §7.1). Regola di redazione nei transport: nessun header `Authorization`/`x-api-key` in eccezioni, log o `AuditEvent.detail`; test dedicato che verifica che `toString()` delle config e i messaggi di `TransportException` non contengano mai la chiave.

---

## 4. Onboarding UX e `TransportSection`

### Onboarding (release Play/GitLab/F-Droid — non tutti hanno un Hermes)

Nuovo step "Scegli il cervello", wizard a 4 passi:
1. **Provider**: lista dal `ProviderCatalog` — "Hermes (self-hosted)" + OpenAI, Anthropic, Google Gemini, z.ai. Copy chiara: i provider diretti richiedono una chiave API propria (BYOK), nessun account Argus.
2. **Endpoint**: precompilato dal catalog; editabile solo per Hermes (oggi default `https://hermes.tail04462d.ts.net`, `BridgeConfigurationStore.kt:113`) e per l'eventuale custom.
3. **Chiave + modello**: campo chiave (masked, paste-friendly) + dropdown modelli di default con campo libero.
4. **Test connessione**: per Hermes riusa `BridgeHealth` (`CliBridgeTransport.kt:178`, check schema/model/sha `:430-436`); per i diretti una chiamata minima (1 token, `max_*_tokens = 1`) che valida chiave+modello e mostra l'errore normalizzato (401 → "chiave non valida", 404 → "modello inesistente", 429 → "rate limit").

Skip consentito (come oggi): la lane generativa resta gated da `GenerativeReadiness` finché non configurato.

### `TransportSection` in Settings

- `TransportUi` (`UiContracts.kt:205`): il caso `OpenAICompat` (`:213`) — oggi puramente cosmetico, mai emesso — evolve in `TransportUi.DirectProvider(providerId, providerLabel, baseUrl, model, authState)`; `CliBridge` (`:206`) resta.
- `SettingsViewModel.kt:153-158`: smette di emettere fisso `TransportUi.CliBridge` — emette il ramo in base a `selectedProviderId` (fixture `:341` aggiornate).
- `SettingsCallbacks` (`UiContracts.kt:221-239`): si aggiungono `onSelectProvider(ProviderId)` e `onSaveProviderConfig(providerId, baseUrl, model, key?)` (pattern `key = null` conserva l'esistente, come `saveConfiguration` `BridgeConfigurationStore.kt:21`); `onTestConnection` (`:222`) diventa provider-aware.
- `TransportSection` (`SettingsScreen.kt:342`): il ramo `OpenAICompat` (`:404-411`) diventa azionabile — selettore provider, editor chiave/modello, "Test connessione" — e `InArrivoChip()` (`:430`) sparisce. Il cast `state.transport as? TransportUi.CliBridge` dell'editor bridge (`SettingsScreen.kt:121`) va generalizzato.

---

## 5. Budget / uso / costi (Feature #49)

Oggi è solo placeholder: `BudgetUi(maxCallsPerHour, usedThisHourLabel)` stringly-typed (`UiContracts.kt:219`), progress-bar che parsa una label (`SettingsScreen.kt:579`), `maxCallsPerHour` hardcoded a 0 (`SettingsViewModel.kt:173-178, :357-362`), `onBudgetChange` no-op (`:282-287`). Nessun contatore, nessun usage nel wire (`ActResponseEnvelope` `CliBridgeTransport.kt:167-176`).

### 5.1 Modello dati (Room, modulo `data`)

Tabella append-only **`usage_events`**: `id, timestampMs, providerId, model, kind (COMPILE|ACT|ACT_V2), outcome (OK|ERROR|BLOCKED_BUDGET), tokensIn?, tokensOut?, costMicros?, pricingVersion?`. Indice su `timestampMs`. Query aggregate a finestra scorrevole (ultima ora / giorno corrente, `GROUP BY providerId`). DAO `UsageDao` con insert + aggregati; upsert atomico sul pattern di `AutomationDao.acquireCooldown` (`AutomationDao.kt:167-220`); migrazione Room con test (pattern `data/.../MigrationTest.kt`). Retention: purge > 35 giorni. (Counters bucketizzati = ottimizzazione futura, non ora.)

### 5.2 Dove si intercetta: decorator `MeteredBrain`

Come da Finding 2 ("punto di innesto minimo"): un **decorator `Brain`** registrato in `ArgusModule.brain()` (`:368`) che avvolge `ConfiguredBridgeBrain` — unico choke-point che vede `compile/act/actV2` (copre anche `compile`, cosa che il gate in `GenerativeActionLane.kt:118-130` non farebbe):
1. **Pre-call**: interroga `BudgetPolicy.check(providerId)`. `HARD_EXCEEDED` → non chiama il transport, ritorna `ActResult(metaError="budget_exceeded")`, registra evento `BLOCKED_BUDGET`. `SOFT_EXCEEDED` → passa, ma emette warning one-shot per finestra (notifica).
2. **Post-call**: scrive `usage_events` con `ActResult.usage` (se presente) e `costMicros = CostEstimator.estimate(model, usage)`.

Enforcement/osservabilità nel motore: nuovo `AuditKind.SUPPRESSED_BUDGET` (`AuditSink.kt:5-13`) e `ExecutionStatus.SUPPRESSED_BUDGET` accanto a `SUPPRESSED_COOLDOWN` (`ExecutionJournal.kt:15`), così il blocco appare nel journal come oggi il cooldown (`Engine.kt:163-182`). Il cooldown per-regola 60s (`Engine.kt:24, :302-305`, `claimFire` `:140`) **resta invariato**: è un freno per-regola, il budget è l'aggregato cross-regola.

### 5.3 Da dove arrivano i token

- **Provider diretti**: usage nativo nella risposta — `usage.prompt_tokens/completion_tokens` (OpenAI/Gemini/z.ai, Finding 3 §1, Finding 4), `usage.input_tokens/output_tokens` + `cache_*` (Anthropic, Finding 3 §3). Il transport lo normalizza in `TurnUsage` → `ActResult.usage`. Disponibile da subito.
- **Hermes**: il wire non trasporta usage e `ignoreUnknownKeys=false` (`CliBridgeTransport.kt:247-251`) rigetterebbe campi extra → serve bump coordinato: `ACT_V2_SCHEMA_VERSION 2→3` (companion `:675-678`) con `usage{input_tokens, output_tokens}` nell'envelope, parsing in `parseActResponse` (`:548-579`), e modifica speculare lato Hermes. Finché Hermes non è aggiornato: si contano le **chiamate** (sempre possibile), token/costo = "n/d". Il negoziato via `/health` (`:430-436`) gestisce la transizione.

### 5.4 `CostEstimator`

Tabella prezzi per-model ($/1M token in/out) nel `ProviderCatalog`, con `pricingVersion` salvato nell'evento (i prezzi cambiano; lo storico resta coerente). Costo calcolato a write-time in micro-dollari. UI con disclaimer "stima indicativa". Modelli sconosciuti → costo null, mai crash.

### 5.5 Limiti e UI

- **`BudgetSettingsStore`** (persistente): `maxCallsPerHour`, `maxCallsPerDay`, `maxCostPerDayMicros`, `softThresholdPct` (default 80%). 0/null = illimitato (default attuale).
- **`BudgetUi` rifatta, tipizzata** (`UiContracts.kt:219`): `usedHour/limitHour`, `usedDay/limitDay`, `costTodayMicros/costLimitMicros`, `perProvider: List<ProviderUsageUi>`, `softWarningActive`. Eliminato il parsing `substringBefore(' ')` (`SettingsScreen.kt:579`).
- **`BudgetSection`** (`SettingsScreen.kt:565`): progress bar per ora/giorno/costo, breakdown per-provider, editor limiti. `onBudgetChange` (+ nuove callback per giorno/costo) persistono davvero su `BudgetSettingsStore` (oggi no-op a `SettingsViewModel.kt:282-287`).
- Hard block a runtime → riga nel journal esecuzioni con stato `SUPPRESSED_BUDGET` + notifica; soft → solo notifica.

---

## 6. Piano a sotto-slice TDD (1 slice = 1 commit)

Ordine con dipendenze; gate host = comando che deve essere verde prima del commit. Test-first sempre (skill `superpowers:test-driven-development`).

| # | Slice | Dipende da | Gate host | Chiavi reali? |
|---|---|---|---|---|
| S1 | `AgentTransport` + `TurnUsage` + `TransportException`; `CliBridgeTransport : AgentTransport`; `HermesBrain` → `TransportBackedBrain` (refactor puro, comportamento invariato) | — | `gradlew :brain-android:test` (suite esistente verde, zero regressioni) | No |
| S2 | `ProviderId` + `ProviderCatalog` (metadati, quirks, prezzi) | — | `gradlew :brain-android:test` | No |
| S3 | `ProviderConfigStore` con migrazione da `BridgeConfigurationStore` + test redazione segreti | S2 | `:brain-android:test` + test instrumented/Robolectric per Keystore/prefs | No |
| S4 | `TransportFactory` + seam `ConfiguredBridgeBrain` (cache `(providerId,url,model)`) + DI `ArgusModule` | S1,S3 | `gradlew :automation-android:test` (fake transport) | No |
| S5 | `OpenAICompatTransport`: actV2 + tool-loop + parsing usage, contro MockWebServer (fixture registrate OpenAI/Gemini/z.ai) | S1,S2 | `:brain-android:test` | No |
| S6 | `OpenAICompatTransport.compile` client-side (tool `emit_draft` / `response_format`) + quirks (`forceToolChoiceAuto`, `outputCapParam`) | S5 | `:brain-android:test` | No |
| S7 | `AnthropicMessagesTransport` (x-api-key, version header, `max_tokens` required, `tool_use`/`tool_result`, echo `content` verbatim) | S1,S2 | `:brain-android:test` | No |
| S8 | **Smoke live opt-in**: task Gradle/test tag `liveApi`, chiavi da env (mai in repo), 1 chiamata compile+actV2 per OpenAI/Anthropic/Gemini | S5–S7 | run manuale di Lorenzo | **Sì** (OpenAI, Anthropic, Google; z.ai se chiave disponibile) |
| S9 | UI Settings: `TransportUi.DirectProvider`, `SettingsViewModel` emette per provider selezionato, `TransportSection` azionabile, callback nuove, via `InArrivoChip` | S4 | `gradlew :ui:test :automation-android:test` | No |
| S10 | Onboarding wizard (provider→endpoint→chiave/modello→test connessione) | S9 | `:ui:test` + verifica manuale su device (skill `verify`) | Utile ma non richiesto (basta Hermes + 1 diretto) |
| S11 | Room `usage_events` + `UsageDao` + migrazione + retention | — (parallela a S5–S10) | `gradlew :data:test` incl. MigrationTest | No |
| S12 | `ActResult.usage` (`Brain.kt:9`, opzionale, XOR intatto) + propagazione dai transport diretti | S5,S7,S11 | `:brain-android:test` + `:engine-core:test` | No |
| S13 | `MeteredBrain` decorator + `CostEstimator` + `BudgetSettingsStore` + `BudgetPolicy` (hard/soft) + `AuditKind/ExecutionStatus.SUPPRESSED_BUDGET` | S11,S12 | `:engine-core:test :automation-android:test :data:test` | No |
| S14 | `BudgetUi` tipizzata + `BudgetSection` reale + persistenza limiti | S13 | `:ui:test :automation-android:test` | No |
| S15 | Wire usage Hermes: bump `ACT_V2_SCHEMA_VERSION` → 3 + parsing (`parseActResponse`) — **coordinata col repo Hermes**, deploy lockstep (strict decoding) | S12 + lavoro lato Hermes | `:brain-android:test` + smoke contro Hermes reale | No (serve Hermes aggiornato) |
| S16 | Verifica end-to-end release: onboarding da APK pulito con provider diretto, budget hard-block osservato nel journal; note distribuzione Play/GitLab/F-Droid | tutte | `gradlew test` completo + skill `verify` su device | **Sì** (percorso reale) |

Percorsi critici: S1→S4→S5/S6/S7→S8 (feature #48 core) e S11→S12→S13→S14 (feature #49); S9/S10 possono procedere in parallelo a S5–S7 dopo S4. S15 è l'unica slice con dipendenza esterna (Hermes) e può slittare senza bloccare il resto (token "n/d" su Hermes nel frattempo).

---

## 7. Rischi aperti e domande per Lorenzo

**Rischi**
1. **`compile()` client-side** (S6): la qualità della compilazione NL→draft sui provider diretti dipende da prompt template che oggi vivono solo lato Hermes. Rischio di divergenza di comportamento tra Hermes e diretti; serve una golden-suite di casi compile condivisa.
2. **Bump schema Hermes lockstep** (S15): `ignoreUnknownKeys=false` rende impossibile un rollout graduale del campo `usage` — client e server vanno aggiornati insieme, con `/health` come guard.
3. **Shim OpenAI di Gemini è beta** (Finding 4): alcuni parametri sono ignorati silenziosamente. Mitigazione: smoke live S8 + adapter nativo Gemini come piano B già mappato.
4. **Prezzi che invecchiano**: tabella statica nel catalog → costi sbagliati dopo cambi listino. Mitigato da `pricingVersion` + disclaimer, ma resta manutenzione manuale per release.
5. **z.ai non verificabile** senza chiave reale: shipperebbe testato solo contro fixture.
6. **F-Droid**: BYOK a runtime è ok per policy, ma la descrizione store deve chiarire che senza chiave/Hermes la lane generativa è disattiva.

**Domande per Lorenzo**
1. I prompt di `compile` lato Hermes sono estraibili/condivisibili con l'app (stesso template per i provider diretti), o li riscrivo da zero?
2. Vuoi un provider **"OpenAI-compatible custom"** (base URL libero: Ollama, LiteLLM, OpenRouter)? Costa quasi zero sopra S5 e copre il self-hosting non-Hermes.
3. Hai (o vuoi comprare) una chiave **z.ai** per lo smoke S8, o z.ai esce marcato "non verificato"?
4. Limiti budget: bastano **globali** (ora/giorno/costo) o li vuoi anche **per-provider**? Valuta in USD o EUR?
5. Il tetto costo: per **giorno** o anche per **mese**? (Il modello dati supporta entrambi, la UI no di default.)
6. Confermi **streaming fuori scope** per questa coppia di feature (resta chip roadmap)?
7. Ordine di rilascio: #48 completa prima di #49, o accetti release intermedia con multi-provider + solo contatore chiamate (senza costi) mentre S15 aspetta Hermes?
