<#
.SYNOPSIS
  Apre nel firewall di Windows la porta del server, solo verso la rete di casa.

.DESCRIPTION
  Va lanciato una volta sola, da amministratore: ci pensa apri-firewall.cmd a chiedere i permessi.

  Due cose che il vecchio consiglio sbagliava, e per cui il tablet non trovava il computer nemmeno
  dopo aver eseguito il comando suggerito:

    * la regola veniva creata per il profilo **Private**, ma se Windows ha classificato la rete di
      casa come **Public** — capita spesso, e nessuno se ne accorge — quella regola non si applica.
      Qui si crea per tutti i profili;
    * si apriva la porta a chiunque. Qui `-RemoteAddress LocalSubnet` la apre solo ai dispositivi
      della stessa rete, che e' tutto quello che serve al tablet.

.PARAMETER Port
  La porta del server. Deve essere la stessa che usa avvia.cmd (di suo, 8765).

.PARAMETER NoPause
  Non aspetta Invio alla fine: lo passa l'installer, che la lancia senza una console da leggere.

.PARAMETER Remove
  Toglie la regola invece di crearla: lo usa la disinstallazione.
#>
[CmdletBinding()]
param([int]$Port = 8765, [switch]$NoPause, [switch]$Remove)

$ErrorActionPreference = "Stop"
$name = "Pampa Notes $Port"

if ($Remove) {
  Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue | Remove-NetFirewallRule
  Write-Host "  Regola «$name» tolta."
  exit 0
}

Write-Host ""
$profiles = Get-NetConnectionProfile -ErrorAction SilentlyContinue
foreach ($p in $profiles) {
  Write-Host ("  Rete «{0}»: Windows la considera {1}." -f $p.Name, $p.NetworkCategory)
}
if ($profiles | Where-Object { $_.NetworkCategory -eq "Public" }) {
  Write-Host "  Su una rete Public Windows e' piu' severo: la regola qui sotto vale per tutti i profili." -ForegroundColor Yellow
}
Write-Host ""

$existing = Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue
if ($existing) {
  # Una regola vecchia puo' essere quella sbagliata: la si rifa' invece di fidarsi del nome.
  Write-Host "  C'era gia' una regola «$name»: la rifaccio con i profili giusti." -ForegroundColor Yellow
  $existing | Remove-NetFirewallRule
}

New-NetFirewallRule `
  -DisplayName $name `
  -Description "Pampa Notes: il tablet manda le registrazioni al server WhisperX di questo computer." `
  -Direction Inbound `
  -Protocol TCP `
  -LocalPort $Port `
  -Action Allow `
  -Profile Any `
  -RemoteAddress LocalSubnet | Out-Null

Write-Host "  Fatto: la porta $Port e' raggiungibile dai dispositivi della tua rete, e solo da quelli." -ForegroundColor Green
Write-Host ""
Write-Host "  Per toglierla, un giorno:" -ForegroundColor DarkGray
Write-Host "     Remove-NetFirewallRule -DisplayName '$name'" -ForegroundColor DarkGray
Write-Host ""
if (-not $NoPause) { Read-Host "  Premi Invio per chiudere" }
