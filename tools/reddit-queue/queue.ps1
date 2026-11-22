# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Yigder
# Shows each drafted reply and sends only the ones you approve. Run it yourself, in your own terminal:
#   powershell -ExecutionPolicy Bypass -File tools\reddit-queue\queue.ps1
#   ... -Paste   no API: the reply goes on the clipboard and the thread opens in your browser, you paste and send.
# Without the API sign-in (setup.ps1) it always works the -Paste way.
param([switch]$Paste)
. "$PSScriptRoot\lib.ps1"
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$items = @(Read-Queue)
$open = @($items | Where-Object { $_.status -eq 'draft' })
if (-not $open.Count) { Write-Host 'Nothing is waiting.'; return }
$useApi = (-not $Paste) -and (Test-ApiReady)
$how = if ($useApi) { "posted through Reddit's API" } else { 'put on the clipboard for you to paste' }
Write-Host "$($open.Count) drafts waiting. Approved replies are $how."
$token = $null
$n = 0

foreach ($it in $open) {
    $n++
    $next = $false
    while (-not $next) {
        Write-Host ''
        Write-Host ('=' * 78)
        Write-Host "$n of $($open.Count)   u/$($it.author)   $($it.link)"
        if ($it.kind -eq 'needs-you') { Write-Host "NEEDS YOU: $($it.note)" -ForegroundColor Yellow }
        Write-Host ''
        Write-Host 'They wrote:' -ForegroundColor DarkGray
        Write-Host $it.quote -ForegroundColor DarkGray
        Write-Host ''
        Write-Host 'Reply:'
        Write-Host $it.reply
        Write-Host ''
        $a = (Read-Host '[A]pprove and send   [E]dit   [S]kip for good   [L]ater   [Q]uit').Trim().ToLower()
        if ($a -eq 'q') { Save-Queue $items; return }
        elseif ($a -eq 'l') { $next = $true }
        elseif ($a -eq 's') { Set-Field $it 'status' 'skipped'; Save-Queue $items; $next = $true }
        elseif ($a -eq 'e') {
            $f = Join-Path (Get-QueueHome) 'edit.txt'
            [IO.File]::WriteAllText($f, [string]$it.reply, $script:Utf8)
            Start-Process notepad.exe $f
            Read-Host 'Change it in Notepad, save, then press Enter here' | Out-Null
            Set-Field $it 'reply' ([IO.File]::ReadAllText($f, $script:Utf8).Trim())
            Save-Queue $items
        }
        elseif ($a -eq 'a') {
            if ($useApi) {
                try {
                    if (-not $token) { $token = Get-AccessToken }
                    $posted = Send-Comment $token $it.thing $it.reply
                    Set-Field $it 'status' 'posted'; Set-Field $it 'via' 'api'; Set-Field $it 'postedLink' $posted
                    Set-Field $it 'postedAt' (Get-Date).ToString('s')
                    Save-Queue $items
                    Write-Host "Posted: $posted" -ForegroundColor Green
                    Start-Sleep -Seconds 2
                    $next = $true
                } catch { Write-Host "Not posted. $($_.Exception.Message)" -ForegroundColor Red }
            } else {
                if (-not (Test-RedditLink $it.link)) { Write-Host 'This draft has no proper Reddit link; skip or edit the queue.' -ForegroundColor Red; continue }
                Set-Clipboard -Value ([string]$it.reply)
                Start-Process $it.link
                $y = (Read-Host 'The reply is on the clipboard and the thread is open. Pasted and sent? [Y]es  [N]o').Trim().ToLower()
                if ($y -eq 'y') {
                    Set-Field $it 'status' 'posted'; Set-Field $it 'via' 'paste'
                    Set-Field $it 'postedAt' (Get-Date).ToString('s')
                    Save-Queue $items
                    $next = $true
                }
            }
        }
    }
}
Write-Host ''
Write-Host 'Done.'
