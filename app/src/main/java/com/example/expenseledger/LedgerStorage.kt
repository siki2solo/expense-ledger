package com.example.expenseledger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

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

enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromKey(value: String): ThemeMode =
            entries.firstOrNull { it.key.equals(value, ignoreCase = true) } ?: SYSTEM
    }
}

enum class DateWindowFilter(val label: String) {
    ALL("All Dates"),
    TODAY("Today"),
    SEVEN_DAYS("7D"),
    MONTH("Month")
}

enum class CalendarWeekStart(val key: String, val label: String) {
    MON("mon", "Mon ISO"),
    SUN("sun", "Sun"),
    SAT("sat", "Sat");

    companion object {
        fun fromKey(value: String): CalendarWeekStart =
            entries.firstOrNull { it.key.equals(value, ignoreCase = true) } ?: MON
    }
}

enum class CalendarRegionCode(val key: String, val label: String) {
    GLOBAL("global", "Global UN"),
    IN("in", "India (IN)"),
    US("us", "United States (US)"),
    UK("uk", "United Kingdom (UK)"),
    EU("eu", "Eurozone (EU)"),
    AE("ae", "UAE (AE)"),
    JP("jp", "Japan (JP)");

    companion object {
        fun fromKey(value: String): CalendarRegionCode =
            entries.firstOrNull { it.key.equals(value, ignoreCase = true) } ?: IN
    }
}

data class CalendarPreferences(
    val weekStart: CalendarWeekStart = CalendarWeekStart.MON,
    val showIsoWeeks: Boolean = true,
    val region: CalendarRegionCode = CalendarRegionCode.IN
)

data class InternationalHoliday(
    val dateKey: String,
    val name: String,
    val region: CalendarRegionCode
)

data class AutoLabelRule(
    val id: String = "rule-" + UUID.randomUUID().toString().take(8),
    val keyword: String,
    val labelIds: List<String> = emptyList(),
    val defaultPaymentMode: PaymentMode? = null,
    val enabled: Boolean = true
)

data class ExpenseSplitShare(
    val id: String = "sp-" + UUID.randomUUID().toString().take(8),
    val friendName: String,
    val amount: Double
)

data class IouRepaymentRecord(
    val id: String = "rep-" + UUID.randomUUID().toString().take(8),
    val friendName: String,
    val amount: Double,
    val walletMode: PaymentMode,
    val at: String,
    val note: String = ""
)

data class FriendIouSummary(
    val friendName: String,
    val totalShared: Double,
    val totalRepaid: Double,
    val outstanding: Double,
    val itemCount: Int
)

data class SavingsGoalItem(
    val id: String = "goal-" + UUID.randomUUID().toString().take(8),
    val title: String,
    val targetAmount: Double,
    val targetDate: String? = null,
    val monthlyTarget: Double? = null,
    val colorHex: String = "#059669",
    val createdAt: String
)

data class GoalTransaction(
    val id: String = "gtx-" + UUID.randomUUID().toString().take(8),
    val goalId: String,
    val kind: String, // "deposit" or "withdraw"
    val amount: Double,
    val walletMode: PaymentMode,
    val at: String,
    val note: String = ""
)

data class GoalProgressSummary(
    val goal: SavingsGoalItem,
    val savedAmount: Double,
    val pct: Int,
    val remaining: Double
)

data class ForecastItem(
    val dateKey: String,
    val title: String,
    val amount: Double,
    val kind: String // "due" or "recurring"
)

data class CashflowForecast30d(
    val liquidCash: Double,
    val projectedOutflow: Double,
    val projectedBalance: Double,
    val shortfallAmount: Double,
    val items: List<ForecastItem>
)

data class AuditEntry(
    val id: String = UUID.randomUUID().toString(),
    val action: String, // "Created" or "Updated"
    val at: String,
    val summary: String = ""
)

data class PartialPaymentRecord(
    val id: String = "pp-" + UUID.randomUUID().toString().take(8),
    val amount: Double,
    val mode: PaymentMode,
    val at: String,
    val note: String = ""
)

data class ReceiptAttachmentRecord(
    val name: String,
    val uriOrDataUrl: String,
    val attachedAt: String
)

data class WalletTransferRecord(
    val id: String = "trf-" + UUID.randomUUID().toString().take(8),
    val fromMode: PaymentMode,
    val toMode: PaymentMode,
    val amount: Double,
    val note: String,
    val at: String
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
    val dueDate: String? = null,
    val paidAmount: Double = 0.0,
    val partialPayments: List<PartialPaymentRecord> = emptyList(),
    val receiptAttachment: ReceiptAttachmentRecord? = null,
    val splitShares: List<ExpenseSplitShare> = emptyList()
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
    val hideBalances: Boolean = false,
    val biometricEnabled: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dueRemindersEnabled: Boolean = true,
    val walletStartingBalances: Map<PaymentMode, Double> = emptyMap(),
    val walletTransfers: List<WalletTransferRecord> = emptyList(),
    val calendarPrefs: CalendarPreferences = CalendarPreferences(),
    val autoLabelRules: List<AutoLabelRule> = emptyList(),
    val iouRepayments: List<IouRepaymentRecord> = emptyList(),
    val savingsGoals: List<SavingsGoalItem> = emptyList(),
    val goalTransactions: List<GoalTransaction> = emptyList(),
    val autoLockSeconds: Int = 0
)

data class CalendarDayCell(
    val dateKey: String,
    val day: Int,
    val total: Double,
    val expenses: List<ExpenseRecord>
)

data class InternationalCalendarDayCell(
    val dateKey: String,
    val day: Int,
    val expenseTotal: Double,
    val incomeTotal: Double,
    val expenses: List<ExpenseRecord>,
    val dueBills: List<ExpenseRecord>,
    val projectedTemplates: List<ExpenseTemplateRecord>,
    val holidays: List<InternationalHoliday>,
    val transfers: List<WalletTransferRecord>,
    val repayments: List<IouRepaymentRecord>,
    val goalTargets: List<SavingsGoalItem>
)

data class InternationalCalendarMonth(
    val monthKey: String,
    val leadingBlanks: Int,
    val daysInMonth: Int,
    val weekHeaders: List<String>,
    val rowWeekNumbers: List<Int>,
    val cells: List<InternationalCalendarDayCell>,
    val monthExpenseTotal: Double,
    val monthIncomeTotal: Double
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

data class LiveWalletBalanceRow(
    val mode: PaymentMode,
    val startingBalance: Double,
    val incomeTotal: Double,
    val cashOutflow: Double,
    val transfersIn: Double,
    val transfersOut: Double,
    val repaymentsIn: Double,
    val liveBalance: Double
)

data class MerchantSummary(
    val displayName: String,
    val count: Int,
    val totalSpent: Double,
    val avgSpent: Double,
    val primaryMode: PaymentMode
)

data class MerchantSuggestion(
    val merchantKey: String,
    val quote: String,
    val lastBalance: Double,
    val paymentMode: PaymentMode,
    val entryType: EntryType,
    val status: ExpenseStatus,
    val labelIds: List<String>,
    val count: Int
)

data class DueAlertItem(
    val expense: ExpenseRecord,
    val remaining: Double,
    val statusLabel: String,
    val severity: String
)

data class DueAlertSummary(
    val overdueCount: Int,
    val dueTodayCount: Int,
    val upcomingCount: Int,
    val items: List<DueAlertItem>
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
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = parser.parse(iso.take(19)) ?: return iso
            SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.getDefault()).format(date)
        } catch (_: Exception) {
            iso
        }
    }

    fun formatCompactDate(iso: String): String {
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
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

    fun formatPlainAmt(value: Double): String {
        val rounded = kotlin.math.round(value * 100.0) / 100.0
        return if (rounded % 1.0 == 0.0) {
            rounded.toLong().toString()
        } else {
            String.format(Locale.US, "%.2f", rounded)
        }
    }

    fun formatAmt(value: Double): String {
        val rounded = kotlin.math.round(value * 100.0) / 100.0
        val sign = if (rounded < 0.0) "-" else ""
        val absVal = kotlin.math.abs(rounded)
        val raw = if (absVal % 1.0 == 0.0) {
            absVal.toLong().toString()
        } else {
            String.format(Locale.US, "%.2f", absVal).trimEnd('0').trimEnd('.')
        }
        val parts = raw.split(".")
        val intPart = parts[0]
        val decPart = if (parts.size > 1 && parts[1].isNotEmpty()) ".${parts[1]}" else ""
        if (intPart.length <= 3) return "$sign$intPart$decPart"
        val lastThree = intPart.takeLast(3)
        val head = intPart.dropLast(3)
        val groupedHead = head.reversed().chunked(2).joinToString(",").reversed()
        return "$sign$groupedHead,$lastThree$decPart"
    }

    fun formatDisplayAmt(value: Double, hideBalances: Boolean): String {
        return if (hideBalances) "••••" else formatAmt(value)
    }

    fun sha256Pin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = ("elpin-v2:" + pin.trim()).toByteArray(Charsets.UTF_8)
        repeat(10_000) {
            digest.reset()
            bytes = digest.digest(bytes)
        }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun verifyPin(pin: String, expectedHash: String?): Boolean {
        if (expectedHash.isNullOrBlank()) return true
        val target = expectedHash.lowercase(Locale.US)
        if (sha256Pin(pin) == target) return true
        val digest = MessageDigest.getInstance("SHA-256")
        val legacy = digest.digest(("elpin-v1:" + pin.trim()).toByteArray(Charsets.UTF_8))
        return legacy.joinToString("") { "%02x".format(it) } == target
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

    fun expensePaidPart(exp: ExpenseRecord): Double {
        if (exp.entryType == EntryType.INCOME) return 0.0
        val bal = exp.balance.coerceAtLeast(0.0)
        return if (exp.status == ExpenseStatus.PAID) bal else exp.paidAmount.coerceIn(0.0, bal)
    }

    fun expenseRemainingPart(exp: ExpenseRecord): Double {
        if (exp.entryType == EntryType.INCOME) return 0.0
        return (exp.balance.coerceAtLeast(0.0) - expensePaidPart(exp)).coerceAtLeast(0.0)
    }

    fun trackedPartialSum(exp: ExpenseRecord): Double {
        return kotlin.math.round(exp.partialPayments.sumOf { it.amount.coerceAtLeast(0.0) } * 100.0) / 100.0
    }

    fun cycleStatus(exp: ExpenseRecord): ExpenseRecord {
        if (exp.entryType == EntryType.INCOME) return exp
        val now = nowIso()
        val nextStatus = exp.status.next()
        val nextPaid = if (nextStatus == ExpenseStatus.PAID) {
            exp.balance
        } else {
            minOf(exp.balance, trackedPartialSum(exp))
        }
        return exp.copy(
            status = nextStatus,
            paidAmount = nextPaid,
            updatedAt = now,
            auditLog = exp.auditLog + AuditEntry(
                action = "Updated",
                at = now,
                summary = "Status changed from ${exp.status.label} to ${nextStatus.label}"
            )
        )
    }

    fun computeCashflowSummary(expenses: List<ExpenseRecord>, monthKey: String? = null): CashflowSummary {
        val scope = if (monthKey.isNullOrBlank()) expenses else expenses.filter { it.createdAt.startsWith(monthKey) }
        val incomeItems = scope.filter { it.entryType == EntryType.INCOME }
        val expItems = scope.filter { it.entryType == EntryType.EXPENSE }
        val totalIncome = incomeItems.sumOf { it.balance }
        val paidExp = expItems.sumOf { expensePaidPart(it) }
        val unpaid = expItems.sumOf { expenseRemainingPart(it) }
        return CashflowSummary(
            totalIncome = totalIncome,
            paidExpenses = paidExp,
            unpaidObligations = unpaid,
            netCashflow = totalIncome - paidExp,
            incomeCount = incomeItems.size,
            expenseCount = expItems.size
        )
    }

    fun recordPartialPayment(
        snapshot: LedgerSnapshot,
        expenseId: String,
        amount: Double,
        mode: PaymentMode,
        note: String = ""
    ): LedgerSnapshot {
        val cleanAmt = kotlin.math.round(amount * 100.0) / 100.0
        if (cleanAmt <= 0.0) return snapshot
        val now = nowIso()
        val updatedExpenses = snapshot.expenses.map { item ->
            if (item.id != expenseId || item.entryType == EntryType.INCOME) {
                item
            } else {
                val curPaid = expensePaidPart(item)
                val remaining = expenseRemainingPart(item)
                if (remaining <= 0.0) {
                    item
                } else {
                    val applied = kotlin.math.round(minOf(remaining, cleanAmt) * 100.0) / 100.0
                    val nextPaid = kotlin.math.round(minOf(item.balance, curPaid + applied) * 100.0) / 100.0
                    val nextRem = (item.balance - nextPaid).coerceAtLeast(0.0)
                    val settled = nextRem <= 0.009
                    val nextStatus = if (settled) ExpenseStatus.PAID else if (item.status == ExpenseStatus.PAID) ExpenseStatus.NEED else item.status
                    val entry = PartialPaymentRecord(amount = applied, mode = mode, at = now, note = note.trim())
                    val summary = if (settled) {
                        "Final settlement ₹${formatAmt(applied)} via ${mode.label} (Paid)"
                    } else {
                        "Partial pay ₹${formatAmt(applied)} via ${mode.label} (₹${formatAmt(nextRem)} left)"
                    }
                    item.copy(
                        status = nextStatus,
                        paidAmount = if (settled) item.balance else nextPaid,
                        partialPayments = item.partialPayments + entry,
                        updatedAt = now,
                        auditLog = item.auditLog + AuditEntry(action = "Updated", at = now, summary = summary)
                    )
                }
            }
        }
        return snapshot.copy(expenses = updatedExpenses)
    }

    fun computeLiveWalletBalances(snapshot: LedgerSnapshot): List<LiveWalletBalanceRow> {
        val incomeMap = PaymentMode.entries.associateWith { 0.0 }.toMutableMap()
        val outflowMap = PaymentMode.entries.associateWith { 0.0 }.toMutableMap()
        val tInMap = PaymentMode.entries.associateWith { 0.0 }.toMutableMap()
        val tOutMap = PaymentMode.entries.associateWith { 0.0 }.toMutableMap()

        snapshot.expenses.forEach { exp ->
            if (exp.entryType == EntryType.INCOME) {
                incomeMap[exp.paymentMode] = (incomeMap[exp.paymentMode] ?: 0.0) + exp.balance.coerceAtLeast(0.0)
            } else {
                var tracked = 0.0
                exp.partialPayments.forEach { pp ->
                    val amt = pp.amount.coerceAtLeast(0.0)
                    outflowMap[pp.mode] = (outflowMap[pp.mode] ?: 0.0) + amt
                    tracked += amt
                }
                val paidPart = expensePaidPart(exp)
                val unattributed = (paidPart - tracked).coerceAtLeast(0.0)
                if (unattributed > 0.0) {
                    outflowMap[exp.paymentMode] = (outflowMap[exp.paymentMode] ?: 0.0) + unattributed
                }
            }
        }

        snapshot.walletTransfers.forEach { trf ->
            if (trf.amount > 0.0 && trf.fromMode != trf.toMode) {
                tOutMap[trf.fromMode] = (tOutMap[trf.fromMode] ?: 0.0) + trf.amount
                tInMap[trf.toMode] = (tInMap[trf.toMode] ?: 0.0) + trf.amount
            }
        }

        val repInMap = PaymentMode.entries.associateWith { 0.0 }.toMutableMap()
        snapshot.iouRepayments.forEach { rep ->
            if (rep.amount > 0.0) {
                repInMap[rep.walletMode] = (repInMap[rep.walletMode] ?: 0.0) + rep.amount
            }
        }

        return PaymentMode.entries.map { mode ->
            val startBal = snapshot.walletStartingBalances[mode] ?: 0.0
            val inc = incomeMap[mode] ?: 0.0
            val out = outflowMap[mode] ?: 0.0
            val tIn = tInMap[mode] ?: 0.0
            val tOut = tOutMap[mode] ?: 0.0
            val rIn = repInMap[mode] ?: 0.0
            LiveWalletBalanceRow(
                mode = mode,
                startingBalance = startBal,
                incomeTotal = inc,
                cashOutflow = out,
                transfersIn = tIn,
                transfersOut = tOut,
                repaymentsIn = rIn,
                liveBalance = kotlin.math.round((startBal + inc + rIn - out - tOut + tIn) * 100.0) / 100.0
            )
        }
    }

    fun getIsoWeekNumber(dateKey: String): Int {
        return try {
            val parts = dateKey.split("-")
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.firstDayOfWeek = Calendar.MONDAY
            cal.minimalDaysInFirstWeek = 4
            cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 0, 0, 0)
            cal.get(Calendar.WEEK_OF_YEAR)
        } catch (_: Exception) {
            1
        }
    }

    fun getInternationalHolidaysForMonth(monthKey: String, region: CalendarRegionCode): List<InternationalHoliday> {
        val parts = monthKey.split("-")
        val year = parts.getOrNull(0) ?: return emptyList()
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return emptyList()
        val fixed = mutableListOf<Triple<String, String, CalendarRegionCode>>()
        when (m) {
            1 -> {
                fixed.add(Triple("$year-01-01", "New Year's Day", CalendarRegionCode.GLOBAL))
                fixed.add(Triple("$year-01-26", "Republic Day", CalendarRegionCode.IN))
            }
            2 -> fixed.add(Triple("$year-02-11", "National Foundation Day", CalendarRegionCode.JP))
            5 -> {
                fixed.add(Triple("$year-05-01", "Labour Day", CalendarRegionCode.GLOBAL))
                fixed.add(Triple("$year-05-09", "Europe Day", CalendarRegionCode.EU))
            }
            7 -> fixed.add(Triple("$year-07-04", "Independence Day", CalendarRegionCode.US))
            8 -> {
                fixed.add(Triple("$year-08-15", "Independence Day", CalendarRegionCode.IN))
                fixed.add(Triple("$year-08-11", "Mountain Day", CalendarRegionCode.JP))
            }
            10 -> {
                fixed.add(Triple("$year-10-02", "Gandhi Jayanti", CalendarRegionCode.IN))
                fixed.add(Triple("$year-10-31", "World Cities Day", CalendarRegionCode.GLOBAL))
            }
            11 -> {
                fixed.add(Triple("$year-11-01", "All Saints' Day", CalendarRegionCode.EU))
                fixed.add(Triple("$year-11-11", "Veterans Day", CalendarRegionCode.US))
                fixed.add(Triple("$year-11-23", "Labor Thanksgiving", CalendarRegionCode.JP))
                fixed.add(Triple("$year-11-30", "Commemoration Day", CalendarRegionCode.AE))
            }
            12 -> {
                fixed.add(Triple("$year-12-02", "UAE National Day", CalendarRegionCode.AE))
                fixed.add(Triple("$year-12-25", "Christmas Day", CalendarRegionCode.GLOBAL))
                fixed.add(Triple("$year-12-26", "Boxing Day", CalendarRegionCode.UK))
            }
        }
        return fixed
            .filter { it.third == CalendarRegionCode.GLOBAL || it.third == region }
            .map { InternationalHoliday(it.first, it.second, it.third) }
    }

    fun buildInternationalCalendarMonth(
        snapshot: LedgerSnapshot,
        monthKey: String,
        prefs: CalendarPreferences = snapshot.calendarPrefs
    ): InternationalCalendarMonth {
        val parts = monthKey.split("-")
        val year = parts.getOrNull(0)?.toIntOrNull() ?: 2026
        val month = (parts.getOrNull(1)?.toIntOrNull() ?: 11) - 1
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(year, month, 1, 0, 0, 0)
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dowSun0 = cal.get(Calendar.DAY_OF_WEEK) - 1 // Sun=0..Sat=6
        val firstOffset = when (prefs.weekStart) {
            CalendarWeekStart.SUN -> dowSun0
            CalendarWeekStart.SAT -> (dowSun0 + 1) % 7
            CalendarWeekStart.MON -> (dowSun0 + 6) % 7
        }
        val headers = when (prefs.weekStart) {
            CalendarWeekStart.SUN -> listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
            CalendarWeekStart.SAT -> listOf("Sat", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri")
            CalendarWeekStart.MON -> listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        }
        val holidays = getInternationalHolidaysForMonth(monthKey, prefs.region)
        var monthExp = 0.0
        var monthInc = 0.0
        val cells = (1..daysInMonth).map { day ->
            val dateKey = String.format(Locale.US, "%s-%02d", monthKey, day)
            val dayEntries = snapshot.expenses.filter { it.createdAt.startsWith(dateKey) }
            val dayExp = dayEntries.filter { it.entryType == EntryType.EXPENSE }.sumOf { it.balance }
            val dayInc = dayEntries.filter { it.entryType == EntryType.INCOME }.sumOf { it.balance }
            monthExp += dayExp
            monthInc += dayInc
            val dueBills = snapshot.expenses.filter {
                it.entryType == EntryType.EXPENSE && it.status != ExpenseStatus.PAID && it.dueDate == dateKey
            }
            val fmtDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val targetMs = try { fmtDate.parse(dateKey)?.time } catch (_: Exception) { null }
            val projected = snapshot.templates.filter { tpl ->
                when (tpl.recurrence) {
                    RecurrenceCycle.MONTHLY -> {
                        val anchorDay = tpl.createdAt.drop(8).take(2).toIntOrNull() ?: 1
                        day == minOf(anchorDay, daysInMonth)
                    }
                    RecurrenceCycle.WEEKLY -> {
                        val baseStr = (tpl.lastUsedAt ?: tpl.createdAt).take(10)
                        val baseMs = try { fmtDate.parse(baseStr)?.time } catch (_: Exception) { null }
                        if (baseMs != null && targetMs != null && targetMs >= baseMs) {
                            ((targetMs - baseMs) % (7L * MS_PER_DAY)) == 0L
                        } else {
                            false
                        }
                    }
                    RecurrenceCycle.NONE -> false
                }
            }
            InternationalCalendarDayCell(
                dateKey = dateKey,
                day = day,
                expenseTotal = dayExp,
                incomeTotal = dayInc,
                expenses = dayEntries,
                dueBills = dueBills,
                projectedTemplates = projected,
                holidays = holidays.filter { it.dateKey == dateKey },
                transfers = snapshot.walletTransfers.filter { it.at.startsWith(dateKey) },
                repayments = snapshot.iouRepayments.filter { it.at.startsWith(dateKey) },
                goalTargets = snapshot.savingsGoals.filter { it.targetDate == dateKey }
            )
        }
        val rowCount = (firstOffset + cells.size + 6) / 7
        val weekNumbers = (0 until rowCount).map { row ->
            val firstDayIdx = (row * 7 - firstOffset).coerceIn(0, (cells.size - 1).coerceAtLeast(0))
            getIsoWeekNumber(cells[firstDayIdx].dateKey)
        }
        return InternationalCalendarMonth(
            monthKey = monthKey,
            leadingBlanks = firstOffset,
            daysInMonth = daysInMonth,
            weekHeaders = headers,
            rowWeekNumbers = weekNumbers,
            cells = cells,
            monthExpenseTotal = monthExp,
            monthIncomeTotal = monthInc
        )
    }

    fun generateBillsIcsCalendar(snapshot: LedgerSnapshot, appName: String): String {
        return buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            appendLine("PRODID:-//$appName//International Ledger//EN")
            snapshot.expenses.filter { !it.dueDate.isNullOrBlank() && it.entryType == EntryType.EXPENSE }.forEach { exp ->
                val dt = exp.dueDate!!.replace("-", "")
                appendLine("BEGIN:VEVENT")
                appendLine("UID:${exp.id}@expenseledger")
                appendLine("DTSTART;VALUE=DATE:$dt")
                appendLine("SUMMARY:${exp.quote} (₹${formatAmt(exp.balance)})")
                appendLine("DESCRIPTION:Status: ${exp.status.label} · Mode: ${exp.paymentMode.label}")
                appendLine("END:VEVENT")
            }
            snapshot.savingsGoals.filter { !it.targetDate.isNullOrBlank() }.forEach { goal ->
                val dt = goal.targetDate!!.replace("-", "")
                appendLine("BEGIN:VEVENT")
                appendLine("UID:${goal.id}@expenseledger")
                appendLine("DTSTART;VALUE=DATE:$dt")
                appendLine("SUMMARY:Goal: ${goal.title} (Target ₹${formatAmt(goal.targetAmount)})")
                appendLine("END:VEVENT")
            }
            appendLine("END:VCALENDAR")
        }
    }

    fun applyAutoLabelRules(
        text: String,
        rules: List<AutoLabelRule>,
        existingLabelIds: List<String>,
        currentMode: PaymentMode
    ): Pair<List<String>, PaymentMode> {
        val hay = text.trim().lowercase(Locale.US)
        if (hay.isBlank()) return existingLabelIds to currentMode
        val mergedLabels = existingLabelIds.toMutableList()
        var nextMode = currentMode
        rules.filter { it.enabled && it.keyword.isNotBlank() }.forEach { r ->
            if (hay.contains(r.keyword.trim().lowercase(Locale.US))) {
                r.labelIds.forEach { id -> if (!mergedLabels.contains(id)) mergedLabels.add(id) }
                if (r.defaultPaymentMode != null) nextMode = r.defaultPaymentMode
            }
        }
        return mergedLabels.toList() to nextMode
    }

    fun summarizeFriendIous(expenses: List<ExpenseRecord>, repayments: List<IouRepaymentRecord>): List<FriendIouSummary> {
        val sharedMap = linkedMapOf<String, Pair<Double, Int>>()
        val repaidMap = linkedMapOf<String, Double>()
        val displayNames = linkedMapOf<String, String>()
        expenses.forEach { exp ->
            exp.splitShares.forEach { sp ->
                val k = sp.friendName.trim().lowercase(Locale.US)
                if (k.isNotBlank() && sp.amount > 0.0) {
                    displayNames.putIfAbsent(k, sp.friendName.trim())
                    val cur = sharedMap[k] ?: (0.0 to 0)
                    sharedMap[k] = (cur.first + sp.amount) to (cur.second + 1)
                }
            }
        }
        repayments.forEach { rep ->
            val k = rep.friendName.trim().lowercase(Locale.US)
            if (k.isNotBlank() && rep.amount > 0.0) {
                displayNames.putIfAbsent(k, rep.friendName.trim())
                repaidMap[k] = (repaidMap[k] ?: 0.0) + rep.amount
            }
        }
        return sharedMap.keys.union(repaidMap.keys).map { k ->
            val shared = sharedMap[k]?.first ?: 0.0
            val count = sharedMap[k]?.second ?: 0
            val repaid = repaidMap[k] ?: 0.0
            FriendIouSummary(
                friendName = displayNames[k] ?: k,
                totalShared = kotlin.math.round(shared * 100.0) / 100.0,
                totalRepaid = kotlin.math.round(repaid * 100.0) / 100.0,
                outstanding = kotlin.math.round((shared - repaid).coerceAtLeast(0.0) * 100.0) / 100.0,
                itemCount = count
            )
        }.sortedByDescending { it.outstanding }
    }

    fun recordIouRepayment(
        snapshot: LedgerSnapshot,
        friendName: String,
        amount: Double,
        walletMode: PaymentMode,
        note: String = ""
    ): LedgerSnapshot {
        val cleanAmt = kotlin.math.round(amount * 100.0) / 100.0
        val cleanName = friendName.trim()
        if (cleanAmt <= 0.0 || cleanName.isBlank()) return snapshot
        val rep = IouRepaymentRecord(
            friendName = cleanName,
            amount = cleanAmt,
            walletMode = walletMode,
            at = nowIso(),
            note = note.trim()
        )
        return snapshot.copy(iouRepayments = listOf(rep) + snapshot.iouRepayments)
    }

    fun computeSavingsGoalsProgress(goals: List<SavingsGoalItem>, txs: List<GoalTransaction>): List<GoalProgressSummary> {
        return goals.map { g ->
            val saved = txs.filter { it.goalId == g.id }.sumOf { if (it.kind == "withdraw") -it.amount else it.amount }.coerceAtLeast(0.0)
            val pct = if (g.targetAmount > 0.0) kotlin.math.round((saved / g.targetAmount) * 100.0).toInt().coerceIn(0, 100) else 0
            GoalProgressSummary(
                goal = g,
                savedAmount = kotlin.math.round(saved * 100.0) / 100.0,
                pct = pct,
                remaining = kotlin.math.round((g.targetAmount - saved).coerceAtLeast(0.0) * 100.0) / 100.0
            )
        }
    }

    fun recordGoalTransaction(
        snapshot: LedgerSnapshot,
        goalId: String,
        kind: String,
        amount: Double,
        walletMode: PaymentMode,
        note: String = ""
    ): LedgerSnapshot {
        val cleanAmt = kotlin.math.round(amount * 100.0) / 100.0
        if (cleanAmt <= 0.0 || snapshot.savingsGoals.none { it.id == goalId }) return snapshot
        val tx = GoalTransaction(
            goalId = goalId,
            kind = if (kind == "withdraw") "withdraw" else "deposit",
            amount = cleanAmt,
            walletMode = walletMode,
            at = nowIso(),
            note = note.trim()
        )
        return snapshot.copy(goalTransactions = listOf(tx) + snapshot.goalTransactions)
    }

    fun compute30DayForecast(snapshot: LedgerSnapshot): CashflowForecast30d {
        val liveWallets = computeLiveWalletBalances(snapshot)
        val liquidCash = liveWallets
            .filter { it.mode == PaymentMode.UPI || it.mode == PaymentMode.CASH || it.mode == PaymentMode.BANK }
            .sumOf { it.liveBalance }
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val todayStr = fmt.format(Date())
        val todayMs = fmt.parse(todayStr)?.time ?: System.currentTimeMillis()
        val items = mutableListOf<ForecastItem>()
        snapshot.expenses.filter { it.entryType == EntryType.EXPENSE && it.status != ExpenseStatus.PAID && !it.dueDate.isNullOrBlank() }.forEach { exp ->
            val rem = expenseRemainingPart(exp)
            if (rem > 0.0) {
                val dMs = try { fmt.parse(exp.dueDate!!)?.time } catch (_: Exception) { null }
                if (dMs != null && dMs <= todayMs + 30L * MS_PER_DAY) {
                    items.add(ForecastItem(exp.dueDate!!, exp.quote, rem, "due"))
                }
            }
        }
        val endMs = todayMs + 29L * MS_PER_DAY
        snapshot.templates.filter { it.entryType == EntryType.EXPENSE && it.recurrence != RecurrenceCycle.NONE && it.balance > 0.0 }.forEach { tpl ->
            val baseStr = (tpl.lastUsedAt ?: tpl.createdAt).take(10)
            val baseMs = try { fmt.parse(baseStr)?.time } catch (_: Exception) { null }
            if (baseMs != null) {
                if (tpl.recurrence == RecurrenceCycle.WEEKLY) {
                    var nextMs = if (tpl.lastUsedAt != null) baseMs + 7L * MS_PER_DAY else todayMs
                    while (nextMs < todayMs) nextMs += 7L * MS_PER_DAY
                    while (nextMs <= endMs) {
                        val dKey = fmt.format(Date(nextMs))
                        items.add(ForecastItem(dKey, "${tpl.title} (Weekly)", tpl.balance, "recurring"))
                        nextMs += 7L * MS_PER_DAY
                    }
                } else if (tpl.recurrence == RecurrenceCycle.MONTHLY) {
                    val anchorDay = baseStr.drop(8).take(2).toIntOrNull() ?: 1
                    val startCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = todayMs }
                    for (mOffset in 0..1) {
                        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                            set(startCal.get(Calendar.YEAR), startCal.get(Calendar.MONTH) + mOffset, 1, 0, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        val dim = c.getActualMaximum(Calendar.DAY_OF_MONTH)
                        c.set(Calendar.DAY_OF_MONTH, minOf(dim, anchorDay.coerceAtLeast(1)))
                        val occMs = c.timeInMillis
                        if (occMs in todayMs..endMs) {
                            items.add(ForecastItem(fmt.format(Date(occMs)), "${tpl.title} (Monthly)", tpl.balance, "recurring"))
                        }
                    }
                }
            }
        }
        val outflow = kotlin.math.round(items.sumOf { it.amount } * 100.0) / 100.0
        val projected = kotlin.math.round((liquidCash - outflow) * 100.0) / 100.0
        return CashflowForecast30d(
            liquidCash = kotlin.math.round(liquidCash * 100.0) / 100.0,
            projectedOutflow = outflow,
            projectedBalance = projected,
            shortfallAmount = if (projected < 0.0) kotlin.math.abs(projected) else 0.0,
            items = items.sortedBy { it.dateKey }
        )
    }

    fun isEncryptedEbakText(raw: String): Boolean {
        return try {
            val obj = JSONObject(raw)
            obj.optString("format") == "expense-ledger-ebak-v1"
        } catch (_: Exception) {
            false
        }
    }

    fun encryptEbakJson(snapshotJson: String, passphrase: String): String {
        val cleanPass = passphrase.trim()
        val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(cleanPass.toCharArray(), salt, 100_000, 256)
        var keyBytes: ByteArray? = null
        return try {
            val encodedKey = factory.generateSecret(spec).encoded
            keyBytes = encodedKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encodedKey, "AES"), GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(snapshotJson.toByteArray(Charsets.UTF_8))
            JSONObject().apply {
                put("format", "expense-ledger-ebak-v1")
                put("iterations", 100_000)
                put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                put("cipher", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            }.toString(2)
        } finally {
            spec.clearPassword()
            keyBytes?.fill(0)
        }
    }

    fun decryptEbakJson(rawEbak: String, passphrase: String): String? {
        var spec: PBEKeySpec? = null
        var keyBytes: ByteArray? = null
        return try {
            val obj = JSONObject(rawEbak)
            if (obj.optString("format") != "expense-ledger-ebak-v1") return null
            val iterations = obj.optInt("iterations", 100_000).coerceIn(10_000, 500_000)
            val salt = Base64.decode(obj.getString("salt"), Base64.DEFAULT)
            val iv = Base64.decode(obj.getString("iv"), Base64.DEFAULT)
            val cipherBytes = Base64.decode(obj.getString("cipher"), Base64.DEFAULT)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val pbeSpec = PBEKeySpec(passphrase.trim().toCharArray(), salt, iterations, 256)
            spec = pbeSpec
            val encodedKey = factory.generateSecret(pbeSpec).encoded
            keyBytes = encodedKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encodedKey, "AES"), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        } finally {
            spec?.clearPassword()
            keyBytes?.fill(0)
        }
    }

    fun filterByDateWindow(exp: ExpenseRecord, window: DateWindowFilter): Boolean {
        if (window == DateWindowFilter.ALL) return true
        if (exp.createdAt.length < 10) return false
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val todayStr = fmt.format(Date())
        val datePart = exp.createdAt.take(10)
        return when (window) {
            DateWindowFilter.ALL -> true
            DateWindowFilter.TODAY -> datePart == todayStr
            DateWindowFilter.MONTH -> datePart.take(7) == todayStr.take(7)
            DateWindowFilter.SEVEN_DAYS -> {
                val expMs = fmt.parse(datePart)?.time ?: return false
                val todayMs = fmt.parse(todayStr)?.time ?: return false
                val diffDays = (todayMs - expMs) / MS_PER_DAY
                diffDays in 0..6
            }
        }
    }

    fun suggestMerchants(expenses: List<ExpenseRecord>, query: String, limit: Int = 4): List<MerchantSuggestion> {
        val q = query.trim().lowercase(Locale.US)
        if (q.length < 2) return emptyList()
        val buckets = linkedMapOf<String, MutableList<ExpenseRecord>>()
        expenses.forEach { exp ->
            val clean = exp.quote.replace(Regex("""\s*\(copy\)\s*$""", RegexOption.IGNORE_CASE), "").trim()
            if (clean.isNotBlank() && clean.lowercase(Locale.US).contains(q)) {
                buckets.getOrPut(clean.lowercase(Locale.US)) { mutableListOf() }.add(exp)
            }
        }
        return buckets.entries.map { (k, list) ->
            val latest = list.maxByOrNull { it.createdAt } ?: list.first()
            MerchantSuggestion(
                merchantKey = k,
                quote = latest.quote.replace(Regex("""\s*\(copy\)\s*$""", RegexOption.IGNORE_CASE), "").trim(),
                lastBalance = latest.balance,
                paymentMode = latest.paymentMode,
                entryType = latest.entryType,
                status = latest.status,
                labelIds = latest.labelIds,
                count = list.size
            )
        }.sortedByDescending { it.count }.take(limit)
    }

    fun collectDueAlerts(expenses: List<ExpenseRecord>): DueAlertSummary {
        var overdue = 0
        var today = 0
        var upcoming = 0
        val items = mutableListOf<DueAlertItem>()
        expenses.forEach { exp ->
            if (exp.entryType == EntryType.INCOME || exp.status == ExpenseStatus.PAID || exp.dueDate.isNullOrBlank()) return@forEach
            val rem = expenseRemainingPart(exp)
            if (rem <= 0.0) return@forEach
            val lbl = computeDueLabel(exp.dueDate, exp.status, exp.entryType) ?: return@forEach
            val sev = when {
                lbl.startsWith("Overdue") -> { overdue++; "overdue" }
                lbl == "Due Today" -> { today++; "today" }
                else -> { upcoming++; "upcoming" }
            }
            items.add(DueAlertItem(exp, rem, lbl, sev))
        }
        val order = mapOf("overdue" to 0, "today" to 1, "upcoming" to 2)
        return DueAlertSummary(overdue, today, upcoming, items.sortedBy { order[it.severity] ?: 3 })
    }

    fun writeLedgerPdfStatement(
        context: Context,
        uri: Uri,
        snapshot: LedgerSnapshot,
        appName: String,
        labelId: String?,
        monthKey: String?
    ): Boolean {
        val targetLabel = labelId?.let { id -> snapshot.labels.firstOrNull { it.id == id } }
        val filtered = snapshot.expenses.filter { exp ->
            val matchLbl = targetLabel == null || exp.labelIds.contains(targetLabel.id)
            val matchMonth = monthKey.isNullOrBlank() || exp.createdAt.startsWith(monthKey)
            matchLbl && matchMonth
        }
        val expOnly = filtered.filter { it.entryType == EntryType.EXPENSE }
        val paid = expOnly.sumOf { expensePaidPart(it) }
        val hold = expOnly.filter { it.status == ExpenseStatus.HOLD }.sumOf { expenseRemainingPart(it) }
        val need = expOnly.filter { it.status == ExpenseStatus.NEED }.sumOf { expenseRemainingPart(it) }
        val netFlow = computeCashflowSummary(filtered).netCashflow

        val doc = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
        var canvas = page.canvas
        val titlePaint = Paint().apply { textSize = 16f; isFakeBoldText = true; color = android.graphics.Color.rgb(15, 23, 42) }
        val subPaint = Paint().apply { textSize = 10f; color = android.graphics.Color.rgb(71, 85, 105) }
        val rowPaint = Paint().apply { textSize = 10f; color = android.graphics.Color.rgb(30, 41, 59) }
        val boldPaint = Paint().apply { textSize = 10.5f; isFakeBoldText = true; color = android.graphics.Color.rgb(15, 23, 42) }

        var y = 48f
        canvas.drawText("$appName — Statement", 36f, y, titlePaint)
        y += 18f
        canvas.drawText("Scope: ${targetLabel?.name ?: "All Entries"} · Period: ${monthKey ?: "All Time"}", 36f, y, subPaint)
        y += 18f
        canvas.drawText("PAID: ₹${formatAmt(paid)} | HOLD: ₹${formatAmt(hold)} | NEED: ₹${formatAmt(need)} | Net: ₹${formatAmt(netFlow)}", 36f, y, boldPaint)
        y += 22f
        canvas.drawLine(36f, y, pageWidth - 36f, y, subPaint)
        y += 18f

        filtered.forEach { exp ->
            if (y > pageHeight - 50) {
                doc.finishPage(page)
                pageNum++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
                canvas = page.canvas
                y = 48f
            }
            val dateStr = exp.createdAt.take(10)
            val tagStr = if (exp.entryType == EntryType.INCOME) "INCOME" else exp.status.label.uppercase()
            val amtStr = (if (exp.entryType == EntryType.INCOME) "+" else "") + "₹" + formatAmt(exp.balance)
            val line = "$dateStr  [$tagStr · ${exp.paymentMode.label}]  $amtStr  ${exp.quote.take(44)}"
            canvas.drawText(line, 36f, y, rowPaint)
            y += 16f
        }
        doc.finishPage(page)

        return try {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                doc.writeTo(stream)
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            doc.close()
        }
    }

    private fun escapeHtml(raw: String): String {
        return raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    fun buildStatementHtml(
        snapshot: LedgerSnapshot,
        appName: String,
        labelId: String? = null,
        monthKey: String? = null
    ): String {
        val targetLabel = labelId?.let { id -> snapshot.labels.firstOrNull { it.id == id } }
        val filtered = snapshot.expenses.filter { exp ->
            val matchLbl = targetLabel == null || exp.labelIds.contains(targetLabel.id)
            val matchMonth = monthKey.isNullOrBlank() || exp.createdAt.startsWith(monthKey)
            matchLbl && matchMonth
        }
        val expOnly = filtered.filter { it.entryType == EntryType.EXPENSE }
        val paid = expOnly.sumOf { expensePaidPart(it) }
        val hold = expOnly.filter { it.status == ExpenseStatus.HOLD }.sumOf { expenseRemainingPart(it) }
        val need = expOnly.filter { it.status == ExpenseStatus.NEED }.sumOf { expenseRemainingPart(it) }
        val grand = paid + hold + need
        val cashflow = computeCashflowSummary(filtered)
        val liveWallets = computeLiveWalletBalances(snapshot)
        val scopeTitle = targetLabel?.let { "Label ${it.name}" } ?: "All Entries"
        val periodTitle = if (monthKey.isNullOrBlank()) "All Time" else monthKey

        return buildString {
            appendLine("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"/>")
            appendLine("<title>${escapeHtml(appName)} — Financial Statement</title>")
            appendLine("<style>body{font-family:system-ui,-apple-system,sans-serif;color:#0f172a;margin:28px;}h1{font-size:20px;margin:0 0 4px;}p.sub{color:#475569;font-size:12px;margin:0 0 16px;}.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-bottom:16px;}.box{border:1px solid #cbd5e1;border-radius:8px;padding:10px;}.box .k{font-size:10px;font-weight:700;color:#475569;text-transform:uppercase;}.box .v{font-family:monospace;font-size:16px;font-weight:700;margin-top:4px;}table{width:100%;border-collapse:collapse;font-size:12px;}th,td{border-bottom:1px solid #e2e8f0;padding:7px 6px;text-align:left;}th{background:#f8fafc;font-weight:700;}</style>")
            appendLine("</head><body>")
            appendLine("<h1>${escapeHtml(appName)} — Financial Statement</h1>")
            appendLine("<p class=\"sub\">Scope: ${escapeHtml(scopeTitle)} · Period: ${escapeHtml(periodTitle)}</p>")
            appendLine("<div class=\"grid\">")
            appendLine("<div class=\"box\"><div class=\"k\">PAID (₹)</div><div class=\"v\">₹${formatAmt(paid)}</div></div>")
            appendLine("<div class=\"box\"><div class=\"k\">HOLD (₹)</div><div class=\"v\">₹${formatAmt(hold)}</div></div>")
            appendLine("<div class=\"box\"><div class=\"k\">NEED (₹)</div><div class=\"v\">₹${formatAmt(need)}</div></div>")
            appendLine("<div class=\"box\"><div class=\"k\">GRAND / NET</div><div class=\"v\">₹${formatAmt(grand)} / ₹${formatAmt(cashflow.netCashflow)}</div></div>")
            appendLine("</div>")
            appendLine("<p class=\"sub\">Live Wallets: " + liveWallets.joinToString(" · ") { "<strong>${escapeHtml(it.mode.label)}:</strong> ₹${formatAmt(it.liveBalance)}" } + "</p>")
            appendLine("<table><thead><tr><th>Date</th><th>Quote</th><th>Type / Status</th><th>Wallet</th><th>Labels</th><th>Amount (₹)</th></tr></thead><tbody>")
            filtered.forEach { e ->
                val rem = expenseRemainingPart(e)
                val statusStr = if (e.entryType == EntryType.INCOME) {
                    "Income"
                } else if (e.status != ExpenseStatus.PAID && rem < e.balance) {
                    "${e.status.label} (₹${formatAmt(rem)} left)"
                } else {
                    e.status.label
                }
                val lblStr = e.labelIds.mapNotNull { id -> snapshot.labels.firstOrNull { it.id == id }?.name }.joinToString(", ")
                val amtPrefix = if (e.entryType == EntryType.INCOME) "+" else ""
                appendLine("<tr><td>${escapeHtml(e.createdAt.take(10))}</td><td>${escapeHtml(e.quote)}</td><td>${escapeHtml(statusStr)}</td><td>${escapeHtml(e.paymentMode.label)}</td><td>${escapeHtml(lblStr)}</td><td style=\"font-family:monospace;font-weight:700;\">${amtPrefix}₹${formatAmt(e.balance)}</td></tr>")
            }
            appendLine("</tbody></table></body></html>")
        }
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
        val paid = filtered.sumOf { expensePaidPart(it) }
        val hold = filtered.filter { it.status == ExpenseStatus.HOLD }.sumOf { expenseRemainingPart(it) }
        val need = filtered.filter { it.status == ExpenseStatus.NEED }.sumOf { expenseRemainingPart(it) }
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
        val startsFormula = raw.isNotEmpty() && (raw[0] == '=' || raw[0] == '+' || raw[0] == '-' || raw[0] == '@' || raw[0] == '\t' || raw[0] == '\r')
        val isPlainNumber = Regex("""^-?\d+(?:\.\d+)?$""").matches(raw)
        val safe = if (startsFormula && !isPlainNumber) "'$raw" else raw
        return if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            "\"" + safe.replace("\"", "\"\"") + "\""
        } else {
            safe
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
                    formatPlainAmt(exp.balance),
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

    fun parseCsvImport(
        csvText: String,
        existingLabels: List<LabelRecord>,
        autoRules: List<AutoLabelRule> = emptyList()
    ): CsvImportResult {
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
            val rawModeStr = cols.getOrNull(colMode).orEmpty()
            val mode = PaymentMode.fromString(rawModeStr)
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
            val (ruleLabels, ruleMode) = applyAutoLabelRules(quote, autoRules, rowLblIds, mode)
            val finalMode = if (rawModeStr.isBlank()) ruleMode else mode

            validEntries.add(
                ExpenseRecord(
                    balance = amt,
                    quote = quote,
                    status = status,
                    labelIds = ruleLabels,
                    createdAt = now,
                    updatedAt = now,
                    auditLog = listOf(AuditEntry(action = "Created", at = now, summary = "Imported CSV ₹${formatAmt(amt)} (${finalMode.label})")),
                    entryType = entryType,
                    paymentMode = finalMode,
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
        put("paidAmount", e.paidAmount)
        put("partialPayments", JSONArray().apply {
            e.partialPayments.forEach { pp ->
                put(JSONObject().apply {
                    put("id", pp.id)
                    put("amount", pp.amount)
                    put("mode", pp.mode.label)
                    put("at", pp.at)
                    if (pp.note.isNotBlank()) put("note", pp.note)
                })
            }
        })
        if (e.receiptAttachment != null) {
            put("receiptAttachment", JSONObject().apply {
                put("name", e.receiptAttachment.name)
                put("uriOrDataUrl", e.receiptAttachment.uriOrDataUrl)
                put("attachedAt", e.receiptAttachment.attachedAt)
            })
        }
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
        if (e.splitShares.isNotEmpty()) {
            put("splitShares", JSONArray().apply {
                e.splitShares.forEach { sp ->
                    put(JSONObject().apply {
                        put("id", sp.id)
                        put("friendName", sp.friendName)
                        put("amount", sp.amount)
                    })
                }
            })
        }
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
        val startObj = JSONObject().apply {
            PaymentMode.entries.forEach { m ->
                put(m.label, snapshot.walletStartingBalances[m] ?: 0.0)
            }
        }
        val trfArr = JSONArray().apply {
            snapshot.walletTransfers.forEach { t ->
                put(JSONObject().apply {
                    put("id", t.id)
                    put("fromMode", t.fromMode.label)
                    put("toMode", t.toMode.label)
                    put("amount", t.amount)
                    put("note", t.note)
                    put("at", t.at)
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
        root.put("biometricEnabled", snapshot.biometricEnabled)
        root.put("hideBalances", snapshot.hideBalances)
        root.put("themeMode", snapshot.themeMode.key)
        root.put("dueRemindersEnabled", snapshot.dueRemindersEnabled)
        root.put("walletStartingBalances", startObj)
        root.put("walletTransfers", trfArr)
        root.put("calendarPrefs", JSONObject().apply {
            put("weekStart", snapshot.calendarPrefs.weekStart.key)
            put("showIsoWeeks", snapshot.calendarPrefs.showIsoWeeks)
            put("region", snapshot.calendarPrefs.region.key)
        })
        root.put("autoLabelRules", JSONArray().apply {
            snapshot.autoLabelRules.forEach { r ->
                put(JSONObject().apply {
                    put("id", r.id)
                    put("keyword", r.keyword)
                    put("labelIds", JSONArray(r.labelIds))
                    put("defaultPaymentMode", r.defaultPaymentMode?.label ?: JSONObject.NULL)
                    put("enabled", r.enabled)
                })
            }
        })
        root.put("iouRepayments", JSONArray().apply {
            snapshot.iouRepayments.forEach { rep ->
                put(JSONObject().apply {
                    put("id", rep.id)
                    put("friendName", rep.friendName)
                    put("amount", rep.amount)
                    put("walletMode", rep.walletMode.label)
                    put("at", rep.at)
                    if (rep.note.isNotBlank()) put("note", rep.note)
                })
            }
        })
        root.put("savingsGoals", JSONArray().apply {
            snapshot.savingsGoals.forEach { g ->
                put(JSONObject().apply {
                    put("id", g.id)
                    put("title", g.title)
                    put("targetAmount", g.targetAmount)
                    put("targetDate", g.targetDate ?: JSONObject.NULL)
                    put("monthlyTarget", g.monthlyTarget ?: JSONObject.NULL)
                    put("colorHex", g.colorHex)
                    put("createdAt", g.createdAt)
                })
            }
        })
        root.put("goalTransactions", JSONArray().apply {
            snapshot.goalTransactions.forEach { tx ->
                put(JSONObject().apply {
                    put("id", tx.id)
                    put("goalId", tx.goalId)
                    put("kind", tx.kind)
                    put("amount", tx.amount)
                    put("walletMode", tx.walletMode.label)
                    put("at", tx.at)
                    if (tx.note.isNotBlank()) put("note", tx.note)
                })
            }
        })
        root.put("autoLockSeconds", snapshot.autoLockSeconds)
        return root.toString()
    }

    fun encodePublicBackupSnapshot(snapshot: LedgerSnapshot): String {
        return encodeSnapshot(snapshot.copy(privacyPinHash = null, biometricEnabled = false))
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
        val status = ExpenseStatus.fromString(o.optString("status", "Need"))
        val balance = o.optDouble("balance", 0.0).coerceAtLeast(0.0)
        val rawPaid = o.optDouble("paidAmount", if (status == ExpenseStatus.PAID) balance else 0.0)
        val paidAmount = if (status == ExpenseStatus.PAID) balance else rawPaid.coerceIn(0.0, balance)

        val partials = mutableListOf<PartialPaymentRecord>()
        val ppArr = o.optJSONArray("partialPayments")
        if (ppArr != null) {
            for (j in 0 until ppArr.length()) {
                val po = ppArr.optJSONObject(j) ?: continue
                val pAmt = po.optDouble("amount", 0.0)
                if (pAmt > 0.0) {
                    partials.add(
                        PartialPaymentRecord(
                            id = po.optString("id").ifBlank { "pp-$j" },
                            amount = pAmt,
                            mode = PaymentMode.fromString(po.optString("mode", "UPI")),
                            at = po.optString("at", createdAt),
                            note = po.optString("note", "")
                        )
                    )
                }
            }
        }

        val rObj = o.optJSONObject("receiptAttachment")
        val receipt = if (rObj != null && rObj.optString("uriOrDataUrl").isNotBlank()) {
            ReceiptAttachmentRecord(
                name = rObj.optString("name", "Receipt.jpg"),
                uriOrDataUrl = rObj.optString("uriOrDataUrl"),
                attachedAt = rObj.optString("attachedAt", createdAt)
            )
        } else null

        val splits = mutableListOf<ExpenseSplitShare>()
        val spArr = o.optJSONArray("splitShares")
        if (spArr != null) {
            for (j in 0 until spArr.length()) {
                val so = spArr.optJSONObject(j) ?: continue
                val name = so.optString("friendName").trim()
                val sAmt = so.optDouble("amount", 0.0)
                if (name.isNotBlank() && sAmt > 0.0) {
                    splits.add(ExpenseSplitShare(id = so.optString("id").ifBlank { "sp-$j" }, friendName = name, amount = sAmt))
                }
            }
        }

        return ExpenseRecord(
            id = o.optString("id"),
            balance = balance,
            quote = o.optString("quote"),
            status = status,
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
            dueDate = dueDate,
            paidAmount = paidAmount,
            partialPayments = partials,
            receiptAttachment = receipt,
            splitShares = splits
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
            val biometricEnabled = root.optBoolean("biometricEnabled", false)
            val hideBalances = root.optBoolean("hideBalances", false)
            val themeMode = ThemeMode.fromKey(root.optString("themeMode", "system"))
            val dueRemindersEnabled = root.optBoolean("dueRemindersEnabled", true)

            val startObj = root.optJSONObject("walletStartingBalances")
            val startingBalances = PaymentMode.entries.associateWith { m ->
                startObj?.optDouble(m.label, 0.0) ?: 0.0
            }

            val transfers = mutableListOf<WalletTransferRecord>()
            val trfArr = root.optJSONArray("walletTransfers") ?: JSONArray()
            for (i in 0 until trfArr.length()) {
                val o = trfArr.optJSONObject(i) ?: continue
                val amt = o.optDouble("amount", 0.0)
                if (amt > 0.0) {
                    transfers.add(
                        WalletTransferRecord(
                            id = o.optString("id").ifBlank { "trf-$i" },
                            fromMode = PaymentMode.fromString(o.optString("fromMode", "Bank")),
                            toMode = PaymentMode.fromString(o.optString("toMode", "UPI")),
                            amount = amt,
                            note = o.optString("note", "Wallet transfer"),
                            at = o.optString("at", LedgerMath.nowIso())
                        )
                    )
                }
            }

            val cpObj = root.optJSONObject("calendarPrefs")
            val calendarPrefs = CalendarPreferences(
                weekStart = CalendarWeekStart.fromKey(cpObj?.optString("weekStart", "mon") ?: "mon"),
                showIsoWeeks = cpObj?.optBoolean("showIsoWeeks", true) ?: true,
                region = CalendarRegionCode.fromKey(cpObj?.optString("region", "in") ?: "in")
            )

            val autoRules = mutableListOf<AutoLabelRule>()
            val arArr = root.optJSONArray("autoLabelRules") ?: JSONArray()
            for (i in 0 until arArr.length()) {
                val o = arArr.optJSONObject(i) ?: continue
                val kw = o.optString("keyword").trim()
                if (kw.isBlank()) continue
                val lIds = mutableListOf<String>()
                val lArr = o.optJSONArray("labelIds") ?: JSONArray()
                for (j in 0 until lArr.length()) {
                    val s = lArr.optString(j)
                    if (s.isNotBlank()) lIds.add(s)
                }
                val defMode = if (o.isNull("defaultPaymentMode")) null else PaymentMode.fromString(o.optString("defaultPaymentMode"))
                autoRules.add(AutoLabelRule(id = o.optString("id").ifBlank { "rule-$i" }, keyword = kw, labelIds = lIds, defaultPaymentMode = defMode, enabled = o.optBoolean("enabled", true)))
            }

            val iouReps = mutableListOf<IouRepaymentRecord>()
            val irArr = root.optJSONArray("iouRepayments") ?: JSONArray()
            for (i in 0 until irArr.length()) {
                val o = irArr.optJSONObject(i) ?: continue
                val amt = o.optDouble("amount", 0.0)
                val fn = o.optString("friendName").trim()
                if (fn.isNotBlank() && amt > 0.0) {
                    iouReps.add(IouRepaymentRecord(id = o.optString("id").ifBlank { "rep-$i" }, friendName = fn, amount = amt, walletMode = PaymentMode.fromString(o.optString("walletMode", "UPI")), at = o.optString("at", LedgerMath.nowIso()), note = o.optString("note", "")))
                }
            }

            val goals = mutableListOf<SavingsGoalItem>()
            val gArr = root.optJSONArray("savingsGoals") ?: JSONArray()
            for (i in 0 until gArr.length()) {
                val o = gArr.optJSONObject(i) ?: continue
                val t = o.optString("title").trim()
                val tgt = o.optDouble("targetAmount", 0.0)
                if (t.isNotBlank() && tgt > 0.0) {
                    val tDate = if (o.isNull("targetDate")) null else o.optString("targetDate").takeIf { it.isNotBlank() }
                    val mTgt = if (o.isNull("monthlyTarget")) null else o.optDouble("monthlyTarget", 0.0).takeIf { it > 0.0 }
                    goals.add(SavingsGoalItem(id = o.optString("id").ifBlank { "goal-$i" }, title = t, targetAmount = tgt, targetDate = tDate, monthlyTarget = mTgt, colorHex = o.optString("colorHex", "#059669"), createdAt = o.optString("createdAt", LedgerMath.nowIso())))
                }
            }

            val gTxs = mutableListOf<GoalTransaction>()
            val gtArr = root.optJSONArray("goalTransactions") ?: JSONArray()
            for (i in 0 until gtArr.length()) {
                val o = gtArr.optJSONObject(i) ?: continue
                val amt = o.optDouble("amount", 0.0)
                val gId = o.optString("goalId")
                if (gId.isNotBlank() && amt > 0.0) {
                    gTxs.add(GoalTransaction(id = o.optString("id").ifBlank { "gtx-$i" }, goalId = gId, kind = o.optString("kind", "deposit"), amount = amt, walletMode = PaymentMode.fromString(o.optString("walletMode", "Bank")), at = o.optString("at", LedgerMath.nowIso()), note = o.optString("note", "")))
                }
            }

            val rawAutoLock = root.optInt("autoLockSeconds", 0)
            val autoLockSeconds = if (rawAutoLock == 30 || rawAutoLock == 60 || rawAutoLock == 300) rawAutoLock else 0

            LedgerSnapshot(
                expenses = expenses,
                labels = labels,
                lists = lists,
                calculatorHistory = calcHistory,
                templates = templates,
                bin = LedgerMath.pruneExpiredBin(bin, retention),
                binRetentionDays = retention,
                privacyPinHash = pinHash,
                hideBalances = hideBalances,
                biometricEnabled = biometricEnabled,
                themeMode = themeMode,
                dueRemindersEnabled = dueRemindersEnabled,
                walletStartingBalances = startingBalances,
                walletTransfers = transfers,
                calendarPrefs = calendarPrefs,
                autoLabelRules = autoRules,
                iouRepayments = iouReps,
                savingsGoals = goals,
                goalTransactions = gTxs,
                autoLockSeconds = autoLockSeconds
            )
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
        val t1Sch = "2026-11-10T09:18:00.000Z"
        val t1Veg = "2026-11-10T09:22:00.000Z"
        val t2 = "2026-11-14T08:30:00.000Z"
        val t3 = "2026-11-15T07:35:00.000Z"
        val t4 = "2026-11-15T16:20:00.000Z"
        val tInc = "2026-11-12T11:00:00.000Z"
        val labels = listOf(
            LabelRecord("lbl-dr", "#Dr.", "Clinic consultations, lab diagnostics & pharmacy bills", "#2563EB", 2000.0, t1, t1, listOf(AuditEntry(action = "Created", at = t1, summary = "Label initialized"))),
            LabelRecord("lbl-school", "#School", "Tuition, stationery, workshop wood & exam fees", "#7C3AED", 1200.0, t1Sch, "2026-11-12T14:20:00.000Z", listOf(AuditEntry(action = "Created", at = t1Sch, summary = "Label initialized"))),
            LabelRecord("lbl-vegetables", "#Vegetables", "Daily fresh produce, herbs & kitchen market runs", "#059669", 800.0, t1Veg, t1Veg, listOf(AuditEntry(action = "Created", at = t1Veg, summary = "Label initialized")))
        )
        val lists = listOf(
            ListNoteRecord("list-craft-supplies", "School Craft & Fees Breakdown", "Wood -> ₹5\nFees -> ₹10\nOthers -> ₹15", t2, t2, listOf(AuditEntry(action = "Created", at = t2, summary = "Created itemized breakdown (₹30)"))),
            ListNoteRecord("list-veg-basket", "Morning Produce Basket", "Tomatoes -> ₹60\nSpinach -> ₹40\nCoriander -> ₹20", "2026-11-15T07:10:00.000Z", "2026-11-15T07:10:00.000Z", listOf(AuditEntry(action = "Created", at = "2026-11-15T07:10:00.000Z", summary = "Created morning market checklist (₹120)")))
        )
        val expenses = listOf(
            ExpenseRecord("exp-1", 850.0, "Pediatric checkup & vitamin prescription", ExpenseStatus.PAID, listOf("lbl-dr", "lbl-school"), emptyList(), null, null, "2026-11-13T10:15:00.000Z", "2026-11-13T17:40:00.000Z", listOf(AuditEntry(action = "Created", at = "2026-11-13T10:15:00.000Z", summary = "Logged ₹850 as Need"), AuditEntry(action = "Updated", at = "2026-11-13T17:40:00.000Z", summary = "Status changed from Need to Paid")), EntryType.EXPENSE, PaymentMode.UPI, false, null, 850.0),
            ExpenseRecord("exp-2", 30.0, "Term project wood, fees & misc supplies", ExpenseStatus.HOLD, listOf("lbl-school"), listOf("list-craft-supplies"), "calc-seed-1", "5 + 10 + 15 = 30", "2026-11-14T08:45:00.000Z", "2026-11-14T08:45:00.000Z", listOf(AuditEntry(action = "Created", at = "2026-11-14T08:45:00.000Z", summary = "Logged ₹30 with attached list breakdown")), EntryType.EXPENSE, PaymentMode.CASH, false, null, 0.0),
            ExpenseRecord("exp-3", 120.0, "Weekly green market produce basket", ExpenseStatus.PAID, listOf("lbl-vegetables"), listOf("list-veg-basket"), "calc-seed-2", "60 + 40 + 20 = 120", t3, t3, listOf(AuditEntry(action = "Created", at = t3, summary = "Logged ₹120 as Paid")), EntryType.EXPENSE, PaymentMode.UPI, false, null, 120.0, splitShares = listOf(ExpenseSplitShare("spl-veg-1", "Meera", 60.0))),
            ExpenseRecord("exp-4", 450.0, "Lab blood panel follow-up deposit", ExpenseStatus.NEED, listOf("lbl-dr"), emptyList(), null, null, t4, t4, listOf(AuditEntry(action = "Created", at = t4, summary = "Logged ₹450 as Need (Pinned due bill)")), EntryType.EXPENSE, PaymentMode.CARD, true, "2026-11-18", 0.0),
            ExpenseRecord("exp-inc-1", 5000.0, "Freelance UI consultation payout", ExpenseStatus.PAID, emptyList(), emptyList(), null, null, tInc, tInc, listOf(AuditEntry(action = "Created", at = tInc, summary = "Logged ₹5,000 Income via Bank")), EntryType.INCOME, PaymentMode.BANK, false, null, 5000.0)
        )
        val calcHistory = listOf(
            CalcHistoryRecord("calc-seed-1", "5 + 10 + 15", 30.0, "2026-11-14T08:42:00.000Z", "2026-11-14T08:42:00.000Z"),
            CalcHistoryRecord("calc-seed-2", "60 + 40 + 20", 120.0, "2026-11-15T07:32:00.000Z", "2026-11-15T07:33:15.000Z")
        )
        val templates = listOf(
            ExpenseTemplateRecord("tpl-veg-run", "Weekly Produce Basket", 120.0, ExpenseStatus.PAID, listOf("lbl-vegetables"), listOf("list-veg-basket"), RecurrenceCycle.WEEKLY, null, "2026-11-10T09:30:00.000Z", EntryType.EXPENSE, PaymentMode.UPI),
            ExpenseTemplateRecord("tpl-clinic-visit", "Monthly Clinic Checkup", 500.0, ExpenseStatus.NEED, listOf("lbl-dr"), emptyList(), RecurrenceCycle.MONTHLY, null, "2026-11-10T09:32:00.000Z", EntryType.EXPENSE, PaymentMode.CARD)
        )
        val startBalances = mapOf(PaymentMode.UPI to 4000.0, PaymentMode.CASH to 1500.0, PaymentMode.CARD to 0.0, PaymentMode.BANK to 12000.0)
        val seedTransfers = listOf(WalletTransferRecord("trf-seed-1", PaymentMode.BANK, PaymentMode.UPI, 1000.0, "Weekly UPI wallet top-up", "2026-11-12T12:00:00.000Z"))
        val seedRules = listOf(
            AutoLabelRule("rule-seed-clinic", "clinic", listOf("lbl-dr"), PaymentMode.UPI, true),
            AutoLabelRule("rule-seed-pharmacy", "pharmacy", listOf("lbl-dr"), null, true),
            AutoLabelRule("rule-seed-produce", "market", listOf("lbl-vegetables"), PaymentMode.UPI, true)
        )
        val seedGoals = listOf(
            SavingsGoalItem("goal-seed-emergency", "Medical & Family Sinking Fund", 10000.0, "2026-12-20", 1000.0, "#059669", "2026-11-10T09:00:00.000Z")
        )
        val seedGoalTxs = listOf(
            GoalTransaction("gtxn-seed-1", "goal-seed-emergency", "deposit", 2500.0, PaymentMode.BANK, "2026-11-12T12:30:00.000Z", "Initial sinking fund deposit")
        )
        return LedgerSnapshot(
            expenses = expenses,
            labels = labels,
            lists = lists,
            calculatorHistory = calcHistory,
            templates = templates,
            bin = emptyList(),
            binRetentionDays = 30,
            privacyPinHash = null,
            hideBalances = false,
            biometricEnabled = false,
            themeMode = ThemeMode.SYSTEM,
            dueRemindersEnabled = true,
            walletStartingBalances = startBalances,
            walletTransfers = seedTransfers,
            calendarPrefs = CalendarPreferences(CalendarWeekStart.MON, true, CalendarRegionCode.IN),
            autoLabelRules = seedRules,
            iouRepayments = emptyList(),
            savingsGoals = seedGoals,
            goalTransactions = seedGoalTxs,
            autoLockSeconds = 0
        )
    }
}
