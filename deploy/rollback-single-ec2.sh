#!/bin/bash
# ==============================================================================
# HOME EASE – Rollback Script for Single-EC2 Deployment
# Safely reverts Nginx, Redis, and Systemd configurations to their prior state
#
# Usage (run with sudo):
#   sudo bash deploy/rollback-single-ec2.sh
# ==============================================================================

set -euo pipefail

echo "=============================================="
echo " HomeEase Single-EC2 Deployment Rollback"
echo " Started at: $(date)"
echo "=============================================="

if [ "$EUID" -ne 0 ]; then
    echo "ERROR: This script must be run as root or with sudo."
    exit 1
fi

# ── 1. Stop and Disable Services ──────────────────────────────────────────────
echo ""
echo "── Step 1: Stopping HomeEase Services ────────"
systemctl stop homeease-tracking || true
systemctl stop homeease-core || true
systemctl disable homeease-tracking || true
systemctl disable homeease-core || true
echo "  ✅ Application services stopped and disabled."

# ── 2. Restore Redis Configuration ────────────────────────────────────────────
echo ""
echo "── Step 2: Restoring Redis Configuration ─────"
if [ -f /etc/redis/redis.conf.bak ]; then
    echo "  Restoring original /etc/redis/redis.conf from backup..."
    cp -f /etc/redis/redis.conf.bak /etc/redis/redis.conf
    systemctl restart redis || true
    echo "  ✅ Redis configuration restored from backup."
else
    echo "  ⚠️ No /etc/redis/redis.conf.bak found. Resetting Redis bind to localhost only."
    sed -i 's/^bind .*/bind 127.0.0.1/' /etc/redis/redis.conf || true
    systemctl restart redis || true
fi

# Disable disable-thp service if created
if [ -f /etc/systemd/system/disable-thp.service ]; then
    systemctl stop disable-thp.service || true
    systemctl disable disable-thp.service || true
    rm -f /etc/systemd/system/disable-thp.service
fi

# ── 3. Restore Nginx Configuration ────────────────────────────────────────────
echo ""
echo "── Step 3: Restoring Nginx Configuration ─────"
rm -f /etc/nginx/conf.d/homeease.conf
rm -f /etc/logrotate.d/homeease-nginx

# Restore default Nginx sites if they were backed up
if [ -f /etc/nginx/nginx.conf.bak ]; then
    cp -f /etc/nginx/nginx.conf.bak /etc/nginx/nginx.conf
fi

# Test and reload or stop Nginx
if nginx -t 2>/dev/null; then
    systemctl reload nginx || true
    echo "  ✅ Nginx reloaded with baseline configuration."
else
    echo "  ⚠️ Nginx configuration test failed; stopping Nginx."
    systemctl stop nginx || true
fi

# ── 4. Remove Systemd Service Units ───────────────────────────────────────────
echo ""
echo "── Step 4: Cleaning Systemd Service Units ────"
rm -f /etc/systemd/system/homeease-core.service
rm -f /etc/systemd/system/homeease-tracking.service
systemctl daemon-reload
echo "  ✅ Systemd service definitions removed and daemon reloaded."

echo ""
echo "=============================================="
echo " ✅ Rollback Complete. All configurations reverted."
echo "=============================================="
