@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
title Pampa Notes - apro la porta nel firewall

rem  Da lanciare una volta sola, quando il tablet non trova il computer. Chiede l'amministratore a
rem  Windows: la porta nel firewall non si apre senza.

set PORT=%~1
if "%PORT%"=="" set PORT=8765

net session >nul 2>&1
if errorlevel 1 (
  echo.
  echo   Serve l'amministratore per toccare il firewall: Windows sta per chiedertelo.
  powershell -NoProfile -Command "Start-Process powershell -Verb RunAs -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','\"%~dp0apri-firewall.ps1\"','-Port','%PORT%'"
  exit /b
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0apri-firewall.ps1" -Port %PORT%
