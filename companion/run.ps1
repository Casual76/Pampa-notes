<#
.SYNOPSIS
  Avvia il server WhisperX per Pampa Notes.

.EXAMPLE
  .\run.ps1
  .\run.ps1 -Model medium -Port 9000
  .\run.ps1 -Token unaparolasegreta

.DESCRIPTION
  Carica il modello e resta in ascolto. La prima volta scarica i pesi (qualche gigabyte per
  large-v3) e ci mette un po'; dopo parte in mezzo minuto.

  Stampa gli indirizzi su cui il telefono lo trova. Se non lo trova, quasi sempre e' il firewall
  di Windows: il comando per aprire la porta lo stampa lui.
#>
[CmdletBinding()]
param(
  [string]$Model = "large-v3",
  [ValidateSet("auto", "cuda", "cpu")][string]$Device = "auto",
  [int]$Port = 8765,
  [int]$BatchSize = 16,
  [string]$Token = ""
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

$venvPython = Join-Path $here ".venv\Scripts\python.exe"
if (-not (Test-Path $venvPython)) {
  Write-Host "Ambiente non trovato. Lancia prima .\setup.ps1" -ForegroundColor Red
  exit 1
}

# La porta aperta nel firewall: senza, il server parte e il telefono non lo vede, che dall'app si
# legge come "server non raggiungibile" e manda a cercare il problema dalla parte sbagliata.
$rule = Get-NetFirewallRule -DisplayName "Pampa Notes $Port" -ErrorAction SilentlyContinue
if (-not $rule) {
  Write-Host "  La porta $Port non risulta aperta nel firewall." -ForegroundColor Yellow
  Write-Host "  Se il telefono non trova il server, apri un PowerShell come amministratore e lancia:" -ForegroundColor Yellow
  Write-Host "     New-NetFirewallRule -DisplayName 'Pampa Notes $Port' -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Private" -ForegroundColor Cyan
  Write-Host ""
}

$arguments = @(
  "whisperx_server.py",
  "--model", $Model,
  "--device", $Device,
  "--port", $Port,
  "--batch-size", $BatchSize
)
if ($Token) { $arguments += @("--token", $Token) }

& $venvPython @arguments
