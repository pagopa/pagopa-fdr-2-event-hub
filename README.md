# pagoPA Functions pagopa-fdr-2-event-hub

The function is designed to ingest FDR data flows into the FdR QI system

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=pagopa_pagopa-fdr-2-event-hub&metric=alert_status)](https://sonarcloud.io/dashboard?id=pagopa_pagopa-fdr-2-event-hub)

---

## Api Documentation 📖

See the [OpenApi 3 here.](https://editor.swagger.io/?url=https://raw.githubusercontent.com/pagopa/pagopa-fdr-2-event-hub/refs/heads/main/openapi/openapi.json)

---

## Technology Stack

- Java 17
- Azure functions [4.0.0, 5.0.0)

## Run locally with Docker
`docker build -t pagopa-functions-fdr-2-event-hub .`

`docker run -p 8999:80 pagopa-functions-fdr-2-event-hub`

### Test
`curl http://localhost:8999/info`

## Run locally with Maven

`mvn clean package`

`mvn azure-functions:run`

### Test
`curl http://localhost:7071/info` 

---

## Recovery Jobs

Il servizio permette di individuare e recuperare gli eventi di pagamento
mancanti in ADX per un intervallo di date, attraverso job asincroni.

- **Verifica:** con `dryRun: true`, individua i dati mancanti senza inviare eventi.
- **Recupero:** con `dryRun: false`, ripubblica gli eventi mancanti su Event Hub.
- **Monitoraggio:** le API permettono di consultare avanzamento e discovery,
  sospendere e riprendere un job.

L’intervallo si riferisce alla data dei pagamenti e include entrambe le date.
Il recupero riguarda gli eventi di pagamento destinati a `IUV_RENDICONTATI`.

Per esempi API, significato degli stati e gestione degli errori, consultare
il [Runbook operativo in italiano](docs/RUNBOOK_RECOVERY_JOBS.it.md).