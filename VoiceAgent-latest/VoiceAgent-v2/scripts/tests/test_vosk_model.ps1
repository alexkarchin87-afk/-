# Offline filesystem regression checks; synthetic fixtures are NOT real Vosk models.
# Run: powershell -File scripts\tests\test_vosk_model.ps1
$ErrorActionPreference = "Stop"
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("voiceagent-model-test-" + [guid]::NewGuid().ToString())
$scriptSource = Join-Path (Split-Path -Parent $PSScriptRoot) "download_vosk_model.ps1"
function Assert-True([bool]$condition, [string]$message) { if (!$condition) { throw $message } }
try {
    $scriptDir = Join-Path $testRoot "scripts"
    $dest = Join-Path $testRoot "app\src\main\assets\model-ru-small"
    New-Item -ItemType Directory -Path $scriptDir -Force | Out-Null
    New-Item -ItemType Directory -Path $dest -Force | Out-Null
    Copy-Item -LiteralPath $scriptSource -Destination $scriptDir
    $installer = Join-Path $scriptDir "download_vosk_model.ps1"
    Set-Content -LiteralPath (Join-Path $dest "old-marker") -Value "keep this model"

    $corrupt = Join-Path $testRoot "corrupt.zip"
    Set-Content -LiteralPath $corrupt -Value "not a zip"
    $failed = $false
    try { & $installer -ModelZip $corrupt } catch { $failed = $true }
    Assert-True $failed "Corrupt ZIP must fail"
    Assert-True (Test-Path -LiteralPath (Join-Path $dest "old-marker")) "Corrupt ZIP removed installed model"
    Write-Host "PASS corrupt archive preserves installed model"

    $fixture = Join-Path $testRoot "vosk-model-small-ru-0.22"
    New-Item -ItemType Directory -Path $fixture | Out-Null
    Set-Content -LiteralPath (Join-Path $fixture "uuid") -Value "incomplete fixture"
    $archive = Join-Path $testRoot "fixture.zip"
    Compress-Archive -Path $fixture -DestinationPath $archive
    $failed = $false
    try { & $installer -ModelZip $archive } catch { $failed = $true }
    Assert-True $failed "Incomplete ZIP must fail"
    Assert-True (Test-Path -LiteralPath (Join-Path $dest "old-marker")) "Incomplete ZIP removed installed model"
    Write-Host "PASS incomplete archive preserves installed model"

    foreach ($file in @("am\final.mdl", "conf\mfcc.conf", "graph\HCLr.fst", "graph\Gr.fst")) {
        $path = Join-Path $fixture $file
        New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
        Set-Content -LiteralPath $path -Value "synthetic fixture"
    }
    Compress-Archive -Path $fixture -DestinationPath $archive -Force
    & $installer -ModelZip $archive
    Assert-True (Test-Path -LiteralPath (Join-Path $dest "am\final.mdl")) "Wrong model nesting"
    Assert-True (!(Test-Path -LiteralPath (Join-Path $dest "old-marker"))) "Old marker remained in new model"
    $kept = @(Get-ChildItem -LiteralPath (Join-Path $testRoot ".model-backups") -Recurse -Filter "old-marker")
    Assert-True ($kept.Count -eq 1) "Previous model was not preserved"
    Assert-True ((Get-Content -LiteralPath $kept[0].FullName) -eq "keep this model") "Previous model changed"
    Assert-True (!(Test-Path -LiteralPath (Join-Path $testRoot ".vosk-install.lock"))) "Lock leaked"
    Write-Host "PASS local install retains backup and cleans lock"
} finally {
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
