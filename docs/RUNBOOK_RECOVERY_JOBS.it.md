# Recovery Jobs — Guida operativa

Il servizio recupera gli eventi di pagamento mancanti in ADX, ripubblicandoli su Event Hub.

L’intervallo si riferisce alla **data dei pagamenti** e comprende entrambe le date.

## 1. Verificare una giornata

Invocare **POST `/recovery/jobs`** con il body:

```json
{
  "dateFrom": "2026-07-27",
  "dateTo": "2026-07-27",
  "dryRun": true
}
```

`dryRun: true` verifica senza inviare eventi; `false` avvia il recupero reale. Per più giorni modificare `dateTo`.

La risposta `202 Accepted` restituisce il `jobId`: indica che la richiesta è stata accettata. Ogni POST crea un nuovo job.

## 2. Verificare l’avanzamento

| API | Cosa controllare |
|---|---|
| **GET `/recovery/jobs/{jobId}`** | `status` e `nextDate`, cioè la giornata da elaborare. A completamento, `nextDate` supera `dateTo`. |
| **GET `/recovery/jobs/{jobId}/discovery/{day}`** | Esito della verifica per una giornata dell’intervallo, ad esempio `2026-07-27`. |

La discovery restituisce i candidati esaminati (`candidateCount`), assenti (`absentCandidateCount`), presenti (`presentCandidateCount`) e non risolti (`unresolvedCandidateCount`). `pageCount` e `lastFlowId` permettono di monitorare l’avanzamento.

I conteggi riguardano le pubblicazioni dei flussi per giornata, non il numero di pagamenti.

## 3. Recuperare i dati

Invocare nuovamente **POST `/recovery/jobs`**, con lo stesso intervallo e `dryRun: false`:

```json
{
  "dateFrom": "2026-07-27",
  "dateTo": "2026-07-27",
  "dryRun": false
}
```

Monitorare il **nuovo `jobId`** con le API precedenti. Il completamento, in questo caso, prevede anche l’ingestione di ADX (se necessario).


## 4. Sospendere e riprendere

| API | Comportamento |
|---|---|
| **POST `/recovery/jobs/{jobId}/pause`** | Richiede la pausa; un job attivo passa da `PAUSING` a `PAUSED` quando il worker recepisce la richiesta. |
| **POST `/recovery/jobs/{jobId}/resume`** | Rimette in coda un job `PAUSED`, conservando l’avanzamento. |

Le due API non richiedono body. I job negli stati `COMPLETED` o `FAILED` non ripartono tramite resume.

## Stati

### Job e discovery

| Stato | Significato / azione |
|---|---|
| `PENDING` | Job in attesa del worker. |
| `RUNNING` | Elaborazione in corso. Controllare discovery e log se l’avanzamento si arresta. |
| `PAUSING` / `PAUSED` | Pausa richiesta / raggiunta. Attendere `PAUSED` prima di riprendere. |
| `COMPLETED` | Elaborazione terminata. In dry-run controllare comunque gli unresolved; nel recupero verificare anche l’arrivo in ADX. |
| `FAILED` | Recupero bloccato: analizzare item e log prima di creare altri job. |
| `NOT_STARTED` | Discovery della giornata non ancora avviata. |
| `DISCOVERING` / `DISCOVERED` | Discovery in corso / terminata. |

### Item e invio

Gli item si consultano in `fdrrecoveryitems`, cercando il `jobId` e la giornata (es. PartitionKey eq 'jobId_2026-10-08' and Status eq 'UNRESOLVED').

| Stato | Significato / azione |
|---|---|
| `ABSENT` | Nessun evento rilevato in ADX per la pubblicazione e la giornata; candidato al recupero. |
| `PRESENT_UNVERIFIED` | Evento presente. Non c'è garanzia che sia completo di tutti i pagamenti, lo si da per assodato. |
| `UNRESOLVED` | Sorgente non determinabile o non utilizzabile: nessun invio per l’item. Leggere `UnresolvedReason` e `DiagnosticDetails`. |
| `PENDING` / `SENDING` | Invio da avviare / tentativo avviato. Se persiste, controllare log e stato del worker. |
| `SENT` | Event Hub ha confermato l’invio; attendere e verificare l’ingestione ADX. |
| `SKIPPED_PRESENT` | Al controllo prima dell’invio sono stati trovati eventi in ADX; invio saltato. |
| `UNCERTAIN` | Invio dall’esito incerto, anche parziale. Confrontare i pagamenti in ADX prima di qualsiasi nuovo recupero. |

## Errori e analisi

| Caso | Verifiche e azioni |
|---|---|
| `SIZE_LIMIT_EXCEEDED` | Nei dettagli verificare sorgente/blob, `Stage`, `LimitBytes` e `ObservedBytesAtLeast`. Valutare dimensione reale e memoria disponibile prima di alzare il limite. |
| Sorgente assente o identità incoerente (`NO_CANDIDATES`, `NO_MATCH`, differenze JSON) | Verificare disponibilità del blob, revisione, identificativo flusso, PSP e dominio rispetto a PostgreSQL. |
| `AMBIGUOUS_MATCH`, `CONFLICTING_ORIGINS`, `ORIGIN_UNDETERMINED` | Controllare le sorgenti candidate, i metadati `elaborate`, `serviceIdentifier`, `sessionId` e la corrispondenza dei pagamenti. |
| `METADATA_UNRESOLVED` / `INCONSISTENT_REPORT` | Leggere il dettaglio: metadati mancanti/non validi, identità discordante o numero di pagamenti incoerente. Correggere la causa sulla sorgente. |
| `UNCERTAIN` o `SENDING` persistente | Correlare item di esecuzione e log tramite `jobId`, giornata e `flowId`; controllare tentativo, batch/eventi confermati e pagamenti effettivamente presenti in ADX. |
| Job fermo o `FAILED` | Verificare log del worker, errori verso PostgreSQL/Storage/ADX/Event Hub, riavvii e consumo CPU/memoria. `updatedAt` da solo non misura l’avanzamento di ogni pagina. |
| HTTP `400` / `404` | Controllare body, formato date, intervallo, `jobId` e ambiente. La giornata richiesta deve appartenere al job. |
| HTTP `409` | Transizione non consentita, aggiornamento concorrente o formato job incompatibile. Rileggere lo stato e analizzare il messaggio. |
| HTTP `503` / `500` | Recupero reale disabilitato / errore del servizio: controllare configurazione e log. |

Un dry-run può terminare con item unresolved; un recupero reale può fallire dopo aver già inviato altri item. Dopo aver corretto la causa, una **nuova discovery** richiede un nuovo job: resume conserva gli item già rilevati. 