<#
.SYNOPSIS
  Costruisce PampaCompanionSetup-<versione>.exe con Inno Setup 6.

.DESCRIPTION
  La versione viene da un posto solo, companion\VERSION: la leggono anche /health e l'icona (per
  sapere se su GitHub ce n'e' una piu' nuova), quindi il setup non puo' dire un numero e il companion
  un altro.

  Servono due programmi, e questo script non li installa da solo: sono programmi del computer, non
  del progetto, e installarli e' una scelta di chi lo usa:

    * Inno Setup 6 (ISCC.exe):   winget install --id JRSoftware.InnoSetup -e
    * uv.exe, che finisce dentro il setup e prepara Python sul computer di chi installa:
        winget install --id astral-sh.uv -e
      oppure uv-x86_64-pc-windows-msvc.zip da https://github.com/astral-sh/uv/releases, con uv.exe
      estratto in companion\installer\vendor\.

  Il setup finisce fuori dal progetto (%LOCALAPPDATA%\PampaNotes\build\companion-installer), per lo
  stesso motivo delle build di Gradle: la cartella del progetto la sincronizza Google Drive.

  Pubblicarlo (una release companion-v<versione> su GitHub) e' un'altra cosa, e si chiede prima.

.PARAMETER OutDir
  Dove scrivere il setup.

.PARAMETER Uv
  Il percorso di uv.exe, se non e' in vendor\ ne' nel PATH.
#>
[CmdletBinding()]
param(
  [string]$OutDir = (Join-Path $env:LOCALAPPDATA "PampaNotes\build\companion-installer"),
  [string]$Uv = ""
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$companion = Split-Path -Parent $here

$version = (Get-Content -LiteralPath (Join-Path $companion "VERSION") -Raw).Trim()
if ($version -notmatch '^\d+\.\d+\.\d+$') {
  Write-Host "  companion\VERSION deve essere tre numeri (1.2.3): c'e' '$version'." -ForegroundColor Red
  exit 1
}

function Find-Iscc {
  $command = Get-Command iscc -ErrorAction SilentlyContinue
  if ($command) { return $command.Source }
  $candidates = @(
    (Join-Path ${env:ProgramFiles(x86)} "Inno Setup 6\ISCC.exe"),
    (Join-Path $env:ProgramFiles "Inno Setup 6\ISCC.exe"),
    (Join-Path $env:LOCALAPPDATA "Programs\Inno Setup 6\ISCC.exe")
  )
  foreach ($key in @(
      "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Inno Setup 6_is1",
      "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Inno Setup 6_is1",
      "HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\Inno Setup 6_is1")) {
    $location = (Get-ItemProperty -Path $key -ErrorAction SilentlyContinue).InstallLocation
    if ($location) { $candidates += (Join-Path $location "ISCC.exe") }
  }
  foreach ($candidate in $candidates) {
    if ($candidate -and (Test-Path -LiteralPath $candidate)) { return $candidate }
  }
  return $null
}

function Find-Uv {
  if ($Uv) {
    if (Test-Path -LiteralPath $Uv) { return (Resolve-Path -LiteralPath $Uv).Path }
    return $null
  }
  $vendored = Join-Path $here "vendor\uv.exe"
  if (Test-Path -LiteralPath $vendored) { return $vendored }
  $command = Get-Command uv -ErrorAction SilentlyContinue
  if ($command) { return $command.Source }
  return $null
}

Write-Host ""
Write-Host "Pampa Notes companion $version - costruisco il setup" -ForegroundColor Cyan

$iscc = Find-Iscc
if (-not $iscc) {
  Write-Host ""
  Write-Host "  Inno Setup 6 non c'e' su questo computer (cerco ISCC.exe)." -ForegroundColor Yellow
  if (Get-Command winget -ErrorAction SilentlyContinue) {
    # Solo una lettura: se winget lo conosce gia', e' installato in un posto che non ho guardato.
    $listed = & winget list --id JRSoftware.InnoSetup -e --accept-source-agreements 2>$null | Out-String
    if ($listed -match "JRSoftware") {
      Write-Host "  winget dice che e' installato, ma ISCC.exe non e' dove lo cerco: aggiungilo al PATH." -ForegroundColor Yellow
      exit 1
    }
  }
  Write-Host "  Installalo, poi rilancia questo script:" -ForegroundColor Yellow
  Write-Host "     winget install --id JRSoftware.InnoSetup -e" -ForegroundColor Cyan
  Write-Host ""
  exit 1
}

$uvExe = Find-Uv
if (-not $uvExe) {
  Write-Host ""
  Write-Host "  Manca uv.exe, che va dentro il setup." -ForegroundColor Yellow
  Write-Host "     winget install --id astral-sh.uv -e" -ForegroundColor Cyan
  Write-Host "  oppure scarica uv-x86_64-pc-windows-msvc.zip da https://github.com/astral-sh/uv/releases"
  Write-Host "  ed estrai uv.exe in $here\vendor\"
  Write-Host ""
  exit 1
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Write-Host "  ISCC: $iscc"
Write-Host "  uv:   $uvExe ($(& $uvExe --version))"
& $iscc "/DAppVersion=$version" "/DUvExe=$uvExe" "/O$OutDir" (Join-Path $here "PampaCompanion.iss")
if ($LASTEXITCODE -ne 0) {
  Write-Host "  ISCC si e' fermato (codice $LASTEXITCODE): l'errore e' qui sopra." -ForegroundColor Red
  exit $LASTEXITCODE
}

$setup = Join-Path $OutDir "PampaCompanionSetup-$version.exe"
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $setup).Hash.ToLower()
Write-Host ""
Write-Host "Fatto: $setup" -ForegroundColor Green
Write-Host "  sha256 $hash"
Write-Host ""
Write-Host "Per pubblicarlo (e' un'azione pubblica: chiedi prima):" -ForegroundColor DarkGray
Write-Host "   gh release create companion-v$version `"$setup`" --repo Casual76/Pampa-notes --title `"Companion $version`" --notes `"...`"" -ForegroundColor DarkGray
Write-Host ""
