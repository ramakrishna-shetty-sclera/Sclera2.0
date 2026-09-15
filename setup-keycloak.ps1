# Bootstraps the local Keycloak (http://localhost:8180, admin/admin) for the
# application-plane services. Idempotent — safe to re-run (e.g. after
# `docker compose down` wipes the Keycloak container, which has no volume).
#
# Creates:
#   realm  sclera
#   client sclera-app       public + direct-access grants (password grant for local testing)
#                           with mappers: user attrs org_id / org_type -> token claims
#   client sclera-bff       confidential, authorization-code flow — used by the API gateway's
#                           BFF login (sclera2.0v-api-gateway). Secret: dev-bff-secret
#                           (pass to the gateway via BFF_TENANT_CLIENT_SECRET).
#   user   testuser/testuser  org_id=11111111-1111-1111-1111-111111111111, org_type=CLIENT
#
# Both clients also map org_id -> tenant_id claim: the gateway's claim extraction
# (JwtClaimExtractionFilter / test-exchange orgId resolution) reads tenant_id,
# while ScleraJwtConverter in the app-plane services reads org_id.
#
# ScleraJwtConverter requires org_id to be a UUID and org_type in {PLATFORM, CLIENT, VENDOR};
# roles come from realm_access.roles. See sclera-common-guide.html.
#
# Get a token afterwards:
#   $tok = (Invoke-RestMethod -Method Post `
#     -Uri http://localhost:8180/realms/sclera/protocol/openid-connect/token `
#     -Body @{ grant_type='password'; client_id='sclera-app'; username='testuser'; password='testuser' }).access_token

$ErrorActionPreference = 'Stop'
$kc = 'http://localhost:8180'
$realm = 'sclera'
$clientId = 'sclera-app'
$orgId = '11111111-1111-1111-1111-111111111111'

$admin = Invoke-RestMethod -Method Post -Uri "$kc/realms/master/protocol/openid-connect/token" `
    -Body @{ grant_type = 'password'; client_id = 'admin-cli'; username = 'admin'; password = 'admin' }
$H = @{ Authorization = "Bearer $($admin.access_token)" }

function Invoke-Kc($method, $path, $body) {
    $params = @{ Method = $method; Uri = "$kc/admin$path"; Headers = $H }
    if ($body) { $params.Body = ($body | ConvertTo-Json -Depth 10); $params.ContentType = 'application/json' }
    Invoke-RestMethod @params
}

# Realm
try { Invoke-Kc GET "/realms/$realm" | Out-Null; "realm '$realm' already exists" }
catch { Invoke-Kc POST "/realms" @{ realm = $realm; enabled = $true }; "realm '$realm' created" }

# Keycloak 24+ drops attributes not declared in the user profile unless unmanaged
# attributes are enabled — without this, org_id/org_type are silently discarded.
$profile = Invoke-Kc GET "/realms/$realm/users/profile"
if ($profile.unmanagedAttributePolicy -ne 'ENABLED') {
    $profile | Add-Member -NotePropertyName unmanagedAttributePolicy -NotePropertyValue 'ENABLED' -Force
    Invoke-Kc PUT "/realms/$realm/users/profile" $profile
    "unmanaged user attributes enabled"
}

# Client (public, direct access grants so we can use the password grant locally)
$existing = Invoke-Kc GET "/realms/$realm/clients?clientId=$clientId"
if ($existing) { $clientUuid = $existing[0].id; "client '$clientId' already exists" }
else {
    Invoke-Kc POST "/realms/$realm/clients" @{
        clientId = $clientId; enabled = $true; publicClient = $true; protocol = 'openid-connect'
        directAccessGrantsEnabled = $true; standardFlowEnabled = $true
        redirectUris = @('http://localhost:*'); webOrigins = @('*')
    }
    $clientUuid = (Invoke-Kc GET "/realms/$realm/clients?clientId=$clientId")[0].id
    "client '$clientId' created"
}

# Mappers: user attributes -> token claims. claim.name may differ from the
# attribute (org_id is additionally exposed as tenant_id for the gateway).
function Set-ClientMappers($targetClientUuid, $clientLabel) {
    $mappers = Invoke-Kc GET "/realms/$realm/clients/$targetClientUuid/protocol-mappers/models"
    foreach ($pair in @('org_id', 'org_id'), @('org_type', 'org_type'), @('org_id', 'tenant_id')) {
        $attr, $claim = $pair
        $name = "map-$claim"
        if ($mappers | Where-Object name -eq $name) { "mapper '$name' already exists on $clientLabel"; continue }
        Invoke-Kc POST "/realms/$realm/clients/$targetClientUuid/protocol-mappers/models" @{
            name = $name; protocol = 'openid-connect'; protocolMapper = 'oidc-usermodel-attribute-mapper'
            config = @{
                'user.attribute' = $attr; 'claim.name' = $claim; 'jsonType.label' = 'String'
                'access.token.claim' = 'true'; 'id.token.claim' = 'true'; 'userinfo.token.claim' = 'true'
            }
        }
        "mapper '$name' created on $clientLabel"
    }
}
Set-ClientMappers $clientUuid $clientId

# BFF client for the API gateway (confidential, authorization-code + PKCE).
# Redirect URI covers the gateway's /api/v1/auth/callback on localhost:8080.
$bffClientId = 'sclera-bff'
$bffSecret = 'dev-bff-secret'
$existingBff = Invoke-Kc GET "/realms/$realm/clients?clientId=$bffClientId"
if ($existingBff) { $bffUuid = $existingBff[0].id; "client '$bffClientId' already exists" }
else {
    Invoke-Kc POST "/realms/$realm/clients" @{
        clientId = $bffClientId; enabled = $true; publicClient = $false; protocol = 'openid-connect'
        secret = $bffSecret; standardFlowEnabled = $true; directAccessGrantsEnabled = $false
        redirectUris = @('http://localhost:8080/*'); webOrigins = @('http://localhost:8080')
        attributes = @{ 'post.logout.redirect.uris' = 'http://localhost:5173/*##http://localhost:8080/*' }
    }
    $bffUuid = (Invoke-Kc GET "/realms/$realm/clients?clientId=$bffClientId")[0].id
    "client '$bffClientId' created (secret: $bffSecret)"
}
Set-ClientMappers $bffUuid $bffClientId

# Test users (attributes re-applied on every run — they are dropped if the user
# was created before the unmanaged-attribute policy was enabled).
# testuser2 belongs to a SECOND org — used to prove schema-per-tenant isolation.
function Set-TestUser($username, $userOrgId, $firstName) {
    $users = Invoke-Kc GET "/realms/$realm/users?username=$username&exact=true"
    if ($users) { $userUuid = $users[0].id; "user '$username' already exists" }
    else {
        Invoke-Kc POST "/realms/$realm/users" @{
            username = $username; enabled = $true; email = "$username@sclera.local"; emailVerified = $true
            firstName = $firstName; lastName = 'User'
        }
        $userUuid = (Invoke-Kc GET "/realms/$realm/users?username=$username&exact=true")[0].id
        "user '$username' created"
    }
    # PUT replaces the whole representation — send it back complete, or Keycloak
    # clears the other profile fields and flags the account "not fully set up".
    $rep = Invoke-Kc GET "/realms/$realm/users/$userUuid"
    @{
        email = "$username@sclera.local"; emailVerified = $true
        firstName = $firstName; lastName = 'User'; requiredActions = @()
        attributes = @{ org_id = @($userOrgId); org_type = @('CLIENT') }
    }.GetEnumerator() | ForEach-Object { $rep | Add-Member -NotePropertyName $_.Key -NotePropertyValue $_.Value -Force }
    Invoke-Kc PUT "/realms/$realm/users/$userUuid" $rep
    "user '$username' attributes set (org_id=$userOrgId, org_type=CLIENT)"
    Invoke-Kc PUT "/realms/$realm/users/$userUuid/reset-password" @{ type = 'password'; value = $username; temporary = $false }
    "password set to '$username'"
}
Set-TestUser 'testuser'  $orgId                                  'Test'
Set-TestUser 'testuser2' '22222222-2222-2222-2222-222222222222'  'Test2'

"`nDone. Token endpoint: $kc/realms/$realm/protocol/openid-connect/token"
