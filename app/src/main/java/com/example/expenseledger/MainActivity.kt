package com.example.expenseledger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.round

class MainActivity : FragmentActivity() {
    private var shortcutActionState by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shortcutActionState = intent?.getStringExtra("shortcut_action")
        setContent {
            ExpenseLedgerApp(
                initialShortcutAction = shortcutActionState,
                onConsumeShortcutAction = { shortcutActionState = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val act = intent.getStringExtra("shortcut_action")
        if (!act.isNullOrBlank()) {
            shortcutActionState = act
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }
}

class QuickExpenseTileService : android.service.quicksettings.TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("shortcut_action", "new_expense")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivityAndCollapse(intent)
    }
}

private fun shareExpenseReceiptTextCard(context: android.content.Context, appName: String, exp: ExpenseRecord) {
    val statusBadge = if (exp.entryType == EntryType.INCOME) "INCOME" else exp.status.label.uppercase()
    val sign = if (exp.entryType == EntryType.INCOME) "+₹" else "₹"
    val cardText = buildString {
        appendLine("🧾 $appName — Receipt Card")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine("Amount : $sign${LedgerMath.formatAmt(exp.balance)} [$statusBadge]")
        appendLine("Item   : ${exp.quote}")
        appendLine("Wallet : ${exp.paymentMode.label}")
        if (!exp.dueDate.isNullOrBlank()) appendLine("Due    : ${exp.dueDate}")
        appendLine("Logged : ${LedgerMath.formatReadableDate(exp.createdAt)}")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━")
    }
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "$appName Receipt — ${exp.quote}")
        putExtra(Intent.EXTRA_TEXT, cardText)
    }
    context.startActivity(Intent.createChooser(sendIntent, "Share Expense Receipt Card"))
}

private fun triggerBiometricAuth(activity: FragmentActivity?, onSuccess: () -> Unit) {
    if (activity == null) return
    val manager = BiometricManager.from(activity)
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (manager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) return
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
        }
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock Expense Ledger")
        .setSubtitle("Verify your fingerprint, face, or device PIN")
        .setAllowedAuthenticators(authenticators)
        .build()
    prompt.authenticate(info)
}

private enum class LedgerTab(val title: String) {
    LISTS("Lists"),
    EXPENSES("Expenses"),
    LABELS("Labels")
}

private fun formatAmount(value: Double): String {
    val rounded = round(value * 100.0) / 100.0
    return if (rounded % 1.0 == 0.0) {
        rounded.toLong().toString()
    } else {
        String.format(java.util.Locale.US, "%.2f", rounded)
    }
}

private fun parseColorHex(hex: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        Color(0xFF2563EB)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseLedgerApp(
    initialShortcutAction: String? = null,
    onConsumeShortcutAction: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val dbHelper = remember { LedgerDbHelper(context) }

    var snapshot by remember { mutableStateOf(dbHelper.loadSnapshot()) }
    var activeTab by remember { mutableStateOf(LedgerTab.EXPENSES) }

    LaunchedEffect(Unit) {
        BillDueScheduler.sync(context, snapshot.dueRemindersEnabled)
    }

    var showExpenseModal by remember { mutableStateOf(false) }
    var editingExpense by remember { mutableStateOf<ExpenseRecord?>(null) }
    var prefillBalance by remember { mutableStateOf("") }
    var prefillQuote by remember { mutableStateOf("") }
    var prefillStatus by remember { mutableStateOf<ExpenseStatus?>(null) }
    var prefillEntryType by remember { mutableStateOf(EntryType.EXPENSE) }
    var prefillPaymentMode by remember { mutableStateOf(PaymentMode.UPI) }
    var prefillIsPinned by remember { mutableStateOf(false) }
    var prefillDueDate by remember { mutableStateOf("") }
    var prefillLabelIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var prefillAttachedListIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var prefillCalcHistoryId by remember { mutableStateOf<String?>(null) }
    var prefillCalcFormula by remember { mutableStateOf<String?>(null) }
    var pendingReceiptAttachment by remember { mutableStateOf<ReceiptAttachmentRecord?>(null) }

    var showLabelModal by remember { mutableStateOf(false) }
    var editingLabel by remember { mutableStateOf<LabelRecord?>(null) }

    var showListModal by remember { mutableStateOf(false) }
    var editingList by remember { mutableStateOf<ListNoteRecord?>(null) }
    var prefillListTitle by remember { mutableStateOf("") }
    var prefillListContent by remember { mutableStateOf("") }

    var showCalculatorSheet by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<ExpenseStatus?>(null) }
    var entryTypeFilter by remember { mutableStateOf<EntryType?>(null) }
    var paymentModeFilter by remember { mutableStateOf<PaymentMode?>(null) }
    var dateWindowFilter by remember { mutableStateOf(DateWindowFilter.ALL) }
    var sortOrder by remember { mutableStateOf(ExpenseSortOrder.NEWEST) }
    var pinnedOnlyFilter by remember { mutableStateOf(false) }
    var labelFilterId by remember { mutableStateOf<String?>(null) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var shareLabelId by remember { mutableStateOf<String?>(null) }

    // v1.2, v1.3, v1.4, v1.5 & v1.6 Controls: Insights, Bin, Bulk Select, Templates, Privacy/Biometrics, CSV, Partial Pay, Receipts, HTML & Reset
    var showInsightsDialog by remember { mutableStateOf(false) }
    var insightsInitialSection by remember { mutableStateOf(0) }
    var showCalendarDialog by remember { mutableStateOf(false) }
    var showAutoRulesDialog by remember { mutableStateOf(false) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }
    var showDueBanner by remember { mutableStateOf(true) }
    var ebakDialogMode by remember { mutableStateOf<String?>(null) } // "export" | "restore"
    var pendingEbakText by remember { mutableStateOf<String?>(null) }
    var pendingEbakPassphrase by remember { mutableStateOf("") }
    var exactDateFilter by remember { mutableStateOf<String?>(null) }
    var prefillSplitShares by remember { mutableStateOf<List<ExpenseSplitShare>>(emptyList()) }
    var showBinDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showCsvImportDialog by remember { mutableStateOf(false) }
    var partialPayTarget by remember { mutableStateOf<ExpenseRecord?>(null) }
    var receiptLightboxRecord by remember { mutableStateOf<ExpenseRecord?>(null) }
    var pendingPdfLabelId by remember { mutableStateOf<String?>(null) }
    var pendingPdfMonthKey by remember { mutableStateOf<String?>(null) }
    var pendingHtmlLabelId by remember { mutableStateOf<String?>(null) }
    var pendingHtmlMonthKey by remember { mutableStateOf<String?>(null) }
    var pendingCsvText by remember { mutableStateOf("") }
    var isLocked by remember { mutableStateOf(!snapshot.privacyPinHash.isNullOrBlank()) }
    var lastPausedAtMs by remember { mutableStateOf<Long?>(null) }
    var bulkMode by remember { mutableStateOf(false) }
    var selectedExpenseIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showQuickTemplates by remember { mutableStateOf(true) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, snapshot.privacyPinHash, snapshot.autoLockSeconds) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    lastPausedAtMs = System.currentTimeMillis()
                }
                Lifecycle.Event.ON_START -> {
                    val pausedAt = lastPausedAtMs
                    val secs = snapshot.autoLockSeconds
                    if (pausedAt != null && secs > 0 && !snapshot.privacyPinHash.isNullOrBlank()) {
                        val elapsedMs = System.currentTimeMillis() - pausedAt
                        if (elapsedMs >= secs * 1000L) {
                            isLocked = true
                        }
                    }
                    lastPausedAtMs = null
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(initialShortcutAction, isLocked) {
        if (!initialShortcutAction.isNullOrBlank() && !isLocked) {
            when (initialShortcutAction) {
                "new_expense", "new_need", "new_paid" -> {
                    activeTab = LedgerTab.EXPENSES
                    editingExpense = null
                    prefillBalance = ""
                    prefillQuote = ""
                    prefillStatus = when (initialShortcutAction) {
                        "new_paid" -> ExpenseStatus.PAID
                        "new_need" -> ExpenseStatus.NEED
                        else -> null
                    }
                    prefillEntryType = EntryType.EXPENSE
                    prefillPaymentMode = PaymentMode.UPI
                    prefillIsPinned = false
                    prefillDueDate = ""
                    prefillLabelIds = emptyList()
                    prefillAttachedListIds = emptyList()
                    prefillCalcHistoryId = null
                    prefillCalcFormula = null
                    pendingReceiptAttachment = null
                    prefillSplitShares = emptyList()
                    showExpenseModal = true
                }
                "calendar" -> showCalendarDialog = true
                "quick_calc" -> showCalculatorSheet = true
                "insights" -> showInsightsDialog = true
            }
            onConsumeShortcutAction()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    fun persist(updated: LedgerSnapshot) {
        snapshot = updated
        dbHelper.saveSnapshot(updated)
        coroutineScope.launch(Dispatchers.IO) {
            try {
                BalanceWidget().updateAll(context)
            } catch (_: Exception) {
            }
        }
    }

    fun showActionSnackbar(message: String, undoSnapshot: LedgerSnapshot? = null) {
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = if (undoSnapshot != null) "Undo" else null,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed && undoSnapshot != null) {
                persist(undoSnapshot)
            }
        }
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(dbHelper.encodePublicBackupSnapshot(snapshot).toByteArray(Charsets.UTF_8))
                }
                showActionSnackbar("Exported JSON backup")
            } catch (_: Exception) {
                showActionSnackbar("Could not export JSON backup")
            }
        }
    }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(LedgerMath.generateCsv(snapshot).toByteArray(Charsets.UTF_8))
                }
                showActionSnackbar("Exported CSV spreadsheet")
            } catch (_: Exception) {
                showActionSnackbar("Could not export CSV file")
            }
        }
    }

    val exportPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) {
            val ok = LedgerMath.writeLedgerPdfStatement(
                context = context,
                uri = uri,
                snapshot = snapshot,
                appName = "Expense Ledger",
                labelId = pendingPdfLabelId,
                monthKey = pendingPdfMonthKey
            )
            showActionSnackbar(if (ok) "Saved PDF statement" else "Could not generate PDF")
        }
    }

    val exportHtmlLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/html")
    ) { uri ->
        if (uri != null) {
            try {
                val html = LedgerMath.buildStatementHtml(
                    snapshot = snapshot,
                    appName = "Expense Ledger",
                    labelId = pendingHtmlLabelId,
                    monthKey = pendingHtmlMonthKey
                )
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(html.toByteArray(Charsets.UTF_8))
                }
                showActionSnackbar("Saved HTML statement (.html)")
            } catch (_: Exception) {
                showActionSnackbar("Could not save HTML statement")
            }
        }
    }

    val receiptPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            pendingReceiptAttachment = ReceiptAttachmentRecord(
                name = uri.lastPathSegment?.substringAfterLast('/') ?: "Receipt Photo",
                uriOrDataUrl = uri.toString(),
                attachedAt = LedgerMath.nowIso()
            )
        }
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val next = snapshot.copy(dueRemindersEnabled = granted)
        persist(next)
        BillDueScheduler.sync(context, granted)
        showActionSnackbar(if (granted) "Due bill reminders enabled" else "Notification permission denied")
    }

    val importCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                pendingCsvText = raw
                showCsvImportDialog = true
            } catch (_: Exception) {
                showActionSnackbar("Failed to read CSV file")
            }
        }
    }

    val exportEbakLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null && pendingEbakPassphrase.isNotBlank()) {
            try {
                val payload = LedgerMath.encryptEbakJson(dbHelper.encodeSnapshot(snapshot), pendingEbakPassphrase)
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(payload.toByteArray(Charsets.UTF_8))
                }
                pendingEbakPassphrase = ""
                showActionSnackbar("Exported AES-256 encrypted .ebak backup")
            } catch (_: Exception) {
                pendingEbakPassphrase = ""
                showActionSnackbar("Could not export encrypted .ebak backup")
            }
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                if (LedgerMath.isEncryptedEbakText(raw)) {
                    pendingEbakText = raw
                    ebakDialogMode = "restore"
                } else {
                    val restored = dbHelper.validateBackupJson(raw)
                    if (restored != null) {
                        val prev = snapshot
                        persist(restored)
                        selectedExpenseIds = emptySet()
                        bulkMode = false
                        showActionSnackbar("Restored ${restored.expenses.size} expenses from JSON", prev)
                    } else {
                        showActionSnackbar("Invalid ledger backup JSON file")
                    }
                }
            } catch (_: Exception) {
                showActionSnackbar("Failed to read backup file")
            }
        }
    }

    // Global commitment sums remain strictly Expense-only and partial-settlement aware
    val expenseOnly = remember(snapshot.expenses) {
        snapshot.expenses.filter { it.entryType == EntryType.EXPENSE }
    }
    val paidTotal = remember(expenseOnly) {
        expenseOnly.sumOf { LedgerMath.expensePaidPart(it) }
    }
    val holdTotal = remember(expenseOnly) {
        expenseOnly.filter { it.status == ExpenseStatus.HOLD }.sumOf { LedgerMath.expenseRemainingPart(it) }
    }
    val needTotal = remember(expenseOnly) {
        expenseOnly.filter { it.status == ExpenseStatus.NEED }.sumOf { LedgerMath.expenseRemainingPart(it) }
    }
    val grandTotal = paidTotal + holdTotal + needTotal
    val cashflow = remember(snapshot.expenses) {
        LedgerMath.computeCashflowSummary(snapshot.expenses)
    }
    val liveWallets = remember(snapshot) {
        LedgerMath.computeLiveWalletBalances(snapshot)
    }
    val goalProgress = remember(snapshot.savingsGoals, snapshot.goalTransactions) {
        LedgerMath.computeSavingsGoalsProgress(snapshot.savingsGoals, snapshot.goalTransactions)
    }
    val dueAlertSummary = remember(snapshot.expenses) {
        LedgerMath.collectDueAlerts(snapshot.expenses)
    }

    val sysDark = isSystemInDarkTheme()
    val isDark = when (snapshot.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> sysDark
    }
    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = Color(0xFF2563EB),
            onPrimary = Color.White,
            primaryContainer = Color(0xFF1E3A8A),
            onPrimaryContainer = Color(0xFFDBEAFE),
            secondary = Color(0xFF3B82F6),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFF1E293B),
            onSecondaryContainer = Color(0xFF93C5FD),
            background = Color(0xFF020617),
            onBackground = Color(0xFFF8FAFC),
            surface = Color(0xFF0F172A),
            onSurface = Color(0xFFF8FAFC),
            surfaceVariant = Color(0xFF1E293B),
            onSurfaceVariant = Color(0xFF94A3B8),
            outline = Color(0xFF334155),
            outlineVariant = Color(0xFF1E293B)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF1D4ED8),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDBEAFE),
            onPrimaryContainer = Color(0xFF1E40AF),
            secondary = Color(0xFF2563EB),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFEFF6FF),
            onSecondaryContainer = Color(0xFF1D4ED8),
            background = Color(0xFFF8FAFC),
            onBackground = Color(0xFF0F172A),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF0F172A),
            surfaceVariant = Color(0xFFF1F5F9),
            onSurfaceVariant = Color(0xFF475569),
            outline = Color(0xFFCBD5E1),
            outlineVariant = Color(0xFFE2E8F0)
        )
    }

    MaterialTheme(colorScheme = colorScheme) {
        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Expense Ledger",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton(
                                onClick = { showCalculatorSheet = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Calculate, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Calc", fontSize = 12.sp, maxLines = 1)
                            }
                            Box {
                                IconButton(onClick = { showOverflowMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Backup, CSV and share options")
                                }
                                DropdownMenu(
                                    expanded = showOverflowMenu,
                                    onDismissRequest = { showOverflowMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("International Calendar") },
                                        leadingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            showCalendarDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Insights, Cashflow & Goals") },
                                        leadingIcon = { Icon(Icons.Default.BarChart, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            insightsInitialSection = 0
                                            showInsightsDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Auto-Label Rules (${snapshot.autoLabelRules.size})") },
                                        leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            showAutoRulesDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Privacy, PIN & .ebak Vault") },
                                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            showPrivacyDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Recycle Bin (${snapshot.bin.size})") },
                                        leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            showBinDialog = true
                                        }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Share Summary & CSV / PDF") },
                                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            shareLabelId = null
                                            showShareDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Export Statement (.html)") },
                                        leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            pendingHtmlLabelId = null
                                            pendingHtmlMonthKey = null
                                            exportHtmlLauncher.launch("expense-ledger-statement.html")
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Import CSV (.csv)") },
                                        leadingIcon = { Icon(Icons.Default.UploadFile, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            pendingCsvText = ""
                                            showCsvImportDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Export CSV (.csv)") },
                                        leadingIcon = { Icon(Icons.Default.TableChart, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            exportCsvLauncher.launch("expense-ledger-export.csv")
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Export Backup (.json)") },
                                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            exportBackupLauncher.launch("expense-ledger-backup.json")
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Export Encrypted (.ebak)") },
                                        leadingIcon = { Icon(Icons.Default.EnhancedEncryption, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            pendingEbakText = null
                                            ebakDialogMode = "export"
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Restore Backup (.json / .ebak)") },
                                        leadingIcon = { Icon(Icons.Default.Upload, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            importBackupLauncher.launch(arrayOf("application/json", "*/*"))
                                        }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Load Demo Sample Data") },
                                        leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            val prev = snapshot
                                            persist(dbHelper.createSeedSnapshot().copy(themeMode = snapshot.themeMode))
                                            showActionSnackbar("Loaded demo sample data", prev)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Clear All Data (Empty Ledger)", color = Color(0xFFDC2626)) },
                                        leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = Color(0xFFDC2626)) },
                                        onClick = {
                                            showOverflowMenu = false
                                            showResetConfirmDialog = true
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { persist(snapshot.copy(hideBalances = !snapshot.hideBalances)) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = if (snapshot.hideBalances) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (snapshot.hideBalances) "Show balances" else "Hide balances",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = { showCalendarDialog = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = "1-Click International Calendar", modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = { showPrivacyDialog = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = "Privacy PIN Lock",
                                tint = if (!snapshot.privacyPinHash.isNullOrBlank()) Color(0xFF059669) else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                insightsInitialSection = 0
                                showInsightsDialog = true
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.BarChart, contentDescription = "Insights and cashflow", modifier = Modifier.size(20.dp))
                        }
                        IconButton(
                            onClick = { showBinDialog = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            BadgedBox(
                                badge = {
                                    if (snapshot.bin.isNotEmpty()) {
                                        Badge { Text("${snapshot.bin.size}") }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Recycle Bin", modifier = Modifier.size(20.dp))
                            }
                        }
                        IconButton(
                            onClick = {
                                val nextMode = if (isDark) ThemeMode.LIGHT else ThemeMode.DARK
                                persist(snapshot.copy(themeMode = nextMode))
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "Toggle theme",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SummaryStatBox("PAID (₹)", paidTotal, Color(0xFF059669), snapshot.hideBalances, Modifier.weight(1f))
                        SummaryStatBox("HOLD (₹)", holdTotal, Color(0xFFD97706), snapshot.hideBalances, Modifier.weight(1f))
                        SummaryStatBox("NEED (₹)", needTotal, Color(0xFFDC2626), snapshot.hideBalances, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Grand Commitment",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "₹${LedgerMath.formatDisplayAmt(grandTotal, snapshot.hideBalances)} · ${expenseOnly.size} exp",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (cashflow.incomeCount > 0) {
                                Text(
                                    text = " · Net ",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val netSign = if (cashflow.netCashflow >= 0.0) "+" else "-"
                                Text(
                                    text = "${netSign}₹${LedgerMath.formatDisplayAmt(kotlin.math.abs(cashflow.netCashflow), snapshot.hideBalances)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (cashflow.netCashflow >= 0.0) Color(0xFF059669) else Color(0xFFDC2626)
                                )
                            }
                        }
                    }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    LedgerTab.entries.forEach { tab ->
                        val icon = when (tab) {
                            LedgerTab.LISTS -> Icons.Default.Checklist
                            LedgerTab.EXPENSES -> Icons.Default.ReceiptLong
                            LedgerTab.LABELS -> Icons.Default.Label
                        }
                        NavigationBarItem(
                            selected = activeTab == tab,
                            onClick = {
                                activeTab = tab
                                if (tab != LedgerTab.EXPENSES) {
                                    bulkMode = false
                                    selectedExpenseIds = emptySet()
                                }
                            },
                            icon = { Icon(icon, contentDescription = tab.title) },
                            label = { Text(tab.title) }
                        )
                    }
                }
            },
            floatingActionButton = {
                if (!(activeTab == LedgerTab.EXPENSES && bulkMode && selectedExpenseIds.isNotEmpty())) {
                    FloatingActionButton(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White,
                        shape = RoundedCornerShape(16.dp),
                        onClick = {
                            when (activeTab) {
                                LedgerTab.EXPENSES -> {
                                    editingExpense = null
                                    prefillBalance = ""
                                    prefillQuote = ""
                                    prefillStatus = null
                                    prefillEntryType = EntryType.EXPENSE
                                    prefillPaymentMode = PaymentMode.UPI
                                    prefillIsPinned = false
                                    prefillDueDate = ""
                                    prefillLabelIds = emptyList()
                                    prefillAttachedListIds = emptyList()
                                    prefillCalcHistoryId = null
                                    prefillCalcFormula = null
                                    pendingReceiptAttachment = null
                                    prefillSplitShares = emptyList()
                                    showExpenseModal = true
                                }
                                LedgerTab.LABELS -> {
                                    editingLabel = null
                                    showLabelModal = true
                                }
                                LedgerTab.LISTS -> {
                                    editingList = null
                                    prefillListTitle = ""
                                    prefillListContent = ""
                                    showListModal = true
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Create new")
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (activeTab) {
                    LedgerTab.EXPENSES -> ExpensesScreen(
                        expenses = snapshot.expenses,
                        labels = snapshot.labels,
                        lists = snapshot.lists,
                        templates = snapshot.templates,
                        liveWallets = liveWallets,
                        goalProgress = goalProgress,
                        dueAlertSummary = dueAlertSummary,
                        dueRemindersEnabled = snapshot.dueRemindersEnabled,
                        showDueBanner = showDueBanner,
                        hideBalances = snapshot.hideBalances,
                        isDark = isDark,
                        searchQuery = searchQuery,
                        statusFilter = statusFilter,
                        entryTypeFilter = entryTypeFilter,
                        paymentModeFilter = paymentModeFilter,
                        dateWindowFilter = dateWindowFilter,
                        sortOrder = sortOrder,
                        exactDateFilter = exactDateFilter,
                        pinnedOnlyFilter = pinnedOnlyFilter,
                        labelFilterId = labelFilterId,
                        bulkMode = bulkMode,
                        selectedExpenseIds = selectedExpenseIds,
                        showQuickTemplates = showQuickTemplates,
                        onDismissDueBanner = { showDueBanner = false },
                        onOpenInsightsSection = { secIdx ->
                            insightsInitialSection = secIdx
                            showInsightsDialog = true
                        },
                        onSearchQueryChange = { searchQuery = it },
                        onStatusFilterChange = { statusFilter = it },
                        onEntryTypeFilterChange = { entryTypeFilter = it },
                        onPaymentModeFilterChange = { paymentModeFilter = it },
                        onDateWindowFilterChange = { dateWindowFilter = it },
                        onSortOrderChange = { sortOrder = it },
                        onClearExactDate = { exactDateFilter = null },
                        onPinnedOnlyFilterChange = { pinnedOnlyFilter = it },
                        onLabelFilterChange = { labelFilterId = it },
                        onClearFilters = {
                            searchQuery = ""
                            statusFilter = null
                            entryTypeFilter = null
                            paymentModeFilter = null
                            dateWindowFilter = DateWindowFilter.ALL
                            sortOrder = ExpenseSortOrder.NEWEST
                            exactDateFilter = null
                            pinnedOnlyFilter = false
                            labelFilterId = null
                        },
                        onToggleBulkMode = {
                            bulkMode = !bulkMode
                            if (!bulkMode) selectedExpenseIds = emptySet()
                        },
                        onToggleSelectId = { id ->
                            selectedExpenseIds = if (selectedExpenseIds.contains(id)) selectedExpenseIds - id else selectedExpenseIds + id
                        },
                        onToggleSelectAll = { visibleIds ->
                            val allSelected = visibleIds.isNotEmpty() && visibleIds.all { selectedExpenseIds.contains(it) }
                            selectedExpenseIds = if (allSelected) emptySet() else visibleIds.toSet()
                        },
                        onBulkStatus = { targetStatus ->
                            if (selectedExpenseIds.isNotEmpty()) {
                                val prev = snapshot
                                val now = LedgerMath.nowIso()
                                val count = selectedExpenseIds.size
                                val updated = snapshot.expenses.map { item ->
                                    if (selectedExpenseIds.contains(item.id) && item.status != targetStatus) {
                                        val nextPaid = if (targetStatus == ExpenseStatus.PAID) {
                                            item.balance
                                        } else {
                                            minOf(item.balance, LedgerMath.trackedPartialSum(item))
                                        }
                                        item.copy(
                                            status = targetStatus,
                                            paidAmount = nextPaid,
                                            updatedAt = now,
                                            auditLog = item.auditLog + AuditEntry(
                                                action = "Updated",
                                                at = now,
                                                summary = "Bulk status changed to ${targetStatus.label}"
                                            )
                                        )
                                    } else item
                                }
                                persist(snapshot.copy(expenses = updated))
                                bulkMode = false
                                selectedExpenseIds = emptySet()
                                showActionSnackbar("Marked $count entries ${targetStatus.label}", prev)
                            }
                        },
                        onBulkMoveToBin = {
                            if (selectedExpenseIds.isNotEmpty()) {
                                val prev = snapshot
                                val count = selectedExpenseIds.size
                                val next = dbHelper.moveExpensesBulkToBin(snapshot, selectedExpenseIds)
                                persist(next)
                                bulkMode = false
                                selectedExpenseIds = emptySet()
                                showActionSnackbar("Moved $count entries to Bin", prev)
                            }
                        },
                        onToggleQuickTemplates = { showQuickTemplates = !showQuickTemplates },
                        onLogTemplate = { tpl ->
                            val prev = snapshot
                            val now = LedgerMath.nowIso()
                            val created = ExpenseRecord(
                                balance = tpl.balance,
                                quote = tpl.title,
                                status = if (tpl.entryType == EntryType.INCOME) ExpenseStatus.PAID else tpl.defaultStatus,
                                labelIds = tpl.labelIds,
                                attachedListIds = tpl.attachedListIds,
                                calcHistoryId = null,
                                calcFormula = null,
                                createdAt = now,
                                updatedAt = now,
                                auditLog = listOf(
                                    AuditEntry(
                                        action = "Created",
                                        at = now,
                                        summary = "1-tap template log: ₹${formatAmount(tpl.balance)} (${tpl.paymentMode.label})"
                                    )
                                ),
                                entryType = tpl.entryType,
                                paymentMode = tpl.paymentMode,
                                isPinned = false,
                                dueDate = null,
                                paidAmount = if (tpl.entryType == EntryType.INCOME || tpl.defaultStatus == ExpenseStatus.PAID) tpl.balance else 0.0
                            )
                            val updatedTemplates = snapshot.templates.map { item ->
                                if (item.id == tpl.id) item.copy(lastUsedAt = now) else item
                            }
                            persist(snapshot.copy(expenses = listOf(created) + snapshot.expenses, templates = updatedTemplates))
                            showActionSnackbar("Logged '${tpl.title}' from template", prev)
                        },
                        onMoveTemplateToBin = { tplId ->
                            val target = snapshot.templates.firstOrNull { it.id == tplId }
                            val prev = snapshot
                            persist(dbHelper.moveTemplateToBin(snapshot, tplId))
                            if (target != null) {
                                showActionSnackbar("Moved template '${target.title}' to Bin", prev)
                            }
                        },
                        onCycleStatus = { exp ->
                            if (exp.entryType == EntryType.INCOME) return@ExpensesScreen
                            val updatedExpenses = snapshot.expenses.map { item ->
                                if (item.id == exp.id) LedgerMath.cycleStatus(item) else item
                            }
                            persist(snapshot.copy(expenses = updatedExpenses))
                        },
                        onTogglePin = { exp ->
                            val now = LedgerMath.nowIso()
                            val nextPin = !exp.isPinned
                            val updatedExpenses = snapshot.expenses.map { item ->
                                if (item.id == exp.id) {
                                    item.copy(
                                        isPinned = nextPin,
                                        updatedAt = now,
                                        auditLog = item.auditLog + AuditEntry(
                                            action = "Updated",
                                            at = now,
                                            summary = if (nextPin) "Pinned to top" else "Unpinned"
                                        )
                                    )
                                } else item
                            }
                            persist(snapshot.copy(expenses = updatedExpenses))
                        },
                        onPartialPay = { exp -> partialPayTarget = exp },
                        onViewReceipt = { exp -> receiptLightboxRecord = exp },
                        onDuplicate = { exp ->
                            val prev = snapshot
                            val now = LedgerMath.nowIso()
                            val copy = ExpenseRecord(
                                balance = exp.balance,
                                quote = "${exp.quote} (Copy)",
                                status = if (exp.entryType == EntryType.INCOME) ExpenseStatus.PAID else ExpenseStatus.NEED,
                                labelIds = exp.labelIds,
                                attachedListIds = exp.attachedListIds,
                                calcHistoryId = exp.calcHistoryId,
                                calcFormula = exp.calcFormula,
                                createdAt = now,
                                updatedAt = now,
                                auditLog = listOf(
                                    AuditEntry(
                                        action = "Created",
                                        at = now,
                                        summary = "Duplicated ₹${formatAmount(exp.balance)} (${exp.paymentMode.label})"
                                    )
                                ),
                                entryType = exp.entryType,
                                paymentMode = exp.paymentMode,
                                isPinned = false,
                                dueDate = exp.dueDate
                            )
                            persist(snapshot.copy(expenses = listOf(copy) + snapshot.expenses))
                            showActionSnackbar("Duplicated '${exp.quote}'", prev)
                        },
                        onEdit = { exp ->
                            editingExpense = exp
                            prefillBalance = formatAmount(exp.balance)
                            prefillQuote = exp.quote
                            prefillStatus = exp.status
                            prefillEntryType = exp.entryType
                            prefillPaymentMode = exp.paymentMode
                            prefillIsPinned = exp.isPinned
                            prefillDueDate = exp.dueDate ?: ""
                            prefillLabelIds = exp.labelIds
                            prefillAttachedListIds = exp.attachedListIds
                            prefillCalcHistoryId = exp.calcHistoryId
                            prefillCalcFormula = exp.calcFormula
                            pendingReceiptAttachment = exp.receiptAttachment
                            prefillSplitShares = exp.splitShares
                            showExpenseModal = true
                        },
                        onDelete = { expId ->
                            val target = snapshot.expenses.firstOrNull { it.id == expId }
                            val prev = snapshot
                            persist(dbHelper.moveExpenseToBin(snapshot, expId))
                            if (target != null) {
                                showActionSnackbar("Moved '${target.quote}' to Bin", prev)
                            }
                        },
                        onToggleChecklistLine = { listId, lineIdx ->
                            val now = LedgerMath.nowIso()
                            val nextLists = snapshot.lists.map { lst ->
                                if (lst.id == listId) {
                                    lst.copy(
                                        content = LedgerMath.toggleChecklistLine(lst.content, lineIdx),
                                        updatedAt = now
                                    )
                                } else lst
                            }
                            persist(snapshot.copy(lists = nextLists))
                        },
                        onShareReceiptCard = { exp ->
                            shareExpenseReceiptTextCard(context, "Expense Ledger", exp)
                        },
                        onLoadDemoData = {
                            val prev = snapshot
                            persist(dbHelper.createSeedSnapshot().copy(themeMode = snapshot.themeMode))
                            showActionSnackbar("Loaded demo sample data", prev)
                        },
                        onAddFirstExpense = {
                            editingExpense = null
                            prefillBalance = ""
                            prefillQuote = ""
                            prefillStatus = null
                            prefillEntryType = EntryType.EXPENSE
                            prefillPaymentMode = PaymentMode.UPI
                            prefillIsPinned = false
                            prefillDueDate = ""
                            prefillLabelIds = emptyList()
                            prefillAttachedListIds = emptyList()
                            prefillCalcHistoryId = null
                            prefillCalcFormula = null
                            pendingReceiptAttachment = null
                            prefillSplitShares = emptyList()
                            showExpenseModal = true
                        }
                    )
                    LedgerTab.LABELS -> LabelsScreen(
                        labels = snapshot.labels,
                        expenses = snapshot.expenses,
                        hideBalances = snapshot.hideBalances,
                        onEditLabel = { lbl ->
                            editingLabel = lbl
                            showLabelModal = true
                        },
                        onDeleteLabel = { lblId ->
                            val targetName = snapshot.labels.firstOrNull { it.id == lblId }?.name ?: "Label"
                            val prev = snapshot
                            persist(dbHelper.moveLabelToBin(snapshot, lblId))
                            if (labelFilterId == lblId) labelFilterId = null
                            showActionSnackbar("Moved label '$targetName' to Bin", prev)
                        },
                        onAddExpenseForLabel = { lblId ->
                            editingExpense = null
                            prefillBalance = ""
                            prefillQuote = ""
                            prefillStatus = null
                            prefillEntryType = EntryType.EXPENSE
                            prefillPaymentMode = PaymentMode.UPI
                            prefillIsPinned = false
                            prefillDueDate = ""
                            prefillLabelIds = listOf(lblId)
                            prefillAttachedListIds = emptyList()
                            prefillCalcHistoryId = null
                            prefillCalcFormula = null
                            pendingReceiptAttachment = null
                            prefillSplitShares = emptyList()
                            activeTab = LedgerTab.EXPENSES
                            showExpenseModal = true
                        },
                        onShareLabel = { lblId ->
                            shareLabelId = lblId
                            showShareDialog = true
                        }
                    )
                    LedgerTab.LISTS -> ListsScreen(
                        lists = snapshot.lists,
                        hideBalances = snapshot.hideBalances,
                        onToggleLine = { listId, lineIdx ->
                            val now = LedgerMath.nowIso()
                            val nextLists = snapshot.lists.map { lst ->
                                if (lst.id == listId) {
                                    lst.copy(
                                        content = LedgerMath.toggleChecklistLine(lst.content, lineIdx),
                                        updatedAt = now
                                    )
                                } else lst
                            }
                            persist(snapshot.copy(lists = nextLists))
                        },
                        onEditList = { lst ->
                            editingList = lst
                            prefillListTitle = lst.title
                            prefillListContent = lst.content
                            showListModal = true
                        },
                        onDeleteList = { listId ->
                            val targetTitle = snapshot.lists.firstOrNull { it.id == listId }?.title ?: "List"
                            val prev = snapshot
                            persist(dbHelper.moveListToBin(snapshot, listId))
                            showActionSnackbar("Moved list '$targetTitle' to Bin", prev)
                        },
                        onUseInExpense = { lst, detectedSum ->
                            editingExpense = null
                            prefillBalance = if (detectedSum > 0.0) formatAmount(detectedSum) else ""
                            prefillQuote = lst.title
                            prefillStatus = null
                            prefillEntryType = EntryType.EXPENSE
                            prefillPaymentMode = PaymentMode.UPI
                            prefillIsPinned = false
                            prefillDueDate = ""
                            prefillLabelIds = emptyList()
                            prefillAttachedListIds = listOf(lst.id)
                            prefillCalcHistoryId = null
                            prefillCalcFormula = null
                            pendingReceiptAttachment = null
                            prefillSplitShares = emptyList()
                            activeTab = LedgerTab.EXPENSES
                            showExpenseModal = true
                        }
                    )
                }
            }
        }

        if (showExpenseModal) {
            ExpenseEditorDialog(
                existing = editingExpense,
                initialBalance = prefillBalance,
                initialQuote = prefillQuote,
                initialStatus = prefillStatus,
                initialEntryType = prefillEntryType,
                initialPaymentMode = prefillPaymentMode,
                initialIsPinned = prefillIsPinned,
                initialDueDate = prefillDueDate,
                initialLabelIds = prefillLabelIds,
                initialAttachedListIds = prefillAttachedListIds,
                initialSplitShares = prefillSplitShares,
                calcFormula = prefillCalcFormula,
                receiptAttachment = pendingReceiptAttachment,
                expensesHistory = snapshot.expenses,
                autoRules = snapshot.autoLabelRules,
                labels = snapshot.labels,
                lists = snapshot.lists,
                onPickReceiptPhoto = {
                    receiptPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onClearReceiptPhoto = { pendingReceiptAttachment = null },
                onDismiss = { showExpenseModal = false },
                onSave = { balance, quote, status, labelIds, attachedListIds, saveAsTemplate, recurrence, entryType, paymentMode, isPinned, dueDate, splitShares ->
                    val now = LedgerMath.nowIso()
                    val finalStatus = if (entryType == EntryType.INCOME) ExpenseStatus.PAID else status
                    val normalizedDue = dueDate.trim().takeIf { Regex("""^\d{4}-\d{2}-\d{2}$""").matches(it) }
                    val nextExpenses = if (editingExpense == null) {
                        val entry = ExpenseRecord(
                            balance = balance,
                            quote = quote,
                            status = finalStatus,
                            labelIds = labelIds,
                            attachedListIds = attachedListIds,
                            calcHistoryId = prefillCalcHistoryId,
                            calcFormula = prefillCalcFormula,
                            createdAt = now,
                            updatedAt = now,
                            auditLog = listOf(AuditEntry(action = "Created", at = now, summary = "Logged ${entryType.label} ₹${formatAmount(balance)} (${paymentMode.label})")),
                            entryType = entryType,
                            paymentMode = paymentMode,
                            isPinned = isPinned,
                            dueDate = normalizedDue,
                            paidAmount = if (finalStatus == ExpenseStatus.PAID) balance else 0.0,
                            receiptAttachment = pendingReceiptAttachment,
                            splitShares = splitShares
                        )
                        listOf(entry) + snapshot.expenses
                    } else {
                        snapshot.expenses.map { item ->
                            if (item.id == editingExpense!!.id) {
                                val nextPaid = if (finalStatus == ExpenseStatus.PAID) {
                                    balance
                                } else if (item.status == ExpenseStatus.PAID) {
                                    minOf(balance, LedgerMath.trackedPartialSum(item))
                                } else {
                                    item.paidAmount.coerceIn(0.0, balance)
                                }
                                item.copy(
                                    balance = balance,
                                    quote = quote,
                                    status = finalStatus,
                                    labelIds = labelIds,
                                    attachedListIds = attachedListIds,
                                    calcHistoryId = prefillCalcHistoryId,
                                    calcFormula = prefillCalcFormula,
                                    updatedAt = now,
                                    auditLog = item.auditLog + AuditEntry(action = "Updated", at = now, summary = "Updated ${entryType.label} ₹${formatAmount(balance)} (${paymentMode.label})"),
                                    entryType = entryType,
                                    paymentMode = paymentMode,
                                    isPinned = isPinned,
                                    dueDate = normalizedDue,
                                    paidAmount = nextPaid,
                                    receiptAttachment = pendingReceiptAttachment,
                                    splitShares = splitShares
                                )
                            } else item
                        }
                    }
                    val nextTemplates = if (saveAsTemplate) {
                        val tpl = ExpenseTemplateRecord(
                            title = quote,
                            balance = balance,
                            defaultStatus = finalStatus,
                            labelIds = labelIds,
                            attachedListIds = attachedListIds,
                            recurrence = recurrence,
                            lastUsedAt = now,
                            createdAt = now,
                            entryType = entryType,
                            paymentMode = paymentMode
                        )
                        listOf(tpl) + snapshot.templates
                    } else {
                        snapshot.templates
                    }
                    persist(snapshot.copy(expenses = nextExpenses, templates = nextTemplates))
                    showExpenseModal = false
                }
            )
        }

        if (showLabelModal) {
            LabelEditorDialog(
                existing = editingLabel,
                onDismiss = { showLabelModal = false },
                onSave = { name, desc, colorHex, monthlyBudget ->
                    val now = LedgerMath.nowIso()
                    val normalizedName = if (name.startsWith("#")) name else "#$name"
                    val nextLabels = if (editingLabel == null) {
                        val newLbl = LabelRecord(
                            name = normalizedName,
                            description = desc,
                            colorHex = colorHex,
                            monthlyBudget = monthlyBudget,
                            createdAt = now,
                            updatedAt = now,
                            auditLog = listOf(AuditEntry(action = "Created", at = now, summary = "Created $normalizedName"))
                        )
                        snapshot.labels + newLbl
                    } else {
                        snapshot.labels.map { l ->
                            if (l.id == editingLabel!!.id) {
                                l.copy(
                                    name = normalizedName,
                                    description = desc,
                                    colorHex = colorHex,
                                    monthlyBudget = monthlyBudget,
                                    updatedAt = now,
                                    auditLog = l.auditLog + AuditEntry(action = "Updated", at = now, summary = "Edited $normalizedName")
                                )
                            } else l
                        }
                    }
                    persist(snapshot.copy(labels = nextLabels))
                    showLabelModal = false
                }
            )
        }

        if (showListModal) {
            ListEditorDialog(
                existing = editingList,
                initialTitle = prefillListTitle,
                initialContent = prefillListContent,
                onDismiss = { showListModal = false },
                onSave = { title, content ->
                    val now = LedgerMath.nowIso()
                    val autoSum = LedgerMath.extractNumbersSum(content)
                    val nextLists = if (editingList == null) {
                        val newList = ListNoteRecord(
                            title = title,
                            content = content,
                            createdAt = now,
                            updatedAt = now,
                            auditLog = listOf(AuditEntry(action = "Created", at = now, summary = "Created list (₹${formatAmount(autoSum)})"))
                        )
                        listOf(newList) + snapshot.lists
                    } else {
                        snapshot.lists.map { lst ->
                            if (lst.id == editingList!!.id) {
                                lst.copy(
                                    title = title,
                                    content = content,
                                    updatedAt = now,
                                    auditLog = lst.auditLog + AuditEntry(action = "Updated", at = now, summary = "Updated list (₹${formatAmount(autoSum)})")
                                )
                            } else lst
                        }
                    }
                    persist(snapshot.copy(lists = nextLists))
                    showListModal = false
                }
            )
        }

        if (showCalculatorSheet) {
            ModalBottomSheet(onDismissRequest = { showCalculatorSheet = false }) {
                CalculatorSheetContent(
                    history = snapshot.calculatorHistory,
                    hideBalances = snapshot.hideBalances,
                    onRecordHistory = { expr, result ->
                        val now = LedgerMath.nowIso()
                        val entry = CalcHistoryRecord(
                            expression = expr,
                            result = result,
                            createdAt = now,
                            updatedAt = now
                        )
                        persist(snapshot.copy(calculatorHistory = listOf(entry) + snapshot.calculatorHistory))
                    },
                    onSendToExpense = { result, formula, historyId ->
                        showCalculatorSheet = false
                        activeTab = LedgerTab.EXPENSES
                        editingExpense = null
                        prefillBalance = formatAmount(result)
                        prefillQuote = if (formula.isNotBlank()) "Calc: $formula" else "Calc: ${formatAmount(result)}"
                        prefillStatus = null
                        prefillEntryType = EntryType.EXPENSE
                        prefillPaymentMode = PaymentMode.UPI
                        prefillIsPinned = false
                        prefillDueDate = ""
                        prefillLabelIds = emptyList()
                        prefillAttachedListIds = emptyList()
                        prefillCalcHistoryId = historyId ?: snapshot.calculatorHistory.firstOrNull()?.id
                        prefillCalcFormula = formula.ifBlank { null }
                        pendingReceiptAttachment = null
                        prefillSplitShares = emptyList()
                        showExpenseModal = true
                    },
                    onInsertIntoList = { result, formula ->
                        showCalculatorSheet = false
                        activeTab = LedgerTab.LISTS
                        editingList = null
                        prefillListTitle = "Calculated Breakdown"
                        val prefix = formula.ifBlank { "Item" }
                        prefillListContent = "$prefix -> ₹${formatAmount(result)}"
                        showListModal = true
                    }
                )
            }
        }

        if (showShareDialog) {
            ShareSummaryDialog(
                appName = "Expense Ledger",
                snapshot = snapshot,
                initialLabelId = shareLabelId,
                onDismiss = { showShareDialog = false },
                onExportCsv = {
                    showShareDialog = false
                    exportCsvLauncher.launch("expense-ledger-export.csv")
                },
                onImportCsv = {
                    showShareDialog = false
                    pendingCsvText = ""
                    showCsvImportDialog = true
                },
                onExportPdf = { lblId, monthKey ->
                    pendingPdfLabelId = lblId
                    pendingPdfMonthKey = monthKey
                    showShareDialog = false
                    exportPdfLauncher.launch("expense-ledger-statement.pdf")
                },
                onExportHtml = { lblId, monthKey ->
                    pendingHtmlLabelId = lblId
                    pendingHtmlMonthKey = monthKey
                    showShareDialog = false
                    exportHtmlLauncher.launch("expense-ledger-statement.html")
                },
                onShareText = { summaryText ->
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, summaryText)
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "Share Ledger Summary"))
                    showShareDialog = false
                }
            )
        }

        if (showResetConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showResetConfirmDialog = false },
                title = { Text("Clear All Data?") },
                text = { Text("This clears all expenses, labels, lists, templates, goals, and wallet balances to a clean empty ledger (₹0). You can Undo immediately after.") },
                confirmButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        onClick = {
                            val prev = snapshot
                            val fresh = dbHelper.createEmptySnapshot().copy(themeMode = snapshot.themeMode)
                            persist(fresh)
                            searchQuery = ""
                            statusFilter = null
                            entryTypeFilter = null
                            paymentModeFilter = null
                            dateWindowFilter = DateWindowFilter.ALL
                            sortOrder = ExpenseSortOrder.NEWEST
                            exactDateFilter = null
                            pinnedOnlyFilter = false
                            labelFilterId = null
                            bulkMode = false
                            selectedExpenseIds = emptySet()
                            showDueBanner = true
                            showResetConfirmDialog = false
                            showActionSnackbar("Cleared all data to empty ledger", prev)
                        }
                    ) {
                        Text("Clear All Data", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirmDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showInsightsDialog) {
            InsightsDialog(
                snapshot = snapshot,
                initialSection = insightsInitialSection,
                onUpdateOpeningBalances = { nextBalances ->
                    persist(snapshot.copy(walletStartingBalances = nextBalances))
                    showActionSnackbar("Updated wallet opening balances")
                },
                onCreateTransfer = { fromMode, toMode, amt, note ->
                    if (amt > 0.0 && fromMode != toMode) {
                        val trf = WalletTransferRecord(
                            fromMode = fromMode,
                            toMode = toMode,
                            amount = amt,
                            note = note.ifBlank { "${fromMode.label} → ${toMode.label} transfer" },
                            at = LedgerMath.nowIso()
                        )
                        persist(snapshot.copy(walletTransfers = listOf(trf) + snapshot.walletTransfers))
                        showActionSnackbar("Transferred ₹${formatAmount(amt)}")
                    }
                },
                onDeleteTransfer = { trfId ->
                    persist(snapshot.copy(walletTransfers = snapshot.walletTransfers.filterNot { it.id == trfId }))
                },
                onRecordIouRepayment = { friendName, amt, walletMode, note ->
                    persist(LedgerMath.recordIouRepayment(snapshot, friendName, amt, walletMode, note))
                    showActionSnackbar("Recorded ₹${formatAmount(amt)} IOU repayment from $friendName")
                },
                onCreateGoal = { title, targetAmt, targetDate ->
                    val cleanTitle = title.trim()
                    if (cleanTitle.isNotBlank() && targetAmt > 0.0) {
                        val goal = SavingsGoalItem(
                            title = cleanTitle,
                            targetAmount = targetAmt,
                            targetDate = targetDate.trim().takeIf { Regex("""^\d{4}-\d{2}-\d{2}$""").matches(it) },
                            createdAt = LedgerMath.nowIso()
                        )
                        persist(snapshot.copy(savingsGoals = listOf(goal) + snapshot.savingsGoals))
                        showActionSnackbar("Created goal '$cleanTitle'")
                    }
                },
                onGoalTransaction = { goalId, kind, amt, walletMode ->
                    persist(LedgerMath.recordGoalTransaction(snapshot, goalId, kind, amt, walletMode))
                    showActionSnackbar(if (kind == "withdraw") "Withdrew ₹${formatAmount(amt)} from goal" else "Deposited ₹${formatAmount(amt)} into goal")
                },
                onOpenInternationalCalendar = {
                    showInsightsDialog = false
                    showCalendarDialog = true
                },
                onDismiss = { showInsightsDialog = false }
            )
        }

        if (showCalendarDialog) {
            InternationalCalendarDialog(
                appName = "Expense Ledger",
                snapshot = snapshot,
                onUpdatePrefs = { nextPrefs -> persist(snapshot.copy(calendarPrefs = nextPrefs)) },
                onLogOnDate = { dateKey ->
                    showCalendarDialog = false
                    activeTab = LedgerTab.EXPENSES
                    editingExpense = null
                    prefillBalance = ""
                    prefillQuote = ""
                    prefillStatus = ExpenseStatus.NEED
                    prefillEntryType = EntryType.EXPENSE
                    prefillPaymentMode = PaymentMode.UPI
                    prefillIsPinned = false
                    prefillDueDate = dateKey
                    prefillLabelIds = emptyList()
                    prefillAttachedListIds = emptyList()
                    prefillCalcHistoryId = null
                    prefillCalcFormula = null
                    pendingReceiptAttachment = null
                    prefillSplitShares = emptyList()
                    showExpenseModal = true
                },
                onFilterFeedByDate = { dateKey ->
                    exactDateFilter = dateKey
                    activeTab = LedgerTab.EXPENSES
                    showCalendarDialog = false
                },
                onPartialPay = { exp ->
                    showCalendarDialog = false
                    partialPayTarget = exp
                },
                onLogTemplateNow = { tpl ->
                    val now = LedgerMath.nowIso()
                    val created = ExpenseRecord(
                        balance = tpl.balance,
                        quote = tpl.title,
                        status = if (tpl.entryType == EntryType.INCOME) ExpenseStatus.PAID else tpl.defaultStatus,
                        labelIds = tpl.labelIds,
                        attachedListIds = tpl.attachedListIds,
                        createdAt = now,
                        updatedAt = now,
                        auditLog = listOf(AuditEntry(action = "Created", at = now, summary = "Calendar recurring log: ₹${formatAmount(tpl.balance)}")),
                        entryType = tpl.entryType,
                        paymentMode = tpl.paymentMode,
                        paidAmount = if (tpl.entryType == EntryType.INCOME || tpl.defaultStatus == ExpenseStatus.PAID) tpl.balance else 0.0
                    )
                    val updatedTpls = snapshot.templates.map { if (it.id == tpl.id) it.copy(lastUsedAt = now) else it }
                    persist(snapshot.copy(expenses = listOf(created) + snapshot.expenses, templates = updatedTpls))
                    showActionSnackbar("Logged '${tpl.title}' from Calendar")
                },
                onShareIcs = { icsText ->
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/calendar"
                        putExtra(Intent.EXTRA_SUBJECT, "Expense Ledger Calendar (.ics)")
                        putExtra(Intent.EXTRA_TEXT, icsText)
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "Export Calendar (.ics)"))
                },
                onDismiss = { showCalendarDialog = false }
            )
        }

        if (showAutoRulesDialog) {
            AutoRulesDialog(
                rules = snapshot.autoLabelRules,
                labels = snapshot.labels,
                onSaveRules = { nextRules ->
                    persist(snapshot.copy(autoLabelRules = nextRules))
                    showActionSnackbar("Saved ${nextRules.size} auto-label rules")
                },
                onDismiss = { showAutoRulesDialog = false }
            )
        }

        if (ebakDialogMode != null) {
            EncryptedBackupDialog(
                mode = ebakDialogMode!!,
                onDismiss = {
                    ebakDialogMode = null
                    pendingEbakText = null
                },
                onExportPassphrase = { pass ->
                    pendingEbakPassphrase = pass
                    ebakDialogMode = null
                    exportEbakLauncher.launch("expense-ledger-backup.ebak")
                },
                onDecryptPassphrase = { pass ->
                    val raw = pendingEbakText.orEmpty()
                    val decryptedJson = LedgerMath.decryptEbakJson(raw, pass)
                    if (decryptedJson == null) {
                        false
                    } else {
                        val restored = dbHelper.validateBackupJson(decryptedJson)
                        if (restored != null) {
                            val prev = snapshot
                            persist(restored)
                            ebakDialogMode = null
                            pendingEbakText = null
                            showActionSnackbar("Decrypted & restored ${restored.expenses.size} expenses", prev)
                            true
                        } else {
                            false
                        }
                    }
                }
            )
        }

        if (showBinDialog) {
            RecycleBinDialog(
                snapshot = snapshot,
                onDismiss = { showBinDialog = false },
                onChangeRetention = { nextDays ->
                    val pruned = LedgerMath.pruneExpiredBin(snapshot.bin, nextDays)
                    persist(snapshot.copy(binRetentionDays = nextDays, bin = pruned))
                },
                onRestore = { record ->
                    val restored = dbHelper.restoreFromBin(snapshot, record)
                    persist(restored)
                    showActionSnackbar("Restored '${record.title}'")
                },
                onDeletePermanent = { recordId ->
                    persist(snapshot.copy(bin = snapshot.bin.filterNot { it.id == recordId }))
                    showActionSnackbar("Permanently deleted item")
                },
                onEmptyBin = {
                    val cleared = snapshot.bin.size
                    persist(snapshot.copy(bin = emptyList()))
                    showActionSnackbar("Permanently emptied $cleared items from Bin")
                }
            )
        }

        if (showPrivacyDialog) {
            PrivacyPinDialog(
                snapshot = snapshot,
                onDismiss = { showPrivacyDialog = false },
                onToggleHideBalances = { hidden ->
                    persist(snapshot.copy(hideBalances = hidden))
                },
                onToggleBiometric = { enabled ->
                    persist(snapshot.copy(biometricEnabled = enabled))
                },
                onChangeThemeMode = { nextMode ->
                    persist(snapshot.copy(themeMode = nextMode))
                },
                onChangeAutoLock = { secs ->
                    persist(snapshot.copy(autoLockSeconds = secs))
                },
                onToggleDueReminders = { enabled ->
                    if (enabled && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        persist(snapshot.copy(dueRemindersEnabled = enabled))
                        BillDueScheduler.sync(context, enabled)
                    }
                },
                onOpenAutoRules = {
                    showPrivacyDialog = false
                    showAutoRulesDialog = true
                },
                onExportEbak = {
                    showPrivacyDialog = false
                    pendingEbakText = null
                    ebakDialogMode = "export"
                },
                onImportEbak = {
                    showPrivacyDialog = false
                    importBackupLauncher.launch(arrayOf("*/*"))
                },
                onSetPin = { newPin ->
                    persist(snapshot.copy(privacyPinHash = LedgerMath.sha256Pin(newPin)))
                    showActionSnackbar("4-digit Privacy PIN enabled")
                },
                onRemovePin = {
                    persist(snapshot.copy(privacyPinHash = null, biometricEnabled = false))
                    isLocked = false
                    showActionSnackbar("Privacy PIN disabled")
                },
                onLockNow = {
                    showPrivacyDialog = false
                    if (!snapshot.privacyPinHash.isNullOrBlank()) {
                        isLocked = true
                    }
                }
            )
        }

        if (showCsvImportDialog) {
            CsvImportDialog(
                initialCsvText = pendingCsvText,
                existingLabels = snapshot.labels,
                autoRules = snapshot.autoLabelRules,
                onPickFile = { importCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*")) },
                onDismiss = { showCsvImportDialog = false },
                onConfirmImport = { validEntries, newLabels ->
                    val prev = snapshot
                    persist(
                        snapshot.copy(
                            expenses = validEntries + snapshot.expenses,
                            labels = snapshot.labels + newLabels
                        )
                    )
                    showCsvImportDialog = false
                    showActionSnackbar("Imported ${validEntries.size} CSV entries", prev)
                }
            )
        }

        if (partialPayTarget != null) {
            PartialPaymentDialog(
                expense = partialPayTarget!!,
                hideBalances = snapshot.hideBalances,
                onDismiss = { partialPayTarget = null },
                onConfirm = { amt, mode, note ->
                    val prev = snapshot
                    val next = LedgerMath.recordPartialPayment(snapshot, partialPayTarget!!.id, amt, mode, note)
                    persist(next)
                    partialPayTarget = null
                    showActionSnackbar("Recorded ₹${formatAmount(amt)} settlement", prev)
                }
            )
        }

        if (receiptLightboxRecord != null && receiptLightboxRecord!!.receiptAttachment != null) {
            ReceiptViewerDialog(
                expense = receiptLightboxRecord!!,
                onDismiss = { receiptLightboxRecord = null }
            )
        }

        if (isLocked && !snapshot.privacyPinHash.isNullOrBlank()) {
            PinLockOverlay(
                pinHash = snapshot.privacyPinHash!!,
                biometricEnabled = snapshot.biometricEnabled,
                onBiometricClick = { triggerBiometricAuth(activity) { isLocked = false } },
                onUnlock = { isLocked = false }
            )
        }
    }
}

@Composable
private fun SummaryStatBox(label: String, amount: Double, accent: Color, hideBalances: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
        color = accent.copy(alpha = 0.08f)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = accent)
            Spacer(Modifier.height(2.dp))
            Text(
                text = "₹${LedgerMath.formatDisplayAmt(amount, hideBalances)}",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ExpensesScreen(
    expenses: List<ExpenseRecord>,
    labels: List<LabelRecord>,
    lists: List<ListNoteRecord>,
    templates: List<ExpenseTemplateRecord>,
    liveWallets: List<LiveWalletBalanceRow>,
    goalProgress: List<GoalProgressSummary>,
    dueAlertSummary: DueAlertSummary,
    dueRemindersEnabled: Boolean,
    showDueBanner: Boolean,
    hideBalances: Boolean,
    isDark: Boolean,
    searchQuery: String,
    statusFilter: ExpenseStatus?,
    entryTypeFilter: EntryType?,
    paymentModeFilter: PaymentMode?,
    dateWindowFilter: DateWindowFilter,
    sortOrder: ExpenseSortOrder,
    exactDateFilter: String?,
    pinnedOnlyFilter: Boolean,
    labelFilterId: String?,
    bulkMode: Boolean,
    selectedExpenseIds: Set<String>,
    showQuickTemplates: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onStatusFilterChange: (ExpenseStatus?) -> Unit,
    onEntryTypeFilterChange: (EntryType?) -> Unit,
    onPaymentModeFilterChange: (PaymentMode?) -> Unit,
    onDateWindowFilterChange: (DateWindowFilter) -> Unit,
    onSortOrderChange: (ExpenseSortOrder) -> Unit,
    onClearExactDate: () -> Unit,
    onPinnedOnlyFilterChange: (Boolean) -> Unit,
    onLabelFilterChange: (String?) -> Unit,
    onClearFilters: () -> Unit,
    onToggleBulkMode: () -> Unit,
    onToggleSelectId: (String) -> Unit,
    onToggleSelectAll: (List<String>) -> Unit,
    onBulkStatus: (ExpenseStatus) -> Unit,
    onBulkMoveToBin: () -> Unit,
    onToggleQuickTemplates: () -> Unit,
    onLogTemplate: (ExpenseTemplateRecord) -> Unit,
    onMoveTemplateToBin: (String) -> Unit,
    onDismissDueBanner: () -> Unit,
    onOpenInsightsSection: (Int) -> Unit,
    onCycleStatus: (ExpenseRecord) -> Unit,
    onTogglePin: (ExpenseRecord) -> Unit,
    onPartialPay: (ExpenseRecord) -> Unit,
    onViewReceipt: (ExpenseRecord) -> Unit,
    onDuplicate: (ExpenseRecord) -> Unit,
    onEdit: (ExpenseRecord) -> Unit,
    onDelete: (String) -> Unit,
    onToggleChecklistLine: (String, Int) -> Unit,
    onShareReceiptCard: (ExpenseRecord) -> Unit,
    onLoadDemoData: () -> Unit,
    onAddFirstExpense: () -> Unit
) {
    val q = searchQuery.trim()
    val filteredExpenses = remember(expenses, labels, lists, q, statusFilter, entryTypeFilter, paymentModeFilter, dateWindowFilter, sortOrder, exactDateFilter, pinnedOnlyFilter, labelFilterId) {
        expenses.filter { exp ->
            val matchStatus = statusFilter == null || (exp.entryType == EntryType.EXPENSE && exp.status == statusFilter)
            val matchEntryType = entryTypeFilter == null || exp.entryType == entryTypeFilter
            val matchMode = paymentModeFilter == null || exp.paymentMode == paymentModeFilter
            val matchDate = LedgerMath.filterByDateWindow(exp, dateWindowFilter)
            val matchExactDate = exactDateFilter == null || exp.createdAt.startsWith(exactDateFilter) || exp.dueDate == exactDateFilter
            val matchPin = !pinnedOnlyFilter || exp.isPinned
            val matchLabel = labelFilterId == null || exp.labelIds.contains(labelFilterId)
            val matchSearch = if (q.isBlank()) {
                true
            } else {
                val labelHit = exp.labelIds.any { id -> labels.firstOrNull { it.id == id }?.name?.contains(q, ignoreCase = true) == true }
                val listHit = exp.attachedListIds.any { id ->
                    val lst = lists.firstOrNull { it.id == id }
                    lst != null && (lst.title.contains(q, ignoreCase = true) || lst.content.contains(q, ignoreCase = true))
                }
                exp.quote.contains(q, ignoreCase = true) ||
                    exp.paymentMode.label.contains(q, ignoreCase = true) ||
                    formatAmount(exp.balance).contains(q, ignoreCase = true) ||
                    LedgerMath.formatAmt(exp.balance).contains(q, ignoreCase = true) ||
                    labelHit ||
                    listHit
            }
            matchStatus && matchEntryType && matchMode && matchDate && matchExactDate && matchPin && matchLabel && matchSearch
        }.sortedWith(
            Comparator { a, b ->
                if (a.isPinned != b.isPinned) {
                    if (a.isPinned) -1 else 1
                } else {
                    when (sortOrder) {
                        ExpenseSortOrder.AMOUNT_DESC -> {
                            val cmp = b.balance.compareTo(a.balance)
                            if (cmp != 0) cmp else b.createdAt.compareTo(a.createdAt)
                        }
                        ExpenseSortOrder.DUE_ASC -> {
                            val aDue = if (!a.dueDate.isNullOrBlank() && a.status != ExpenseStatus.PAID) a.dueDate else "9999-99-99"
                            val bDue = if (!b.dueDate.isNullOrBlank() && b.status != ExpenseStatus.PAID) b.dueDate else "9999-99-99"
                            val cmp = aDue.compareTo(bDue)
                            if (cmp != 0) cmp else b.createdAt.compareTo(a.createdAt)
                        }
                        ExpenseSortOrder.NEWEST -> b.createdAt.compareTo(a.createdAt)
                    }
                }
            }
        )
    }

    val chipInactiveBg = if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0)
    val chipInactiveText = if (isDark) Color(0xFF94A3B8) else Color(0xFF334155)
    val chipActiveBg = Color(0xFF2563EB)

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "expenses-filter-bar") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            placeholder = { Text("Search quote, label, mode, amount...", fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            trailingIcon = {
                                if (searchQuery.isNotBlank()) {
                                    IconButton(onClick = { onSearchQueryChange("") }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        )
                        Button(
                            onClick = onAddFirstExpense,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB), contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add Entry", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val isAllStatus = statusFilter == null
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isAllStatus) chipActiveBg else chipInactiveBg,
                                modifier = Modifier.clickable { onStatusFilterChange(null) }
                            ) {
                                Text(
                                    text = "All (${expenses.size})",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isAllStatus) Color.White else chipInactiveText,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                            ExpenseStatus.entries.forEach { s ->
                                val selected = statusFilter == s
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selected) chipActiveBg else chipInactiveBg,
                                    modifier = Modifier.clickable { onStatusFilterChange(if (selected) null else s) }
                                ) {
                                    Text(
                                        text = s.label,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (selected) Color.White else chipInactiveText,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (bulkMode) Color(0xFF4F46E5) else chipInactiveBg,
                            modifier = Modifier.clickable { onToggleBulkMode() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.SelectAll,
                                    contentDescription = null,
                                    tint = if (bulkMode) Color.White else chipInactiveText,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = if (bulkMode) "Done" else "Select",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (bulkMode) Color.White else chipInactiveText
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (exactDateFilter != null) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF059669),
                                modifier = Modifier.clickable { onClearExactDate() }
                            ) {
                                Text(
                                    text = "Date: $exactDateFilter ×",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                )
                            }
                        }
                        DateWindowFilter.entries.forEach { dw ->
                            val selected = dateWindowFilter == dw
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) chipActiveBg else chipInactiveBg,
                                modifier = Modifier.clickable { onDateWindowFilterChange(dw) }
                            ) {
                                Text(
                                    text = dw.label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selected) Color.White else chipInactiveText,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                )
                            }
                        }
                        ExpenseSortOrder.entries.forEach { so ->
                            val selected = sortOrder == so
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) Color(0xFF4F46E5) else chipInactiveBg,
                                modifier = Modifier.clickable { onSortOrderChange(so) }
                            ) {
                                Text(
                                    text = "Sort: ${so.label}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selected) Color.White else chipInactiveText,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (pinnedOnlyFilter) Color(0xFFD97706) else chipInactiveBg,
                            modifier = Modifier.clickable { onPinnedOnlyFilterChange(!pinnedOnlyFilter) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    Icons.Default.PushPin,
                                    contentDescription = null,
                                    tint = if (pinnedOnlyFilter) Color.White else chipInactiveText,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "Pinned",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (pinnedOnlyFilter) Color.White else chipInactiveText
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (entryTypeFilter == EntryType.INCOME) Color(0xFF059669) else chipInactiveBg,
                            modifier = Modifier.clickable { onEntryTypeFilterChange(if (entryTypeFilter == EntryType.INCOME) null else EntryType.INCOME) }
                        ) {
                            Text(
                                text = "Income",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (entryTypeFilter == EntryType.INCOME) Color.White else chipInactiveText,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (entryTypeFilter == EntryType.EXPENSE) Color(0xFFDC2626) else chipInactiveBg,
                            modifier = Modifier.clickable { onEntryTypeFilterChange(if (entryTypeFilter == EntryType.EXPENSE) null else EntryType.EXPENSE) }
                        ) {
                            Text(
                                text = "Expense",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (entryTypeFilter == EntryType.EXPENSE) Color.White else chipInactiveText,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                            )
                        }
                        PaymentMode.entries.forEach { mode ->
                            val selected = paymentModeFilter == mode
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) chipActiveBg else chipInactiveBg,
                                modifier = Modifier.clickable { onPaymentModeFilterChange(if (selected) null else mode) }
                            ) {
                                Text(
                                    text = mode.label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selected) Color.White else chipInactiveText,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                )
                            }
                        }
                        labels.forEach { lbl ->
                            val selected = labelFilterId == lbl.id
                            val c = parseColorHex(lbl.colorHex)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) c else chipInactiveBg,
                                modifier = Modifier.clickable { onLabelFilterChange(if (selected) null else lbl.id) }
                            ) {
                                Text(
                                    text = "#${lbl.name}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selected) Color.White else chipInactiveText,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    if (liveWallets.isNotEmpty() || goalProgress.isNotEmpty()) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            liveWallets.forEach { w ->
                                val isNeg = w.liveBalance < 0.0
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isDark) Color(0xFF0F172A) else Color(0xFFFFFFFF),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0)),
                                    modifier = Modifier.clickable { onOpenInsightsSection(1) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = w.mode.label,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = chipInactiveText
                                        )
                                        Text(
                                            text = (if (isNeg) "-₹" else "₹") + LedgerMath.formatDisplayAmt(kotlin.math.abs(w.liveBalance), hideBalances),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isNeg) Color(0xFFFB7185) else Color(0xFF34D399)
                                        )
                                    }
                                }
                            }
                            goalProgress.forEach { gp ->
                                val pctInt = gp.pct
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isDark) Color(0xFF0F172A) else Color(0xFFFFFFFF),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = 0.45f)),
                                    modifier = Modifier.clickable { onOpenInsightsSection(4) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "Goal: ${gp.goal.title}",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isDark) Color(0xFF93C5FD) else Color(0xFF1D4ED8)
                                        )
                                        Text(
                                            text = "$pctInt%",
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF34D399)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (dueRemindersEnabled && showDueBanner && dueAlertSummary.items.isNotEmpty()) {
                        val topDue = dueAlertSummary.items.first()
                        val hasUrgent = dueAlertSummary.overdueCount > 0 || dueAlertSummary.dueTodayCount > 0
                        val bannerBorder = if (hasUrgent) Color(0xFFF59E0B).copy(alpha = 0.55f) else Color(0xFF2563EB).copy(alpha = 0.45f)
                        val bannerBg = if (isDark) Color(0xFF451A03).copy(alpha = 0.35f) else Color(0xFFFFFBEB)
                        val bannerTitleColor = if (isDark) Color(0xFFFCD34D) else Color(0xFF92400E)
                        val parts = mutableListOf<String>()
                        if (dueAlertSummary.overdueCount > 0) parts.add("${dueAlertSummary.overdueCount} overdue")
                        if (dueAlertSummary.dueTodayCount > 0) parts.add("${dueAlertSummary.dueTodayCount} due today")
                        if (dueAlertSummary.upcomingCount > 0) parts.add("${dueAlertSummary.upcomingCount} upcoming")
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = bannerBg,
                            border = androidx.compose.foundation.BorderStroke(1.dp, bannerBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Due Bills Alert · " + parts.joinToString(", "),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = bannerTitleColor
                                    )
                                    Text(
                                        text = "${topDue.expense.quote} (₹${LedgerMath.formatDisplayAmt(topDue.remaining, hideBalances)} · ${topDue.statusLabel})",
                                        fontSize = 10.sp,
                                        color = bannerTitleColor.copy(alpha = 0.9f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                                Button(
                                    onClick = { onPartialPay(topDue.expense) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF047857), contentColor = Color.White),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Pay Part", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                }
                                IconButton(onClick = onDismissDueBanner, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Dismiss due alert", tint = bannerTitleColor, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }

                    if (templates.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isDark) Color(0xFF0F172A) else Color(0xFFF1F5F9),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { onToggleQuickTemplates() },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Quick Templates & Bills (${templates.size})",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Icon(
                                        imageVector = if (showQuickTemplates) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = "Toggle templates",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                if (showQuickTemplates) {
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        templates.forEach { tpl ->
                                            val isDue = LedgerMath.isTemplateDue(tpl)
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = MaterialTheme.colorScheme.surface,
                                                border = androidx.compose.foundation.BorderStroke(
                                                    1.dp,
                                                    if (isDue) Color(0xFFD97706) else MaterialTheme.colorScheme.outlineVariant
                                                ),
                                                modifier = Modifier.clickable { onLogTemplate(tpl) }
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Column {
                                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                            Text(tpl.title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                                            if (isDue) {
                                                                Text(
                                                                    text = "Due",
                                                                    fontSize = 9.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = Color(0xFFD97706),
                                                                    modifier = Modifier
                                                                        .background(Color(0xFFD97706).copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                                                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                                                )
                                                            }
                                                        }
                                                        val prefix = if (tpl.entryType == EntryType.INCOME) "+₹" else "₹"
                                                        Text(
                                                            text = "$prefix${LedgerMath.formatDisplayAmt(tpl.balance, hideBalances)} · ${tpl.paymentMode.label} · ${tpl.recurrence.label}",
                                                            fontSize = 10.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    IconButton(
                                                        onClick = { onMoveTemplateToBin(tpl.id) },
                                                        modifier = Modifier.size(20.dp)
                                                    ) {
                                                        Icon(Icons.Default.Close, contentDescription = "Move template to bin", modifier = Modifier.size(12.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (filteredExpenses.isEmpty()) {
                item(key = "expenses-empty-filter") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = if (isDark) Color(0xFF0F172A) else Color(0xFFFFFFFF))
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (expenses.isEmpty()) {
                                Text("Your ledger is empty", fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = "Start fresh by logging your first expense or income, or load optional sample data from the menu.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = onAddFirstExpense) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Add Entry")
                                    }
                                    OutlinedButton(onClick = onLoadDemoData) {
                                        Text("Load Demo Data")
                                    }
                                }
                            } else {
                                Text("No matching entries", fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = "Try clearing your search, wallet, pinned, or label filters.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                OutlinedButton(onClick = onClearFilters) {
                                    Text("Clear Filters")
                                }
                            }
                        }
                    }
                }
            }

            items(filteredExpenses, key = { it.id }) { exp ->
                var expandAudit by remember { mutableStateOf(false) }
                val attachedLists = exp.attachedListIds.mapNotNull { id -> lists.firstOrNull { it.id == id } }
                val isSelected = selectedExpenseIds.contains(exp.id)
                val isIncome = exp.entryType == EntryType.INCOME
                val dueLabel = remember(exp.dueDate, exp.status, exp.entryType) {
                    LedgerMath.computeDueLabel(exp.dueDate, exp.status, exp.entryType)
                }
                val statusColor = when {
                    isIncome -> Color(0xFF34D399)
                    exp.status == ExpenseStatus.PAID -> Color(0xFF34D399)
                    exp.status == ExpenseStatus.HOLD -> Color(0xFFFBBF24)
                    else -> Color(0xFFFB7185)
                }
                val statusBg = when {
                    isIncome -> Color(0xFF064E3B).copy(alpha = if (isDark) 0.55f else 0.16f)
                    exp.status == ExpenseStatus.PAID -> Color(0xFF064E3B).copy(alpha = if (isDark) 0.55f else 0.16f)
                    exp.status == ExpenseStatus.HOLD -> Color(0xFF78350F).copy(alpha = if (isDark) 0.55f else 0.16f)
                    else -> Color(0xFF881337).copy(alpha = if (isDark) 0.55f else 0.16f)
                }
                val cardBorderColor = when {
                    bulkMode && isSelected -> Color(0xFF6366F1)
                    exp.isPinned -> Color(0xFFF59E0B).copy(alpha = 0.45f)
                    isDark -> Color(0xFF1E293B)
                    else -> Color(0xFFE2E8F0)
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(if (bulkMode && isSelected) 2.dp else 1.dp, cardBorderColor, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = if (isDark) Color(0xFF0F172A) else Color(0xFFFFFFFF))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (bulkMode) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { onToggleSelectId(exp.id) }
                                        )
                                    }
                                    Text(
                                        text = (if (isIncome) "+₹" else "₹") + LedgerMath.formatDisplayAmt(exp.balance, hideBalances),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (isIncome) Color(0xFF34D399) else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (isIncome) "INCOME" else exp.status.label.uppercase(),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isDark) statusColor else statusColor.copy(alpha = 0.95f),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(statusBg)
                                            .clickable { onCycleStatus(exp) }
                                            .padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                    Text(
                                        text = exp.paymentMode.label,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF475569),
                                        modifier = Modifier
                                            .background(if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                    if (exp.isPinned) {
                                        Text(
                                            text = "PINNED",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFFBBF24),
                                            modifier = Modifier
                                                .background(Color(0xFF78350F).copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }
                                    if (dueLabel != null) {
                                        val dueColor = when {
                                            dueLabel.startsWith("Overdue") -> Color(0xFFFB7185)
                                            dueLabel == "Due Today" -> Color(0xFFFBBF24)
                                            dueLabel.contains("Settled") -> Color(0xFF34D399)
                                            else -> Color(0xFF38BDF8)
                                        }
                                        Text(
                                            text = dueLabel,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = dueColor,
                                            modifier = Modifier
                                                .background(dueColor.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = exp.quote,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)
                                )
                            }

                            if (!bulkMode) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    IconButton(onClick = { onTogglePin(exp) }, modifier = Modifier.size(28.dp)) {
                                        Icon(
                                            imageVector = Icons.Default.PushPin,
                                            contentDescription = if (exp.isPinned) "Unpin" else "Pin to top",
                                            tint = if (exp.isPinned) Color(0xFFFBBF24) else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                    IconButton(onClick = { onShareReceiptCard(exp) }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Share, contentDescription = "Share Receipt Card", modifier = Modifier.size(15.dp))
                                    }
                                    IconButton(onClick = { onDuplicate(exp) }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate", modifier = Modifier.size(15.dp))
                                    }
                                    IconButton(onClick = { onEdit(exp) }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp))
                                    }
                                    IconButton(onClick = { onDelete(exp.id) }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Move to Bin", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }

                        if (!isIncome && exp.status != ExpenseStatus.PAID) {
                            val paidPart = LedgerMath.expensePaidPart(exp)
                            val remPart = LedgerMath.expenseRemainingPart(exp)
                            val pct = if (exp.balance > 0.0) (paidPart / exp.balance).toFloat().coerceIn(0f, 1f) else 0f
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isDark) Color(0xFF020617).copy(alpha = 0.65f) else Color(0xFFF8FAFC),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Paid ₹${LedgerMath.formatDisplayAmt(paidPart, hideBalances)}",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFF34D399)
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                text = "Rem ₹${LedgerMath.formatDisplayAmt(remPart, hideBalances)}",
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFFFB7185)
                                            )
                                            Button(
                                                onClick = { onPartialPay(exp) },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF047857), contentColor = Color.White),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                            ) {
                                                Text("Pay Part", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                    if (paidPart > 0.0) {
                                        Spacer(Modifier.height(5.dp))
                                        LinearProgressIndicator(
                                            progress = { pct },
                                            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(4.dp)),
                                            color = Color(0xFF10B981)
                                        )
                                    }
                                }
                            }
                        }

                        if (exp.splitShares.isNotEmpty() || exp.receiptAttachment != null) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (exp.splitShares.isNotEmpty()) {
                                    val splitSum = exp.splitShares.sumOf { it.amount }
                                    val friendWord = if (exp.splitShares.size > 1) "friends" else "friend"
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF4F46E5).copy(alpha = 0.16f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6366F1).copy(alpha = 0.4f)),
                                        modifier = Modifier.clickable { onOpenInsightsSection(3) }
                                    ) {
                                        Text(
                                            text = "Split · ${exp.splitShares.size} $friendWord owe ₹${LedgerMath.formatDisplayAmt(splitSum, hideBalances)}",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isDark) Color(0xFFA5B4FC) else Color(0xFF4338CA),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                                if (exp.receiptAttachment != null) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF0EA5E9).copy(alpha = 0.15f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f)),
                                        modifier = Modifier.clickable { onViewReceipt(exp) }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(Icons.Default.Image, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(12.dp))
                                            Text(
                                                text = "Receipt: ${exp.receiptAttachment.name}",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isDark) Color(0xFF7DD3FC) else Color(0xFF0369A1),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (exp.labelIds.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                exp.labelIds.forEach { id ->
                                    val lbl = labels.firstOrNull { it.id == id }
                                    if (lbl != null) {
                                        val c = parseColorHex(lbl.colorHex)
                                        val isFiltered = labelFilterId == lbl.id
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = c.copy(alpha = if (isFiltered) 0.3f else 0.16f),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, c.copy(alpha = if (isFiltered) 0.85f else 0.4f)),
                                            modifier = Modifier.clickable { onLabelFilterChange(if (isFiltered) null else lbl.id) }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .background(c, CircleShape)
                                                )
                                                Text(
                                                    text = "#${lbl.name}",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (!exp.calcFormula.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Math Tape: ${exp.calcFormula}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        attachedLists.forEach { attachedList ->
                            var expandList by remember(attachedList.id) { mutableStateOf(false) }
                            val listSum = remember(attachedList.content) { LedgerMath.extractNumbersSum(attachedList.content) }
                            val checkRows = remember(attachedList.content) { LedgerMath.parseChecklistLines(attachedList.content) }
                            val checkedSum = remember(checkRows) { checkRows.filter { it.checked }.sumOf { it.amount } }
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expandList = !expandList },
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "2nd Description: ${attachedList.title} (Checked ₹${LedgerMath.formatDisplayAmt(checkedSum, hideBalances)} / ₹${LedgerMath.formatDisplayAmt(listSum, hideBalances)})",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(
                                            imageVector = if (expandList) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    AnimatedVisibility(visible = expandList) {
                                        Column(modifier = Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            checkRows.forEach { row ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable { onToggleChecklistLine(attachedList.id, row.lineIndex) }
                                                        .padding(vertical = 2.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Checkbox(
                                                        checked = row.checked,
                                                        onCheckedChange = { onToggleChecklistLine(attachedList.id, row.lineIndex) },
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Text(
                                                        text = row.text,
                                                        fontSize = 12.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = if (row.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                text = "Tap any line to check/uncheck · Checked ₹${LedgerMath.formatDisplayAmt(checkedSum, hideBalances)} / Total ₹${LedgerMath.formatDisplayAmt(listSum, hideBalances)}",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = if (isDark) Color(0xFF1E293B) else Color(0xFFE2E8F0))
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandAudit = !expandAudit },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Created: ${LedgerMath.formatCompactDate(exp.createdAt)} · Updated: ${LedgerMath.formatCompactDate(exp.updatedAt)}",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Icon(
                                imageVector = if (expandAudit) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = "Toggle audit log",
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        AnimatedVisibility(visible = expandAudit) {
                            Column(modifier = Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                exp.auditLog.forEach { entry ->
                                    Text(
                                        text = "${entry.action}: ${LedgerMath.formatReadableDate(entry.at)} — ${entry.summary}",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (bulkMode) {
            val visibleIds = filteredExpenses.map { it.id }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${selectedExpenseIds.size} picked",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { onToggleSelectAll(visibleIds) }) {
                        Text("All/None", fontSize = 11.sp)
                    }
                    FilledTonalButton(
                        enabled = selectedExpenseIds.isNotEmpty(),
                        onClick = { onBulkStatus(ExpenseStatus.PAID) }
                    ) { Text("Paid", fontSize = 11.sp) }
                    FilledTonalButton(
                        enabled = selectedExpenseIds.isNotEmpty(),
                        onClick = { onBulkStatus(ExpenseStatus.HOLD) }
                    ) { Text("Hold", fontSize = 11.sp) }
                    FilledTonalButton(
                        enabled = selectedExpenseIds.isNotEmpty(),
                        onClick = { onBulkStatus(ExpenseStatus.NEED) }
                    ) { Text("Need", fontSize = 11.sp) }
                    Button(
                        enabled = selectedExpenseIds.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        onClick = onBulkMoveToBin
                    ) { Text("Bin", fontSize = 11.sp) }
                }
            }
        }
    }
}

@Composable
private fun LabelsScreen(
    labels: List<LabelRecord>,
    expenses: List<ExpenseRecord>,
    hideBalances: Boolean,
    onEditLabel: (LabelRecord) -> Unit,
    onDeleteLabel: (String) -> Unit,
    onAddExpenseForLabel: (String) -> Unit,
    onShareLabel: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(labels, key = { it.id }) { lbl ->
            var expandAudit by remember { mutableStateOf(false) }
            val linked = expenses.filter { it.entryType == EntryType.EXPENSE && it.labelIds.contains(lbl.id) }
            val paid = linked.sumOf { LedgerMath.expensePaidPart(it) }
            val hold = linked.filter { it.status == ExpenseStatus.HOLD }.sumOf { LedgerMath.expenseRemainingPart(it) }
            val need = linked.filter { it.status == ExpenseStatus.NEED }.sumOf { LedgerMath.expenseRemainingPart(it) }
            val spentTotal = paid + hold + need
            val accent = parseColorHex(lbl.colorHex)

            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(accent))
                            Text(lbl.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Row {
                            TextButton(onClick = { onAddExpenseForLabel(lbl.id) }) {
                                Text("+ Expense")
                            }
                            IconButton(onClick = { onShareLabel(lbl.id) }) {
                                Icon(Icons.Default.Share, contentDescription = "Share label summary", modifier = Modifier.size(16.dp))
                            }
                            IconButton(onClick = { onEditLabel(lbl) }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit label", modifier = Modifier.size(16.dp))
                            }
                            IconButton(onClick = { onDeleteLabel(lbl.id) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Move label to Bin", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    if (lbl.description.isNotBlank()) {
                        Text(lbl.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Paid ₹${LedgerMath.formatDisplayAmt(paid, hideBalances)} · Hold ₹${LedgerMath.formatDisplayAmt(hold, hideBalances)} · Need ₹${LedgerMath.formatDisplayAmt(need, hideBalances)}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (lbl.monthlyBudget != null && lbl.monthlyBudget > 0.0) {
                        val ratio = (spentTotal / lbl.monthlyBudget).toFloat().coerceIn(0f, 1f)
                        val over = spentTotal > lbl.monthlyBudget
                        val warn = !over && (spentTotal / lbl.monthlyBudget) >= 0.8
                        val barColor = when {
                            over -> Color(0xFFDC2626)
                            warn -> Color(0xFFD97706)
                            else -> Color(0xFF059669)
                        }
                        val statusText = when {
                            over -> "Over by ₹${LedgerMath.formatDisplayAmt(spentTotal - lbl.monthlyBudget, hideBalances)}"
                            warn -> "Near limit"
                            else -> "Within budget"
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Cap ₹${LedgerMath.formatDisplayAmt(lbl.monthlyBudget, hideBalances)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text(statusText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = barColor)
                        }
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { ratio },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),
                            color = barColor
                        )
                    }
                    if (linked.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        linked.forEach { e ->
                            Text(
                                text = "• ${e.quote} — ₹${LedgerMath.formatDisplayAmt(e.balance, hideBalances)} (${e.status.label})",
                                fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Audit Log (${lbl.auditLog.size}) — Created ${LedgerMath.formatReadableDate(lbl.createdAt)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { expandAudit = !expandAudit }
                    )
                    AnimatedVisibility(visible = expandAudit) {
                        Column(modifier = Modifier.padding(top = 4.dp)) {
                            lbl.auditLog.forEach { entry ->
                                Text(
                                    text = "${entry.action}: ${LedgerMath.formatReadableDate(entry.at)}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListsScreen(
    lists: List<ListNoteRecord>,
    hideBalances: Boolean,
    onToggleLine: (String, Int) -> Unit,
    onEditList: (ListNoteRecord) -> Unit,
    onDeleteList: (String) -> Unit,
    onUseInExpense: (ListNoteRecord, Double) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(lists, key = { it.id }) { lst ->
            var expandAudit by remember { mutableStateOf(false) }
            val detectedSum = remember(lst.content) { LedgerMath.extractNumbersSum(lst.content) }
            val checkRows = remember(lst.content) { LedgerMath.parseChecklistLines(lst.content) }
            val checkedSum = remember(checkRows) { checkRows.filter { it.checked }.sumOf { it.amount } }
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(lst.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text(
                            text = "Checked ₹${LedgerMath.formatDisplayAmt(checkedSum, hideBalances)} / ₹${LedgerMath.formatDisplayAmt(detectedSum, hideBalances)}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        checkRows.forEach { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleLine(lst.id, row.lineIndex) }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Checkbox(
                                    checked = row.checked,
                                    onCheckedChange = { onToggleLine(lst.id, row.lineIndex) },
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = row.text,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onUseInExpense(lst, detectedSum) }) {
                            Text("Use in New Expense")
                        }
                        OutlinedButton(onClick = { onEditList(lst) }) {
                            Text("Edit")
                        }
                        IconButton(onClick = { onDeleteList(lst.id) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Move list to Bin", modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Audit Log (${lst.auditLog.size}) — Created ${LedgerMath.formatReadableDate(lst.createdAt)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { expandAudit = !expandAudit }
                    )
                    AnimatedVisibility(visible = expandAudit) {
                        Column(modifier = Modifier.padding(top = 4.dp)) {
                            lst.auditLog.forEach { entry ->
                                Text(
                                    text = "${entry.action}: ${LedgerMath.formatReadableDate(entry.at)}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpenseEditorDialog(
    existing: ExpenseRecord?,
    initialBalance: String,
    initialQuote: String,
    initialStatus: ExpenseStatus?,
    initialEntryType: EntryType,
    initialPaymentMode: PaymentMode,
    initialIsPinned: Boolean,
    initialDueDate: String,
    initialLabelIds: List<String>,
    initialAttachedListIds: List<String>,
    initialSplitShares: List<ExpenseSplitShare> = emptyList(),
    calcFormula: String?,
    receiptAttachment: ReceiptAttachmentRecord?,
    expensesHistory: List<ExpenseRecord>,
    autoRules: List<AutoLabelRule> = emptyList(),
    labels: List<LabelRecord>,
    lists: List<ListNoteRecord>,
    onPickReceiptPhoto: () -> Unit,
    onClearReceiptPhoto: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (Double, String, ExpenseStatus, List<String>, List<String>, Boolean, RecurrenceCycle, EntryType, PaymentMode, Boolean, String, List<ExpenseSplitShare>) -> Unit
) {
    var balanceText by remember { mutableStateOf(initialBalance) }
    var quoteText by remember { mutableStateOf(initialQuote) }
    var entryType by remember { mutableStateOf(existing?.entryType ?: initialEntryType) }
    var paymentMode by remember { mutableStateOf(existing?.paymentMode ?: initialPaymentMode) }
    var status by remember { mutableStateOf(existing?.status ?: initialStatus ?: ExpenseStatus.NEED) }
    var isPinned by remember { mutableStateOf(existing?.isPinned ?: initialIsPinned) }
    var dueDateText by remember { mutableStateOf(existing?.dueDate ?: initialDueDate) }
    var selectedLabels by remember { mutableStateOf(initialLabelIds.toSet()) }
    var attachedListIds by remember { mutableStateOf(initialAttachedListIds.toSet()) }
    var splitShares by remember { mutableStateOf(existing?.splitShares ?: initialSplitShares) }
    var splitFriendInput by remember { mutableStateOf("") }
    var splitAmtInput by remember { mutableStateOf("") }
    var saveAsTemplate by remember { mutableStateOf(false) }
    var recurrence by remember { mutableStateOf(RecurrenceCycle.MONTHLY) }
    var showSmsPaste by remember { mutableStateOf(false) }
    var smsInput by remember { mutableStateOf("") }
    var smsFeedback by remember { mutableStateOf<String?>(null) }
    var modeManuallyChanged by remember { mutableStateOf(false) }

    var selectedCurrency by remember { mutableStateOf(CurrencyCode.INR) }
    var fxRateText by remember { mutableStateOf("1") }
    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val matches = res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
        val spoken = matches?.firstOrNull().orEmpty()
        if (spoken.isNotBlank()) {
            val parsed = LedgerMath.parseVoicePhrase(spoken)
            if (parsed.matched && parsed.amount != null) {
                balanceText = formatAmount(parsed.amount)
                entryType = parsed.entryType
                paymentMode = parsed.paymentMode
                if (parsed.quote.isNotBlank()) quoteText = parsed.quote
                smsFeedback = "Voice filled ₹${formatAmount(parsed.amount)} · ${paymentMode.label}"
            } else {
                quoteText = spoken
            }
        }
    }

    val merchantSuggestions = remember(existing, quoteText, expensesHistory) {
        if (existing != null) emptyList() else LedgerMath.suggestMerchants(expensesHistory, quoteText, 4)
    }
    val ruleMatch = remember(quoteText, autoRules, selectedLabels, paymentMode) {
        LedgerMath.applyAutoLabelRules(quoteText, autoRules, selectedLabels.toList(), paymentMode)
    }
    val hasUnappliedRuleLabels = remember(ruleMatch, selectedLabels) {
        ruleMatch.first.any { !selectedLabels.contains(it) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (existing == null) "New Entry" else "Edit Entry")
                FilledTonalButton(
                    onClick = {
                        try {
                            val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say e.g. 'Paid 450 for coffee via UPI'")
                            }
                            voiceLauncher.launch(intent)
                        } catch (_: Exception) {
                            smsFeedback = "Voice recognition not available on this device"
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Mic, contentDescription = "Voice Quick Entry", modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Voice", fontSize = 11.sp)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (existing == null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { showSmsPaste = !showSmsPaste },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text("Smart Paste Bank / UPI SMS", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Icon(
                                    imageVector = if (showSmsPaste) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            if (showSmsPaste) {
                                OutlinedTextField(
                                    value = smsInput,
                                    onValueChange = { smsInput = it },
                                    placeholder = { Text("Paste alert SMS (zero SMS permission needed)...", fontSize = 11.sp) },
                                    minLines = 2
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    FilledTonalButton(
                                        onClick = {
                                            val parsed = LedgerMath.parseBankSmsSnippet(smsInput)
                                            if (parsed.matched && parsed.amount != null) {
                                                balanceText = formatAmount(parsed.amount)
                                                entryType = parsed.entryType
                                                if (parsed.entryType == EntryType.INCOME) status = ExpenseStatus.PAID
                                                val nextMode = parsed.paymentMode
                                                if (parsed.quote.isNotBlank()) quoteText = parsed.quote
                                                val ruled = LedgerMath.applyAutoLabelRules(
                                                    "${parsed.quote} $smsInput",
                                                    autoRules,
                                                    selectedLabels.toList(),
                                                    nextMode
                                                )
                                                selectedLabels = ruled.first.toSet()
                                                paymentMode = ruled.second
                                                smsFeedback = "Auto-filled ₹${formatAmount(parsed.amount)} · ${paymentMode.label}"
                                            } else {
                                                smsFeedback = "No ₹ amount detected in pasted text."
                                            }
                                        }
                                    ) {
                                        Text("Auto-Fill", fontSize = 11.sp)
                                    }
                                    if (!smsFeedback.isNullOrBlank()) {
                                        Text(smsFeedback!!, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }

                Text("Entry Type", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EntryType.entries.forEach { t ->
                        FilterChip(
                            selected = entryType == t,
                            onClick = {
                                entryType = t
                                if (t == EntryType.INCOME) status = ExpenseStatus.PAID
                            },
                            label = { Text(t.label) }
                        )
                    }
                }

                Text("Currency (Auto-converts to INR)", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CurrencyCode.entries.forEach { cc ->
                        FilterChip(
                            selected = selectedCurrency == cc,
                            onClick = {
                                selectedCurrency = cc
                                fxRateText = formatAmount(cc.defaultRateToInr)
                            },
                            label = { Text(cc.code, fontSize = 11.sp) }
                        )
                    }
                }
                if (selectedCurrency != CurrencyCode.INR) {
                    OutlinedTextField(
                        value = fxRateText,
                        onValueChange = { fxRateText = it },
                        label = { Text("1 ${selectedCurrency.code} to INR Rate (₹)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = balanceText,
                    onValueChange = { balanceText = it },
                    label = { Text(if (selectedCurrency == CurrencyCode.INR) "Amount (₹ INR)" else "Amount (${selectedCurrency.symbol}${selectedCurrency.code})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = quoteText,
                    onValueChange = { nextQuote ->
                        quoteText = nextQuote
                        if (existing == null) {
                            val liveHit = LedgerMath.applyAutoLabelRules(nextQuote, autoRules, selectedLabels.toList(), paymentMode)
                            if (liveHit.first != selectedLabels.toList()) {
                                selectedLabels = liveHit.first.toSet()
                            }
                            if (!modeManuallyChanged && liveHit.second != paymentMode) {
                                paymentMode = liveHit.second
                            }
                        }
                    },
                    label = { Text("Quote / Merchant Description") }
                )
                if (hasUnappliedRuleLabels) {
                    SuggestionChip(
                        onClick = {
                            selectedLabels = ruleMatch.first.toSet()
                            if (!modeManuallyChanged) paymentMode = ruleMatch.second
                        },
                        label = { Text("Apply Auto-Rule Labels & Mode", fontSize = 10.sp) }
                    )
                }
                if (merchantSuggestions.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        merchantSuggestions.forEach { sug ->
                            SuggestionChip(
                                onClick = {
                                    quoteText = sug.quote
                                    if (balanceText.isBlank()) balanceText = formatAmount(sug.lastBalance)
                                    paymentMode = sug.paymentMode
                                    entryType = sug.entryType
                                    status = sug.status
                                    selectedLabels = sug.labelIds.toSet()
                                },
                                label = { Text("Use: ${sug.quote} · ₹${formatAmount(sug.lastBalance)}", fontSize = 10.sp) }
                            )
                        }
                    }
                }
                if (!calcFormula.isNullOrBlank()) {
                    Text(
                        text = "Math Tape: $calcFormula",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text("Wallet / Payment Mode", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PaymentMode.entries.forEach { mode ->
                        FilterChip(
                            selected = paymentMode == mode,
                            onClick = {
                                paymentMode = mode
                                modeManuallyChanged = true
                            },
                            label = { Text(mode.label, fontSize = 11.sp) }
                        )
                    }
                }

                if (entryType == EntryType.EXPENSE) {
                    Text("Status", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExpenseStatus.entries.forEach { s ->
                            FilterChip(
                                selected = status == s,
                                onClick = { status = s },
                                label = { Text(s.label) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = dueDateText,
                        onValueChange = { dueDateText = it },
                        label = { Text("Bill Due Date (YYYY-MM-DD · Optional)") },
                        placeholder = { Text("2026-11-25", fontSize = 12.sp) },
                        singleLine = true
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { isPinned = !isPinned }
                ) {
                    Checkbox(checked = isPinned, onCheckedChange = { isPinned = it })
                    Text("Pin to top of ledger", fontSize = 13.sp)
                }

                Text("Labels", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    labels.forEach { lbl ->
                        val picked = selectedLabels.contains(lbl.id)
                        FilterChip(
                            selected = picked,
                            onClick = {
                                selectedLabels = if (picked) selectedLabels - lbl.id else selectedLabels + lbl.id
                            },
                            label = { Text(lbl.name) }
                        )
                    }
                }
                if (lists.isNotEmpty()) {
                    Text("Attach Lists (2nd Description · Optional)", style = MaterialTheme.typography.labelMedium)
                    lists.forEach { lst ->
                        val picked = attachedListIds.contains(lst.id)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                attachedListIds = if (picked) attachedListIds - lst.id else attachedListIds + lst.id
                            }
                        ) {
                            Checkbox(
                                checked = picked,
                                onCheckedChange = { checked ->
                                    attachedListIds = if (checked) attachedListIds + lst.id else attachedListIds - lst.id
                                }
                            )
                            Text(lst.title, fontSize = 13.sp)
                        }
                    }
                }

                if (entryType == EntryType.EXPENSE) {
                    Text("Split with Friends (IOU · Tracked separately)", style = MaterialTheme.typography.labelMedium)
                    splitShares.forEach { sp ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${sp.friendName} owes ₹${formatAmount(sp.amount)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            TextButton(onClick = { splitShares = splitShares.filterNot { it.id == sp.id } }) {
                                Text("Remove", fontSize = 10.sp, color = Color(0xFFDC2626))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = splitFriendInput,
                            onValueChange = { splitFriendInput = it },
                            label = { Text("Friend", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = splitAmtInput,
                            onValueChange = { splitAmtInput = it },
                            label = { Text("Share ₹", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(88.dp),
                            singleLine = true
                        )
                        OutlinedButton(
                            onClick = {
                                val nm = splitFriendInput.trim()
                                val a = splitAmtInput.toDoubleOrNull() ?: 0.0
                                if (nm.isNotBlank() && a > 0.0) {
                                    splitShares = splitShares + ExpenseSplitShare(friendName = nm, amount = a)
                                    splitFriendInput = ""
                                    splitAmtInput = ""
                                }
                            }
                        ) { Text("Add", fontSize = 11.sp) }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { saveAsTemplate = !saveAsTemplate }
                ) {
                    Checkbox(checked = saveAsTemplate, onCheckedChange = { saveAsTemplate = it })
                    Text("Also save as Quick Template", fontSize = 13.sp)
                }
                if (saveAsTemplate) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RecurrenceCycle.entries.forEach { cycle ->
                            FilterChip(
                                selected = recurrence == cycle,
                                onClick = { recurrence = cycle },
                                label = { Text(cycle.label, fontSize = 11.sp) }
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onPickReceiptPhoto) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (receiptAttachment == null) "Attach Receipt" else "Replace Receipt", fontSize = 11.sp)
                    }
                    if (receiptAttachment != null) {
                        TextButton(onClick = onClearReceiptPhoto) {
                            Text("Remove (${receiptAttachment.name.take(14)})", fontSize = 10.sp, color = Color(0xFFDC2626))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val rawAmount = balanceText.toDoubleOrNull() ?: 0.0
                    val rate = if (selectedCurrency == CurrencyCode.INR) 1.0 else (fxRateText.toDoubleOrNull()?.takeIf { it > 0.0 } ?: selectedCurrency.defaultRateToInr)
                    val amount = kotlin.math.round(rawAmount * rate * 100.0) / 100.0
                    val baseQuote = quoteText.ifBlank { if (entryType == EntryType.INCOME) "Untitled Income" else "Untitled Expense" }
                    val finalQuote = if (selectedCurrency != CurrencyCode.INR && rawAmount > 0.0 && !baseQuote.contains(selectedCurrency.code)) {
                        "$baseQuote (${selectedCurrency.symbol}${formatAmount(rawAmount)} @ ₹${formatAmount(rate)})"
                    } else {
                        baseQuote
                    }
                    onSave(
                        amount,
                        finalQuote,
                        if (entryType == EntryType.INCOME) ExpenseStatus.PAID else status,
                        selectedLabels.toList(),
                        attachedListIds.toList(),
                        saveAsTemplate,
                        recurrence,
                        entryType,
                        paymentMode,
                        isPinned,
                        dueDateText,
                        if (entryType == EntryType.EXPENSE) splitShares else emptyList()
                    )
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun LabelEditorDialog(
    existing: LabelRecord?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Double?) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "#") }
    var desc by remember { mutableStateOf(existing?.description ?: "") }
    var budgetText by remember { mutableStateOf(existing?.monthlyBudget?.let { formatAmount(it) } ?: "") }
    var colorHex by remember { mutableStateOf(existing?.colorHex ?: "#2563EB") }
    val palette = listOf("#2563EB", "#059669", "#D97706", "#7C3AED", "#DC2626", "#0D9488")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New Label" else "Edit Label") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description") })
                OutlinedTextField(
                    value = budgetText,
                    onValueChange = { budgetText = it },
                    label = { Text("Monthly Budget Cap (₹ · Optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    palette.forEach { hex ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(parseColorHex(hex))
                                .border(if (colorHex == hex) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .clickable { colorHex = hex }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cap = budgetText.trim().toDoubleOrNull()?.takeIf { it > 0.0 }
                    onSave(name.ifBlank { "#Label" }, desc, colorHex, cap)
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ListEditorDialog(
    existing: ListNoteRecord?,
    initialTitle: String,
    initialContent: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var title by remember { mutableStateOf(existing?.title ?: initialTitle) }
    var content by remember { mutableStateOf(existing?.content ?: initialContent) }
    val previewSum = remember(content) { LedgerMath.extractNumbersSum(content) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New List Note" else "Edit List Note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("List Title") }, singleLine = true)
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Items (e.g. Wood -> ₹5)") },
                    minLines = 4
                )
                Text("Detected Auto-Sum: ₹${formatAmount(previewSum)}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            Button(onClick = { onSave(title.ifBlank { "Untitled List" }, content) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CalculatorSheetContent(
    history: List<CalcHistoryRecord>,
    hideBalances: Boolean,
    onRecordHistory: (String, Double) -> Unit,
    onSendToExpense: (Double, String, String?) -> Unit,
    onInsertIntoList: (Double, String) -> Unit
) {
    var display by remember { mutableStateOf("0") }
    var accumulator by remember { mutableStateOf<Double?>(null) }
    var pendingOp by remember { mutableStateOf<String?>(null) }
    var waitingNext by remember { mutableStateOf(false) }
    var formulaPreview by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }

    fun pressKey(key: String) {
        when (key) {
            "AC" -> {
                display = "0"
                accumulator = null
                pendingOp = null
                waitingNext = false
                formulaPreview = ""
            }
            "C" -> {
                display = "0"
                waitingNext = false
            }
            "00.0" -> {
                display = if (waitingNext || display == "0") "0.0" else if (display.contains(".")) display + "00" else display + "00.0"
                waitingNext = false
            }
            "%" -> {
                val cur = display.toDoubleOrNull() ?: 0.0
                val nextVal = if (accumulator != null && (pendingOp == "+" || pendingOp == "-")) (accumulator!! * cur) / 100.0 else cur / 100.0
                display = formatAmount(nextVal)
            }
            "+", "-", "×", "÷" -> {
                val cur = display.toDoubleOrNull() ?: 0.0
                if (accumulator == null) {
                    accumulator = cur
                } else if (!waitingNext && pendingOp != null) {
                    val res = when (pendingOp) {
                        "+" -> accumulator!! + cur
                        "-" -> accumulator!! - cur
                        "×" -> accumulator!! * cur
                        "÷" -> if (cur == 0.0) 0.0 else accumulator!! / cur
                        else -> cur
                    }
                    accumulator = res
                    display = formatAmount(res)
                }
                pendingOp = key
                waitingNext = true
                formulaPreview = "${formatAmount(accumulator!!)} $key"
            }
            "=" -> {
                val cur = display.toDoubleOrNull() ?: 0.0
                if (accumulator != null && pendingOp != null) {
                    val expr = "${formatAmount(accumulator!!)} $pendingOp ${formatAmount(cur)}"
                    val res = when (pendingOp) {
                        "+" -> accumulator!! + cur
                        "-" -> accumulator!! - cur
                        "×" -> accumulator!! * cur
                        "÷" -> if (cur == 0.0) 0.0 else accumulator!! / cur
                        else -> cur
                    }
                    display = formatAmount(res)
                    formulaPreview = "$expr = $display"
                    onRecordHistory(expr, res)
                    accumulator = null
                    pendingOp = null
                    waitingNext = true
                }
            }
            "." -> {
                if (waitingNext) {
                    display = "0."
                    waitingNext = false
                } else if (!display.contains(".")) {
                    display += "."
                }
            }
            else -> {
                if (waitingNext || display == "0") {
                    display = key
                    waitingNext = false
                } else {
                    display += key
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Quick Calculator", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = { showHistory = !showHistory }) {
                Text(if (showHistory) "Keypad" else "History (${history.size})")
            }
        }

        if (showHistory) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                history.forEach { h ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${h.expression} = ₹${LedgerMath.formatDisplayAmt(h.result, hideBalances)}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Text(
                                text = "Created: ${LedgerMath.formatReadableDate(h.createdAt)} · Updated: ${LedgerMath.formatReadableDate(h.updatedAt)}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onSendToExpense(h.result, "${h.expression} = ${formatAmount(h.result)}", h.id) }) {
                                    Text("Send to Expense", fontSize = 11.sp)
                                }
                                OutlinedButton(onClick = { onInsertIntoList(h.result, h.expression) }) {
                                    Text("Insert into List", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Text(formulaPreview, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "₹$display",
                fontFamily = FontFamily.Monospace,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.End)
            )

            val rows = listOf(
                listOf("AC", "C", "%", "÷"),
                listOf("7", "8", "9", "×"),
                listOf("4", "5", "6", "-"),
                listOf("1", "2", "3", "+"),
                listOf("00.0", "0", ".", "=")
            )
            rows.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { k ->
                        FilledTonalButton(
                            onClick = { pressKey(k) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(k, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val valNum = display.toDoubleOrNull() ?: 0.0
                    val expr = if (formulaPreview.isNotBlank()) formulaPreview else "Calc Item"
                    onInsertIntoList(valNum, expr)
                },
                modifier = Modifier.weight(1f).height(46.dp)
            ) {
                Text("Insert into List", fontSize = 12.sp)
            }
            Button(
                onClick = {
                    val valNum = display.toDoubleOrNull() ?: 0.0
                    val formula = if (formulaPreview.isNotBlank()) formulaPreview else display
                    onSendToExpense(valNum, formula, null)
                },
                modifier = Modifier.weight(1.2f).height(46.dp)
            ) {
                Text("Send to Expense (₹$display)", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ShareSummaryDialog(
    appName: String,
    snapshot: LedgerSnapshot,
    initialLabelId: String?,
    onDismiss: () -> Unit,
    onExportCsv: () -> Unit,
    onImportCsv: () -> Unit,
    onExportPdf: (String?, String?) -> Unit,
    onExportHtml: (String?, String?) -> Unit,
    onShareText: (String) -> Unit
) {
    var selectedLabelId by remember(initialLabelId) { mutableStateOf(initialLabelId) }
    var selectedMonthKey by remember { mutableStateOf<String?>(null) }
    val months = remember(snapshot.expenses) { LedgerMath.availableMonths(snapshot.expenses) }
    val summaryText = remember(snapshot, selectedLabelId, selectedMonthKey) {
        LedgerMath.buildShareSummary(snapshot, appName, selectedLabelId, selectedMonthKey)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share & Export") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Scope", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedLabelId == null,
                        onClick = { selectedLabelId = null },
                        label = { Text("All Expenses", fontSize = 11.sp) }
                    )
                    snapshot.labels.forEach { lbl ->
                        FilterChip(
                            selected = selectedLabelId == lbl.id,
                            onClick = { selectedLabelId = lbl.id },
                            label = { Text(lbl.name, fontSize = 11.sp) }
                        )
                    }
                }

                Text("Month", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedMonthKey == null,
                        onClick = { selectedMonthKey = null },
                        label = { Text("All Time", fontSize = 11.sp) }
                    )
                    months.forEach { month ->
                        FilterChip(
                            selected = selectedMonthKey == month,
                            onClick = { selectedMonthKey = month },
                            label = { Text(month, fontSize = 11.sp) }
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = summaryText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onExportCsv, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Export CSV", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onImportCsv, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Import CSV", fontSize = 11.sp)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onExportPdf(selectedLabelId, selectedMonthKey) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("PDF (.pdf)", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { onExportHtml(selectedLabelId, selectedMonthKey) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Statement (.html)", fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onShareText(summaryText) }) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Share Text")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun InsightsDialog(
    snapshot: LedgerSnapshot,
    initialSection: Int = 0,
    onUpdateOpeningBalances: (Map<PaymentMode, Double>) -> Unit,
    onCreateTransfer: (PaymentMode, PaymentMode, Double, String) -> Unit,
    onDeleteTransfer: (String) -> Unit,
    onRecordIouRepayment: (String, Double, PaymentMode, String) -> Unit,
    onCreateGoal: (String, Double, String) -> Unit,
    onGoalTransaction: (String, String, Double, PaymentMode) -> Unit,
    onOpenInternationalCalendar: () -> Unit,
    onDismiss: () -> Unit
) {
    val initialMonth = remember(snapshot.expenses) {
        LedgerMath.availableMonths(snapshot.expenses).firstOrNull() ?: LedgerMath.nowIso().take(7)
    }
    var monthKey by remember { mutableStateOf(initialMonth) }
    var activeSection by remember(initialSection) { mutableStateOf(initialSection) } // 0=Budgets,1=Cashflow,2=Forecast,3=Splits,4=Goals,5=Merchants,6=Calendar
    var selectedDateKey by remember { mutableStateOf<String?>(null) }
    var showOpeningEditor by remember { mutableStateOf(false) }
    var showTransferForm by remember { mutableStateOf(false) }
    var trfFrom by remember { mutableStateOf(PaymentMode.BANK) }
    var trfTo by remember { mutableStateOf(PaymentMode.UPI) }
    var trfAmtText by remember { mutableStateOf("") }
    var trfNote by remember { mutableStateOf("") }

    var startUpi by remember(snapshot.walletStartingBalances) { mutableStateOf(formatAmount(snapshot.walletStartingBalances[PaymentMode.UPI] ?: 0.0)) }
    var startCash by remember(snapshot.walletStartingBalances) { mutableStateOf(formatAmount(snapshot.walletStartingBalances[PaymentMode.CASH] ?: 0.0)) }
    var startCard by remember(snapshot.walletStartingBalances) { mutableStateOf(formatAmount(snapshot.walletStartingBalances[PaymentMode.CARD] ?: 0.0)) }
    var startBank by remember(snapshot.walletStartingBalances) { mutableStateOf(formatAmount(snapshot.walletStartingBalances[PaymentMode.BANK] ?: 0.0)) }

    var repayFriend by remember { mutableStateOf("") }
    var repayAmt by remember { mutableStateOf("") }
    var repayWallet by remember { mutableStateOf(PaymentMode.UPI) }

    var newGoalTitle by remember { mutableStateOf("") }
    var newGoalTarget by remember { mutableStateOf("") }
    var newGoalDate by remember { mutableStateOf("") }
    var goalTxAmt by remember { mutableStateOf("500") }
    var goalTxWallet by remember { mutableStateOf(PaymentMode.UPI) }

    val hideBalances = snapshot.hideBalances
    val monthExpenses = remember(snapshot.expenses, monthKey) {
        snapshot.expenses.filter { it.entryType == EntryType.EXPENSE && it.createdAt.startsWith(monthKey) }
    }
    val monthTotal = remember(monthExpenses) { monthExpenses.sumOf { it.balance } }
    val cashflow = remember(snapshot.expenses, monthKey) {
        LedgerMath.computeCashflowSummary(snapshot.expenses, monthKey)
    }
    val wallets = remember(snapshot.expenses, monthKey) {
        LedgerMath.computeWalletBreakdown(snapshot.expenses, monthKey)
    }
    val liveWallets = remember(snapshot) {
        LedgerMath.computeLiveWalletBalances(snapshot)
    }
    val forecast30d = remember(snapshot) {
        LedgerMath.compute30DayForecast(snapshot)
    }
    val friendIous = remember(snapshot.expenses, snapshot.iouRepayments) {
        LedgerMath.summarizeFriendIous(snapshot.expenses, snapshot.iouRepayments)
    }
    val goalProgress = remember(snapshot.savingsGoals, snapshot.goalTransactions) {
        LedgerMath.computeSavingsGoalsProgress(snapshot.savingsGoals, snapshot.goalTransactions)
    }
    val merchants = remember(snapshot.expenses, monthKey) {
        LedgerMath.computeMerchantInsights(snapshot.expenses, monthKey)
    }
    val calendarPair = remember(snapshot.expenses, monthKey) {
        LedgerMath.buildCalendarGrid(snapshot.expenses, monthKey)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insights & Cashflow") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        monthKey = LedgerMath.stepMonthKey(monthKey, -1)
                        selectedDateKey = null
                    }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month")
                    }
                    Text(monthKey, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    IconButton(onClick = {
                        monthKey = LedgerMath.stepMonthKey(monthKey, 1)
                        selectedDateKey = null
                    }) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next month")
                    }
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Budgets", "Cashflow", "Forecast", "Splits", "Goals", "Merchants", "Calendar").forEachIndexed { idx, label ->
                        FilterChip(
                            selected = activeSection == idx,
                            onClick = { activeSection = idx },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                }

                when (activeSection) {
                    0 -> {
                        Text(
                            text = "Monthly Expense Total: ₹${LedgerMath.formatDisplayAmt(monthTotal, hideBalances)} (${monthExpenses.size} entries)",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                        snapshot.labels.forEach { lbl ->
                            val spent = monthExpenses.filter { it.labelIds.contains(lbl.id) }.sumOf { it.balance }
                            val cap = lbl.monthlyBudget
                            val ratio = when {
                                cap != null && cap > 0.0 -> (spent / cap).toFloat().coerceIn(0f, 1f)
                                monthTotal > 0.0 -> (spent / monthTotal).toFloat().coerceIn(0f, 1f)
                                else -> 0f
                            }
                            val over = cap != null && spent > cap
                            val warn = cap != null && !over && (spent / cap) >= 0.8
                            val barColor = when {
                                over -> Color(0xFFDC2626)
                                warn -> Color(0xFFD97706)
                                else -> parseColorHex(lbl.colorHex)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(lbl.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = if (cap != null) "₹${LedgerMath.formatDisplayAmt(spent, hideBalances)} / ₹${LedgerMath.formatDisplayAmt(cap, hideBalances)}" else "₹${LedgerMath.formatDisplayAmt(spent, hideBalances)}",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { ratio },
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),
                                    color = barColor
                                )
                            }
                        }
                    }
                    1 -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Net Cashflow (Income − Paid)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    text = (if (cashflow.netCashflow >= 0) "+" else "-") + "₹${LedgerMath.formatDisplayAmt(kotlin.math.abs(cashflow.netCashflow), hideBalances)}",
                                    fontSize = 18.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (cashflow.netCashflow >= 0) Color(0xFF059669) else Color(0xFFDC2626)
                                )
                                Text(
                                    text = "Income +₹${LedgerMath.formatDisplayAmt(cashflow.totalIncome, hideBalances)} · Paid -₹${LedgerMath.formatDisplayAmt(cashflow.paidExpenses, hideBalances)}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Pending Hold+Need: ₹${LedgerMath.formatDisplayAmt(cashflow.unpaidObligations, hideBalances)}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Live Wallet Balances", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(
                                    onClick = { showOpeningEditor = !showOpeningEditor; showTransferForm = false },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) { Text("Opening", fontSize = 10.sp) }
                                FilledTonalButton(
                                    onClick = { showTransferForm = !showTransferForm; showOpeningEditor = false },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) { Text("Transfer", fontSize = 10.sp) }
                            }
                        }
                        liveWallets.forEach { lw ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(lw.mode.label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Text(
                                            text = "Open ₹${LedgerMath.formatDisplayAmt(lw.startingBalance, hideBalances)} · Out -₹${LedgerMath.formatDisplayAmt(lw.cashOutflow, hideBalances)}" + if (lw.repaymentsIn > 0.0) " · IOU +₹${LedgerMath.formatDisplayAmt(lw.repaymentsIn, hideBalances)}" else "",
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Text(
                                        text = "₹${LedgerMath.formatDisplayAmt(lw.liveBalance, hideBalances)}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                        if (showOpeningEditor) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(value = startUpi, onValueChange = { startUpi = it }, label = { Text("UPI Opening (₹)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                OutlinedTextField(value = startCash, onValueChange = { startCash = it }, label = { Text("Cash Opening (₹)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                OutlinedTextField(value = startCard, onValueChange = { startCard = it }, label = { Text("Card Opening (₹)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                OutlinedTextField(value = startBank, onValueChange = { startBank = it }, label = { Text("Bank Opening (₹)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                Button(
                                    onClick = {
                                        onUpdateOpeningBalances(
                                            mapOf(
                                                PaymentMode.UPI to (startUpi.toDoubleOrNull() ?: 0.0),
                                                PaymentMode.CASH to (startCash.toDoubleOrNull() ?: 0.0),
                                                PaymentMode.CARD to (startCard.toDoubleOrNull() ?: 0.0),
                                                PaymentMode.BANK to (startBank.toDoubleOrNull() ?: 0.0)
                                            )
                                        )
                                        showOpeningEditor = false
                                    }
                                ) { Text("Save Opening Balances", fontSize = 11.sp) }
                            }
                        }
                        if (showTransferForm) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    PaymentMode.entries.forEach { m ->
                                        FilterChip(selected = trfFrom == m, onClick = { trfFrom = m }, label = { Text("From " + m.label, fontSize = 9.sp) })
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    PaymentMode.entries.forEach { m ->
                                        FilterChip(selected = trfTo == m, onClick = { trfTo = m }, label = { Text("To " + m.label, fontSize = 9.sp) })
                                    }
                                }
                                OutlinedTextField(value = trfAmtText, onValueChange = { trfAmtText = it }, label = { Text("Transfer Amount (₹)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                OutlinedTextField(value = trfNote, onValueChange = { trfNote = it }, label = { Text("Note (Optional)") }, singleLine = true)
                                Button(
                                    onClick = {
                                        val amt = trfAmtText.toDoubleOrNull() ?: 0.0
                                        if (amt > 0.0 && trfFrom != trfTo) {
                                            onCreateTransfer(trfFrom, trfTo, amt, trfNote)
                                            trfAmtText = ""
                                            trfNote = ""
                                            showTransferForm = false
                                        }
                                    }
                                ) { Text("Record Transfer", fontSize = 11.sp) }
                            }
                        }
                        if (snapshot.walletTransfers.isNotEmpty()) {
                            Text("Recent Transfers", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            snapshot.walletTransfers.take(5).forEach { trf ->
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("${trf.fromMode.label} → ${trf.toMode.label} · ₹${LedgerMath.formatDisplayAmt(trf.amount, hideBalances)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    TextButton(onClick = { onDeleteTransfer(trf.id) }) { Text("Delete", fontSize = 10.sp, color = Color(0xFFDC2626)) }
                                }
                            }
                        }
                        Text("Monthly Spend Breakdown", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        wallets.forEach { w ->
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${w.mode.label} (${w.count})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = "Out ₹${LedgerMath.formatDisplayAmt(w.expenseTotal, hideBalances)} (${w.sharePct}%)",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { (w.sharePct / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(4.dp))
                                )
                            }
                        }
                    }
                    2 -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("30-Day Liquidity Projection", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    text = "Liquid Cash: ₹${LedgerMath.formatDisplayAmt(forecast30d.liquidCash, hideBalances)}",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Projected Outflow: -₹${LedgerMath.formatDisplayAmt(forecast30d.projectedOutflow, hideBalances)}",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFFD97706)
                                )
                                Text(
                                    text = if (forecast30d.shortfallAmount > 0.0) "Shortfall: ₹${LedgerMath.formatDisplayAmt(forecast30d.shortfallAmount, hideBalances)}" else "Projected Balance: ₹${LedgerMath.formatDisplayAmt(forecast30d.projectedBalance, hideBalances)}",
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (forecast30d.shortfallAmount > 0.0) Color(0xFFDC2626) else Color(0xFF059669)
                                )
                            }
                        }
                        Text("Upcoming Bills & Recurring Templates", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        if (forecast30d.items.isEmpty()) {
                            Text("No upcoming dated bills or recurring templates within 30 days.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            forecast30d.items.forEach { item ->
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${item.dateKey} · ${item.title}", fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("-₹${LedgerMath.formatDisplayAmt(item.amount, hideBalances)}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    3 -> {
                        Text("Split Bills & Friend IOUs", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        if (friendIous.isEmpty()) {
                            Text("Add friend shares inside an expense editor to track IOUs separately.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            friendIous.forEach { iou ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("${iou.friendName} (${iou.itemCount} splits)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            Text(
                                                text = if (iou.outstanding > 0.0) "Owes ₹${LedgerMath.formatDisplayAmt(iou.outstanding, hideBalances)}" else "Settled",
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = if (iou.outstanding > 0.0) Color(0xFFD97706) else Color(0xFF059669)
                                            )
                                        }
                                        Text(
                                            text = "Shared ₹${LedgerMath.formatDisplayAmt(iou.totalShared, hideBalances)} · Repaid ₹${LedgerMath.formatDisplayAmt(iou.totalRepaid, hideBalances)}",
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        if (iou.outstanding > 0.0) {
                                            OutlinedButton(
                                                onClick = {
                                                    repayFriend = iou.friendName
                                                    repayAmt = formatAmount(iou.outstanding)
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) { Text("Settle IOU", fontSize = 10.sp) }
                                        }
                                    }
                                }
                            }
                        }
                        if (repayFriend.isNotBlank()) {
                            HorizontalDivider()
                            Text("Record Repayment from $repayFriend", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            OutlinedTextField(
                                value = repayAmt,
                                onValueChange = { repayAmt = it },
                                label = { Text("Repaid Amount (₹)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true
                            )
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PaymentMode.entries.forEach { m ->
                                    FilterChip(selected = repayWallet == m, onClick = { repayWallet = m }, label = { Text(m.label, fontSize = 10.sp) })
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    val amt = repayAmt.toDoubleOrNull() ?: 0.0
                                    if (amt > 0.0) {
                                        onRecordIouRepayment(repayFriend, amt, repayWallet, "IOU repayment")
                                        repayFriend = ""
                                        repayAmt = ""
                                    }
                                }) { Text("Credit Wallet", fontSize = 11.sp) }
                                TextButton(onClick = { repayFriend = "" }) { Text("Cancel", fontSize = 11.sp) }
                            }
                        }
                    }
                    4 -> {
                        Text("Savings Goals & Sinking Funds", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        goalProgress.forEach { gp ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(gp.goal.title, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Text("${gp.pct}%", fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    }
                                    LinearProgressIndicator(
                                        progress = { (gp.pct / 100f).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp))
                                    )
                                    Text(
                                        text = "Saved ₹${LedgerMath.formatDisplayAmt(gp.savedAmount, hideBalances)} / ₹${LedgerMath.formatDisplayAmt(gp.goal.targetAmount, hideBalances)}",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        FilledTonalButton(
                                            onClick = {
                                                val amt = goalTxAmt.toDoubleOrNull() ?: 500.0
                                                if (amt > 0.0) onGoalTransaction(gp.goal.id, "deposit", amt, goalTxWallet)
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) { Text("+ Deposit ₹$goalTxAmt", fontSize = 10.sp) }
                                        OutlinedButton(
                                            onClick = {
                                                val amt = goalTxAmt.toDoubleOrNull() ?: 500.0
                                                if (amt > 0.0) onGoalTransaction(gp.goal.id, "withdraw", amt, goalTxWallet)
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) { Text("Withdraw", fontSize = 10.sp) }
                                    }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = goalTxAmt,
                            onValueChange = { goalTxAmt = it },
                            label = { Text("Deposit / Withdraw Amount (₹)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true
                        )
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PaymentMode.entries.forEach { m ->
                                FilterChip(selected = goalTxWallet == m, onClick = { goalTxWallet = m }, label = { Text(m.label, fontSize = 10.sp) })
                            }
                        }
                        HorizontalDivider()
                        Text("New Savings Goal", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(value = newGoalTitle, onValueChange = { newGoalTitle = it }, label = { Text("Goal Title") }, singleLine = true)
                        OutlinedTextField(value = newGoalTarget, onValueChange = { newGoalTarget = it }, label = { Text("Target Amount (₹)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(value = newGoalDate, onValueChange = { newGoalDate = it }, label = { Text("Target Date (YYYY-MM-DD · Optional)") }, singleLine = true)
                        Button(onClick = {
                            val tAmt = newGoalTarget.toDoubleOrNull() ?: 0.0
                            if (newGoalTitle.isNotBlank() && tAmt > 0.0) {
                                onCreateGoal(newGoalTitle, tAmt, newGoalDate)
                                newGoalTitle = ""
                                newGoalTarget = ""
                                newGoalDate = ""
                            }
                        }) { Text("Add Goal", fontSize = 11.sp) }
                    }
                    5 -> {
                        Text("Most Frequent Merchants", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        if (merchants.isEmpty()) {
                            Text("No expenses logged for $monthKey.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            merchants.take(8).forEachIndexed { idx, m ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("${idx + 1}. ${m.displayName}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${m.count}x · ${m.primaryMode.label} · Avg ₹${LedgerMath.formatDisplayAmt(m.avgSpent, hideBalances)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(
                                            text = "₹${LedgerMath.formatDisplayAmt(m.totalSpent, hideBalances)}",
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        FilledTonalButton(
                            onClick = onOpenInternationalCalendar,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Open Full International Calendar", fontSize = 12.sp)
                        }
                        val (firstWeekdayMon0, cells) = calendarPair
                        val totalSlots = firstWeekdayMon0 + cells.size
                        val rowCount = (totalSlots + 6) / 7
                        val weekdays = listOf("M", "T", "W", "T", "F", "S", "S")
                        Row(modifier = Modifier.fillMaxWidth()) {
                            weekdays.forEach { d ->
                                Text(
                                    text = d,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        for (r in 0 until rowCount) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (c in 0 until 7) {
                                    val slot = r * 7 + c
                                    val idx = slot - firstWeekdayMon0
                                    if (idx < 0 || idx >= cells.size) {
                                        Spacer(modifier = Modifier.weight(1f).height(38.dp))
                                    } else {
                                        val cell = cells[idx]
                                        val picked = selectedDateKey == cell.dateKey
                                        Surface(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(40.dp)
                                                .clickable { selectedDateKey = if (picked) null else cell.dateKey },
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (picked) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else if (cell.total > 0.0) Color(0xFF2563EB).copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(2.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                Text("${cell.day}", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                if (cell.total > 0.0) {
                                                    Text("₹${LedgerMath.formatDisplayAmt(cell.total, hideBalances)}", fontSize = 8.sp, maxLines = 1)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val pickedCell = cells.firstOrNull { it.dateKey == selectedDateKey }
                        if (pickedCell != null) {
                            Text(
                                text = "${pickedCell.dateKey}: ₹${LedgerMath.formatDisplayAmt(pickedCell.total, hideBalances)}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (pickedCell.expenses.isEmpty()) {
                                Text("No expenses recorded on this day.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                pickedCell.expenses.forEach { e ->
                                    Text(
                                        text = "• ₹${LedgerMath.formatDisplayAmt(e.balance, hideBalances)} (${e.status.label}) — ${e.quote}",
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun RecycleBinDialog(
    snapshot: LedgerSnapshot,
    onDismiss: () -> Unit,
    onChangeRetention: (Int?) -> Unit,
    onRestore: (BinnedRecord) -> Unit,
    onDeletePermanent: (String) -> Unit,
    onEmptyBin: () -> Unit
) {
    var confirmEmpty by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recycle Bin (${snapshot.bin.size})") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Auto-Purge Retention", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf<Pair<String, Int?>>("30 Days" to 30, "60 Days" to 60, "90 Days" to 90, "Manual Only" to null).forEach { (title, days) ->
                        FilterChip(
                            selected = snapshot.binRetentionDays == days,
                            onClick = { onChangeRetention(days) },
                            label = { Text(title, fontSize = 11.sp) }
                        )
                    }
                }

                if (snapshot.bin.isEmpty()) {
                    Text(
                        text = "Recycle Bin is empty. Deleted items stay here for your selected retention period before auto-purge.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    snapshot.bin.forEach { item ->
                        val daysLeft = LedgerMath.daysRemainingInBin(item.deletedAt, snapshot.binRetentionDays)
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(item.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    if (item.amount != null) {
                                        Text("₹${LedgerMath.formatDisplayAmt(item.amount, snapshot.hideBalances)}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                                Text(item.subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = if (daysLeft == null) "Manual retention" else "Purges in $daysLeft d",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFFB45309)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { onRestore(item) }) {
                                        Icon(Icons.Default.RestoreFromTrash, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Restore", fontSize = 11.sp)
                                    }
                                    TextButton(onClick = { onDeletePermanent(item.id) }) {
                                        Text("Delete Permanently", fontSize = 11.sp, color = Color(0xFFDC2626))
                                    }
                                }
                            }
                        }
                    }

                    if (!confirmEmpty) {
                        OutlinedButton(
                            onClick = { confirmEmpty = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Empty Bin Now", color = Color(0xFFDC2626))
                        }
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    confirmEmpty = false
                                    onEmptyBin()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Confirm Purge")
                            }
                            TextButton(onClick = { confirmEmpty = false }) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun PrivacyPinDialog(
    snapshot: LedgerSnapshot,
    onDismiss: () -> Unit,
    onToggleHideBalances: (Boolean) -> Unit,
    onToggleBiometric: (Boolean) -> Unit,
    onChangeThemeMode: (ThemeMode) -> Unit,
    onChangeAutoLock: (Int) -> Unit,
    onToggleDueReminders: (Boolean) -> Unit,
    onOpenAutoRules: () -> Unit,
    onExportEbak: () -> Unit,
    onImportEbak: () -> Unit,
    onSetPin: (String) -> Unit,
    onRemovePin: () -> Unit,
    onLockNow: () -> Unit
) {
    val hasPin = !snapshot.privacyPinHash.isNullOrBlank()
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Privacy & Preferences") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Hide Balances (₹••••)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Mask amounts across tabs & summaries", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = snapshot.hideBalances, onCheckedChange = onToggleHideBalances)
                }

                Text("App Theme Mode", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = snapshot.themeMode == mode,
                            onClick = { onChangeThemeMode(mode) },
                            label = { Text(mode.label, fontSize = 11.sp) }
                        )
                    }
                }

                Text("Auto-Lock Timeout", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Off" to 0, "30s" to 30, "1m" to 60, "5m" to 300).forEach { (lbl, secs) ->
                        FilterChip(
                            selected = snapshot.autoLockSeconds == secs,
                            onClick = { onChangeAutoLock(secs) },
                            label = { Text(lbl, fontSize = 11.sp) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Daily Due Bill Reminders", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("WorkManager local alerts for unpaid bills", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = snapshot.dueRemindersEnabled, onCheckedChange = onToggleDueReminders)
                }

                OutlinedButton(onClick = onOpenAutoRules, modifier = Modifier.fillMaxWidth()) {
                    Text("Auto-Label Rules (${snapshot.autoLabelRules.size})", fontSize = 12.sp)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onExportEbak, modifier = Modifier.weight(1f)) {
                        Text("Export .ebak", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = onImportEbak, modifier = Modifier.weight(1f)) {
                        Text("Restore .ebak", fontSize = 11.sp)
                    }
                }

                HorizontalDivider()

                if (hasPin) {
                    Text("4-Digit PIN Lock Active", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF059669))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Biometric Unlock", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Fingerprint / Face via BiometricPrompt", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = snapshot.biometricEnabled, onCheckedChange = onToggleBiometric)
                    }
                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = { if (it.length <= 4) currentPin = it.filter { c -> c.isDigit() } },
                        label = { Text("Current 4-Digit PIN") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = newPin,
                    onValueChange = { if (it.length <= 4) newPin = it.filter { c -> c.isDigit() } },
                    label = { Text(if (hasPin) "New 4-Digit PIN" else "Set 4-Digit PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { if (it.length <= 4) confirmPin = it.filter { c -> c.isDigit() } },
                    label = { Text("Confirm 4-Digit PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )

                if (!errorMsg.isNullOrBlank()) {
                    Text(errorMsg!!, fontSize = 11.sp, color = Color(0xFFDC2626))
                }

                if (hasPin) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onLockNow, modifier = Modifier.weight(1f)) {
                            Text("Lock Now", fontSize = 11.sp)
                        }
                        TextButton(
                            onClick = {
                                if (!LedgerMath.verifyPin(currentPin, snapshot.privacyPinHash)) {
                                    errorMsg = "Enter your current PIN to disable lock."
                                } else {
                                    onRemovePin()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Disable PIN", fontSize = 11.sp, color = Color(0xFFDC2626))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (hasPin && !LedgerMath.verifyPin(currentPin, snapshot.privacyPinHash)) {
                        errorMsg = "Current PIN is incorrect."
                        return@Button
                    }
                    if (!Regex("""^\d{4}$""").matches(newPin)) {
                        errorMsg = "PIN must be exactly 4 digits."
                        return@Button
                    }
                    if (newPin != confirmPin) {
                        errorMsg = "New PINs do not match."
                        return@Button
                    }
                    errorMsg = null
                    onSetPin(newPin)
                    onDismiss()
                }
            ) { Text(if (hasPin) "Update PIN" else "Enable PIN") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun CsvImportDialog(
    initialCsvText: String,
    existingLabels: List<LabelRecord>,
    autoRules: List<AutoLabelRule> = emptyList(),
    onPickFile: () -> Unit,
    onDismiss: () -> Unit,
    onConfirmImport: (List<ExpenseRecord>, List<LabelRecord>) -> Unit
) {
    var csvText by remember(initialCsvText) { mutableStateOf(initialCsvText) }
    val preview = remember(csvText, existingLabels, autoRules) {
        if (csvText.isBlank()) null else LedgerMath.parseCsvImport(csvText, existingLabels, autoRules)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import CSV Spreadsheet") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickFile, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Pick .csv", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            csvText = "Balance (INR),Quote,Status,Entry Type,Payment Mode,Pinned,Due Date,Labels\n240,Metro Top-up,Paid,Expense,UPI,No,,#Transport\n1500,Bonus Refund,Paid,Income,Bank,No,,"
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Load Sample", fontSize = 11.sp)
                    }
                }
                OutlinedTextField(
                    value = csvText,
                    onValueChange = { csvText = it },
                    label = { Text("CSV Data") },
                    minLines = 4
                )
                if (preview != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Preview: ${preview.validEntries.size} valid · ${preview.newLabels.size} new labels · ${preview.skippedRows} skipped",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            preview.errors.take(3).forEach { err ->
                                Text(err, fontSize = 10.sp, color = Color(0xFFB45309))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = preview != null && preview.validEntries.isNotEmpty(),
                onClick = {
                    if (preview != null && preview.validEntries.isNotEmpty()) {
                        onConfirmImport(preview.validEntries, preview.newLabels)
                    }
                }
            ) { Text("Import (${preview?.validEntries?.size ?: 0})") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun PartialPaymentDialog(
    expense: ExpenseRecord,
    hideBalances: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Double, PaymentMode, String) -> Unit
) {
    val alreadyPaid = LedgerMath.expensePaidPart(expense)
    val remaining = LedgerMath.expenseRemainingPart(expense)
    var amtText by remember { mutableStateOf(formatAmount(remaining)) }
    var mode by remember { mutableStateOf(expense.paymentMode) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pay Part — ${expense.status.label} Bill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(expense.quote, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    text = "Paid ₹${LedgerMath.formatDisplayAmt(alreadyPaid, hideBalances)} · Left ₹${LedgerMath.formatDisplayAmt(remaining, hideBalances)}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("25%" to 0.25, "50%" to 0.50, "Full" to 1.0).forEach { (lbl, factor) ->
                        OutlinedButton(
                            onClick = {
                                amtText = formatAmount(kotlin.math.round(remaining * factor * 100.0) / 100.0)
                                error = null
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text(lbl, fontSize = 11.sp) }
                    }
                }
                OutlinedTextField(
                    value = amtText,
                    onValueChange = { amtText = it; error = null },
                    label = { Text("Amount (₹)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PaymentMode.entries.forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label, fontSize = 11.sp) })
                    }
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (Optional)") }, singleLine = true)
                if (!error.isNullOrBlank()) {
                    Text(error!!, fontSize = 11.sp, color = Color(0xFFDC2626))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val amt = amtText.toDoubleOrNull() ?: 0.0
                if (amt <= 0.0 || amt > remaining + 0.01) {
                    error = "Enter an amount between ₹1 and ₹${formatAmount(remaining)}."
                } else {
                    onConfirm(amt, mode, note)
                }
            }) { Text("Confirm Pay") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ReceiptViewerDialog(
    expense: ExpenseRecord,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val att = expense.receiptAttachment
    val bitmap = remember(att?.uriOrDataUrl) {
        if (att == null) null
        else try {
            val uri = Uri.parse(att.uriOrDataUrl)
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOpts)
            }
            var sampleSize = 1
            val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
            if (maxDim > 1080) {
                while (maxDim / sampleSize > 1080) {
                    sampleSize *= 2
                }
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)?.asImageBitmap()
            }
        } catch (_: Throwable) {
            null
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(expense.quote, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = att?.name ?: "Receipt photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(10.dp))
                )
            } else {
                Text("Receipt URI: ${att?.name ?: "Unavailable"}", fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun PinLockOverlay(
    pinHash: String,
    biometricEnabled: Boolean,
    onBiometricClick: () -> Unit,
    onUnlock: () -> Unit
) {
    var entered by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }

    fun pressDigit(d: String) {
        if (entered.length >= 4) return
        val next = entered + d
        entered = next
        mismatch = false
        if (next.length == 4) {
            if (LedgerMath.verifyPin(next, pinHash)) {
                entered = ""
                onUnlock()
            } else {
                mismatch = true
                entered = ""
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Text("Ledger Locked", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (mismatch) "Incorrect PIN. Try again." else "Enter your 4-digit Privacy PIN",
                fontSize = 12.sp,
                color = if (mismatch) Color(0xFFDC2626) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (i in 0 until 4) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(if (i < entered.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            val keypad = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("C", "0", "<")
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(240.dp)) {
                keypad.forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { key ->
                            FilledTonalButton(
                                onClick = {
                                    when (key) {
                                        "C" -> { entered = ""; mismatch = false }
                                        "<" -> { if (entered.isNotEmpty()) entered = entered.dropLast(1) }
                                        else -> pressDigit(key)
                                    }
                                },
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape = CircleShape
                            ) {
                                Text(key, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
            if (biometricEnabled) {
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = onBiometricClick) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Unlock with Biometrics", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun InternationalCalendarDialog(
    appName: String,
    snapshot: LedgerSnapshot,
    onUpdatePrefs: (CalendarPreferences) -> Unit,
    onLogOnDate: (String) -> Unit,
    onFilterFeedByDate: (String) -> Unit,
    onPartialPay: (ExpenseRecord) -> Unit,
    onLogTemplateNow: (ExpenseTemplateRecord) -> Unit,
    onShareIcs: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val initialMonth = remember(snapshot.expenses) {
        LedgerMath.availableMonths(snapshot.expenses).firstOrNull() ?: LedgerMath.nowIso().take(7)
    }
    var monthKey by remember { mutableStateOf(initialMonth) }
    var prefs by remember(snapshot.calendarPrefs) { mutableStateOf(snapshot.calendarPrefs) }
    val monthGrid = remember(snapshot, monthKey, prefs) {
        LedgerMath.buildInternationalCalendarMonth(snapshot, monthKey, prefs)
    }
    var selectedDateKey by remember(monthKey) {
        mutableStateOf(
            monthGrid.cells.firstOrNull {
                it.expenses.isNotEmpty() || it.dueBills.isNotEmpty() || it.holidays.isNotEmpty()
            }?.dateKey ?: monthGrid.cells.firstOrNull()?.dateKey ?: "$monthKey-01"
        )
    }
    val selectedCell = monthGrid.cells.firstOrNull { it.dateKey == selectedDateKey }
    val hideBalances = snapshot.hideBalances

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("International Calendar", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = { onShareIcs(LedgerMath.generateBillsIcsCalendar(snapshot, appName)) }) {
                    Text(".ics", fontSize = 11.sp)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { monthKey = LedgerMath.stepMonthKey(monthKey, -1) }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month")
                    }
                    Text(monthKey, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    IconButton(onClick = { monthKey = LedgerMath.stepMonthKey(monthKey, 1) }) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next month")
                    }
                }

                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CalendarWeekStart.entries.forEach { ws ->
                        FilterChip(
                            selected = prefs.weekStart == ws,
                            onClick = {
                                val next = prefs.copy(weekStart = ws)
                                prefs = next
                                onUpdatePrefs(next)
                            },
                            label = { Text(ws.label, fontSize = 10.sp) }
                        )
                    }
                    FilterChip(
                        selected = prefs.showIsoWeeks,
                        onClick = {
                            val next = prefs.copy(showIsoWeeks = !prefs.showIsoWeeks)
                            prefs = next
                            onUpdatePrefs(next)
                        },
                        label = { Text("ISO Wk", fontSize = 10.sp) }
                    )
                }

                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CalendarRegionCode.entries.forEach { reg ->
                        FilterChip(
                            selected = prefs.region == reg,
                            onClick = {
                                val next = prefs.copy(region = reg)
                                prefs = next
                                onUpdatePrefs(next)
                            },
                            label = { Text(reg.label, fontSize = 10.sp) }
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (prefs.showIsoWeeks) {
                        Text("Wk", fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    monthGrid.weekHeaders.forEach { h ->
                        Text(h.take(2), fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                val rowCount = (monthGrid.leadingBlanks + monthGrid.cells.size + 6) / 7
                for (r in 0 until rowCount) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (prefs.showIsoWeeks) {
                            val wk = monthGrid.rowWeekNumbers.getOrNull(r) ?: 1
                            Text("W$wk", fontSize = 8.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        for (c in 0 until 7) {
                            val idx = r * 7 + c - monthGrid.leadingBlanks
                            if (idx < 0 || idx >= monthGrid.cells.size) {
                                Spacer(modifier = Modifier.weight(1f).height(38.dp))
                            } else {
                                val cell = monthGrid.cells[idx]
                                val picked = cell.dateKey == selectedDateKey
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .clickable { selectedDateKey = cell.dateKey },
                                    shape = RoundedCornerShape(6.dp),
                                    color = when {
                                        picked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
                                        cell.dueBills.isNotEmpty() -> Color(0xFFD97706).copy(alpha = 0.22f)
                                        cell.expenseTotal > 0.0 -> Color(0xFF2563EB).copy(alpha = 0.16f)
                                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    }
                                ) {
                                    Column(modifier = Modifier.padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Text("${cell.day}", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        if (cell.holidays.isNotEmpty()) {
                                            Text("★", fontSize = 8.sp, color = Color(0xFF7C3AED))
                                        } else if (cell.expenseTotal > 0.0) {
                                            Text("₹${LedgerMath.formatDisplayAmt(cell.expenseTotal, hideBalances)}", fontSize = 7.sp, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (selectedCell != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Day Agenda · ${selectedCell.dateKey}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilledTonalButton(onClick = { onLogOnDate(selectedCell.dateKey) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                                    Text("+ Log on Date", fontSize = 10.sp)
                                }
                                OutlinedButton(onClick = { onFilterFeedByDate(selectedCell.dateKey) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                                    Text("Filter Feed", fontSize = 10.sp)
                                }
                            }
                            selectedCell.holidays.forEach { h ->
                                Text("★ ${h.name} (${h.region.label})", fontSize = 11.sp, color = Color(0xFF7C3AED), fontWeight = FontWeight.SemiBold)
                            }
                            selectedCell.dueBills.forEach { bill ->
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Due: ${bill.quote} · ₹${LedgerMath.formatDisplayAmt(LedgerMath.expenseRemainingPart(bill), hideBalances)}", fontSize = 11.sp, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { onPartialPay(bill) }) { Text("Pay Part", fontSize = 10.sp) }
                                }
                            }
                            selectedCell.projectedTemplates.forEach { tpl ->
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Recurring: ${tpl.title} · ₹${LedgerMath.formatDisplayAmt(tpl.balance, hideBalances)}", fontSize = 11.sp, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { onLogTemplateNow(tpl) }) { Text("Log Now", fontSize = 10.sp) }
                                }
                            }
                            selectedCell.expenses.forEach { e ->
                                Text("• ${e.entryType.label}: ${e.quote} — ₹${LedgerMath.formatDisplayAmt(e.balance, hideBalances)}", fontSize = 11.sp)
                            }
                            selectedCell.transfers.forEach { trf ->
                                Text("⇄ Transfer: ${trf.fromMode.label} → ${trf.toMode.label} ₹${LedgerMath.formatDisplayAmt(trf.amount, hideBalances)}", fontSize = 11.sp)
                            }
                            selectedCell.repayments.forEach { rep ->
                                Text("✓ IOU Repaid (${rep.friendName}): ₹${LedgerMath.formatDisplayAmt(rep.amount, hideBalances)}", fontSize = 11.sp, color = Color(0xFF059669))
                            }
                            selectedCell.goalTargets.forEach { g ->
                                Text("◎ Goal Target: ${g.title} (₹${LedgerMath.formatDisplayAmt(g.targetAmount, hideBalances)})", fontSize = 11.sp, color = Color(0xFF059669))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun AutoRulesDialog(
    rules: List<AutoLabelRule>,
    labels: List<LabelRecord>,
    onSaveRules: (List<AutoLabelRule>) -> Unit,
    onDismiss: () -> Unit
) {
    var draftRules by remember(rules) { mutableStateOf(rules) }
    var keywordText by remember { mutableStateOf("") }
    var pickedLabelId by remember { mutableStateOf(labels.firstOrNull()?.id ?: "") }
    var pickedMode by remember { mutableStateOf<PaymentMode?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auto-Label Rules") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Match keywords in SMS, quotes, or CSV rows to auto-apply #Labels and default wallet.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = keywordText,
                    onValueChange = { keywordText = it },
                    label = { Text("Keyword (e.g. clinic, swiggy)") },
                    singleLine = true
                )
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    labels.forEach { lbl ->
                        FilterChip(
                            selected = pickedLabelId == lbl.id,
                            onClick = { pickedLabelId = lbl.id },
                            label = { Text(lbl.name, fontSize = 10.sp) }
                        )
                    }
                }
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = pickedMode == null, onClick = { pickedMode = null }, label = { Text("Any Mode", fontSize = 10.sp) })
                    PaymentMode.entries.forEach { m ->
                        FilterChip(selected = pickedMode == m, onClick = { pickedMode = m }, label = { Text(m.label, fontSize = 10.sp) })
                    }
                }
                Button(
                    onClick = {
                        val kw = keywordText.trim()
                        if (kw.isNotBlank() && pickedLabelId.isNotBlank()) {
                            draftRules = listOf(AutoLabelRule(keyword = kw, labelIds = listOf(pickedLabelId), defaultPaymentMode = pickedMode, enabled = true)) + draftRules
                            keywordText = ""
                        }
                    }
                ) { Text("Add Rule", fontSize = 11.sp) }

                HorizontalDivider()
                draftRules.forEach { r ->
                    val lblName = labels.firstOrNull { r.labelIds.contains(it.id) }?.name ?: "#Label"
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Keyword '${r.keyword}' → $lblName" + (r.defaultPaymentMode?.let { " · ${it.label}" } ?: ""), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Switch(
                            checked = r.enabled,
                            onCheckedChange = { chk ->
                                draftRules = draftRules.map { if (it.id == r.id) it.copy(enabled = chk) else it }
                            }
                        )
                        TextButton(onClick = { draftRules = draftRules.filterNot { it.id == r.id } }) {
                            Text("Del", fontSize = 10.sp, color = Color(0xFFDC2626))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSaveRules(draftRules)
                onDismiss()
            }) { Text("Save Rules") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun EncryptedBackupDialog(
    mode: String,
    onDismiss: () -> Unit,
    onExportPassphrase: (String) -> Unit,
    onDecryptPassphrase: (String) -> Boolean
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmPass by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (mode == "export") "Export Encrypted .ebak" else "Decrypt & Restore .ebak") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Uses PBKDF2-HMAC-SHA256 (100k rounds) + AES-256-GCM encryption compatible across Android and Web Simulator.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it; errorMsg = null },
                    label = { Text("Backup Passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                if (mode == "export") {
                    OutlinedTextField(
                        value = confirmPass,
                        onValueChange = { confirmPass = it; errorMsg = null },
                        label = { Text("Confirm Passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                }
                if (!errorMsg.isNullOrBlank()) {
                    Text(errorMsg!!, fontSize = 11.sp, color = Color(0xFFDC2626))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (passphrase.trim().length < 4) {
                    errorMsg = "Enter a passphrase of at least 4 characters."
                    return@Button
                }
                if (mode == "export") {
                    if (passphrase != confirmPass) {
                        errorMsg = "Passphrases do not match."
                        return@Button
                    }
                    onExportPassphrase(passphrase)
                } else {
                    if (!onDecryptPassphrase(passphrase)) {
                        errorMsg = "Incorrect passphrase or corrupted .ebak archive."
                    }
                }
            }) {
                Text(if (mode == "export") "Save .ebak File" else "Decrypt & Restore")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
