# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Yigder
# Shared pieces of the Reddit reply queue. See README.md in this folder.
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$script:Utf8 = New-Object Text.UTF8Encoding($false)
$script:Subreddit = 'VinzMusicApp'

# Everything private (keys, the queue) lives outside the repository.
function Get-QueueHome {
    $d = if ($env:VINZ_REDDIT_HOME) { $env:VINZ_REDDIT_HOME } else { Join-Path $env:USERPROFILE '.vinz-reddit' }
    New-Item -ItemType Directory -Force $d | Out-Null
    $d
}

# Keys are kept encrypted for this Windows account (DPAPI); they open on no other account or PC.
function ConvertFrom-Secure($sec) {
    $p = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($p) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($p) }
}
function Protect-Text([string]$text) { (ConvertTo-SecureString $text -AsPlainText -Force) | ConvertFrom-SecureString }
function Unprotect-Text([string]$stored) { ConvertFrom-Secure (ConvertTo-SecureString $stored.Trim()) }

function Read-Queue {
    $p = Join-Path (Get-QueueHome) 'queue.json'
    if (-not (Test-Path $p)) { return @() }
    $t = [IO.File]::ReadAllText($p, $script:Utf8)
    if (-not $t.Trim()) { return @() }
    @((ConvertFrom-Json $t) | ForEach-Object { $_ })
}
function Save-Queue($items) {
    $p = Join-Path (Get-QueueHome) 'queue.json'
    [IO.File]::WriteAllText($p, (ConvertTo-Json -InputObject @($items) -Depth 5), $script:Utf8)
}
function Set-Field($item, [string]$name, $value) { $item | Add-Member -NotePropertyName $name -NotePropertyValue $value -Force }

# Only real Reddit thread links are ever opened in the browser.
function Test-RedditLink([string]$link) { $link -match '^https://(www|old)\.reddit\.com/r/[A-Za-z0-9_]+/comments/[A-Za-z0-9_/%-]+/?$' }
# A reply goes only to a comment (t1_) or a post (t3_).
function Test-Thing([string]$thing) { $thing -match '^t[13]_[a-z0-9]+$' }

function Get-Config {
    $p = Join-Path (Get-QueueHome) 'config.json'
    if (-not (Test-Path $p)) { throw 'Not set up yet. Run setup.ps1 first.' }
    ConvertFrom-Json ([IO.File]::ReadAllText($p, $script:Utf8))
}
function Test-ApiReady { Test-Path (Join-Path (Get-QueueHome) 'refresh.dpapi') }

# A one-hour access token from the stored sign-in. Reddit may hand back a new sign-in token; the newest is kept.
function Get-AccessToken {
    $h = Get-QueueHome
    $cfg = Get-Config
    $secret = Unprotect-Text ([IO.File]::ReadAllText((Join-Path $h 'secret.dpapi')))
    $refreshPath = Join-Path $h 'refresh.dpapi'
    $refresh = Unprotect-Text ([IO.File]::ReadAllText($refreshPath))
    $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($cfg.clientId + ':' + $secret))
    $tok = Invoke-RestMethod -Method Post -Uri 'https://www.reddit.com/api/v1/access_token' -UserAgent $cfg.userAgent `
        -Headers @{ Authorization = "Basic $basic" } -Body @{ grant_type = 'refresh_token'; refresh_token = $refresh }
    if ($tok.refresh_token -and $tok.refresh_token -ne $refresh) {
        [IO.File]::WriteAllText($refreshPath, (Protect-Text $tok.refresh_token), $script:Utf8)
    }
    $tok.access_token
}

function Invoke-RedditGet([string]$token, [string]$path) {
    Invoke-RestMethod -Uri ('https://oauth.reddit.com' + $path) -UserAgent (Get-Config).userAgent `
        -Headers @{ Authorization = "Bearer $token" }
}

# Posts one approved reply. Returns the new comment's link.
function Send-Comment([string]$token, [string]$thing, [string]$text) {
    if (-not (Test-Thing $thing)) { throw "Not a comment or post id: $thing" }
    $r = Invoke-RestMethod -Method Post -Uri 'https://oauth.reddit.com/api/comment' -UserAgent (Get-Config).userAgent `
        -Headers @{ Authorization = "Bearer $token" } -Body @{ api_type = 'json'; thing_id = $thing; text = $text }
    if ($r.json.errors -and @($r.json.errors).Count) { throw ('Reddit said: ' + (($r.json.errors | ForEach-Object { $_ -join ' ' }) -join '; ')) }
    'https://www.reddit.com' + $r.json.data.things[0].data.permalink
}
