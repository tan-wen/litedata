$ErrorActionPreference = 'Stop'
docker compose exec -T dolphinscheduler python3 /opt/platform/bin/bootstrap_engine.py
if ($LASTEXITCODE -ne 0) { throw 'Engine bootstrap failed.' }
$engine = Get-Content -LiteralPath './deploy/runtime/engine.json' -Raw | ConvertFrom-Json
$text = Get-Content -LiteralPath '.env' -Raw
foreach ($key in @('DOLPHINSCHEDULER_PROJECT_CODE','DOLPHINSCHEDULER_TOKEN')) {
  $text = [regex]::Replace($text, "(?m)^$key=.*$", "$key=$($engine.$key)")
}
Set-Content -LiteralPath '.env' -Value $text -NoNewline
Write-Host 'Engine bootstrap complete.'
