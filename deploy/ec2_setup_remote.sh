#!/usr/bin/env bash
set -euo pipefail

echo "1. Placing JAR files..."
sudo mv /tmp/homeease-core.jar /opt/homeease/core/homeease-backend.jar
sudo mv /tmp/homeease-tracking.jar /opt/homeease/tracking/homeease-tracking-service.jar
sudo chown -R homeease:homeease /opt/homeease

echo "2. Setting up self-signed TLS cert..."
sudo mkdir -p /etc/nginx/ssl /var/www/certbot
if [ ! -f /etc/nginx/ssl/homeease-selfsigned.crt ]; then
    sudo openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
        -keyout /etc/nginx/ssl/homeease-selfsigned.key \
        -out /etc/nginx/ssl/homeease-selfsigned.crt \
        -subj "/CN=homeease-backend/O=HomeEase/C=IN"
fi

echo "3. Clean Amazon Linux default nginx server block..."
# Replace /etc/nginx/nginx.conf with a clean configuration including conf.d/*.conf
sudo tee /etc/nginx/nginx.conf > /dev/null << 'NGINX_MAIN'
user nginx;
worker_processes auto;
error_log /var/log/nginx/error.log warn;
pid /run/nginx.pid;

events {
    worker_connections 4096;
    multi_accept on;
}

http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;
    log_format main '$remote_addr - $remote_user [$time_local] "$request" '
                    '$status $body_bytes_sent "$http_referer" '
                    '"$http_user_agent" "$http_x_forwarded_for"';
    access_log /var/log/nginx/access.log main;
    sendfile on;
    tcp_nopush on;
    tcp_nodelay on;
    keepalive_timeout 65;
    types_hash_max_size 4096;
    include /etc/nginx/conf.d/*.conf;
}
NGINX_MAIN

echo "4. Writing HomeEase virtual host configuration..."
sudo tee /etc/nginx/conf.d/homeease.conf > /dev/null << 'NGINX_CONF'
upstream homeease_core {
    server 127.0.0.1:8080;
    keepalive 32;
}

upstream homeease_tracking {
    server 127.0.0.1:8082;
    keepalive 64;
}

map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}

server {
    listen 80 default_server;
    listen [::]:80 default_server;
    listen 443 ssl default_server;
    listen [::]:443 ssl default_server;
    server_name _;

    ssl_certificate         /etc/nginx/ssl/homeease-selfsigned.crt;
    ssl_certificate_key     /etc/nginx/ssl/homeease-selfsigned.key;
    ssl_protocols           TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers off;

    proxy_http_version 1.1;
    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;

    location /healthz {
        access_log off;
        default_type text/plain;
        return 200 "OK\n";
    }

    location /actuator/health {
        proxy_pass http://homeease_core;
    }

    location /ws {
        proxy_pass http://homeease_tracking;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_buffering off;
    }

    location ~* ^/api/v1/tracking/ {
        proxy_pass http://homeease_tracking;
        proxy_connect_timeout 3s;
        proxy_read_timeout 10s;
    }

    location /api/ {
        proxy_pass http://homeease_core;
        proxy_connect_timeout 5s;
        proxy_read_timeout 30s;
    }

    location / {
        return 200 '{"service":"HomeEase API Gateway","status":"UP","node":"m7i-flex.large"}';
        default_type application/json;
    }
}
NGINX_CONF

echo "5. Testing and starting Nginx..."
sudo nginx -t
sudo systemctl enable --now nginx
sudo systemctl restart nginx

echo "6. Starting HomeEase systemd services..."
sudo systemctl enable --now homeease-core
sudo systemctl enable --now homeease-tracking
sudo systemctl restart homeease-core
sudo systemctl restart homeease-tracking

echo "Setup completed successfully."

