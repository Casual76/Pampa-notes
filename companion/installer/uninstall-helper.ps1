<#
.SYNOPSIS
  Le parti della disinstallazione che Inno Setup non sa fare da solo.

.DESCRIPTION
  La chiama PampaCompanion.iss, un'azione alla volta:

    stop           ferma l'icona e il server di questa cartella (e solo di questa: un companion
                   lanciato da un'altra cartella, per esempio quella del progetto, non si tocca);
    autostart-off  toglie il collegamento da Esecuzione automatica, se punta a questa cartella;
    firewall-off   toglie la regola "Pampa Notes <porta>", chiedendo l'amministratore a Windows;
    purge-archive  cancella l'archivio dei file, ma solo se e' davvero un archivio del companion
                   (ci sono archive.db o blobs/): un archive_root scritto male non deve portarsi via
                   una cartella qualunque;
    purge-models   cancella dalla cache di Hugging Face i modelli che il companion scarica, e solo
                   quelli: Whisper (Systran/faster-whisper-*), le voci (pyannote/*) e l'allineamento
                   delle parole (jonatasgrosman/wav2vec2-*). Sono gigabyte, e senza il companion non
                   li usa nessuno; il resto della cache e' di altri programmi e resta.

.PARAMETER App
  La cartella del companion installato.
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$App,
  [Parameter(Mandatory = $true)][ValidateSet("stop", "autostart-off", "firewall-off", "purge-archive", "purge-models")][string]$Action
)

# I modelli che il companion scarica nella cache di Hugging Face (install.py, fetch_model.py e le
# lezioni). Una cartella per repository, "models--<autore>--<nome>": si toglie solo quello che
# corrisponde qui, mai la cache intera.
$ModelPatterns = @("models--Systran--faster-whisper-*", "models--pyannote--*", "models--jonatasgrosman--wav2vec2-*")

function Get-HubCache {
  # Lo stesso ordine di huggingface_hub: HF_HUB_CACHE, poi HF_HOME\hub, poi ~\.cache\huggingface\hub.
  if ($env:HF_HUB_CACHE) { return $env:HF_HUB_CACHE }
  if ($env:HF_HOME) { return (Join-Path $env:HF_HOME "hub") }
  return (Join-Path $env:USERPROFILE ".cache\huggingface\hub")
}

$ErrorActionPreference = "SilentlyContinue"
$App = (Resolve-Path -LiteralPath $App).Path.TrimEnd("\")

function Read-Config {
  $path = Join-Path $App "config.json"
  if (Test-Path -LiteralPath $path) {
    try { return Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json } catch { }
  }
  return $null
}

switch ($Action) {
  "stop" {
    # Solo Python, uv e ffmpeg: anche il disinstallatore ha questa cartella nella sua riga di comando.
    $names = @("python.exe", "pythonw.exe", "uv.exe", "ffmpeg.exe")
    $mine = Get-CimInstance Win32_Process | Where-Object { $names -contains $_.Name.ToLower() } | Where-Object {
      ($_.ExecutablePath -and $_.ExecutablePath.StartsWith($App, [System.StringComparison]::OrdinalIgnoreCase)) -or
      ($_.CommandLine -and $_.CommandLine.IndexOf($App, [System.StringComparison]::OrdinalIgnoreCase) -ge 0)
    } | Where-Object { $_.ProcessId -ne $PID }
    foreach ($process in $mine) { Stop-Process -Id $process.ProcessId -Force }
  }
  "autostart-off" {
    $link = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\Startup\Pampa Notes companion.lnk"
    if (Test-Path -LiteralPath $link) {
      $shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut($link)
      $where = "$($shortcut.TargetPath) $($shortcut.Arguments) $($shortcut.WorkingDirectory)"
      if ($where.IndexOf($App, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) { Remove-Item -LiteralPath $link -Force }
    }
  }
  "firewall-off" {
    $settings = Read-Config
    $port = 8765
    if ($settings -and $settings.port) { $port = [int]$settings.port }
    if (Get-NetFirewallRule -DisplayName "Pampa Notes $port") {
      $script = Join-Path $App "apri-firewall.ps1"
      try {
        Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList @(
          "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$script`"", "-Port", "$port", "-Remove", "-NoPause")
      } catch { }
    }
  }
  "purge-archive" {
    $settings = Read-Config
    $root = Join-Path $env:LOCALAPPDATA "PampaNotes\archivio"
    if ($settings -and $settings.archive_root) { $root = [string]$settings.archive_root }
    $looksOurs = (Test-Path -LiteralPath (Join-Path $root "archive.db")) -or (Test-Path -LiteralPath (Join-Path $root "blobs"))
    if ($looksOurs) { Remove-Item -LiteralPath $root -Recurse -Force }
  }
  "purge-models" {
    $hub = Get-HubCache
    if (Test-Path -LiteralPath $hub) {
      foreach ($pattern in $ModelPatterns) {
        # Le cartelle dei modelli, e i loro lucchetti in .locks, che hanno lo stesso nome.
        foreach ($folder in @($hub, (Join-Path $hub ".locks"))) {
          if (Test-Path -LiteralPath $folder) {
            Get-ChildItem -LiteralPath $folder -Directory -Filter $pattern | ForEach-Object {
              Remove-Item -LiteralPath $_.FullName -Recurse -Force
            }
          }
        }
      }
    }
  }
}
exit 0
