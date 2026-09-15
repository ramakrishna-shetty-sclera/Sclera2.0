# Bootstraps the local OpenFGA (http://localhost:8085) for the application-plane
# services. Idempotent — safe to re-run.
#
# Does:
#   1. Ensures the sclera_openfga database exists (the postgres-init script only
#      runs on a fresh postgres volume) and (re)starts the openfga containers.
#   2. Creates the 'sclera' store if missing.
#   3. Writes docker/openfga/authorization-model.json as the active model
#      (services always resolve the LATEST model, so re-running is harmless).
#   4. Grants Keycloak's testuser the 'admin' role on its organization:
#        user:<testuser-sub> admin organization:11111111-1111-1111-1111-111111111111
#      (requires Keycloak up + setup-keycloak.ps1 already run; skipped otherwise).
#
# Verify a permission afterwards:
#   Invoke-RestMethod -Method Post -Uri "http://localhost:8085/stores/$storeId/check" `
#     -ContentType application/json -Body (@{ tuple_key = @{
#       user = "user:<sub>"; relation = 'can_manage_inspections';
#       object = 'organization:11111111-1111-1111-1111-111111111111' } } | ConvertTo-Json -Depth 5)

$ErrorActionPreference = 'Stop'
$fga = 'http://localhost:8085'
$storeName = 'sclera'
$orgId = '11111111-1111-1111-1111-111111111111'
$modelFile = Join-Path $PSScriptRoot 'docker\openfga\authorization-model.json'

# --- 1. database + containers -------------------------------------------------
$dbExists = docker exec sclera-postgres psql -U sclera -tAc "SELECT 1 FROM pg_database WHERE datname='sclera_openfga'"
# "$dbExists": a $null LHS of -notmatch behaves as an empty collection, not a string
if ("$dbExists" -notmatch '1') {
    docker exec sclera-postgres psql -U sclera -c "CREATE DATABASE sclera_openfga OWNER sclera" | Out-Null
    "database sclera_openfga created"
} else { "database sclera_openfga already exists" }
# via cmd: compose writes progress to stderr, which PS 5.1 + ErrorActionPreference=Stop treats as fatal
cmd /c "docker compose up -d openfga >nul 2>&1"
if ($LASTEXITCODE -ne 0) { throw "docker compose up -d openfga failed (exit $LASTEXITCODE)" }

$deadline = (Get-Date).AddSeconds(60)
while ($true) {
    try { Invoke-RestMethod "$fga/healthz" | Out-Null; break }
    catch {
        if ((Get-Date) -gt $deadline) { throw "OpenFGA did not become healthy at $fga within 60s" }
        Start-Sleep -Seconds 2
    }
}
"OpenFGA healthy at $fga"

# --- 2. store -----------------------------------------------------------------
$stores = (Invoke-RestMethod "$fga/stores?page_size=100").stores
$store = $stores | Where-Object name -eq $storeName | Select-Object -First 1
if ($store) { $storeId = $store.id; "store '$storeName' already exists ($storeId)" }
else {
    $storeId = (Invoke-RestMethod -Method Post -Uri "$fga/stores" -ContentType 'application/json' `
        -Body (@{ name = $storeName } | ConvertTo-Json)).id
    "store '$storeName' created ($storeId)"
}

# --- 3. authorization model ---------------------------------------------------
$modelJson = Get-Content $modelFile -Raw
$modelId = (Invoke-RestMethod -Method Post -Uri "$fga/stores/$storeId/authorization-models" `
    -ContentType 'application/json' -Body $modelJson).authorization_model_id
"authorization model written ($modelId)"

# --- 4. seed tuples: testuser -> admin of the dev org --------------------------
function Write-FgaTuple($user, $relation, $object) {
    $body = @{ writes = @{ tuple_keys = @(@{ user = $user; relation = $relation; object = $object }) } } |
        ConvertTo-Json -Depth 6
    try {
        Invoke-RestMethod -Method Post -Uri "$fga/stores/$storeId/write" -ContentType 'application/json' -Body $body | Out-Null
        "tuple written: $user $relation $object"
    } catch {
        # duplicate writes come back 400 "cannot write a tuple which already exists"
        $detail = try { ($_.ErrorDetails.Message | ConvertFrom-Json).message } catch { $_.Exception.Message }
        if ($detail -match 'already exists') { "tuple already exists: $user $relation $object" }
        else { throw }
    }
}

try {
    $kc = 'http://localhost:8180'
    $admin = Invoke-RestMethod -Method Post -Uri "$kc/realms/master/protocol/openid-connect/token" `
        -Body @{ grant_type = 'password'; client_id = 'admin-cli'; username = 'admin'; password = 'admin' }
    $users = Invoke-RestMethod -Uri "$kc/admin/realms/sclera/users?username=testuser&exact=true" `
        -Headers @{ Authorization = "Bearer $($admin.access_token)" }
    if ($users) {
        Write-FgaTuple "user:$($users[0].id)" 'admin' "organization:$orgId"
    } else {
        "WARN: testuser not found in Keycloak — run setup-keycloak.ps1 first, then re-run this script"
    }
} catch {
    "WARN: Keycloak not reachable ($($_.Exception.Message)) — role tuples not seeded; re-run after setup-keycloak.ps1"
}

"`nDone. Store: $storeId  Model: $modelId"
"Services resolve the store by name '$storeName' (sclera.fga.store-name) and use the latest model."
