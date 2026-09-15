# Runs the API gateway (sclera2.0v-api-gateway) on :8080 as the single entry
# point for the frontend: BFF login (session cookie + CSRF), JWT relay, and
# routing to the application-plane services.
#
# Prereqs: docker compose up -d (Redis + Keycloak), setup-keycloak.ps1 (creates
# the confidential sclera-bff client), and a built jar:
#   mvn -f sclera2.0v-api-gateway/pom.xml package "-Dmaven.test.skip=true"
# (first install the parent stub — see README "API gateway" section).
#
# The gateway runs WITHOUT a Dapr sidecar locally: Dapr is only used for
# api-key validation against identity-service, which this workspace doesn't
# run. Use /actuator/health/readiness (plain /actuator/health reports DOWN
# without the sidecar).

# Application-plane services (this repo)
$env:PROCEDURE_SERVICE_URL = 'http://localhost:8095'
$env:INSPECTION_SERVICE_URL = 'http://localhost:8096'

# Keycloak + BFF client (see setup-keycloak.ps1)
$env:KEYCLOAK_URL = 'http://localhost:8180'
$env:BFF_TENANT_CLIENT_ID = 'sclera-bff'
$env:BFF_TENANT_CLIENT_SECRET = 'dev-bff-secret'
$env:BFF_ADMIN_CLIENT_SECRET = 'unused-locally'   # sclera-admin realm not provisioned here

# Frontend origin (Vite dev server) — OAuth callback redirects land here
$env:APP_BASE_URL = 'http://localhost:8080'
$env:FRONTEND_URL = 'http://localhost:5173'

# CORS allow-list (CorsWebFilter runs at highest precedence and REJECTS any
# request carrying an Origin the list doesn't contain — browsers always send
# Origin on POST, so without this the SPA's login/exchange gets a 403 with an
# empty body). Must be exact origins, no wildcards (wildcards throw at startup).
$env:SCLERA_ALLOWED_ORIGINS = 'http://localhost:5173,http://localhost:8080'

# Local HTTP dev: non-Secure cookies, allow localhost return origins,
# enable the test-exchange endpoint the frontend's dev login uses.
$env:BFF_COOKIE_SECURE = 'false'
$env:AUTH_ALLOW_INSECURE_LOCALHOST = 'true'
$env:SCLERA_BFF_TEST_EXCHANGE_ENABLED = 'true'

# Keycloak admin console proxying needs a CIDR allowlist; off for local dev.
$env:SCLERA_ADMIN_CONSOLE_ENABLED = 'false'

# HMAC secret for internal service-to-service endpoints (sclera-common
# @EnableSclereHmac refuses to start without it). Same dev value the
# application-plane services default to, so signatures verify both ways.
# NOTE: use the versioned jar — the folder shipped with a stale unversioned
# sclera-api-gateway.jar built before the procedure/inspection routes existed.
java -jar sclera2.0v-api-gateway/target/sclera-api-gateway-0.1.0-SNAPSHOT.jar `
    --sclera.event-listener.signing-secret=dev-internal-signing-secret
