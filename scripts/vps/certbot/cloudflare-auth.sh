#!/usr/bin/env bash
# certbot --manual-auth-hook: puts the DNS-01 TXT record in Cloudflare (IPv4: the token is limited to the box's IPv4 address).
set -euo pipefail
T=$(sed -n 's/^dns_cloudflare_api_token = //p' /root/.secrets/cloudflare.ini)
ZONE_NAME=${ZONE_NAME:-oyeksglobal.com}
Z=$(curl -4 -sf -H "Authorization: Bearer $T" "https://api.cloudflare.com/client/v4/zones?name=$ZONE_NAME" | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"][0]["id"])')
id=$(curl -4 -sf -X POST -H "Authorization: Bearer $T" -H "Content-Type: application/json" \
  "https://api.cloudflare.com/client/v4/zones/$Z/dns_records" \
  --data "{\"type\":\"TXT\",\"name\":\"_acme-challenge.$CERTBOT_DOMAIN\",\"content\":\"$CERTBOT_VALIDATION\",\"ttl\":60}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"]["id"])')
mkdir -p /tmp/odel-acme && echo "$Z $id" >> "/tmp/odel-acme/$CERTBOT_DOMAIN"
sleep 25   # let Cloudflare's nameservers pick it up before Let's Encrypt looks
