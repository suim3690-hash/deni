param(
    [string]$ApiBaseUrl = 'http://localhost:8080'
)

$ErrorActionPreference = 'Stop'
$api = $ApiBaseUrl.TrimEnd('/') + '/api/v1'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$name = "api-smoke-$stamp"
$birthDate = (Get-Date).AddMonths(-6).ToString('yyyy-MM-dd')
$updatedBirthDate = (Get-Date).AddMonths(-18).ToString('yyyy-MM-dd')
$key = [guid]::NewGuid().ToString()

function Assert-Equal($actual, $expected, [string]$label) {
    if ($actual -ne $expected) {
        throw "$label mismatch: expected '$expected', got '$actual'."
    }
    Write-Host "PASS $label"
}

try {
    $registrationBody = @{ name = $name; birthDate = $birthDate } | ConvertTo-Json -Compress
    $headers = @{ 'Idempotency-Key' = $key }
    $registered = Invoke-RestMethod -Method Post -Uri "$api/children" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $registrationBody
    if (-not $registered.childId) { throw 'Registration returned no childId.' }
    Assert-Equal $registered.name $name 'registration name'
    Assert-Equal $registered.safetyProfile.stage 'INFANT' 'registration stage'

    $repeated = Invoke-RestMethod -Method Post -Uri "$api/children" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $registrationBody
    Assert-Equal $repeated.childId $registered.childId 'idempotent retry'

    $childId = $registered.childId
    $dashboard = Invoke-RestMethod -Method Get -Uri "$api/dashboard?childId=$childId"
    Assert-Equal $dashboard.child.childId $childId 'dashboard child'
    Assert-Equal $dashboard.currentProfile.stage 'INFANT' 'dashboard stage'

    $profile = Invoke-RestMethod -Method Get -Uri "$api/children/$childId/safety-profile"
    Assert-Equal $profile.childId $childId 'profile child'
    Assert-Equal $profile.stage 'INFANT' 'profile stage'
    if ($profile.criteria.Count -lt 1) { throw 'Profile returned no safety criteria.' }
    Write-Host 'PASS profile criteria'

    $updatedName = "$name-updated"
    $updateBody = @{ name = $updatedName; birthDate = $updatedBirthDate } | ConvertTo-Json -Compress
    $updated = Invoke-RestMethod -Method Patch -Uri "$api/children/$childId" -ContentType 'application/json; charset=utf-8' -Body $updateBody
    Assert-Equal $updated.name $updatedName 'updated name'
    Assert-Equal $updated.safetyProfile.stage 'TODDLER' 'updated stage'

    $updatedDashboard = Invoke-RestMethod -Method Get -Uri "$api/dashboard?childId=$childId"
    Assert-Equal $updatedDashboard.child.name $updatedName 'dashboard updated name'
    Assert-Equal $updatedDashboard.currentProfile.stage 'TODDLER' 'dashboard updated stage'

    Write-Host "PASS core API flow. Test child retained in DB: $childId"
} catch {
    Write-Error "Core API flow failed: $($_.Exception.Message)"
    exit 1
}
