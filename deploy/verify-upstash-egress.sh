#!/usr/bin/env bash
# Verifies, ON THE EC2 HOST, that this machine can reach Upstash Redis securely and that the service env files are
# set up correctly. Read-only: it never writes to Redis except one PING/ECHO-free AUTH+PING, never prints the URL or
# password, and never restarts anything.
#
# Usage:  sudo ./deploy/verify-upstash-egress.sh          (reads /etc/homeease/*.env)
set -u
ENV_DIR=/etc/homeease
fail=0
ok()   { printf '  [ OK ] %s\n' "$*"; }
bad()  { printf '  [FAIL] %s\n' "$*"; fail=1; }
note() { printf '  [INFO] %s\n' "$*"; }

echo "== 1. Environment files (permissions and required keys; values are never printed) =="
for svc in backend dispatch tracking; do
  f="$ENV_DIR/$svc.env"
  if [ ! -f "$f" ]; then bad "$f missing"; continue; fi
  mode=$(stat -c '%a' "$f"); owner=$(stat -c '%U:%G' "$f")
  [ "$mode" = "600" ] && ok "$f mode 600" || bad "$f mode is $mode (want 600)"
  [ "$owner" = "root:root" ] && ok "$f owner root:root" || note "$f owner is $owner (root:root recommended)"
  grep -q '^REDIS_URL=rediss://' "$f" && ok "$svc: REDIS_URL present and uses rediss:// (TLS)" || bad "$svc: REDIS_URL missing or not rediss://"
done
# the three services must share one REDIS_URL: compare hashes, not values
h() { grep '^REDIS_URL=' "$ENV_DIR/$1.env" 2>/dev/null | sha256sum | cut -c1-16; }
if [ "$(h backend)" = "$(h dispatch)" ] && [ "$(h backend)" = "$(h tracking)" ]; then ok "REDIS_URL identical across backend/dispatch/tracking"; else bad "REDIS_URL differs between services"; fi
j() { grep '^JWT_SECRET=' "$ENV_DIR/$1.env" 2>/dev/null | sha256sum | cut -c1-16; }
[ "$(j backend)" = "$(j tracking)" ] && ok "JWT_SECRET identical on backend and tracking" || bad "JWT_SECRET differs between backend and tracking"

URL=$(grep '^REDIS_URL=' "$ENV_DIR/backend.env" 2>/dev/null | cut -d= -f2-)
HOST=$(printf '%s' "$URL" | sed -E 's#^rediss?://([^@]*@)?([^:/]+).*#\2#')
PORT=$(printf '%s' "$URL" | sed -E 's#^rediss?://([^@]*@)?[^:/]+:([0-9]+).*#\2#')
PASS=$(printf '%s' "$URL" | sed -E 's#^rediss?://[^:]*:([^@]*)@.*#\1#')
[ -z "$HOST" ] && { bad "cannot parse host from REDIS_URL"; exit 1; }

echo "== 2. DNS =="
getent hosts "$HOST" >/dev/null && ok "$HOST resolves" || bad "$HOST does not resolve"

echo "== 3. Outbound TCP $PORT =="
timeout 5 bash -c "exec 3<>/dev/tcp/$HOST/$PORT" 2>/dev/null && ok "TCP connect to $HOST:$PORT" || bad "cannot connect to $HOST:$PORT (check security-group OUTBOUND rules / NACL)"

echo "== 4. TLS certificate verification (system CA store) =="
out=$(echo | timeout 8 openssl s_client -connect "$HOST:$PORT" -servername "$HOST" -verify_hostname "$HOST" -verify_return_error 2>&1)
if echo "$out" | grep -q 'Verification: OK\|Verify return code: 0 (ok)'; then ok "certificate chain and hostname verified"; else bad "TLS verification failed"; echo "$out" | grep -E 'Verify return code|verify error' | head -3; fi
echo "$out" | grep -E 'Protocol *:|TLSv' | head -1 | sed 's/^/  [INFO] /'

echo "== 5. Authenticated PING (password passed via environment, not argv) =="
if command -v redis-cli >/dev/null 2>&1; then
  r=$(REDISCLI_AUTH="$PASS" timeout 8 redis-cli --tls -h "$HOST" -p "$PORT" --user default PING 2>&1)
  [ "$r" = "PONG" ] && ok "PING -> PONG (authenticated, TLS)" || bad "PING failed: $(printf "%s" "${r//"$PASS"/***}" | head -c 120)"
else
  note "redis-cli not installed: skipping (install redis-tools, or rely on the app's /actuator/health redisPing)"
fi

echo "== 6. Inbound exposure =="
ss -ltn 2>/dev/null | awk '$4 ~ /:6379$/ {print "  [FAIL] something is LISTENING on 6379 locally: " $4; f=1} END{ if(!f) print "  [ OK ] nothing listens on 6379 locally (no inbound Redis port)"}'

echo "== 7. systemd =="
for svc in backend dispatch tracking; do
  u=homeease-$svc
  if systemctl cat "$u" >/dev/null 2>&1; then
    systemctl show "$u" -p EnvironmentFiles --value | grep -q "/etc/homeease/$svc.env" && ok "$u uses EnvironmentFile=/etc/homeease/$svc.env" || bad "$u EnvironmentFile mismatch"
    systemctl cat "$u" | grep -q 'Requires=redis.service' && bad "$u still Requires=redis.service" || ok "$u has no dependency on a local redis.service"
  else note "$u not installed yet"; fi
done
exit $fail
