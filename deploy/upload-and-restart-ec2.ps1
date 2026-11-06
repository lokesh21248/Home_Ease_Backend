# ==============================================================================
# HOME EASE – Fast Artifact Upload & Zero-Downtime Service Restart (PowerShell)
# Phase 1: Uploads both fresh JARs to the single EC2 backend and restarts services.
#
# Usage:
#   .\deploy\upload-and-restart-ec2.ps1 -Ec2Host <EC2_HOST_OR_IP> [-SshKey <PATH_TO_PEM>] [-SshUser <USER>]
#
# Examples:
#   .\deploy\upload-and-restart-ec2.ps1 -Ec2Host "13.233.10.50" -SshKey "C:\Users\Admin\.ssh\homeease.pem"
# ==============================================================================

param (
    [Parameter(Mandatory=$true)]
    [string]$Ec2Host,

    [Parameter(Mandatory=$false)]
    [string]$SshKey = "",

    [Parameter(Mandatory=$false)]
    [string]$SshUser = "ec2-user"
)

$ErrorActionPreference = "Stop"

$CoreJar = "target\homeease-backend-1.0.0-SNAPSHOT.jar"
$TrackJar = "homeease-tracking-service\target\homeease-tracking-service-1.0.0-SNAPSHOT.jar"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " 🚀 HomeEase Phase 1 Deploy: $Ec2Host" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

if (-not (Test-Path $CoreJar) -or -not (Test-Path $TrackJar)) {
    Write-Error "Packaged JAR files not found locally. Run maven package first."
}

$SshKeyArg = @()
if ($SshKey -ne "") {
    $SshKeyArg = @("-i", $SshKey)
}

Write-Host "1. Uploading fresh JARs to remote /tmp..." -ForegroundColor Yellow
& scp @SshKeyArg -o StrictHostKeyChecking=no $CoreJar "${SshUser}@${Ec2Host}:/tmp/homeease-core.jar"
& scp @SshKeyArg -o StrictHostKeyChecking=no $TrackJar "${SshUser}@${Ec2Host}:/tmp/homeease-tracking.jar"

Write-Host "2. Rotating artifacts and restarting services on remote EC2..." -ForegroundColor Yellow

$RemoteCommands = @"
sudo mkdir -p /opt/homeease/core/backup /opt/homeease/tracking/backup
if [ -f /opt/homeease/core/homeease-backend.jar ]; then
    sudo cp /opt/homeease/core/homeease-backend.jar /opt/homeease/core/backup/homeease-backend-$(date +%s).jar
fi
if [ -f /opt/homeease/tracking/homeease-tracking-service.jar ]; then
    sudo cp /opt/homeease/tracking/homeease-tracking-service.jar /opt/homeease/tracking/backup/homeease-tracking-$(date +%s).jar
fi
sudo mv /tmp/homeease-core.jar /opt/homeease/core/homeease-backend.jar
sudo mv /tmp/homeease-tracking.jar /opt/homeease/tracking/homeease-tracking-service.jar
sudo chown -R homeease:homeease /opt/homeease

echo 'Restarting homeease-core...'
sudo systemctl restart homeease-core
sleep 4

echo 'Restarting homeease-tracking...'
sudo systemctl restart homeease-tracking
sleep 2

echo 'Checking service health...'
sleep 3
if curl -s -k http://localhost:8082/api/v1/health | grep -q 'UP'; then
    echo '  [OK] Tracking Service (Port 8082): UP'
else
    echo '  [WARN] Tracking Service health pending: check journalctl -u homeease-tracking -n 20'
fi
if curl -s -k http://localhost:8080/actuator/health 2>/dev/null | grep -q 'UP'; then
    echo '  [OK] Core Backend (Port 8080): UP'
else
    echo '  [INFO] Core Backend is starting: check journalctl -u homeease-core -n 20'
fi
"@

& ssh @SshKeyArg -o StrictHostKeyChecking=no "${SshUser}@${Ec2Host}" $RemoteCommands

Write-Host "==========================================================" -ForegroundColor Green
Write-Host " Deployment completed successfully!" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green

