package dev.friday.assistant.tools

/**
 * Questions a tool can put to the user mid-task. Tools use these instead of guessing:
 * an unclear request becomes a list to pick from, and anything destructive asks first.
 */
interface UserPrompts {
    /** Asks for the contacts permission, returning whether it was granted. */
    suspend fun ensureContacts(): Boolean

    /** Asks for the permission to place calls directly, returning whether it was granted. */
    suspend fun ensureCallPhone(): Boolean

    /** Shows [options] and returns the index the user tapped, or null if they backed out. */
    suspend fun choose(title: String, options: List<String>): Int?

    /** Asks for a few free words (for instance what to write in a reply). Null if nothing was said or the user backed out. */
    suspend fun ask(question: String): String?

    /** Asks a yes/no question; anything other than tapping [yes] counts as no. */
    suspend fun confirm(message: String, yes: String): Boolean
}
