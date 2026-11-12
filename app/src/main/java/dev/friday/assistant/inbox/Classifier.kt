package dev.friday.assistant.inbox

import android.content.Context
import android.provider.ContactsContract
import java.util.Calendar

/**
 * Works out what a message is and makes a first guess at how much it matters, before anything has been learned.
 * Cheap and local: word lists and a few rules. [Scorer] then corrects the guess from the user's own answers.
 */
object Classifier {
    data class Analysis(
        val category: String,
        val features: List<String>,
        val prior: Float,
        val question: Boolean,
        val sensitive: Boolean,
    )

    private val URGENT = listOf(
        "urgent", "asap", "jaldi", "abhi", "emergency", "immediately", "turant", "zaroori", "call me", "call kar",
        "please call", "help", "important", "accident", "hospital",
    )
    private val ASKING = listOf(
        "kab", "kya", "kaise", "kahan", "when", "where", "can you", "could you", "please", "bhej", "send", "reply",
        "batao", "tell me", "let me know",
    )
    private val PROMO = listOf(
        "% off", "sale", "offer", "discount", "unsubscribe", "newsletter", "deal", "cashback", "coupon",
        "limited time", "congratulations", "you won", "flat ", "upto", "up to ", "promo", "webinar", "subscribe",
    )
    private val PROMO_SENDERS = listOf("noreply", "no-reply", "newsletter", "marketing", "offers", "mailer", "deals", "updates@", "info@")
    private val MONEY = listOf("debited", "credited", "a/c", "account", "upi", "transaction", "statement", "invoice", "bill ", "due ", "emi", "payment")
    private val DELIVERY = listOf("out for delivery", "delivered", "arriving", "your order", "shipped", "dispatched", "tracking", "delivery partner")
    private val WATCHED = listOf("meeting", "interview", "invoice", "deadline", "payment", "offer letter", "tomorrow", "kal", "aaj", "tonight")

    private val knownCache = HashMap<String, Boolean>()

    fun senderKey(name: String) = Vault.fingerprint(name)

    fun analyze(context: Context, p: Parsed): Analysis {
        val text = p.text.lowercase()
        val sender = p.sender.lowercase()
        val promoLike = PROMO.any { it in text } || PROMO_SENDERS.any { it in sender }
        val moneyLike = MONEY.any { it in text }
        val deliveryLike = DELIVERY.any { it in text }

        var category = when (p.kind) {
            Kind.CALL -> Cat.CALL
            Kind.MESSAGE -> if (p.group) Cat.GROUP else Cat.PERSON
            Kind.MAIL -> when {
                promoLike -> Cat.PROMO
                moneyLike -> Cat.MONEY
                deliveryLike -> Cat.DELIVERY
                else -> Cat.MAIL
            }
            Kind.OTHER -> when {
                promoLike -> Cat.PROMO
                moneyLike -> Cat.MONEY
                deliveryLike -> Cat.DELIVERY
                else -> Cat.OTHER
            }
        }
        // A forwarded sale in a WhatsApp group is still a sale.
        if (p.kind == Kind.MESSAGE && promoLike && p.group) category = Cat.PROMO

        val question = '?' in text || ASKING.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(text) }
        val urgent = URGENT.any { it in text }
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        val features = mutableListOf("app:${p.pkg}", "cat:$category", "s:${senderKey(p.sender)}", "g:${p.group}", "h:${hour / 4}")
        features += "len:${when { text.length < 15 -> 0; text.length < 80 -> 1; else -> 2 }}"
        if (question) features += "q"
        if (urgent) features += "u"
        WATCHED.filter { it in text }.forEach { features += "kw:$it" }

        var prior = when (category) {
            Cat.PERSON -> 0.8f
            Cat.GROUP -> -0.9f
            Cat.CALL -> 1.6f
            Cat.MAIL -> 0.45f
            Cat.PROMO -> -2.4f
            Cat.MONEY -> 0.0f
            Cat.DELIVERY -> -0.3f
            else -> -1.0f
        }
        if (question) prior += 0.6f
        if (urgent) prior += 1.2f
        if (p.kind == Kind.MESSAGE || p.kind == Kind.CALL) {
            // A saved contact matters more than a bare number.
            prior += if (isKnown(context, p.sender)) 0.5f else if (looksLikeNumber(p.sender)) -0.4f else 0f
        }
        return Analysis(category, features, prior, question, sensitive = category == Cat.MONEY)
    }

    private fun looksLikeNumber(name: String) = name.count { it.isDigit() } >= 8

    private fun isKnown(context: Context, name: String): Boolean = synchronized(knownCache) {
        knownCache.getOrPut(name.lowercase()) {
            runCatching {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?", arrayOf(name), null,
                )?.use { it.moveToFirst() } ?: false
            }.getOrDefault(false)
        }
    }
}
