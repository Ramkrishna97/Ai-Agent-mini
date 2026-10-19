$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { Write-Error 'Maven not found. Install Maven and add it to PATH.' }
Start-Job -ScriptBlock { Start-Sleep -Seconds 7; Start-Process 'http://localhost:8080' } | Out-Null
mvn spring-boot:run
