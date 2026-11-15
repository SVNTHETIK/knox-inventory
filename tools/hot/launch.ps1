# The desktop shortcut "Knox DEV" runs this (the author's request 2026-11-10: one shortcut for
# the whole DEV start). Starts Steam if it is not running and waits for it, then the game on
# JetBrains Runtime (run_jbr.ps1). On an error the window stays open with the message.
# The shortcut itself: tools\hot\make_shortcut.ps1.
$ErrorActionPreference = 'Stop'
try {
    if (-not (Get-Process steam -ErrorAction SilentlyContinue)) {
        "Starting Steam..."
        $steam = (Get-ItemProperty HKCU:\Software\Valve\Steam).SteamExe
        Start-Process $steam
        $t = 0
        while (-not (Get-Process steamwebhelper -ErrorAction SilentlyContinue) -and $t -lt 120) {
            Start-Sleep 1; $t++
        }
        Start-Sleep 10   # (Steam logs in after its window comes up; the game needs it logged in)
    }
    & (Join-Path $PSScriptRoot 'run_jbr.ps1') | Out-Null
    Start-Sleep 2
} catch {
    Write-Host $_ -ForegroundColor Red
    Read-Host "Press Enter to close"
}
