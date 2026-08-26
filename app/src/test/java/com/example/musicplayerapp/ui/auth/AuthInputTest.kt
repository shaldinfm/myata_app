package com.example.musicplayerapp.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the auth forms accept, asserted without a device.
 *
 * Every rule in [AuthInput] is a claim about a string, so this is a unit test and
 * runs in milliseconds - which matters, because the interesting cases here are the
 * addresses somebody actually has rather than the three anybody thinks of.
 */
class AuthInputTest {

    // ==================== name ====================

    @Test
    fun `a name is trimmed on its way to the account`() {
        assertEquals("Денис", AuthInput.name("  Денис  "))
    }

    @Test
    fun `whitespace is not a name`() {
        assertFalse(AuthInput.isNameValid(""))
        assertFalse(AuthInput.isNameValid("   "))
        assertFalse(AuthInput.isNameValid("\t\n"))
    }

    @Test
    fun `anything with a character in it is a name`() {
        // No length rule, no character-set rule and no uniqueness rule: this goes to
        // user_metadata.display_name, which has none of those, and inventing one here
        // would refuse somebody their own name.
        assertTrue(AuthInput.isNameValid("Денис"))
        assertTrue(AuthInput.isNameValid("D"))
        assertTrue(AuthInput.isNameValid("Anne-Marie O'Hara"))
        assertTrue(AuthInput.isNameValid("小明"))
    }

    // ==================== email ====================

    @Test
    fun `an address is trimmed but not otherwise touched`() {
        assertEquals("denis@example.com", AuthInput.email("  denis@example.com "))
        // Case is preserved. Local parts are case-sensitive by the standard, and
        // lower-casing one here would send a different address than was typed.
        assertEquals("Denis@Example.COM", AuthInput.email("Denis@Example.COM"))
    }

    @Test
    fun `ordinary addresses are accepted`() {
        val accepted = listOf(
            "denis@example.com",
            "  denis@example.com  ",
            "d@e.co",
            "denis.shaldin@mail.example.ru",
            "denis+radio@example.com",
            "denis_1985@example-mail.com",
            "DENIS@EXAMPLE.COM",
        )
        for (address in accepted) {
            assertTrue("$address should be accepted", AuthInput.isEmailValid(address))
        }
    }

    @Test
    fun `addresses no mail server would route are refused`() {
        val refused = listOf(
            "",
            "   ",
            "denis",
            "denis@",
            "@example.com",
            "denis@example",          // no dot, so no domain
            "denis@@example.com",
            "denis example@mail.com", // a space in the middle is a typo, not an address
            "denis@exa mple.com",
            "denis@.com",
            "denis@example.",
        )
        for (address in refused) {
            assertFalse("$address should be refused", AuthInput.isEmailValid(address))
        }
    }

    // ==================== password ====================

    @Test
    fun `the create-account minimum is exactly the rule the screen states`() {
        assertEquals(8, AuthInput.MIN_PASSWORD_LENGTH)
        assertFalse(AuthInput.isPasswordLongEnough("1234567"))
        assertTrue(AuthInput.isPasswordLongEnough("12345678"))
    }

    @Test
    fun `no rule beyond length is invented`() {
        // Supabase enforces a length and nothing else unless the project says
        // otherwise, and it reports its own refusals. A client that demanded an
        // uppercase letter would be stating a policy that does not exist, with no way
        // for anybody to discover it is wrong.
        assertTrue(AuthInput.isPasswordLongEnough("аааааааа"))
        assertTrue(AuthInput.isPasswordLongEnough("11111111"))
        assertTrue(AuthInput.isPasswordLongEnough("        "))
    }

    @Test
    fun `a password is never trimmed`() {
        // Spaces at either end are part of the secret. Trimming would send something
        // other than what was typed, and the account would then be unreachable from
        // anywhere that did not make the same mistake.
        assertTrue(AuthInput.isPasswordLongEnough(" secret "))
        assertEquals(8, " secret ".length)
    }

    @Test
    fun `sign-in asks only that there is a password`() {
        // Deliberately not the create-account minimum: Supabase's own default is six,
        // so an account made before this rule existed still has to be able to sign in.
        assertTrue(AuthInput.isPasswordPresent("123456"))
        assertTrue(AuthInput.isPasswordPresent("x"))
        assertFalse(AuthInput.isPasswordPresent(""))
    }
}
