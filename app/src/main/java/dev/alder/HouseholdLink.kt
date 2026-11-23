package dev.alder

/**
 * Links a freshly signed-in profile into the plan owner's household. A profile is a person with their own account;
 * when that account's email is invited to the owner's family plan, the member can use the owner's subscription. This
 * makes "add a profile and sign in" also "add this person to the group" without a separate step.
 *
 * Best-effort and silent: no active plan on the owner, no free seat, the email already invited, or no network all
 * leave the profile created and the household untouched. Call off the main thread.
 */
object HouseholdLink {

    /**
     * Invite [newKey]'s account email into [ownerKey]'s plan, under [name]/[color]. Returns true when an invite was
     * recorded (new or already present), false otherwise. Never throws.
     */
    fun linkMember(ownerKey: String, name: String, color: Int, newKey: String): Boolean = runCatching {
        val email = Stremio.email(newKey).trim()
        if (email.isEmpty()) return false
        // Only invite when the owner actually manages a shareable plan with a seat free.
        val house = Stremio.household(ownerKey) ?: return false
        if (!house.active || !house.canManage) return false
        if (house.seatsLeft <= 0) return false
        val result = Stremio.inviteCreate(ownerKey, email, name = name, color = color)
        // already_invited means the person is already in the group — still a success for linking.
        result.ok || result.error == "already_invited"
    }.getOrDefault(false)
}