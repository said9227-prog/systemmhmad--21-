package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.example.data.model.ExchangeRate
import com.example.data.model.Invoice
import com.example.data.model.InvoiceItem
import kotlin.math.abs

object WhatsAppHelper {

    /**
     * Builds a formatted Arabic WhatsApp message with Markdown emphasis and emojis,
     * supporting multi-currency (Saudi SAR, Yemeni YER, and US Dollar USD) for items and totals.
     */
    fun formatWhatsAppInvoiceMessage(
        invoice: Invoice,
        items: List<InvoiceItem>,
        storeName: String,
        storePhone: String = "",
        storeAddress: String = "",
        currency: String = "الريال اليمني",
        clientBalance: Double? = null,
        clientCurrency: String? = null,
        showVatAndSubtotal: Boolean = false,
        rates: List<ExchangeRate> = emptyList()
    ): String {
        val invoiceCurr = MonetaryUtils.normalizeCurrency(currency.ifBlank { invoice.currency })
        val sarToYer = MonetaryUtils.convert(1.0, MonetaryUtils.CURRENCY_SAR, MonetaryUtils.CURRENCY_YER, rates).convertedAmount
        val usdToYer = MonetaryUtils.convert(1.0, MonetaryUtils.CURRENCY_USD, MonetaryUtils.CURRENCY_YER, rates).convertedAmount

        val sb = StringBuilder()
        sb.append("🏥 *").append(storeName.ifBlank { "الحكيمي للأدوية والمستلزمات الطبية" }).append("*\n")
        if (storePhone.isNotBlank()) {
            sb.append("📞 هاتف: ").append(storePhone).append("\n")
        }
        if (storeAddress.isNotBlank()) {
            sb.append("📍 العنوان: ").append(storeAddress).append("\n")
        }
        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("🧾 *تفاصيل الفاتورة*\n")
        sb.append("• *رقم الفاتورة:* #").append(invoice.invoiceNumber).append("\n")
        sb.append("• *العميل:* ").append(invoice.clientName).append("\n")
        sb.append("• *التاريخ:* ").append(DateTimeUtils.formatDateTime12h(invoice.date)).append("\n")
        sb.append("• *النوع:* ").append(if (invoice.isQuickInvoice) "فاتورة سريعة ⚡" else "فاتورة مفصلة بالأصناف 📦").append("\n")
        sb.append("• *الحالة:* ").append(if (invoice.isDraft) "مسودة" else "نهائية مكتملة ✅").append("\n")
        sb.append("• *صرف الصرف المعتمد:* 1 ر.س = ").append(FormatUtils.formatAmount(sarToYer)).append(" ر.ي | 1 $ = ").append(FormatUtils.formatAmount(usdToYer)).append(" ر.ي\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━\n")

        var grandTotalSar = 0.0
        var grandTotalYer = 0.0
        var grandTotalUsd = 0.0

        if (invoice.isQuickInvoice) {
            val desc = invoice.description?.ifBlank { "شراء مستلزمات طبية وأدوية" } ?: "شراء مستلزمات طبية وأدوية"
            val totalSar = MonetaryUtils.convert(invoice.totalAmount, invoiceCurr, MonetaryUtils.CURRENCY_SAR, rates).convertedAmount
            val totalYer = MonetaryUtils.convert(invoice.totalAmount, invoiceCurr, MonetaryUtils.CURRENCY_YER, rates).convertedAmount
            val totalUsd = MonetaryUtils.convert(invoice.totalAmount, invoiceCurr, MonetaryUtils.CURRENCY_USD, rates).convertedAmount
            grandTotalSar = totalSar
            grandTotalYer = totalYer
            grandTotalUsd = totalUsd
            sb.append("📝 *البيان / الوصف:*\n").append(desc).append("\n")
            sb.append("   • الإجمالي: ${FormatUtils.formatAmount(totalSar)} ر.س  |  ${FormatUtils.formatAmount(totalYer)} ر.ي  |  $${FormatUtils.formatAmount(totalUsd)}\n")
            sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        } else if (items.isNotEmpty()) {
            sb.append("📦 *الأصناف وتفاصيل الصرف (سعودي / يمني / دولار):*\n")
            items.forEachIndexed { index, item ->
                val rawCurr = item.currency.ifBlank { invoiceCurr }
                val itemCurr = MonetaryUtils.normalizeCurrency(rawCurr)
                val qty = item.quantity
                val unitPrice = item.unitPrice
                val itemTotalPrice = if (item.totalPrice > 0.001) item.totalPrice else unitPrice * qty

                val pSar = MonetaryUtils.convert(unitPrice, itemCurr, MonetaryUtils.CURRENCY_SAR, rates).convertedAmount
                val pYer = MonetaryUtils.convert(unitPrice, itemCurr, MonetaryUtils.CURRENCY_YER, rates).convertedAmount
                val pUsd = MonetaryUtils.convert(unitPrice, itemCurr, MonetaryUtils.CURRENCY_USD, rates).convertedAmount

                val totSar = MonetaryUtils.convert(itemTotalPrice, itemCurr, MonetaryUtils.CURRENCY_SAR, rates).convertedAmount
                val totYer = MonetaryUtils.convert(itemTotalPrice, itemCurr, MonetaryUtils.CURRENCY_YER, rates).convertedAmount
                val totUsd = MonetaryUtils.convert(itemTotalPrice, itemCurr, MonetaryUtils.CURRENCY_USD, rates).convertedAmount

                grandTotalSar += totSar
                grandTotalYer += totYer
                grandTotalUsd += totUsd

                sb.append("${index + 1}. *${item.itemName}* (الكمية: $qty)\n")
                sb.append("   ▫️ الوحدة: ${FormatUtils.formatAmount(pSar)} ر.س  |  ${FormatUtils.formatAmount(pYer)} ر.ي  |  $${FormatUtils.formatAmount(pUsd)}\n")
                sb.append("   ▫️ الإجمالي: *${FormatUtils.formatAmount(totSar)} ر.س*  |  *${FormatUtils.formatAmount(totYer)} ر.ي*  |  *$${FormatUtils.formatAmount(totUsd)}*\n")
            }
            sb.append("━━━━━━━━━━━━━━━━━━━━\n")
            if (showVatAndSubtotal) {
                sb.append("💵 *المجموع الفرعي:* ")
                    .append(FormatUtils.formatAmount(grandTotalSar)).append(" ر.س  |  ")
                    .append(FormatUtils.formatAmount(grandTotalYer)).append(" ر.ي  |  $")
                    .append(FormatUtils.formatAmount(grandTotalUsd)).append("\n")
            }
            if (invoice.discount > 0) {
                val discSar = MonetaryUtils.convert(invoice.discount, invoiceCurr, MonetaryUtils.CURRENCY_SAR, rates).convertedAmount
                val discYer = MonetaryUtils.convert(invoice.discount, invoiceCurr, MonetaryUtils.CURRENCY_YER, rates).convertedAmount
                val discUsd = MonetaryUtils.convert(invoice.discount, invoiceCurr, MonetaryUtils.CURRENCY_USD, rates).convertedAmount
                sb.append("🏷️ *الخصم الممنوح:* -")
                    .append(FormatUtils.formatAmount(discSar)).append(" ر.س  |  -")
                    .append(FormatUtils.formatAmount(discYer)).append(" ر.ي  |  -$")
                    .append(FormatUtils.formatAmount(discUsd)).append("\n")
            }
        }

        val totalSar = MonetaryUtils.convert(invoice.totalAmount, invoiceCurr, MonetaryUtils.CURRENCY_SAR, rates).convertedAmount
        val totalYer = MonetaryUtils.convert(invoice.totalAmount, invoiceCurr, MonetaryUtils.CURRENCY_YER, rates).convertedAmount
        val totalUsd = MonetaryUtils.convert(invoice.totalAmount, invoiceCurr, MonetaryUtils.CURRENCY_USD, rates).convertedAmount

        val paidSar = MonetaryUtils.convert(invoice.paidAmount, invoiceCurr, MonetaryUtils.CURRENCY_SAR, rates).convertedAmount
        val paidYer = MonetaryUtils.convert(invoice.paidAmount, invoiceCurr, MonetaryUtils.CURRENCY_YER, rates).convertedAmount
        val paidUsd = MonetaryUtils.convert(invoice.paidAmount, invoiceCurr, MonetaryUtils.CURRENCY_USD, rates).convertedAmount

        val remSar = (totalSar - paidSar).coerceAtLeast(0.0)
        val remYer = (totalYer - paidYer).coerceAtLeast(0.0)
        val remUsd = (totalUsd - paidUsd).coerceAtLeast(0.0)

        sb.append("💰 *المبلغ الإجمالي للفاتورة:*\n")
        sb.append("   • *${FormatUtils.formatAmount(totalSar)} ر.س* (ريال سعودي)\n")
        sb.append("   • *${FormatUtils.formatAmount(totalYer)} ر.ي* (ريال يمني)\n")
        sb.append("   • *$${FormatUtils.formatAmount(totalUsd)}* (دولار أمريكي)\n")
        sb.append("🟢 *المدفوع:* ${FormatUtils.formatAmount(paidSar)} ر.س  |  ${FormatUtils.formatAmount(paidYer)} ر.ي  |  $${FormatUtils.formatAmount(paidUsd)}\n")
        sb.append("🔴 *المتبقي:* ${FormatUtils.formatAmount(remSar)} ر.س  |  ${FormatUtils.formatAmount(remYer)} ر.ي  |  $${FormatUtils.formatAmount(remUsd)}\n")

        if (clientBalance != null) {
            sb.append("━━━━━━━━━━━━━━━━━━━━\n")
            val cCurr = MonetaryUtils.normalizeCurrency(clientCurrency ?: invoiceCurr)
            val balSar = MonetaryUtils.convert(abs(clientBalance), cCurr, MonetaryUtils.CURRENCY_SAR, rates).convertedAmount
            val balYer = MonetaryUtils.convert(abs(clientBalance), cCurr, MonetaryUtils.CURRENCY_YER, rates).convertedAmount
            val balUsd = MonetaryUtils.convert(abs(clientBalance), cCurr, MonetaryUtils.CURRENCY_USD, rates).convertedAmount

            if (clientBalance > 0.001) {
                sb.append("⚠️ *إجمالي الرصيد المتبقي المستحق على العميل:*\n")
                sb.append("   • ${FormatUtils.formatAmount(balSar)} ر.س  |  ${FormatUtils.formatAmount(balYer)} ر.ي  |  $${FormatUtils.formatAmount(balUsd)}\n")
            } else if (abs(clientBalance) <= 0.001) {
                sb.append("✅ *حساب العميل خالص بالكامل بدون مديونية*\n")
            } else {
                sb.append("🟢 *رصيد العميل الدائن المتبقي لصالحه:*\n")
                sb.append("   • ${FormatUtils.formatAmount(balSar)} ر.س  |  ${FormatUtils.formatAmount(balYer)} ر.ي  |  $${FormatUtils.formatAmount(balUsd)}\n")
            }
        }

        if (!invoice.notes.isNullOrBlank()) {
            sb.append("━━━━━━━━━━━━━━━━━━━━\n")
            sb.append("📌 *ملاحظات:* ").append(invoice.notes).append("\n")
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("✨ *شكراً لتعاملكم معنا ونرحب بكم دائماً!* ✨")
        return sb.toString()
    }

    /**
     * Cleans up telephone numbers and opens WhatsApp directly to chat with the recipient.
     */
    fun sendWhatsAppMessage(context: Context, phone: String?, messageText: String) {
        try {
            var cleanPhone = phone?.filter { it.isDigit() || it == '+' } ?: ""
            if (cleanPhone.startsWith("+")) {
                cleanPhone = cleanPhone.substring(1)
            } else if (cleanPhone.startsWith("00")) {
                cleanPhone = cleanPhone.substring(2)
            }

            // If Yemeni number without international code (e.g. 7xxxxxxxx or 07xxxxxxxx)
            if (cleanPhone.startsWith("07") && cleanPhone.length == 10) {
                cleanPhone = "967" + cleanPhone.substring(1)
            } else if (cleanPhone.startsWith("7") && cleanPhone.length == 9) {
                cleanPhone = "967$cleanPhone"
            } else if (cleanPhone.startsWith("05") && cleanPhone.length == 10) { // Saudi local
                cleanPhone = "966" + cleanPhone.substring(1)
            }

            val encodedMsg = Uri.encode(messageText)
            val uri = if (cleanPhone.isNotBlank()) {
                Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=$encodedMsg")
            } else {
                Uri.parse("https://api.whatsapp.com/send?text=$encodedMsg")
            }

            // Attempt standard WhatsApp package first
            val whatsappIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                context.startActivity(whatsappIntent)
            } catch (e: Exception) {
                // Try WhatsApp Business package
                try {
                    val businessIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.whatsapp.w4b")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(businessIntent)
                } catch (e2: Exception) {
                    // Fallback to browser or generic handler
                    try {
                        val genericIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(genericIntent)
                    } catch (e3: Exception) {
                        // Ultimate fallback: ACTION_SEND intent chooser
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, messageText)
                            type = "text/plain"
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "إرسال الفاتورة عبر:"))
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "تعذر فتح تطبيق الواتس آب: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}
