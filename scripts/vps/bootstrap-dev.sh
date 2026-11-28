#!/usr/bin/env bash
# One-time preparation of the dev VPS for ODeL (backend services + web). Run as the deploy user (devoyeks)
# from a checkout of this repo:  bash scripts/vps/bootstrap-dev.sh
# Safe to run again: every step checks before it changes anything.
set -euo pipefail

ROOT=/opt/odel-dev
ENV_FILE=$ROOT/.env.dev
DB_NAME=${DB_NAME:-odel}
DB_USER=${DB_USER:-odel_user}
DEPLOY_USER=${DEPLOY_USER:-$USER}
SERVICES=(api-gateway identity-service tenant-service notification-service academic-service student-service payment-service registration-service)
SCHEMAS=(app identity tenant notification academic student payment registration)
DOMAINS=(odel-dev.oyeksglobal.com odel-console-dev.oyeksglobal.com odel-api-dev.oyeksglobal.com)
HERE=$(cd "$(dirname "$0")/../.." && pwd)

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  ✓ %s\n' "$*"; }
warn() { printf '  ! %s\n' "$*"; }
die()  { printf '  ✗ %s\n' "$*" >&2; exit 1; }

say "1. What the box has"
java -version 2>&1 | head -1 | grep -q '"21' || die "Java 21 is required."
command -v node >/dev/null || die "node is required for the web app."
avail_mb=$(awk '/MemAvailable/ {printf "%d", $2/1024}' /proc/meminfo)
echo "  memory available: ${avail_mb} MB (ODeL needs about 3 GB: 4 JVMs at ~700 MB plus the web app)"
for port in 6379:Redis 5432:Postgres; do
  p=${port%%:*}; ss -ltn | grep -q ":$p " && ok "${port##*:} on :$p" || die "${port##*:} is not listening on :$p"
done
busy=$(ss -ltn | grep -E ':930[0-9] |:3100 ' || true)
[ -n "$busy" ] && warn "ports 9300–9309/3100 already in use (fine on a re-run):\n$busy" || ok "ports 9300–9303 and 3100 are free"

say "2. Directories"
sudo mkdir -p "$ROOT/storage"
for svc in "${SERVICES[@]}"; do sudo mkdir -p "$ROOT/$svc"; done
sudo chown -R "$DEPLOY_USER:$DEPLOY_USER" "$ROOT"
mkdir -p "$HOME/apps/odel-web-dev"
ok "$ROOT/<service> and ~/apps/odel-web-dev"

say "3. Postgres database $DB_NAME"
if sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='$DB_USER'" | grep -q 1; then
  DB_PASSWORD=${DB_PASSWORD:-}
  [ -z "$DB_PASSWORD" ] && [ -f "$ENV_FILE" ] && DB_PASSWORD=$(sed -n 's/^ODEL_DB_PASSWORD=//p' "$ENV_FILE")
  [ -z "$DB_PASSWORD" ] && die "Role $DB_USER exists but its password is unknown. Export DB_PASSWORD=... and re-run."
  ok "role $DB_USER exists"
else
  DB_PASSWORD=${DB_PASSWORD:-$(openssl rand -base64 24 | tr -d '/+=')}
  sudo -u postgres psql -qc "CREATE USER $DB_USER WITH PASSWORD '$DB_PASSWORD';"
  ok "role $DB_USER created"
fi
sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='$DB_NAME'" | grep -q 1 \
  && ok "database $DB_NAME exists" \
  || { sudo -u postgres psql -qc "CREATE DATABASE $DB_NAME OWNER $DB_USER;"; ok "database $DB_NAME created"; }
for sch in "${SCHEMAS[@]}"; do sudo -u postgres psql -d "$DB_NAME" -qc "CREATE SCHEMA IF NOT EXISTS $sch AUTHORIZATION $DB_USER;"; done
ok "schemas ${SCHEMAS[*]} owned by $DB_USER"

say "4. Environment file $ENV_FILE"
if [ -f "$ENV_FILE" ]; then
  ok "exists; leaving it alone"
else
  cat > "$ENV_FILE" <<ENV
# dev profile: typed-email sign-in instead of Google, emails logged (GET /api/v1/dev/emails), files on local disk.
SPRING_PROFILES_ACTIVE=dev

ODEL_DB_HOST=localhost
ODEL_DB_PORT=5432
ODEL_DB_NAME=$DB_NAME
ODEL_DB_USER=$DB_USER
ODEL_DB_PASSWORD=$DB_PASSWORD

# Redis: CoRyd uses database 1 on this box.
ODEL_REDIS_HOST=localhost
ODEL_REDIS_PORT=6379
ODEL_REDIS_DATABASE=3

ODEL_BASE_DOMAIN=odel-dev.oyeksglobal.com
ODEL_CONSOLE_HOST=odel-console-dev.oyeksglobal.com
ODEL_WEB_SCHEME=https
ODEL_SECURE_COOKIES=true
ODEL_MFA_KEY=$(openssl rand -base64 32)
ODEL_INTERNAL_API_KEY=$(openssl rand -hex 32)
ODEL_GOOGLE_REDIRECT_URI=https://odel-console-dev.oyeksglobal.com/api/v1/auth/google/callback

ODEL_STORAGE_MODE=local
ODEL_STORAGE_LOCAL_DIR=$ROOT/storage
ODEL_MAIL_MODE=log

ODEL_BOOTSTRAP_SUPER_ADMIN_EMAIL=chuka.obi@oyeksglobal.com
ODEL_BOOTSTRAP_SUPER_ADMIN_NAME=Chuka Obi

# Payments (B2): paystack mode needs the TEST secret key, added by hand (never committed):
#   ODEL_PAYSTACK_MODE=paystack
#   ODEL_PAYSTACK_SECRET_KEY=sk_test_...
# Without them payment-service uses the fake gateway.
ODEL_PAYSTACK_MODE=fake

JAVA_OPTS=-Xms64m -Xmx320m -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=64m -XX:+UseSerialGC -Xss512k -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom
ENV
  chmod 600 "$ENV_FILE"
  ok "written with generated DB password, MFA key and internal key (chmod 600)"
fi

say "5. systemd units"
sudo cp "$HERE"/systemd/odel-*-service.service "$HERE"/systemd/odel-web-dev.service /etc/systemd/system/
sudo systemctl daemon-reload
for u in "$HERE"/systemd/odel-*-service.service "$HERE"/systemd/odel-web-dev.service; do sudo systemctl enable -q "$(basename "$u")"; done
ok "units installed and enabled; each starts once its jar (or the web bundle) arrives"

say "6. nginx and certificates"
sudo cp "$HERE/nginx/odel-dev.oyeksglobal.com" /etc/nginx/sites-available/odel-dev.oyeksglobal.com
sudo ln -sf /etc/nginx/sites-available/odel-dev.oyeksglobal.com /etc/nginx/sites-enabled/odel-dev.oyeksglobal.com
sudo nginx -t && sudo systemctl reload nginx
ok "site enabled"
if sudo certbot certificates 2>/dev/null | grep -q "Certificate Name: odel-dev.oyeksglobal.com"; then
  ok "certificate exists"
else
  args=(); for d in "${DOMAINS[@]}"; do args+=(-d "$d"); done
  sudo certbot --nginx --non-interactive --agree-tos --redirect --cert-name odel-dev.oyeksglobal.com "${args[@]}" \
    -m "${CERTBOT_EMAIL:-admin@oyeksglobal.com}" && ok "certificate issued for ${DOMAINS[*]}"
fi

say "7. Universities' own domains"
sudo install -m 750 "$HERE/scripts/vps/odel-custom-domains.sh" /usr/local/bin/odel-custom-domains.sh
sudo cp "$HERE/systemd/odel-custom-domains.service" "$HERE/systemd/odel-custom-domains.timer" /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable -q --now odel-custom-domains.timer
ok "odel-custom-domains.timer runs every 2 minutes"

say "Done. Push develop to odel-backend and odel-web (or run their deploy workflows by hand)."
