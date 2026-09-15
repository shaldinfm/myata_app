package com.example.musicplayerapp.ui.profile

/**
 * What the avatar picker does with a choice, decided without a device.
 *
 * ## Choosing and saving are two steps, because the frame draws two
 *
 * `profile-avatar` has a `Сохранить` button under the grid. A tap moves the ring, the
 * badge and the `Текущий аватар` preview at once - the listener sees the choice
 * immediately - and only `Сохранить` writes it to the account. Back without saving
 * leaves the account as it was, which is what Back means on every other form here.
 */
object AvatarSelection {

    /**
     * The cell ringed when the picker opens.
     *
     * A choice that survived a rotation or process death wins, if it is still a real
     * avatar. Otherwise the account's own avatar. An account with none - or with a key
     * this build does not know - opens with **no** cell ringed: the frame's ringed
     * `myata-06` is an example state, and picking one for somebody would be exactly the
     * random default the listener never chose.
     */
    fun initial(savedOnAccount: String?, restored: String?): String? =
        ProfileAvatars.resolve(restored)?.key ?: ProfileAvatars.resolve(savedOnAccount)?.key

    /**
     * Whether `Сохранить` has anything to write.
     *
     * False with nothing chosen, and false when the choice is what the account already
     * holds - saving the same avatar again is a request that can fail and change
     * nothing, so the button simply returns. An unknown stored key counts as none, so
     * choosing any real avatar over it is a change.
     */
    fun needsWrite(savedOnAccount: String?, selected: String?): Boolean {
        val chosen = ProfileAvatars.resolve(selected) ?: return false
        return chosen.key != ProfileAvatars.resolve(savedOnAccount)?.key
    }
}
