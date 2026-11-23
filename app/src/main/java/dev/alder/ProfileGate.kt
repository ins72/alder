package dev.alder

/**
 * Decides whether a new profile may be added, based on the signed-in account's plan. A profile is a person who uses
 * the subscription, so the number of profiles is capped by the plan's seats; a free account gets one.
 *
 * - Paid plan: allowed while the device has fewer profiles than the plan's seats (the household's size).
 * - Free account, plans on sale: only the first profile; a second is refused with the upgrade prompt.
 * - Free account, no plans on sale (paywall off) or offline/unknown: allowed, so the app is never stuck.
 *
 * Best-effort guidance only; the server is the real authority (invites enforce seats).
 */
object ProfileGate {

    sealed class Decision {
        object Allow : Decision()
        /** Free, at the one-profile limit, and a plan is purchasable — show the upgrade prompt. */
        data class Upgrade(val status: Stremio.BillingStatus) : Decision()
        /** At the plan's seat limit — the household is full. */
        data class Full(val seats: Int) : Decision()
    }

    /**
     * Whether one more profile is allowed with [currentCount] already present.
     * [status] is the active account's billing (null = unknown/offline → allow).
     * [seats] is the active plan's seat count when known (paid owner).
     */
    fun check(currentCount: Int, status: Stremio.BillingStatus?, seats: Int?): Decision {
        if (status == null || !status.paywallEnabled) return Decision.Allow // no plans on sale / offline: never block
        if (status.active) {
            val cap = seats ?: return Decision.Allow
            return if (currentCount < cap) Decision.Allow else Decision.Full(cap)
        }
        // Free account with plans on sale: one profile only.
        return if (currentCount < 1) Decision.Allow else Decision.Upgrade(status)
    }

    /**
     * How many seats [used] is over the plan's [seats] (both owner-inclusive); 0 when within the limit or [seats] is
     * unknown (offline). Drives the blocking "over your limit, remove someone" flow after a downgrade or a household
     * that grew past its current plan.
     */
    fun overLimit(used: Int, seats: Int?): Int {
        val cap = seats ?: return 0
        return (used - cap).coerceAtLeast(0)
    }
}