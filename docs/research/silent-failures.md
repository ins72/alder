# Silent failures in Alder

Read-only audit of `app/src/main/java/dev/alder/*.kt`. Paths below are relative to that folder.

Sites checked: 92 (79 `runCatching` calls and 13 `catch` blocks). The grep for `runCatching` finds 82 lines, but 3 of them are `return@runCatching` labels in `WatchNext.kt`, not new calls.

Verdicts:

- **should report** (5): the viewer loses something, or sees an empty or wrong screen with no message.
- **should log** (6): nothing for the viewer, but a crash or support report would want it.
- **fine** (81): optional data, a cache, a fallback that shows its own message, or a failure that is already recorded or logged.

## Table

| File:line | What is dropped | What the viewer sees | Verdict | Suggested fix |
|---|---|---|---|---|
| DetailsActivity.kt:1367 | Each add-on's `search(actor.name)` failure (`getOrNull().orEmpty()`) | Tapping an actor opens nothing; the screen falls back to Info with no message when every add-on fails | should report | When the list is empty and an add-on failed, show `failureMessage(..., failure)` instead of silently going back to Info. |
| DetailsActivity.kt:1577 | Failure of the browser fallback for a trailer | Trailer button does nothing when neither a YouTube app nor a browser opens | should report | Show a toast when the fallback also fails. |
| PlayerActivity.kt:1042 | Same browser fallback, in the player's trailer path | Trailer closes back to the title with no message | should report | Same toast as Details, before `finish()`. |
| MainActivity.kt:2320 | Add-on lookup failure (`runCatching` gives null, so `videos` is empty); `markTitleWatched` then sets the title watched without per-episode marks | A series shows as watched in My List but its episodes are not marked; no message | should report | If the lookup failed for a series, keep the old state and show `failureMessage`. |
| StreamsActivity.kt:162 | A lender profile's add-on list (`getOrDefault(emptyList())`) | Lent sources are silently missing. If nothing else is left, the status says "no add-ons" or "none for this kind", which blames the TV | should report | Collect lender failures and show them with `streams_load_failed`, as the own-add-on path does. |
| DetailsActivity.kt:1129 | Failure of each add-on's `similar()` (skipped by `firstNotNullOfOrNull`) | The "More like this" row is missing | should log | `FailureLog.record(DETAILS, failureOf(e))` for each failing add-on. |
| Library.kt:102 | Malformed `state.watched` bitmap (`getOrDefault(emptySet())`) | Episodes show as unwatched | should log | `Log.w` in the `runCatching` failure path. |
| MainActivity.kt:2022 | `Tmdb.services` failure (`getOrDefault(emptyList())`) | The Discover "On [service]" chip is missing, with no message | should log | `Log.w` on failure; the chip returns on the next visit. |
| Tmdb.kt:154 | `external_ids` failure for one title (title dropped from the page) | Discover page is shorter; paging continues quietly | should log | `Log.w` per failure, and count the skipped titles. |
| MkvChapters.kt:76 | Any exception in the chapter parser (`getOrDefault(emptyList())`) | Chapter markers are absent; a parser bug is hidden | should log | `Log.w(e)` in the failure path. |
| SettingsActivity.kt:53 | Corrupt hidden-titles JSON (`getOrDefault(JSONObject())`) | The "hidden from Continue Watching" restore list is empty, so titles cannot be restored here | should log | `Log.w` and reset the pref. |
| Addon.kt:91 | Saved catalog JSON parse | Falls back to the network | fine | Cache read; no action. |
| Addon.kt:125 | Each catalog's search fetch (`Result` is passed up) | Shown by the search screen via `failure` | fine | Already reported to the caller. |
| Addon.kt:169 | `HttpError` 404 becomes "no streams" | Source list says none | fine | Intentional: 404 means no sources. |
| Addon.kt:203 | Saved answer parse | Falls back to network | fine | Cache read. |
| Addon.kt:220 | Per-add-on `meta()` (`Result` returned to caller) | Caller shows `failureMessage` when `reportFailure` is set | fine | Already reported to caller. |
| Addon.kt:234 | Saved meta lookup | Falls back to network | fine | Cache read. |
| Addon.kt:686 | IMDb certificate (content rating) | Rating row hidden | fine | Optional enrichment. |
| Addon.kt:729 | IMDb trailer lookup (`getOrDefault(emptyList())`) | Trailer button hidden | fine | Optional enrichment. |
| Crashes.kt:27 | Writing the crash file inside the crash handler | Nothing; app is already dying | fine | Cannot log from a crash handler. |
| Crashes.kt:33 | Reading the saved crash | No crash offer | fine | Harmless. |
| Crashes.kt:66 | `versionCode` lookup (`getOrDefault(0)`) | Report shows build `0` | fine | Only the crash report text. |
| DetailsActivity.kt:47 | Day parse for "aired?" check | Treated as aired | fine | Regex already checked the format. |
| DetailsActivity.kt:51 | Time parse for "aired?" check | Falls back to day check | fine | Same reason. |
| DetailsActivity.kt:64 | Day parse for "Airs" label | No label | fine | Label only. |
| DetailsActivity.kt:72 | Day parse for "recently aired" | Not shown as new | fine | Badge only. |
| DetailsActivity.kt:80 | Day parse for episode date | No date | fine | Label only. |
| DetailsActivity.kt:1362 | TMDB credits (`getOrDefault(emptySet())`) | Filmography sort is less precise | fine | Ranking only; the list still shows. |
| DetailsActivity.kt:1574 | First trailer attempt (`isSuccess`) | Falls through to the browser | fine | The second attempt is the one that matters (1577). |
| DetailsActivity.kt:1616 | Library refresh on return (falls back to saved) | Shows last saved state | fine | Fallback is intentional. |
| Failures.kt:66 | Progress sync failure | Recorded in `FailureLog`; shown by caller | fine | Already recorded. |
| Http.kt:49 | Saved answer read | Falls back to network | fine | Cache read. |
| Http.kt:54 | Writing a cache file | Next visit asks the network again | fine | Cache write is best-effort. |
| Images.kt:105 | Download or decode of artwork (null) | Placeholder art | fine | Art is optional. |
| Images.kt:112 | Waiting on a shared download | Placeholder art | fine | Same as above. |
| MainActivity.kt:1788 | Genre read (`Failure` captured and used) | Row shows its failure message | fine | Already reported. |
| MainActivity.kt:1983 | Missing list-card details | Card without art | fine | Optional enrichment. |
| MainActivity.kt:2014 | Discover prefs JSON parse | Default Discover query | fine | Default is sensible. |
| MainActivity.kt:2843 | Meta fetch for a Home card (null) | Card without art or facts | fine | Optional; `settle()` still runs. |
| MainActivity.kt:2902 | Saved meta for Continue Watching | Default filter (keep the item) | fine | Keeps the item. |
| PhoneEntry.kt:28 | `accept()` after server closes | Loop ends | fine | Normal shutdown. |
| PhoneEntry.kt:29 | Serving one phone request | That request dies; the next works | fine | Per-request. |
| PhoneEntry.kt:34 | `close()` | Nothing | fine | Idempotent close. |
| PhoneEntry.kt:86 | LAN address lookup (null) | Type it on the TV | fine | Caller already falls back. |
| PlaybackAudio.kt:72 | Media3 `ConfigurationException` (expected) | Passthrough not offered | fine | Media3 control flow. |
| PlaybackAudio.kt:84 | `IllegalStateException` rethrown as `ConfigurationException` | Passthrough not used | fine | Rethrows; not silent. |
| PlayerActivity.kt:692 | Add-on subtitle failure | Fewer subtitles | fine | Already `Log.w`. |
| PlayerActivity.kt:994 | `ActivityNotFoundException` when no video app | Returns false; player says "no other app" | fine | Caller shows the message. |
| PlayerActivity.kt:1039 | First trailer attempt (`isSuccess`) | Falls through to the browser | fine | The fallback is at 1042. |
| ProfilesActivity.kt:681 | Other profile's user id lookup | Treated as not the same account | fine | Warning only; sign-in continues. |
| ProfilesActivity.kt:705 | Other profiles' add-ons for Watchly setup (`getOrDefault`) | Watchly setup has fewer hints | fine | Optional hint. |
| Ratings.kt:48 | Letterboxd fetch | Falls back to saved; logged | fine | Already `Log.w`. |
| Ratings.kt:51 | Letterboxd parse | No rating | fine | Optional. |
| Screen.kt:87 | Pixel sampling (null) | Default colour | fine | Visual only. |
| Screen.kt:185 | `background()` wrapper around `runCatching(work)` | Each caller decides | fine | Pass-through; callers checked above. |
| Screen.kt:555 | Phone entry setup (null) | Falls back to on-TV typing | fine | Intentional fallback. |
| Screen.kt:849 | `CHANGELOG.md` read (null) | "No notes" dialog | fine | Shown when not quiet. |
| SearchActivity.kt:515 | Popular catalog read per catalog | Next catalog tried; "Popular unavailable" if all fail | fine | Shows its own message. |
| SearchActivity.kt:573 | Per-add-on search (`Result` to `failures[]`) | Section shows its failure | fine | Already reported. |
| SettingsActivity.kt:82 | Tab order JSON | Default order | fine | Default. |
| SettingsActivity.kt:107 | Title choice JSON | No saved choice | fine | Default. |
| SettingsActivity.kt:114 | Picked-in-player JSON | Starts fresh | fine | Overwrites a corrupt value. |
| SettingsActivity.kt:138 | Old subtitle bool migration | Default on | fine | Migration. |
| SettingsActivity.kt:163 | Recent subtitles JSON | Empty list | fine | Default. |
| SettingsActivity.kt:174 | Recent audio JSON | Empty list | fine | Default. |
| SettingsActivity.kt:211 | ISO-3 code lookup (default) | Language name stays as code | fine | Only affects a name. |
| SettingsActivity.kt:710 | Add-ons for tab list (falls back to saved) | Saved tabs | fine | Fallback is intentional. |
| StreamsActivity.kt:326 | QR code for add-on setup (null) | No QR; text still shown | fine | Optional. |
| StreamsResolver.kt:126 | Add-on list for auto-play | Falls back to the source picker, which shows its own error | fine | Picker reports. |
| StreamsResolver.kt:129 | Lender add-ons for auto-play | Auto-play uses own add-ons | fine | Picker reports. |
| StreamsResolver.kt:162 | One add-on's streams (`null`) | That add-on gives nothing; others still play | fine | Fallback to picker shows failures. |
| Stremio.kt:34 | `readLink` `ApiException` 101 | Still waiting for approval | fine | Rethrows other codes. |
| Stremio.kt:49 | Saved add-on list parse | Falls back to network | fine | Cache read. |
| Stremio.kt:91 | Saved library parse | Falls back to network | fine | Cache read. |
| Stremio.kt:110 | `saveLibrary` failure | Rolls back and rethrows | fine | Rethrows. |
| Stremio.kt:137 | Cached library parse in sync | Network data used | fine | Cache write only. |
| SubtitleFiles.kt:51 | UTF-8 decode failure | Legacy code page used | fine | Intentional fallback. |
| Tmdb.kt:18 | TMDB cast (`runCatching` to empty) | Cast row absent | fine | Optional enrichment. |
| Tmdb.kt:78 | TMDB credits for filmography (`getOrDefault`) | Ranking only | fine | Caller uses it for sort. |
| Tmdb.kt:158 | `Future.get` after `runCatching` in 154 | Nothing new | fine | Cannot fail beyond 154. |
| TorrentSource.kt:90 | Engine start failure | Stops engine, rethrows | fine | Rethrows. |
| TorrentSource.kt:100 | Engine "metadata not ready" | Retries; other errors rethrown | fine | Rethrows. |
| TorrentSource.kt:129 | Bad URI in torrent link (null) | Source skipped | fine | Malformed input. |
| TorrentSource.kt:134 | Bad tracker encoding (null) | Tracker skipped | fine | Malformed input. |
| TorrentSource.kt:146 | Bad tracker URI (`false`) | Tracker skipped | fine | Malformed input. |
| Updates.kt:26 | Update check (null) | Settings shows "check failed"; Home shows nothing | fine | Settings reports it. |
| Updates.kt:71 | `canInstall` on old Android (`true`) | Install allowed; Android asks | fine | Android handles it. |
| Updates.kt:86 | Install settings page missing (`false`) | Caller shows steps | fine | Caller reports it. |
| Updates.kt:112 | Install download failure | Abandons session and rethrows | fine | Rethrows. |
| WatchNext.kt:19 | Launcher sync failure | Logged (`Log.w`) | fine | Already logged. |
| WatchNext.kt:63 | Backdrop lookup (null) | Falls back to library art | fine | Optional art. |
| WatchNext.kt:69 | Timestamp parse (`now`) | Uses current time | fine | Default. |

## Top 5 "should report"

1. **DetailsActivity.kt:1367**: an actor whose add-ons all fail opens nothing, with no message.
2. **DetailsActivity.kt:1577** and **PlayerActivity.kt:1042**: the trailer does nothing (or closes) when no YouTube app or browser opens.
3. **MainActivity.kt:2320**: "Mark as watched" on a series with a failed add-on lookup marks the title watched but no episodes.
4. **StreamsActivity.kt:162**: a lender's add-ons vanish silently, and the status can say "no add-ons", which is wrong.
5. The five "should report" items above are the only ones where the viewer loses something with no message. The six "should log" items need no screen change.
