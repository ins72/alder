# Alder's next five features

11 November 2026

The ranking favors work that makes Alder easier to use from the sofa, has direct user demand, or builds on data Alder already reads. It uses the checked-out code, the locally available `origin/main` history through PR #112, public issues and PRs, the November 3 feedback review, and first-party product and developer documentation. The ordering is a product judgment. Device behavior and adoption are unmeasured.

Recent changes already cover larger text and posters, external players, new catalog types, better add-on subtitle matching, and changing sources without losing the current playback position. The November 3 feedback report predates some of those changes. Use the changelog and PR history to reconcile those requests before adding them to the roadmap. [Changelog](../../CHANGELOG.md), [November 3 feedback review](2026-11-03-reddit-feedback.md), [Alder pull requests](https://github.com/aradhyas8/Alder/pulls)

## 1. Discover and pinned catalogs

Add a Discover grid where viewers can choose an installed catalog and use the filters it advertises. Let a profile pin a favorite catalog to Home. Start with one path from catalog selection through genre filtering, pagination, and playback. A search-only catalog can stay available through Search.

Alder already groups catalogs by media type and lets viewers hide rows. Issue #90 asks to browse a particular add-on catalog or surface it on Home. The launch feedback also asks for grid layouts and more control over Home. The Stremio Android TV app now has a Discover grid, and its SDK defines catalog filters and pagination. This has the strongest direct evidence of the five and gives users something to do when they open Alder without a title in mind. [Alder #90](https://github.com/aradhyas8/Alder/issues/90), [Launch feedback](2026-11-03-reddit-feedback.md), [Stremio Android TV update](https://blog.stremio.com/stremio-tech-update-87-stremio-android-tv-update/), [Stremio catalog contract](https://stremio.github.io/stremio-addon-sdk/api/requests/defineCatalogHandler.html)

## 2. Voice and multilingual search

Offer the system keyboard as a first-class option in Search so users can type in supported input languages and dictate where the device supports it. Keep the remote-friendly QWERTY keyboard available. Use the existing add-on search for both input methods. Check Arabic text entry and right-to-left input, and verify the fallback on a Fire TV without dictation.

The mic in Alder's current Search screen is disabled. A launch user explicitly asked for the system keyboard and Arabic input. Google documents speech-to-text as part of Android TV's Gboard, which provides one route on supported devices. [Alder Search code](../../app/src/main/java/dev/alder/SearchActivity.kt), [Keyboard request](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rv61hc6czs1/), [Android TV keyboard documentation](https://developer.android.com/training/tv/get-started/onscreen-keyboard)

## 3. New & Upcoming

Add a weekly agenda for saved shows, with recently aired episodes and upcoming air dates. Open the exact episode from the agenda and respect the profile's spoiler preferences. Label air dates as schedule data. Playback still depends on whether an add-on has a stream.

Alder already has new-episode tags and episode air dates. A single view makes them useful for viewers who follow multiple shows. Stremio now offers an Android TV calendar and uses saved series for its episode tracking. [Alder changelog](../../CHANGELOG.md), [Stremio Android TV update](https://blog.stremio.com/stremio-tech-update-87-stremio-android-tv-update/), [Stremio episode tracking](https://blog.stremio.com/how-to-use-episode-notifications-in-stremio/)

## 4. Kids profiles

Add a maturity limit managed by an adult PIN. Hide restricted titles and enforce the same rule before playback, including entry from Search, Android TV Watch Next, and external-player handoff. For the first version, exclude unrated or unrecognized content unless an adult explicitly allows the title. Require the adult PIN to exit Kids mode or change its restrictions.

Alder already has PINs and title rating badges. A user specifically asked for a PG-13 or 12A-style restriction. Netflix provides a precedent for a simpler profile with maturity limits and restricted settings. Metadata coverage and rating differences across countries are the main implementation constraints. [Alder changelog](../../CHANGELOG.md), [Rating-limit request](https://www.reddit.com/r/AndroidTVApps/comments/1xhjjwn/comment/19rvhpmr3eckj/), [Netflix Kids profiles](https://help.netflix.com/en/node/114275)

## 5. Live guide and favorites

Add per-profile channel favorites and a Now/Next list for add-ons that supply program schedules. Keep ordinary channel tiles playable when schedule data is absent. Try one compatible add-on and one without guide data before investing in a full time grid.

Alder already has Live channels and sports. Stremio documents a native electronic program guide contract for add-ons, and YouTube TV shows how favorites and current programs can make a guide useful. This is the most dependent on add-on coverage, so rank it behind the other four. [Alder changelog](../../CHANGELOG.md), [Stremio guide protocol](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/epg.md), [YouTube TV Live guide](https://support.google.com/youtubetv/answer/7067974?hl=en-001)

## Work already underway

The local `feat/setup-check` branch contains a first-play add-on check and a Settings entry at commit `dfd71c3`. Verify it with new users after it lands. [Setup and source reports](2026-11-03-reddit-feedback.md)

Trakt was requested and PR #42 already covers pairing, scrobbling, and watch-state sync. The PR title cites production access as the blocker, and the current Trakt docs describe a separate Production app for public rollout. Confirm Alder's actual access and test the existing integration before scheduling more Trakt work. [Trakt PR #42](https://github.com/aradhyas8/Alder/pull/42), [Trakt production access](https://developer.trakt.tv/docs/create-an-app)

Start with Discover and Search. They solve adjacent problems for the same viewers and use Alder's existing catalog and search paths. Follow with New & Upcoming once episode metadata coverage is clear.
