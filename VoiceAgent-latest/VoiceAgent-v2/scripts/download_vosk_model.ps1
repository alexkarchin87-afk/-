# Downloads the existing model, or installs the user's local ZIP without a network request.
# Example: powershell -File scripts\download_vosk_model.ps1 -ModelZip 'C:\Downloads\vosk-model-small-ru-0.22 (1).zip'
param([string]$ModelZip = "")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $root "app\src\main\assets\model-ru-small"
$id = [guid]::NewGuid().ToString()
$workPath = Join-Path ([IO.Path]::GetTempPath()) ("voiceagent-vosk-" + $id)
$stage = Join-Path $root (".model-stage-" + $id)
$backupRoot = Join-Path $root (".model-backups\" + $id)
$backup = Join-Path $backupRoot "model-ru-small"
$lockPath = Join-Path $root ".vosk-install.lock"
$lock = $null
$oldMoved = $false

function Assert-ModelFile([string]$directory, [string]$relativePath) {
    $file = Get-Item -LiteralPath (Join-Path $directory $relativePath) -ErrorAction Stop
    if ($file.PSIsContainer -or $file.Length -le 0) { throw "Missing or empty model file: $relativePath" }
}

try {
    # Exclusive installation. A stale lock after a process crash must be reviewed, not overwritten.
    $lock = [IO.File]::Open($lockPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    New-Item -ItemType Directory -Path $workPath | Out-Null
    if ($ModelZip) {
        if (!(Test-Path -LiteralPath $ModelZip -PathType Leaf)) { throw "Local model ZIP does not exist" }
        $archive = (Resolve-Path -LiteralPath $ModelZip).Path
    } else {
        $archive = Join-Path $workPath "model.zip"
        Invoke-WebRequest "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip" -OutFile $archive
    }

    $unpack = Join-Path $workPath "unpacked"
    # Reject archive paths escaping the temporary extraction directory, including older PowerShell versions.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archiveReader = [IO.Compression.ZipFile]::OpenRead($archive)
    try {
        $prefix = [IO.Path]::GetFullPath($unpack) + [IO.Path]::DirectorySeparatorChar
        foreach ($entry in $archiveReader.Entries) {
            $target = [IO.Path]::GetFullPath((Join-Path $unpack $entry.FullName))
            if (!$target.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Unsafe path in model ZIP" }
        }
    } finally { $archiveReader.Dispose() }
    Expand-Archive -LiteralPath $archive -DestinationPath $unpack
    $model = Join-Path $unpack "vosk-model-small-ru-0.22"
    Assert-ModelFile $model "am\final.mdl"
    Assert-ModelFile $model "conf\mfcc.conf"
    $hclg = Join-Path $model "graph\HCLG.fst"
    if ((Test-Path -LiteralPath $hclg -PathType Leaf) -and (Get-Item -LiteralPath $hclg).Length -gt 0) {
        Assert-ModelFile $model "graph\HCLG.fst"
    } else {
        Assert-ModelFile $model "graph\HCLr.fst"
        Assert-ModelFile $model "graph\Gr.fst"
    }
    [guid]::NewGuid().ToString() | Set-Content -LiteralPath (Join-Path $model "uuid") -NoNewline -Encoding ascii

    # Prepare on the project filesystem before touching the installed model.
    Copy-Item -LiteralPath $model -Destination $stage -Recurse
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
    if (Test-Path -LiteralPath $dest) {
        if (!(Test-Path -LiteralPath $dest -PathType Container)) { throw "Model destination is not a directory" }
        New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
        [IO.Directory]::Move($dest, $backup)
        $oldMoved = $true
    }
    try { [IO.Directory]::Move($stage, $dest) }
    catch {
        if ($oldMoved -and !(Test-Path -LiteralPath $dest)) { [IO.Directory]::Move($backup, $dest) }
        throw
    }
    Write-Host "Model installed: $dest"
    if ($oldMoved) { Write-Host "Previous model preserved outside APK assets: $backup" }
} finally {
    # Only this invocation's temporary directories are removed. Previous models are kept.
    try {
        if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
        if (Test-Path -LiteralPath $workPath) { Remove-Item -LiteralPath $workPath -Recurse -Force }
    } finally {
        if ($null -ne $lock) { $lock.Dispose(); Remove-Item -LiteralPath $lockPath -Force }
    }
}
