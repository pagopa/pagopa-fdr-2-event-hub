# CSV flow recovery

Resolve flow IDs to FDR3 blobs and replay their payment events through the recovery API.
The supplied CSV is the authoritative recovery list; no manual blob inventory is needed.

## Requirements

- Python 3.11+ on Windows, Linux or macOS (`python3` where needed).
- Original file `FLUSSI_MANCANTI_27-30_sett.csv`, placed in `work/` without editing it.
  A downloaded copy named `FLUSSI_MANCANTI_27-30_sett(1).csv` must be renamed accordingly.
- PROD storage `pagopapweufdrsa`, container `fdr3-flows`: network access and
  `Storage Blob Data Reader` through Azure CLI, or an approved connection string.
- PROD APIM subscription key in `FDR_RECOVERY_APIM_KEY`, supplied through an approved
  secret mechanism. Never put the key in commands, CSV files, logs or Git.
- APIM base URL: `https://api.platform.pagopa.it/fdr-2-event-hub/api/v1`.
  This API exposes `/notify/fdr`, not `/notify/fdr/massive`.

Run the following commands from `scripts/fdr-recovery`. Keep `work/`, credentials,
CSV files and SQLite registries out of Git. Only an authorized operator runs PROD.

## 1. Install and discover blobs

```sh
python -m pip install -r requirements-discovery.txt
az login
python recover_fdr.py discover --csv "work/FLUSSI_MANCANTI_27-30_sett.csv" --environment prod --source-account pagopapweufdrsa --subscription b9fc9419-6097-45fe-9f74-ba0641c91912 --out-dir work/discovery-prod
```

The CSV contains 89,555 IDs with date prefixes from September 27 to October 2, 2026.
Discovery lists all pages for those prefixes, fetches names and metadata together,
and matches complete flow IDs. It downloads no blob contents and sends no API events.
Restart an interrupted scan with the same command: its checkpoint is preserved.

Outputs under `work/discovery-prod/prepared/`:

- `manifest.csv`: uniquely resolved, processable blobs.
- `issues.csv`: missing blobs, multiple PSPs/revisions, or invalid metadata.
- `preparation.json`: input hashes and counts.

Review issues before sending. No revision is selected automatically; only blobs with
`elaborate=true`, `sessionId`, `insertedTimestamp` and `serviceIdentifier` are accepted.
For approved connection-string authentication, set
`FDR_RECOVERY_STORAGE_CONNECTION_STRING` and replace `--subscription ...` with
`--storage-auth connection-string-env`. This does not change Azure permissions.

## 2. Simulate and send one flow through APIM

```sh
python recover_fdr.py run --csv work/discovery-prod/prepared/manifest.csv --base-url https://api.platform.pagopa.it/fdr-2-event-hub/api/v1 --recovery-path /notify/fdr --apim-key-env FDR_RECOVERY_APIM_KEY --environment prod --source-account pagopapweufdrsa --destination-id prod-apim-fdr-2-event-hub --ledger work/prod.sqlite --limit 1
```

Without `--execute`, the command validates the manifest and prints a preview without
network calls or registry changes. Add `--execute` to the same command to send one
flow. The client checks `/info` and includes the APIM key on both GET and POST.
If the workstation requires its configured HTTP proxy, add `--use-system-proxy`.

Each POST targets:

```text
https://api.platform.pagopa.it/fdr-2-event-hub/api/v1/notify/fdr?sendFlowEvent=false&sendPaymentEvent=true
```

The body contains `container` and the resolved `fileName`. All payment events in
that blob are replayed; the flow summary event is skipped. The single-blob API's
success message is validated; 503 or a timeout may follow partial publication.

## 3. Verify, then continue in batches

Verify the first flow in PROD ADX before continuing. HTTP 200 means API processing
succeeded, not that ADX ingestion has finished. Compare flow/payment identifiers,
counts and amounts. Replaying existing payments creates duplicate ADX rows.

Run the same command with `--execute --limit 1000` for the next batch. Always reuse
`work/prod.sqlite`: every previously attempted blob is skipped, including uncertain
or failed attempts. Stop at the first unsuccessful response; investigate partial
results in logs/ADX before deciding on a retry. Do not delete the registry.

Default settings: sequential calls, one second between sends, 120-second socket
timeout. At 89,555 flows the pause alone is about 25 hours; tune `--interval` and
`--timeout` from measured service capacity. Keep the manifest and registry on local
persistent storage; run one process at a time and transfer the registry too if
changing machines while stopped. Exit codes: 0 no known errors, 1 local/preflight
failure, 2 preparation issues or unsuccessful attempts recorded in the registry.

```sh
python recover_fdr.py status --ledger work/prod.sqlite --export work/prod-report.csv
python -m unittest discover -s tests -v
```

DEV/UAT validated the previous direct-endpoint sender, ADX recovery and restart.
Automatic storage discovery and the APIM adapter require a live validation by an
authorized operator before the full PROD run.
