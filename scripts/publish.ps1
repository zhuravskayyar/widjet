param([string]$Message = 'Update Stone Clock')
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
Push-Location -LiteralPath $projectRoot
try {
    git add --all
    if ($LASTEXITCODE -ne 0) { throw 'Could not stage changes' }
    git diff --cached --quiet
    if ($LASTEXITCODE -eq 1) {
        git commit -m $Message
        if ($LASTEXITCODE -ne 0) { throw 'Could not commit changes' }
    } elseif ($LASTEXITCODE -ne 0) { throw 'Could not inspect changes' }
    git pull --rebase origin main
    if ($LASTEXITCODE -ne 0) { throw 'Sync needs attention. Resolve the Git conflict before publishing.' }
    git push origin main
    if ($LASTEXITCODE -ne 0) { throw 'Could not push changes' }
    Write-Host 'Published. GitHub Actions will build and release the APK.'
} finally { Pop-Location }
