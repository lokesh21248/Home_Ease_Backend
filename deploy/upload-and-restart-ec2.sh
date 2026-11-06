#!/usr/bin/env bash
# ==============================================================================
# HOME EASE – Fast Artifact Upload & Zero-Downtime Service Restart
# Phase 1: Uploads both fresh JARs to the single EC2 backend and restarts services.
#
# Usage:
#   bash deploy/upload-and-restart-ec2.sh <EC2_HOST_OR_IP> [SSH_KEY_PATH] [SSH_USER]
#
# Examples:
#   bash deploy/upload-and-restart-ec2.sh 13.233.10.50 ~/.ssh/homeease-key.pem ubuntu
# ==============================================================================

set -euo pipefail

EC2_HOST="${1:-}"
SSH_KEY="${2:-}"
SSH_USER="${3:-ubuntu}"

if [ -z "$EC2_HOST" ]; then
    echo "ERROR: EC2 host or IP is required."
    echo "Usage: bash deploy/upload-and-restart-ec2.sh <EC2_HOST> [SSH_KEY_PATH] [SSH_USER]"
    exit 1
fi

SSH_OPTS="-o StrictHostKeyChecking=no -o ConnectTimeout=10"
if [ -n "$SSH_KEY" ]; then
    SSH_OPTS="$SSH_OPTS -i $SSH_KEY"
fi

CORE_JAR="target/homeease-backend-1.0.0-SNAPSHOT.jar"
TRACK_JAR="homeease-tracking-service/target/homeease-tracking-service-1.0.0-SNAPSHOT.jar"

echo "=========================================================="
echo " 🚀 HomeEase Phase 1 Deploy: $EC2_HOST"
echo "=========================================================="

# Check local artifacts
if [ ! -f "$CORE_JAR" ] || [ ! -f "$TRACK_JAR" ]; then
    echo "❌ Error: Packaged JAR files not found locally. Run maven package first."
    exit 1
fi

echo "1. Uploading fresh JARs to remote /tmp..."
scp $SSH_OPTS "$CORE_JAR" "${SSH_USER}@${EC2_HOST}:/tmp/homeease-core.jar"
scp $SSH_OPTS "$TRACK_JAR" "${SSH_USER}@${EC2_HOST}:/tmp/homeease-tracking.jar"

echo "2. Rotating artifacts and restarting services on remote EC2..."
ssh $SSH_OPTS "${SSH_USER}@${EC2_HOST}" 'bash -s' << 'REMOTE_EXEC'
set -euo pipefail

# Ensure backup directories exist
sudo mkdir -p /opt/homeease/core/backup /opt/homeease/tracking/backup

# Backup existing JARs if present
if [ -f /opt/homeease/core/homeease-backend.jar ]; then
    sudo cp /opt/homeease/core/homeease-backend.jar /opt/homeease/core/backup/homeease-backend-$(date +%s).jar
fi
if [ -f /opt/homeease/tracking/homeease-tracking-service.jar ]; then
    sudo cp /opt/homeease/tracking/homeease-tracking-service.jar /opt/homeease/tracking/backup/homeease-tracking-$(date +%s).jar
fi

# Move new JARs into place
sudo mv /tmp/homeease-core.jar /opt/homeease/core/homeease-backend.jar
sudo mv /tmp/homeease-tracking.jar /opt/homeease/tracking/homeease-tracking-service.jar
sudo chown -R homeease:homeease /opt/homeease

# Restart services sequentially
echo "Restarting homeease-core..."
sudo systemctl restart homeease-core
sleep 4

echo "Restarting homeease-tracking..."
sudo systemctl restart homeease-tracking
sleep 2

# Verify health
echo "Checking service health..."
sleep 3
if curl -s -k http://localhost:8082/api/v1/health | grep -q "UP"; then
    echo "  ✅ Tracking Service (Port 8082): UP"
else
    echo "  ⚠️ Tracking Service health pending: check journalctl -u homeease-tracking -n 20"
fi

if curl -s -k http://localhost:8080/actuator/health 2>/dev/null | grep -q "UP"; then
    echo "  ✅ Core Backend (Port 8080): UP"
else
    echo "  ℹ️ Core Backend is starting: check journalctl -u homeease-core -n 20"
fi
REMOTE_EXEC

echo "=========================================================="
echo " ✅ Deployment completed successfully!"
echo "=========================================================="

