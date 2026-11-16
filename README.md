# Expense Ledger (Android)

Package ID: `com.example.expenseledger`
Version: `1.0` (Code `1`)

## Features Included
- **Live Balance Dashboard**: Isolated `PAID (₹)`, `HOLD (₹)`, `NEED (₹)`, and Grand Commitment calculated strictly from the Expenses table.
- **Expenses**: Multi-select Labels, optional attached List as expandable 2nd description, and expandable Created/Updated audit log.
- **Labels**: Isolated Paid/Hold/Need sums per label, linked expense list, direct add-expense inside label, and audit history.
- **Lists**: Itemized notes with automatic numeric sum detection and 1-tap "Use in New Expense".
- **Slide-up Calculator**: `+`, `-`, `÷`, `×`, `=`, `%`, `AC/C`, `00.0`, timestamped History tape, and 1-tap "Send to Expense".
- **Persistent SQLite + JSON Storage** (`LedgerStorage.kt`).
- **CI/CD Pipeline** (`.github/workflows/build-apk.yml`): Dual-path PKCS12 signing with `gradle assembleRelease`.
