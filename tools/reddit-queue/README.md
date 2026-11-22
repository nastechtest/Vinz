# Reddit reply queue

A small tool for Vinz's developer, to answer people in r/VinzMusicApp sooner. It is not part of the app.

## What it does

1. `fetch.ps1` reads the newest posts and comments of r/VinzMusicApp (two requests per run).
2. A reply to each question or bug report is drafted on the developer's PC with an AI assistant (Claude), checked
   against Vinz's source code and its GitHub issues.
3. `queue.ps1` shows the developer each draft. They approve it, edit it, skip it or leave it for later.
4. Only an approved reply is sent, as a comment in that thread, from the developer's account.

Nothing is ever posted without the developer reading and approving that exact text. There is no timer and no
unattended posting.

## What it does not do

- No votes, no private messages or chats, no new threads.
- No other subreddit than r/VinzMusicApp.
- No repeated or copied replies.
- No Reddit data is kept beyond the threads being answered, none is shared, none is used to train AI.

## Access it asks for

`identity` (who is signed in), `read` (posts and comments), `submit` (post an approved reply). A few requests a
minute at most, usually fewer than ten replies a day.

## Without the API

`queue.ps1 -Paste` uses no API at all: the approved reply goes on the clipboard, the thread opens in the browser,
and the developer pastes and sends it by hand.

## Files

- `setup.ps1`: one-time sign-in through Reddit's own "Allow" page. The Reddit password is never asked for.
- `fetch.ps1`: reads new posts and comments.
- `queue.ps1`: approve and send.
- `lib.ps1`: shared pieces. `test.ps1`: checks that need no Reddit.

Keys and the queue are kept in `%USERPROFILE%\.vinz-reddit`, outside this repository; the keys are encrypted for
the developer's Windows account.

License: GPL-3.0-or-later, like Vinz.
