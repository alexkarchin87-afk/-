#define AppName "VanyaBridge"
#define AppVersion "0.2.0"
#define AppExeName "VanyaBridge.exe"
[Setup]
AppId={{DD29E9C3-A546-4CCC-865A-C2F754E85465}
AppName={#AppName}
AppVersion={#AppVersion}
DefaultDirName={localappdata}\Programs\VanyaBridge
DefaultGroupName={#AppName}
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=installer-output
OutputBaseFilename=VanyaBridge-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\{#AppExeName}
[Files]
Source: "dist\VanyaBridge\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
[Icons]
Name: "{group}\VanyaBridge"; Filename: "{app}\{#AppExeName}"
Name: "{autodesktop}\VanyaBridge"; Filename: "{app}\{#AppExeName}"; Tasks: desktopicon
[Tasks]
Name: "desktopicon"; Description: "Создать ярлык на рабочем столе"; Flags: unchecked
[Run]
Filename: "{app}\{#AppExeName}"; Description: "Запустить VanyaBridge"; Flags: nowait postinstall skipifsilent
; User data in %LOCALAPPDATA%\VanyaBridge is deliberately not included in uninstall cleanup.
