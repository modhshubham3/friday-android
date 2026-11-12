package dev.friday.assistant.inbox

import kotlin.math.exp

/**
 * The part of Friday that learns. Each message is described by a handful of plain features (the app, the sender,
 * whether it asks a question, the time of day, a few telling words). A starting guess from [Classifier] is nudged by a weight
 * per feature, and every "that mattered" or "that did not" moves those weights, so the sender who always matters
 * rises and the one who never does sinks. It is logistic regression, trained one correction at a time, on the phone.
 */
object Scorer {
    private const val BUCKETS = 4096
    /** How far one correction moves the score of the very message it was about, shared out among its features. */
    private const val STEP = 2.0f

    /** The sender is the most telling feature, so it takes a bigger share than the app or the time of day. */
    private const val SENDER_SHARE = 10f
    private val weights = FloatArray(BUCKETS)
    @Volatile private var loaded = false

    private fun ensureLoaded(db: InboxDb) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            db.loadWeights().forEach { (i, w) -> if (i in 0 until BUCKETS) weights[i] = w }
            loaded = true
        }
    }

    private fun bucket(feature: String) = (feature.hashCode() and 0x7fffffff) % BUCKETS

    /** How likely this is to matter, 0 to 1. */
    fun probability(db: InboxDb, features: List<String>, prior: Float): Float {
        ensureLoaded(db)
        var z = prior
        for (f in features.distinct()) z += weights[bucket(f)]
        return 1f / (1f + exp(-z))
    }

    /** One correction. [mattered] true raises everything about this message, false lowers it. */
    fun learn(db: InboxDb, features: List<String>, prior: Float, mattered: Boolean) {
        ensureLoaded(db)
        val error = (if (mattered) 1f else 0f) - probability(db, features, prior)
        // One correction is spread over all the features, not given in full to each, or a single thumbs-up on one
        // group message would lift every group message at once.
        val shares = features.distinct().map { it to if (it.startsWith("s:")) SENDER_SHARE else 1f }
        val total = shares.sumOf { it.second.toDouble() }.toFloat()
        for ((f, share) in shares) {
            val i = bucket(f)
            weights[i] = (weights[i] + STEP * error * share / total).coerceIn(-4f, 4f)
            db.saveWeight(i, weights[i])
        }
    }
}
