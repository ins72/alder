# Alder — Household & Profile sync (full technical documentation)

This document covers everything household- and profile-related: the on-device profile
store, multi-device profile sync, household (family-plan) membership and seats, how the
household is materialised as profile cards, the management UI, and the server side that
backs it. It is the companion to `FULL_DOCUMENTATION.md`.

---

## 1. Overview

A "profile" is a person who uses the subscription on a device (name, avatar colour, optional
PIN, and a Stremio sign-in). A "household" is the family plan: one **owner** who pays, plus
**members** who were invited and joined, plus **pending invites**. The plan has a **seat**
count; the number of profiles that can be active is capped by it.

Two independent sync systems cooperate:

- **Profile sync** — the *list of profiles* (and which one is active) follows the person from
  device to device and survives a reinstall, under the signed-in account.
- **Household sync** (server-authoritative) — who is in the family, their roles, seats and the
  plan. The client reads it and calls invite/role/remove endpoints; the server enforces seats.

A third piece, **household materialisation**, turns the *other* household members into profile
cards on this device so the whole family is switchable on a shared TV.

---

## 2. Data model

### 2.1 `Profile` (client, `Profiles.kt`)

```kotlin
data class Profile(
    val id: String,          // "1".."5" for real profiles, "h<hex>" for materialised household cards
    val name: String,
    val color: Int,          // avatar colour (ARGB)
    val authKey: String,     // Stremio sign-in; "" for a placeholder card awaiting sign-in
    val pin: String? = null, // optional 4-digit lock
    val mod: Long = 0L,      // last-modified time; the per-profile ordering truth for merge
    val deleted: Boolean = false // tombstone: a removed profile kept so the delete syncs
)
```

- `Profiles.MAX = 5` — the on-device cap on real profiles (the "+" tile is hidden at the cap).
- `Profiles.COLORS` — the five avatar colours.
- **Real profiles** use numeric ids `1..MAX`. **Materialised household cards** use `h` + the hex
  of the member's email hash (`"h" + email.hashCode().toUInt().toString(16)`), a stable id so the
  same person is the same card on every device.

### 2.2 On-device storage (SharedPreferences `account`)

| Key | Meaning |
|---|---|
| `profiles` | JSON array of `Profile` rows (including tombstones), each with `id,name,color,authKey,pin,mod` and optional `deleted`. |
| `active` | id of the selected profile (or absent). |
| `profilesRev` | the server's whole-list revision last folded in (ordering truth across devices). |
| `signedOut:<id>` | this profile's session expired / was signed out elsewhere; show "sign in again". |
| `shareStreams:<id>` | this profile lends its stream add-ons to the others. |

`Profiles` API: `all()` (visible, tombstones filtered), `entries()` (tombstones included — the
merge/prune work on these), `add`, `signIn`, `update`, `remove` (writes a tombstone),
`placeholder` (empty-authKey card), `setActive`, `save`/`replaceAll`/`writeRaw`.

### 2.3 `Household` / `Member` (server, mapped in `Stremio.kt`)

- `Household`: `active`, `planId`/`planName`, `owner` (Member), `members` (list), `seats`,
  `used`, `sharedBy` (for a plain member), `isOwner`, `isManager`, `canManage`, `isMember`.
- `Member`: `email`, `name?`, `color?`, `role` (owner/manager/member), `joined`, `expired`,
  `isManager`, `confirmed`.

---

## 3. Profile sync (multi-device)

### 3.1 The problem

The profile list must (a) follow the user to a new device / after a reinstall, and (b) converge
when two devices edit it offline. A naïve "whole list, last writer wins" loses edits; Alder does
a **per-profile 3-way merge by `mod`**, the same technique as settings sync.

### 3.2 `ProfileSync` (`Profiles.kt`)

- **Which account stores the list** — `activeAuthKey(prefs)` = the first profile with a non-empty
  authKey (the primary/owner account). It is deliberately **not** the active profile: the list is
  the family's, so it must sync under the same account on every device regardless of which profile
  is currently selected, otherwise devices with different active profiles would write to different
  accounts and never converge.
- **`changed(prefs)`** — after any local mutation (`save` calls it): enqueue `sync` on a
  single-thread executor.
- **`pull(context)`** — at app start: run `sync`, return whether the visible list changed (so the
  screen can re-render).
- **`sync(prefs)`**:
  1. `profilesGet(key)` — read the server list (offline → keep local).
  2. `merge(local, server)` — combine by id; the row with the higher `mod` wins each id.
  3. `writeRaw` the merged list (does **not** re-push — the sync owns pushing).
  4. Choose active: keep the current `active` if still live, else the server's, else the first live.
  5. If the merged list differs from what the server had, `profilesSet` it (so this device's newer
     edits propagate) and store the returned `rev`.
- **`merge(local, server)`** — `LinkedHashMap` keyed by id; for each server row, replace the local
  one iff `newer(server, local)`.
- **`newer(p, e)`** — higher `mod` wins; on equal `mod` a live row beats a tombstone; then a stable
  content key (`authKey|name|pin`) breaks ties deterministically so every device makes the same
  choice.
- **`signature(list)`** — a comparable snapshot of the *visible* profiles, used by `pull` to tell
  if the screen changed.

### 3.3 Tombstones

`Profiles.remove(id)` does **not** delete the row; it copies it to `deleted = true` with
`mod = now`. The tombstone is written and pushed, so the removal reaches the person's other
devices, where the merge applies it. A tombstone keeps its id reserved (a removed id is never
reused). Re-adding the same person is a *new* profile / a re-sign-in, not a resurrection of the id.

### 3.4 Active selection

`active` is synced but always points to a **live** id; `sync` repairs a stale/removed active to the
server's or the first live profile. (Note: because `sync` keeps the current active if it is live,
a device that was left on profile A stays on A until A is removed or the user switches.)

---

## 4. Household materialisation (`ProfileSync.materializeHousehold`)

When any member signs in, every **other** joined member of the shared plan becomes a profile card
on this device, so the whole family is switchable on a shared TV. Members whose sign-in this device
does not hold appear as **placeholder** cards (empty authKey) that ask "sign in again" and fill the
auth key on first use.

Algorithm (`materializeHousehold(context, key)`):

1. `household(key)` — read the family (offline/!active → no-op).
2. `selfEmail` = the signed-in account's email (resolved with `Stremio.email`).
3. **`covered`** = emails of this device's real (non-`h`) profiles that have an authKey. *(This is
   the fix for the duplicate-owner-card bug — see §8.3.)*
4. `people` = owner + joined members, **excluding** anyone whose email equals `selfEmail` or is in
   `covered` (already represented by a real profile here).
5. For each person: add an `h<hex(email)>` card if absent; **heal** a placeholder that is tombstoned
   or has a blank/`"null"` name; never touch a real signed-in card (non-empty authKey).
6. **Prune**: tombstone `h`-cards with an empty authKey that are no longer in `people` (left/removed),
   so a delete syncs.
7. `replaceAll` if anything changed.

The id is stable across devices (`h` + email hash), so "the card for Alice" is the same row
everywhere and merges cleanly.

---

## 5. Seats & gating (`ProfileGate.kt`)

A profile is a person who uses the subscription, so the count is capped by the plan's seats.

- `check(currentCount, status, seats)` → `Allow` / `Upgrade(status)` / `Full(seats)`:
  - no `status` or paywall off → `Allow` (never block; offline is permissive).
  - active paid plan → `Allow` while `currentCount < seats`, else `Full`.
  - free account with plans on sale → one profile only, else `Upgrade`.
- `overLimit(used, seats)` → how many seats `used` is over `seats` (owner-inclusive); drives the
  blocking "over your limit — remove someone" flow after a downgrade or a household that outgrew
  its plan.

`ProfilesActivity.gateAdd { … }` runs the check in the background (billing + household seats) and
then: `Upgrade` → the paywall dialog; `Full` → the "household full" message; else run the supplied
continuation. **Both** the "+" tile (`addProfile`) and the household **Add member** (`startAddMember`)
go through it, so adding a profile is capped identically from either entry point.

---

## 6. Household membership, roles & seats

### 6.1 Server endpoints (client → `Stremio.kt` → server)

| Client call | Route | Purpose |
|---|---|---|
| `household(key)` | `billingHousehold` | read the caller's household (owner view, or member view with `sharedBy` + `canManage:false`). |
| `inviteCreate(key,email,role?,name?,color?)` | `billingInviteCreate` | invite a new member (owner/manager). |
| `inviteUpdate(key,email,name?,color?)` | `billingInviteUpdate` | edit an invitee's name/colour. |
| `inviteRole(key,email,role)` | `billingInviteRole` | change member↔manager (owner only). |
| `inviteResend(key,email)` | `billingInviteResend` | renew a pending invite. |
| `inviteTransfer(key,email)` | `billingInviteTransfer` | hand the plan to a member (owner only). |
| `inviteRemove(key,email)` | `billingInviteRemove` | remove a member / revoke an invite. |
| `leavePlan(key)` | `billingLeave` | a member leaves the plan. |
| `billingCheckout(key,planId?)` | `billingCheckout` | create a checkout session linked to the account; returns a URL. |
| `billingStatus(key)` | `billingStatus` | paywall state, active, plans, `webUrl`. |

Roles: **owner** (pays, full control), **manager** (can invite/edit/remove, not billing/transfer),
**member** (can view, leave). `canManage` = owner or manager.

### 6.2 The household-shared profile store (server)

A member's device must see and edit the **owner's** profile list (it's the family's). The server
wires this via `accounts.setHouseholdResolver(fn)`:

- `billing.householdOwnerId(user)` returns the owner's account id for anyone in the household
  (the owner maps to itself).
- `accounts.profileStoreUser(user)` resolves the *store* a profiles read/write targets: the owner's
  record when the caller is in a household, else the caller's own.
- `http_server.js` connects them:
  `accounts.setHouseholdResolver((u) => billing.householdOwnerId(u))`.

So `profilesGet`/`profilesSet` from a member read and write the owner's shared list.

### 6.3 Server `profilesGet` / `profilesSet` (`accounts.js`)

- Body: `{ authKey, list?, active? }`; `list` items `{ id, name, color, authKey, pin, mod, deleted? }`.
- `profilesSet`: `list.length > 16` → `too_large`; per row `id` sliced to **24** chars, `pin` sliced
  to **8**; tombstones need no authKey; the active is chosen only among **live** ids; live count
  capped at 8. Returns a new `rev`.
- `profilesGet`: returns `{ list, active, rev }` from the resolved (household-shared) store.
- Storage columns/migration live in `accountsStore.js` (`app_settings`, `profiles`).

---

## 7. The management UI (`ProfilesActivity.kt`)

### 7.1 Picker & per-profile menu

- The picker shows every visible profile as a tile (`tile()`), the active one selected, plus a "+"
  tile (hidden at `Profiles.MAX`). Long-press a tile → `showMenu(p)`.
- `showMenu(p, familyBack?)` — rename, set/change PIN, colour, remove (and remove-PIN). **Colour and
  remove-PIN** apply then rebuild the picker (`showPicker()`) so the avatar updates immediately, and
  re-render the menu (`again()`) so it stays open. When opened with a `familyBack` it renders inside
  the household dialog and shows a trailing **Back** row (see §7.2).
- `withPin(p) { … }` gates locked profiles behind their PIN.

### 7.2 The household hub — one persistent dialog

The household manager is a **single persistent dialog** (`familyDialog` + `familyOpen`), not a stack
of popups. `familyRender(title, items, message?, …, pick)` creates the dialog on first use and then
**swaps its content in place** on every call (`renderChoose(dialog, …, dismissOnPick=false,
onClose={ closeFamily() })` in `Screen.kt`). Consequences:

- Navigating hub → billing → members → member-actions → back never closes the modal.
- Async member actions (role/resend/transfer/remove) keep the modal visible and re-render on
  completion.
- Errors set an inline `familyNotice` and re-render the list in place (the reason shows as the hint).
- The modal closes only when the user **leaves** the household view (Add member, editing a profile,
  Leave plan) or **closes** it (top X, tap outside, or the root **Close** row).

Views: `showHub` (root: Billing, Members & roles, Profiles, Add member, Leave, **Close**),
`showBilling` (plan/status/seats/plans + **Pay** that calls `billingCheckout` and opens the URL),
`showHousehold` (owner + members list; **read-only for non-managers**), `householdMemberActions`
(rename/role/resend/transfer/remove/back), `showProfilesList` (profiles; tap → `showMenu` with Back),
`showMemberHousehold` (plain member: who shared + Leave).

Root row reads **Close** (`family_close`); sub-views read **Back** (`family_back`).

### 7.3 The three removal paths — all permanent & synced

Every way of removing a profile converges on the same permanent, synced removal:

- **Avatar long-press → Remove** and **Profiles list → tap → Remove** both call
  `confirmRemove(p)` → `removeLocally(p)` + free the household seat.
- **Household members → Remove** (`householdRemove`) frees the seat, then finds *every* local profile
  that **is** that member — the materialised `h`-card **and** a real profile signed in under the same
  email — and runs `removeLocally` on each.
- `removeLocally(p)`: `Profiles.remove` (tombstone → syncs), clear active if it was active, forget
  caches/progress, and `logout` the account if no other local profile uses it.

### 7.4 Blocking a duplicate add

`signedIn(name, key, again)` (a brand-new sign-in) first checks the household: if the email is
already an owner/joined/pending member, it shows "`<email>` is already in this household"
(`family_email_in_household`) and returns to the picker instead of creating a duplicate. Offline /
unknown → allow (best-effort). Otherwise `completeSignIn` adds the profile, best-effort links it into
the household (`HouseholdLink.linkMember`), and materialises the family.

---

## 8. Edge cases & root causes fixed

- **`optString` returns the literal `"null"`.** Android's `JSONObject.optString(key)` returns the
  string `"null"` for a JSON null, which then propagates as a name/PIN/email. Fixed with a
  `JSONObject.optText(key)` helper (returns null for missing / JSON-null / literal `"null"` / blank),
  applied to PINs, member/owner names and emails (`Stremio.kt`).
- **Materialised-card id truncation.** Household ids are 9 chars (`h` + 8 hex); the server sliced ids
  to **8**, turning `h26bc5056` into `h26bc505` and colliding with the old `h26bc505` tombstone — two
  "tmonnens" cards that churned. Fixed by slicing ids to **24**.
- **Resurrecting the duplicate owner card.** `materializeHousehold` healed any tombstoned `h`-card
  whose owner was back in `people`; since only the *active* user's email was excluded, the owner's
  card was un-deleted on every sync while someone else was active — the "tmonnens duplicate that
  never goes away". Fixed with the `covered` set (§4): anyone with a real local profile is excluded
  from `people`, so their card is never healed.
- **Active sync.** `sync` keeps the current active if it is still live (§3.4), so a manual/test
  change to `active` reverts to the live one on next sync.

---

## 9. Strings & localisation

Household/profile strings (all 11 files — base + 10 locales `es pt fr de it hi ar nl th tr`):

- `family_*` — sharing, manage title/hint, billing, members_roles, profiles_count, add_member,
  seats, owner, joined, pending, expired, manager, member, make_manager/make_member, resend,
  transfer(+hint), remove(+hint), rename(+title), back, **close**, leave, plan/status/billing_*.
- `family_over_title` / `family_over_hint` (`%1$d`) — the over-limit blocking dialog.
- `family_email_in_household` (`%1$s`) — duplicate-add block.
- `profiles_limit_title` / `profiles_limit_desc` (`%1$d`) — the "household full" dialog.
- `profiles_*` — rename/set/change/remove PIN, colour, portrait, remove(+title/desc), your_name,
  whos_here, hold_ok, step, etc.

`TranslationStringsTest` enforces: every English key present + non-blank in all 10 locales with
matching placeholders; every string used in code; no hardcoded English in UI helpers. Add new
strings to **all 11** files; escape `&` as `&amp;` and apostrophes as `\'`.

---

## 10. Testing & verification

- **App unit tests** — `:app:testDebugUnitTest` (211 tests incl. `TranslationStringsTest`).
- **Server tests** — `node --test server/*.check.js` (150 tests), incl. `accounts.check.js`
  (profiles mod/tombstone round-trip, active-points-to-live) and `billing-link.check.js`
  (household-shared profile store: a member reads/writes the owner's list).
- **Live sync properties** (verified against a running dev server):
  - owner and member `profilesGet` return the **identical** list (household resolver).
  - a member `profilesSet` writes the shared list.
  - `appSettings` round-trips and restores.
  - `billingHousehold`: owner sees owner+members+seats; member resolves to the same household with
    `canManage:false`.
  - cross-device (throwaway account): rename, tombstone and the exact newer `mod` propagate; list
    size stays correct; active always points to a live profile.
  - real devices converge to the same list; a stale device resolves a live card to the server's
    newer tombstone on next pull.

---

## 11. Dev environment & test accounts

- Build: `$env:JAVA_HOME="C:\Program Files\Zulu\zulu-21"; .\gradlew.bat :app:testDebugUnitTest
  :app:assembleDebug` (workdir `Alder-main`).
- Install: `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`.
- Read prefs: `adb -s <serial> shell "run-as dev.alder cat
  /data/data/dev.alder/shared_prefs/account.xml"`.
- Launch: `adb -s <serial> shell am force-stop dev.alder` then `... am start -n
  dev.alder/.ProfilesActivity` (force-stop first so `onCreate` re-runs).
- Devices: `RFCR401MNSA` (phone), `emulator-5554` (MuMu, hevc=true av1=false), `emulator-5556`
  (AOSP TV).
- Dev account service: `http://192.168.1.100:8080` (`/api/...`, operator panel `/operator`).
  **Do not touch `127.0.0.1:8080`** — that is the user's production server. Dev launcher:
  `C:\Users\tmonn\AppData\Local\Temp\kilo\audit-server-launch.ps1` (sets `PORT=8080`,
  `PUBLIC_URL=http://192.168.1.100:8080`, `PAYWALL_ENABLED=false`, `OWN_ACCOUNTS=true`,
  `STRIPE_MOCK_URL=http://127.0.0.1:12111`, etc.). Restart it after server code changes.
- Owner test account: `tmonnens@outlook.com`, plan `family`, seats 2 (authKey rotates; read the
  current one from the device prefs or `profilesGet`).
- Call any route directly: `POST http://192.168.1.100:8080/api/<route>` with JSON
  `{ authKey, ... }`.