#!/usr/bin/env bash
# certbot --manual-cleanup-hook: removes the TXT records the auth hook created.
set -uo pipefail
T=$(sed -n 's/^dns_cloudflare_api_token = //p' /root/.secrets/cloudflare.ini)
f="/tmp/odel-acme/$CERTBOT_DOMAIN"
[ -f "$f" ] || exit 0
while read -r Z id; do
  curl -4 -s -X DELETE -H "Authorization: Bearer $T" "https://api.cloudflare.com/client/v4/zones/$Z/dns_records/$id" >/dev/null
done < "$f"
rm -f "$f"
