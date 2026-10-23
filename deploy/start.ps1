$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
  if (-not (Test-Path -LiteralPath '.env')) { & ./deploy/setup.ps1 }
  docker compose up -d --wait mysql demo-mysql
  if ($LASTEXITCODE -ne 0) { throw 'MySQL startup failed; see docker compose logs.' }
  docker compose exec -T mysql bash /docker-entrypoint-initdb.d/01-scheduler.sh
  if ($LASTEXITCODE -ne 0) { throw 'Scheduler schema initialization failed.' }
  docker compose up -d --build --wait doris dolphinscheduler
  if ($LASTEXITCODE -ne 0) { throw 'Engine startup failed; see docker compose logs.' }
  & ./deploy/bootstrap.ps1
  docker compose up -d --build --wait platform
  if ($LASTEXITCODE -ne 0) { throw 'Platform startup failed.' }
  Write-Host 'Open http://localhost:8080; admin initial password is stored in .env.'
} finally { Pop-Location }

