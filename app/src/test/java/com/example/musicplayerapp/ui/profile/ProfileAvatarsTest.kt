package com.example.musicplayerapp.ui.profile

import com.example.musicplayerapp.R
import com.example.musicplayerapp.data.supabase.SupabaseEmailAuthApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The avatar set, what a stored key means, and what `Сохранить` does with a choice.
 *
 * The keys are the account's data, so they are asserted literally: renaming one would
 * silently turn somebody's saved avatar back into their initial.
 */
class ProfileAvatarsTest {

    // ==================== the set ====================

    @Test
    fun `24 avatars, keyed myata-01 to myata-24 in grid reading order`() {
        assertEquals((1..24).map { "myata-%02d".format(it) }, ProfileAvatars.all.map { it.key })
        assertEquals((1..24).toList(), ProfileAvatars.all.map { it.number })
    }

    @Test
    fun `each key maps to the drawable exported from its own cell`() {
        val expected = listOf(
            R.drawable.avatar_myata_01, R.drawable.avatar_myata_02, R.drawable.avatar_myata_03,
            R.drawable.avatar_myata_04, R.drawable.avatar_myata_05, R.drawable.avatar_myata_06,
            R.drawable.avatar_myata_07, R.drawable.avatar_myata_08, R.drawable.avatar_myata_09,
            R.drawable.avatar_myata_10, R.drawable.avatar_myata_11, R.drawable.avatar_myata_12,
            R.drawable.avatar_myata_13, R.drawable.avatar_myata_14, R.drawable.avatar_myata_15,
            R.drawable.avatar_myata_16, R.drawable.avatar_myata_17, R.drawable.avatar_myata_18,
            R.drawable.avatar_myata_19, R.drawable.avatar_myata_20, R.drawable.avatar_myata_21,
            R.drawable.avatar_myata_22, R.drawable.avatar_myata_23, R.drawable.avatar_myata_24,
        )
        assertEquals(expected, ProfileAvatars.all.map { it.drawable })
        assertEquals(24, ProfileAvatars.all.map { it.drawable }.toSet().size)
    }

    @Test
    fun `the metadata key is avatar_id, beside display_name`() {
        assertEquals("avatar_id", SupabaseEmailAuthApi.AVATAR_ID)
    }

    // ==================== resolving a stored key ====================

    @Test
    fun `a known key resolves to its avatar`() {
        assertEquals(R.drawable.avatar_myata_06, ProfileAvatars.resolve("myata-06")?.drawable)
        assertEquals("myata-16", ProfileAvatars.resolve(" myata-16 ")?.key)
        assertEquals(R.drawable.avatar_myata_24, ProfileAvatars.resolve("myata-24")?.drawable)
    }

    @Test
    fun `no key, a blank key and an unknown key are all no avatar`() {
        assertNull(ProfileAvatars.resolve(null))
        assertNull(ProfileAvatars.resolve(""))
        assertNull(ProfileAvatars.resolve("   "))
        assertNull(ProfileAvatars.resolve("myata-25"))
        assertNull(ProfileAvatars.resolve("myata-00"))
        assertNull(ProfileAvatars.resolve("myata-6"))
        assertNull(ProfileAvatars.resolve("6"))
        assertNull(ProfileAvatars.resolve("MYATA-06"))
        assertNull(ProfileAvatars.resolve("https://example.com/a.png"))
    }

    @Test
    fun `the pre-rename m3 keys are not avatar ids`() {
        // Renamed before any account stored one, so there is deliberately no mapping:
        // an m3 key anywhere is just an unknown key, which means no avatar.
        for (n in 1..24) assertNull(ProfileAvatars.resolve("m3-%02d".format(n)))
    }

    // ==================== the default ====================

    @Test
    fun `an account with no avatar opens the picker with nothing ringed`() {
        // Not myata-06: the frame's ringed cell is an example state, not a default.
        assertNull(AvatarSelection.initial(savedOnAccount = null, restored = null))
    }

    @Test
    fun `an account with an unknown key opens the picker with nothing ringed`() {
        assertNull(AvatarSelection.initial(savedOnAccount = "myata-99", restored = null))
    }

    @Test
    fun `the picker opens on the account's avatar`() {
        assertEquals("myata-03", AvatarSelection.initial(savedOnAccount = "myata-03", restored = null))
    }

    @Test
    fun `a choice restored after process death wins over the account's, if it is real`() {
        assertEquals("myata-11", AvatarSelection.initial(savedOnAccount = "myata-03", restored = "myata-11"))
        assertEquals("myata-03", AvatarSelection.initial(savedOnAccount = "myata-03", restored = "bogus"))
    }

    // ==================== what Save writes ====================

    @Test
    fun `nothing chosen writes nothing`() {
        assertFalse(AvatarSelection.needsWrite(savedOnAccount = null, selected = null))
        assertFalse(AvatarSelection.needsWrite(savedOnAccount = "myata-02", selected = null))
    }

    @Test
    fun `choosing the avatar the account already has writes nothing`() {
        assertFalse(AvatarSelection.needsWrite(savedOnAccount = "myata-02", selected = "myata-02"))
    }

    @Test
    fun `a different avatar is a write`() {
        assertTrue(AvatarSelection.needsWrite(savedOnAccount = null, selected = "myata-01"))
        assertTrue(AvatarSelection.needsWrite(savedOnAccount = "myata-02", selected = "myata-01"))
    }

    @Test
    fun `any real avatar over an unknown stored key is a write`() {
        assertTrue(AvatarSelection.needsWrite(savedOnAccount = "myata-99", selected = "myata-01"))
    }

    @Test
    fun `an unknown selection is never written`() {
        assertFalse(AvatarSelection.needsWrite(savedOnAccount = null, selected = "myata-99"))
    }

    // ==================== grid geometry ====================

    @Test
    fun `at the frame's 358 content width the cells are 76 with 18 gutters`() {
        assertEquals(76f, AvatarGridLayout.cellSize(width = 358f, preferred = 76f, gutter = 18f), 0f)
    }

    @Test
    fun `the frame's own width at a fractional density still fits the frame's cell`() {
        // 2.625x: content 940px, cell 199.5px, gutter 47.25px - an exact fit that whole-pixel
        // rounding used to turn into a 1px overshoot and a 199px cell.
        assertEquals(199.5f, AvatarGridLayout.cellSize(width = 940f, preferred = 199.5f, gutter = 47.25f), 0f)
        assertEquals(199.5f, AvatarGridLayout.cellSize(width = 939.2f, preferred = 199.5f, gutter = 47.25f), 0f)
    }

    @Test
    fun `wider keeps 76 and narrower shrinks the cells so four still fit`() {
        assertEquals(76f, AvatarGridLayout.cellSize(width = 380f, preferred = 76f, gutter = 18f), 0f)
        assertEquals(68.5f, AvatarGridLayout.cellSize(width = 328f, preferred = 76f, gutter = 18f), 0f)
        assertEquals(0f, AvatarGridLayout.cellSize(width = 10f, preferred = 76f, gutter = 18f), 0f)
    }
}
