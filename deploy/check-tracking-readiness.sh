#!/usr/bin/env bash
# HomeEase tracking readiness: read-only diagnostics. Does not print secret values.
# Run on the backend EC2 host: bash deploy/check-tracking-readiness.sh
set -uo pipefail

echo "HOME EASE | tracking readiness | $(date -u +%FT%TZ)"
echo "============================================"
for unit in homeease-core homeease-tracking nginx; do
  state="$(systemctl is-active "$unit" 2>/dev/null || true)"
  printf '%-24s %s\n' "$unit" "${state:-unknown}"
done

check_url() {
  local name="$1" url="$2"
  local code
  code="$(curl --silent --output /dev/null --max-time 6 --write-out '%{http_code}' "$url" 2>/dev/null || true)"
  printf '%-24s HTTP %s\n' "$name" "${code:-unreachable}"
}
check_url "core actuator" "http://127.0.0.1:8080/actuator/health"
check_url "tracking health" "http://127.0.0.1:8082/api/v1/health"
check_url "nginx core" "http://127.0.0.1/actuator/health"

echo "--- expected environment files (presence only) ---"
for file in /etc/homeease/app.env /etc/homeease/tracking.env; do
  if [[ -r "$file" ]]; then
    echo "$file : readable"
  elif [[ -e "$file" ]]; then
    echo "$file : exists but not readable by this user (try sudo)"
  else
    echo "$file : missing"
  fi
done

# Only root can inspect service EnvironmentFiles. Never echo their values.
if [[ "${EUID}" -eq 0 ]]; then
  python3 - <<'PY'
from pathlib import Path
from urllib.parse import urlparse
import hmac
p = [Path('/etc/homeease/app.env'), Path('/etc/homeease/tracking.env')]
required = ('JWT_SECRET', 'DB_USER', 'DB_PASS', 'REDIS_URL', 'UPSTASH_REDIS_URL', 'REDIS_HOST')
envs = []
for path in p:
    d = {}
    if path.exists():
        for line in path.read_text(errors='replace').splitlines():
            s = line.strip()
            if not s or s.startswith('#') or '=' not in s: continue
            k, v = s.split('=',1)
            d[k.strip()] = v.strip().strip('"').strip("'")
    envs.append(d)
    print(f'{path.name}: ' + ', '.join(f'{key}={"SET" if d.get(key) else "missing"}' for key in required))
core, track = envs
if core.get('JWT_SECRET') and track.get('JWT_SECRET'):
    print('JWT_SECRET consistent across services:', hmac.compare_digest(core['JWT_SECRET'], track['JWT_SECRET']))
else:
    print('JWT_SECRET consistent across services: cannot compare (missing)')
def redis_target(d):
    raw=d.get('REDIS_URL') or d.get('UPSTASH_REDIS_URL')
    if raw:
        try:
            url=urlparse(raw)
            return (url.hostname, url.port or 6379, bool(url.scheme=='rediss'))
        except Exception:
            return None
    return (d.get('REDIS_HOST','localhost'), d.get('REDIS_PORT','6379'), d.get('REDIS_SSL_ENABLED','false').lower()=='true')
a,b=redis_target(core),redis_target(track)
print('Redis target consistent across services:', a==b if a and b else 'unknown')
print('Redis TLS core / tracking:', a[2] if a else 'unknown','/',b[2] if b else 'unknown')
PY
else
  echo "Tip: run with sudo to check env key presence, JWT consistency, and Redis target without printing values."
fi

echo "--- nginx config sanity (no raw config output) ---"
if command -v nginx >/dev/null 2>&1; then
  if nginx -t >/dev/null 2>&1; then echo "nginx config syntax: valid"
  else echo "nginx config syntax: unknown/invalid (run sudo nginx -t)"; fi
fi
echo "--- no GPS/booking data read; no secrets printed ---"
echo "For live booking checks, use a test booking and compare ON_THE_WAY, first GPS upload,"
echo "tracking:assignment:<bookingUUID>, tracking:booking:<bookingUUID>, and route HTTP status."
