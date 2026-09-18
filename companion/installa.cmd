@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
title Pampa Notes - preparo il server

rem  Una volta sola. Dentro c'e' setup.ps1, che sa gia' l'ordine giusto: prima WhisperX, poi torch
rem  nella versione per la scheda.
rem
rem  Opzioni utili, passate cosi' come sono:
rem     installa.cmd -InstallPython     (se non c'e' un Python 3.11/3.12 sul computer)
rem     installa.cmd -Cuda cu126        (driver NVIDIA piu' vecchi)
rem     installa.cmd -Cuda cpu          (senza scheda)

echo.
echo   Preparo l'ambiente. La prima volta scarica qualche gigabyte
echo   e ci mette diversi minuti: e' normale.
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1" %*
set CODE=%ERRORLEVEL%

echo.
if not "%CODE%"=="0" (
  echo   L'installazione si e' fermata ^(codice %CODE%^). L'errore sta qui sopra.
) else (
  echo   Fatto. Adesso avvia.cmd.
)
pause
