@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem  Il server con l'icona accanto all'orologio, senza finestra.
rem
rem  Differenza con avvia.cmd: li' resta aperta una console che mostra quello che succede e che
rem  bisogna ricordarsi di lanciare; qui non c'e' niente da guardare, e dal menu col tasto destro
rem  si puo' accendere l'avvio automatico e non pensarci mai piu'.
rem
rem  Se qualcosa va storto non c'e' una console dove leggerlo: sta in logs\companion.log.

if not exist ".venv\Scripts\pythonw.exe" (
  echo.
  echo   Manca l'ambiente Python di questa cartella.
  echo   Lancia installa.cmd una volta sola, poi torna qui.
  echo.
  pause
  exit /b 1
)

rem  start "" lascia partire il processo e restituisce subito il prompt: pythonw non ha una
rem  console, quindi questa finestra non deve restare ad aspettarlo.
start "" ".venv\Scripts\pythonw.exe" tray.py
exit /b 0
