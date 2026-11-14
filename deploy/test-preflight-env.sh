#!/usr/bin/env bash
# Local test harness for deploy/preflight-env.sh: a fake `systemctl` and fake env files, no root, no real services.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
pass=0; failn=0

# fake systemctl: serves EnvironmentFiles/Environment for units defined by $TMP/<unit>.files and $TMP/<unit>.env
cat > "$TMP/systemctl" <<'EOS'
#!/usr/bin/env bash
unit="${@: -1}"
case "$*" in
  *"show -p EnvironmentFiles"*) cat "$FAKE_DIR/$unit.files" 2>/dev/null ;;
  *"show -p Environment --value"*) cat "$FAKE_DIR/$unit.env" 2>/dev/null ;;
  cat*) [ -f "$FAKE_DIR/$unit.files" ] || [ -f "$FAKE_DIR/$unit.env" ] ;;
esac
EOS
chmod +x "$TMP/systemctl"
export FAKE_DIR="$TMP" SYSTEMCTL="$TMP/systemctl" SUDO=""

run() { # name expected_exit specs...
  local name="$1" want="$2"; shift 2
  out=$("$HERE/preflight-env.sh" "$@" 2>&1); got=$?
  if [ "$got" = "$want" ]; then pass=$((pass+1)); printf 'PASS  %s (exit %s)\n' "$name" "$got"
  else failn=$((failn+1)); printf 'FAIL  %s (wanted exit %s, got %s)\n%s\n' "$name" "$want" "$got" "$out"; fi
  # a value must never appear in the output
  if printf '%s' "$out" | grep -q -E 'Placeholder|S3cr3t|0123456789abcdef'; then failn=$((failn+1)); echo "FAIL  $name leaked a value into its output"; fi
}

GOOD_JWT="0123456789abcdef0123456789abcdef0123456789abcdef"

# 1. core with everything set via an EnvironmentFile
printf 'DB_USER=appuser\nDB_PASS=S3cr3t-Placeholder-1\nJWT_SECRET=%s\n' "$GOOD_JWT" > "$TMP/core.envfile"
echo "$TMP/core.envfile (ignore_errors=no)" > "$TMP/homeease-core.files"
run "core: all three secrets present" 0 homeease-core:core

# 2. core missing the DB password (the realistic production situation: relied on the old committed default)
printf 'DB_USER=appuser\nJWT_SECRET=%s\n' "$GOOD_JWT" > "$TMP/core.envfile"
run "core: DB_PASS missing -> abort" 1 homeease-core:core

# 3. standard Spring env names are accepted
printf 'SPRING_DATASOURCE_USERNAME=appuser\nSPRING_DATASOURCE_PASSWORD=S3cr3t-Placeholder-2\nJWT_SECRET=%s\n' "$GOOD_JWT" > "$TMP/core.envfile"
run "core: SPRING_DATASOURCE_* names accepted" 0 homeease-core:core

# 4. JWT too short
printf 'DB_USER=u\nDB_PASS=S3cr3t-Placeholder-3\nJWT_SECRET=short\n' > "$TMP/core.envfile"
run "core: short JWT secret -> abort" 1 homeease-core:core

# 5. a value equal to a known-leaked one (we cannot embed the real value, so test the mechanism via a planted hash)
planted="planted-leaked-value-0123456789abcdef0123456789"
printf 'DB_USER=u\nDB_PASS=S3cr3t-Placeholder-4\nJWT_SECRET=%s\n' "$planted" > "$TMP/core.envfile"
h=$(printf '%s' "$planted" | sha256sum | cut -d' ' -f1)
out=$(COMPROMISED_EXTRA="$h" bash -c 'sed "s/^COMPROMISED=\"/COMPROMISED=\"$COMPROMISED_EXTRA /" "'"$HERE"'/preflight-env.sh" > "'"$TMP"'/pf2.sh"; chmod +x "'"$TMP"'/pf2.sh"; "'"$TMP"'/pf2.sh" homeease-core:core' 2>&1); got=$?
if [ "$got" = 1 ] && printf '%s' "$out" | grep -q "committed to Git history"; then pass=$((pass+1)); echo "PASS  core: known-leaked value rejected by hash (exit 1)"; else failn=$((failn+1)); echo "FAIL  leaked-value rejection (exit $got)"; fi
printf '%s' "$out" | grep -q "$planted" && { failn=$((failn+1)); echo "FAIL  leaked value echoed"; }

# 6. via Environment= instead of a file
rm -f "$TMP/homeease-core.files"
echo "DB_USER=u DB_PASS=S3cr3t-Placeholder-5 JWT_SECRET=$GOOD_JWT" > "$TMP/homeease-core.env"
run "core: values supplied through Environment=" 0 homeease-core:core

# 7. tracking and dispatch
printf 'JWT_SECRET=%s\n' "$GOOD_JWT" > "$TMP/tr.envfile"; echo "$TMP/tr.envfile (ignore_errors=no)" > "$TMP/homeease-tracking.files"
run "tracking: JWT present" 0 homeease-tracking:tracking
printf 'REDIS_URL=x\n' > "$TMP/tr.envfile"
run "tracking: JWT missing -> abort" 1 homeease-tracking:tracking
echo "$TMP/tr.envfile (ignore_errors=no)" > "$TMP/homeease-dispatch.files"
run "dispatch: nothing required" 0 homeease-dispatch:dispatch

# 8. unknown unit / kind / no args
run "unit not found -> abort" 1 homeease-missing:core
run "unknown kind -> abort" 1 homeease-dispatch:bogus
run "no arguments -> usage" 2

# 9. several units: one bad unit fails the whole preflight
printf 'DB_USER=u\nDB_PASS=S3cr3t-Placeholder-6\nJWT_SECRET=%s\n' "$GOOD_JWT" > "$TMP/core.envfile"; echo "$TMP/core.envfile (x)" > "$TMP/homeease-core.files"; rm -f "$TMP/homeease-core.env"
run "core ok + tracking bad -> abort (nothing restarted)" 1 homeease-core:core homeease-tracking:tracking

echo; echo "preflight tests: $pass passed, $failn failed"
[ "$failn" -eq 0 ]
