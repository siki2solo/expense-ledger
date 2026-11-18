package com.example.expenseledger

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

class BalanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = LedgerDbHelper(context)
        val snapshot = db.loadSnapshot()
        val expenses = snapshot.expenses.filter { it.entryType == EntryType.EXPENSE }
        val paid = expenses.sumOf { LedgerMath.expensePaidPart(it) }
        val hold = expenses.filter { it.status == ExpenseStatus.HOLD }.sumOf { LedgerMath.expenseRemainingPart(it) }
        val need = expenses.filter { it.status == ExpenseStatus.NEED }.sumOf { LedgerMath.expenseRemainingPart(it) }
        val cashflow = LedgerMath.computeCashflowSummary(snapshot.expenses)
        val hide = snapshot.hideBalances

        provideContent {
            val ctx = LocalContext.current
            val openAppIntent = Intent(ctx, MainActivity::class.java).apply {
                action = "ACTION_WIDGET_HOME"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val addIntent = Intent(ctx, MainActivity::class.java).apply {
                action = "ACTION_WIDGET_NEW_EXPENSE"
                putExtra("shortcut_action", "new_expense")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val calIntent = Intent(ctx, MainActivity::class.java).apply {
                action = "ACTION_WIDGET_CALENDAR"
                putExtra("shortcut_action", "calendar")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val addNeedIntent = Intent(ctx, MainActivity::class.java).apply {
                action = "ACTION_WIDGET_NEW_NEED"
                putExtra("shortcut_action", "new_need")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val addPaidIntent = Intent(ctx, MainActivity::class.java).apply {
                action = "ACTION_WIDGET_NEW_PAID"
                putExtra("shortcut_action", "new_paid")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(ColorProvider(Color(0xFF0F172A)))
                        .cornerRadius(16.dp)
                        .padding(12.dp)
                        .clickable(actionStartActivity(openAppIntent)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Expense Ledger",
                            style = TextStyle(
                                color = ColorProvider(Color(0xFFFFFFFF)),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = GlanceModifier.defaultWeight()
                        )
                        Text(
                            text = "Net ₹${LedgerMath.formatDisplayAmt(cashflow.netCashflow, hide)}",
                            style = TextStyle(
                                color = ColorProvider(Color(0xFF34D399)),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        Column(
                            modifier = GlanceModifier
                                .defaultWeight()
                                .background(ColorProvider(Color(0xFF064E3B)))
                                .cornerRadius(8.dp)
                                .padding(6.dp)
                        ) {
                            Text("PAID", style = TextStyle(color = ColorProvider(Color(0xFF6EE7B7)), fontSize = 9.sp, fontWeight = FontWeight.Bold))
                            Text("₹${LedgerMath.formatDisplayAmt(paid, hide)}", style = TextStyle(color = ColorProvider(Color(0xFFFFFFFF)), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
                        }
                        Spacer(GlanceModifier.width(6.dp))
                        Column(
                            modifier = GlanceModifier
                                .defaultWeight()
                                .background(ColorProvider(Color(0xFF78350F)))
                                .cornerRadius(8.dp)
                                .padding(6.dp)
                        ) {
                            Text("HOLD", style = TextStyle(color = ColorProvider(Color(0xFFFCD34D)), fontSize = 9.sp, fontWeight = FontWeight.Bold))
                            Text("₹${LedgerMath.formatDisplayAmt(hold, hide)}", style = TextStyle(color = ColorProvider(Color(0xFFFFFFFF)), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
                        }
                        Spacer(GlanceModifier.width(6.dp))
                        Column(
                            modifier = GlanceModifier
                                .defaultWeight()
                                .background(ColorProvider(Color(0xFF7F1D1D)))
                                .cornerRadius(8.dp)
                                .padding(6.dp)
                        ) {
                            Text("NEED", style = TextStyle(color = ColorProvider(Color(0xFFFCA5A5)), fontSize = 9.sp, fontWeight = FontWeight.Bold))
                            Text("₹${LedgerMath.formatDisplayAmt(need, hide)}", style = TextStyle(color = ColorProvider(Color(0xFFFFFFFF)), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
                        }
                    }
                    Spacer(GlanceModifier.height(8.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        Text(
                            text = "+ Need",
                            style = TextStyle(color = ColorProvider(Color(0xFFFFFFFF)), fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            modifier = GlanceModifier
                                .defaultWeight()
                                .background(ColorProvider(Color(0xFF1D4ED8)))
                                .cornerRadius(8.dp)
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .clickable(actionStartActivity(addNeedIntent))
                        )
                        Spacer(GlanceModifier.width(6.dp))
                        Text(
                            text = "+ Paid",
                            style = TextStyle(color = ColorProvider(Color(0xFFFFFFFF)), fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            modifier = GlanceModifier
                                .defaultWeight()
                                .background(ColorProvider(Color(0xFF047857)))
                                .cornerRadius(8.dp)
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .clickable(actionStartActivity(addPaidIntent))
                        )
                        Spacer(GlanceModifier.width(6.dp))
                        Text(
                            text = "Calendar",
                            style = TextStyle(color = ColorProvider(Color(0xFFE2E8F0)), fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            modifier = GlanceModifier
                                .defaultWeight()
                                .background(ColorProvider(Color(0xFF1E293B)))
                                .cornerRadius(8.dp)
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .clickable(actionStartActivity(calIntent))
                        )
                    }
                }
            }
        }
    }
}

class BalanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BalanceWidget()
}
