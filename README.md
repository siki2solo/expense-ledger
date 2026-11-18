# Expense Ledger (Android)

Package ID: `com.example.expenseledger`
Version: `1.6` (Code `7`)

## Features Included
- **Live Balance Dashboard**: Isolated `PAID (₹)`, `HOLD (₹)`, `NEED (₹)`, and Grand Commitment calculated strictly from active Expenses (with partial settlements credited live to `PAID`), plus Net Cashflow indicator when Income entries exist.
- **1-Click International Calendar & .ics Export (v1.5)**: Direct header & launcher shortcut calendar with configurable `Mon ISO | Sun | Sat` week start, ISO week numbers (`W01–W53`), 7 regional holiday packs (`Global / IN / US / UK / EU / AE / JP`), RFC 5545 `.ics` export, and unified Day Agenda (`+ Log on Date`, `Filter Feed`, `Pay Part`, recurring `Log Now`, transfers, IOU repayments, and goal targets).
- **Home Screen Glance Balance Widget (v1.5)**: Jetpack Glance widget (`BalanceWidget.kt`) showing live `PAID`, `HOLD`, `NEED`, `Net`, and direct `+ Expense` & `Calendar` deep links.
- **AES-256-GCM Encrypted Backups & Auto-Lock (v1.5)**: Cross-platform `.ebak` archive encryption (PBKDF2-HMAC-SHA256 100k rounds + AES-256-GCM) plus background lifecycle Auto-Lock timeout (`Off / 30s / 1m / 5m`).
- **Auto-Label Rules, 30-Day Cashflow Forecast, Split Bills / IOUs & Savings Goals (v1.5)**: Keyword rules in Entry/SMS/CSV, 30-day liquidity projection, friend IOU settlement crediting the chosen Wallet, and target Savings Goal jars inside `Insights`.
- **Partial Payments, Live Wallet Balances & Transfers, Biometrics, Dark Mode, Receipt Photo Picker & PDF Export (v1.4)**.
- **Wallets, Smart Paste SMS, Pinned/Due Dates, CSV Import/Export, Recycle Bin, Bulk Select, Templates & Calculator**.
- **Persistent SQLite + JSON Storage** (`LedgerStorage.kt`) & **GitHub Actions Signed APK CI/CD** (`.github/workflows/build-apk.yml`).
