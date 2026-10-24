# Run from the repository after the signing PR is merged. The private key stays on your PC.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
if (Get-Command py -ErrorAction SilentlyContinue) {
    & py -3 scripts/android_signing.py setup @args
} elseif (Get-Command python -ErrorAction SilentlyContinue) {
    & python scripts/android_signing.py setup @args
} else {
    throw 'Install Python 3 first. See docs/ANDROID_UPDATES.md.'
}
if ($LASTEXITCODE -ne 0) { throw 'Signing setup did not finish. Keep the existing signing folder and retry after fixing the reported issue.' }
