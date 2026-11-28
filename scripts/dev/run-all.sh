#!/usr/bin/env bash
# Starts every service and the gateway locally with the dev profile (after: docker compose up -d; mvn -DskipTests install).
# Logs go to target/dev-logs/<service>.log. Stop with: scripts/dev/stop-all.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p target/dev-logs
docker compose exec -T postgres psql -U odel -d odel -q < docker/init-schemas.sql
for svc in tenant-service identity-service academic-service student-service payment-service registration-service notification-service api-gateway; do
  SPRING_PROFILES_ACTIVE=dev nohup java -Xmx256m -jar $svc/target/$svc.jar > target/dev-logs/$svc.log 2>&1 &
  echo $! > target/dev-logs/$svc.pid
  echo "started $svc (pid $!)"
done
for url in 9302/actuator/health 9301/actuator/health 9304/actuator/health 9305/actuator/health 9306/actuator/health 9307/actuator/health 9303/actuator/health 9300/actuator/health; do
  for i in $(seq 1 60); do curl -sf http://127.0.0.1:$url >/dev/null && break; sleep 1; done
  curl -s http://127.0.0.1:$url | grep -q UP && echo "  ✓ :${url%%/*} up" || echo "  ✗ :${url%%/*} not up (see target/dev-logs)"
done
echo "Gateway: http://localhost:9300   Swagger: http://localhost:9300/swagger-ui.html"
