<#
.SYNOPSIS
  Prepara il server WhisperX per Pampa Notes.

.DESCRIPTION
  Crea un ambiente Python a se' dentro companion\.venv, ci installa WhisperX e il server, e poi
  rimette torch nella versione per la scheda. L'ordine conta: WhisperX si porta dietro un torch
  senza CUDA che scavalca qualunque cosa ci fosse prima, quindi la versione CUDA va messa DOPO, e
  senza dipendenze, perche' torch e' l'unico pezzo da sostituire.

  WhisperX va su Python 3.9-3.12. Se sul computer c'e' solo un Python piu' nuovo lo script lo
  dice, e con -InstallPython installa lui Python 3.11, accanto a quello che c'e' e senza toccarlo.

  Da rifare solo quando cambia qualcosa: per l'uso di tutti i giorni basta run.ps1.

.PARAMETER Python
  L'interprete da usare. Senza, ne cerca uno adatto da solo (3.11, 3.12 o 3.10).

.PARAMETER Cuda
  I pacchetti torch da mettere: cu128 per i driver recenti (RTX 30, 40 e 50), cu126 per driver
  piu' vecchi, cpu per andare senza scheda.

.PARAMETER InstallPython
  Se non trova un Python adatto, lo installa con winget.

.EXAMPLE
  .\setup.ps1
  .\setup.ps1 -InstallPython
  .\setup.ps1 -Cuda cu126
  .\setup.ps1 -Python C:\Python311\python.exe
#>
[CmdletBinding()]
param(
  [string]$Python = "",
  [ValidateSet("cu128", "cu126", "cpu")][string]$Cuda = "cu128",
  [switch]$InstallPython
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

# La coppia provata: whisperx 3.8 con torch 2.8. Un torch piu' nuovo di solito va, ma questo e'
# quello che si sa funzionare, e su un server che deve solo trascrivere "si sa" vale piu' di "nuovo".
$torchVersion = "2.8.0"

Write-Host "Pampa Notes - preparazione del server WhisperX" -ForegroundColor Cyan
Write-Host ""

# Un interprete e' buono se e' un 3.9-3.12: su 3.13 e oltre certe dipendenze non hanno i pacchetti
# compilati, e l'installazione muore a meta' con un errore che parla di compilatori C.
function Test-PythonVersion([string]$exe) {
  $old = $ErrorActionPreference
  $ErrorActionPreference = "SilentlyContinue"
  try {
    $v = & $exe --version 2>$null
    if ($v -match "Python 3\.(\d+)") { $m = [int]$Matches[1]; return ($m -ge 9 -and $m -le 12) }
    return $false
  } catch { return $false } finally { $ErrorActionPreference = $old }
}

function Find-Python {
  foreach ($minor in 11, 12, 10) {
    $exe = Join-Path $env:LOCALAPPDATA "Programs\Python\Python3$minor\python.exe"
    if (Test-Path $exe) { return $exe }
    $exe = "C:\Python3$minor\python.exe"
    if (Test-Path $exe) { return $exe }
  }
  # Il launcher "py" sa dove stanno tutti i Python installati, anche quelli messi altrove.
  if (Get-Command py -ErrorAction SilentlyContinue) {
    foreach ($minor in 11, 12, 10) {
      $old = $ErrorActionPreference
      $ErrorActionPreference = "SilentlyContinue"
      try {
        $p = & py "-3.$minor" -c "import sys; print(sys.executable)" 2>$null
        if ($LASTEXITCODE -eq 0 -and $p) { return "$p".Trim() }
      } catch {} finally { $ErrorActionPreference = $old }
    }
  }
  if (Test-PythonVersion "python") { return "python" }
  return $null
}

# 1. Python
if ($Python) {
  if (-not (Test-PythonVersion $Python)) {
    Write-Host "  $Python non e' un Python 3.9-3.12, e WhisperX non va sugli altri." -ForegroundColor Red
    exit 1
  }
} else {
  $Python = Find-Python
  if (-not $Python -and $InstallPython) {
    Write-Host "  installo Python 3.11 con winget, accanto a quello che c'e'..."
    & winget install --id Python.Python.3.11 -e --accept-package-agreements --accept-source-agreements
    $Python = Find-Python
  }
  if (-not $Python) {
    Write-Host ""
    Write-Host "  Non trovo un Python adatto: WhisperX va su 3.9-3.12, non su quelli piu' nuovi." -ForegroundColor Yellow
    Write-Host "  Lo installo io, accanto a quello che c'e', se rilanci con:" -ForegroundColor Yellow
    Write-Host "     .\setup.ps1 -InstallPython" -ForegroundColor Cyan
    Write-Host "  Oppure, se ce l'hai gia' da qualche parte:" -ForegroundColor Yellow
    Write-Host "     .\setup.ps1 -Python C:\percorso\python.exe" -ForegroundColor Cyan
    Write-Host ""
    exit 1
  }
}
Write-Host "  $(& $Python --version)   $Python"

# 2. L'ambiente
if (-not (Test-Path ".venv")) {
  Write-Host "  creo l'ambiente in companion\.venv..."
  & $Python -m venv .venv
}
$venvPython = Join-Path $here ".venv\Scripts\python.exe"

# 3. WhisperX e il server. Si porta dietro un torch per CPU: e' previsto, lo si cambia al passo 4.
Write-Host "  installo WhisperX e il server (qualche minuto)..."
& $venvPython -m pip install --upgrade pip --quiet
& $venvPython -m pip install -r requirements.txt
if ($LASTEXITCODE -ne 0) {
  Write-Host "  pip ha fallito: l'errore e' qui sopra." -ForegroundColor Red
  exit 1
}

# 4. torch per la scheda. --force-reinstall perche' pip vede gia' un torch e crederebbe di avere
# finito; --no-deps perche' le dipendenze ci sono gia' e torch e' l'unico pezzo da sostituire.
if ($Cuda -ne "cpu") {
  Write-Host "  metto torch $torchVersion per CUDA ($Cuda, un paio di gigabyte)..."
  & $venvPython -m pip install --force-reinstall --no-deps "torch==$torchVersion" "torchaudio==$torchVersion" --index-url "https://download.pytorch.org/whl/$Cuda"
  if ($LASTEXITCODE -ne 0) {
    Write-Host "  pip ha fallito: l'errore e' qui sopra. Con un driver vecchio prova -Cuda cu126." -ForegroundColor Red
    exit 1
  }
}

# 5. La prova: si importa, e vede la scheda?
$check = @(& $venvPython -c "import torch, whisperx; print(torch.__version__); print(torch.cuda.is_available()); print(torch.cuda.get_device_name(0) if torch.cuda.is_available() else '-')")
if ($LASTEXITCODE -ne 0) {
  Write-Host "  torch o whisperx non si importano: l'errore e' qui sopra." -ForegroundColor Red
  exit 1
}
Write-Host ""
Write-Host "  torch $($check[0])"
if ("$($check[1])".Trim() -eq "True") {
  Write-Host "  scheda: $($check[2])" -ForegroundColor Green
} elseif ($Cuda -eq "cpu") {
  Write-Host "  senza scheda: funziona, ma un'ora di lezione ci mette decine di minuti." -ForegroundColor Yellow
} else {
  Write-Host "  torch non vede la scheda. Quasi sempre e' il driver troppo vecchio per $Cuda:" -ForegroundColor Yellow
  Write-Host "  aggiornalo da nvidia.com, oppure rilancia con -Cuda cu126." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Fatto. Avvia con:" -ForegroundColor Green
Write-Host "   .\run.ps1" -ForegroundColor Cyan
Write-Host ""
