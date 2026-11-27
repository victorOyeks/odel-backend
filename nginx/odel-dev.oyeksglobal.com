# ODeL dev, three hosts on one box:
#   odel-dev.oyeksglobal.com          Wesley University's portal (students at /, staff at /staff) -> odel-web :3100
#   odel-console-dev.oyeksglobal.com  provider console                                          -> odel-web :3100
#   odel-api-dev.oyeksglobal.com      api-gateway (Swagger at /swagger-ui.html)                 -> :9300
# The web app proxies /api/* to the gateway itself (same origin, first-party cookies), adding X-Odel-Host.
# certbot adds the listen 443 / ssl_* lines to each server block.

upstream odel_dev_web {
    server 127.0.0.1:3100;
    keepalive 16;
}

upstream odel_dev_gateway {
    server 127.0.0.1:9300;
    keepalive 16;
}

# Sign-in and two-step codes: 30 a minute per address, burst 20. identity-service limits attempts per sign-in too.
limit_req_zone $binary_remote_addr zone=odel_dev_auth:10m rate=30r/m;

server {
    server_name odel-dev.oyeksglobal.com odel-console-dev.oyeksglobal.com;
    listen 80;
    listen [::]:80;

    client_max_body_size 10m;
    gzip on;
    gzip_proxied any;
    gzip_min_length 1024;
    gzip_vary on;
    gzip_types application/json application/javascript text/javascript text/css text/plain image/svg+xml;

    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Host $host;
    proxy_connect_timeout 5s;

    location ~ ^/api/v1/auth/(google|mfa|dev) {
        limit_req zone=odel_dev_auth burst=20 nodelay;
        limit_req_status 429;
        proxy_pass http://odel_dev_web;
        proxy_read_timeout 30s;
    }

    location / {
        proxy_pass http://odel_dev_web;
        proxy_read_timeout 30s;
    }
}

server {
    server_name odel-api-dev.oyeksglobal.com;
    listen 80;
    listen [::]:80;

    client_max_body_size 10m;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Host $host;
    proxy_connect_timeout 5s;

    location / {
        proxy_pass http://odel_dev_gateway;
        proxy_read_timeout 30s;
    }
}
