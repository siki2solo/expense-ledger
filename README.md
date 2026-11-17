# Expense Ledger (Android)

Package ID: `com.example.expenseledger`
Version: `1.0` (Code `1`)

## Features Included
- **Live Balance Dashboard**: Isolated `PAID (₹)`, `HOLD (₹)`, `NEED (₹)`, and Grand Commitment calculated strictly from active Expenses, plus optional Net Cashflow indicator when Income entries exist.
- **Wallets & Income Cashflow (v1.3)**: Track `UPI / Cash / Card / Bank` payment modes and `Income (+₹)` vs `Expense` entries with dedicated filter chips and Insights breakdown.
- **Privacy PIN Lock & Balance Masking (v1.3)**: 1-tap `Hide Balances (₹••••)` eye toggle and SHA-256 hashed 4-digit PIN lock overlay.
- **Smart Paste Bank / UPI SMS (v1.3)**: Zero-permission collapsible paste auto-fill extracting amount, type, payment mode, and merchant quote inside the Entry Editor.
- **Pinned Bills & Due Dates (v1.3)**: Pin priority entries to the top and track due countdowns (`Overdue`, `Due Today`, `Due in Xd`).
- **Expenses**: Search bar, `Pinned / Income / Expense / Paid / Hold / Need / UPI / Cash / Card / Bank` & label filters, Bulk Select action bar, Quick Templates & Recurring Bills, 1-tap Duplicate, multi-select Labels, and expandable attached Lists.
- **Insights (Budgets · Cashflow · Merchants · Calendar)**: Monthly budget progress bars per Label, Net Cashflow & Wallet breakdown, Most Frequent Merchants ranking, and Mon–Sun daily spend Calendar grid.
- **Recycle Bin & Retention**: Soft-delete Expenses, Labels, Lists, and Templates into Bin with `30 / 60 / 90 Days` or `Manual Only` auto-purge, Restore, Delete Permanently, Empty Bin, and Snackbar Undo.
- **Labels & Lists**: Isolated Paid/Hold/Need sums per label with monthly budget caps, itemized notes with auto-sum detection, and Slide-up Calculator with tape history.
- **CSV Import / Export & Backup**: Storage Access Framework CSV Import & Export (`.csv`), JSON Backup & Restore (`.json`), Shareable plain-text summaries, and custom adaptive vector launcher icon.
- **Persistent SQLite + JSON Storage** (`LedgerStorage.kt`).
- **CI/CD Pipeline** (`.github/workflows/build-apk.yml`): Dual-path PKCS12 signing with `gradle assembleRelease`.
