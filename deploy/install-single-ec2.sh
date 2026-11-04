#!/bin/bash
# ==============================================================================
# HOME EASE – Single-EC2 Installation Script
# Installs Nginx + configures Redis + sets up systemd services on ONE EC2.
#
# Usage (run as root or with sudo):
#   sudo bash deploy/install-single-ec2.sh
#
# The script is IDEMPOTENT – safe to re-run after a failure.
# ==============================================================================

set -euo pipefail

# ── Configurable variables ────────────────────────────────────────────────────
# Override via environment before calling the script:
#   sudo DOMAIN=api.yourapp.com bash deploy/install-single-ec2.sh
DOMAIN="${DOMAIN:-}"                        # leave empty to skip certbot
REDIS_PASSWORD="${REDIS_PASSWORD:-}"        # REQUIRED – set before running
SPRING_BOOT_CORE_PORT="${SPRING_BOOT_CORE_PORT:-8080}"
SPRING_BOOT_TRACK_PORT="${SPRING_BOOT_TRACK_PORT:-8082}"

# ── Pre-flight checks ─────────────────────────────────────────────────────────
echo ""
echo "======================================================"
echo " HomeEase Single-EC2 Installer"
echo " $(date)"
echo "======================================================"

if [ "$EUID" -ne 0 ]; then
    echo "ERROR: Run with sudo." ; exit 1
fi

if [ -z "$REDIS_PASSWORD" ]; then
    echo "ERROR: Set REDIS_PASSWORD before running."
    echo "  export REDIS_PASSWORD=\$(openssl rand -base64 32)"
    exit 1
fi

# ── Detect OS ─────────────────────────────────────────────────────────────────
if command -v apt-get &>/dev/null; then PKG_MGR="apt" ; else PKG_MGR="dnf" ; fi

# ── Detect instance RAM (MB) for Redis memory limit calculation ───────────────
TOTAL_RAM_MB=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
echo ""
echo "── System Resources ──────────────────────────────────"
echo "  Total RAM      : ${TOTAL_RAM_MB} MB"
nproc_count=$(nproc)
echo "  CPU cores      : $nproc_count"
echo ""

# ==============================================================================
# REDIS MEMORY LIMIT CALCULATION
# Reserve memory for:
#   OS + kernel      : 300 MB  (conservative, includes filesystem cache)
#   Spring Boot Core : 512 MB  (JVM heap + native; tuned below)
#   Tracking Service : 256 MB  (lighter service on same box)
#   Nginx            : 50  MB  (worker memory + SSL session cache)
#   Headroom buffer  : 100 MB  (GC pressure, peak bursts)
# Redis gets the remainder, capped at 25% of total RAM.
# ==============================================================================
RESERVED_MB=1218    # 300 + 512 + 256 + 50 + 100
REDIS_TWENTY_FIVE_PCT=$(( TOTAL_RAM_MB / 4 ))
REDIS_REMAINDER=$(( TOTAL_RAM_MB - RESERVED_MB ))
# Redis gets the smaller of: remaining headroom, 25% of RAM
if [ "$REDIS_REMAINDER" -lt "$REDIS_TWENTY_FIVE_PCT" ]; then
    REDIS_MAX_MB=$REDIS_REMAINDER
else
    REDIS_MAX_MB=$REDIS_TWENTY_FIVE_PCT
fi
# Floor at 128 MB to avoid starving Redis; cap at 2048 MB
[ "$REDIS_MAX_MB" -lt 128  ] && REDIS_MAX_MB=128
[ "$REDIS_MAX_MB" -gt 2048 ] && REDIS_MAX_MB=2048

echo "── Redis Memory Budget ───────────────────────────────"
echo "  Reserved for OS+JVM+Nginx: ${RESERVED_MB} MB"
echo "  25% of total RAM          : ${REDIS_TWENTY_FIVE_PCT} MB"
echo "  Calculated Redis maxmemory: ${REDIS_MAX_MB} MB"
echo ""

# ── Determine JVM heap for the two Spring Boot services ──────────────────────
# Core backend: needs more heap for Hibernate L1 cache, Flyway, Kafka consumer
# Tracking service: lightweight; mostly Redis calls + WebSocket broadcast
if [ "$TOTAL_RAM_MB" -le 2048 ]; then
    CORE_HEAP_MIN=256 ; CORE_HEAP_MAX=512
    TRACK_HEAP_MIN=128 ; TRACK_HEAP_MAX=256
elif [ "$TOTAL_RAM_MB" -le 4096 ]; then
    CORE_HEAP_MIN=384 ; CORE_HEAP_MAX=768
    TRACK_HEAP_MIN=192 ; TRACK_HEAP_MAX=384
else
    CORE_HEAP_MIN=512 ; CORE_HEAP_MAX=1024
    TRACK_HEAP_MIN=256 ; TRACK_HEAP_MAX=512
fi

echo "── JVM Heap Allocation ───────────────────────────────"
echo "  Core service    : ${CORE_HEAP_MIN}m – ${CORE_HEAP_MAX}m"
echo "  Tracking service: ${TRACK_HEAP_MIN}m – ${TRACK_HEAP_MAX}m"
echo ""

# ══════════════════════════════════════════════════════════════════════════════
# STEP 1 – Install packages
# ══════════════════════════════════════════════════════════════════════════════
echo "── Installing packages ───────────────────────────────"
if [ "$PKG_MGR" = "apt" ]; then
    apt-get update -y -q
    apt-get install -y -q nginx redis-server openssl certbot python3-certbot-nginx logrotate
else
    dnf install -y nginx redis openssl certbot python3-certbot-nginx logrotate
fi
echo "  ✅ Packages installed."

# ══════════════════════════════════════════════════════════════════════════════
# STEP 2 – Configure Redis
# ══════════════════════════════════════════════════════════════════════════════
echo ""
echo "── Configuring Redis ─────────────────────────────────"

# Disable Transparent Huge Pages (required for Redis latency)
echo never > /sys/kernel/mm/transparent_hugepage/enabled 2>/dev/null || true
echo never > /sys/kernel/mm/transparent_hugepage/defrag  2>/dev/null || true

cat > /etc/systemd/system/disable-thp.service <<'THP'
[Unit]
Description=Disable Transparent Huge Pages
DefaultDependencies=no
After=sysinit.target local-fs.target
Before=redis.service

[Service]
Type=oneshot
ExecStart=/bin/sh -c 'echo never > /sys/kernel/mm/transparent_hugepage/enabled'
ExecStart=/bin/sh -c 'echo never > /sys/kernel/mm/transparent_hugepage/defrag'

[Install]
WantedBy=multi-user.target
THP
systemctl daemon-reload
systemctl enable --now disable-thp.service

# vm.overcommit_memory=1 prevents Redis fork from failing under memory pressure
sysctl -w vm.overcommit_memory=1 >/dev/null
grep -qxF 'vm.overcommit_memory=1' /etc/sysctl.conf || \
    echo 'vm.overcommit_memory=1' >> /etc/sysctl.conf

# Back up original Redis config
REDIS_CONF=/etc/redis/redis.conf
[ -f "${REDIS_CONF}.bak" ] || cp "$REDIS_CONF" "${REDIS_CONF}.bak"

# Private IP detection (used for bind directive)
PRIVATE_IP=$(hostname -I | awk '{print $1}')

cat > "$REDIS_CONF" <<REDIS
# HomeEase Redis – Single-EC2 Configuration
# Generated by install-single-ec2.sh at $(date)
# DO NOT bind to 0.0.0.0

bind 127.0.0.1 ${PRIVATE_IP}
protected-mode yes
port 6379
tcp-backlog 256
tcp-keepalive 300
tcp-nodelay yes
timeout 0

requirepass ${REDIS_PASSWORD}
rename-command FLUSHALL   ""
rename-command FLUSHDB    ""
rename-command DEBUG      ""

maxmemory ${REDIS_MAX_MB}mb
maxmemory-policy allkeys-lru
maxclients 1024

# Persistence
save 900 1
save 300 10
save 60  1000
appendonly yes
appendfilename "homeease.aof"
appendfsync everysec
no-appendfsync-on-rewrite no
auto-aof-rewrite-percentage 100
auto-aof-rewrite-min-size 32mb
dir /var/lib/redis
dbfilename homeease.rdb

# Latency monitoring
slowlog-log-slower-than 10000
slowlog-max-len 128
latency-monitor-threshold 25
latency-tracking yes

lazyfree-lazy-eviction yes
lazyfree-lazy-expire yes
lazyfree-lazy-server-del yes

loglevel notice
logfile /var/log/redis/redis-server.log
REDIS

mkdir -p /var/log/redis
chown redis:redis /var/log/redis 2>/dev/null || true
systemctl enable redis
systemctl restart redis
sleep 2

if redis-cli -a "$REDIS_PASSWORD" PING 2>/dev/null | grep -q PONG; then
    echo "  ✅ Redis running and authenticated."
else
    echo "  ❌ Redis failed – check /var/log/redis/redis-server.log"
    journalctl -u redis --no-pager -n 20
    exit 1
fi

# Verify NOT on 0.0.0.0
if ss -tlnp 2>/dev/null | grep -qE '0\.0\.0\.0:6379|:::6379'; then
    echo "  ❌ DANGER: Redis is exposed on 0.0.0.0. Aborting."
    exit 1
else
    echo "  ✅ Redis NOT exposed publicly."
fi

# ══════════════════════════════════════════════════════════════════════════════
# STEP 3 – Generate a self-signed TLS cert (replace later with Let's Encrypt)
# ══════════════════════════════════════════════════════════════════════════════
echo ""
echo "── TLS Certificate ───────────────────────────────────"
mkdir -p /etc/nginx/ssl
if [ ! -f /etc/nginx/ssl/homeease-selfsigned.crt ]; then
    openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
        -keyout /etc/nginx/ssl/homeease-selfsigned.key \
        -out    /etc/nginx/ssl/homeease-selfsigned.crt \
        -subj   "/CN=homeease-local/O=HomeEase/C=IN" \
        -quiet
    echo "  ✅ Self-signed TLS certificate created."
else
    echo "  ℹ️  Existing TLS certificate kept."
fi

# ══════════════════════════════════════════════════════════════════════════════
# STEP 4 – Install Nginx configuration
# ══════════════════════════════════════════════════════════════════════════════
echo ""
echo "── Installing Nginx configuration ────────────────────"
mkdir -p /var/www/certbot

# Remove any existing default site that conflicts
rm -f /etc/nginx/sites-enabled/default
rm -f /etc/nginx/conf.d/default.conf

# Install our configurations (from repository)
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cp "${REPO_DIR}/nginx/nginx.conf"                        /etc/nginx/nginx.conf
cp "${REPO_DIR}/nginx/conf.d/homeease_tracking.conf"    /etc/nginx/conf.d/homeease.conf

nginx -t
echo "  ✅ Nginx configuration is valid."
systemctl enable nginx
systemctl reload nginx || systemctl start nginx
echo "  ✅ Nginx started."

# ══════════════════════════════════════════════════════════════════════════════
# STEP 5 – Obtain Let's Encrypt TLS certificate (optional)
# ══════════════════════════════════════════════════════════════════════════════
if [ -n "$DOMAIN" ]; then
    echo ""
    echo "── Obtaining Let's Encrypt certificate for $DOMAIN ──"
    certbot --nginx -d "$DOMAIN" \
        --non-interactive --agree-tos \
        --email ops@homeease.in --redirect 2>&1 | tail -5
    echo "  ✅ Let's Encrypt certificate installed."
    # Reload nginx to pick up real cert
    systemctl reload nginx
fi

# ══════════════════════════════════════════════════════════════════════════════
# STEP 6 – Create homeease OS user and directories
# ══════════════════════════════════════════════════════════════════════════════
echo ""
echo "── Creating homeease user and directories ────────────"
id homeease &>/dev/null || useradd -r -s /bin/false -d /opt/homeease homeease
mkdir -p /opt/homeease/core /opt/homeease/tracking /var/log/homeease /etc/homeease
chown -R homeease:homeease /opt/homeease /var/log/homeease
chmod 700 /etc/homeease
echo "  ✅ Done."

# ══════════════════════════════════════════════════════════════════════════════
# STEP 7 – Install systemd service files
# ══════════════════════════════════════════════════════════════════════════════
echo ""
echo "── Installing systemd services ───────────────────────"

# Generate the core service file (not in repo – generated fresh with correct heap)
cat > /etc/systemd/system/homeease-core.service <<SERVICE
[Unit]
Description=HomeEase Core Backend (Port ${SPRING_BOOT_CORE_PORT})
After=network.target redis.service
Wants=redis.service

[Service]
Type=simple
User=homeease
Group=homeease
WorkingDirectory=/opt/homeease/core
EnvironmentFile=/etc/homeease/app.env
Environment=SPRING_PROFILES_ACTIVE=prod
Environment=REDIS_HOST=127.0.0.1
Environment=REDIS_PORT=6379
Environment="JAVA_OPTS=-Xms${CORE_HEAP_MIN}m -Xmx${CORE_HEAP_MAX}m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/homeease -Djava.security.egd=file:/dev/./urandom -Dspring.profiles.active=prod"
ExecStart=/usr/bin/java \$JAVA_OPTS -jar /opt/homeease/core/homeease-backend.jar
TimeoutStopSec=30
KillMode=mixed
KillSignal=SIGTERM
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal
SyslogIdentifier=homeease-core
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=strict
ReadWritePaths=/var/log/homeease /opt/homeease/core
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
SERVICE

# Update tracking service with correct heap
cp "${REPO_DIR}/deploy/systemd/homeease-tracking.service" \
   /etc/systemd/system/homeease-tracking.service

# Patch heap values in the tracking service file
sed -i "s/-Xms256m -Xmx512m/-Xms${TRACK_HEAP_MIN}m -Xmx${TRACK_HEAP_MAX}m/" \
    /etc/systemd/system/homeease-tracking.service

# Create /etc/homeease/app.env from example if it does not exist
if [ ! -f /etc/homeease/app.env ]; then
    cp "${REPO_DIR}/deploy/systemd/tracking.env.example" /etc/homeease/app.env
    echo "REDIS_PASSWORD=${REDIS_PASSWORD}" >> /etc/homeease/app.env
    chmod 600 /etc/homeease/app.env
    chown homeease:homeease /etc/homeease/app.env
    echo "  ⚠️  Edit /etc/homeease/app.env and set JWT_SECRET + GOOGLE_MAPS_API_KEY."
fi

systemctl daemon-reload
systemctl enable homeease-core homeease-tracking
echo "  ✅ Systemd services installed (not started yet – deploy JARs first)."

# ══════════════════════════════════════════════════════════════════════════════
# STEP 8 – Logrotate for Nginx
# ══════════════════════════════════════════════════════════════════════════════
cat > /etc/logrotate.d/homeease-nginx <<'LOGROTATE'
/var/log/nginx/*.log {
    daily
    rotate 14
    compress
    delaycompress
    missingok
    notifempty
    sharedscripts
    postrotate
        [ -f /run/nginx.pid ] && kill -USR1 $(cat /run/nginx.pid) || true
    endscript
}
LOGROTATE

echo ""
echo "======================================================"
echo " ✅ Installation complete."
echo "======================================================"
echo ""
echo " Next steps:"
echo "   1. Upload JARs:"
echo "      scp target/homeease-backend.jar           ec2:<ip>:/opt/homeease/core/"
echo "      scp target/homeease-tracking-service.jar  ec2:<ip>:/opt/homeease/tracking/"
echo "   2. Set secrets in /etc/homeease/app.env"
echo "   3. Start services:"
echo "      sudo systemctl start homeease-core homeease-tracking"
echo "   4. Verify:"
echo "      curl http://localhost:8080/actuator/health"
echo "      curl http://localhost:8082/api/v1/health"
echo "      curl https://localhost/healthz -k"
echo ""

