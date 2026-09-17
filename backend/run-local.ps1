$ErrorActionPreference = 'Stop'

$environmentFile = Join-Path $PSScriptRoot '.env'
if (-not (Test-Path -LiteralPath $environmentFile)) {
    throw "Missing $environmentFile. Copy .env.example to .env and set DB_PASSWORD."
}

foreach ($rawLine in Get-Content -LiteralPath $environmentFile -Encoding UTF8) {
    $line = $rawLine.Trim()
    if ($line.Length -eq 0 -or $line.StartsWith('#')) {
        continue
    }

    $parts = $line.Split('=', 2)
    if ($parts.Length -ne 2) {
        throw "Invalid .env entry: $rawLine"
    }

    $name = $parts[0].Trim()
    $value = $parts[1].Trim()
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}

Push-Location $PSScriptRoot
try {
	& .\gradlew.bat bootRun '--args=--debug=false'
    if ($LASTEXITCODE -ne 0) {
        throw "Backend exited with code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}
