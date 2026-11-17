# Expense Ledger (Android)

Package ID: `com.example.expenseledger`
Version: `1.0` (Code `1`)

## Features Included
- **Live Balance Dashboard**: Isolated `PAID (₹)`, `HOLD (₹)`, `NEED (₹)`, and Grand Commitment calculated strictly from the Expenses table.
- **Expenses**: Search bar, `All / Paid / Hold / Need` & label filters, 1-tap status cycling, multi-select Labels, expandable attached Lists, and audit log.
- **Labels**: Isolated Paid/Hold/Need sums per label, linked expense list, direct add-expense inside label, and 1-tap label summary share.
- **Lists**: Itemized notes with automatic numeric sum detection and 1-tap "Use in New Expense".
- **Slide-up Calculator**: `+`, `-`, `÷`, `×`, `=`, `%`, `AC/C`, `00.0`, timestamped History tape, and 1-tap "Send to Expense" / "Insert into List".
- **Undo & Backup/Share (v1.1)**: 4-second Snackbar Undo on deletes, Storage Access Framework JSON Backup & Restore, Shareable plain-text summaries, and custom adaptive vector launcher icon.
- **Persistent SQLite + JSON Storage** (`LedgerStorage.kt`).
- **CI/CD Pipeline** (`.github/workflows/build-apk.yml`): Dual-path PKCS12 signing with `gradle assembleRelease`.
