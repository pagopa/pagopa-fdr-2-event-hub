#!/usr/bin/env python3
"""Recovery FDR: standard library only, Python >= 3.11."""
import argparse
import csv
import hashlib
import json
import math
import os
import re
import sqlite3
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from datetime import date, datetime, timezone
from pathlib import Path

VERSION = "0.2.0"
FIELDS = ["id_flusso", "container", "fileName"]


def now():
    return datetime.now(timezone.utc).isoformat()


def csv_rows(path, required):
    with Path(path).open(encoding="utf-8-sig", newline="") as stream:
        sample = stream.read(8192)
        stream.seek(0)
        try:
            dialect = csv.Sniffer().sniff(sample, delimiters=",;\t")
        except csv.Error:
            dialect = csv.excel
        reader = csv.DictReader(stream, dialect=dialect)
        if not reader.fieldnames or not set(required).issubset(reader.fieldnames):
            raise ValueError(f"{path}: colonne richieste: {', '.join(required)}")
        for line, row in enumerate(reader, 2):
            if None in row or any(v is None for v in row.values()):
                raise ValueError(f"{path}:{line}: numero di colonne non valido")
            row = {k: v.strip() for k, v in row.items()}
            if any(not row[k] or row[k].upper() == "NULL" for k in required):
                raise ValueError(f"{path}:{line}: campo richiesto vuoto/NULL")
            yield row


def write_csv(path, fields, rows):
    with Path(path).open("x", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def prepare(args):
    """Resolve exact FDR3 names; never infer the latest revision."""
    ids = list(dict.fromkeys(r["id_flusso"] for r in csv_rows(args.csv, ["id_flusso"])))
    if not ids:
        raise ValueError("CSV senza flussi")
    wanted = set(ids)
    index = defaultdict(dict)
    for inventory in args.inventory:
        data = json.loads(Path(inventory).read_text(encoding="utf-8-sig"))
        if not isinstance(data, list):
            raise ValueError("Inventario: attesa lista JSON prodotta da az storage blob list")
        for blob in data:
            name = blob.get("name", "")
            if not name.endswith(".json.zip"):
                continue
            parts = name[:-9].rsplit("_", 2)
            if len(parts) != 3 or not parts[2].isdigit() or not parts[1]:
                continue
            flow_id = parts[0]
            if flow_id in wanted:
                if name in index[flow_id] and index[flow_id][name] != blob:
                    raise ValueError(f"Inventari discordanti per {name}")
                index[flow_id][name] = blob
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=False)
    ready, issues = [], []
    for flow_id in ids:
        candidates = list(index[flow_id].values())
        reason = ""
        if not candidates:
            reason = "NOT_IN_INVENTORY"
        elif len(candidates) != 1:
            reason = "AMBIGUOUS"
        else:
            metadata = {k.lower(): v for k, v in (candidates[0].get("metadata") or {}).items()}
            if str(metadata.get("elaborate", "")).lower() != "true":
                reason = "ELABORATE_NOT_TRUE"
            elif any(not metadata.get(k) for k in ("sessionid", "serviceidentifier", "insertedtimestamp")):
                reason = "MISSING_METADATA"
        if reason:
            issues.append({"id_flusso": flow_id, "reason": reason,
                           "candidates": json.dumps([b["name"] for b in candidates])})
        else:
            ready.append({"id_flusso": flow_id, "container": args.container,
                          "fileName": candidates[0]["name"]})
    write_csv(out / "manifest.csv", FIELDS, ready)
    write_csv(out / "issues.csv", ["id_flusso", "reason", "candidates"], issues)
    report = {"script_version": VERSION, "created": now(), "source_account": args.source_account,
              "environment": args.environment, "container": args.container,
              "flows": len(ids), "ready": len(ready), "issues": len(issues),
              "inventory_sha256": {str(p): hashlib.sha256(Path(p).read_bytes()).hexdigest()
                                   for p in args.inventory},
              "csv_sha256": hashlib.sha256(Path(args.csv).read_bytes()).hexdigest()}
    (out / "preparation.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    print("Gli inventari devono coprire tutti i prefissi necessari; nessuna chiamata API eseguita.")
    return 2 if issues else 0


def discovery_prefixes(ids):
    """Group date-prefixed IDs: one paginated listing per day, not per flow."""
    prefixes = set()
    for flow_id in ids:
        prefix = flow_id + "_"
        if re.match(r"^\d{4}-\d{2}-\d{2}", flow_id):
            try:
                date.fromisoformat(flow_id[:10])
                prefix = flow_id[:10]
            except ValueError:
                pass
        prefixes.add(prefix)
    return sorted(prefixes)


def storage_client(args):
    """Optional Azure dependencies: run/prepare/status remain standard-library only."""
    try:
        from azure.storage.blob import BlobServiceClient
        from azure.identity import AzureCliCredential
    except ImportError:
        raise ValueError("Per discover: python -m pip install -r requirements-discovery.txt") from None
    credential = None
    if args.storage_auth == "cli":
        if not args.subscription:
            raise ValueError("--subscription obbligatorio con --storage-auth cli")
        credential = AzureCliCredential(subscription=args.subscription)
        service = BlobServiceClient(
            account_url=f"https://{args.source_account}.blob.core.windows.net",
            credential=credential, connection_timeout=15, read_timeout=60,
            retry_total=3, logging_enable=False)
    else:
        connection = os.environ.get(args.connection_string_env)
        if not connection:
            raise ValueError(f"Variabile {args.connection_string_env} non valorizzata")
        service = BlobServiceClient.from_connection_string(
            connection, connection_timeout=15, read_timeout=60, retry_total=3, logging_enable=False)
    host = urllib.parse.urlsplit(service.url).hostname
    if host != f"{args.source_account}.blob.core.windows.net":
        service.close()
        if credential:
            credential.close()
        raise ValueError("Account della credenziale diverso da --source-account")
    return service, credential


def discover(args):
    if not re.fullmatch(r"[a-z0-9]{3,24}", args.source_account):
        raise ValueError("Nome storage account non valido")
    if not 1 <= args.page_size <= 5000:
        raise ValueError("page-size deve essere compreso fra 1 e 5000")
    ids = list(dict.fromkeys(r["id_flusso"] for r in csv_rows(args.csv, ["id_flusso"])))
    if not ids:
        raise ValueError("CSV senza flussi")
    wanted = set(ids)
    prefixes = discovery_prefixes(ids)
    out = args.out_dir
    out.mkdir(parents=True, exist_ok=True)
    lock = out / "discovery.lock"
    with lock.open("x", encoding="utf-8") as stream:
        stream.write(now())
    db = service = credential = None
    try:
        db = sqlite3.connect(out / "discovery.sqlite", timeout=1)
        db.execute("PRAGMA synchronous=FULL")
        db.execute("CREATE TABLE IF NOT EXISTS context (id INTEGER PRIMARY KEY CHECK(id=1),data TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS prefixes (prefix TEXT PRIMARY KEY,token TEXT,done INTEGER NOT NULL DEFAULT 0,scanned INTEGER NOT NULL DEFAULT 0)")
        db.execute("CREATE TABLE IF NOT EXISTS blobs (name TEXT PRIMARY KEY,metadata TEXT NOT NULL)")
        context = {"environment": args.environment, "source_account": args.source_account,
                   "container": args.container, "csv_sha256": hashlib.sha256(args.csv.read_bytes()).hexdigest(),
                   "prefixes": prefixes, "page_size": args.page_size}
        serial = json.dumps(context, sort_keys=True)
        saved = db.execute("SELECT data FROM context WHERE id=1").fetchone()
        if saved and saved[0] != serial:
            raise ValueError("Checkpoint discovery associato a input/account/ambiente differenti; usare una nuova out-dir")
        if not saved:
            db.execute("INSERT INTO context VALUES (1,?)", (serial,))
            db.executemany("INSERT INTO prefixes(prefix) VALUES (?)", [(p,) for p in prefixes])
            db.commit()
        if (out / "prepared").exists():
            raise ValueError("Discovery gia' completata: usare prepared/manifest.csv e leggere prepared/issues.csv")
        pending = db.execute("SELECT prefix,token FROM prefixes WHERE done=0 ORDER BY prefix").fetchall()
        print(f"DISCOVERY: {len(ids)} flussi, {len(prefixes)} prefissi, {len(pending)} da completare. Nessun invio API.", flush=True)
        if pending:
            try:
                service, credential = storage_client(args)
                container = service.get_container_client(args.container)
                for prefix, token in pending:
                    pages = container.list_blobs(name_starts_with=prefix, include=["metadata"],
                                                 results_per_page=args.page_size).by_page(continuation_token=token)
                    for page in pages:
                        scanned = 0
                        matches = []
                        for blob in page:
                            scanned += 1
                            name = blob.name
                            parts = name[:-9].rsplit("_", 2) if name.endswith(".json.zip") else []
                            if len(parts) == 3 and parts[0] in wanted and parts[1] and parts[2].isdigit():
                                matches.append((name, json.dumps(blob.metadata or {}, sort_keys=True)))
                        next_token = pages.continuation_token
                        # Matching names AND continuation token are committed atomically.
                        with db:
                            db.executemany("INSERT OR REPLACE INTO blobs VALUES (?,?)", matches)
                            db.execute("UPDATE prefixes SET token=?,done=?,scanned=scanned+? WHERE prefix=?",
                                       (next_token, int(not next_token), scanned, prefix))
                        print(f"{now()} prefisso={prefix} letti={scanned} candidati={len(matches)} completo={not bool(next_token)}", flush=True)
                    # Empty listing can yield no pages; the exhausted iterator still confirms completion.
                    with db:
                        db.execute("UPDATE prefixes SET done=1,token=NULL WHERE prefix=?", (prefix,))
            except (ValueError, ImportError):
                raise
            except Exception as exc:
                # Azure exceptions can contain credential-bearing URLs: never print raw errors.
                code = getattr(exc, "status_code", None)
                raise ValueError(f"Discovery storage fallita ({type(exc).__name__}, HTTP={code}). Verificare accessi/rete; ripetere lo stesso comando per riprendere.") from None
        data = [{"name": n, "metadata": json.loads(m)} for n, m in db.execute("SELECT name,metadata FROM blobs ORDER BY name")]
        temp_inventory = out / "inventory.json.tmp"
        temp_inventory.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        temp_inventory.replace(out / "inventory.json")
        # Publish a complete prepared directory only after every prefix has completed.
        with tempfile.TemporaryDirectory(dir=out) as staging:
            prepared = Path(staging) / "prepared"
            result = prepare(argparse.Namespace(csv=args.csv, inventory=[out / "inventory.json"],
                out_dir=prepared, source_account=args.source_account, environment=args.environment,
                container=args.container))
            prepared.replace(out / "prepared")
        print(f"Risultati: {out / 'prepared'}. Elaborazione API da avviare separatamente.")
        return result
    finally:
        if service:
            service.close()
        if credential:
            credential.close()
        if db:
            db.close()
        lock.unlink(missing_ok=True)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def opener():
    # Direct endpoint/port-forward: do not route through workstation proxy settings.
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())


def validate_manifest(path):
    rows = []
    seen = set()
    flows = set()
    for row in csv_rows(path, FIELDS):
        item = {k: row[k] for k in FIELDS}
        if not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])", item["container"]):
            raise ValueError(f"Container non valido: {item['container']}")
        if any(ord(c) < 32 for v in item.values() for c in v):
            raise ValueError("Caratteri di controllo nel manifest")
        if item["container"] != "fdr3-flows":
            raise ValueError("Questa versione accetta solo il container FDR3 fdr3-flows")
        parts = item["fileName"][:-9].rsplit("_", 2) if item["fileName"].endswith(".json.zip") else []
        if len(parts) != 3 or parts[0] != item["id_flusso"] or not parts[1] or not parts[2].isdigit():
            raise ValueError(f"Nome blob non coerente con id_flusso: {item['fileName']}")
        key = (item["container"], item["fileName"])
        if key in seen:
            continue
        if item["id_flusso"] in flows:
            raise ValueError(f"Più blob per lo stesso flusso: {item['id_flusso']}")
        seen.add(key)
        flows.add(item["id_flusso"])
        rows.append(item)
    if not rows:
        raise ValueError("Manifest vuoto")
    return rows


def connect_ledger(path, context):
    db = sqlite3.connect(path, timeout=1)
    db.execute("PRAGMA synchronous=FULL")
    db.execute("CREATE TABLE IF NOT EXISTS context (id INTEGER PRIMARY KEY CHECK(id=1), data TEXT NOT NULL)")
    db.execute("""CREATE TABLE IF NOT EXISTS attempts (
        id INTEGER PRIMARY KEY, container TEXT NOT NULL, filename TEXT NOT NULL,
        flow_id TEXT NOT NULL, started TEXT NOT NULL, finished TEXT,
        state TEXT NOT NULL, http_status INTEGER, detail TEXT)""")
    db.execute("CREATE INDEX IF NOT EXISTS attempts_blob ON attempts(container,filename,id DESC)")
    saved = db.execute("SELECT data FROM context WHERE id=1").fetchone()
    serial = json.dumps(context, sort_keys=True)
    if saved and saved[0] != serial:
        db.close()
        raise ValueError("Registro associato a un altro ambiente/storage/destinazione. Usare il registro corretto.")
    if not saved:
        db.execute("INSERT INTO context VALUES (1,?)", (serial,))
    db.commit()
    return db


def export_ledger(db, path):
    # Overwriteable report; SQLite is the authoritative durable state.
    with Path(path).open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream)
        cursor = db.execute("SELECT * FROM attempts ORDER BY id")
        writer.writerow([d[0] for d in cursor.description])
        writer.writerows(cursor)


def status(args):
    db = sqlite3.connect(args.ledger.resolve().as_uri() + "?mode=ro", uri=True)
    try:
        print(db.execute("SELECT data FROM context WHERE id=1").fetchone()[0])
        print(json.dumps(dict(db.execute("SELECT state,count(*) FROM attempts GROUP BY state")), indent=2))
        if args.export:
            export_ledger(db, args.export)
    finally:
        db.close()
    return 0


def send(args):
    rows = validate_manifest(args.csv)
    base = args.base_url.rstrip("/")
    parsed = urllib.parse.urlsplit(base)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.query or parsed.fragment or parsed.username or parsed.password:
        raise ValueError("base-url: richiesto URL HTTP(S) senza credenziali, query o fragment")
    if args.limit < 1 or args.timeout <= 0 or args.interval < 0 or not all(math.isfinite(v) for v in (args.timeout, args.interval)):
        raise ValueError("limit >= 1, timeout > 0, interval >= 0 e valori finiti richiesti")
    url = base + "/notify/fdr/massive?sendFlowEvent=false&sendPaymentEvent=true"
    if not args.execute:
        print(f"SIMULAZIONE: {len(rows)} blob validati, limite {args.limit}. Nessuna rete o modifica al registro.")
        for item in rows[:args.limit]:
            print(json.dumps({"url": url, "body": {k: item[k] for k in ("container", "fileName")}}, ensure_ascii=False))
        return 0
    if not args.ledger:
        raise ValueError("--ledger obbligatorio per --execute")
    # Exclusive lock prevents concurrent users sharing a ledger on the same filesystem.
    lock = Path(str(args.ledger) + ".lock")
    with lock.open("x", encoding="utf-8") as stream:
        stream.write(f"{now()}\nNon rimuovere mentre un processo e' attivo.\n")
    db = None
    try:
        http = opener()
        with http.open(base + "/info", timeout=min(args.timeout, 15)) as response:
            if response.status != 200:
                raise ValueError("/info non restituisce HTTP 200")
            info = json.loads(response.read(65536))
        if info.get("name") != "FDR To Event Hub" or info.get("environment") != args.environment:
            raise ValueError(f"Servizio/ambiente inatteso in /info: {info}")
        print(f"Servizio: {info['name']}, ambiente: {info['environment']}, versione: {info.get('version')}")
        context = {"environment": args.environment, "source_account": args.source_account,
                   "destination_id": args.destination_id, "sendFlowEvent": False, "sendPaymentEvent": True}
        db = connect_ledger(args.ledger, context)
        counts = Counter()
        for item in rows:
            if counts["attempted"] >= args.limit:
                break
            previous = db.execute("SELECT state FROM attempts WHERE container=? AND filename=? ORDER BY id DESC LIMIT 1",
                                  (item["container"], item["fileName"])).fetchone()
            if previous:
                counts["skipped_" + previous[0]] += 1
                continue
            started = now()
            cursor = db.execute("INSERT INTO attempts (container,filename,flow_id,started,state) VALUES (?,?,?,?,?)",
                                (item["container"], item["fileName"], item["id_flusso"], started, "IN_FLIGHT"))
            attempt_id = cursor.lastrowid
            db.commit()  # Durable BEFORE network: an interrupted call is never automatically repeated.
            counts["attempted"] += 1
            code = None
            state = "UNCERTAIN"
            detail = ""
            try:
                request = urllib.request.Request(url, method="POST", headers={"Content-Type": "application/json"},
                    data=json.dumps({k: item[k] for k in ("container", "fileName")}).encode("utf-8"))
                try:
                    response = http.open(request, timeout=args.timeout)
                except urllib.error.HTTPError as exc:
                    response = exc
                with response:
                    code = response.code
                    detail = response.read(65536).decode("utf-8", errors="replace")
                if code == 200:
                    try:
                        expected = f"Successfully processed 1 file(s) in container {item['container']}"
                        if json.loads(detail).get("message") == expected:
                            state = "SENT"  # Event Hub confirmed by API; NOT an ADX verification.
                    except (ValueError, AttributeError):
                        pass
                elif code == 207:
                    state = "PARTIAL"
                elif code in (400, 401, 403, 404, 405, 422):
                    state = "REJECTED"
            except (Exception, KeyboardInterrupt) as exc:
                detail = f"{type(exc).__name__}: {exc}"
                if isinstance(exc, KeyboardInterrupt):
                    detail = "Interrotto dall'operatore; verificare eventuale pubblicazione parziale"
            db.execute("UPDATE attempts SET finished=?,state=?,http_status=?,detail=? WHERE id=?",
                       (now(), state, code, detail, attempt_id))
            db.commit()
            counts[state] += 1
            print(f"{now()} {state} HTTP={code} {item['fileName']}", flush=True)
            if state != "SENT":
                print(detail)
                print("Arresto al primo esito non riuscito. Nessun retry automatico.")
                break
            if counts["attempted"] < args.limit:
                time.sleep(args.interval)
        print(json.dumps(dict(counts), indent=2))
        export_ledger(db, str(args.ledger) + ".csv")
        unresolved = db.execute("SELECT count(*) FROM attempts WHERE state != 'SENT'").fetchone()[0]
        return 2 if unresolved else 0
    finally:
        if db:
            db.close()
        lock.unlink(missing_ok=True)


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--version", action="version", version=VERSION)
    sub = p.add_subparsers(dest="command", required=True)
    discovery = sub.add_parser("discover", help="CSV originale -> ricerca automatica blob e metadati -> manifest, senza invii")
    discovery.add_argument("--csv", required=True, type=Path)
    discovery.add_argument("--out-dir", required=True, type=Path)
    discovery.add_argument("--source-account", required=True)
    discovery.add_argument("--environment", required=True, choices=["dev", "uat", "prod"])
    discovery.add_argument("--container", default="fdr3-flows", choices=["fdr3-flows"])
    discovery.add_argument("--storage-auth", default="cli", choices=["cli", "connection-string-env"])
    discovery.add_argument("--subscription", help="Nome/ID sottoscrizione per AzureCliCredential; non cambia il default della CLI")
    discovery.add_argument("--connection-string-env", default="FDR_RECOVERY_STORAGE_CONNECTION_STRING", help="Nome della variabile, mai il valore della credenziale")
    discovery.add_argument("--page-size", type=int, default=5000)
    discovery.set_defaults(func=discover)
    prep = sub.add_parser("prepare", help="CSV originale + inventari JSON -> manifest e anomalie, senza API")
    prep.add_argument("--csv", required=True, type=Path)
    prep.add_argument("--inventory", required=True, nargs="+", type=Path)
    prep.add_argument("--out-dir", required=True, type=Path)
    prep.add_argument("--container", choices=["fdr3-flows"], default="fdr3-flows")
    prep.add_argument("--source-account", required=True)
    prep.add_argument("--environment", required=True, choices=["dev", "uat", "prod"])
    prep.set_defaults(func=prepare)
    run = sub.add_parser("run", help="Simulazione predefinita; --execute per inviare")
    run.add_argument("--csv", required=True, type=Path)
    run.add_argument("--base-url", required=True, help="Esempio: http://127.0.0.1:18080; senza /info o /notify")
    run.add_argument("--environment", required=True, choices=["dev", "uat", "prod"])
    run.add_argument("--source-account", required=True, help="Account verificato dall'operatore, non validato dall'API")
    run.add_argument("--destination-id", required=True, help="Identificativo stabile del deployment: non dipende dalla porta locale")
    run.add_argument("--ledger", type=Path)
    run.add_argument("--limit", type=int, default=1, help="Numero massimo di nuovi invii, default 1")
    run.add_argument("--timeout", type=float, default=120)
    run.add_argument("--interval", type=float, default=1)
    run.add_argument("--execute", action="store_true")
    run.set_defaults(func=send)
    report = sub.add_parser("status", help="Legge il registro e opzionalmente esporta tutti gli esiti")
    report.add_argument("--ledger", required=True, type=Path)
    report.add_argument("--export", type=Path)
    report.set_defaults(func=status)
    return p


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        return args.func(args)
    except (Exception, KeyboardInterrupt) as exc:
        print(f"ERRORE: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
