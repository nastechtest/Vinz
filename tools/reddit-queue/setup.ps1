# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Yigder
# One-time sign-in for the Reddit reply queue. Run it yourself, in your own terminal:
#   powershell -ExecutionPolicy Bypass -File tools\reddit-queue\setup.ps1
#
# It asks for the app's client id and secret, opens Reddit's own "Allow" page in your browser, and keeps the answer
# in %USERPROFILE%\.vinz-reddit, encrypted for your Windows account. Your Reddit password is never asked for or
# stored. Nothing is posted.
. "$PSScriptRoot\lib.ps1"

$dir = Get-QueueHome
$redirect = 'http://localhost:8080'
$scope = 'identity submit read'

$clientId = (Read-Host 'Client id (the short line under the app name)').Trim()
$secretSec = Read-Host 'Secret (stays hidden as you paste)' -AsSecureString
$user = (Read-Host 'Your Reddit username, without u/').Trim()
$ua = "windows:vinz-reply-queue:v1.0 (by /u/$user)"

# Reddit's own page asks you to allow the app; the answer comes back to this PC only.
$state = [guid]::NewGuid().ToString('N')
$listener = New-Object Net.HttpListener
$listener.Prefixes.Add("$redirect/")
$listener.Start()
$authUrl = 'https://www.reddit.com/api/v1/authorize?client_id=' + [uri]::EscapeDataString($clientId) +
    '&response_type=code&state=' + $state + '&redirect_uri=' + [uri]::EscapeDataString($redirect) +
    '&duration=permanent&scope=' + [uri]::EscapeDataString($scope)
Write-Host 'Opening Reddit in your browser. Press Allow there.'
Start-Process $authUrl

$code = $null
try {
    while ($true) {
        $ctx = $listener.GetContext()
        $q = $ctx.Request.QueryString
        $done = ($null -ne $q['state'])
        $text = if ($done) { 'Done. You can close this tab and go back to the terminal.' } else { '' }
        $bytes = [Text.Encoding]::UTF8.GetBytes("<html><body style='font-family:sans-serif'>$text</body></html>")
        $ctx.Response.ContentType = 'text/html'
        $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length)
        $ctx.Response.Close()
        if (-not $done) { continue }   # the browser also asks for an icon
        if ($q['state'] -ne $state) { throw 'The answer did not match this request. Run the setup again.' }
        if ($q['error']) { throw "Reddit said: $($q['error'])" }
        $code = $q['code']
        break
    }
} finally { $listener.Stop() }

$basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($clientId + ':' + (ConvertFrom-Secure $secretSec)))
$tok = Invoke-RestMethod -Method Post -Uri 'https://www.reddit.com/api/v1/access_token' -UserAgent $ua `
    -Headers @{ Authorization = "Basic $basic" } `
    -Body @{ grant_type = 'authorization_code'; code = $code; redirect_uri = $redirect }
if (-not $tok.refresh_token) { throw 'Reddit gave no lasting sign-in. Run the setup again.' }

[IO.File]::WriteAllText((Join-Path $dir 'secret.dpapi'), ($secretSec | ConvertFrom-SecureString), $script:Utf8)
[IO.File]::WriteAllText((Join-Path $dir 'refresh.dpapi'), (Protect-Text $tok.refresh_token), $script:Utf8)
$config = @{ clientId = $clientId; user = $user; userAgent = $ua; redirect = $redirect; scope = $scope } | ConvertTo-Json
[IO.File]::WriteAllText((Join-Path $dir 'config.json'), $config, $script:Utf8)

$me = Invoke-RestMethod -Uri 'https://oauth.reddit.com/api/v1/me' -UserAgent $ua `
    -Headers @{ Authorization = "Bearer $($tok.access_token)" }
Write-Host ''
Write-Host "Signed in as u/$($me.name). Saved in $dir. Nothing was posted."
