# Bootstraps the local Keycloak (http://localhost:8180, admin/admin) for the
# application-plane services. Idempotent — safe to re-run (e.g. after
# `docker compose down` wipes the Keycloak container, which has no volume).
#
# Creates:
#   realm  sclera
#   client sclera-app       public + direct-access grants (password grant for local testing)
#                           with mappers: user attrs org_id / org_type -> token claims
#   user   testuser/testuser  org_id=11111111-1111-1111-1111-111111111111, org_type=CLIENT
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

# Mappers: user attributes -> token claims (org_id, org_type)
$mappers = Invoke-Kc GET "/realms/$realm/clients/$clientUuid/protocol-mappers/models"
foreach ($attr in 'org_id', 'org_type') {
    if ($mappers | Where-Object name -eq "map-$attr") { "mapper 'map-$attr' already exists"; continue }
    Invoke-Kc POST "/realms/$realm/clients/$clientUuid/protocol-mappers/models" @{
        name = "map-$attr"; protocol = 'openid-connect'; protocolMapper = 'oidc-usermodel-attribute-mapper'
        config = @{
            'user.attribute' = $attr; 'claim.name' = $attr; 'jsonType.label' = 'String'
            'access.token.claim' = 'true'; 'id.token.claim' = 'true'; 'userinfo.token.claim' = 'true'
        }
    }
    "mapper 'map-$attr' created"
}

# Test user (attributes re-applied on every run — they are dropped if the user
# was created before the unmanaged-attribute policy was enabled)
$users = Invoke-Kc GET "/realms/$realm/users?username=testuser&exact=true"
if ($users) { $userUuid = $users[0].id; "user 'testuser' already exists" }
else {
    Invoke-Kc POST "/realms/$realm/users" @{
        username = 'testuser'; enabled = $true; email = 'testuser@sclera.local'; emailVerified = $true
        firstName = 'Test'; lastName = 'User'
    }
    $userUuid = (Invoke-Kc GET "/realms/$realm/users?username=testuser&exact=true")[0].id
    "user 'testuser' created"
}
# PUT replaces the whole representation — send it back complete, or Keycloak
# clears the other profile fields and flags the account "not fully set up".
$rep = Invoke-Kc GET "/realms/$realm/users/$userUuid"
@{
    email = 'testuser@sclera.local'; emailVerified = $true
    firstName = 'Test'; lastName = 'User'; requiredActions = @()
    attributes = @{ org_id = @($orgId); org_type = @('CLIENT') }
}.GetEnumerator() | ForEach-Object { $rep | Add-Member -NotePropertyName $_.Key -NotePropertyValue $_.Value -Force }
Invoke-Kc PUT "/realms/$realm/users/$userUuid" $rep
"user attributes set (org_id=$orgId, org_type=CLIENT)"
Invoke-Kc PUT "/realms/$realm/users/$userUuid/reset-password" @{ type = 'password'; value = 'testuser'; temporary = $false }
"password set to 'testuser'"

"`nDone. Token endpoint: $kc/realms/$realm/protocol/openid-connect/token"
