#!/usr/bin/env bash
# Pre-flight for deployments: verifies, BEFORE anything is built, copied or restarted, that every service about to be
# restarted has the secrets the new build REQUIRES at startup. If any is missing the script exits non-zero, the
# calling deploy aborts, and the currently running services are left untouched (instead of being restarted into a
# jar that refuses to start).
#
# Why it exists: the build refuses to start without DB_USER / DB_PASS (or SPRING_DATASOURCE_USERNAME / _PASSWORD) and
# JWT_SECRET, and refuses the exact values that were once committed to Git. Earlier builds fell back to those
# committed values silently, so a server may never have had these variables set.
#
# It reads the units' EnvironmentFile= and Environment= through systemd, checks KEYS and LENGTHS only, and never
# prints a value. Compromised values are detected by SHA-256 (the values themselves are not in this script).
#
# Usage:   deploy/preflight-env.sh <unit>:<kind> [<unit>:<kind> ...]
#          kind = core    (needs DB user + DB password + JWT_SECRET)
#                 tracking (needs JWT_SECRET)
#                 dispatch (needs nothing beyond REDIS settings)
# Example: deploy/preflight-env.sh homeease-core:core homeease-tracking:tracking
#
# Env: SUDO (default "sudo"; set SUDO="" when already root or in tests), SYSTEMCTL (default systemctl).
set -u
SUDO="${SUDO-sudo}"
SYSTEMCTL="${SYSTEMCTL:-systemctl}"
# SHA-256 of values that were committed to Git history (database password, JWT secret). Hashes only.
COMPROMISED="da3264b2e5f77fe87c5ae5eb6ee1e8e66e9cf589aab86df74a2341a526d8f463 3354cfcf65c7b2e6840ea6f880aede239750cb1d063950c4e8fd9fbf9ec73a32"

fail=0
problem() { printf '  [FAIL] %s\n' "$*"; fail=1; }
ok()      { printf '  [ OK ] %s\n' "$*"; }

# value of KEY from the unit's environment (EnvironmentFile first, then Environment=). Prints the value; callers
# must never echo it.
unit_value() { # unit key
  local unit="$1" key="$2" f v
  for f in $($SYSTEMCTL show -p EnvironmentFiles --value "$unit" 2>/dev/null | tr ' ' '\n' | grep '^/' ); do
    v=$($SUDO grep -h "^${key}=" "$f" 2>/dev/null | tail -n1 | cut -d= -f2-)
    [ -n "$v" ] && { printf '%s' "$v"; return 0; }
  done
  v=$($SYSTEMCTL show -p Environment --value "$unit" 2>/dev/null | tr ' ' '\n' | grep "^${key}=" | tail -n1 | cut -d= -f2-)
  [ -n "$v" ] && { printf '%s' "$v"; return 0; }
  return 1
}

hash_of() { printf '%s' "$1" | sha256sum | cut -d' ' -f1; }

check_secret() { # unit label min_len value
  local unit="$1" label="$2" min="$3" val="$4"
  if [ -z "$val" ]; then problem "$unit: $label is not set in its EnvironmentFile/Environment"; return; fi
  if [ "${#val}" -lt "$min" ]; then problem "$unit: $label is too short (${#val} < $min characters)"; return; fi
  local h; h=$(hash_of "$val")
  case " $COMPROMISED " in *" $h "*) problem "$unit: $label is a value that was committed to Git history; set a NEW one"; return ;; esac
  ok "$unit: $label is set (${#val} characters, not a known-leaked value)"
}

if [ "$#" -eq 0 ]; then echo "usage: $0 <unit>:<core|tracking|dispatch> ..."; exit 2; fi

for spec in "$@"; do
  unit="${spec%%:*}"; kind="${spec##*:}"
  echo "== $unit ($kind)"
  if ! $SYSTEMCTL cat "$unit" >/dev/null 2>&1; then problem "$unit: unit not found by systemd"; continue; fi
  case "$kind" in
    core)
      user=$(unit_value "$unit" DB_USER || unit_value "$unit" SPRING_DATASOURCE_USERNAME || true)
      pass=$(unit_value "$unit" DB_PASS || unit_value "$unit" SPRING_DATASOURCE_PASSWORD || true)
      jwt=$(unit_value "$unit" JWT_SECRET || true)
      check_secret "$unit" "DB_USER (or SPRING_DATASOURCE_USERNAME)" 1 "$user"
      check_secret "$unit" "DB_PASS (or SPRING_DATASOURCE_PASSWORD)" 1 "$pass"
      check_secret "$unit" "JWT_SECRET" 32 "$jwt"
      ;;
    tracking)
      jwt=$(unit_value "$unit" JWT_SECRET || true)
      check_secret "$unit" "JWT_SECRET" 32 "$jwt"
      ;;
    dispatch) ok "$unit: no startup secrets required by the build" ;;
    *) problem "$unit: unknown kind '$kind'" ;;
  esac
done

if [ "$fail" -ne 0 ]; then
  echo
  echo "PRE-FLIGHT FAILED: nothing was changed. Set the missing variables in the services' EnvironmentFile, then redeploy."
  exit 1
fi
echo
echo "PRE-FLIGHT OK"
