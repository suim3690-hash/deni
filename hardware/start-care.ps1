param([string]$PiHost = '172.30.1.10', [switch]$CheckConfig)
$ErrorActionPreference = 'Stop'
$configPath = Join-Path $env:LOCALAPPDATA 'Deni\robot-001-runtime.json'
if (-not (Test-Path -LiteralPath $configPath)) {
    throw 'Saved device credentials are missing for this Windows account.'
}
$config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
$secureToken = ConvertTo-SecureString $config.encryptedToken
$credential = New-Object System.Management.Automation.PSCredential('robot', $secureToken)
$env:ROBOT_HTTP_URL = $config.ROBOT_HTTP_URL
$env:ROBOT_WS_URL = $config.ROBOT_WS_URL
$env:ROBOT_DEVICE_ID = $config.ROBOT_DEVICE_ID
$env:ROBOT_DEVICE_TOKEN = $credential.GetNetworkCredential().Password
if (-not $env:ROBOT_DEVICE_TOKEN) { throw 'Device token is empty.' }
if ($CheckConfig) { Write-Output 'Device configuration loaded successfully.'; exit 0 }
$runtimePath = Join-Path $PSScriptRoot 'care_runtime.py'
$existing = Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq 'python.exe' -and $_.CommandLine -like '*care_runtime.py*'
}
if ($existing) { throw 'care_runtime.py is already running. Stop its terminal with Ctrl+C first.' }
Push-Location $PSScriptRoot
try {
    & .\.venv\Scripts\python.exe $runtimePath --host $PiHost
    if ($LASTEXITCODE -ne 0) { throw "Runtime exited with code $LASTEXITCODE." }
} finally {
    Remove-Item Env:ROBOT_DEVICE_TOKEN -ErrorAction SilentlyContinue
    Pop-Location
}
