<#
.SYNOPSIS
  Avvia il server WhisperX per Pampa Notes, con la console aperta.

.EXAMPLE
  .\run.ps1
  .\run.ps1 -Model medium -Port 9000
  .\run.ps1 -IdleMinutes 30
  .\run.ps1 -Token unaparolasegreta

.DESCRIPTION
  Si mette in ascolto subito: il modello si carica alla prima registrazione che arriva, e se ne va
  da solo dopo qualche minuto di silenzio per restituire la VRAM.

  Per l'uso di tutti i giorni c'e' avvia-in-background.cmd, che mette il server accanto all'orologio
  senza finestra; avvia.cmd fa la stessa cosa di questo script con un doppio clic.

  Le impostazioni stanno in config.json, accanto a questo file. Quello che si passa qui vale sopra a
  quel file, per questa volta sola: i parametri non hanno un default perche' il default e' quello
  che c'e' scritto li'.

  Se il tablet non lo trova, quasi sempre e' il firewall: apri-firewall.cmd apre la porta verso la
  rete di casa, e basta lanciarlo una volta.
#>
[CmdletBinding()]
param(
  [string]$Model,
  [ValidateSet("auto", "cuda", "cpu")][string]$Device,
  [int]$Port,
  [int]$BatchSize,
  [string]$Token,
  # Dopo quanti minuti senza richieste liberare la memoria della scheda. 0 = tenerla occupata.
  [int]$IdleMinutes,
  # Carica il modello subito invece che alla prima registrazione.
  [switch]$Preload
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

$venvPython = Join-Path $here ".venv\Scripts\python.exe"
if (-not (Test-Path $venvPython)) {
  Write-Host "Ambiente non trovato. Lancia prima installa.cmd (oppure .\setup.ps1)" -ForegroundColor Red
  exit 1
}

# La porta su cui controllare il firewall: quella passata, altrimenti quella di config.json,
# altrimenti quella di partenza. Deve essere la stessa che il server usera' davvero.
$effectivePort = 8765
$configPath = Join-Path $here "config.json"
if (Test-Path $configPath) {
  try {
    $stored = Get-Content $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($null -ne $stored.port) { $effectivePort = [int]$stored.port }
  } catch {
    # Un config.json rotto lo segnala il server, che sa spiegarlo meglio.
  }
}
if ($PSBoundParameters.ContainsKey("Port")) { $effectivePort = $Port }

# Il firewall si controlla per porta, non per nome: una regola chiamata come vogliamo noi puo'
# esistere ed essere quella sbagliata (per esempio creata solo per il profilo Private, mentre la
# rete di casa e' classificata Public). Cosi' invece si guarda se qualcosa lascia entrare davvero.
$openForPort = Get-NetFirewallPortFilter -ErrorAction SilentlyContinue |
  Where-Object { $_.Protocol -eq "TCP" -and $_.LocalPort -eq [string]$effectivePort } |
  Get-NetFirewallRule -ErrorAction SilentlyContinue |
  Where-Object { $_.Direction -eq "Inbound" -and $_.Action -eq "Allow" -and $_.Enabled -eq "True" }

if (-not $openForPort) {
  Write-Host "  La porta $effectivePort non risulta aperta nel firewall." -ForegroundColor Yellow
  $public = Get-NetConnectionProfile -ErrorAction SilentlyContinue | Where-Object { $_.NetworkCategory -eq "Public" }
  if ($public) {
    Write-Host "  E la rete di questo computer e' classificata Public, dove Windows e' piu' severo." -ForegroundColor Yellow
  }
  Write-Host "  Se il tablet non lo trova, lancia apri-firewall.cmd: chiede lui i permessi." -ForegroundColor Cyan
  Write-Host ""
}

# Solo quello che e' stato scritto davvero: un parametro non passato non deve coprire config.json.
$arguments = @("whisperx_server.py")
if ($PSBoundParameters.ContainsKey("Model"))       { $arguments += @("--model", $Model) }
if ($PSBoundParameters.ContainsKey("Device"))      { $arguments += @("--device", $Device) }
if ($PSBoundParameters.ContainsKey("Port"))        { $arguments += @("--port", $Port) }
if ($PSBoundParameters.ContainsKey("BatchSize"))   { $arguments += @("--batch-size", $BatchSize) }
if ($PSBoundParameters.ContainsKey("IdleMinutes")) { $arguments += @("--idle-minutes", $IdleMinutes) }
if ($Token)   { $arguments += @("--token", $Token) }
if ($Preload) { $arguments += "--preload" }

& $venvPython @arguments
