#define MyAppName "VanyaBridge"
#define MyAppVersion "0.1.0"
#define MyAppExeName "VanyaBridge.exe"

[Setup]
AppId={{8A0A1DD0-F988-44C5-A536-268598E81237}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
DefaultDirName={localappdata}\Programs\VanyaBridge
DefaultGroupName=VanyaBridge
PrivilegesRequired=lowest
OutputDir=installer-output
OutputBaseFilename=VanyaBridge-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
UninstallDisplayName=VanyaBridge
SetupLogging=yes

[Files]
Source: "dist\VanyaBridge\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\VanyaBridge"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\VanyaBridge"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Создать ярлык на рабочем столе"; GroupDescription: "Дополнительно:"

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Запустить VanyaBridge"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; Intentionally preserve data in %LOCALAPPDATA%\VanyaBridge.
