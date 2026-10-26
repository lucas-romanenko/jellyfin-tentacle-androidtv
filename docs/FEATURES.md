# Settings

The TV app's Settings are deliberately small (2026-10-22): customising happens in Tentacle
("any customization should be done from tentacle"), and the rest has fixed defaults.

## What Settings shows

| Row | What it does |
|---|---|
| **Switch user** | Back to "Who's watching?"; the person stays signed in on this TV |
| **Log out** | Signs the person out of this TV; their password is needed next time |
| **Screensaver** | On/off. Shows pictures from the library when the TV is left alone (after 5 minutes by default) |
| **Check for updates** | github flavor only, see below |
| **About** | App version, the server's address, who is signed in |

Developer options (debug logging, clear the image cache, …) are not in the menu: press the
app version 7 times.

## Set in Tentacle, not on the TV

- **Home rows**: which rows, their order and each row's picture style (poster or wide), and the
  navbar buttons: the person's home layout in Tentacle, live on every TV.
- **The hero** (the banner at the top of Home): its content, from Tentacle's home settings.
- **Subtitle and audio languages**: the person's Jellyfin settings. During playback the player's
  own track picker still switches tracks.
- **Kids**: Tentacle's kids profiles. The TV no longer has its own rating blocks.

## Fixed on every TV

`preference/FixedLook.kt`. A choice made on the TV before is forgotten once, at the first start
of the version that removed the options.

- Focus colour: Tentacle's accent (#6C63FF)
- A focused card grows slightly
- Background blur: light; the background follows the selected item
- Watched markers on; the clock on, top right; navbar on top
- Posters, no series thumbnails; theme music off
- Live TV guide: favourites first. The guide's options and filter buttons are gone (filters
  saved before are cleared): Favorites | All is the choice. Header: Live TV, Favorites | All,
  Multi-view, Recordings, then the dates on the right (scrolling there when there are many).
  A date is offered only when it has real guide data (at least a quarter of the busiest day
  before it), so one event listing a week ahead adds no empty days.
- Live TV multi-view (try-out): Multi-view in the guide, tick 2-4 channels, Start. One
  picture from Tentacle; the highlighted tile is heard, OK = full screen, Back = the grid.
  No screensaver while it plays, like the normal player.

Playback choices made on the TV before (maximum bitrate and resolution, refresh-rate
switching, audio output, external player) are kept as they were; new installs get the
defaults. Inside a library, its view options (grid, picture size) stay in the library's own menu.

## Menus (long press of OK, or Menu)

One menu everywhere (`ui/shared/menu/TvMenu.kt`): small, dark and rounded, the focused choice
purple, on the right. It ignores the rest of the press that opened it, so letting go of OK never
picks its first choice (`OkKeyGate`); confirmations use it too.

- **Guide programme**: Watch now (on now) or Tune to channel, Record this / Record every
  episode (or their cancels), Details. No favourite here.
- **Guide channel name**: a favourite at once ("Added to Favorites"); taking one off asks first.
- **Home**: in Continue Watching, Remove from Continue Watching (the saved place goes, watched
  stays), Play from start, Mark watched, Details; other cards Mark watched / unwatched, Details.
  The row changes at once. A long press never also opens the card (`OkLongPress`).
- **Recordings**: a recording still recording plays on OK (live, on the stream the recording
  already uses); its menu has Watch live and Play from start.

## Live preview in the guide

With a provider connection to spare (Tentacle's `/LivePreview`, asked every 15 s), the guide
header's box plays the focused channel muted after 1.5 s on a programme on now; a later
programme shows its picture. Without one the box goes and the text moves left; this is decided
when the guide opens and when the answer changes, not per programme. OK on the previewed channel
hands its stream to the player. `ui/livetv/GuidePreview.kt`.

## Live TV player

- **Guide button** (or Menu): the guide with the channel in the top-left corner. Its page (25
  channels on a small TV) is built ahead, hidden, 4 s after a channel starts, from the channel
  list the app already has (read once if it has none), and again when the half hour turns, so
  the first open is as quick as the later ones. It opens on the playing channel's row.
  `ui/playback/PlayerGuide.kt`, logcat `PlayerTiming`.
- **Channels button** (or Down): the person's recent channels, most recent first, without the
  one playing, so OK on the first card goes back to the last channel. From Jellyfin's per-person
  last played date, so it follows them across devices; their favourites when they have no
  recents yet. Cards as Favorites' channels (logo, number, what's on, progress, purple focus).
  Read when each channel starts and again on open; while open it follows changes from anywhere.
  `ui/playback/RecentChannelsPanel.kt`, `ui/livetv/RecentChannels.kt`.

## Refresh rate

The TV's own **Match content frame rate** setting decides (Android 12 and later, in
Android's display settings):

- **Always**: films, episodes and recordings play at their own frame rate (a 23.976 fps
  episode at 23.976 Hz instead of juddering at 60 Hz), at the TV's resolution. The screen goes
  black for a second or two while the TV switches: the video starts after that, and the TV
  switches back when the player closes. Interlaced video plays at its field rate (25i at 50 Hz).
  Live TV stays in the TV's mode (a channel's rate is often unknown, and every zap would black
  out), unless refresh-rate switching was picked on the TV before.
- **Never**: never switches.
- **Seamless only**, or Android 11 and older: switches only where there is no blackout, unless
  refresh-rate switching was picked on the TV before.

`ui/playback/DisplayModeSwitch.kt`.

## Updates

Install: the **Downloader** app on the TV with your Tentacle server's address + `/tv` (the
server redirects to the newest release), or
`https://github.com/lucas-romanenko/jellyfin-tentacle-androidtv/releases/latest/download/tentacle-androidtv-github-release.apk`.
After that the app offers new versions itself (github flavor; not in the Play Store build).

- **New version dialog**
  - When Home shows (opening the app, coming back to it, back from playback) and the last
    check you saw is 6 hours old, or at once when the daily background check found a new
    version; after Home has loaded, never over playback: release notes, then
    **Update now** (download with percent) and the system installer's **Install**
  - **Later** hides that version for 3 days; a newer one shows at once
  - ⚠️ The first time, Android asks to allow Tentacle to install apps; the update carries on
    when you come back
- **Check for updates** (Settings)
  - Says "You're on the latest version", offers the same dialog, or "Couldn't check for
    updates right now" (no internet, GitHub busy)
  - Debug builds don't update themselves ("Debug build: updates come from test builds")
