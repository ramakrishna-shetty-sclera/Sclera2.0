<#
.SYNOPSIS
  Smoke check for the Procedures editor (the UI-2 editor branch), sent exactly
  the way the browser sends it.

.DESCRIPTION
  Every request goes through the Vite dev server (default http://localhost:5173)
  -> API gateway (:8080) -> procedure service, using the same dev login as the
  SPA (Keycloak password grant, then POST /api/auth/test-exchange), the BFF
  session cookie, and the headers src/api/client.ts adds: X-Requested-With:
  sclera-spa and X-CSRF-Token copied from the sclera-csrf cookie.

  It walks what the editor can do, in the order an author would do it: author a
  section and a Yes/No question, read the minted keys back, map the answers to
  result types, hang a follow-up off one answer, add a bounded number question,
  flag a work order, be refused for a follow-up pointing at the wrong answer, be
  refused a publish with every reason at once, publish, edit, publish again, and
  diff the two versions.

  It is safe to run against data you care about: everything it creates lives
  under one procedure named "Editor smoke <random>", which it archives at the
  end. It changes nothing else — result types are only read.

  Prerequisites: docker compose up -d; setup-keycloak.ps1; setup-openfga.ps1;
  procedure service from a branch that has the content model (feature 4);
  gateway whose procedure-service predicate includes
  /api/v1/procedure-templates/** (rebuilt); `npm run dev` in frontend/.

.EXAMPLE
  .\frontend\scripts\check-procedure-editor.ps1
.EXAMPLE
  .\frontend\scripts\check-procedure-editor.ps1 -BaseUrl http://localhost:5173 -User testuser -Password testuser
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
$api = '/api/v1/procedure-templates'

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
        $params.Body = ($body | ConvertTo-Json -Depth 20 -Compress)
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

# The editor sends every field on every item, which is what the document is; a
# field left unset is simply absent from the canonical bytes.
function Item([hashtable]$fields) {
    $item = @{ text = ''; type = 'TEXT'; required = $false; workOrder = $false; options = @(); follow = @() }
    foreach ($k in $fields.Keys) { $item[$k] = $fields[$k] }
    $item
}

function Opt([string]$label, [string]$result = $null, [string]$key = $null) {
    $o = @{ label = $label }
    if ($result) { $o.result = $result }
    if ($key) { $o.key = $key }
    $o
}

# Every item at every depth, parents first — the server's flatten(), so a
# follow-up's minted key can be found without walking the tree by hand.
function Flatten($items) {
    foreach ($i in $items) { $i; if ($i.follow) { Flatten $i.follow } }
}
function ByText($definition, [string]$text) { Flatten $definition.items | Where-Object text -eq $text }

# --- 0. dev login, like devLogin() in src/api/client.ts ------------------------
$token = (Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/realms/sclera/protocol/openid-connect/token" `
    -Body @{ grant_type = 'password'; client_id = 'sclera-app'; username = $User; password = $Password }).access_token
$session.Headers.Clear()
$null = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$BaseUrl/api/auth/test-exchange" -WebSession $session `
    -Headers @{ 'X-Test-Authorization' = "Bearer $token"; 'X-Requested-With' = 'sclera-spa' }
$cookieNames = ($session.Cookies.GetCookies($BaseUrl) | ForEach-Object Name) -join ', '
Check '0. dev login sets the session and CSRF cookies' ($cookieNames -match 'sclera-session' -and $cookieNames -match 'sclera-csrf') "cookies: $cookieNames"

# --- 1. the organization's result types, which is what an answer can mean -----
$resultTypes = @((Call GET '/api/v1/result-types').Body.data | Where-Object active)
$pass = ($resultTypes | Where-Object key -eq 'PASS').key
$fail = ($resultTypes | Where-Object key -eq 'FAIL').key
Check '1. Pass and Fail are active, so answers have something to mean' ($pass -and $fail) "active: $(($resultTypes | ForEach-Object key) -join ', ')"

$procedure = $null
try {
    # --- 2. create, the way the editor does: identity plus the whole draft ----
    $name = "Editor smoke $(Get-Random -Maximum 99999)"
    $r = Call POST $api @{
        name       = $name
        definition = @{
            schema = 2
            items  = @(
                Item @{ text = 'Fire safety'; type = 'SECTION' },
                Item @{ text = 'Is the fire exit clear?'; type = 'YES_NO'; required = $true
                        options = @((Opt 'Yes' $pass), (Opt 'No' $fail)) }
            )
        }
    }
    Check '2. create a procedure with a section and a Yes/No question' ($r.Status -eq 200 -or $r.Status -eq 201) "$($r.Status) $($r.Raw)"
    $procedure = $r.Body.data
    Check '2. it opens draft v1 and publishes nothing' ($procedure.draftVersionNo -eq 1 -and $null -eq $procedure.currentPublishedVersionNo) `
        "draft=$($procedure.draftVersionNo) published=$($procedure.currentPublishedVersionNo)"

    # --- 3. the keys the server minted, which the editor reads back -----------
    $draft = (Call GET "$api/$($procedure.id)/draft").Body.data
    $section = ByText $draft.definition 'Fire safety'
    $exit = ByText $draft.definition 'Is the fire exit clear?'
    Check '3. the section was keyed s*, the question q*' ($section.key -match '^s\d+$' -and $exit.key -match '^q\d+$') `
        "section=$($section.key) question=$($exit.key)"
    $no = $exit.options | Where-Object label -eq 'No'
    Check '3. each answer was keyed o*, which is what a follow-up points at' ($no.key -match '^o\d+$') `
        "answers: $(($exit.options | ForEach-Object { "$($_.label)=$($_.key)/$($_.result)" }) -join ', ')"
    Check '3. the answers kept the result types they were given' `
        (($exit.options | Where-Object label -eq 'Yes').result -eq $pass -and $no.result -eq $fail)

    # --- 4. a follow-up pointing at an answer that is not its parent's --------
    $r = Call PUT "$api/$($procedure.id)/draft" @{
        rowVersion = $draft.rowVersion
        definition = @{
            schema = 2
            items  = @(
                Item @{ key = $section.key; text = 'Fire safety'; type = 'SECTION' },
                Item @{ key = $exit.key; text = 'Is the fire exit clear?'; type = 'YES_NO'; required = $true
                        options = @((Opt 'Yes' $pass $exit.options[0].key), (Opt 'No' $fail $no.key))
                        follow = @(Item @{ text = 'Describe the obstruction'; when = 'o999' }) }
            )
        }
    }
    Check '4. a follow-up on an answer that does not exist is refused (400)' ($r.Status -eq 400) "$($r.Status) $($r.Raw)"
    Check '4. and the refusal names the problem' ($r.Body.error.message -match 'does not belong to') $r.Body.error.message

    # --- 5. the same follow-up, pointed at the real answer --------------------
    $r = Call PUT "$api/$($procedure.id)/draft" @{
        rowVersion = $draft.rowVersion
        changeNote = 'Follow-up on No, and a gauge reading'
        definition = @{
            schema = 2
            items  = @(
                Item @{ key = $section.key; text = 'Fire safety'; type = 'SECTION' },
                Item @{ key = $exit.key; text = 'Is the fire exit clear?'; type = 'YES_NO'; required = $true
                        workOrder = $true; alertProfile = 'fire-urgent'
                        options = @((Opt 'Yes' $pass $exit.options[0].key), (Opt 'No' $fail $no.key))
                        follow = @(Item @{ text = 'Describe the obstruction'; type = 'TEXT'; required = $true; when = $no.key }) },
                Item @{ text = 'Extinguisher gauge reading'; type = 'INTEGER'; unit = 'psi'; min = 100; max = 175 }
            )
        }
    }
    Check '5. a follow-up on "No", a work order and a bounded reading all save' ($r.Status -eq 200) "$($r.Status) $($r.Raw)"
    $draft = (Call GET "$api/$($procedure.id)/draft").Body.data
    $describe = ByText $draft.definition 'Describe the obstruction'
    Check '5. the follow-up was keyed and still points at "No"' ($describe.key -match '^q\d+$' -and $describe.when -eq $no.key) `
        "key=$($describe.key) when=$($describe.when)"
    $gauge = ByText $draft.definition 'Extinguisher gauge reading'
    Check '5. the reading kept its unit and bounds' ($gauge.unit -eq 'psi' -and $gauge.min -eq 100 -and $gauge.max -eq 175) `
        "unit=$($gauge.unit) min=$($gauge.min) max=$($gauge.max)"
    $reread = ByText $draft.definition 'Is the fire exit clear?'
    Check '5. the work order and its alert profile were stored' ($reread.workOrder -eq $true -and $reread.alertProfile -eq 'fire-urgent') `
        "workOrder=$($reread.workOrder) alertProfile=$($reread.alertProfile)"

    # --- 6. publish refused, naming every reason at once ----------------------
    $r = Call PUT "$api/$($procedure.id)/draft" @{
        rowVersion = $draft.rowVersion
        definition = @{
            schema = 2
            items  = @(
                Item @{ key = $exit.key; text = 'Is the fire exit clear?'; type = 'YES_NO'
                        options = @((Opt 'Yes' 'NOT_A_RESULT' $exit.options[0].key), (Opt 'No' $null $no.key)) },
                Item @{ text = 'Pick one'; type = 'RADIO'; options = @((Opt 'Only answer')) }
            )
        }
    }
    Check '6. a half-finished draft still saves' ($r.Status -eq 200) "$($r.Status) $($r.Raw)"
    $draft = (Call GET "$api/$($procedure.id)/draft").Body.data
    $r = Call POST "$api/$($procedure.id)/publish" @{ rowVersion = $draft.rowVersion }
    Check '6. publishing it is refused' ($r.Status -ge 400) "$($r.Status) $($r.Raw)"
    $reasons = @(($r.Body.error.message -split '; '))
    Check '6. and every reason comes back at once, not one per attempt' ($reasons.Count -ge 3) `
        "$($reasons.Count): $($r.Body.error.message)"
    Check '6. including the answer mapped to a result type the org does not have' `
        ($r.Body.error.message -match 'NOT_A_RESULT') $r.Body.error.message

    # --- 7. put it right and publish -----------------------------------------
    $r = Call PUT "$api/$($procedure.id)/draft" @{
        rowVersion = $draft.rowVersion
        definition = @{
            schema = 2
            items  = @(
                Item @{ key = $section.key; text = 'Fire safety'; type = 'SECTION' },
                Item @{ key = $exit.key; text = 'Is the fire exit clear?'; type = 'YES_NO'; required = $true
                        options = @((Opt 'Yes' $pass $exit.options[0].key), (Opt 'No' $fail $no.key))
                        follow = @(Item @{ key = $describe.key; text = 'Describe the obstruction'; type = 'TEXT'; required = $true; when = $no.key }) },
                Item @{ key = $gauge.key; text = 'Extinguisher gauge reading'; type = 'INTEGER'; unit = 'psi'; min = 100; max = 175 }
            )
        }
    }
    Check '7. the fixed draft saves' ($r.Status -eq 200) "$($r.Status) $($r.Raw)"
    $draft = (Call GET "$api/$($procedure.id)/draft").Body.data
    $r = Call POST "$api/$($procedure.id)/publish" @{ rowVersion = $draft.rowVersion }
    Check '7. and publishes as v1' ($r.Status -eq 200 -and $r.Body.data.newVersion -eq $true -and $r.Body.data.version.versionNo -eq 1) `
        "$($r.Status) $($r.Raw)"

    # --- 8. reword, publish again, and diff -----------------------------------
    $r = Call POST "$api/$($procedure.id)/draft" @{ fromVersionNo = 1 }
    Check '8. a new draft opens from v1' ($r.Status -eq 200 -and $r.Body.data.versionNo -eq 2) "$($r.Status) $($r.Raw)"
    $draft = $r.Body.data
    $r = Call PUT "$api/$($procedure.id)/draft" @{
        rowVersion = $draft.rowVersion
        changeNote = 'Reworded the exit question'
        definition = @{
            schema = 2
            items  = @(
                Item @{ key = $section.key; text = 'Fire safety'; type = 'SECTION' },
                Item @{ key = $exit.key; text = 'Is the fire exit completely clear?'; type = 'YES_NO'; required = $true
                        options = @((Opt 'Yes' $pass $exit.options[0].key), (Opt 'No — blocked' $fail $no.key))
                        follow = @(Item @{ key = $describe.key; text = 'Describe the obstruction'; type = 'TEXT'; required = $true; when = $no.key }) },
                Item @{ key = $gauge.key; text = 'Extinguisher gauge reading'; type = 'INTEGER'; unit = 'psi'; min = 100; max = 175 }
            )
        }
    }
    Check '8. the reword saves' ($r.Status -eq 200) "$($r.Status) $($r.Raw)"
    $draft = (Call GET "$api/$($procedure.id)/draft").Body.data
    $r = Call POST "$api/$($procedure.id)/publish" @{ rowVersion = $draft.rowVersion }
    Check '8. and publishes as v2' ($r.Status -eq 200 -and $r.Body.data.version.versionNo -eq 2) "$($r.Status) $($r.Raw)"

    $diff = (Call GET "$api/$($procedure.id)/diff?from=1&to=2").Body.data
    $change = $diff.diff.items | Where-Object key -eq $exit.key
    Check '9. the diff reports one change on the question, not a delete and an add' `
        ($diff.diff.items.Count -eq 1 -and $change.kind -eq 'MODIFIED') `
        "$(($diff.diff.items | ForEach-Object { "$($_.key)=$($_.kind)" }) -join ', ')"
    Check '9. and names what changed: its text and its answers' `
        (($change.changedFields -contains 'text') -and ($change.changedFields -contains 'options')) `
        "changedFields: $($change.changedFields -join ', ')"
    Check '9. the follow-up survived the reword with its key intact' `
        ((ByText (Call GET "$api/$($procedure.id)/versions/2").Body.data.definition 'Describe the obstruction').key -eq $describe.key)

    # --- 10. the browser's own guard -----------------------------------------
    $r = Call POST $api @{ name = 'csrf probe' } -NoCsrf
    Check '10. a write without X-CSRF-Token is refused (403)' ($r.Status -eq 403) "$($r.Status) $($r.Raw)"
}
finally {
    # --- restore: archive what this run created ------------------------------
    if ($procedure) { $null = Call POST "$api/$($procedure.id)/archive" }
}

if ($procedure) {
    $after = (Call GET "$api/$($procedure.id)").Body.data
    Check 'restore: the smoke procedure is archived' ($after.status -eq 'ARCHIVED') "status=$($after.status)"
}

Write-Output ''
Write-Output "RESULT: $script:passed passed, $script:failed failed"
if ($script:failed -gt 0) { exit 1 }
