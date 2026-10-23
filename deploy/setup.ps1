$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath '.env') { throw '.env already exists; keep it or remove it deliberately before regenerating.' }
Copy-Item .env.example .env
function Secret([int]$Bytes = 32) {
  $buffer = New-Object byte[] $Bytes
  [System.Security.Cryptography.RandomNumberGenerator]::Fill($buffer)
  [Convert]::ToBase64String($buffer)
}
$values = @{
  'MYSQL_ROOT_PASSWORD' = Secret 24
  'DOLPHINSCHEDULER_DB_PASSWORD' = Secret 24
  'PLATFORM_DB_PASSWORD' = Secret 24
  'PLATFORM_ENCRYPTION_KEY' = Secret 32
  'PLATFORM_ADMIN_PASSWORD' = Secret 24
  'PLATFORM_INTERNAL_TOKEN' = Secret 32
  'DOLPHINSCHEDULER_ADMIN_PASSWORD' = 'dolphinscheduler123'
  'DORIS_PASSWORD' = Secret 24
  'DORIS_WRITER_PASSWORD' = Secret 24
}
$text = Get-Content .env -Raw
foreach ($key in $values.Keys) { $text = [regex]::Replace($text, "(?m)^$key=.*$", "$key=$($values[$key])") }
Set-Content -LiteralPath .env -Value $text -NoNewline
Write-Host 'Created .env with random Base64 secrets. Run: docker compose up -d --build'
