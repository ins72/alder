# Error messages that say what to do

Branch `fix/error-messages`, on top of PR #136 (`feat/tmdb-discover`).

## Goal

Every error a viewer sees answers four questions: what failed, whose fault, why, and what to do. Today Alder
answers "what" and "why" (`failureMessage(title, failure)` → "Couldn't load this title · timed out"). This change
adds "what to do" for every failure kind, and "whose" where the screen already knows the add-on or service.

## Design

1. `Failure` (Failures.kt) gets a second string: `advice`, one line of what to do, for each kind.
2. `Context.failureMessage(title, failure, source = null, advice = true)` builds every message in one place:
   - line 1: `Title · reason`, or `Title · Source: reason` when `source` (an add-on or service name) is given;
   - line 2: the advice, when `advice` is true.
   One-line places (a toast, a status line under a spinner) pass `advice = false`.
3. The catalog card's own fix line (`catalog_addon_fix`) goes: the advice line replaces it.
4. A unit test: every `Failure` has a non-empty advice in English, and no two kinds share a string id by mistake.
   `TranslationStringsTest` already checks every locale has every string with the same placeholders.
5. Silent failures (82 `runCatching`, 87 `getOrNull()`) are audited, not changed: a report in
   `docs/research/silent-failures.md` lists each one that can hide something the viewer should know.
   Fixing those is a later step.

## Advice strings (English)

| Kind | String name | Text |
|---|---|---|
| SIGN_IN | `failure_advice_sign_in` | Sign in to Stremio again. |
| ACCESS | `failure_advice_access` | Check the key or account in this add-on's settings in Stremio. |
| TIMEOUT | `failure_advice_timeout` | Check your internet connection, then try again. |
| ADDRESS | `failure_advice_address` | Check your internet connection. If it works, the add-on may have moved: update it in Stremio. |
| CONNECTION | `failure_advice_connection` | The service may be down. Try again later. |
| SECURE | `failure_advice_secure` | Check the date and time on your TV, then try again. |
| BUSY | `failure_advice_busy` | Wait a minute, then try again. |
| SERVER | `failure_advice_server` | The service has a problem. Try again later. |
| RESPONSE | `failure_advice_response` | If this goes on, update or remove the add-on in Stremio. |
| MISSING | `failure_advice_missing` | Try another add-on or source. |
| FORMAT | `failure_advice_format` | Try another source. |
| NETWORK | `failure_advice_network` | Check your internet connection, then try again. |
| WEB_PAGE | `failure_advice_web_page` | The add-on may have moved or closed: update or remove it in Stremio. |
| OTHER | `failure_advice_other` | Try again. If this goes on, send a report from Settings › About. |

## Work split (Haiku agents, one set of files each)

| Agent | Files | Job |
|---|---|---|
| Core | `Failures.kt`, `values/strings.xml`, `MainActivity.kt` (only `catalogMessage`), `FailuresTest.kt`, `CHANGELOG.md` | Advice field, new `failureMessage`, English strings, remove `catalog_addon_fix`, test, changelog |
| Translations | `values-{es,pt,fr,de,it,hi,ar}/strings.xml` | The 14 advice strings in each language; remove `catalog_addon_fix` |
| Call sites | `AddonsActivity`, `DetailsActivity`, `PlayerActivity`, `ProfilesActivity`, `SearchActivity`, `StreamsActivity` | Pass `source` where the add-on or service is known; `advice = false` on one-line places |
| Audit | `docs/research/silent-failures.md` (new) | Read-only report on dropped errors |

No agent builds, commits or touches another agent's files. The lead reviews, builds once, tests on the TV, and opens
the PR.
