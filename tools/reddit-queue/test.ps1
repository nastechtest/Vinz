# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Yigder
# Checks the parts of the queue that need no Reddit: the queue file, the key store, the link and id rules.
#   powershell -ExecutionPolicy Bypass -File tools\reddit-queue\test.ps1
. "$PSScriptRoot\lib.ps1"
$env:VINZ_REDDIT_HOME = Join-Path ([IO.Path]::GetTempPath()) ('vinz-reddit-test-' + [guid]::NewGuid().ToString('N'))
$fails = 0
function Check([string]$name, [bool]$ok) {
    if ($ok) { Write-Host "ok    $name" } else { Write-Host "FAIL  $name" -ForegroundColor Red; $script:fails++ }
}
try {
    Check 'no queue file reads as empty' (@(Read-Queue).Count -eq 0)
    Save-Queue @()
    Check 'an empty queue stays empty' (@(Read-Queue).Count -eq 0)

    # Spanish, Japanese and an emoji, built from codes so this file stays plain ASCII.
    $words = 'Gracias ' + [char]0xA1 + 'ya est' + [char]0xE1 + '! ' + [char]0x3042 + [char]0x308A + ' ' + [char]::ConvertFromUtf32(0x1F3B5)
    $one = [pscustomobject]@{ id = 'a1'; thing = 't1_abc123'; author = 'someone'; quote = "line one`nline two"
        reply = $words; kind = 'routine'; status = 'draft'; link = 'https://www.reddit.com/r/VinzMusicApp/comments/1abc/title/def/' }
    Save-Queue @($one)
    $back = @(Read-Queue)
    Check 'one draft comes back as one' ($back.Count -eq 1)
    Check 'other alphabets survive the file' ($back[0].reply -ceq $words)
    Check 'line breaks survive the file' ($back[0].quote -ceq "line one`nline two")
    $raw = [IO.File]::ReadAllBytes((Join-Path (Get-QueueHome) 'queue.json'))
    Check 'the file has no byte-order mark' (-not ($raw[0] -eq 0xEF -and $raw[1] -eq 0xBB))

    Set-Field $back[0] 'status' 'posted'
    Set-Field $back[0] 'via' 'paste'
    $two = [pscustomobject]@{ id = 'a2'; thing = 't3_xyz'; status = 'draft'; reply = 'second' }
    Save-Queue ($back + $two)
    $again = @(Read-Queue)
    Check 'two entries come back as two' ($again.Count -eq 2)
    Check 'a sent reply is remembered as sent' ($again[0].status -eq 'posted' -and $again[0].via -eq 'paste')
    Check 'only drafts are offered' (@($again | Where-Object { $_.status -eq 'draft' }).Count -eq 1)

    $stored = Protect-Text 'not-a-real-secret'
    Check 'a stored key is not readable text' ($stored -notmatch 'not-a-real-secret')
    Check 'a stored key opens again' ((Unprotect-Text $stored) -ceq 'not-a-real-secret')

    Check 'a thread link is accepted' (Test-RedditLink 'https://www.reddit.com/r/VinzMusicApp/comments/1abc/some_title/def456/')
    Check 'another site is refused' (-not (Test-RedditLink 'https://example.com/r/VinzMusicApp/comments/1abc/'))
    Check 'a look-alike host is refused' (-not (Test-RedditLink 'https://www.reddit.com.evil.example/r/x/comments/1/'))
    Check 'a file path is refused' (-not (Test-RedditLink 'C:\Windows\System32\calc.exe'))
    Check 'a comment id is accepted' (Test-Thing 't1_abc123')
    Check 'a post id is accepted' (Test-Thing 't3_xyz')
    Check 'a user or subreddit id is refused' ((-not (Test-Thing 't2_abc')) -and (-not (Test-Thing 't5_abc')))
    Check 'not set up means no API' (-not (Test-ApiReady))
} finally {
    Remove-Item -Recurse -Force $env:VINZ_REDDIT_HOME -Confirm:$false
    $env:VINZ_REDDIT_HOME = $null
}
if ($fails) { Write-Host "$fails failed" -ForegroundColor Red; exit 1 } else { Write-Host 'All passed' }
