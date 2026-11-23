package dev.alder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Profile picker Back navigation must never bypass a profile PIN. */
class ProfilesPinTest {
    private val lockedA = Profile(id = "1", name = "A", color = 0, authKey = "key-a", pin = "1234")
    private val unlockedB = Profile(id = "2", name = "B", color = 0, authKey = "key-b", pin = null)

    @Test fun deletingOriginProfileLeavingLockedProfileReturnsNull() {
        // Removing B clears "active", leaving only locked A; no fallback to singleOrNull().
        assertNull(pickerReturnProfile(listOf(lockedA), null))
        // Even if an old origin id is still held, a removed profile no longer resolves.
        assertNull(pickerReturnProfile(listOf(lockedA), unlockedB.id))
    }

    @Test fun addingPinToOriginProfileReturnsNull() {
        val nowLockedB = Profile(id = unlockedB.id, name = unlockedB.name, color = unlockedB.color, authKey = unlockedB.authKey, pin = "1234")
        assertNull(pickerReturnProfile(listOf(lockedA, nowLockedB), unlockedB.id))
    }

    @Test fun lockedOriginProfileReturnsNull() {
        assertNull(pickerReturnProfile(listOf(lockedA, unlockedB), lockedA.id))
        assertNull(pickerReturnProfile(listOf(lockedA), lockedA.id))
    }

    @Test fun validUnlockedOriginProfileReturnsThatProfile() {
        val returned = pickerReturnProfile(listOf(lockedA, unlockedB), unlockedB.id)
        assertEquals(unlockedB.id, returned?.id)
        assertEquals(unlockedB.name, returned?.name)
    }

    @Test fun nullOriginIdOrEmptyProfilesReturnsNull() {
        assertNull(pickerReturnProfile(listOf(unlockedB), null))
        assertNull(pickerReturnProfile(emptyList(), unlockedB.id))
        assertNull(pickerReturnProfile(emptyList(), null))
    }
}
