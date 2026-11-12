package dev.friday.assistant.tools

/**
 * Questions a tool can put to the user mid-task. Tools use these instead of guessing:
 * an unclear request becomes a list to pick from, and anything destructive asks first.
 */
interface UserPrompts {
    /** Asks for the contacts permission, returning whether it was granted. */
    suspend fun ensureContacts(): Boolean

    /** Shows [options] and returns the index the user tapped, or null if they backed out. */
    suspend fun choose(title: String, options: List<String>): Int?

    /** Asks a yes/no question; anything other than tapping [yes] counts as no. */
    suspend fun confirm(message: String, yes: String): Boolean
}
