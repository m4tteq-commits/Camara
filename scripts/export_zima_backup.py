"""Export a Cămara SQLite backup over SSH and rehearse restoring it on this PC."""
import argparse
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys
from tempfile import TemporaryDirectory
from uuid import UUID, uuid4

ROOT = Path(__file__).resolve().parents[1]

REMOTE_EXPORT = """
import json
import os
from pathlib import Path
import shutil
import sys
import urllib.request
from uuid import UUID
from app.request_limits import secret_setting

request = urllib.request.Request(
    'http://127.0.0.1:8080/v1/backups', data=b'',
    headers={'Authorization': 'Bearer ' + secret_setting('CAMARA_API_KEY')},
)
with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=120) as response:
    info = json.load(response)
backup = Path(os.environ.get('CAMARA_BACKUP_DIR', '/backups')) / (str(UUID(info['id'])) + '.db')
with backup.open('rb') as source:
    out = sys.stdout.buffer
    out.write(json.dumps(info).encode('utf-8') + b'\\n')
    shutil.copyfileobj(source, out)
    out.flush()
"""


def receive_backup(command, staging):
    with subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE) as process:
        try:
            process.stdin.write(REMOTE_EXPORT.encode("utf-8"))
            process.stdin.close()
            line = process.stdout.readline(65_536)
            if not line.endswith(b"\n"):
                raise ValueError("ZimaOS nu a trimis metadatele copiei.")
            info = json.loads(line)
            backup_id = UUID(info["id"])
            digest, size = info["sha256"], info["size_bytes"]
            if not re.fullmatch(r"[a-f0-9]{64}", digest) or type(size) is not int or size <= 0:
                raise ValueError("Metadatele copiei nu sunt valide.")
            folder = staging / str(backup_id)
            folder.mkdir()
            database = folder / f"{backup_id}.db"
            with database.open("wb") as output:
                remaining = size
                while remaining:
                    chunk = process.stdout.read(min(remaining, 1024 * 1024))
                    if not chunk:
                        raise ValueError("Transferul copiei s-a întrerupt.")
                    output.write(chunk)
                    remaining -= len(chunk)
                if process.stdout.read(1):
                    raise ValueError("Dimensiunea primită diferă de metadate.")
            if process.wait(timeout=10) != 0:
                raise ValueError("Comanda de export SSH a eșuat.")
        finally:
            if process.poll() is None:
                process.terminate()

    with database.open("rb") as source:
        if hashlib.file_digest(source, "sha256").hexdigest() != digest:
            raise ValueError("Copia primită nu corespunde hashului SHA-256.")
    (folder / f"{backup_id}.json").write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return folder, backup_id, info


def rehearse_restore(folder, backup_id, info, staging):
    sys.path.insert(0, str(ROOT / "backend"))
    from app.backups import BackupManager, RestoreRequest
    from app.database import Database

    source = folder / f"{backup_id}.db"
    with closing(sqlite3.connect(f"{source.as_uri()}?mode=ro&immutable=1", uri=True)) as connection:
        if connection.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise ValueError("Copia nu a trecut verificarea SQLite.")

    test_db = Database(staging / "restore.db")
    manager = BackupManager(test_db, staging / "restore-backups")
    for suffix in ("db", "json"):
        name = f"{backup_id}.{suffix}"
        shutil.copyfile(folder / name, manager.directory / name)
    preview = manager.preview(backup_id)
    request = RestoreRequest(request_id=uuid4(), expected_fingerprint=preview["fingerprint"],
                             backup_sha256=info["sha256"], confirmation="RESTAUREAZĂ")
    manager.restore(backup_id, request)
    with test_db.connection() as connection:
        restored = manager.counts(connection)
        if connection.execute("PRAGMA integrity_check").fetchone()[0] != "ok" or restored != info["counts"]:
            raise ValueError("Baza restaurată diferă de copia exportată.")


def main():
    if sys.stdout.encoding:
        sys.stdout.reconfigure(errors="backslashreplace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True, help="Adresa SSH din Tailscale, cu utilizator")
    parser.add_argument("--host-key-alias", required=True, help="Identificatorul deja verificat în known_hosts")
    parser.add_argument("--identity", required=True, type=Path)
    default_output = Path(os.environ["LOCALAPPDATA"]) / "Camara" / "Backups" if "LOCALAPPDATA" in os.environ else None
    parser.add_argument("--output", type=Path, default=default_output, required=default_output is None)
    args = parser.parse_args()

    output = args.output.expanduser().resolve()
    if output.is_relative_to(ROOT):
        parser.error("Alege un folder privat din afara proiectului.")
    output.mkdir(parents=True, exist_ok=True)
    command = ["ssh", "-T", "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes", "-o", "StrictHostKeyChecking=yes",
               "-o", "UpdateHostKeys=no", "-o", f"HostKeyAlias={args.host_key_alias}", "-o", "ConnectTimeout=8",
               "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=2", "-i", str(args.identity.expanduser()),
               "--", args.host, "docker --config /DATA/AppData/camara/.docker exec -i camara-api-1 python -"]
    try:
        with TemporaryDirectory(prefix=".camara-export-", dir=output) as temporary:
            staging = Path(temporary)
            folder, backup_id, info = receive_backup(command, staging)
            rehearse_restore(folder, backup_id, info, staging)
            destination = output / str(backup_id)
            folder.rename(destination)
    except (OSError, ValueError, KeyError, sqlite3.Error, subprocess.SubprocessError) as error:
        parser.exit(1, f"Exportul nu a fost finalizat. {error}\n")
    print(f"Backup verificat. Restaurare de proba confirmata. {destination}")


if __name__ == "__main__":
    main()
