<#
.SYNOPSIS
  Smoke check for the Result types settings page (UI-1), sent exactly the way
  the browser sends it.

.DESCRIPTION
  Every request goes through the Vite dev server (default http://localhost:5173)
  -> API gateway (:8080) -> procedure service, using the same dev login as the
  SPA (Keycloak password grant, then POST /api/auth/test-exchange), the BFF
  session cookie, and the headers src/api/client.ts adds: X-Requested-With:
  sclera-spa and X-CSRF-Token copied from the sclera-csrf cookie.

  It walks the page's checklist: list, create at a rank, invalid fields,
  duplicate key, edit a system type, reorder, deactivate/activate, delete (204),
  and that a write without the CSRF token is refused.

  It is safe to run against data you care about: it creates one result type
  with a unique key (SMOKE_<random>) and deletes only that, and it restores
  Pass's name/colour/description and the original severity order at the end.

  Prerequisites: docker compose up -d; setup-keycloak.ps1; setup-openfga.ps1;
  procedure service from a branch that has result types; gateway whose
  procedure-service predicate includes /api/v1/result-types/** (rebuilt);
  `npm run dev` in frontend/.

.EXAMPLE
  .\frontend\scripts\check-result-types.ps1
.EXAMPLE
  .\frontend\scripts\check-result-types.ps1 -BaseUrl http://localhost:5173 -User testuser -Password testuser
#>
param(
    [string]$BaseUrl = 'http://localhost:5173',
    [string]$User = 'testuser',
    [string]$Password = 'testuser'
)

$ErrorActionPreference = 'Stop'
$script:passed = 0
$script:failed = 0
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$api = '/api/v1/result-types'

function Check([string]$name, [bool]$ok, [string]$detail = '') {
    if ($ok) { $script:passed++; Write-Output "PASS  $name" }
    else { $script:failed++; Write-Output "FAIL  $name  $detail" }
}

function CsrfCookie { ($session.Cookies.GetCookies($BaseUrl) | Where-Object Name -eq 'sclera-csrf').Value }

# The same headers as gatewayHeaders() in src/api/client.ts.
function Call([string]$method, [string]$path, $body = $null, [switch]$NoCsrf) {
    # Windows PowerShell keeps -Headers inside the WebSession and re-sends them on
    # later calls; clear them so each request carries only what the browser sends.
    $session.Headers.Clear()
    $headers = @{ 'X-Requested-With' = 'sclera-spa'; 'X-Correlation-ID' = [guid]::NewGuid().ToString() }
    if (-not $NoCsrf -and (CsrfCookie)) { $headers['X-CSRF-Token'] = CsrfCookie }
    $params = @{ UseBasicParsing = $true; Method = $method; Uri = "$BaseUrl$path"; WebSession = $session; Headers = $headers }
    if ($null -ne $body) {
        $params.ContentType = 'application/json'
        $params.Body = ($body | ConvertTo-Json -Depth 10 -Compress)
    }
    try {
        $r = Invoke-WebRequest @params
        $status = [int]$r.StatusCode; $text = $r.Content
    } catch {
        $resp = $_.Exception.Response
        if ($null -eq $resp) { throw }
        $status = [int]$resp.StatusCode
        $text = (New-Object IO.StreamReader($resp.GetResponseStream())).ReadToEnd()
    }
    $json = $null
    if ($text) { try { $json = $text | ConvertFrom-Json } catch { } }
    [pscustomobject]@{ Status = $status; Body = $json; Raw = $text }
}

function AllTypes { @((Call GET $api).Body.data | Sort-Object severityOrder) }
function RankOf([string]$id) { (AllTypes | Where-Object id -eq $id).severityOrder }

# --- 0. dev login, like devLogin() in src/api/client.ts ------------------------
$token = (Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/realms/sclera/protocol/openid-connect/token" `
    -Body @{ grant_type = 'password'; client_id = 'sclera-app'; username = $User; password = $Password }).access_token
$session.Headers.Clear()
$null = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$BaseUrl/api/auth/test-exchange" -WebSession $session `
    -Headers @{ 'X-Test-Authorization' = "Bearer $token"; 'X-Requested-With' = 'sclera-spa' }
$cookieNames = ($session.Cookies.GetCookies($BaseUrl) | ForEach-Object Name) -join ', '
Check '0. dev login sets the session and CSRF cookies' ($cookieNames -match 'sclera-session' -and $cookieNames -match 'sclera-csrf') "cookies: $cookieNames"

# --- 1. list ------------------------------------------------------------------
$original = AllTypes
$passT = $original | Where-Object key -eq 'PASS'
$failT = $original | Where-Object key -eq 'FAIL'
$ranks = ($original | ForEach-Object severityOrder) -join ','
$expected = (1..$original.Count) -join ','
Check '1. list is ordered 1..n with no gaps' ($ranks -eq $expected) "ranks: $ranks"
Check '1. Pass and Fail exist and are system types' ($passT.system -and $failT.system)
if (-not ($passT -and $failT)) { Write-Output 'Pass/Fail missing - stopping.'; exit 1 }

$key = 'SMOKE_' + (Get-Random -Minimum 10000 -Maximum 99999)
$smoke = $null
try {
    # --- 2. create at severity 2 ---------------------------------------------
    $r = Call POST $api @{ key = $key; name = 'Smoke check'; color = '#f39c12'; severityOrder = 2 }
    $smoke = $r.Body.data
    Check "2. create $key at severity 2 -> 201" ($r.Status -eq 201) $r.Raw
    Check '2. it is at rank 2 after reload' ((RankOf $smoke.id) -eq 2) "rank $(RankOf $smoke.id)"

    # --- 3. invalid fields (the form blocks these; the server agrees) ----------
    $r = Call POST $api @{ key = 'lower_case'; name = 'x'; color = 'orange' }
    $fields = ($r.Body.error.fieldErrors | ForEach-Object field) -join ','
    Check '3. bad key + colour -> 400 with fieldErrors for key and color' ($r.Status -eq 400 -and $fields -match 'key' -and $fields -match 'color') "$($r.Status) $fields"

    # --- 4. duplicate key ----------------------------------------------------
    $r = Call POST $api @{ key = $key; name = 'Again'; color = '#111111' }
    Check '4. duplicate key -> 409 with the message the form shows' ($r.Status -eq 409 -and $r.Body.error.message -eq "A result type with key '$key' already exists") $r.Raw

    # --- 5. edit a system type (rename + recolour allowed, key fixed) -----------
    $r = Call PUT "$api/$($passT.id)" @{ name = 'Compliant'; color = '#27ae60' }
    Check '5. rename + recolour Pass -> 200, key unchanged' ($r.Status -eq 200 -and $r.Body.data.name -eq 'Compliant' -and $r.Body.data.key -eq 'PASS') $r.Raw

    # --- 6. reorder: move to the top, then one down (always every id) ----------
    $ids = @($smoke.id) + @(AllTypes | Where-Object id -ne $smoke.id | ForEach-Object id)
    $r = Call POST "$api/reorder" @{ orderedIds = $ids }
    Check '6. reorder with every id -> 200' ($r.Status -eq 200) $r.Raw
    Check '6. moved to rank 1, kept after reload' ((RankOf $smoke.id) -eq 1) "rank $(RankOf $smoke.id)"
    $down = @(AllTypes | ForEach-Object id)
    $down[0], $down[1] = $down[1], $down[0]
    $null = Call POST "$api/reorder" @{ orderedIds = $down }
    Check '6. one step down saved (rank 2)' ((RankOf $smoke.id) -eq 2) "rank $(RankOf $smoke.id)"
    $r = Call POST "$api/reorder" @{ orderedIds = @($smoke.id) }
    Check '6. a partial reorder list is refused (422)' ($r.Status -eq 422) "$($r.Status)"

    # --- 7. deactivate / activate -------------------------------------------
    $r = Call POST "$api/$($smoke.id)/deactivate"
    Check '7. deactivate -> inactive, still listed' ($r.Status -eq 200 -and -not $r.Body.data.active -and (RankOf $smoke.id)) $r.Raw
    $r = Call POST "$api/$($smoke.id)/activate"
    Check '7. activate -> active again' ($r.Status -eq 200 -and $r.Body.data.active) $r.Raw

    # --- 8. system types cannot be deleted or deactivated -------------------
    Check '8. delete Pass is refused (422)' ((Call DELETE "$api/$($passT.id)").Status -eq 422)
    Check '8. deactivate Fail is refused (422)' ((Call POST "$api/$($failT.id)/deactivate").Status -eq 422)

    # --- 9. delete: 204 with an empty body; ranks close the gap --------------
    $r = Call DELETE "$api/$($smoke.id)"
    Check '9. delete -> 204 with an empty body' ($r.Status -eq 204 -and [string]::IsNullOrEmpty($r.Raw)) "$($r.Status) [$($r.Raw)]"
    if ($r.Status -eq 204) { $smoke = $null }
    $after = AllTypes
    Check '9. gone, and ranks are 1..n again' (-not ($after | Where-Object key -eq $key) -and (($after | ForEach-Object severityOrder) -join ',') -eq ((1..$after.Count) -join ','))

    # --- 10. CSRF: a write without X-CSRF-Token is refused --------------------
    $noCsrfKey = $key + '_NOCSRF'
    $r = Call POST $api @{ key = $noCsrfKey; name = 'x'; color = '#111111' } -NoCsrf
    Check '10. a write without X-CSRF-Token is refused (403)' ($r.Status -eq 403) "$($r.Status) $($r.Raw)"
    $leaked = AllTypes | Where-Object key -eq $noCsrfKey
    Check '10. and nothing was created' ($null -eq $leaked)
    if ($leaked) { $null = Call DELETE "$api/$($leaked.id)" }
}
finally {
    # --- restore: remove what this run created, put Pass and the order back ---
    if ($smoke) { $null = Call DELETE "$api/$($smoke.id)" }
    $null = Call PUT "$api/$($passT.id)" @{ name = $passT.name; color = $passT.color; description = $passT.description }
    $current = @(AllTypes | ForEach-Object id)
    $originalIds = @($original | ForEach-Object id | Where-Object { $current -contains $_ })
    if ($originalIds.Count -eq $current.Count) { $null = Call POST "$api/reorder" @{ orderedIds = $originalIds } }
}

$restored = (AllTypes | ForEach-Object { "$($_.severityOrder)=$($_.key)" }) -join ', '
Check 'restore: order and Pass are back as they were' ($restored -eq (($original | ForEach-Object { "$($_.severityOrder)=$($_.key)" }) -join ', ') -and (AllTypes | Where-Object key -eq 'PASS').name -eq $passT.name) $restored

Write-Output ''
Write-Output "RESULT: $script:passed passed, $script:failed failed"
if ($script:failed -gt 0) { exit 1 }
