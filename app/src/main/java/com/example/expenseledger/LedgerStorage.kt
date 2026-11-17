package com.example.expenseledger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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

enum class EntryType(val label: String) {
    EXPENSE("Expense"),
    INCOME("Income");

    companion object {
        fun fromString(value: String): EntryType =
            entries.firstOrNull { it.label.equals(value, ignoreCase = true) } ?: EXPENSE
    }
}

enum class PaymentMode(val label: String) {
    UPI("UPI"),
    CASH("Cash"),
    CARD("Card"),
    BANK("Bank");

    companion object {
        fun fromString(value: String): PaymentMode =
            entries.firstOrNull { it.label.equals(value, ignoreCase = true) } ?: UPI
    }
}

enum class RecurrenceCycle(val label: String) {
    NONE("None"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly");

    companion object {
        fun fromString(value: String): RecurrenceCycle =
            entries.firstOrNull { it.label.equals(value, ignoreCase = true) } ?: NONE
    }
}

enum class BinnedEntityType(val key: String, val displayLabel: String) {
    EXPENSE("expense", "Expense"),
    LABEL("label", "Label"),
    LIST("list", "List"),
    TEMPLATE("template", "Template");

    companion object {
        fun fromKey(value: String): BinnedEntityType =
            entries.firstOrNull { it.key.equals(value, ignoreCase = true) } ?: EXPENSE
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
    val monthlyBudget: Double? = null,
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
    val auditLog: List<AuditEntry>,
    val entryType: EntryType = EntryType.EXPENSE,
    val paymentMode: PaymentMode = PaymentMode.UPI,
    val isPinned: Boolean = false,
    val dueDate: String? = null
)

data class CalcHistoryRecord(
    val id: String = "calc-" + UUID.randomUUID().toString().take(8),
    val expression: String,
    val result: Double,
    val createdAt: String,
    val updatedAt: String
)

data class ExpenseTemplateRecord(
    val id: String = "tpl-" + UUID.randomUUID().toString().take(8),
    val title: String,
    val balance: Double,
    val defaultStatus: ExpenseStatus = ExpenseStatus.NEED,
    val labelIds: List<String> = emptyList(),
    val attachedListIds: List<String> = emptyList(),
    val recurrence: RecurrenceCycle = RecurrenceCycle.NONE,
    val lastUsedAt: String? = null,
    val createdAt: String,
    val entryType: EntryType = EntryType.EXPENSE,
    val paymentMode: PaymentMode = PaymentMode.UPI
)

data class BinnedRecord(
    val id: String = "bin-" + UUID.randomUUID().toString().take(8),
    val entityType: BinnedEntityType,
    val title: String,
    val subtitle: String,
    val amount: Double? = null,
    val deletedAt: String,
    val payloadJson: String
)

data class LedgerSnapshot(
    val expenses: List<ExpenseRecord>,
    val labels: List<LabelRecord>,
    val lists: List<ListNoteRecord>,
    val calculatorHistory: List<CalcHistoryRecord>,
    val templates: List<ExpenseTemplateRecord> = emptyList(),
    val bin: List<BinnedRecord> = emptyList(),
    val binRetentionDays: Int? = 30,
    val privacyPinHash: String? = null,
    val hideBalances: Boolean = false
)

data class CalendarDayCell(
    val dateKey: String,
    val day: Int,
    val total: Double,
    val expenses: List<ExpenseRecord>
)

data class CashflowSummary(
    val totalIncome: Double,
    val paidExpenses: Double,
    val unpaidObligations: Double,
    val netCashflow: Double,
    val incomeCount: Int,
    val expenseCount: Int
)

data class WalletSummary(
    val mode: PaymentMode,
    val expenseTotal: Double,
    val incomeTotal: Double,
    val netFlow: Double,
    val count: Int,
    val sharePct: Int
)

data class MerchantSummary(
    val displayName: String,
    val count: Int,
    val totalSpent: Double,
    val avgSpent: Double,
    val primaryMode: PaymentMode
)

data class ParsedSmsResult(
    val matched: Boolean,
    val amount: Double?,
    val entryType: EntryType,
    val paymentMode: PaymentMode,
    val quote: String
)

data class CsvImportResult(
    val validEntries: List<ExpenseRecord>,
    val newLabels: List<LabelRecord>,
    val skippedRows: Int,
    val errors: List<String>
)

object LedgerMath {
    private const val MS_PER_DAY = 86_400_000L

    fun nowIso(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    fun parseIsoMillis(iso: String): Long? {
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            parser.timeZone = TimeZone.getTimeZone("UTC")
            parser.parse(iso.take(19))?.time
        } catch (_: Exception) {
            null
        }
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

    fun formatAmt(value: Double): String {
        val rounded = kotlin.math.round(value * 100.0) / 100.0
        return if (rounded % 1.0 == 0.0) {
            rounded.toLong().toString()
        } else {
            String.format(Locale.US, "%.2f", rounded)
        }
    }

    fun formatDisplayAmt(value: Double, hideBalances: Boolean): String {
        return if (hideBalances) "••••" else formatAmt(value)
    }

    fun sha256Pin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(("elpin-v1:" + pin.trim()).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun verifyPin(pin: String, expectedHash: String?): Boolean {
        if (expectedHash.isNullOrBlank()) return true
        return sha256Pin(pin) == expectedHash.lowercase(Locale.US)
    }

    fun availableMonths(expenses: List<ExpenseRecord>): List<String> {
        val monthRegex = Regex("""^\d{4}-\d{2}$""")
        return expenses
            .mapNotNull { exp ->
                val prefix = exp.createdAt.take(7)
                if (monthRegex.matches(prefix)) prefix else null
            }
            .distinct()
            .sortedDescending()
    }

    fun stepMonthKey(monthKey: String, delta: Int): String {
        val parts = monthKey.split("-")
        if (parts.size != 2) return monthKey
        val year = parts[0].toIntOrNull() ?: return monthKey
        val month = (parts[1].toIntOrNull() ?: return monthKey) - 1
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(year, month, 1, 0, 0, 0)
        cal.add(Calendar.MONTH, delta)
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        return String.format(Locale.US, "%04d-%02d", y, m)
    }

    fun buildCalendarGrid(expenses: List<ExpenseRecord>, monthKey: String): Pair<Int, List<CalendarDayCell>> {
        val parts = monthKey.split("-")
        val year = parts.getOrNull(0)?.toIntOrNull() ?: 2026
        val month = (parts.getOrNull(1)?.toIntOrNull() ?: 11) - 1
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(year, month, 1, 0, 0, 0)
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dow = cal.get(Calendar.DAY_OF_WEEK) // Sun=1 .. Sat=7
        val firstWeekdayMon0 = (dow + 5) % 7
        val monthExpenses = expenses.filter { it.entryType == EntryType.EXPENSE && it.createdAt.startsWith(monthKey) }
        val byDate = monthExpenses.groupBy { it.createdAt.take(10) }
        val cells = (1..daysInMonth).map { day ->
            val dateKey = String.format(Locale.US, "%s-%02d", monthKey, day)
            val list = byDate[dateKey].orEmpty()
            CalendarDayCell(dateKey = dateKey, day = day, total = list.sumOf { it.balance }, expenses = list)
        }
        return firstWeekdayMon0 to cells
    }

    fun computeCashflowSummary(expenses: List<ExpenseRecord>, monthKey: String? = null): CashflowSummary {
        val scope = if (monthKey.isNullOrBlank()) expenses else expenses.filter { it.createdAt.startsWith(monthKey) }
        val incomeItems = scope.filter { it.entryType == EntryType.INCOME }
        val expItems = scope.filter { it.entryType == EntryType.EXPENSE }
        val totalIncome = incomeItems.sumOf { it.balance }
        val paidExp = expItems.filter { it.status == ExpenseStatus.PAID }.sumOf { it.balance }
        val unpaid = expItems.filter { it.status != ExpenseStatus.PAID }.sumOf { it.balance }
        return CashflowSummary(
            totalIncome = totalIncome,
            paidExpenses = paidExp,
            unpaidObligations = unpaid,
            netCashflow = totalIncome - paidExp,
            incomeCount = incomeItems.size,
            expenseCount = expItems.size
        )
    }

    fun computeWalletBreakdown(expenses: List<ExpenseRecord>, monthKey: String? = null): List<WalletSummary> {
        val scope = if (monthKey.isNullOrBlank()) expenses else expenses.filter { it.createdAt.startsWith(monthKey) }
        val grandExp = scope.filter { it.entryType == EntryType.EXPENSE }.sumOf { it.balance }
        return PaymentMode.entries.map { mode ->
            val rows = scope.filter { it.paymentMode == mode }
            val expAmt = rows.filter { it.entryType == EntryType.EXPENSE }.sumOf { it.balance }
            val incAmt = rows.filter { it.entryType == EntryType.INCOME }.sumOf { it.balance }
            val pct = if (grandExp > 0.0) kotlin.math.round((expAmt / grandExp) * 100.0).toInt() else 0
            WalletSummary(mode, expAmt, incAmt, incAmt - expAmt, rows.size, pct)
        }
    }

    fun computeMerchantInsights(expenses: List<ExpenseRecord>, monthKey: String? = null): List<MerchantSummary> {
        val scope = (if (monthKey.isNullOrBlank()) expenses else expenses.filter { it.createdAt.startsWith(monthKey) })
            .filter { it.entryType == EntryType.EXPENSE }
        val grouped = scope.groupBy {
            it.quote.trim().lowercase(Locale.US).replace(Regex("""\s*\(copy\)\s*$"""), "").ifBlank { "untitled" }
        }
        return grouped.values.map { list ->
            val total = list.sumOf { it.balance }
            val topMode = list.groupingBy { it.paymentMode }.eachCount().maxByOrNull { it.value }?.key ?: PaymentMode.UPI
            val title = list.first().quote.replace(Regex("""\s*\(copy\)\s*$""", RegexOption.IGNORE_CASE), "").trim().ifBlank { "Untitled" }
            MerchantSummary(
                displayName = title,
                count = list.size,
                totalSpent = total,
                avgSpent = total / list.size.coerceAtLeast(1),
                primaryMode = topMode
            )
        }.sortedWith(compareByDescending<MerchantSummary> { it.count }.thenByDescending { it.totalSpent })
    }

    fun computeDueLabel(dueDate: String?, status: ExpenseStatus, entryType: EntryType): String? {
        if (entryType == EntryType.INCOME || dueDate.isNullOrBlank()) return null
        if (!Regex("""^\d{4}-\d{2}-\d{2}$""").matches(dueDate.trim())) return null
        if (status == ExpenseStatus.PAID) return "Due $dueDate · Settled"
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            val dueMs = fmt.parse(dueDate.trim())?.time ?: return null
            val todayStr = fmt.format(Date())
            val todayMs = fmt.parse(todayStr)?.time ?: return null
            val diff = ((dueMs - todayMs) / MS_PER_DAY).toInt()
            when {
                diff < 0 -> "Overdue ${kotlin.math.abs(diff)}d"
                diff == 0 -> "Due Today"
                diff == 1 -> "Due Tomorrow"
                else -> "Due in ${diff}d"
            }
        } catch (_: Exception) {
            null
        }
    }

    fun parseBankSmsSnippet(rawText: String): ParsedSmsResult {
        val text = rawText.trim()
        if (text.isBlank()) {
            return ParsedSmsResult(false, null, EntryType.EXPENSE, PaymentMode.UPI, "")
        }
        val amtRegex = Regex("""(?:₹|rs\.?|inr)\s*(\d{1,3}(?:,\d{2,3})*(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
        val fallbackRegex = Regex("""(?:debited|credited|paid|sent|spent|received)\s*(?:by|for|with)?\s*(?:₹|rs\.?|inr)?\s*(\d{1,3}(?:,\d{2,3})*(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
        val amtMatch = amtRegex.find(text) ?: fallbackRegex.find(text)
        val amount = amtMatch?.groupValues?.getOrNull(1)?.replace(",", "")?.toDoubleOrNull()

        val hasCredit = Regex("""\b(credited|received|deposited|refunded|cashback|salary)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val hasDebit = Regex("""\b(debited|spent|paid|withdrawn|sent to)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val entryType = if (hasCredit && !hasDebit) EntryType.INCOME else EntryType.EXPENSE

        val mode = when {
            Regex("""\b(upi|vpa|gpay|google pay|phonepe|paytm|bhim|@ok|@ybl|@ibl|@axl)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> PaymentMode.UPI
            Regex("""\b(credit card|debit card|card xx|visa|mastercard|rupay|pos)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> PaymentMode.CARD
            Regex("""\b(neft|imps|rtgs|netbanking|net banking|a/c|acct|account)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> PaymentMode.BANK
            Regex("""\b(cash|atm)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) -> PaymentMode.CASH
            else -> PaymentMode.UPI
        }

        val merchantPat = Regex("""(?:\bto\b|\bat\b|\btowards\b|\bpaid to\b)\s+([A-Za-z0-9&._\- ]{2,36}?)(?=\s+(?:via|on|ref|utr|upi|using|from|avl|bal)\b|[.,;]|$)""", RegexOption.IGNORE_CASE)
        val fromPat = Regex("""(?:\bfrom\b|\bby\b)\s+([A-Za-z0-9&._\- ]{2,36}?)(?=\s+(?:via|on|ref|utr|upi|to|avl|bal)\b|[.,;]|$)""", RegexOption.IGNORE_CASE)
        val extractedQuote = (merchantPat.find(text)?.groupValues?.getOrNull(1)
            ?: fromPat.find(text)?.groupValues?.getOrNull(1)
            ?: text.replace(amtRegex, "").replace(Regex("""\s+"""), " ").take(36)).trim()

        return ParsedSmsResult(
            matched = amount != null && amount > 0.0,
            amount = amount,
            entryType = entryType,
            paymentMode = mode,
            quote = extractedQuote
        )
    }

    fun isTemplateDue(tpl: ExpenseTemplateRecord, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (tpl.recurrence == RecurrenceCycle.NONE) return false
        val last = tpl.lastUsedAt ?: return true
        val lastMs = parseIsoMillis(last) ?: return true
        if (tpl.recurrence == RecurrenceCycle.WEEKLY) {
            return (nowMs - lastMs) >= 7L * MS_PER_DAY
        }
        val fmt = SimpleDateFormat("yyyy-MM", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        val currentMonth = fmt.format(Date(nowMs))
        return last.take(7) < currentMonth
    }

    fun pruneExpiredBin(
        bin: List<BinnedRecord>,
        retentionDays: Int?,
        nowMs: Long = System.currentTimeMillis()
    ): List<BinnedRecord> {
        if (retentionDays == null) return bin
        val maxAge = retentionDays.toLong() * MS_PER_DAY
        return bin.filter { item ->
            val deletedMs = parseIsoMillis(item.deletedAt) ?: return@filter true
            (nowMs - deletedMs) < maxAge
        }
    }

    fun daysRemainingInBin(
        deletedAt: String,
        retentionDays: Int?,
        nowMs: Long = System.currentTimeMillis()
    ): Int? {
        if (retentionDays == null) return null
        val deletedMs = parseIsoMillis(deletedAt) ?: return retentionDays
        val elapsedDays = ((nowMs - deletedMs) / MS_PER_DAY).toInt().coerceAtLeast(0)
        return (retentionDays - elapsedDays).coerceAtLeast(0)
    }

    fun buildShareSummary(
        snapshot: LedgerSnapshot,
        appName: String,
        labelId: String?,
        monthKey: String?
    ): String {
        val targetLabel = labelId?.let { id -> snapshot.labels.firstOrNull { it.id == id } }
        val filtered = snapshot.expenses.filter { exp ->
            val matchExpense = exp.entryType == EntryType.EXPENSE
            val matchLabel = targetLabel == null || exp.labelIds.contains(targetLabel.id)
            val matchMonth = monthKey.isNullOrBlank() || exp.createdAt.startsWith(monthKey)
            matchExpense && matchLabel && matchMonth
        }
        val paid = filtered.filter { it.status == ExpenseStatus.PAID }.sumOf { it.balance }
        val hold = filtered.filter { it.status == ExpenseStatus.HOLD }.sumOf { it.balance }
        val need = filtered.filter { it.status == ExpenseStatus.NEED }.sumOf { it.balance }
        val total = paid + hold + need
        val scopeName = targetLabel?.name ?: "All Expenses"
        val periodName = if (monthKey.isNullOrBlank()) "All Time" else monthKey

        return buildString {
            appendLine("$appName — Ledger Summary")
            appendLine("Scope: $scopeName · Period: $periodName")
            appendLine("Paid: ₹${formatAmt(paid)} | Hold: ₹${formatAmt(hold)} | Need: ₹${formatAmt(need)}")
            appendLine("Grand Commitment: ₹${formatAmt(total)} (${filtered.size} entries)")
            appendLine("---")
            if (filtered.isEmpty()) {
                appendLine("No matching expense entries.")
            } else {
                filtered.forEachIndexed { idx, exp ->
                    val tagNames = exp.labelIds.mapNotNull { id -> snapshot.labels.firstOrNull { it.id == id }?.name }
                    val tagSuffix = if (tagNames.isNotEmpty()) " [" + tagNames.joinToString(", ") + "]" else ""
                    appendLine("${idx + 1}. [${exp.status.label.uppercase()} · ${exp.paymentMode.label}] ₹${formatAmt(exp.balance)} — ${exp.quote}$tagSuffix")
                }
            }
        }.trimEnd()
    }

    private fun escapeCsv(raw: String): String {
        return if (raw.contains(",") || raw.contains("\"") || raw.contains("\n") || raw.contains("\r")) {
            "\"" + raw.replace("\"", "\"\"") + "\""
        } else {
            raw
        }
    }

    fun generateCsv(snapshot: LedgerSnapshot): String {
        val header = listOf(
            "ID", "Created", "Updated", "Balance (INR)", "Status",
            "Entry Type", "Payment Mode", "Pinned", "Due Date",
            "Quote", "Labels", "Attached Lists", "Math Tape"
        )
        return buildString {
            append(header.joinToString(",") { escapeCsv(it) })
            snapshot.expenses.forEach { exp ->
                append("\r\n")
                val lblNames = exp.labelIds.mapNotNull { id -> snapshot.labels.firstOrNull { it.id == id }?.name }.joinToString(" | ")
                val lstNames = exp.attachedListIds.mapNotNull { id -> snapshot.lists.firstOrNull { it.id == id }?.title }.joinToString(" | ")
                val cols = listOf(
                    exp.id,
                    exp.createdAt,
                    exp.updatedAt,
                    formatAmt(exp.balance),
                    exp.status.label,
                    exp.entryType.label,
                    exp.paymentMode.label,
                    if (exp.isPinned) "Yes" else "No",
                    exp.dueDate ?: "",
                    exp.quote,
                    lblNames,
                    lstNames,
                    exp.calcFormula ?: ""
                )
                append(cols.joinToString(",") { escapeCsv(it) })
            }
        }
    }

    private fun parseCsvRows(csvText: String): List<List<String>> {
        val clean = csvText.trimStart('\uFEFF')
        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < clean.length) {
            val ch = clean[i]
            if (inQuotes) {
                if (ch == '\"') {
                    if (i + 1 < clean.length && clean[i + 1] == '\"') {
                        cell.append('\"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    cell.append(ch)
                }
            } else {
                when (ch) {
                    '\"' -> inQuotes = true
                    ',' -> {
                        currentRow.add(cell.toString())
                        cell.clear()
                    }
                    '\r' -> {
                        if (i + 1 < clean.length && clean[i + 1] == '\n') i++
                        currentRow.add(cell.toString())
                        cell.clear()
                        if (currentRow.any { it.isNotBlank() }) rows.add(currentRow.toList())
                        currentRow.clear()
                    }
                    '\n' -> {
                        currentRow.add(cell.toString())
                        cell.clear()
                        if (currentRow.any { it.isNotBlank() }) rows.add(currentRow.toList())
                        currentRow.clear()
                    }
                    else -> cell.append(ch)
                }
            }
            i++
        }
        if (cell.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(cell.toString())
            if (currentRow.any { it.isNotBlank() }) rows.add(currentRow.toList())
        }
        return rows
    }

    fun parseCsvImport(csvText: String, existingLabels: List<LabelRecord>): CsvImportResult {
        val rows = parseCsvRows(csvText)
        if (rows.size < 2) {
            return CsvImportResult(emptyList(), emptyList(), 0, listOf("CSV needs header and at least 1 row."))
        }
        val headers = rows.first().map { it.trim().lowercase(Locale.US) }
        fun findCol(keys: List<String>): Int = headers.indexOfFirst { h -> keys.any { k -> h == k || h.contains(k) } }
        val colBal = findCol(listOf("balance", "amount", "value", "inr", "total"))
        if (colBal < 0) {
            return CsvImportResult(emptyList(), emptyList(), rows.size - 1, listOf("Missing Balance/Amount column."))
        }
        val colQuote = findCol(listOf("quote", "description", "title", "merchant", "note"))
        val colStatus = findCol(listOf("status", "state"))
        val colType = findCol(listOf("entry type", "type", "kind"))
        val colMode = findCol(listOf("payment mode", "mode", "wallet", "account"))
        val colPin = findCol(listOf("pinned", "pin"))
        val colDue = findCol(listOf("due date", "due"))
        val colLabels = findCol(listOf("labels", "category", "tags"))

        val labelMap = existingLabels.associateBy { it.name.lowercase(Locale.US) }.toMutableMap()
        val newLabels = mutableListOf<LabelRecord>()
        val validEntries = mutableListOf<ExpenseRecord>()
        val errors = mutableListOf<String>()
        var skipped = 0
        val now = nowIso()
        val palette = listOf("#2563EB", "#059669", "#D97706", "#7C3AED", "#DC2626", "#0D9488")

        for (r in 1 until rows.size) {
            val cols = rows[r]
            val rawAmt = cols.getOrNull(colBal).orEmpty().replace(Regex("""[₹,\s]"""), "")
            val amt = rawAmt.toDoubleOrNull()
            if (amt == null || amt <= 0.0) {
                skipped++
                errors.add("Row ${r + 1}: invalid amount")
                continue
            }
            val quote = cols.getOrNull(colQuote)?.trim().orEmpty().ifBlank { "Imported Row $r" }
            val entryType = EntryType.fromString(cols.getOrNull(colType).orEmpty())
            val status = if (entryType == EntryType.INCOME) ExpenseStatus.PAID else ExpenseStatus.fromString(cols.getOrNull(colStatus).orEmpty())
            val mode = PaymentMode.fromString(cols.getOrNull(colMode).orEmpty())
            val pinRaw = cols.getOrNull(colPin).orEmpty().trim().lowercase(Locale.US)
            val isPinned = pinRaw == "yes" || pinRaw == "true" || pinRaw == "1"
            val dueRaw = cols.getOrNull(colDue)?.trim().orEmpty()
            val dueDate = if (Regex("""^\d{4}-\d{2}-\d{2}$""").matches(dueRaw)) dueRaw else null

            val rowLblIds = mutableListOf<String>()
            val rawLblStr = cols.getOrNull(colLabels).orEmpty()
            if (rawLblStr.isNotBlank()) {
                rawLblStr.split("|", ";", ",").map { it.trim() }.filter { it.isNotBlank() }.forEach { tok ->
                    val norm = if (tok.startsWith("#")) tok else "#$tok"
                    val key = norm.lowercase(Locale.US)
                    var lbl = labelMap[key]
                    if (lbl == null) {
                        val color = palette[(existingLabels.size + newLabels.size) % palette.size]
                        lbl = LabelRecord(
                            name = norm,
                            description = "Created from CSV import",
                            colorHex = color,
                            createdAt = now,
                            updatedAt = now,
                            auditLog = listOf(AuditEntry(action = "Created", at = now, summary = "CSV import label $norm"))
                        )
                        labelMap[key] = lbl
                        newLabels.add(lbl)
                    }
                    if (!rowLblIds.contains(lbl.id)) rowLblIds.add(lbl.id)
                }
            }

            validEntries.add(
                ExpenseRecord(
                    balance = amt,
                    quote = quote,
                    status = status,
                    labelIds = rowLblIds,
                    createdAt = now,
                    updatedAt = now,
                    auditLog = listOf(AuditEntry(action = "Created", at = now, summary = "Imported CSV ₹${formatAmt(amt)} (${mode.label})")),
                    entryType = entryType,
                    paymentMode = mode,
                    isPinned = isPinned,
                    dueDate = dueDate
                )
            )
        }
        return CsvImportResult(validEntries, newLabels, skipped, errors)
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
                val decoded = decodeSnapshot(raw)
                val pruned = LedgerMath.pruneExpiredBin(decoded.bin, decoded.binRetentionDays)
                return if (pruned.size != decoded.bin.size) {
                    val cleaned = decoded.copy(bin = pruned)
                    saveSnapshot(cleaned)
                    cleaned
                } else {
                    decoded
                }
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

    fun encodeLabel(l: LabelRecord): JSONObject = JSONObject().apply {
        put("id", l.id)
        put("name", l.name)
        put("description", l.description)
        put("colorHex", l.colorHex)
        put("monthlyBudget", l.monthlyBudget ?: JSONObject.NULL)
        put("createdAt", l.createdAt)
        put("updatedAt", l.updatedAt)
        put("auditLog", encodeAuditList(l.auditLog))
    }

    fun encodeListNote(n: ListNoteRecord): JSONObject = JSONObject().apply {
        put("id", n.id)
        put("title", n.title)
        put("content", n.content)
        put("createdAt", n.createdAt)
        put("updatedAt", n.updatedAt)
        put("auditLog", encodeAuditList(n.auditLog))
    }

    fun encodeExpense(e: ExpenseRecord): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("balance", e.balance)
        put("quote", e.quote)
        put("status", e.status.label)
        put("entryType", e.entryType.label)
        put("paymentMode", e.paymentMode.label)
        put("isPinned", e.isPinned)
        put("dueDate", e.dueDate ?: JSONObject.NULL)
        put("labelIds", JSONArray(e.labelIds))
        put("attachedListIds", JSONArray(e.attachedListIds))
        put("calcHistoryId", e.calcHistoryId ?: JSONObject.NULL)
        put("calcFormula", e.calcFormula ?: JSONObject.NULL)
        put("createdAt", e.createdAt)
        put("updatedAt", e.updatedAt)
        put("auditLog", encodeAuditList(e.auditLog))
    }

    fun encodeTemplate(t: ExpenseTemplateRecord): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("title", t.title)
        put("balance", t.balance)
        put("defaultStatus", t.defaultStatus.label)
        put("entryType", t.entryType.label)
        put("paymentMode", t.paymentMode.label)
        put("labelIds", JSONArray(t.labelIds))
        put("attachedListIds", JSONArray(t.attachedListIds))
        put("recurrence", t.recurrence.label)
        put("lastUsedAt", t.lastUsedAt ?: JSONObject.NULL)
        put("createdAt", t.createdAt)
    }

    fun encodeSnapshot(snapshot: LedgerSnapshot): String {
        val root = JSONObject()
        val labelsArr = JSONArray().apply { snapshot.labels.forEach { put(encodeLabel(it)) } }
        val listsArr = JSONArray().apply { snapshot.lists.forEach { put(encodeListNote(it)) } }
        val expArr = JSONArray().apply { snapshot.expenses.forEach { put(encodeExpense(it)) } }
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
        val tplArr = JSONArray().apply { snapshot.templates.forEach { put(encodeTemplate(it)) } }
        val binArr = JSONArray().apply {
            snapshot.bin.forEach { b ->
                put(JSONObject().apply {
                    put("id", b.id)
                    put("entityType", b.entityType.key)
                    put("title", b.title)
                    put("subtitle", b.subtitle)
                    put("amount", b.amount ?: JSONObject.NULL)
                    put("deletedAt", b.deletedAt)
                    put("payload", JSONObject(b.payloadJson))
                })
            }
        }
        root.put("labels", labelsArr)
        root.put("lists", listsArr)
        root.put("expenses", expArr)
        root.put("calculatorHistory", calcArr)
        root.put("templates", tplArr)
        root.put("bin", binArr)
        root.put("binRetentionDays", snapshot.binRetentionDays ?: JSONObject.NULL)
        root.put("privacyPinHash", snapshot.privacyPinHash ?: JSONObject.NULL)
        root.put("hideBalances", snapshot.hideBalances)
        return root.toString()
    }

    fun decodeLabel(o: JSONObject): LabelRecord {
        val createdAt = o.optString("createdAt")
        val audit = decodeAuditList(o.optJSONArray("auditLog"))
        val updatedAt = o.optString("updatedAt").ifBlank { audit.lastOrNull()?.at ?: createdAt }
        val rawBudget = if (o.isNull("monthlyBudget")) null else o.optDouble("monthlyBudget", -1.0).takeIf { it > 0.0 }
        return LabelRecord(
            id = o.optString("id"),
            name = o.optString("name"),
            description = o.optString("description"),
            colorHex = o.optString("colorHex", "#2563EB"),
            monthlyBudget = rawBudget,
            createdAt = createdAt,
            updatedAt = updatedAt,
            auditLog = audit
        )
    }

    fun decodeListNote(o: JSONObject): ListNoteRecord {
        val createdAt = o.optString("createdAt")
        val audit = decodeAuditList(o.optJSONArray("auditLog"))
        val updatedAt = o.optString("updatedAt").ifBlank { audit.lastOrNull()?.at ?: createdAt }
        return ListNoteRecord(
            id = o.optString("id"),
            title = o.optString("title"),
            content = o.optString("content"),
            createdAt = createdAt,
            updatedAt = updatedAt,
            auditLog = audit
        )
    }

    fun decodeExpense(o: JSONObject): ExpenseRecord {
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
        val dueDate = if (o.isNull("dueDate")) null else o.optString("dueDate").takeIf { it.isNotBlank() }
        val createdAt = o.optString("createdAt")
        val audit = decodeAuditList(o.optJSONArray("auditLog"))
        val updatedAt = o.optString("updatedAt").ifBlank { audit.lastOrNull()?.at ?: createdAt }

        return ExpenseRecord(
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
            auditLog = audit,
            entryType = EntryType.fromString(o.optString("entryType", "Expense")),
            paymentMode = PaymentMode.fromString(o.optString("paymentMode", "UPI")),
            isPinned = o.optBoolean("isPinned", false),
            dueDate = dueDate
        )
    }

    fun decodeTemplate(o: JSONObject): ExpenseTemplateRecord {
        val lblIds = mutableListOf<String>()
        val lArr = o.optJSONArray("labelIds") ?: JSONArray()
        for (j in 0 until lArr.length()) lblIds.add(lArr.optString(j))
        val attIds = mutableListOf<String>()
        val aArr = o.optJSONArray("attachedListIds") ?: JSONArray()
        for (j in 0 until aArr.length()) attIds.add(aArr.optString(j))
        val lastUsed = if (o.isNull("lastUsedAt")) null else o.optString("lastUsedAt").takeIf { it.isNotBlank() }
        return ExpenseTemplateRecord(
            id = o.optString("id"),
            title = o.optString("title"),
            balance = o.optDouble("balance", 0.0),
            defaultStatus = ExpenseStatus.fromString(o.optString("defaultStatus", "Need")),
            labelIds = lblIds,
            attachedListIds = attIds,
            recurrence = RecurrenceCycle.fromString(o.optString("recurrence", "None")),
            lastUsedAt = lastUsed,
            createdAt = o.optString("createdAt"),
            entryType = EntryType.fromString(o.optString("entryType", "Expense")),
            paymentMode = PaymentMode.fromString(o.optString("paymentMode", "UPI"))
        )
    }

    fun decodeSnapshot(raw: String): LedgerSnapshot {
        return try {
            val root = JSONObject(raw)
            val labels = mutableListOf<LabelRecord>()
            val labelsArr = root.optJSONArray("labels") ?: JSONArray()
            for (i in 0 until labelsArr.length()) {
                val o = labelsArr.optJSONObject(i) ?: continue
                labels.add(decodeLabel(o))
            }

            val lists = mutableListOf<ListNoteRecord>()
            val listsArr = root.optJSONArray("lists") ?: JSONArray()
            for (i in 0 until listsArr.length()) {
                val o = listsArr.optJSONObject(i) ?: continue
                lists.add(decodeListNote(o))
            }

            val expenses = mutableListOf<ExpenseRecord>()
            val expArr = root.optJSONArray("expenses") ?: JSONArray()
            for (i in 0 until expArr.length()) {
                val o = expArr.optJSONObject(i) ?: continue
                expenses.add(decodeExpense(o))
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

            val templates = mutableListOf<ExpenseTemplateRecord>()
            val tplArr = root.optJSONArray("templates") ?: JSONArray()
            for (i in 0 until tplArr.length()) {
                val o = tplArr.optJSONObject(i) ?: continue
                templates.add(decodeTemplate(o))
            }

            val retention: Int? = if (!root.has("binRetentionDays")) {
                30
            } else if (root.isNull("binRetentionDays")) {
                null
            } else {
                val v = root.optInt("binRetentionDays", 30)
                if (v == 30 || v == 60 || v == 90) v else 30
            }

            val bin = mutableListOf<BinnedRecord>()
            val binArr = root.optJSONArray("bin") ?: JSONArray()
            for (i in 0 until binArr.length()) {
                val o = binArr.optJSONObject(i) ?: continue
                val payloadObj = o.optJSONObject("payload") ?: continue
                val amt = if (o.isNull("amount")) null else o.optDouble("amount", 0.0)
                bin.add(
                    BinnedRecord(
                        id = o.optString("id"),
                        entityType = BinnedEntityType.fromKey(o.optString("entityType", "expense")),
                        title = o.optString("title", "Deleted item"),
                        subtitle = o.optString("subtitle", ""),
                        amount = amt,
                        deletedAt = o.optString("deletedAt", LedgerMath.nowIso()),
                        payloadJson = payloadObj.toString()
                    )
                )
            }

            val pinHash = if (root.isNull("privacyPinHash")) null else root.optString("privacyPinHash").takeIf { it.isNotBlank() }
            val hideBalances = root.optBoolean("hideBalances", false)

            LedgerSnapshot(expenses, labels, lists, calcHistory, templates, LedgerMath.pruneExpiredBin(bin, retention), retention, pinHash, hideBalances)
        } catch (_: Exception) {
            createSeedSnapshot()
        }
    }

    fun validateBackupJson(rawJson: String): LedgerSnapshot? {
        return try {
            val root = JSONObject(rawJson)
            if (root.optJSONArray("expenses") == null ||
                root.optJSONArray("labels") == null ||
                root.optJSONArray("lists") == null
            ) {
                return null
            }
            val parsed = decodeSnapshot(rawJson)
            val validLabelIds = parsed.labels.map { it.id }.toSet()
            val validListIds = parsed.lists.map { it.id }.toSet()
            val normalizedExpenses = parsed.expenses.map { exp ->
                exp.copy(
                    labelIds = exp.labelIds.filter { validLabelIds.contains(it) },
                    attachedListIds = exp.attachedListIds.filter { validListIds.contains(it) }
                )
            }
            parsed.copy(expenses = normalizedExpenses)
        } catch (_: Exception) {
            null
        }
    }

    fun moveExpenseToBin(snapshot: LedgerSnapshot, expId: String): LedgerSnapshot {
        val target = snapshot.expenses.firstOrNull { it.id == expId } ?: return snapshot
        val record = BinnedRecord(
            entityType = BinnedEntityType.EXPENSE,
            title = target.quote,
            subtitle = "${target.status.label} · Created ${LedgerMath.formatCompactDate(target.createdAt)}",
            amount = target.balance,
            deletedAt = LedgerMath.nowIso(),
            payloadJson = encodeExpense(target).toString()
        )
        return snapshot.copy(
            expenses = snapshot.expenses.filterNot { it.id == expId },
            bin = listOf(record) + snapshot.bin
        )
    }

    fun moveExpensesBulkToBin(snapshot: LedgerSnapshot, expIds: Set<String>): LedgerSnapshot {
        if (expIds.isEmpty()) return snapshot
        val now = LedgerMath.nowIso()
        val targets = snapshot.expenses.filter { expIds.contains(it.id) }
        if (targets.isEmpty()) return snapshot
        val binned = targets.map { target ->
            BinnedRecord(
                entityType = BinnedEntityType.EXPENSE,
                title = target.quote,
                subtitle = "${target.status.label} · Created ${LedgerMath.formatCompactDate(target.createdAt)}",
                amount = target.balance,
                deletedAt = now,
                payloadJson = encodeExpense(target).toString()
            )
        }
        return snapshot.copy(
            expenses = snapshot.expenses.filterNot { expIds.contains(it.id) },
            bin = binned + snapshot.bin
        )
    }

    fun moveLabelToBin(snapshot: LedgerSnapshot, labelId: String): LedgerSnapshot {
        val target = snapshot.labels.firstOrNull { it.id == labelId } ?: return snapshot
        val now = LedgerMath.nowIso()
        val record = BinnedRecord(
            entityType = BinnedEntityType.LABEL,
            title = target.name,
            subtitle = target.description.ifBlank { "Relational label" },
            amount = null,
            deletedAt = now,
            payloadJson = encodeLabel(target).toString()
        )
        return snapshot.copy(
            labels = snapshot.labels.filterNot { it.id == labelId },
            expenses = snapshot.expenses.map { exp ->
                if (exp.labelIds.contains(labelId)) {
                    exp.copy(
                        labelIds = exp.labelIds.filterNot { it == labelId },
                        updatedAt = now,
                        auditLog = exp.auditLog + AuditEntry(action = "Updated", at = now, summary = "Unlinked binned label ${target.name}")
                    )
                } else exp
            },
            templates = snapshot.templates.map { tpl ->
                if (tpl.labelIds.contains(labelId)) tpl.copy(labelIds = tpl.labelIds.filterNot { it == labelId }) else tpl
            },
            bin = listOf(record) + snapshot.bin
        )
    }

    fun moveListToBin(snapshot: LedgerSnapshot, listId: String): LedgerSnapshot {
        val target = snapshot.lists.firstOrNull { it.id == listId } ?: return snapshot
        val now = LedgerMath.nowIso()
        val detected = LedgerMath.extractNumbersSum(target.content)
        val record = BinnedRecord(
            entityType = BinnedEntityType.LIST,
            title = target.title,
            subtitle = "Itemized list · Sum ₹${LedgerMath.formatAmt(detected)}",
            amount = if (detected > 0.0) detected else null,
            deletedAt = now,
            payloadJson = encodeListNote(target).toString()
        )
        return snapshot.copy(
            lists = snapshot.lists.filterNot { it.id == listId },
            expenses = snapshot.expenses.map { exp ->
                if (exp.attachedListIds.contains(listId)) exp.copy(attachedListIds = exp.attachedListIds.filterNot { it == listId }, updatedAt = now) else exp
            },
            templates = snapshot.templates.map { tpl ->
                if (tpl.attachedListIds.contains(listId)) tpl.copy(attachedListIds = tpl.attachedListIds.filterNot { it == listId }) else tpl
            },
            bin = listOf(record) + snapshot.bin
        )
    }

    fun moveTemplateToBin(snapshot: LedgerSnapshot, tplId: String): LedgerSnapshot {
        val target = snapshot.templates.firstOrNull { it.id == tplId } ?: return snapshot
        val record = BinnedRecord(
            entityType = BinnedEntityType.TEMPLATE,
            title = target.title,
            subtitle = "Template · ${target.recurrence.label}",
            amount = target.balance,
            deletedAt = LedgerMath.nowIso(),
            payloadJson = encodeTemplate(target).toString()
        )
        return snapshot.copy(
            templates = snapshot.templates.filterNot { it.id == tplId },
            bin = listOf(record) + snapshot.bin
        )
    }

    fun restoreFromBin(snapshot: LedgerSnapshot, record: BinnedRecord): LedgerSnapshot {
        val now = LedgerMath.nowIso()
        val remainingBin = snapshot.bin.filterNot { it.id == record.id }
        val activeLabelIds = snapshot.labels.map { it.id }.toSet()
        val activeListIds = snapshot.lists.map { it.id }.toSet()
        val payload = JSONObject(record.payloadJson)
        return when (record.entityType) {
            BinnedEntityType.EXPENSE -> {
                val raw = decodeExpense(payload)
                val restored = raw.copy(
                    labelIds = raw.labelIds.filter { activeLabelIds.contains(it) },
                    attachedListIds = raw.attachedListIds.filter { activeListIds.contains(it) },
                    updatedAt = now,
                    auditLog = raw.auditLog + AuditEntry(action = "Updated", at = now, summary = "Restored from Recycle Bin")
                )
                snapshot.copy(expenses = listOf(restored) + snapshot.expenses.filterNot { it.id == restored.id }, bin = remainingBin)
            }
            BinnedEntityType.LABEL -> {
                val raw = decodeLabel(payload)
                val restored = raw.copy(updatedAt = now, auditLog = raw.auditLog + AuditEntry(action = "Updated", at = now, summary = "Restored from Recycle Bin"))
                snapshot.copy(labels = snapshot.labels.filterNot { it.id == restored.id } + restored, bin = remainingBin)
            }
            BinnedEntityType.LIST -> {
                val raw = decodeListNote(payload)
                val restored = raw.copy(updatedAt = now, auditLog = raw.auditLog + AuditEntry(action = "Updated", at = now, summary = "Restored from Recycle Bin"))
                snapshot.copy(lists = listOf(restored) + snapshot.lists.filterNot { it.id == restored.id }, bin = remainingBin)
            }
            BinnedEntityType.TEMPLATE -> {
                val raw = decodeTemplate(payload)
                val restored = raw.copy(
                    labelIds = raw.labelIds.filter { activeLabelIds.contains(it) },
                    attachedListIds = raw.attachedListIds.filter { activeListIds.contains(it) }
                )
                snapshot.copy(templates = listOf(restored) + snapshot.templates.filterNot { it.id == restored.id }, bin = remainingBin)
            }
        }
    }

    private fun createSeedSnapshot(): LedgerSnapshot {
        val t1 = "2026-11-10T09:15:00.000Z"
        val t2 = "2026-11-14T08:30:00.000Z"
        val labels = listOf(
            LabelRecord("lbl-dr", "#Dr.", "Clinic consultations & pharmacy bills", "#2563EB", 2000.0, t1, t1, listOf(AuditEntry(action = "Created", at = t1, summary = "Seeded label"))),
            LabelRecord("lbl-school", "#School", "Tuition, craft supplies & fees", "#7C3AED", 1200.0, t1, t1, listOf(AuditEntry(action = "Created", at = t1, summary = "Seeded label"))),
            LabelRecord("lbl-vegetables", "#Vegetables", "Daily fresh market produce", "#059669", 800.0, t1, t1, listOf(AuditEntry(action = "Created", at = t1, summary = "Seeded label")))
        )
        val lists = listOf(
            ListNoteRecord("list-craft", "School Craft & Fees Breakdown", "Wood -> ₹5\nFees -> ₹10\nOthers -> ₹15", t2, t2, listOf(AuditEntry(action = "Created", at = t2, summary = "Created breakdown ₹30")))
        )
        val expenses = listOf(
            ExpenseRecord("exp-1", 850.0, "Pediatric checkup & vitamin prescription", ExpenseStatus.PAID, listOf("lbl-dr", "lbl-school"), emptyList(), null, null, t2, t2, listOf(AuditEntry(action = "Created", at = t2, summary = "Logged ₹850")), EntryType.EXPENSE, PaymentMode.UPI, false, null),
            ExpenseRecord("exp-2", 30.0, "Term project wood, fees & misc supplies", ExpenseStatus.HOLD, listOf("lbl-school"), listOf("list-craft"), "calc-1", "5 + 10 + 15 = 30", t2, t2, listOf(AuditEntry(action = "Created", at = t2, summary = "Logged ₹30 with attached list")), EntryType.EXPENSE, PaymentMode.CASH, false, null),
            ExpenseRecord("exp-3", 450.0, "Lab blood panel follow-up deposit", ExpenseStatus.NEED, listOf("lbl-dr"), emptyList(), null, null, t2, t2, listOf(AuditEntry(action = "Created", at = t2, summary = "Logged ₹450")), EntryType.EXPENSE, PaymentMode.CARD, true, "2026-11-18"),
            ExpenseRecord("exp-inc-1", 5000.0, "Freelance UI consultation payout", ExpenseStatus.PAID, emptyList(), emptyList(), null, null, t2, t2, listOf(AuditEntry(action = "Created", at = t2, summary = "Logged ₹5000 income")), EntryType.INCOME, PaymentMode.BANK, false, null)
        )
        val calcHistory = listOf(
            CalcHistoryRecord("calc-1", "5 + 10 + 15", 30.0, t2, t2)
        )
        val templates = listOf(
            ExpenseTemplateRecord("tpl-veg", "Weekly Produce Basket", 120.0, ExpenseStatus.PAID, listOf("lbl-vegetables"), emptyList(), RecurrenceCycle.WEEKLY, null, t1, EntryType.EXPENSE, PaymentMode.CASH),
            ExpenseTemplateRecord("tpl-clinic", "Monthly Clinic Checkup", 500.0, ExpenseStatus.NEED, listOf("lbl-dr"), emptyList(), RecurrenceCycle.MONTHLY, null, t1, EntryType.EXPENSE, PaymentMode.UPI)
        )
        return LedgerSnapshot(expenses, labels, lists, calcHistory, templates, emptyList(), 30, null, false)
    }
}
