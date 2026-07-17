# #59 — P4 sink-notifica per invoke_llm

Data: 2026-07-17 · Autore: Claude (negozio) · Stato: in implementazione

## Obiettivo
Un `invoke_llm` puo' consegnare il testo generato come **notifica locale** (non solo reply WhatsApp),
da un trigger QUALSIASI (time/immediate/…). Sblocca "mandami tra 2 min una notifica col cambio euro→usd"
(la richiesta reale di Lorenzo). NON e' il P4 completo (variabili/loop/taint = #36): e' solo il sink.

## Contratto (engine-core)
- `enum class GenerativeDeliverMode { WHATSAPP_REPLY, LOCAL_NOTIFICATION }`
- `Action.InvokeLlm` guadagna `deliver: GenerativeDeliverMode = WHATSAPP_REPLY` e `notificationTitle: String? = null`.
- `GenerativeContract.isNotificationToolset(tools)`: allowedTools per il sink notifica = nessun duplicato,
  SOLO `web.search` opzionale, **mai** `whatsapp_reply` (il sink e' la notifica, non un tool).

## Validazione (DraftValidator.validateInvokeLlm) — branch su deliver
- `WHATSAPP_REPLY` (invariato): contextSources con "notification", `isAllowedToolset` (reply+web opz),
  replyTargetSender=true, validazione reply-target sul trigger notification.
- `LOCAL_NOTIFICATION`:
  - `notificationTitle` non-blank, bounded (<=120), no control char;
  - `isNotificationToolset(allowedTools)` (niente whatsapp_reply);
  - `replyTargetSender` DEVE essere false;
  - contextSources vuota o subset di {state} (NON richiede "notification"): il testo si genera dal goal
    (+ web se concesso), non da una notifica in arrivo;
  - nessun vincolo di trigger (qualsiasi trigger va bene).

## Capability (CapabilityRequirements.InvokeLlm) — branch su deliver
- `WHATSAPP_REPLY`: invariato (`addAll(allowedTools)` → whatsapp_reply [+web.search]).
- `LOCAL_NOTIFICATION`: `ACTION_SHOW_NOTIFICATION` (serve il permesso notifiche) + `web.search` se in allowedTools.

## Lane (GenerativeActionLane.process) — branch su deliver
- Dopo aver ottenuto `text` dal brain:
  - `WHATSAPP_REPLY` (invariato): richiede `TriggerEvent.NotificationPosted`, `replies.send`.
  - `LOCAL_NOTIFICATION`: `notifier.show(title=notificationTitle, text=generatedText, context)` — nessun
    requisito NotificationPosted. Serve iniettare un `AutomationNotifier` nella lane (oggi ha solo `replies`).
- `validContract`: branch su deliver (i due profili sopra).

## Transport (nessun cambio di firma) — inferenza pulita
`useReplyTool = whatsapp_reply in allowedTools`. Il sink notifica NON ha whatsapp_reply → il transport usa
il path **plain** (testo diretto, `actSystemTextPlain`), gia' esistente per il web (OpenAI Responses/Gemini
nativo). Reply mode → path reply-tool invariato. Vale per OpenAICompat, Anthropic, e CliBridgeTransport.

## Bridge Hermes (/act) — path plain
`/act`: se `allowed_tools` non contiene whatsapp_reply → prompt plain (genera solo il testo, niente reply
framing), toolset `web,clarify` se web richiesto. Rilassare la validazione allowed_tools per il caso notifica
(oggi `_valid_generative_toolset` esige whatsapp_reply → aggiungere `_valid_notification_toolset`).

## Compile prompt (AgentMessageSupport regola 16 + bridge regola 14) — da "rifiuta" a "supportato"
"Per una NOTIFICA con contenuto generato/dal web da un trigger time/immediate: usa invoke_llm con
deliver=LOCAL_NOTIFICATION, notificationTitle sintetico, allowedTools=[] o ["web.search"], contextSources=[]
(o solo state), replyTargetSender=false. La reply WhatsApp resta per rispondere a un messaggio in arrivo."

## Probe
Pubblicare che il sink notifica generativo e' disponibile quando `generativeReady && notificationsGranted`
(la capability ACTION_SHOW_NOTIFICATION gia' esiste; verificare che invoke_llm resti pubblicato e che il
compilatore sappia del deliver via la regola).

## Ondate
- **O1 engine-core**: enum + InvokeLlm + isNotificationToolset + DraftValidator + CapabilityRequirements +
  RuleRenderMapper + serializzazione + exhaustive when + test.
- **O2 automation**: lane (notifier inject + branch) + probe + DI + test.
- **O3 transport**: inferenza useReplyTool nei transport (OpenAICompat/Anthropic/CliBridge) + test.
- **O4 compile**: AgentMessageSupport regola 16 + bridge regola 14 + bridge /act plain + schema + deploy.

## Invarianti
- Single-turn. Nessun taint/variabile (quello e' #36).
- Il testo generato per la notifica e' comunque un dato: il titolo e' LETTERALE (dal fingerprint approvato,
  non dal contenuto trigger). D2 invariato.
