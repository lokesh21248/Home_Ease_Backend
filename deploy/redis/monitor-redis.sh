#!/bin/bash
# ==============================================================================
# HOME EASE – Redis Health Monitor Script
# Run manually or schedule via cron to track Redis memory and latency.
# Usage: bash monitor-redis.sh [--alert-threshold-ms 10]
# ==============================================================================

REDIS_CLI="redis-cli"
REDIS_PASSWORD="${REDIS_PASSWORD:-}"
ALERT_LATENCY_MS="${1:-10}"   # Alert if ping latency > 10ms

auth_flags=()
if [ -n "$REDIS_PASSWORD" ]; then
    auth_flags=(-a "$REDIS_PASSWORD")
fi

echo "=============================================="
echo " HomeEase Redis Health Monitor"
echo " $(date)"
echo "=============================================="

# ── 1. Ping latency ──────────────────────────────────────────────────────────
echo ""
echo "── PING Latency ──────────────────────────────"
START_NS=$(date +%s%N)
PONG=$($REDIS_CLI "${auth_flags[@]}" PING 2>/dev/null)
END_NS=$(date +%s%N)
LATENCY_MS=$(( (END_NS - START_NS) / 1000000 ))

if [ "$PONG" == "PONG" ]; then
    echo "  ✅ Redis PING: OK (${LATENCY_MS}ms)"
    if [ "$LATENCY_MS" -gt "$ALERT_LATENCY_MS" ]; then
        echo "  ⚠️  WARNING: Latency ${LATENCY_MS}ms exceeds threshold ${ALERT_LATENCY_MS}ms"
    fi
else
    echo "  ❌ Redis PING failed!"
    exit 1
fi

# ── 2. Memory ────────────────────────────────────────────────────────────────
echo ""
echo "── Memory ────────────────────────────────────"
$REDIS_CLI "${auth_flags[@]}" INFO memory 2>/dev/null | grep -E \
    "used_memory_human|maxmemory_human|maxmemory_policy|mem_fragmentation_ratio|mem_allocator"

# ── 3. Key count and TTL check ───────────────────────────────────────────────
echo ""
echo "── Key Statistics ────────────────────────────"
TOTAL_KEYS=$($REDIS_CLI "${auth_flags[@]}" DBSIZE 2>/dev/null)
TRACKING_KEYS=$($REDIS_CLI "${auth_flags[@]}" EVAL "return #redis.call('keys','tracking:booking:*')" 0 2>/dev/null || echo "N/A")
ASSIGNMENT_KEYS=$($REDIS_CLI "${auth_flags[@]}" EVAL "return #redis.call('keys','tracking:assignment:*')" 0 2>/dev/null || echo "N/A")
echo "  Total keys:           $TOTAL_KEYS"
echo "  tracking:booking:*    $TRACKING_KEYS"
echo "  tracking:assignment:* $ASSIGNMENT_KEYS"

# ── 4. Eviction stats ────────────────────────────────────────────────────────
echo ""
echo "── Evictions & Commands ──────────────────────"
$REDIS_CLI "${auth_flags[@]}" INFO stats 2>/dev/null | grep -E \
    "evicted_keys|total_commands_processed|keyspace_hits|keyspace_misses|rejected_connections"

# ── 5. Clients ───────────────────────────────────────────────────────────────
echo ""
echo "── Connected Clients ─────────────────────────"
$REDIS_CLI "${auth_flags[@]}" INFO clients 2>/dev/null | grep -E \
    "connected_clients|blocked_clients|maxclients"

# ── 6. Slow log ──────────────────────────────────────────────────────────────
echo ""
echo "── Slow Queries (last 5) ─────────────────────"
$REDIS_CLI "${auth_flags[@]}" SLOWLOG GET 5 2>/dev/null || echo "  (slowlog unavailable)"

# ── 7. Network exposure check ────────────────────────────────────────────────
echo ""
echo "── Port Exposure Check ───────────────────────"
if ss -tlnp 2>/dev/null | grep -q '0.0.0.0:6379'; then
    echo "  ❌ DANGER: Redis port 6379 is exposed on 0.0.0.0!"
    echo "     Fix: set 'bind 127.0.0.1 <private_ip>' in /etc/redis/redis.conf"
else
    echo "  ✅ Redis is NOT exposed on 0.0.0.0 (safe)"
fi

echo ""
echo "=============================================="
echo " Monitor complete at $(date)"
echo "=============================================="

