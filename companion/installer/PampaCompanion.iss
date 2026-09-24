; Pampa Notes companion - il setup per Windows (Inno Setup 6).
;
; Si costruisce con build-installer.ps1, che passa la versione (da companion\VERSION) e il percorso
; di uv.exe. Cosa fa, e cosa lascia a install.py:
;
;   * copia il codice del companion in %LOCALAPPDATA%\Programs\PampaCompanion, per l'utente e senza
;     chiedere l'amministratore: il companion gira come l'utente, e un'installazione per tutti
;     vorrebbe UAC a ogni aggiornamento;
;   * con uv prepara un Python 3.11 tutto suo (dentro la cartella: la disinstallazione lo porta via, e
;     nessun altro Python del computer viene toccato) e l'ambiente .venv;
;   * lancia install.py con quel Python: WhisperX, torch, il modello, il firewall, l'avvio
;     automatico, l'icona e il QR, in una finestra con la barra e con "Riprova".
;
; Torch e il modello non stanno qui dentro: sono gigabyte, e dipendono dalla scheda video.
;
; Installare sopra un'installazione che c'e' (o /UPGRADE, come fa l'icona quando si aggiorna da
; sola) e' un aggiornamento: l'ambiente e il modello restano, cambia il codice, e l'icona si riavvia
; solo quando non sta trascrivendo.
;
; Niente caratteri fuori dall'ASCII qui dentro: il file si legge uguale con e senza BOM.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef UvExe
  #define UvExe "vendor\uv.exe"
#endif

[Setup]
AppId={{6F1D3C52-9B7E-4E2A-A0C4-5D2B8E7F1A93}
AppName=Pampa Notes companion
AppVersion={#AppVersion}
AppVerName=Pampa Notes companion {#AppVersion}
AppPublisher=Pampa Notes
AppPublisherURL=https://github.com/Casual76/Pampa-notes
AppSupportURL=https://github.com/Casual76/Pampa-notes/releases
AppUpdatesURL=https://github.com/Casual76/Pampa-notes/releases
VersionInfoVersion={#AppVersion}
DefaultDirName={localappdata}\Programs\PampaCompanion
DisableDirPage=yes
DisableProgramGroupPage=yes
DefaultGroupName=Pampa Notes companion
PrivilegesRequired=lowest
; Inno 6.5 accende RedirectionGuard, che i processi figli ereditano: blocca l'attraversamento delle
; giunzioni create senza amministratore, e uv ne crea una (cpython-3.11 -> cpython-3.11.x) appena
; scaricato Python - "Failed to create Python minor version link directory", os error 448. La
; protezione serve agli installer che girano da amministratore; questo installa per utente, senza
; privilegi, in una cartella dell'utente.
RedirectionGuard=no
OutputBaseFilename=PampaCompanionSetup-{#AppVersion}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
; I file .py non restano aperti mentre l'icona gira: niente Restart Manager, che proporrebbe di
; chiudere Python a meta' di una trascrizione. L'icona la riavvia install.py, quando si puo'.
CloseApplications=no
RestartApplications=no
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
UninstallDisplayName=Pampa Notes companion
SetupLogging=yes

[Languages]
Name: "it"; MessagesFile: "compiler:Languages\Italian.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[Dirs]
Name: "{app}\logs"

[Files]
; Il codice del companion, senza le prove.
Source: "..\*.py"; Excludes: "test_*.py"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\avvio.pyw"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\requirements.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\VERSION"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\README.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\*.cmd"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\*.ps1"; DestDir: "{app}"; Flags: ignoreversion
; L'installatore vero, e uv.
Source: "install.py"; DestDir: "{app}\installer"; Flags: ignoreversion
Source: "fetch_model.py"; DestDir: "{app}\installer"; Flags: ignoreversion
Source: "uninstall-helper.ps1"; DestDir: "{app}\installer"; Flags: ignoreversion
Source: "{#UvExe}"; DestDir: "{app}\installer"; DestName: "uv.exe"; Flags: ignoreversion

[Icons]
Name: "{group}\Pampa Notes companion"; Filename: "{app}\.venv\Scripts\pythonw.exe"; Parameters: """{app}\tray.py"""; WorkingDir: "{app}"; Comment: "Il server di trascrizione, accanto all'orologio"
Name: "{group}\Collega il telefono (QR)"; Filename: "{app}\.venv\Scripts\pythonw.exe"; Parameters: """{app}\installer\install.py"" --app ""{app}"" --pair-only"; WorkingDir: "{app}"
Name: "{group}\Pampa Notes companion - ripara"; Filename: "{app}\.venv\Scripts\pythonw.exe"; Parameters: """{app}\installer\install.py"" --app ""{app}"""; WorkingDir: "{app}"
Name: "{group}\Cartella del companion"; Filename: "{app}"
Name: "{group}\Disinstalla Pampa Notes companion"; Filename: "{uninstallexe}"

[UninstallDelete]
; Quello che il setup non ha copiato ma install.py ha creato. config.json no: lo decide la domanda.
Type: filesandordirs; Name: "{app}\.venv"
Type: filesandordirs; Name: "{app}\python"
Type: filesandordirs; Name: "{app}\bin"
Type: filesandordirs; Name: "{app}\logs"
Type: filesandordirs; Name: "{app}\__pycache__"
Type: filesandordirs; Name: "{app}\installer\__pycache__"

[Code]
const
  { Il setup rilanciato fuori da un contenitore lo dice cosi', e non guarda piu': vedi fuori.py. }
  FuoriParam = '/FUORI';

var
  UpgradeMode: Boolean;

function HasParam(const Name: String): Boolean;
var
  I: Integer;
begin
  Result := False;
  for I := 1 to ParamCount do
    if CompareText(ParamStr(I), Name) = 0 then
    begin
      Result := True;
      Exit;
    end;
end;

{ Il setup non deve mai girare dentro il contenitore di un'altra app (vedi fuori.py e
  install.installer_container). Lanciato da un'app che virtualizza %LOCALAPPDATA% (l'app di Claude
  sul PC, e tutto quello che lancia), i file che copia finirebbero nella copia privata di quell'app,
  e il companion avviato da Windows non li vedrebbe. Nessuna API lo dice: si scrive una sonda e si
  guarda dove e' finita. Torna il nome del contenitore, o ''. }
function RedirectedTo: String;
var
  Base, Name, Probe: String;
  Found: TFindRec;
begin
  Result := '';
  Base := ExpandConstant('{localappdata}');
  if (Base = '') or not DirExists(Base) then
    Exit;
  Name := '.sonda-setup-' + GetDateTimeString('yyyymmddhhnnsszzz', #0, #0);
  Probe := Base + '\PampaNotes\' + Name;
  if not ForceDirectories(Base + '\PampaNotes') then
    Exit;
  if not SaveStringToFile(Probe, 'x', False) then
    Exit;
  try
    if FindFirst(Base + '\Packages\*', Found) then
    try
      repeat
        if ((Found.Attributes and FILE_ATTRIBUTE_DIRECTORY) <> 0) and (Found.Name <> '.') and (Found.Name <> '..') and
          FileExists(Base + '\Packages\' + Found.Name + '\LocalCache\Local\PampaNotes\' + Name) then
        begin
          Result := Found.Name;
          Break;
        end;
      until not FindNext(Found);
    finally
      FindClose(Found);
    end;
  finally
    DeleteFile(Probe);
    { Solo se e' vuota: su un PC nuovo la cartella l'ha creata la sonda. }
    RemoveDir(Base + '\PampaNotes');
  end;
end;

{ Rilancia questo setup, con gli stessi parametri piu' /FUORI, attraverso WMI (Win32_Process.Create):
  il processo lo crea il servizio di Windows, fuori da ogni contenitore. Lo script passa da un file
  perche' la riga di comando ha virgolette che non sopravvivono a due interpreti. }
function RelaunchOutside: Boolean;
var
  I, Code: Integer;
  Command, Folder, Script, ScriptPath: String;
begin
  Command := '"' + ExpandConstant('{srcexe}') + '"';
  for I := 1 to ParamCount do
    { /SL5= e' il parametro interno fra setup.exe e il suo .tmp: al setup.exe nuovo non serve. }
    if CompareText(Copy(ParamStr(I), 1, 3), '/SL') <> 0 then
      Command := Command + ' "' + ParamStr(I) + '"';
  Command := Command + ' ' + FuoriParam;
  Folder := ExtractFileDir(ExpandConstant('{srcexe}'));
  { Dentro gli apici singoli di PowerShell l'apice si scrive due volte. }
  StringChangeEx(Command, '''', '''''', True);
  StringChangeEx(Folder, '''', '''''', True);
  { Esce con 0 solo se WMI ha creato il processo. Prima era `exit $r.ReturnValue`: con
    Invoke-CimMethod fallito $r restava vuoto, `exit $null` e' `exit 0`, e questa copia usciva
    convinta di essersi rilanciata senza che partisse niente. 'Stop' rende terminanti anche gli
    errori di CIM che non lo sono, cosi' il catch li vede. Lo stesso script sta in fuori.py. }
  Script := '$ErrorActionPreference = ''Stop''; try { $r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create ' +
    '-Arguments @{ CommandLine = ''' + Command + '''; CurrentDirectory = ''' + Folder + ''' }; ' +
    'if ($null -eq $r) { exit 1 }; exit [int]$r.ReturnValue } catch { exit 1 }';
  ScriptPath := ExpandConstant('{tmp}\fuori.ps1');
  Result := SaveStringToFile(ScriptPath, Script, False) and
    Exec('powershell.exe', '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + ScriptPath + '"', '', SW_HIDE,
      ewWaitUntilTerminated, Code) and (Code = 0);
end;

function InitializeSetup: Boolean;
var
  Boxed: String;
begin
  Result := True;
  if HasParam(FuoriParam) then
    Exit;
  Boxed := RedirectedTo;
  if Boxed = '' then
    Exit;
  { Questa copia esce in ogni caso: o ne e' partita una fuori, o dentro non si installa. Se il
    rilancio fallisce non si va avanti qui dentro: il companion finirebbe nella copia privata di
    %LOCALAPPDATA% dell'altra app, dove il PC non lo trova (e install.py lo rifiuterebbe comunque,
    uscita 4, dopo aver gia' preparato Python). Meglio fermarsi subito e dire come lanciarlo. }
  Result := False;
  if RelaunchOutside then
  begin
    Log('Partito dentro il contenitore di ' + Boxed + ': rilanciato fuori con WMI.');
    Exit;
  end;
  Log('Partito dentro il contenitore di ' + Boxed + ' e il rilancio con WMI non e'' riuscito: mi fermo.');
  if not WizardSilent then
    MsgBox('Il setup e'' partito dentro un''altra app (' + Boxed + '): Windows metterebbe il companion nella sua copia ' +
      'privata delle cartelle, dove il computer non lo trova, e non sono riuscito a ripartire da fuori.' + #13#10 + #13#10 +
      'Chiudi e lancia il setup con un doppio clic da Esplora file.', mbError, MB_OK);
end;

function AppPath(const Relative: String): String;
begin
  Result := AddBackslash(ExpandConstant('{app}')) + Relative;
end;

{ Il Python e l'ambiente, con uv. Una volta sola: se l'ambiente c'e' gia' non si rifa'. }
function Bootstrap: Boolean;
var
  Code: Integer;
  Params: String;
begin
  Result := FileExists(AppPath('.venv\Scripts\python.exe'));
  while not Result do
  begin
    WizardForm.StatusLabel.Caption := 'Preparo Python 3.11 per il companion (una trentina di MB)...';
    Params := '/C set "UV_PYTHON_INSTALL_DIR=' + AppPath('python') + '" && set "UV_PYTHON_PREFERENCE=only-managed"' +
      ' && set "UV_LINK_MODE=copy" && "' + AppPath('installer\uv.exe') + '" venv --python 3.11 --seed "' +
      AppPath('.venv') + '" > "' + AppPath('logs\bootstrap.log') + '" 2>&1';
    if Exec(ExpandConstant('{cmd}'), Params, AppPath(''), SW_HIDE, ewWaitUntilTerminated, Code) and (Code = 0)
      and FileExists(AppPath('.venv\Scripts\python.exe')) then
      Result := True
    else if WizardSilent then
      Exit
    else if MsgBox('Non riesco a preparare Python: la prima volta serve internet per scaricarlo.' + #13#10 +
      'Il dettaglio e'' in ' + AppPath('logs\bootstrap.log') + '.', mbError, MB_RETRYCANCEL) <> IDRETRY then
      Exit;
  end;
end;

procedure RunCompanionSetup;
var
  Code: Integer;
  Params: String;
begin
  if not Bootstrap then
  begin
    if not WizardSilent then
      MsgBox('L''installazione non e'' finita. Si riprende dal menu Start: "Pampa Notes companion - ripara".', mbInformation, MB_OK);
    Exit;
  end;
  Params := '"' + AppPath('installer\install.py') + '" --app "' + ExpandConstant('{app}') + '"';
  if UpgradeMode then
    Params := Params + ' --upgrade';
  if WizardSilent then
    Params := Params + ' --silent';
  { Per le prove: /INSTALLARGS="--port 8799 --no-autostart --no-firewall --no-start" arriva a
    install.py com'e', cosi' un setup si prova su un PC che ha gia' il suo companion senza toccarlo. }
  if ExpandConstant('{param:INSTALLARGS|}') <> '' then
    Params := Params + ' ' + ExpandConstant('{param:INSTALLARGS|}');
  WizardForm.StatusLabel.Caption := 'Installo WhisperX, torch e il modello: segui la finestra del companion...';
  if not Exec(AppPath('.venv\Scripts\pythonw.exe'), Params, AppPath(''), SW_SHOWNORMAL, ewWaitUntilTerminated, Code) then
  begin
    if not WizardSilent then
      MsgBox('La finestra del companion non si apre. Si riprova dal menu Start: "Pampa Notes companion - ripara".', mbError, MB_OK);
  end
  else if (Code = 1) and not WizardSilent then
    MsgBox('L''installazione del companion non e'' finita. Il registro e'' in ' + AppPath('logs\install.log') + #13#10 +
      'Si riprende dal menu Start: "Pampa Notes companion - ripara".', mbInformation, MB_OK);
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssInstall then
    UpgradeMode := HasParam('/UPGRADE') or FileExists(AppPath('.venv\Scripts\python.exe'));
  if CurStep = ssPostInstall then
    RunCompanionSetup;
end;

procedure RunHelper(const Action: String);
var
  Code: Integer;
begin
  Exec('powershell.exe', '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' +
    AppPath('installer\uninstall-helper.ps1') + '" -App "' + ExpandConstant('{app}') + '" -Action ' + Action,
    '', SW_HIDE, ewWaitUntilTerminated, Code);
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
  begin
    { Prima si ferma l'icona: la venv in uso non si cancella. }
    RunHelper('stop');
    RunHelper('autostart-off');
    if not UninstallSilent then
    begin
      { La regola del firewall chiede l'amministratore: Windows mostra la sua finestra. }
      RunHelper('firewall-off');
      { L'archivio prima del config: e' il config a dire dove sta. }
      if MsgBox('Tenere l''archivio dei file (le registrazioni e gli originali che i dispositivi hanno mandato a questo computer)?' + #13#10 + #13#10 +
        'Se per qualcosa questa e'' l''unica copia, rispondi Si''.', mbConfirmation, MB_YESNO or MB_DEFBUTTON1) = IDNO then
        RunHelper('purge-archive');
      if MsgBox('Tenere le impostazioni (config.json: account, codice, modello)?' + #13#10 +
        'Servono se lo reinstalli.', mbConfirmation, MB_YESNO or MB_DEFBUTTON1) = IDNO then
        DeleteFile(AppPath('config.json'));
      { I modelli stanno nella cache di Hugging Face e in quella di torch (gli allineatori di
        torchaudio), fuori da questa cartella: sono gigabyte, e la disinstallazione li lasciava li'.
        Si tolgono solo quelli che il companion scarica (Whisper, le voci, l'allineamento), e solo a
        chi dice di si': li userebbe anche un altro companion di questo computer, per esempio una
        copia per le prove. }
      if MsgBox('Cancellare anche i modelli scaricati per la trascrizione (Whisper, la separazione delle voci, ' +
        'l''allineamento delle parole)? Sono qualche gigabyte nelle cache di Hugging Face e di torch.' + #13#10 + #13#10 +
        'Rispondi No se sul computer c''e'' un''altra copia del companion, o se pensi di reinstallarlo.',
        mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
        RunHelper('purge-models');
    end;
  end;
  if CurUninstallStep = usPostUninstall then
  begin
    RemoveDir(AppPath('installer'));
    RemoveDir(ExpandConstant('{app}'));
    { La cartella dei dati del companion (l'archivio sta li' di serie), solo se e' rimasta vuota:
      RemoveDir non cancella mai una cartella che ha qualcosa dentro. }
    RemoveDir(ExpandConstant('{localappdata}\PampaNotes'));
  end;
end;
