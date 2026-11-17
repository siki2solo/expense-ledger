# Expense Ledger (Android)

Package ID: `com.example.expenseledger`
Version: `1.4` (Code `5`)

## Features Included
- **Live Balance Dashboard**: Isolated `PAID (₹)`, `HOLD (₹)`, `NEED (₹)`, and Grand Commitment calculated strictly from active Expenses (with partial settlements credited live to `PAID`), plus Net Cashflow indicator when Income entries exist.
- **Partial Payments on Hold & Need Bills (v1.4)**: Record inline `Pay Part` settlements with `25% / 50% / Full` presets and wallet tracking without double-counting totals.
- **Live Wallet Balances & Transfers (v1.4)**: Opening balances per wallet (`UPI / Cash / Card / Bank`) plus internal transfers inside `Insights → Cashflow`.
- **Biometric Unlock & Persistent Theme Mode (v1.4)**: Android `BiometricPrompt` (`FragmentActivity`) alongside SHA-256 4-digit PIN lock and persistent `System | Light | Dark` theme preference.
- **Date-Range Filter, Merchant Auto-Suggest & PDF Export (v1.4)**: `Today / 7D / Month` chips, historical merchant suggestions, and formatted multi-page `PdfDocument` statement export (`.pdf`).
- **Receipt Photo Attachments (v1.4)**: Scoped Android Photo Picker (`PickVisualMedia`, zero storage permissions required) with inline receipt viewer.
- **Home Screen Shortcuts & Due Bill Reminders (v1.4)**: Static launcher shortcuts (`new_expense`, `quick_calc`, `insights`) and `WorkManager` daily due alerts (`BillDueWorker`).
- **Wallets, Smart Paste SMS, Pinned/Due Dates, CSV Import/Export, 4-Tab Insights, Recycle Bin, Bulk Select, Templates & Calculator**.
- **Persistent SQLite + JSON Storage** (`LedgerStorage.kt`) & **GitHub Actions Signed APK CI/CD** (`.github/workflows/build-apk.yml`).
