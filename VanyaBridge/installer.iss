#define MyAppName "VanyaBridge"
#define MyAppVersion "0.2.0-test"
#define MyAppExeName "VanyaBridge.exe"

[Setup]
AppId={{2CE82743-68D1-42CC-83C9-BC0093864813}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher=VanyaBridge
DefaultDirName={localappdata}\Programs\VanyaBridge
DefaultGroupName=VanyaBridge
PrivilegesRequired=lowest
OutputDir=installer-output
OutputBaseFilename=VanyaBridge-Setup-0.2.0-test
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayIcon={app}\{#MyAppExeName}
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"

[Tasks]
Name: "desktopicon"; Description: "Создать ярлык на рабочем столе"; GroupDescription: "Дополнительные задачи:"

[Files]
Source: "dist\VanyaBridge\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Ваня"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\Ваня"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Запустить Ваню"; Flags: nowait postinstall skipifsilent
