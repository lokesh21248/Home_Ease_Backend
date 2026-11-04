#!/bin/bash
# ==============================================================================
# HOME EASE – Nginx Installer for Phase 1 (Two Nginx EC2 Nodes)
# Run on EACH Nginx EC2 (AZ-1a and AZ-1b):
#   sudo bash install-nginx.sh
#
# Variables:
#   BACKEND_PRIVATE_IP   – Private IP of backend EC2 (default: 10.0.1.20)
#   DOMAIN               – Primary API domain (default: api.homeease.com)
#   TRACKING_DOMAIN      – Tracking subdomain (default: tracking.homeease.com)
# ==============================================================================

set -euo pipefail

BACKEND_PRIVATE_IP="${BACKEND_PRIVATE_IP:-10.0.1.20}"
DOMAIN="${DOMAIN:-api.homeease.com}"
TRACKING_DOMAIN="${TRACKING_DOMAIN:-tracking.homeease.com}"

echo "=============================================="
echo " HomeEase Phase 1 – Nginx Installer"
echo " Backend Private IP : $BACKEND_PRIVATE_IP"
echo " Domain             : $DOMAIN"
echo "=============================================="

# ── 1. Install Nginx ──────────────────────────────────────────────────────────
if command -v apt-get &> /dev/null; then
    apt-get update -y
    apt-get install -y nginx certbot python3-certbot-nginx
elif command -v dnf &> /dev/null; then
    dnf install -y nginx certbot python3-certbot-nginx
else
    echo "ERROR: Unsupported package manager."
    exit 1
fi

# ── 2. Tune OS for connection handling ───────────────────────────────────────
cat > /etc/security/limits.d/nginx.conf <<EOF
nginx   soft    nofile   65536
nginx   hard    nofile   65536
EOF

# ── 3. Write main nginx.conf ─────────────────────────────────────────────────
cat > /etc/nginx/nginx.conf <<'NGINX_MAIN'
user nginx;
worker_processes 2;
worker_rlimit_nofile 65536;
pid /run/nginx.pid;

events {
    worker_connections 2048;
    use epoll;
    multi_accept on;
}

http {
    include       /etc/nginx/mime.types;
    default_type  application/octet-stream;

    log_format main '$remote_addr - [$time_local] "$request" '
                    '$status $body_bytes_sent rt=$request_time '
                    'urt=$upstream_response_time';

    access_log  /var/log/nginx/access.log  main buffer=64k flush=5s;
    error_log   /var/log/nginx/error.log   warn;

    sendfile on;
    tcp_nopush on;
    tcp_nodelay on;
    keepalive_timeout 65;
    keepalive_requests 1000;
    server_tokens off;

    gzip on;
    gzip_comp_level 4;
    gzip_min_length 1024;
    gzip_types text/plain application/json application/javascript text/css;
    gzip_proxied any;
    gzip_vary on;

    map $http_upgrade $connection_upgrade {
        default upgrade;
        ''      close;
    }

    limit_req_zone $binary_remote_addr zone=gps_ingest_zone:10m  rate=10r/s;
    limit_req_zone $binary_remote_addr zone=api_general_zone:10m rate=50r/s;

    client_max_body_size 1m;
    client_body_timeout  10s;
    client_header_timeout 10s;
    send_timeout 30s;

    proxy_connect_timeout 5s;
    proxy_read_timeout    30s;
    proxy_send_timeout    30s;
    proxy_buffering off;
    proxy_buffer_size 16k;
    proxy_busy_buffers_size 32k;

    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_http_version 1.1;

    include /etc/nginx/conf.d/*.conf;
}
NGINX_MAIN

# ── 4. Write virtual host config (HTTP only first, Certbot adds HTTPS) ────────
mkdir -p /var/www/certbot

cat > /etc/nginx/conf.d/homeease.conf <<NGINX_HTTP
# Upstreams
upstream tracking_service_upstream {
    server ${BACKEND_PRIVATE_IP}:8082 max_fails=3 fail_timeout=10s;
    keepalive 128;
}
upstream backend_core_upstream {
    server ${BACKEND_PRIVATE_IP}:8080 max_fails=3 fail_timeout=10s;
    keepalive 64;
}

# HTTP: health check + ACME + redirect
server {
    listen 80;
    listen [::]:80;
    server_name ${DOMAIN} ${TRACKING_DOMAIN};

    location /.well-known/acme-challenge/ { root /var/www/certbot; }
    location /healthz { access_log off; default_type text/plain; return 200 "OK\n"; }
    location / { return 301 https://\$host\$request_uri; }
}
NGINX_HTTP

# ── 5. Test and start Nginx ───────────────────────────────────────────────────
nginx -t
systemctl enable nginx
systemctl restart nginx

# ── 6. Obtain TLS Certificate from Let's Encrypt ─────────────────────────────
echo ""
echo "── Obtaining TLS Certificate ─────────────────────────────────────────────"
echo "  Ensure DNS A records for $DOMAIN and $TRACKING_DOMAIN point to this EC2's"
echo "  public IP BEFORE running certbot."
echo ""
read -rp "  DNS is configured. Run certbot now? [y/N] " RUN_CERTBOT
if [[ "${RUN_CERTBOT,,}" == "y" ]]; then
    certbot --nginx \
        -d "$DOMAIN" \
        -d "$TRACKING_DOMAIN" \
        --non-interactive \
        --agree-tos \
        --email ops@homeease.com \
        --redirect
    echo "  ✅ TLS certificate installed."
else
    echo "  ⚠️  Skipping certbot. Run manually:"
    echo "      certbot --nginx -d $DOMAIN -d $TRACKING_DOMAIN"
fi

# ── 7. Write full HTTPS virtual host (after cert exists) ──────────────────────
cat > /etc/nginx/conf.d/homeease.conf <<NGINX_HTTPS
upstream tracking_service_upstream {
    server ${BACKEND_PRIVATE_IP}:8082 max_fails=3 fail_timeout=10s;
    keepalive 128;
}
upstream backend_core_upstream {
    server ${BACKEND_PRIVATE_IP}:8080 max_fails=3 fail_timeout=10s;
    keepalive 64;
}

server {
    listen 80;
    listen [::]:80;
    server_name ${DOMAIN} ${TRACKING_DOMAIN};
    location /.well-known/acme-challenge/ { root /var/www/certbot; }
    location /healthz { access_log off; default_type text/plain; return 200 "OK\n"; }
    location / { return 301 https://\$host\$request_uri; }
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name ${DOMAIN} ${TRACKING_DOMAIN};

    ssl_certificate         /etc/letsencrypt/live/${DOMAIN}/fullchain.pem;
    ssl_certificate_key     /etc/letsencrypt/live/${DOMAIN}/privkey.pem;
    ssl_trusted_certificate /etc/letsencrypt/live/${DOMAIN}/chain.pem;
    ssl_protocols           TLSv1.2 TLSv1.3;
    ssl_ciphers             ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384;
    ssl_prefer_server_ciphers off;
    ssl_session_cache       shared:SSL:10m;
    ssl_session_timeout     1d;
    ssl_session_tickets     off;
    ssl_stapling            on;
    ssl_stapling_verify     on;
    resolver                8.8.8.8 valid=300s;

    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "DENY" always;

    proxy_set_header Host              \$host;
    proxy_set_header X-Real-IP         \$remote_addr;
    proxy_set_header X-Forwarded-For   \$proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto \$scheme;
    proxy_http_version 1.1;

    location /healthz {
        access_log off;
        default_type text/plain;
        return 200 "OK\n";
    }

    location ~* ^/ws {
        proxy_pass http://tracking_service_upstream;
        proxy_set_header Upgrade    \$http_upgrade;
        proxy_set_header Connection \$connection_upgrade;
        proxy_connect_timeout 7s;
        proxy_send_timeout    3600s;
        proxy_read_timeout    3600s;
        proxy_buffering off;
        proxy_cache off;
        chunked_transfer_encoding off;
        proxy_socket_keepalive on;
    }

    location ~* ^/api/v1/tracking/(partner/location|update-location) {
        limit_req zone=gps_ingest_zone burst=20 nodelay;
        limit_req_status 429;
        proxy_pass http://tracking_service_upstream;
        proxy_set_header Connection "";
        proxy_connect_timeout 2s;
        proxy_read_timeout    5s;
        proxy_buffering off;
        proxy_request_buffering off;
    }

    location ~* ^/api/v1/tracking/ {
        limit_req zone=api_general_zone burst=30 nodelay;
        proxy_pass http://tracking_service_upstream;
        proxy_set_header Connection "";
        proxy_connect_timeout 3s;
        proxy_read_timeout    10s;
    }

    location /api/ {
        limit_req zone=api_general_zone burst=50 nodelay;
        proxy_pass http://backend_core_upstream;
        proxy_set_header Connection "";
        proxy_connect_timeout 5s;
        proxy_read_timeout    30s;
    }

    location / {
        return 404 '{"error":"Not Found"}';
        default_type application/json;
    }
}
NGINX_HTTPS

nginx -t && systemctl reload nginx

echo ""
echo "=============================================="
echo " ✅ Nginx Phase 1 Installation Complete"
echo "=============================================="
