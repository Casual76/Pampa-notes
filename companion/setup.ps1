<#
.SYNOPSIS
  Prepara il server WhisperX per Pampa Notes.

.DESCRIPTION
  Crea un ambiente Python a se' dentro companion\.venv e ci installa WhisperX e il server. Le
  librerie CUDA non le tocca: dipendono dalla scheda e dal driver, non dal progetto, e installarne
  la versione sbagliata e' il modo piu' rapido di ritrovarsi con una GPU che non viene vista.
  Se torch non c'e' o non vede la scheda, lo dice e spiega cosa lanciare.

  Da rifare solo quando cambia qualcosa: per l'uso di tutti i giorni basta run.ps1.
#>
[CmdletBinding()]
param(
  [string]$Python = "python"
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

Write-Host "Pampa Notes - preparazione del server WhisperX" -ForegroundColor Cyan
Write-Host ""

# 1. Python
try {
  $version = & $Python --version 2>&1
} catch {
  Write-Host "Python non trovato. Installalo da python.org (3.10 o 3.11) e rilancia." -ForegroundColor Red
  exit 1
}
Write-Host "  $version"

# WhisperX vuole 3.9-3.12: su 3.13 e oltre certe dipendenze non hanno ancora i pacchetti compilati,
# e l'installazione fallisce a meta' con un errore che parla di compilatori C.
if ($version -match "Python 3\.(\d+)") {
  $minor = [int]$Matches[1]
  if ($minor -ge 13 -or $minor -lt 9) {
    Write-Host ""
    Write-Host "  Attenzione: WhisperX va su Python 3.9-3.12. Con questa versione l'installazione" -ForegroundColor Yellow
    Write-Host "  puo' fallire. Se succede, installa Python 3.11 e rilancia con:" -ForegroundColor Yellow
    Write-Host "     .\setup.ps1 -Python C:\Python311\python.exe" -ForegroundColor Yellow
    Write-Host ""
  }
}

# 2. L'ambiente
if (-not (Test-Path ".venv")) {
  Write-Host "  creo l'ambiente in companion\.venv…"
  & $Python -m venv .venv
}
$venvPython = Join-Path $here ".venv\Scripts\python.exe"

# 3. torch: c'e'? vede la scheda?
Write-Host "  controllo torch…"
$torch = & $venvPython -c "import torch, sys; print(torch.__version__); print(torch.cuda.is_available())" 2>&1
if ($LASTEXITCODE -ne 0) {
  Write-Host ""
  Write-Host "  torch non e' installato in questo ambiente." -ForegroundColor Yellow
  Write-Host "  Installalo con la riga che pytorch.org indica per la tua scheda, per esempio:" -ForegroundColor Yellow
  Write-Host "     .venv\Scripts\python.exe -m pip install torch torchaudio --index-url https://download.pytorch.org/whl/cu124" -ForegroundColor Cyan
  Write-Host "  Poi rilancia questo script." -ForegroundColor Yellow
  Write-Host ""
  Write-Host "  (Senza GPU funziona lo stesso, ma un'ora di lezione passa da qualche minuto a" -ForegroundColor DarkGray
  Write-Host "   qualche decina: in quel caso conviene Groq.)" -ForegroundColor DarkGray
  exit 1
}
$torchLines = $torch -split "`n"
Write-Host "    torch $($torchLines[0].Trim()), CUDA disponibile: $($torchLines[1].Trim())"

# 4. Il resto
Write-Host "  installo WhisperX e il server…"
& $venvPython -m pip install --upgrade pip --quiet
& $venvPython -m pip install -r requirements.txt

Write-Host ""
Write-Host "Fatto. Avvia con:" -ForegroundColor Green
Write-Host "   .\run.ps1" -ForegroundColor Cyan
Write-Host ""
