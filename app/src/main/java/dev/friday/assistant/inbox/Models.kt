package dev.friday.assistant.inbox

import android.app.PendingIntent
import android.app.RemoteInput

enum class Kind { MESSAGE, MAIL, CALL, OTHER }

/** What kind of thing it is. Decides the starting guess of how much it matters. */
object Cat {
    const val PERSON = "PERSON"
    const val GROUP = "GROUP"
    const val CALL = "CALL"
    const val MAIL = "MAIL"
    const val PROMO = "PROMO"
    const val MONEY = "MONEY"
    const val DELIVERY = "DELIVERY"
    const val OTHER = "OTHER"
}

/** What it takes to answer a notification from here: the reply button and its text box. */
data class ReplyHandle(val action: PendingIntent, val input: RemoteInput)

/** One message or mail, read out of a notification. */
data class Parsed(
    val pkg: String,
    val app: String,
    val key: String,
    val conv: String,
    val sender: String,
    val text: String,
    val group: Boolean,
    val kind: Kind,
    val time: Long,
    val reply: ReplyHandle?,
)

data class NewEvent(
    val ts: Long,
    val pkg: String,
    val app: String,
    val conv: String,
    val sender: String,
    val text: String,
    val kind: Kind,
    val category: String,
    val importance: Float,
    val prior: Float,
    val group: Boolean,
    val question: Boolean,
    val sensitive: Boolean,
    val features: List<String>,
)

/** A saved event, with its sender and text unlocked. [feedback] is 1 for "mattered", -1 for "did not", 0 for no answer yet. */
data class Event(
    val id: Long,
    val ts: Long,
    val pkg: String,
    val app: String,
    val conv: String,
    val senderKey: String,
    val sender: String,
    val text: String,
    val kind: Kind,
    val category: String,
    val importance: Float,
    val prior: Float,
    val group: Boolean,
    val question: Boolean,
    val sensitive: Boolean,
    val announced: Boolean,
    val feedback: Int,
    val features: List<String>,
)

data class AppRow(val pkg: String, val label: String, val enabled: Boolean)

/** What the user has said to do with one sender. */
object Rule {
    const val NONE = 0
    const val ALWAYS = 1
    const val NEVER = 2
}
