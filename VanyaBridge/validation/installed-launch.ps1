$ErrorActionPreference = 'Stop'
$base = Join-Path $env:RUNNER_TEMP ('vanya-install-' + [guid]::NewGuid().ToString('N'))
$install = Join-Path $base 'app'
$data = Join-Path $base 'data'
New-Item -ItemType Directory -Path $data -Force | Out-Null
Set-Content -Path (Join-Path $data 'user-data-sentinel.txt') -Value 'keep'
$exe = $null
try {
    $setup = (Resolve-Path 'installer-output/VanyaBridge-Setup.exe').Path
    $p = Start-Process $setup -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', "/DIR=$install") -Wait -PassThru
    if ($p.ExitCode -ne 0) { throw "Installer exit code $($p.ExitCode)" }
    $exePath = Join-Path $install 'VanyaBridge.exe'
    if (!(Test-Path $exePath)) { throw 'Installed executable missing' }
    $exe = Start-Process $exePath -ArgumentList @('--data', "`"$data`"", '--port', '18765') -PassThru
    $found = $false
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Milliseconds 500
        $exe.Refresh()
        if ($exe.HasExited) { throw "Application exited early: $($exe.ExitCode)" }
        if ($exe.MainWindowHandle -ne 0 -and $exe.MainWindowTitle -like '*VanyaBridge*') {
            try {
                $tcp = [System.Net.Sockets.TcpClient]::new()
                $tcp.Connect('127.0.0.1', 18765)
                $tcp.Dispose()
                $found = $true
                break
            } catch { if ($tcp) { $tcp.Dispose() } }
        }
    }
    if (!$found) { throw "Application window or local server not found; title=$($exe.MainWindowTitle)" }
    Write-Host "Installed application window: $($exe.MainWindowTitle); server listening"
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $bitmap = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
    $bitmap.Save((Join-Path $env:RUNNER_TEMP 'vanya-desktop.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $graphics.Dispose(); $bitmap.Dispose()
    if (!$exe.CloseMainWindow()) { throw 'Unable to close GUI window' }
    if (!$exe.WaitForExit(15000)) { throw 'Application did not exit after window close' }
    if ($exe.ExitCode -ne 0) { throw "Application exit code $($exe.ExitCode)" }
    $uninstaller = Join-Path $install 'unins000.exe'
    $u = Start-Process $uninstaller -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART') -Wait -PassThru
    if ($u.ExitCode -ne 0) { throw "Uninstaller exit code $($u.ExitCode)" }
    if (!(Test-Path (Join-Path $data 'user-data-sentinel.txt'))) { throw 'User data was removed' }
    Write-Host 'Installer, ordinary GUI launch, shutdown and uninstaller completed; separate data retained.'
} finally {
    if ($exe -and !$exe.HasExited) { Stop-Process -Id $exe.Id -Force -ErrorAction SilentlyContinue }
    Remove-Item -LiteralPath $base -Recurse -Force -ErrorAction SilentlyContinue
}
