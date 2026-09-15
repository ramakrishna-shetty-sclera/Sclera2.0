# Creates three demo accounts in Keycloak (realm sclera) and grants their
# OpenFGA roles, to exercise every authorization tier. Idempotent - safe to
# re-run after docker compose down (Keycloak wipe) or an OpenFGA store wipe.
#
#   demo-user        / demo-user        FGA 'viewer' on the dev org  -> read-only
#   demo-admin       / demo-admin       FGA 'admin'  on the dev org  -> full org control
#   demo-superadmin  / demo-superadmin  is_platform_admin=true claim -> bypasses FGA entirely
#
# All three belong to org 11111111-1111-1111-1111-111111111111 (org_type CLIENT).
# Also adds the boolean 'is_platform_admin' claim mapper to the sclera-app and
# sclera-bff clients (ScleraJwtConverter reads it into OrgContext.isPlatformAdmin).
#
# Prereqs: docker compose up -d, setup-keycloak.ps1, setup-openfga.ps1.
#
# Get a token, e.g.:
#   $tok = (Invoke-RestMethod -Method Post -Uri http://localhost:8180/realms/sclera/protocol/openid-connect/token `
#     -Body @{ grant_type='password'; client_id='sclera-app'; username='demo-admin'; password='demo-admin' }).access_token

$ErrorActionPreference = 'Stop'
$kc = 'http://localhost:8180'
$fga = 'http://localhost:8085'
$realm = 'sclera'
$orgId = '11111111-1111-1111-1111-111111111111'

$admin = Invoke-RestMethod -Method Post -Uri "$kc/realms/master/protocol/openid-connect/token" `
    -Body @{ grant_type = 'password'; client_id = 'admin-cli'; username = 'admin'; password = 'admin' }
$H = @{ Authorization = "Bearer $($admin.access_token)" }

function Invoke-Kc($method, $path, $body) {
    $params = @{ Method = $method; Uri = "$kc/admin$path"; Headers = $H }
    if ($body) { $params.Body = ($body | ConvertTo-Json -Depth 10); $params.ContentType = 'application/json' }
    Invoke-RestMethod @params
}

# --- is_platform_admin claim mapper on both clients --------------------------
foreach ($clientId in 'sclera-app', 'sclera-bff') {
    $client = Invoke-Kc GET "/realms/$realm/clients?clientId=$clientId"
    if (-not $client) { "client '$clientId' not found - run setup-keycloak.ps1 first"; continue }
    $uuid = $client[0].id
    $mappers = Invoke-Kc GET "/realms/$realm/clients/$uuid/protocol-mappers/models"
    if ($mappers | Where-Object name -eq 'map-is_platform_admin') {
        "mapper 'map-is_platform_admin' already exists on $clientId"
    } else {
        Invoke-Kc POST "/realms/$realm/clients/$uuid/protocol-mappers/models" @{
            name = 'map-is_platform_admin'; protocol = 'openid-connect'; protocolMapper = 'oidc-usermodel-attribute-mapper'
            config = @{
                'user.attribute' = 'is_platform_admin'; 'claim.name' = 'is_platform_admin'
                'jsonType.label' = 'boolean'
                'access.token.claim' = 'true'; 'id.token.claim' = 'true'; 'userinfo.token.claim' = 'true'
            }
        }
        "mapper 'map-is_platform_admin' created on $clientId"
    }
}

# --- users -------------------------------------------------------------------
function New-DemoUser($username, $extraAttrs) {
    $users = Invoke-Kc GET "/realms/$realm/users?username=$username&exact=true"
    if ($users) { $uuid = $users[0].id; Write-Host "user '$username' already exists" }
    else {
        Invoke-Kc POST "/realms/$realm/users" @{
            username = $username; enabled = $true; email = "$username@sclera.local"; emailVerified = $true
            firstName = 'Demo'; lastName = $username
        } | Out-Null
        $uuid = (Invoke-Kc GET "/realms/$realm/users?username=$username&exact=true")[0].id
        Write-Host "user '$username' created"
    }
    # re-apply attributes every run (PUT needs the full representation back)
    $attrs = @{ org_id = @($orgId); org_type = @('CLIENT') }
    if ($extraAttrs) { $extraAttrs.GetEnumerator() | ForEach-Object { $attrs[$_.Key] = $_.Value } }
    $rep = Invoke-Kc GET "/realms/$realm/users/$uuid"
    @{
        email = "$username@sclera.local"; emailVerified = $true
        firstName = 'Demo'; lastName = $username; requiredActions = @()
        attributes = $attrs
    }.GetEnumerator() | ForEach-Object { $rep | Add-Member -NotePropertyName $_.Key -NotePropertyValue $_.Value -Force }
    Invoke-Kc PUT "/realms/$realm/users/$uuid" $rep | Out-Null
    Invoke-Kc PUT "/realms/$realm/users/$uuid/reset-password" @{ type = 'password'; value = $username; temporary = $false } | Out-Null
    return $uuid
}

$userUuid = New-DemoUser 'demo-user' $null
$adminUuid = New-DemoUser 'demo-admin' $null
$superUuid = New-DemoUser 'demo-superadmin' @{ is_platform_admin = @('true') }
"passwords set (same as each username)"

# --- FGA role tuples ---------------------------------------------------------
$stores = (Invoke-RestMethod "$fga/stores?page_size=100").stores
$store = $stores | Where-Object name -eq 'sclera' | Select-Object -First 1
if (-not $store) { throw "OpenFGA store 'sclera' not found - run setup-openfga.ps1 first" }
$storeId = $store.id

function Write-FgaTuple($user, $relation, $object) {
    $body = @{ writes = @{ tuple_keys = @(@{ user = $user; relation = $relation; object = $object }) } } |
        ConvertTo-Json -Depth 6
    try {
        Invoke-RestMethod -Method Post -Uri "$fga/stores/$storeId/write" -ContentType 'application/json' -Body $body | Out-Null
        "tuple written: $user $relation $object"
    } catch {
        $detail = try { ($_.ErrorDetails.Message | ConvertFrom-Json).message } catch { $_.Exception.Message }
        if ($detail -match 'already exists') { "tuple already exists: $user $relation $object" }
        else { throw }
    }
}

Write-FgaTuple "user:$userUuid" 'viewer' "organization:$orgId"
Write-FgaTuple "user:$adminUuid" 'admin' "organization:$orgId"
# demo-superadmin gets NO tuple on purpose: platform admins bypass FGA checks.

""
"Done."
"  demo-user       (viewer)          $userUuid"
"  demo-admin      (org admin)       $adminUuid"
"  demo-superadmin (platform admin)  $superUuid"
"Note: services cache check decisions for up to 30s (sclera.fga.check-cache-ttl-seconds)."
