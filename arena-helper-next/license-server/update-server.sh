#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
# Keep the current project name and volumes. Never call down or remove volumes.
docker compose -p arena-android exec -T api python - <<'PY'
import sqlite3,time,os
path=os.environ.get('LICENSE_DB','/data/licenses.sqlite')
backup='/data/licenses-before-admin-'+str(time.time_ns())+'.sqlite'
os.umask(0o077)
with sqlite3.connect(path) as source, sqlite3.connect(backup) as target:
    source.backup(target)
print('License backup saved:',backup)
PY
docker compose -p arena-android up -d --build --no-deps api updater
echo 'Server updated. Open https://45-91-53-108.sslip.io/admin'
