package com.example.expenseledger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class ExpenseStatus(val label: String) {
    PAID("Paid"),
    HOLD("Hold"),
    NEED("Need");

    fun next(): ExpenseStatus = when (this) {
        PAID -> HOLD
        HOLD -> NEED
        NEED -> PAID
    }

    companion object {
        fun fromString(value: String): ExpenseStatus =
            entries.firstOrNull { it.label.equals(value, ignoreCase = true) } ?: NEED
    }
}

data class AuditEntry(
    val id: String = UUID.randomUUID().toString(),
    val action: String, // "Created" or "Updated"
    val at: String,
    val summary: String = ""
)

data class LabelRecord(
    val id: String = "lbl-" + UUID.randomUUID().toString().take(8),
    val name: String,
    val description: String,
    val colorHex: String,
    val createdAt: String,
    val updatedAt: String = createdAt,
    val auditLog: List<AuditEntry>
)

data class ListNoteRecord(
    val id: String = "list-" + UUID.randomUUID().toString().take(8),
    val title: String,
    val content: String,
    val createdAt: String,
    val updatedAt: String = createdAt,
    val auditLog: List<AuditEntry>
)

data class ExpenseRecord(
    val id: String = "exp-" + UUID.randomUUID().toString().take(8),
    val balance: Double,
    val quote: String,
    val status: ExpenseStatus,
    val labelIds: List<String>,
    val attachedListIds: List<String> = emptyList(),
    val calcHistoryId: String? = null,
    val calcFormula: String? = null,
    val createdAt: String,
    val updatedAt: String = createdAt,
    val auditLog: List<AuditEntry>
)

data class CalcHistoryRecord(
    val id: String = "calc-" + UUID.randomUUID().toString().take(8),
    val expression: String,
    val result: Double,
    val createdAt: String,
    val updatedAt: String
)

data class LedgerSnapshot(
    val expenses: List<ExpenseRecord>,
    val labels: List<LabelRecord>,
    val lists: List<ListNoteRecord>,
    val calculatorHistory: List<CalcHistoryRecord>
)

object LedgerMath {
    fun nowIso(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        return fmt.format(Date())
    }

    fun formatReadableDate(iso: String): String {
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            val date = parser.parse(iso.take(19)) ?: return iso
            SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault()).format(date)
        } catch (_: Exception) {
            iso
        }
    }

    fun formatCompactDate(iso: String): String {
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            val date = parser.parse(iso.take(19)) ?: return iso
            SimpleDateFormat("dd-MM-yy HH:mm", Locale.getDefault()).format(date)
        } catch (_: Exception) {
            iso
        }
    }

    fun extractNumbersSum(text: String): Double {
        if (text.isBlank()) return 0.0
        var sum = 0.0
        val regex = Regex("""(?:₹|rs\.?|inr)?\s*(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d+))?""", RegexOption.IGNORE_CASE)
        val bulletPrefix = Regex("""^\s*\d+[.)]\s+""")
        for (rawLine in text.lines()) {
            val cleaned = rawLine.replace(bulletPrefix, "")
            regex.findAll(cleaned).forEach { m ->
                val whole = m.groupValues[1].replace(",", "")
                val dec = if (m.groupValues[2].isNotEmpty()) "." + m.groupValues[2] else ""
                val parsed = (whole + dec).toDoubleOrNull()
                if (parsed != null) sum += parsed
            }
        }
        return kotlin.math.round(sum * 100.0) / 100.0
    }
}

class LedgerDbHelper(context: Context) : SQLiteOpenHelper(context, "expense_ledger.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS ledger_store (
                store_key TEXT PRIMARY KEY,
                payload_json TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS ledger_store")
        onCreate(db)
    }

    fun loadSnapshot(): LedgerSnapshot {
        val db = readableDatabase
        val cursor = db.query(
            "ledger_store",
            arrayOf("payload_json"),
            "store_key = ?",
            arrayOf("main_ledger"),
            null,
            null,
            null
        )
        cursor.use { c ->
            if (c.moveToFirst()) {
                val raw = c.getString(0)
                return decodeSnapshot(raw)
            }
        }
        val seed = createSeedSnapshot()
        saveSnapshot(seed)
        return seed
    }

    fun saveSnapshot(snapshot: LedgerSnapshot) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("store_key", "main_ledger")
            put("payload_json", encodeSnapshot(snapshot))
            put("updated_at", LedgerMath.nowIso())
        }
        db.insertWithOnConflict("ledger_store", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun encodeAuditList(entries: List<AuditEntry>): JSONArray {
        return JSONArray().apply {
            entries.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("action", e.action)
                    put("at", e.at)
                    put("summary", e.summary)
                })
            }
        }
    }

    private fun decodeAuditList(arr: JSONArray?): List<AuditEntry> {
        if (arr == null) return emptyList()
        val list = mutableListOf<AuditEntry>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(
                AuditEntry(
                    id = obj.optString("id"),
                    action = obj.optString("action", "Created"),
                    at = obj.optString("at"),
                    summary = obj.optString("summary", "")
                )
            )
        }
        return list
    }

    fun encodeSnapshot(snapshot: LedgerSnapshot): String {
        val root = JSONObject()
        val labelsArr = JSONArray().apply {
            snapshot.labels.forEach { l ->
                put(JSONObject().apply {
                    put("id", l.id)
                    put("name", l.name)
                    put("description", l.description)
                    put("colorHex", l.colorHex)
                    put("createdAt", l.createdAt)
                    put("updatedAt", l.updatedAt)
                    put("auditLog", encodeAuditList(l.auditLog))
                })
            }
        }
        val listsArr = JSONArray().apply {
            snapshot.lists.forEach { n ->
                put(JSONObject().apply {
                    put("id", n.id)
                    put("title", n.title)
                    put("content", n.content)
                    put("createdAt", n.createdAt)
                    put("updatedAt", n.updatedAt)
                    put("auditLog", encodeAuditList(n.auditLog))
                })
            }
        }
        val expArr = JSONArray().apply {
            snapshot.expenses.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("balance", e.balance)
                    put("quote", e.quote)
                    put("status", e.status.label)
                    put("labelIds", JSONArray(e.labelIds))
                    put("attachedListIds", JSONArray(e.attachedListIds))
                    put("calcHistoryId", e.calcHistoryId ?: JSONObject.NULL)
                    put("calcFormula", e.calcFormula ?: JSONObject.NULL)
                    put("createdAt", e.createdAt)
                    put("updatedAt", e.updatedAt)
                    put("auditLog", encodeAuditList(e.auditLog))
                })
            }
        }
        val calcArr = JSONArray().apply {
            snapshot.calculatorHistory.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id)
                    put("expression", c.expression)
                    put("result", c.result)
                    put("createdAt", c.createdAt)
                    put("updatedAt", c.updatedAt)
                })
            }
        }
        root.put("labels", labelsArr)
        root.put("lists", listsArr)
        root.put("expenses", expArr)
        root.put("calculatorHistory", calcArr)
        return root.toString()
    }

    fun decodeSnapshot(raw: String): LedgerSnapshot {
        return try {
            val root = JSONObject(raw)
            val labels = mutableListOf<LabelRecord>()
            val labelsArr = root.optJSONArray("labels") ?: JSONArray()
            for (i in 0 until labelsArr.length()) {
                val o = labelsArr.optJSONObject(i) ?: continue
                val createdAt = o.optString("createdAt")
                val audit = decodeAuditList(o.optJSONArray("auditLog"))
                val updatedAt = o.optString("updatedAt").ifBlank { audit.lastOrNull()?.at ?: createdAt }
                labels.add(
                    LabelRecord(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        description = o.optString("description"),
                        colorHex = o.optString("colorHex", "#2563EB"),
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                        auditLog = audit
                    )
                )
            }

            val lists = mutableListOf<ListNoteRecord>()
            val listsArr = root.optJSONArray("lists") ?: JSONArray()
            for (i in 0 until listsArr.length()) {
                val o = listsArr.optJSONObject(i) ?: continue
                val createdAt = o.optString("createdAt")
                val audit = decodeAuditList(o.optJSONArray("auditLog"))
                val updatedAt = o.optString("updatedAt").ifBlank { audit.lastOrNull()?.at ?: createdAt }
                lists.add(
                    ListNoteRecord(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        content = o.optString("content"),
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                        auditLog = audit
                    )
                )
            }

            val expenses = mutableListOf<ExpenseRecord>()
            val expArr = root.optJSONArray("expenses") ?: JSONArray()
            for (i in 0 until expArr.length()) {
                val o = expArr.optJSONObject(i) ?: continue
                val lblIds = mutableListOf<String>()
                val lArr = o.optJSONArray("labelIds") ?: JSONArray()
                for (j in 0 until lArr.length()) lblIds.add(lArr.optString(j))

                val attachedIds = mutableListOf<String>()
                val aArr = o.optJSONArray("attachedListIds")
                if (aArr != null) {
                    for (j in 0 until aArr.length()) {
                        val s = aArr.optString(j)
                        if (s.isNotBlank()) attachedIds.add(s)
                    }
                } else if (!o.isNull("attachedListId")) {
                    val legacy = o.optString("attachedListId")
                    if (legacy.isNotBlank()) attachedIds.add(legacy)
                }

                val calcHistoryId = if (o.isNull("calcHistoryId")) null else o.optString("calcHistoryId").takeIf { it.isNotBlank() }
                val calcFormula = if (o.isNull("calcFormula")) null else o.optString("calcFormula").takeIf { it.isNotBlank() }
                val createdAt = o.optString("createdAt")
                val audit = decodeAuditList(o.optJSONArray("auditLog"))
                val updatedAt = o.optString("updatedAt").ifBlank { audit.lastOrNull()?.at ?: createdAt }

                expenses.add(
                    ExpenseRecord(
                        id = o.optString("id"),
                        balance = o.optDouble("balance", 0.0),
                        quote = o.optString("quote"),
                        status = ExpenseStatus.fromString(o.optString("status", "Need")),
                        labelIds = lblIds,
                        attachedListIds = attachedIds,
                        calcHistoryId = calcHistoryId,
                        calcFormula = calcFormula,
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                        auditLog = audit
                    )
                )
            }

            val calcHistory = mutableListOf<CalcHistoryRecord>()
            val calcArr = root.optJSONArray("calculatorHistory") ?: JSONArray()
            for (i in 0 until calcArr.length()) {
                val o = calcArr.optJSONObject(i) ?: continue
                calcHistory.add(
                    CalcHistoryRecord(
                        id = o.optString("id"),
                        expression = o.optString("expression"),
                        result = o.optDouble("result", 0.0),
                        createdAt = o.optString("createdAt"),
                        updatedAt = o.optString("updatedAt")
                    )
                )
            }

            LedgerSnapshot(expenses, labels, lists, calcHistory)
        } catch (_: Exception) {
            createSeedSnapshot()
        }
    }

    private fun createSeedSnapshot(): LedgerSnapshot {
        val t1 = "2026-11-10T09:15:00.000Z"
        val t2 = "2026-11-14T08:30:00.000Z"
        val labels = listOf(
            LabelRecord("lbl-dr", "#Dr.", "Clinic consultations & pharmacy bills", "#2563EB", t1, t1, listOf(AuditEntry(action = "Created", at = t1, summary = "Seeded label"))),
            LabelRecord("lbl-school", "#School", "Tuition, craft supplies & fees", "#7C3AED", t1, t1, listOf(AuditEntry(action = "Created", at = t1, summary = "Seeded label"))),
            LabelRecord("lbl-vegetables", "#Vegetables", "Daily fresh market produce", "#059669", t1, t1, listOf(AuditEntry(action = "Created", at = t1, summary = "Seeded label")))
        )
        val lists = listOf(
            ListNoteRecord("list-craft", "School Craft & Fees Breakdown", "Wood -> ₹5
Fees -> ₹10
Others -> ₹15", t2, t2, listOf(AuditEntry(action = "Created", at = t2, summary = "Created breakdown ₹30")))
        )
        val expenses = listOf(
            ExpenseRecord("exp-1", 850.0, "Pediatric checkup & vitamin prescription", ExpenseStatus.PAID, listOf("lbl-dr", "lbl-school"), emptyList(), null, null, t2, t2, listOf(AuditEntry(action = "Created", at = t2, summary = "Logged ₹850"))),
            ExpenseRecord("exp-2", 30.0, "Term project wood, fees & misc supplies", ExpenseStatus.HOLD, listOf("lbl-school"), listOf("list-craft"), "calc-1", "5 + 10 + 15 = 30", t2, t2, listOf(AuditEntry(action = "Created", at = t2, summary = "Logged ₹30 with attached list"))),
            ExpenseRecord("exp-3", 450.0, "Lab blood panel follow-up deposit", ExpenseStatus.NEED, listOf("lbl-dr"), emptyList(), null, null, t2, t2, listOf(AuditEntry(action = "Created", at = t2, summary = "Logged ₹450")))
        )
        val calcHistory = listOf(
            CalcHistoryRecord("calc-1", "5 + 10 + 15", 30.0, t2, t2)
        )
        return LedgerSnapshot(expenses, labels, lists, calcHistory)
    }
}
