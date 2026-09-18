@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
title Pampa Notes - server di trascrizione

rem  Doppio clic e parte. Esiste perche' "powershell -ExecutionPolicy Bypass -File run.ps1" e' una
rem  riga che nessuno si ricorda, e perche' una finestra che si chiude da sola quando qualcosa va
rem  storto non lascia leggere l'errore: qui in fondo c'e' un pause.
rem
rem  Accetta le stesse opzioni del server, per esempio:
rem     avvia.cmd --model medium
rem     avvia.cmd --idle-minutes 30
rem     avvia.cmd --idle-minutes 0        (non liberare mai la VRAM)

if not exist ".venv\Scripts\python.exe" (
  echo.
  echo   Manca l'ambiente Python di questa cartella.
  echo   Lancia installa.cmd una volta sola, poi torna qui.
  echo.
  pause
  exit /b 1
)

echo.
echo   Avvio il server. Si mette in ascolto subito: il modello arriva
echo   con la prima registrazione, non adesso.
echo.

".venv\Scripts\python.exe" whisperx_server.py %*
set CODE=%ERRORLEVEL%

echo.
if not "%CODE%"=="0" (
  echo   Il server si e' fermato con un errore ^(codice %CODE%^).
  echo   Se parla di moduli mancanti, rilancia installa.cmd.
) else (
  echo   Server fermato.
)
pause
