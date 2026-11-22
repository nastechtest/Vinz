# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Yigder
# Reads the newest posts and comments of r/VinzMusicApp into inbox.json, so replies can be drafted.
# Reads only: it posts, votes and messages nothing. Two requests per run.
#   powershell -ExecutionPolicy Bypass -File tools\reddit-queue\fetch.ps1
. "$PSScriptRoot\lib.ps1"

$cfg = Get-Config
$token = Get-AccessToken
$out = @()
$comments = Invoke-RedditGet $token "/r/$script:Subreddit/comments?limit=100&raw_json=1"
foreach ($c in $comments.data.children) {
    $d = $c.data
    if ($d.author -eq $cfg.user) { continue }
    $out += [pscustomobject]@{ thing = $d.name; author = $d.author; text = $d.body; thread = $d.link_title
        parent = $d.parent_id; link = 'https://www.reddit.com' + $d.permalink; created = $d.created_utc }
}
Start-Sleep -Seconds 2
$posts = Invoke-RedditGet $token "/r/$script:Subreddit/new?limit=25&raw_json=1"
foreach ($p in $posts.data.children) {
    $d = $p.data
    if ($d.author -eq $cfg.user) { continue }
    $out += [pscustomobject]@{ thing = $d.name; author = $d.author; text = $d.selftext; thread = $d.title
        parent = $null; link = 'https://www.reddit.com' + $d.permalink; created = $d.created_utc }
}
$path = Join-Path (Get-QueueHome) 'inbox.json'
[IO.File]::WriteAllText($path, (ConvertTo-Json -InputObject @($out) -Depth 5), $script:Utf8)
Write-Host "$($out.Count) posts and comments written to $path"
