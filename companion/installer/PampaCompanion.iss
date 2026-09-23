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
    end;
  end;
  if CurUninstallStep = usPostUninstall then
  begin
    RemoveDir(AppPath('installer'));
    RemoveDir(ExpandConstant('{app}'));
  end;
end;
