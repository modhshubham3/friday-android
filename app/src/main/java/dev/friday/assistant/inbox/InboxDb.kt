package dev.friday.assistant.inbox

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Everything Friday knows about the user's messages, in one database on the phone. Names and message text are
 * locked with [Vault] before they are stored; what stays readable is only what is needed to sort and count.
 */
class InboxDb private constructor(context: Context) : SQLiteOpenHelper(context.applicationContext, "inbox.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE events(
                id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, pkg TEXT NOT NULL, app TEXT NOT NULL,
                conv_enc TEXT NOT NULL, sender_key TEXT NOT NULL, sender_enc TEXT NOT NULL, text_enc TEXT NOT NULL,
                kind TEXT NOT NULL, category TEXT NOT NULL, importance REAL NOT NULL, prior REAL NOT NULL,
                is_group INTEGER NOT NULL, is_question INTEGER NOT NULL, sensitive INTEGER NOT NULL,
                announced INTEGER NOT NULL DEFAULT 0, feedback INTEGER NOT NULL DEFAULT 0, feat TEXT NOT NULL)""",
        )
        db.execSQL("CREATE INDEX events_ts ON events(ts)")
        db.execSQL("CREATE TABLE senders(key TEXT PRIMARY KEY, name_enc TEXT NOT NULL, rule INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE weights(idx INTEGER PRIMARY KEY, w REAL NOT NULL)")
        db.execSQL("CREATE TABLE apps(pkg TEXT PRIMARY KEY, label TEXT NOT NULL, enabled INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE digests(day TEXT PRIMARY KEY, text TEXT NOT NULL, ts INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE facts(k TEXT PRIMARY KEY, v_enc TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---- Events -------------------------------------------------------------

    fun insert(e: NewEvent): Long {
        val values = ContentValues().apply {
            put("ts", e.ts); put("pkg", e.pkg); put("app", e.app)
            put("conv_enc", Vault.seal(e.conv))
            put("sender_key", Vault.fingerprint(e.sender))
            put("sender_enc", Vault.seal(e.sender))
            put("text_enc", Vault.seal(e.text))
            put("kind", e.kind.name); put("category", e.category)
            put("importance", e.importance); put("prior", e.prior)
            put("is_group", if (e.group) 1 else 0)
            put("is_question", if (e.question) 1 else 0)
            put("sensitive", if (e.sensitive) 1 else 0)
            put("feat", e.features.joinToString("|"))
        }
        return writableDatabase.insert("events", null, values)
    }

    fun recent(limit: Int): List<Event> = events("ORDER BY ts DESC LIMIT $limit")

    fun since(ts: Long): List<Event> = events("WHERE ts >= $ts ORDER BY importance DESC, ts DESC")

    /** Not yet read out and important enough to read, most important first, then oldest first. */
    fun pending(minImportance: Float, limit: Int): List<Event> =
        events("WHERE announced = 0 AND importance >= $minImportance ORDER BY importance DESC, ts ASC LIMIT $limit")

    fun fromSender(name: String, limit: Int): List<Event> {
        val wanted = name.trim().lowercase()
        return events("ORDER BY ts DESC LIMIT 400").filter { it.sender.lowercase().contains(wanted) }.take(limit)
    }

    fun event(id: Long): Event? = events("WHERE id = $id").firstOrNull()

    fun markAnnounced(id: Long) {
        writableDatabase.execSQL("UPDATE events SET announced = 1 WHERE id = $id")
    }

    fun setFeedback(id: Long, feedback: Int) {
        writableDatabase.execSQL("UPDATE events SET feedback = $feedback WHERE id = $id")
    }

    fun purge(olderThan: Long) {
        writableDatabase.execSQL("DELETE FROM events WHERE ts < $olderThan")
    }

    private fun events(tail: String): List<Event> {
        val out = mutableListOf<Event>()
        readableDatabase.rawQuery(
            "SELECT id, ts, pkg, app, conv_enc, sender_key, sender_enc, text_enc, kind, category, importance, prior, " +
                "is_group, is_question, sensitive, announced, feedback, feat FROM events $tail",
            null,
        ).use { c -> while (c.moveToNext()) out += read(c) }
        return out
    }

    private fun read(c: Cursor) = Event(
        id = c.getLong(0), ts = c.getLong(1), pkg = c.getString(2), app = c.getString(3),
        conv = Vault.open(c.getString(4)), senderKey = c.getString(5),
        sender = Vault.open(c.getString(6)), text = Vault.open(c.getString(7)),
        kind = runCatching { Kind.valueOf(c.getString(8)) }.getOrDefault(Kind.OTHER),
        category = c.getString(9), importance = c.getFloat(10), prior = c.getFloat(11),
        group = c.getInt(12) == 1, question = c.getInt(13) == 1, sensitive = c.getInt(14) == 1,
        announced = c.getInt(15) == 1, feedback = c.getInt(16),
        features = c.getString(17).split("|").filter { it.isNotEmpty() },
    )

    // ---- Senders and the rules about them ------------------------------------

    fun touchSender(key: String, name: String) {
        writableDatabase.insertWithOnConflict(
            "senders", null,
            ContentValues().apply { put("key", key); put("name_enc", Vault.seal(name)); put("rule", Rule.NONE) },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun rule(key: String): Int =
        readableDatabase.rawQuery("SELECT rule FROM senders WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getInt(0) else Rule.NONE }

    fun setRule(key: String, name: String, rule: Int) {
        touchSender(key, name)
        writableDatabase.execSQL("UPDATE senders SET rule = $rule WHERE key = ?", arrayOf(key))
    }

    /** Senders that have a rule, as (name, key, rule). */
    fun ruled(): List<Triple<String, String, Int>> {
        val out = mutableListOf<Triple<String, String, Int>>()
        readableDatabase.rawQuery("SELECT name_enc, key, rule FROM senders WHERE rule != 0", null)
            .use { while (it.moveToNext()) out += Triple(Vault.open(it.getString(0)), it.getString(1), it.getInt(2)) }
        return out
    }

    // ---- What the classifier has learned --------------------------------------

    fun loadWeights(): Map<Int, Float> {
        val out = HashMap<Int, Float>()
        readableDatabase.rawQuery("SELECT idx, w FROM weights", null).use { while (it.moveToNext()) out[it.getInt(0)] = it.getFloat(1) }
        return out
    }

    fun saveWeight(index: Int, weight: Float) {
        writableDatabase.insertWithOnConflict(
            "weights", null, ContentValues().apply { put("idx", index); put("w", weight) }, SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    // ---- Which apps are watched ------------------------------------------------

    fun app(pkg: String): AppRow? =
        readableDatabase.rawQuery("SELECT pkg, label, enabled FROM apps WHERE pkg = ?", arrayOf(pkg))
            .use { if (it.moveToFirst()) AppRow(it.getString(0), it.getString(1), it.getInt(2) == 1) else null }

    fun apps(): List<AppRow> {
        val out = mutableListOf<AppRow>()
        readableDatabase.rawQuery("SELECT pkg, label, enabled FROM apps ORDER BY enabled DESC, label", null)
            .use { while (it.moveToNext()) out += AppRow(it.getString(0), it.getString(1), it.getInt(2) == 1) }
        return out
    }

    fun upsertApp(row: AppRow) {
        writableDatabase.insertWithOnConflict(
            "apps", null,
            ContentValues().apply { put("pkg", row.pkg); put("label", row.label); put("enabled", if (row.enabled) 1 else 0) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    // ---- Day summaries and remembered facts ------------------------------------

    fun saveDigest(day: String, text: String) {
        writableDatabase.insertWithOnConflict(
            "digests", null,
            ContentValues().apply { put("day", day); put("text", Vault.seal(text)); put("ts", System.currentTimeMillis()) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun digest(day: String): String? =
        readableDatabase.rawQuery("SELECT text FROM digests WHERE day = ?", arrayOf(day))
            .use { if (it.moveToFirst()) Vault.open(it.getString(0)).ifBlank { null } else null }

    fun putFact(fact: String) {
        writableDatabase.insertWithOnConflict(
            "facts", null,
            ContentValues().apply { put("k", Vault.fingerprint(fact)); put("v_enc", Vault.seal(fact)) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun facts(): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT v_enc FROM facts", null).use { while (it.moveToNext()) Vault.open(it.getString(0)).takeIf { s -> s.isNotBlank() }?.let(out::add) }
        return out
    }

    /** Forgets every fact that mentions [words]. Returns how many. */
    fun forgetFacts(words: String): Int {
        val wanted = words.trim().lowercase()
        if (wanted.isEmpty()) return 0
        var removed = 0
        for (fact in facts()) {
            if (fact.lowercase().contains(wanted)) {
                writableDatabase.execSQL("DELETE FROM facts WHERE k = ?", arrayOf(Vault.fingerprint(fact)))
                removed++
            }
        }
        return removed
    }

    companion object {
        @Volatile private var instance: InboxDb? = null

        fun get(context: Context): InboxDb =
            instance ?: synchronized(this) { instance ?: InboxDb(context).also { instance = it } }
    }
}
