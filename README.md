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