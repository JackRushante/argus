# #52 — Web tool per invoke_llm (server-side, single-turn)

Data: 2026-07-17 · Autore: Claude (negozio) · Stato: in implementazione

## Tesi (corretta dopo pushback di Lorenzo)
Il web search dei provider moderni è **server-side**: si aggiunge un tool/flag alla richiesta e il
provider fa il loop internamente, restituendo il testo finale in **UNA** chiamata. → **Nessun**
refactor multi-turno, `ActResult`=solo testo resta valido, l'architettura single-turn dei transport
va bene così com'è. Il "loop client-side" che temevo valeva solo per un tool eseguito da Argus.

## Meccanismo per provider (verificato sui doc, 2026-07)
| Provider | Attivazione web (server-side) | Note |
|---|---|---|
| Hermes (bridge) | `hermes -z -t web` in `run_gpt` | già in prod su guida-agent (brave-free+ddgs) |
| Anthropic | tool `{"type":"web_search_20250305","name":"web_search"}` in `tools` | loop interno lato Anthropic |
| OpenRouter | modello `…:online` **o** `plugins:[{"id":"web"}]` | via OpenAICompatTransport |
| OpenAI | Chat Completions: modello `gpt-4o(-mini)-search-preview` + `web_search_options` **o** Responses API `web_search` | wrinkle: serve modello search o Responses |
| Gemini | tool grounding `google_search` | via shim OpenAI-compat: verificare supporto |
| Custom OpenAI-compat | best-effort (config `webSearch` del provider) | può non supportarlo → resta unavailable |

## Innesti (5 pezzi, nessun refactor)
1. **engine-core**
   - `GenerativeContract`: nuovo `TOOL_WEB_SEARCH = "web.search"`; introdurre `WEB_TOOLS`/insieme
     dei tool di contesto ammessi oltre al reply. `ALLOWED_TOOLS` resta il profilo reply P1.
   - `DraftValidator.validateInvokeLlm`/`validateGenerativeReplyContract`: rilassare l'uguaglianza
     esatta `allowedTools == ALLOWED_TOOLS` → ammettere `[whatsapp_reply]` **e opzionalmente**
     `web.search` (mai shell/automation.*; `web.search` deve essere in `knownTools`).
   - `GenerativeActionLane.validContract`: stessa uguaglianza esatta da rilassare (v1 e v2).
2. **capability probe** (`AndroidCapabilityProbe`)
   - Spostare `web.search` da `PHASE_UNAVAILABLE_TOOLS` a `available` **quando** il provider
     configurato supporta il web (Hermes sempre; OpenAI/Anthropic/OpenRouter/Gemini sì; custom da
     config). Resta in `KNOWN_TOOLS`.
3. **compile prompt** (`AgentMessageSupport` + `bridge.py` schema)
   - Dichiarare che `invoke_llm.allowedTools` può includere `web.search` quando il goal richiede
     dati aggiornati/online. Regola: usarlo solo se serve un dato live (cambio, meteo, prezzo, news).
4. **transport** (per-provider, single-turn)
   - `ProviderQuirks`: nuovo campo `webSearch: WebSearchMechanism` (NONE | ANTHROPIC_TOOL |
     OPENROUTER_ONLINE | OPENAI_SEARCH | GEMINI_GROUNDING | CUSTOM). Mai `if(provider==)`.
   - `OpenAICompatTransport.generate(...)` e `AnthropicMessagesTransport.generate(...)`: thread
     `webRequested = TOOL_WEB_SEARCH in allowedTools`. Se richiesto e il provider lo supporta:
     applica il meccanismo web alla richiesta **e** `tool_choice=auto` (il reply forzato impedirebbe
     la ricerca). `replyText()` già fa `fromTool ?: content`, quindi il testo finale (post-web) passa.
   - `CliBridgeTransport.act`: ammettere `web.search` in `allowedTools` (oggi rifiuta ≠ reply).
5. **bridge Hermes** (`~/argus-bridge/bridge.py`)
   - `run_gpt(prompt, tools=...)` parametrico: `-t web,clarify` quando l'act richiede web.
   - `/act`: rilassare `allowed_tools == [whatsapp_reply]` per ammettere `web.search`;
     `build_act_prompt` non deve dire "non eseguire tool" quando il web è concesso.
   - `/compile` schema (`DRAFT_SCHEMA_TEXT` + `validate_action`): ammettere `web.search` in allowedTools.

## Fasi di esecuzione
- **F1 core** (engine-core + probe + compile prompt) — TDD inline. Sblocca la dichiarazione web.
- **F2 Hermes** (bridge `-t web` + CliBridgeTransport) — end-to-end col provider di default, più veloce.
- **F3 direct providers** (OpenRouter→Anthropic→OpenAI→Gemini) — per-provider, quirks, smoke live.

## Test live (chiavi Lorenzo, alcune senza credito)
`LiveApiSmokeTest` env-gated: una risposta "no credit" (402/403) valida comunque il path di chiamata
(request costruita giusta). Basta che UN provider risponda con contenuto web per confermare.

## Invarianti da non rompere
- Single-turn: nessun `ActResult` con tool-call, nessun loop client-side.
- La consegna generativa resta la reply esistente (sink notifica = P4, deciso da Lorenzo 2026-07-17).
- `web.search` è di sola lettura: MAI shell/automation.* (DraftValidator `FORBIDDEN_IN_INVOKE_LLM`).
- Redazione chiavi invariata; nessun body remoto negli errori.
