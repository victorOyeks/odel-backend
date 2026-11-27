#!/usr/bin/env bash
# Runs as root every 2 minutes (odel-custom-domains.timer). Asks tenant-service which universities' own domains now point
# at this server, issues each one a certificate (HTTP-01 on a per-domain port-80 site), turns on its nginx site and reports
# back: LIVE, or the error the console shows. tenant-service owns the status; this only acts on what it is told.
set -uo pipefail
ENV_FILE=${ENV_FILE:-/opt/odel-dev/.env.dev}
API=${API:-http://127.0.0.1:9302}
WEBROOT=/var/www/odel-acme
SITES=/etc/nginx/sites-available
KEY=$(sed -n 's/^ODEL_INTERNAL_API_KEY=//p' "$ENV_FILE")
DOMAIN_RE='^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$'
mkdir -p "$WEBROOT"

report() { # tenantId domain ok error
  body=$(python3 -c 'import json,sys; print(json.dumps({"domain": sys.argv[1], "ok": sys.argv[2] == "true", "error": sys.argv[3] or None}))' "$2" "$3" "${4:-}")
  curl -s -o /dev/null -X POST -H "X-Internal-Key: $KEY" -H "Content-Type: application/json" --data "$body" "$API/internal/custom-domains/$1/result"
}

site_http() { # port 80: Let's Encrypt's check, everything else to https
  cat > "$SITES/odel-custom-$1" <<CONF
# ODeL: $1 (a university's own domain). Written by odel-custom-domains.sh.
server {
    server_name $1;
    listen 80;
    listen [::]:80;
    location /.well-known/acme-challenge/ { root $WEBROOT; }
    location / { return 301 https://\$host\$request_uri; }
}
CONF
}

site_https() {
  site_http "$1"
  cat >> "$SITES/odel-custom-$1" <<CONF

server {
    server_name $1;
    listen 443 ssl;
    listen [::]:443 ssl;
    ssl_certificate /etc/letsencrypt/live/odel-custom-$1/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/odel-custom-$1/privkey.pem;
    include /etc/letsencrypt/options-ssl-nginx.conf;
    ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem;
    client_max_body_size 10m;
    gzip on;
    gzip_proxied any;
    gzip_types application/json application/javascript text/javascript text/css text/plain image/svg+xml;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host \$host;
    proxy_set_header X-Real-IP \$remote_addr;
    proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto \$scheme;
    proxy_set_header X-Forwarded-Host \$host;
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
CONF
}

pending=$(curl -sf -H "X-Internal-Key: $KEY" "$API/internal/custom-domains/pending") || exit 0
echo "$pending" | python3 -c 'import json,sys; [print(d["tenantId"], d["domain"]) for d in json.load(sys.stdin)]' | while read -r id domain; do
  domain=$(echo "$domain" | tr 'A-Z' 'a-z')
  if ! [[ "$domain" =~ $DOMAIN_RE ]]; then report "$id" "$domain" false "Not a valid domain name."; continue; fi
  echo "→ $domain"
  site_http "$domain"
  ln -sf "$SITES/odel-custom-$domain" "/etc/nginx/sites-enabled/odel-custom-$domain"
  if ! nginx -t >/dev/null 2>&1; then rm -f "/etc/nginx/sites-enabled/odel-custom-$domain"; report "$id" "$domain" false "The web server refused the site settings."; continue; fi
  systemctl reload nginx
  log=$(mktemp)
  if certbot certonly --webroot -w "$WEBROOT" -d "$domain" --cert-name "odel-custom-$domain" --non-interactive --agree-tos \
       --register-unsafely-without-email --keep-until-expiring >"$log" 2>&1; then
    site_https "$domain"
    if nginx -t >/dev/null 2>&1; then systemctl reload nginx; report "$id" "$domain" true ""; echo "  ✓ live"
    else site_http "$domain"; systemctl reload nginx; report "$id" "$domain" false "The certificate was issued but the web server refused the site settings."; fi
  else
    err=$(grep -E "Detail:|Error|error" "$log" | head -2 | tr '\n' ' ' | cut -c1-300)
    [ -z "$err" ] && err="The certificate authority could not verify the domain."
    report "$id" "$domain" false "$err"
    echo "  ✗ $err"
  fi
  rm -f "$log"
done
