# ADR — versionare separatamente automazioni, fingerprint e bridge

**Stato:** accepted, 2026-07-16
**Contesto:** P3 introduce nuovi reader e, in seguito, variabili e turni agentici. Il JSON Kotlin
usa `encodeDefaults=true`; aggiungere anche un solo campo con default a un tipo eseguibile v1 può
cambiare il JSON canonico e quindi l'hash di regole già approvate.

## Decisione

Argus mantiene tre assi indipendenti:

1. `automation_schema_version`: decodifica e compatibilità delle automazioni persistite;
2. `fingerprint_material_version`: serializzazione canonica di ciò che l'utente ha approvato;
3. `bridge_schema_version`: envelope e payload degli endpoint host/Android.

Un incremento su un asse non implica incrementi sugli altri. Costanti, migrazioni e changelog
devono nominarlo per esteso; il generico `SCHEMA_VERSION` è ammesso soltanto dentro un componente
il cui confine sia inequivocabile.

## Regole di compatibilità

- Le estensioni P3 preferiscono nuovi sealed subtype. Non si aggiungono campi default ai subtype
  eseguibili v1 senza una nuova versione del materiale canonico.
- Il decoder persistente accetta soltanto versioni dichiarate compatibili. Una versione futura o
  non migrabile produce `NEEDS_REVIEW`, mai drop, arm silenzioso o crash.
- Una migrazione che cambia semantica o materiale approvato non conserva l'arm: richiede review e
  nuovo fingerprint. Una migrazione puramente rappresentazionale può conservarlo solo se un
  golden test prova l'identità del materiale canonico.
- I golden test fissano almeno una regola v1 per ogni subtype trigger/condition/action e il relativo
  fingerprint. Vanno eseguiti prima e dopo qualunque cambio serializer.
- Il bridge è strict per versione. Un client v1 non invia subtype v2 e il server non li retrofitta;
  niente fallback `/chat` o interpretazione permissiva di campi sconosciuti.
- Il sample/probe di un reader non entra nel fingerprint; famiglia, parametri, tipo, operatore,
  limiti e policy sì.

## Deploy e rollback

Host e Android possono essere aggiornati nello stesso rollout, ma conservano health/versione
espliciti e rollback separato. I confronti repo/host normalizzano CRLF/LF prima dell'hash. Un
mismatch di protocollo blocca compile/act con errore visibile senza invalidare automazioni locali
già armate.

## Conseguenze

Il costo è qualche costante e fixture in più. Il beneficio è che aggiungere capability non revoca
silenziosamente tutte le approvazioni, e che una modifica del bridge non viene confusa con una
migrazione dei dati sul telefono.
