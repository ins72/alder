# Alder: Reddit feedback and priority report

Report date: 3 November 2026.

## Decision

Give device support, audio, and access to episodes first priority. Then correct source selection, add-on access, and watch progress.

Improve text size and setup help before large new features. Keep the app small and simple.

The priority order below is an assessment of user impact. Reddit votes do not establish the number of affected users.

## Sources and limits

This report covers the Alder posts found in r/AndroidTVApps and r/AndroidtvPiracy.

| Source | Post date | Post | Comment count shown |
|---|---|---|---:|
| A | 28 October 2026 | [AndroidTVApps launch](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/) | 239 |
| B | 30 October 2026 | [AndroidTVApps version 1.3.1](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/) | 92 |
| C | 28 October 2026 | [AndroidtvPiracy launch](https://www.reddit.com/r/AndroidtvPiracy/comments/1xhjm3c/) | 35 |
| D | Not used for fault dates | [AndroidTVApps duplicate update](https://www.reddit.com/r/AndroidTVApps/comments/1xjyaan/) | 0 |

The review includes expanded replies and additional comments available through the browser. Source A exposes 227 comment articles. Source B exposes 91. Source C exposes 34.

These counts include author replies and comments with no fault report. They are not counts of faults or affected users.

The visible counts do not fully match the post totals. Deleted, removed, or unavailable text can contain additional feedback.

This report lists all distinct problems and requests found in the accessible text. It does not claim access to unavailable comments.

Author replies establish the author's stated intent. A promise of a fix does not establish that the fix works.

The local changelog provides release claims. It does not establish a successful test on each affected device.

The report uses ASD-STE100 sentence rules and consistent technical names. Product names, screen labels, and protocol names remain unchanged.

## Status terms

| Term | Meaning |
|---|---|
| Open report | A user reports a fault. The review finds no clear success result for that fault. |
| Test again | Release notes claim a related fix. A test must establish the result on the affected device. |
| User reports success | The affected user states that a change corrects the problem. |
| Request | A user asks for a capability. This does not prove a fault. |
| Help gap | A user cannot find or understand an available capability or setup step. |

## Priority order

The order applies to work that remains. A successful test can close an older fault without a new code change.

| Order | Area | Reason | First action |
|---:|---|---|---|
| 1 | Fire TV install and launch | Some users cannot open the app, including a reported version 1.3.1 failure. | Reproduce each device and OS case. |
| 2 | Receiver and lossless audio | A user loses the expected audio format despite the Receiver choice. | Test the reported TV box and receiver path. |
| 3 | Episode and title access | Some title pages have no episode controls. | Test the affected catalogs and Firestick rows. |
| 4 | Source selection and failure recovery | Automatic play can select an error video. | Separate an error response from a valid film. |
| 5 | Add-on access and account setup | Users can see titles but cannot play them. | Check account identity and add-on responses. |
| 6 | Watch progress and next episodes | A watched episode does not always produce the expected next episode. | Check manual watched state and Home refresh. |
| 7 | Audio and subtitle preferences | A language choice does not always apply automatically. | Test preferred languages and exact track choice. |
| 8 | Text and poster size | Small labels and posters reduce access from a normal seat. | Check larger text and poster sizes. |
| 9 | Catalog filters and remote focus | Filters and focus behavior make title selection difficult. | Check year values and row transitions. |
| 10 | Setup and install help | Users do not understand accounts, add-ons, or install warnings. | Add short steps with exact screen names. |
| 11 | External player | Repeated requests ask for another player. | Define a simple player handoff and return path. |
| 12 | Trakt | Users request history, watchlist, and watched-state sync. | Confirm service access and scope. |
| 13 | Home, catalog, and profile controls | Users request more control of rows and profiles. | Add the smallest useful controls after fault tests. |
| 14 | New account and source systems | Requests include Nuvio, torrents, and Xtream Codes. | Assess each system separately. |
| 15 | New platforms and optional display effects | These requests extend the product beyond current TV use. | Keep them after core reliability work. |

## Device support and access

### 1. Firestick failure on version 1.3.1

Status: Open report. Priority: 1.

MrCalPoly reports a Firestick failure and confirms version 1.3.1. The release claim for Fire TV support does not close this report.

Sources: [fault](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rzxo9bzpulf/), [version](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rzyc6vrp0kj/).

Action: Obtain the exact model, OS, and failure step. Test a new install and an update with existing profile data.

Close condition: The affected device installs the APK, opens a profile, and starts a valid video.

### 2. Fire TV 4K first-generation launch failure

Status: Open report. Priority: 1.

dionread reports a launch failure on Fire TV 4K first generation with Fire OS 6.7.1.1.

Source: [device report](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19s074qk1i78l/).

Action: Test this OS case separately from newer Fire TV devices. Record the exact APK version before the test.

Close condition: The app opens repeatedly without a return to the device Home screen.

### 3. Fire OS 6 package parse error

Status: Test again. Priority: 1 if the current APK still fails.

SufficientCoconut450 reports a package parse error. The comment names Firestick 4K second generation and Fire OS 6.

Keep both details as user-supplied information. Confirm the model and OS before a device-specific conclusion.

Source: [install report](https://www.reddit.com/r/AndroidtvPiracy/comments/1xhjm3c/comment/19rtt68pxhnxe/).

Version 1.3 release notes claim a Fire TV parse-error fix. Test the current release before a new fix.

### 4. Android 7 returns to Home at launch

Status: Test again. Priority: 1 if the current APK still fails.

billyme_ reports that the app closes at launch on Android 7.

The author identifies a Typeface.Builder call and promises a fix. This is an author diagnosis, without a user success result.

Sources: [fault](https://www.reddit.com/r/AndroidtvPiracy/comments/1xhjm3c/comment/19rwd6mkum6hu/), [author diagnosis](https://www.reddit.com/r/AndroidtvPiracy/comments/1xhjm3c/comment/19rwdxu1p17c2/).

Version 1.3 notes claim Android 6.0 support. The local build also sets minSdk to 23.

Action: Test the lowest supported OS and Android 7. Check all startup screens, not only the first activity.

### 5. Google TV warns about a potentially malicious app

Status: Open install report with a visible override. Priority: 10.

r8erl reports an install block through the Downloader code. A later reply confirms an **Install anyway** option.

The user does not confirm a successful install in the reviewed replies. The warning alone does not establish malware.

Sources: [initial report](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rvsqu7mvd7l/), [follow-up](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rvsykdfkdy8/).

Action: Record the exact warning and verify the official APK and download path. Explain the result with short install steps.

Do not assume every external APK produces the same warning. The user's reply disputes that explanation.

### 6. Phone setup screen extends outside the display

Status: Open report outside the stated TV scope. Priority: 15.

Think_Bullets cannot see the last alphabet row or the next button on a phone.

The user wants to test away from home and prefers phone text input. The author states that Alder is a TV app.

Sources: [screen report](https://www.reddit.com/r/AndroidtvPiracy/comments/1xhjm3c/comment/19rzgqysw9co3/), [use case](https://www.reddit.com/r/AndroidtvPiracy/comments/1xhjm3c/comment/19rzt8sis7ntw/).

Action: State the supported device scope clearly. Treat phone support as a separate product decision.

## Audio, video, and source access

### 7. Receiver output loses the expected lossless format

Status: Open report. Priority: 2.

davejos8 reports lossless-audio problems with a Formuler Z12 Ultra and a Pioneer 5.1 receiver.

The app rejects lossless sources and tries other sources until Dolby Digital 5.1 or Dolby Digital Plus plays. The Receiver choice is already active.

The report does not establish an unwanted audio conversion. It establishes source refusal and fallback to another format.

Sources: [audio report](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rwmjxruibs4/), [follow-up](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rwuc9my1dt1/), [device](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rxmxf9eef50/).

Action: Record the file audio format, selected track, receiver model, and HDMI path. Compare Alder with a player that preserves the format.

Close condition: The receiver gets the supported expected format. An unsupported path produces a clear explanation.

### 8. Firestick title pages have no episode controls

Status: Open report. Priority: 3.

aaronshappyending reports this on a 2020 Firestick. In **Shows**, titles below the first four rows show only a title and picture.

The user cannot open episodes or select another action.

Source: [episode-page report](https://www.reddit.com/r/AndroidtvPiracy/comments/1xhjm3c/comment/19rt47e96fper/).

Action: Compare the first four rows with later rows. Check catalog IDs, metadata responses, and remote focus.

Close condition: Each valid series has reachable episode controls. A metadata failure shows a clear error and a retry action.

### 9. Automatic play selects an error video

Status: Open report. Priority: 4.

Physical_Search7847 reports that automatic play selects an error-placeholder video instead of another valid source.

Source: [source-selection report](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rxns4fyulgg/).

Action: Inspect the add-on response and the selected URL. Keep a valid short video distinct from a known service error response.

Close condition: A known error source does not stop the automatic source sequence when a valid source exists.

### 10. Some add-ons produce an install-in-Stremio prompt

Status: Open report. Priority: 5.

junioreality reports a prompt to install an add-on in Stremio when the user selects a stream.

The comment does not identify the add-ons. The cause remains unknown.

Source: [add-on prompt](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rwyd4fiki3p/).

Action: Obtain the add-on name and a response sample without account secrets. Check installed state, URL type, and error text.

Close condition: A supported installed add-on plays correctly or explains the exact missing setup step.

### 11. No streams after sign-in with the wrong account method

Status: User reports success. Priority: 10 for prevention.

Pleasant_Maximum6367 reports no playable streams with Real-Debrid and AIOStreams. Later, the user reports success after sign-in with Apple instead of email.

Sources: [initial fault](https://www.reddit.com/r/AndroidtvPiracy/comments/1xhjm3c/comment/19ry16dh8l84j/), [details](https://www.reddit.com/r/AndroidtvPiracy/comments/1xhjm3c/comment/19rzh1s3k2lb4/), [success](https://www.reddit.com/r/AndroidtvPiracy/comments/1xhjm3c/comment/19s06r8qcgpcy/).

Action: Tell users to use the same Stremio account and sign-in method. Show useful account and add-on state after sign-in.

Do not count this case as proof of a remaining stream-parser fault.

### 12. New users see titles but cannot play them

Status: Help gap. Priority: 10.

alpha-9909 confirms Stremio sign-in but does not know about debrid or extra add-ons. The user expects the initial add-ons to provide playback.

Sources: [problem](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19ry47koqh9ya/), [setup details](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19ryvf25lca02/).

Action: Explain account, catalog, and stream add-on roles during setup. Show the missing requirement before the user repeatedly selects Play.

### 13. Automatic source quality is unclear

Status: Help gap; possible preference issue. Priority: 4 for verification, 10 for text.

forcedmarcel expects 4K first but sees 720p first. The author points to **Settings → Streams**.

Leezey1 also asks what **Balanced** changes.

Sources: [quality order](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rwagf7sffz4/), [Balanced question](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rz6xhfyrp9d/).

Action: Test quality preferences against valid available sources. Explain the tradeoff beside each choice.

### 14. External player

Status: Repeated request. Priority: 11.

Maleficent_Platform8 and Leezey1 ask for an external player. An author promise does not establish that the feature ships.

Sources: [piracy request](https://www.reddit.com/r/AndroidtvPiracy/comments/1xhjm3c/comment/19rucqs0emvs1/), [update request](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rz6xhfyrp9d/).

Action: Preserve title, episode, and resume information during player handoff. Check the return to Alder before release.

### 15. Internal libVLC player

Status: Request. Priority: 14.

ConstantBet4764 asks for libVLC as an internal player. This differs from an external-player choice.

Source: [libVLC request](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19s2kavqmie8x/).

Action: Establish a playback case that the current player cannot support before an additional internal player.

### 16. Video zoom or stretch

Status: Request. Priority: 13.

Bright_Ad1447 asks for a control to enlarge or stretch the picture. The author states that it is not available yet.

Source: [picture-size request](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rw4igythkf4/).

Action: Add clear picture-fit choices if this work proceeds. Preserve the original aspect ratio as an available choice.

## Language and subtitles

### 17. Romanian preference does not apply automatically

Status: Open report. Priority: 7.

Ruibix reports that the Romanian preference does not apply automatically. The author acknowledges the report.

Sources: [language report](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19s1rp6cibsbl/), [author reply](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19s24qj3cqi3k/).

Action: Check language-code matching, saved profile preferences, and automatic track choice with the user's source.

Close condition: A preferred available track starts automatically and remains correct after an episode change.

### 18. Automatic forced subtitles

Status: Request. Priority: 7.

G4ndorf asks for forced subtitles in the default language instead of full subtitles. The author adds the request to the list.

Sources: [request](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rw8w0uivl43/), [reply](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rw9wpmn2obn/).

**Foreign audio only** does not prove support for this exact request. Forced tracks and full foreign-language tracks require separate tests.

### 19. Track controls are difficult to find

Status: Help question. Priority: 10.

v_bras asks whether audio language and subtitles can change during playback. The author confirms that these controls exist.

Source: [track-control question](https://www.reddit.com/r/AndroidtvPiracy/comments/1xhjm3c/comment/19rwcl0f9unol/).

Action: Explain how to open **Audio & subtitles** with the remote. Do not record this question as a missing feature.

## Text, posters, catalogs, and remote use

### 20. Text is too small

Status: Open usability report and request. Priority: 8.

Sky131048 reports small **Search**, **Movies**, and series labels on a parent's TV. The user requests a text-size control.

Source: [text-size report](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rywmy6fo1wh/).

Action: Check labels from a normal seat. Verify larger text without clipped labels or lost remote focus.

### 21. Posters are too small

Status: Repeated usability report. Priority: 8.

ilovelamp2410 and sandhu84 report small posters on large TVs. They ask for larger posters or a size control.

Sources: [first report](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rwc3qk1v739/), [second report](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rxabzo1049u/).

Action: Test poster sizes with long titles and normal TV distance. Keep focused posters inside the screen.

### 22. Year filter has no selectable year

Status: Open report. Priority: 9.

toyoto states that Stremio permits a year choice, such as 2026 or 2005. Alder shows **year** without that choice.

Source: [filter details](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rzya6t4nyjk/).

Action: Check the catalog's filter values and required extra parameters. Test the selected value in the outgoing catalog request.

### 23. Next row retains the previous horizontal position

Status: Open usability report. Priority: 9.

LazerDiller2 reports that a move to another row retains the eleventh position.

Source: [row-focus report](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rx6bifmdmox/).

Action: Confirm the expected row-entry position. Check focus restoration separately from the first visit to another row.

### 24. Row or grid layout choice

Status: Repeated request. Priority: 13.

ilovelamp2410 and sandhu84 request a choice between horizontal rows and a poster grid.

Sources: [row and wall request](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rwc3qk1v739/), [layout request](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rxabzo1049u/).

### 25. Nuvio collection JSON import

Status: Request. Priority: 14.

itsme99881 wants collection-file support. The user describes about 3,700 rows in 21 collections and objects to endless separate rows.

Source: [collection request](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rx1equb83hc/).

This is a catalog-organization request. It differs from Nuvio sign-in and Nuvio plugin support.

### 26. Anime tab

Status: Request. Priority: 13.

MamaSaidLUL asks for an anime tab after an anime add-on install. The author agrees to consider it.

Source: [anime-tab request](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19s04zhnmrlb4/).

### 27. Automatic trailer on poster focus

Status: Request. Priority: 15.

TechFuz requests automatic trailer playback when a poster has focus. A manual trailer button does not satisfy this request.

Source: [automatic-trailer request](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19s04vcif0sde/).

### 28. QWERTY keyboard

Status: Test again; release notes claim support. Priority: 9 if a fault remains.

bangigiampi requests QWERTY keys. Version 1.3 notes list QWERTY Search keys. The local Search screen defines QWERTY letter rows.

Source: [keyboard request](https://www.reddit.com/r/AndroidtvPiracy/comments/1xhjm3c/comment/19rtixip162qt/).

Action: Test all keys and remote paths. Do not create a duplicate implementation task.

## Accounts, profiles, and service scope

### 29. Nuvio sign-in

Status: Repeated request with a stated service dependency. Priority: 14.

Users ask for Nuvio sign-in in both communities. The author states that third-party access is not available through a documented flow.

This report records that statement as the author's account. It does not independently verify Nuvio's access policy.

Sources: [first request](https://www.reddit.com/r/AndroidtvPiracy/comments/1xhjm3c/comment/19rt3v6udgm03/), [combined request](https://www.reddit.com/r/AndroidtvPiracy/comments/1xhjm3c/comment/19rtixip162qt/), [update request and reply](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rvrnmzkrycg/), [later request](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rxr6tl5e7j5/).

### 30. Nuvio library and add-on sync

Status: Request. Priority: 14.

atharv66 wants the Nuvio library and add-ons in Alder. The user has debrid services set up in Nuvio.

Source: [sync request](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rxfc27ccgit/).

A shared email address does not establish shared account data. Explain the current Stremio account boundary.

### 31. Nuvio-specific plugins

Status: Request and compatibility question. Priority: 14.

Independent_Golf487 asks about Nuvio add-ons. The author distinguishes Stremio-compatible add-ons from Nuvio-specific plugins.

Source: [plugin question and answer](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rw9t8aoqdgk/).

Action: State the supported add-on protocol. Keep plugin support separate from account sync.

### 32. Direct torrent playback without debrid

Status: Repeated scope request. Priority: 14.

Federal-Space-1581, elderskrull, sero_t, and Verybusywolf ask about use without debrid or with direct torrent sources.

The author states that HTTP streams work and torrent-hash playback is not integrated.

Sources: [Torrentio request](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rvw5oiuuqap/), [without debrid](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rvzgtrdkpep/), [Torrentio and Comet](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rw7fa09cmn5/), [piracy question](https://www.reddit.com/r/AndroidtvPiracy/comments/1xhjm3c/comment/19rz71j5d25b5/).

Action: Explain HTTP support and torrent limits before install. Treat a torrent engine as a separate architecture decision.

Do not classify every non-debrid HTTP source as unsupported. The protocol and the source service are different constraints.

### 33. Xtream Codes credentials

Status: Request; author confirms it is absent. Priority: 14.

Exact-Abies-5182 asks for an Xtream username and password. The author states that this sign-in is not available.

Source: [Xtream request](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rw1yf8p82ao/).

### 34. Child profile

Status: Request. Priority: 13.

thedonza asks for a child profile. An ordinary profile with a PIN does not establish content restrictions.

Source: [child-profile request](https://www.reddit.com/r/AndroidtvPiracy/comments/1xhjm3c/comment/19rxseo1gj5uo/).

Action: Define the content boundary before implementation. Check catalogs, search, direct title access, and profile exit.

### 35. Account ownership and account removal are unclear

Status: Help gap. Priority: 10.

Hour-Mathematician31 asks whether each profile has its own Stremio account. The user also asks how to remove the account link.

eagarcia1001 asks for separate profiles, although the author states that they exist.

Sources: [profile questions](https://www.reddit.com/r/AndroidtvPiracy/comments/1xhjm3c/comment/19rwlbjfi93l1/), [profile discovery](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rwamkdo176p/).

Action: Explain each profile's account and shared source state. Make account change and profile deletion distinct actions.

### 36. Trakt history, watched state, and watchlist

Status: Request; author states that service access is pending. Priority: 12.

sandhu84 requests Trakt history updates and watchlist support. The author announces work on progress, history, and watched-state sync.

Sources: [user request](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rxabzo1049u/), [author status](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rvsp6daozad/).

The author says production access is the remaining dependency. This does not establish release readiness or watchlist support.

Action: Track watchlist scope separately. Test manual watched changes, resume points, profile boundaries, and conflict results.

### 37. Apple TV version

Status: Request. Priority: 15.

franklinyyz asks for Apple TV. The author states that test hardware is absent. Another user offers TestFlight help.

Source: [Apple TV request](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rw0ywl2dsbm/).

Treat this as a separate platform project. Do not include it in the Android TV fault-fix estimate.

## Help and product feedback

### 38. A short setup guide and an example configuration

Status: Repeated help gap. Priority: 10.

polorsport and m7box ask which add-ons produce the demonstrated setup. Ck-retro asks which add-ons supply live TV and sport.

Hopeful-Ad9694 asks whether Stremio must remain installed. Curious_Cellist9451 asks whether TorBox is specifically required.

Sources: [parent setup](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19s01cjfdgxib/), [example setup](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rx5w3rr26xu/), [live TV](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rw6z0yxqboi/), [Stremio install](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rw16j0d1msk/), [TorBox question](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rwi4ys8ns82/).

Action: Provide one short guide. Explain account setup, compatible sources, optional services, and what the TV app imports.

Show a separate live-TV setup path. A Live tab alone does not explain which compatible source supplies channels.

### 39. Update controls are not obvious

Status: Help gap and request. Priority: 10.

bangigiampi requests automatic updates. The author states that updates already exist.

Source: [update request](https://www.reddit.com/r/AndroidtvPiracy/comments/1xhjm3c/comment/19rtixip162qt/).

Version 1.3 notes list an update dot and a profile-menu install action. Test discovery and install separately from automatic checks.

### 40. Keep the app small and simple

Status: Product constraint. Apply to all priorities.

elli_mar83 asks for a small app without unnecessary features. The comment also reports smooth playback.

Source: [size and scope feedback](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rw2323ljp28/).

Action: Retain the simple Play path. Check memory, startup time, and APK size when source systems or players change.

### 41. Explain the difference from Nuvio and AIOStreams

Status: Product question. Priority: 10 for help text.

roddyc11 asks how Alder improves on Nuvio. End2EndEncryption says two Nuvio settings provide the same result.

testwiese420 suggests a self-hosted AIOStreams setup with automatic source choice and fallback.

Sources: [comparison question](https://www.reddit.com/r/AndroidTVApps/comments/1xjf5rd/comment/19rwcqggjpqqs/), [Nuvio comparison](https://www.reddit.com/r/AndroidtvPiracy/comments/1xhjm3c/comment/19s24is91atep/), [AIOStreams suggestion](https://www.reddit.com/r/AndroidtvPiracy/comments/1xhjm3c/comment/19rzrnwo88oys/).

Action: Explain Alder's device checks, remote use, profiles, and setup limits with examples. Do not treat comparisons as reproduced faults.

### 42. Concern about wider piracy exposure

Status: Community concern. No software-fix priority.

Specialist_Memory_77 objects to more mainstream access to piracy tools. This is a community-position concern, not an Alder compatibility failure.

Source: [community concern](https://www.reddit.com/r/AndroidtvPiracy/comments/1xhjm3c/comment/19rxyzw38gcpt/).

## Additional launch-post reports and requests

The item numbers below identify records. The priority column gives their work order from the priority table.

### 43. MAL anime lists and custom TMDB lists are absent

Status: Open report. Priority: 3.

CrustyRot reports that only a few MAL anime lists and custom TMDB lists appear.

Source: [missing catalogs](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rz1h0mzynfn/).

Action: Compare the Stremio catalog list with Alder's list for the same account. Check catalog IDs, types, filters, and pagination.

Close condition: All supported catalogs appear, or the app clearly explains an unsupported catalog.

### 44. Torrentio appears but does not load on Fire TV Cube 2

Status: Open report. Priority: 5.

Remarkable_Nose2336 reports this on Fire TV Cube 2 with Android 9. The user uses an established Stremio account.

Source: [Fire TV Cube report](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rwt692s8izp/).

The comment does not establish whether Torrentio supplies HTTP or torrent-hash sources. Do not assume unsupported torrents explain this case.

Action: Check the exact Torrentio configuration and source response. Keep account credentials out of the test record.

### 45. Torrentio requests configuration after a URL install

Status: Open report with a stated source limit. Priority: 5 for diagnosis; 10 for help.

Outrageous_Act_9098 reinstalls Torrentio by URL but still sees a configuration prompt. The author replies that torrent-hash playback is unsupported.

Sources: [configuration report](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rznmbcp0lk2/), [author explanation](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rznq1prn4mb/).

The reply does not establish a successful result. Check the actual source type before closure.

### 46. Other no-source and setup failures

Status: Mixed open reports and help gaps. Priority: 5 or 10, according to the actual cause.

| User | Report | Evidence and assessment |
|---|---|---|
| x3emee | Torrentio exists in Stremio, but the TV has no playable sources. | [Report](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rxbl72uzdpe/). No final success result appears. |
| oh1994io | Every title has no playable sources. The user cannot find how to add sources. | [Report](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rt3gipjb57k/). The author explains stream add-ons. |
| Poffertje101 | Nine add-ons exist, but Reacher has no playable sources. | [Report](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19ruekvnkax42/). The user confirms no stream-source setup. |
| Poffertje101 | Setup remains too difficult after several attempts. | [Follow-up](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rukl2xexan8/). This supports simpler setup help. |
| Tenchu83 | Only links to subscription services appear. | [Details](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rsgtdzpi04g/). The user requests a complete tutorial. |

Action: Distinguish an empty account, an unconfigured add-on, an unsupported source, and a failed supported source.

Do not merge all five cases into one confirmed player fault.

### 47. Next episode does not appear after manual watched changes

Status: Open report. Priority: 6.

Physical_Search7847 marks three episodes as watched. The next episode does not appear in **Continue Watching** until playback starts.

Source: [next-episode report](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rvyrkvyulgl/).

Action: Check manual watched state, the next unwatched episode, and Home refresh. Check the result after an app restart.

Close condition: The next eligible episode appears without a brief playback step.

### 48. Home has no suggestions with Featured on Home active

Status: Open report. Priority: 6.

The same user reports no Home suggestions although **Featured on Home** is active. This differs from the next-episode fault.

Source: [Home report](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rvyrkvyulgl/).

Action: Check the setting, catalog availability, initial data load, and refresh. Explain an empty result.

### 49. Greeting uses the wrong time of day

Status: Open usability report. Priority: 13.

Physical_Search7847 reports a greeting that says tonight at 09:45.

Source: [greeting report](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rvhpmr3eckj/).

Action: Check local time and greeting boundaries. Consider a time-neutral greeting if it meets the product need.

### 50. More harmful-app warnings

Status: Open install reports. Priority: 10, with item 5.

slackjawreally and Indervir007 also report harmful-app or harmful-file warnings.

Sources: [first warning](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rspks8fovtt/), [second warning](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rs8f6lueuxu/).

These reports strengthen the need for a verified download path and exact warning text. They do not prove a security fault.

### 51. Phone cannot pass the name screen

Status: Open report outside the stated TV scope. Priority: 15, with item 6.

Electronic-Metal2391 cannot pass the name screen. A follow-up identifies a Samsung phone.

Sources: [name-screen report](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rsn0w4oy881/), [phone confirmation](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rso9y9gnmld/).

Do not classify this as a TV setup failure.

### 52. General failure without enough details

Status: Insufficient evidence. No independent fault priority.

boywater states that two attempts fail. The author asks which part fails, but the reviewed text gives no specific step.

Source: [general failure](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rsjiqcpaiox/).

Action: Request device, version, exact step, and error text before a separate implementation task.

### 53. Genre selection

Status: Repeated request and filter-discovery issue. Priority: 9.

bugastine and Ruibix request genre selection. The author says work is in progress in one reply.

Sources: [genre and grid](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rsaav7o8ftu/), [later request](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19s1rp6cibsbl/).

The update-post author says genres depend on the catalog. Item 22 shows that a displayed filter can still lack usable values.

Action: Test real catalogs with genre values. Distinguish a missing catalog feature from a missing Alder control.

### 54. Actor details and similar titles

Status: Request; actor details have a release claim. Priority: 13.

One_Scholar2823 asks for actor information and similar-title recommendations.

Source: [actor and similar-title request](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rsj7hhz8v1h/).

Version 1.3 notes claim cast and director details with other works. They do not establish a general similar-title feature.

Action: Close the actor-information portion after a test. Keep similar-title recommendations as a separate request.

### 55. Keep the video active during source selection

Status: Request with an existing-control claim. Priority: 11.

Inder602 wants to browse other sources while the current video continues. The author points to an in-player popup.

Sources: [request](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rs7g1oazj8k/), [author reply](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rsaeeyyixwi/).

Action: Check whether the existing popup meets the full use case. Verify that playback stops only when another source is selected.

### 56. Simpler automatic source screen

Status: Request with a related release claim. Priority: 9.

Coder_Badri wants a small progress indicator, logo, or status message during automatic source choice. Detailed source information should remain available for manual choice.

Source: [source-screen request](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rtirycccisk/).

Version 1.3 notes claim direct play and a cinematic video start. Test this path before another redesign task.

### 57. Home content, content order, and Settings access

Status: Requests with partial release claims. Priority: 13.

Physical_Search7847 requests films and shows on Home, separate Settings access, and an optional films-before-shows order.

Sources: [Home request](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rufxd1pvqqt/), [follow-up](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rug1l3s4468/).

Version 1.3 notes claim Featured on Home, default-tab choice, and hidden rows. Those claims do not establish separate Settings access or content order.

Action: Test existing controls. Retain the remaining requests separately.

### 58. Rating limit for child profiles

Status: Request. Priority: 13, with item 34.

Physical_Search7847 requests a child mode with a content-rating limit, such as PG-13 or 12A.

Source: [rating-limit request](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rvhpmr3eckj/).

Content-rating badges do not satisfy a restriction request. Define what happens when a title has no rating.

### 59. Updates that parents can complete without help

Status: Repeated request; more than an update-notification request. Priority: 10.

TechFuz requests automatic updates for parents. After the author describes the version 1.3 update marker, the user still asks for an easier process.

JustSentYourMomHome also asks how updates work. The author promises an automatic-update option.

Sources: [automatic-update request](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rvi71huhhr9/), [remaining difficulty](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rwc7cirfgv7/), [update-method question](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rzh4heeo55w/).

A notification dot does not complete this request. Test the complete update path with the intended user.

### 60. Playback speed and subtitles for foreign audio

Status: Two requests; subtitle behavior has a release claim. Priority: 7 for subtitles; 13 for speed.

Physical_Search7847 asks for 2x speed and automatic subtitles when the audio language differs from the default.

Source: [speed and subtitle request](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rugx3fw5esk/).

Version 1.3 notes claim **Foreign audio only**. The reviewed release notes do not establish a 2x speed control.

Action: Test the subtitle mode. Check the current speed controls before a new implementation task.

### 61. SIMKL and direct history-service support

Status: Requests. Priority: 12 after Trakt scope, or a separate service decision.

Rubicon_83 states that absent SIMKL support prevents adoption. elli_mar83 requests direct history-service support rather than only Stremio add-ons.

Sources: [SIMKL request](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rvm09l29hcy/), [direct service request](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19ru38o18tag2/), [clarification](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19ru3cdb3a80x/).

Do not mark SIMKL complete when Trakt ships. They are separate services.

### 62. System keyboard and Arabic input

Status: Request. Priority: 9.

Gamezawy requests the normal system keyboard, other input languages, Arabic input, and a choice of keyboard.

Sources: [keyboard request](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19ruzau8bvr51/), [Arabic and keyboard choice](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rv61hc6czs1/).

QWERTY keys do not provide Arabic input or the system keyboard. Keep this task distinct from item 28.

### 63. App language independent of the TV language

Status: Request with a release claim. Priority: 9 if a gap remains.

HatAffectionate3481 requests an explicit app-language choice. Version 1.3 notes list **Settings → Language → App language**.

Source: [app-language request](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rv4b9ds9ijn/).

Action: Test a language override and a return to the TV language. Avoid another language-selector task if the control works.

### 64. French releases and German titles

Status: Language-filter questions; not established playback faults. Priority: 7 for verification, 10 for help.

Gizmofou describes difficulty with French releases in other apps and asks whether Alder solves it. vipera-77190 also asks about French audio.

Curlgod52 asks for German-only titles. The comment does not clearly distinguish translated metadata from content with German audio.

Sources: [French releases](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rtir6iqb9cw/), [French audio](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rvpxxoen7z6/), [German titles](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rxcp7gpek2r/).

Version 1.2 notes claim a source-language filter. Test that feature, then clarify the unresolved metadata-language request.

### 65. Quality controls and the next episode

Status: Feature-discovery questions. Priority: 10.

Indervir007 asks for quality, release-type, and file-size limits. The author points to existing settings.

Crusader-NZ- asks whether the next episode retains the previous quality. The author states that Alder already does this.

Sources: [source controls](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rrzsfwd1zc0/), [next-episode quality](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rs2mj5710yr/).

Action: Explain the controls and test the claims. No reproduced failure appears in these questions.

### 66. Manual trailer button

Status: Addressed by a release claim and positive user follow-up. Priority: Test only.

georgie_torrance requests a trailer button. The author states that it is implemented. The user later praises the update.

Sources: [request](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rs31d8ky2p0/), [author status](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rwtjw5klrfm/), [user follow-up](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rxiv98k8fts/).

Version 1.3 notes also claim a trailer button. This differs from automatic trailers in item 27.

### 67. Jellyfin, LAN, and guest use

Status: Separate source and account requests. Priority: 14.

Gamezawy asks for Jellyfin and LAN playback. anon1880 asks to use Alder without a Stremio account.

Sources: [Jellyfin and LAN](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rukzaeoopb9/), [guest use](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rs14l81jlt1/).

The author says guest use is unsupported. Do not treat these requests as faults in Stremio account sync.

### 68. Other platform and input requests

Status: Product expansion requests. Priority: 15.

| Request | User and source | Assessment |
|---|---|---|
| Phone catalog, mouse, keyboard, and Samsung DeX | [Status_Western2781](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rskt76mt0kk/) | Separate phone and input work from TV remote fixes. |
| Phone version | [noypi75](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rtqdnjym61w/) | Repeats the mobile request. |
| iOS | [Natejr3](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19ruocj8ksa3k/) | Separate platform project. |
| Apple TV or tvOS | [bookamp](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rt3gekd4ftu/), [Ihadtosubscribe](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19s2nf0sn9nhe/) | Supports the request in item 37. |
| Desktop | [Capable_Meal4673](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rykw2xk1noh/) | The author states that desktop is unsupported. |
| LG TV | [damshipscrew](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rsa1lnrujoh/) | Confirm the TV operating system before scope estimates. |
| Samsung Tizen | [4b3j4ndr0](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rtr4jmgyo0z/) | Separate platform project. |
| Android 7 | [parmesanocheese](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rsovagmhnaa/) | A newer support claim exists. Test with item 4. |

### 69. HDR, Dolby Vision, and more than one TV

Status: Capability questions. Priority: Verify with the related core tests.

nokia7110 asks about HDR capability filters. The author states that HDR-to-SDR filter behavior needs verification.

Krethaloshanan asks about Dolby Vision. Another user reports that Alder corrects discolored Dolby Vision video.

phaTTjohn asks whether profiles can sync between TVs. The author points to Stremio sync.

Sources: [HDR question](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19s09dgqt5g6a/), [author test gap](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19s09febv2lus/), [Dolby Vision question](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rsauaisdpgk/), [positive result](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rs26pm5yq78/), [multi-TV question](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rwn5i9woy03/).

These comments do not establish three new faults. HDR filter behavior remains an explicit test gap.

### 70. Trust, product comparisons, and an ambiguous service question

Status: Product feedback. No independent software-fault priority.

Some users question the value compared with Nuvio, Debrid Stream, and Ventic. Others criticize AI use during development and replies.

Sources: [Nuvio fallback comparison](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rsdl68mwx7m/), [Debrid Stream comparison](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rsdyebr7w9u/), [Ventic question](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rtklindkpb6/), [AI criticism](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rry1bk2cnxg/), [reply criticism](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rssaso6zztt/).

Inner_Explanation313 asks about a history or tracking service. The author answers about telemetry, and the user declines the app.

Source: [ambiguous question](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rs0ihmgk3dx/).

Action: Clarify history-service support and privacy separately. Use accurate product comparisons and test evidence to answer trust concerns.

These comments do not establish malware, insecure code, or a specific missing service beyond the explicit requests above.

## Items excluded from the fault count

Praise, test offers, the app-name question, and promotion of other apps do not establish faults.

Failures in Wuplay, Nuvio, Penguplay, or Stremio do not establish the same failure in Alder.

The lossless-audio discussion also mentions high-bitrate Dolby Vision stutter in other players. This report does not attribute all those symptoms to Alder.

## Release claims that need careful use

The local [changelog](../../CHANGELOG.md) claims these changes:

| Release | Relevant claim | Effect on this report |
|---|---|---|
| 1.1 | Initial Continue Watching, per-profile add-ons, and Audio & subtitles fixes | Test older reports again. |
| 1.2 | HTTP add-ons, empty metadata pages, source help, seek, and language fixes | Do not assume a later report has the same cause. |
| 1.3 | Android 6.0, Fire TV parse fix, QWERTY, subtitle controls, watched-state actions, and Home controls | Verify affected cases before closure. |
| 1.3.1 | APK file name | Do not attribute extra playback fixes to this version. |
| Unreleased | Profile, watched-state, source-order, and account-boundary changes | Do not tell users that these changes are released. |

The README still states Android TV 8.0+. The version 1.3 notes and build configuration state Android 6.0 support.

This difference can confuse compatibility reports. Correct the support statement when the supported device tests establish the result.

## First work sequence

1. Reproduce Fire TV install and launch failures on the exact reported devices.
2. Test the Formuler receiver case with known audio formats.
3. Test blocked episode pages and missing catalog metadata.
4. Test error-video rejection, add-on prompts, and automatic source order.
5. Test watched-state sync and the next episode after a manual watched change.
6. Test saved audio and subtitle preferences across episodes.
7. Improve text size, poster size, catalog filters, and remote focus.
8. Publish concise setup, account, update, and source-help steps.
9. Add external-player support and complete the agreed Trakt scope.
10. Assess larger service and platform requests after the core tests pass.

For each fault, record the APK version, device, OS, source add-on, exact steps, expected result, and actual result.

Use test media with known formats. Keep credentials and account tokens out of reports.

No TV or device test forms part of this review. Source code and release notes provide context only.
