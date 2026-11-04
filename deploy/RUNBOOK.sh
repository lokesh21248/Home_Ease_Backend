#!/bin/bash
# ==============================================================================
# HOME EASE – Phase 1 Deployment Runbook
# Full step-by-step deployment of Option B:
#   Two Nginx EC2 nodes + existing backend EC2 + co-located Redis
# ==============================================================================

set -euo pipefail

# ── Deployment Checklist ─────────────────────────────────────────────────────
echo "
======================================================================
 HomeEase Phase 1 Deployment Runbook
 Target: 1,000 Concurrent Live Tracking Sessions
 Infrastructure: Nginx × 2 AZ + Backend EC2 + Redis (co-located)
======================================================================

STEP 1 ─ BACKEND EC2 (existing)
─────────────────────────────────────────────────────────────────────
1a. Install Redis (run as sudo):
    export REDIS_PASSWORD='<your-strong-password>'
    export BACKEND_PRIVATE_IP='10.0.1.20'   # your EC2 private IP
    export REDIS_MAXMEMORY='400mb'           # 400mb for t4g.small, 800mb for t4g.medium
    bash deploy/redis/install-redis.sh

1b. Create homeease OS user and directories:
    useradd -r -s /bin/false homeease
    mkdir -p /opt/homeease/tracking /var/log/homeease /etc/homeease
    chown homeease:homeease /opt/homeease/tracking /var/log/homeease

1c. Upload JAR to backend EC2:
    scp homeease-tracking-service/target/homeease-tracking-service-1.0.0-SNAPSHOT.jar \\
        ec2-user@<backend-ip>:/opt/homeease/tracking/homeease-tracking-service.jar

1d. Create /etc/homeease/tracking.env (copy deploy/systemd/tracking.env.example):
    cp deploy/systemd/tracking.env.example /etc/homeease/tracking.env
    # Edit and fill in: REDIS_PASSWORD, JWT_SECRET, GOOGLE_MAPS_API_KEY
    chmod 600 /etc/homeease/tracking.env
    chown homeease:homeease /etc/homeease/tracking.env

1e. Install systemd service:
    cp deploy/systemd/homeease-tracking.service /etc/systemd/system/
    systemctl daemon-reload
    systemctl enable homeease-tracking
    systemctl start homeease-tracking

1f. Verify service is running:
    systemctl status homeease-tracking
    curl http://localhost:8082/api/v1/health
    # Expected: { \"status\": \"UP\" }

    curl http://localhost:8082/api/v1/health/detail
    # Expected: { \"status\": \"UP\", \"redis\": { \"status\": \"UP\", \"pingLatencyMs\": <1 } }

STEP 2 ─ NGINX NODE 1 (AZ-1a)
─────────────────────────────────────────────────────────────────────
2a. Launch EC2:
    Instance type: t4g.small (2 vCPU, 2 GB RAM) or t3.micro for minimal traffic
    AMI: Ubuntu 22.04 LTS (ARM for t4g) or Amazon Linux 2023
    Security Group: allow 80, 443 from 0.0.0.0/0, port 22 from your IP only

2b. Install Nginx and obtain TLS certificate:
    export BACKEND_PRIVATE_IP='10.0.1.20'
    export DOMAIN='api.homeease.com'
    export TRACKING_DOMAIN='tracking.homeease.com'
    bash deploy/nginx/install-nginx.sh

2c. Verify Nginx is serving:
    curl -k https://<nginx-az1-ip>/healthz
    # Expected: OK
    curl -k https://<nginx-az1-ip>/api/v1/health
    # Expected: proxied to backend, { \"status\": \"UP\" }

STEP 3 ─ NGINX NODE 2 (AZ-1b)
─────────────────────────────────────────────────────────────────────
3a. Launch a second EC2 in a different AZ (us-east-1b if Node 1 is us-east-1a).

3b. Run the same install-nginx.sh script.

3c. Verify independently:
    curl -k https://<nginx-az2-ip>/healthz   # OK
    curl -k https://<nginx-az2-ip>/api/v1/health/detail

STEP 4 ─ AWS ELASTIC IPs
─────────────────────────────────────────────────────────────────────
4a. EC2 → Elastic IPs → Allocate 2 EIPs
4b. Associate EIP-1 to Nginx Node 1, EIP-2 to Nginx Node 2
4c. Note the EIP addresses for Route 53

STEP 5 ─ AWS ROUTE 53 DNS FAILOVER
─────────────────────────────────────────────────────────────────────
Follow: deploy/dns/route53-dns-failover-guide.conf

Summary:
  - Create 2 health checks (one per Nginx node) polling /healthz on port 443
  - Create 2 weighted A records (weight 50/50) for api.homeease.com
  - TTL: 60 seconds (failover within 1 TTL = 60s)
  - Create CNAME: tracking.homeease.com → api.homeease.com

STEP 6 ─ VERIFY END-TO-END
─────────────────────────────────────────────────────────────────────
6a. DNS resolution:
    nslookup api.homeease.com
    # Should return both Nginx EIPs (alternating)

6b. HTTPS health:
    curl https://api.homeease.com/healthz       # Nginx-level, no backend
    curl https://api.homeease.com/api/v1/health # Backend-level
    curl https://api.homeease.com/api/v1/health/detail  # Redis health

6c. GPS ingestion smoke test:
    curl -X POST https://api.homeease.com/api/v1/tracking/partner/location \\
      -H 'Content-Type: application/json' \\
      -H 'X-User-Id: worker-001' \\
      -d '{\"bookingId\":\"BK-SMOKE\",\"lat\":17.4485,\"lng\":78.3748,\"bearing\":90,\"speed\":5.0,\"accuracy\":8.0,\"timestamp\":'$(date +%s000)'}'
    # Expected: { \"status\": \"SUCCESS\" }

6d. Redis key verification (on backend EC2):
    redis-cli -a \$REDIS_PASSWORD GET 'tracking:booking:BK-SMOKE'
    # Expected: JSON payload with lat/lng

STEP 7 ─ LOAD TEST (Optional)
─────────────────────────────────────────────────────────────────────
# Install k6: https://k6.io/docs/getting-started/installation
npm install -g k6   # or brew install k6 on Mac

# Run staged capacity benchmark
TRACKING_URL=https://api.homeease.com \\
TEST_JWT='Bearer <real-test-worker-jwt>' \\
k6 run tests/load/phase1-capacity-test.js

# Expected results at 1,000 sessions:
#   gps_p99_latency_ms p(99) < 150ms
#   gps_success_rate   > 99.5%
#   http_req_failed    < 0.5%

STEP 8 ─ MONITOR REDIS
─────────────────────────────────────────────────────────────────────
# Run on backend EC2 to check Redis health and memory:
bash deploy/redis/monitor-redis.sh

# Schedule every 5 minutes:
echo '*/5 * * * * homeease bash /opt/homeease/deploy/redis/monitor-redis.sh >> /var/log/homeease/redis-monitor.log 2>&1' | crontab -

======================================================================
 UPGRADE PATH – When to scale up
======================================================================
 ┌─────────────────┬────────────────────────────────────────────────┐
 │ Sessions        │ Action                                         │
 ├─────────────────┼────────────────────────────────────────────────┤
 │ 0 – 1,000       │ Current Phase 1: single backend + Redis local  │
 │ 1,000 – 5,000   │ Add second backend EC2, uncomment second       │
 │                 │ server line in Nginx upstream config           │
 │ 5,000 – 10,000  │ Migrate Redis to ElastiCache t3.micro          │
 │                 │ Change REDIS_HOST in tracking.env              │
 │ 10,000 – 50,000 │ Set TRACKING_KAFKA_ENABLED=true, deploy Kafka  │
 │                 │ MSK cluster (3 brokers, t3.small)              │
 │ 50,000+         │ Upgrade to Redis Cluster, Kafka partitioning   │
 │                 │ by bookingId already implemented in code       │
 └─────────────────┴────────────────────────────────────────────────┘
"
