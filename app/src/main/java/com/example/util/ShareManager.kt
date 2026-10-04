package com.example.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.data.model.Client
import com.example.data.model.ExchangeRate
import com.example.data.model.Invoice
import com.example.data.model.InvoiceItem
import com.example.data.model.Item
import com.example.data.model.Payment
import com.example.data.model.StoreSettings
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Unified, professional Android sharing manager.
 * Uses standard Android Share Sheet (Intent.ACTION_SEND + Intent.createChooser),
 * FileProvider for content:// URIs with FLAG_GRANT_READ_URI_PERMISSION,
 * proper MIME types, robust error handling in Arabic, and zero hardcoded direct app bindings.
 */
object ShareManager {

    private fun getFileProviderAuthority(context: Context): String {
        return "${context.packageName}.fileprovider"
    }

    /**
     * Shares any file safely via Android's official system Share Sheet.
     */
    fun shareFile(
        context: Context,
        file: File?,
        mimeType: String = "application/pdf",
        chooserTitle: String = "📤 مشاركة الملف",
        extraText: String? = null,
        subject: String? = null
    ): Boolean {
        if (file == null || !file.exists() || file.length() <= 0L) {
            Toast.makeText(context, "تعذر تجهيز الملف للمشاركة", Toast.LENGTH_SHORT).show()
            return false
        }

        return try {
            val authority = getFileProviderAuthority(context)
            val uri: Uri = FileProvider.getUriForFile(context, authority, file)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                if (!extraText.isNullOrBlank()) {
                    putExtra(Intent.EXTRA_TEXT, extraText)
                }
                if (!subject.isNullOrBlank()) {
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, chooserTitle).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(chooser)
            true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "لا توجد تطبيقات متاحة للمشاركة", Toast.LENGTH_SHORT).show()
            false
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "تعذر تجهيز الملف للمشاركة", Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * Shares a PDF file using MIME type "application/pdf".
     */
    fun sharePdf(
        context: Context,
        file: File?,
        chooserTitle: String = "📤 مشاركة مستند PDF",
        extraText: String? = null,
        subject: String? = null
    ): Boolean {
        return shareFile(
            context = context,
            file = file,
            mimeType = "application/pdf",
            chooserTitle = chooserTitle,
            extraText = extraText,
            subject = subject
        )
    }

    /**
     * Shares text content (messages, summaries, receipts) via official Share Sheet.
     */
    fun shareText(
        context: Context,
        text: String,
        chooserTitle: String = "📤 مشاركة",
        subject: String? = null
    ): Boolean {
        if (text.isBlank()) {
            Toast.makeText(context, "لا يوجد نص للمشاركة", Toast.LENGTH_SHORT).show()
            return false
        }

        return try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                if (!subject.isNullOrBlank()) {
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                }
            }

            val chooser = Intent.createChooser(shareIntent, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(chooser)
            true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "لا توجد تطبيقات متاحة للمشاركة", Toast.LENGTH_SHORT).show()
            false
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "تعذر مشاركة النص", Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * Shares an Invoice either as generated PDF (primary) or with accompanied detailed text.
     */
    fun shareInvoice(
        context: Context,
        invoice: Invoice,
        items: List<InvoiceItem>,
        settings: StoreSettings,
        client: Client?,
        shareAsPdf: Boolean = true,
        exchangeRates: List<ExchangeRate> = emptyList()
    ): Boolean {
        val invoiceNumber = invoice.invoiceNumber
        val storeName = settings.storeName.ifBlank { "المتجر" }
        val currency = invoice.currency.ifBlank { settings.currency }

        val db = com.example.data.database.AppDatabase.getDatabase(context)
        val rates = if (exchangeRates.isNotEmpty()) exchangeRates else runBlocking {
            try {
                db.exchangeRateDao().getAllRates()
            } catch (e: Exception) {
                emptyList()
            }
        }

        val itemsMap = runBlocking {
            try {
                val ids = items.mapNotNull { it.itemId }.distinct()
                ids.mapNotNull { id -> db.itemDao().getItemById(id) }.associateBy { it.id }
            } catch (e: Exception) {
                emptyMap()
            }
        }

        val textSummary = buildInvoiceSummaryText(
            invoice = invoice,
            items = items,
            storeName = storeName,
            storePhone = settings.storePhone,
            storeAddress = settings.storeAddress,
            currency = currency,
            client = client,
            showVatAndSubtotal = settings.showVatAndSubtotal,
            rates = rates,
            itemsMap = itemsMap
        )

        if (shareAsPdf) {
            val pdfPath = exportInvoiceToPdf(
                context = context,
                invoice = invoice,
                items = items,
                storeName = storeName,
                client = client,
                storeSettings = settings,
                exchangeRates = rates
            )

            if (pdfPath != null) {
                val pdfFile = File(pdfPath)
                if (pdfFile.exists() && pdfFile.length() > 0L) {
                    return sharePdf(
                        context = context,
                        file = pdfFile,
                        chooserTitle = "📤 مشاركة فاتورة رقم #$invoiceNumber",
                        extraText = textSummary,
                        subject = "فاتورة مبيعات رقم $invoiceNumber - $storeName"
                    )
                }
            }
        }

        // Fallback or text mode
        return shareText(
            context = context,
            text = textSummary,
            chooserTitle = "📤 مشاركة تفاصيل الفاتورة #$invoiceNumber",
            subject = "فاتورة رقم $invoiceNumber - $storeName"
        )
    }

    /**
     * Builds comprehensive formatted Arabic text for an invoice with clear dual currency details.
     */
    fun buildInvoiceSummaryText(
        invoice: Invoice,
        items: List<InvoiceItem>,
        storeName: String,
        storePhone: String,
        storeAddress: String,
        currency: String,
        client: Client?,
        showVatAndSubtotal: Boolean = false,
        rates: List<ExchangeRate> = emptyList(),
        itemsMap: Map<Int, Item> = emptyMap()
    ): String {
        val invoiceCurr = MonetaryUtils.normalizeCurrency(currency)
        val sarToYer = MonetaryUtils.convert(1.0, MonetaryUtils.CURRENCY_SAR, MonetaryUtils.CURRENCY_YER, rates).convertedAmount
        val usdToYer = MonetaryUtils.convert(1.0, MonetaryUtils.CURRENCY_USD, MonetaryUtils.CURRENCY_YER, rates).convertedAmount

        val sb = StringBuilder()
        sb.append("🧾 *فاتورة مبيعات - ").append(storeName).append("*\n")
        if (storePhone.isNotBlank()) sb.append("📞 هاتف: ").append(storePhone).append("\n")
        if (storeAddress.isNotBlank()) sb.append("📍 العنوان: ").append(storeAddress).append("\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("• رقم الفاتورة: #").append(invoice.invoiceNumber).append("\n")
        sb.append("• العميل: ").append(invoice.clientName).append("\n")
        sb.append("• التاريخ: ").append(DateTimeUtils.formatDateTime12h(invoice.date)).append("\n")
        sb.append("• نوع الفاتورة: ").append(if (invoice.isQuickInvoice) "فاتورة سريعة" else "فاتورة مفصلة").append("\n")
        sb.append("• صرف الصرف المعتمد: 1 ر.س = ").append(FormatUtils.formatAmount(sarToYer)).append(" ر.ي | 1 $ = ").append(FormatUtils.formatAmount(usdToYer)).append(" ر.ي\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━\n")

        var grandTotalSar = 0.0
        var grandTotalYer = 0.0
        var grandTotalUsd = 0.0

        if (invoice.isQuickInvoice) {
            val desc = invoice.description?.ifBlank { "مبيعات عامة" } ?: "مبيعات عامة"
            val totalSar = MonetaryUtils.convert(invoice.totalAmount, invoiceCurr, MonetaryUtils.CURRENCY_SAR, rates).convertedAmount
            val totalYer = MonetaryUtils.convert(invoice.totalAmount, invoiceCurr, MonetaryUtils.CURRENCY_YER, rates).convertedAmount
            val totalUsd = MonetaryUtils.convert(invoice.totalAmount, invoiceCurr, MonetaryUtils.CURRENCY_USD, rates).convertedAmount
            grandTotalSar = totalSar
            grandTotalYer = totalYer
            grandTotalUsd = totalUsd
            sb.append("📝 البيان: ").append(desc).append("\n")
            sb.append("   • الإجمالي: ${FormatUtils.formatAmount(totalSar)} ر.س  |  ${FormatUtils.formatAmount(totalYer)} ر.ي  |  $${FormatUtils.formatAmount(totalUsd)}\n")
        } else if (items.isNotEmpty()) {
            sb.append("📦 الأصناف المبيعة بالتفصيل:\n")
            items.forEachIndexed { index, item ->
                val rawCurr = item.currency.ifBlank { currency }
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

                val itemRateNote = when (itemCurr) {
                    MonetaryUtils.CURRENCY_SAR -> "1 ر.س = ${FormatUtils.formatAmount(sarToYer)} ر.ي"
                    MonetaryUtils.CURRENCY_USD -> "1 $ = ${FormatUtils.formatAmount(usdToYer)} ر.ي"
                    else -> "1 ر.س = ${FormatUtils.formatAmount(sarToYer)} ر.ي | 1 $ = ${FormatUtils.formatAmount(usdToYer)} ر.ي"
                }

                sb.append("${index + 1}. *${item.itemName}*\n")
                sb.append("   • الكمية: $qty\n")
                sb.append("   • السعر للوحدة: ${FormatUtils.formatAmount(pSar)} ر.س  |  ${FormatUtils.formatAmount(pYer)} ر.ي  |  $${FormatUtils.formatAmount(pUsd)}\n")
                sb.append("   • الإجمالي للصنف: ${FormatUtils.formatAmount(totSar)} ر.س  |  ${FormatUtils.formatAmount(totYer)} ر.ي  |  $${FormatUtils.formatAmount(totUsd)}\n")
                sb.append("   • (صرف الصنف: $itemRateNote)\n")
            }
            sb.append("────────────────────\n")
            val subtotalSar = grandTotalSar
            val subtotalYer = grandTotalYer
            val subtotalUsd = grandTotalUsd
            if (showVatAndSubtotal) {
                sb.append("💵 المجموع الفرعي: ")
                    .append(FormatUtils.formatAmount(subtotalSar)).append(" ر.س  |  ")
                    .append(FormatUtils.formatAmount(subtotalYer)).append(" ر.ي  |  $")
                    .append(FormatUtils.formatAmount(subtotalUsd)).append("\n")
            }
            if (invoice.discount > 0) {
                val discSar = MonetaryUtils.convert(invoice.discount, invoiceCurr, MonetaryUtils.CURRENCY_SAR, rates).convertedAmount
                val discYer = MonetaryUtils.convert(invoice.discount, invoiceCurr, MonetaryUtils.CURRENCY_YER, rates).convertedAmount
                val discUsd = MonetaryUtils.convert(invoice.discount, invoiceCurr, MonetaryUtils.CURRENCY_USD, rates).convertedAmount
                sb.append("🏷️ الخصم الممنوح: -")
                    .append(FormatUtils.formatAmount(discSar)).append(" ر.س  |  -")
                    .append(FormatUtils.formatAmount(discYer)).append(" ر.ي  |  -$")
                    .append(FormatUtils.formatAmount(discUsd)).append("\n")
            }
        }

        val invoiceTotalSar = MonetaryUtils.convert(invoice.totalAmount, invoiceCurr, MonetaryUtils.CURRENCY_SAR, rates).convertedAmount
        val invoiceTotalYer = MonetaryUtils.convert(invoice.totalAmount, invoiceCurr, MonetaryUtils.CURRENCY_YER, rates).convertedAmount
        val invoiceTotalUsd = MonetaryUtils.convert(invoice.totalAmount, invoiceCurr, MonetaryUtils.CURRENCY_USD, rates).convertedAmount

        val paidSar = MonetaryUtils.convert(invoice.paidAmount, invoiceCurr, MonetaryUtils.CURRENCY_SAR, rates).convertedAmount
        val paidYer = MonetaryUtils.convert(invoice.paidAmount, invoiceCurr, MonetaryUtils.CURRENCY_YER, rates).convertedAmount
        val paidUsd = MonetaryUtils.convert(invoice.paidAmount, invoiceCurr, MonetaryUtils.CURRENCY_USD, rates).convertedAmount

        val remainingSar = (invoiceTotalSar - paidSar).coerceAtLeast(0.0)
        val remainingYer = (invoiceTotalYer - paidYer).coerceAtLeast(0.0)
        val remainingUsd = (invoiceTotalUsd - paidUsd).coerceAtLeast(0.0)

        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("💰 *الإجمالي النهائي للفاتورة:*\n")
        sb.append("   • ").append(FormatUtils.formatAmount(invoiceTotalSar)).append(" ر.س (ريال سعودي)\n")
        sb.append("   • ").append(FormatUtils.formatAmount(invoiceTotalYer)).append(" ر.ي (ريال يمني)\n")
        sb.append("   • $").append(FormatUtils.formatAmount(invoiceTotalUsd)).append(" (دولار أمريكي)\n")
        sb.append("🟢 المدفوع: ").append(FormatUtils.formatAmount(paidSar)).append(" ر.س  |  ").append(FormatUtils.formatAmount(paidYer)).append(" ر.ي  |  $").append(FormatUtils.formatAmount(paidUsd)).append("\n")
        sb.append("🔴 المتبقي: ").append(FormatUtils.formatAmount(remainingSar)).append(" ر.س  |  ").append(FormatUtils.formatAmount(remainingYer)).append(" ر.ي  |  $").append(FormatUtils.formatAmount(remainingUsd)).append("\n")

        if (client != null) {
            sb.append("━━━━━━━━━━━━━━━━━━━━\n")
            val clientCurr = MonetaryUtils.normalizeCurrency(client.currency.ifBlank { currency })
            val clientBal = client.balance
            val balSar = MonetaryUtils.convert(abs(clientBal), clientCurr, MonetaryUtils.CURRENCY_SAR, rates).convertedAmount
            val balYer = MonetaryUtils.convert(abs(clientBal), clientCurr, MonetaryUtils.CURRENCY_YER, rates).convertedAmount
            val balUsd = MonetaryUtils.convert(abs(clientBal), clientCurr, MonetaryUtils.CURRENCY_USD, rates).convertedAmount

            if (clientBal > 0) {
                sb.append("⚠️ إجمالي رصيد المديونية المستحق: ")
                    .append(FormatUtils.formatAmount(balSar)).append(" ر.س  |  ")
                    .append(FormatUtils.formatAmount(balYer)).append(" ر.ي  |  $")
                    .append(FormatUtils.formatAmount(balUsd)).append(" (مدين)\n")
            } else if (clientBal < 0) {
                sb.append("🟢 رصيدكم الدائن: ")
                    .append(FormatUtils.formatAmount(balSar)).append(" ر.س  |  ")
                    .append(FormatUtils.formatAmount(balYer)).append(" ر.ي  |  $")
                    .append(FormatUtils.formatAmount(balUsd)).append(" (دائن)\n")
            } else {
                sb.append("✅ رصيد الحساب خالص بالكامل\n")
            }
        }

        if (!invoice.notes.isNullOrBlank()) {
            sb.append("📌 ملاحظات: ").append(invoice.notes).append("\n")
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("✨ شكراً لتعاملكم معنا!")
        return sb.toString()
    }

    /**
     * Shares customer profile, information, and financial status.
     */
    fun shareCustomerProfile(
        context: Context,
        client: Client,
        currency: String = "",
        loyaltyProfile: ClientLoyaltyProfile? = null
    ): Boolean {
        val customerCode = if (client.customerId.isNotBlank()) client.customerId else "#${client.id}"
        val bal = client.balance
        val balStatus = when {
            bal > 0 -> "مدين (مستحق عليه)"
            bal < 0 -> "دائن (له رصيد)"
            else -> "متعادل (خالص)"
        }

        val sb = StringBuilder()
        sb.append("👤 *بطاقة بيانات وحساب العميل*\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("• الاسم: ").append(client.name).append("\n")
        sb.append("• رقم العميل / الحساب: ").append(customerCode).append("\n")
        if (client.phone.isNotBlank()) sb.append("• الهاتف: ").append(client.phone).append("\n")
        if (client.altPhone.isNotBlank()) sb.append("• هاتف إضافي: ").append(client.altPhone).append("\n")
        if (client.companyName.isNotBlank()) sb.append("• المؤسسة: ").append(client.companyName).append("\n")
        if (client.city.isNotBlank()) sb.append("• المدينة: ").append(client.city).append("\n")
        if (client.address.isNotBlank()) sb.append("• العنوان: ").append(client.address).append("\n")
        if (client.email.isNotBlank()) sb.append("• البريد: ").append(client.email).append("\n")
        sb.append("• نوع التعامل: ").append(client.dealType.ifBlank { "نقدي" }).append("\n")
        val currDisplay = currency.ifBlank { "ريال" }
        sb.append("• الرصيد الحالي: ").append(FormatUtils.formatWithCurrency(abs(bal), currDisplay)).append(" (").append(balStatus).append(")\n")

        if (client.creditLimit > 0) {
            sb.append("• حد الائتمان: ").append(FormatUtils.formatWithCurrency(client.creditLimit, currDisplay)).append("\n")
        }

        if (loyaltyProfile != null) {
            sb.append("• تقييم الالتزام بالسداد: ").append((loyaltyProfile.paymentCommitmentRate * 100).toInt()).append("%\n")
            sb.append("• شارة العميل: ").append(loyaltyProfile.primaryBadge.iconEmoji).append(" ").append(loyaltyProfile.primaryBadge.title).append("\n")
        }

        if (client.notes.isNotBlank()) {
            sb.append("• ملاحظات: ").append(client.notes).append("\n")
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        sb.append("📅 تم التصدير بتاريخ: ").append(dateFmt.format(Date()))

        return shareText(
            context = context,
            text = sb.toString(),
            chooserTitle = "📤 مشاركة بيانات العميل: ${client.name}",
            subject = "بيانات العميل - ${client.name}"
        )
    }

    /**
     * Overload for sharing customer profile with detailed statistics from customer details screen.
     */
    fun shareCustomerProfile(
        context: Context,
        client: Client,
        balance: Double,
        invoiceCount: Int,
        totalSales: Double,
        totalPayments: Double,
        settings: StoreSettings
    ): Boolean {
        val customerCode = if (client.customerId.isNotBlank()) client.customerId else "#${client.id}"
        val currency = settings.currency.ifBlank { "ريال" }
        val balStatus = when {
            balance > 0 -> "مدين (مستحق عليه)"
            balance < 0 -> "دائن (له رصيد)"
            else -> "متعادل (خالص)"
        }

        val sb = StringBuilder()
        sb.append("👤 *بطاقة بيانات وملف العميل*\n")
        sb.append("🏢 *").append(settings.storeName.ifBlank { "المتجر" }).append("*\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("• الاسم: ").append(client.name).append("\n")
        sb.append("• رقم العميل: ").append(customerCode).append("\n")
        if (client.phone.isNotBlank()) sb.append("• الهاتف: ").append(client.phone).append("\n")
        if (client.altPhone.isNotBlank()) sb.append("• هاتف إضافي: ").append(client.altPhone).append("\n")
        if (client.companyName.isNotBlank()) sb.append("• المؤسسة: ").append(client.companyName).append("\n")
        if (client.city.isNotBlank()) sb.append("• المدينة: ").append(client.city).append("\n")
        if (client.address.isNotBlank()) sb.append("• العنوان: ").append(client.address).append("\n")
        if (client.taxNumber.isNotBlank()) sb.append("• الرقم الضريبي: ").append(client.taxNumber).append("\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("📊 *المؤشرات المالية للعميل:*\n")
        sb.append("• الرصيد الحالي: *").append(FormatUtils.formatWithCurrency(abs(balance), currency)).append("* (").append(balStatus).append(")\n")
        sb.append("• إجمالي المبيعات/الفواتير: ").append(FormatUtils.formatWithCurrency(totalSales, currency)).append(" (").append(invoiceCount).append(" فاتورة)\n")
        sb.append("• إجمالي المدفوعات المسددة: ").append(FormatUtils.formatWithCurrency(totalPayments, currency)).append("\n")
        if (client.creditLimit > 0) {
            sb.append("• الحد الائتماني: ").append(FormatUtils.formatWithCurrency(client.creditLimit, currency)).append("\n")
        }
        if (client.notes.isNotBlank()) {
            sb.append("• ملاحظات: ").append(client.notes).append("\n")
        }
        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        sb.append("📅 التاريخ: ").append(dateFmt.format(Date()))

        return shareText(
            context = context,
            text = sb.toString(),
            chooserTitle = "📤 مشاركة بيانات العميل: ${client.name}",
            subject = "بيانات العميل - ${client.name}"
        )
    }

    /**
     * Shares payment voucher receipt.
     */
    fun sharePaymentReceipt(
        context: Context,
        payment: Payment,
        client: Client?,
        invoice: Invoice?,
        settings: StoreSettings
    ): Boolean {
        val currency = payment.currency.ifBlank { settings.currency }
        val dateFmt = SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.getDefault())
        val dateStr = dateFmt.format(Date(payment.date))
        val storeName = settings.storeName.ifBlank { "المتجر" }

        val sb = StringBuilder()
        sb.append("💵 *سند قبض / استلام دفعة*\n")
        sb.append("🏢 *").append(storeName).append("*\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        if (!payment.voucherNumber.isNullOrBlank()) {
            sb.append("• رقم السند: ").append(payment.voucherNumber).append("\n")
        }
        sb.append("• التاريخ والوقت: ").append(dateStr).append("\n")
        if (client != null) {
            sb.append("• استلمنا من السيد: ").append(client.name).append("\n")
            if (client.phone.isNotBlank()) sb.append("• هاتف العميل: ").append(client.phone).append("\n")
        }
        sb.append("• مبلغ وقدره: *").append(FormatUtils.formatAmount(payment.amount)).append(" ").append(currency).append("*\n")
        sb.append("• طريقة الدفع: ").append(payment.paymentMethod).append("\n")

        if (!payment.collectorName.isNullOrBlank()) {
            sb.append("• المُحصل: ").append(payment.collectorName).append("\n")
        }
        if (!payment.transferNumber.isNullOrBlank()) {
            sb.append("• رقم الحوالة / المرجع: ").append(payment.transferNumber).append("\n")
        }
        if (invoice != null) {
            sb.append("• دفعة عن الفاتورة رقم: #").append(invoice.invoiceNumber).append("\n")
        }
        if (!payment.notes.isNullOrBlank()) {
            sb.append("• البيان / الملاحظات: ").append(payment.notes).append("\n")
        }

        if (client != null) {
            sb.append("━━━━━━━━━━━━━━━━━━━━\n")
            val bal = client.balance
            if (bal > 0) {
                sb.append("⚠️ الرصيد المتبقي المستحق: ").append(FormatUtils.formatAmount(bal)).append(" ").append(currency).append("\n")
            } else if (bal < 0) {
                sb.append("🟢 رصيدكم الدائن: ").append(FormatUtils.formatAmount(-bal)).append(" ").append(currency).append("\n")
            } else {
                sb.append("✅ الحساب خالص بالكامل\n")
            }
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("✨ شكراً لكم - سند إلكتروني معتمد")

        return shareText(
            context = context,
            text = sb.toString(),
            chooserTitle = "📤 مشاركة سند القبض",
            subject = "سند قبض - ${client?.name ?: "سداد"}"
        )
    }

    /**
     * Builds clean, concise Arabic text summary for product return (Sales or Purchases).
     */
    fun buildReturnSummaryText(
        productReturn: com.example.data.model.ProductReturn,
        items: List<com.example.data.model.ProductReturnItem>,
        storeName: String = ""
    ): String {
        val isCustomer = productReturn.type == "CUSTOMER"
        val titleStr = if (isCustomer) "سند مرتجع مبيعات" else "سند مرتجع مشتريات"
        val partyLabel = if (isCustomer) "العميل" else "المورد"
        val partyName = if (isCustomer) productReturn.clientName else productReturn.supplierName
        val currency = productReturn.currency.ifBlank { "الريال اليمني" }

        val sb = StringBuilder()
        sb.append("🔄 *").append(titleStr).append("*\n")
        if (storeName.isNotBlank()) {
            sb.append("🏢 *").append(storeName).append("*\n")
        }
        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("• رقم المرتجع: #").append(productReturn.returnNumber).append("\n")
        sb.append("• التاريخ: ").append(DateTimeUtils.formatDateTime12h(productReturn.date)).append("\n")
        sb.append("• ").append(partyLabel).append(": ").append(partyName.ifBlank { "غير مسمى" }).append("\n")
        if (productReturn.invoiceNumber.isNotBlank()) {
            sb.append("• رقم الفاتورة الأصلية: #").append(productReturn.invoiceNumber).append("\n")
        }
        sb.append("• طريقة التسوية: ").append(productReturn.settlementType).append("\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("📦 *الأصناف المرتجعة:*\n")

        items.forEachIndexed { index, item ->
            val qtyStr = "${item.quantity} ${item.unit.ifBlank { "قطعة" }}"
            val totalFormatted = FormatUtils.formatAmount(item.totalPrice)
            sb.append("${index + 1}. ${item.itemName} | العدد المرتجع: $qtyStr | الإجمالي: $totalFormatted $currency\n")
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("💰 *إجمالي قيمة المرتجع: ").append(FormatUtils.formatAmount(productReturn.totalAmount)).append(" ").append(currency).append("*\n")

        if (productReturn.reason.isNotBlank()) {
            sb.append("📌 سبب الإرجاع: ").append(productReturn.reason).append("\n")
        }
        if (productReturn.notes.isNotBlank()) {
            sb.append("📝 ملاحظات: ").append(productReturn.notes).append("\n")
        }

        sb.append("━━━━━━━━━━━━━━━━━━━━\n")
        sb.append("✨ تم التصدير عبر النظام المحاسبي")
        return sb.toString()
    }

    /**
     * Shares a Return either as generated PDF or structured text.
     */
    fun shareReturn(
        context: Context,
        productReturn: com.example.data.model.ProductReturn,
        items: List<com.example.data.model.ProductReturnItem>,
        storeName: String,
        shareAsPdf: Boolean = true
    ): Boolean {
        val returnNumber = productReturn.returnNumber
        val isCustomer = productReturn.type == "CUSTOMER"
        val typeStr = if (isCustomer) "مرتجع مبيعات" else "مرتجع مشتريات"
        val textSummary = buildReturnSummaryText(productReturn, items, storeName)

        if (shareAsPdf) {
            val pdfPath = exportReturnToPdf(
                context = context,
                productReturn = productReturn,
                items = items,
                storeName = storeName
            )

            if (pdfPath != null) {
                val pdfFile = File(pdfPath)
                if (pdfFile.exists() && pdfFile.length() > 0L) {
                    return sharePdf(
                        context = context,
                        file = pdfFile,
                        chooserTitle = "📤 مشاركة $typeStr رقم #$returnNumber",
                        extraText = textSummary,
                        subject = "$typeStr رقم #$returnNumber - $storeName"
                    )
                }
            }
        }

        return shareText(
            context = context,
            text = textSummary,
            chooserTitle = "📤 مشاركة $typeStr رقم #$returnNumber",
            subject = "$typeStr رقم #$returnNumber - $storeName"
        )
    }

    /**
     * Shares a CSV file.
     */
    fun shareCsv(
        context: Context,
        file: File?,
        title: String = "📤 مشاركة ملف البيانات CSV"
    ): Boolean {
        return shareFile(
            context = context,
            file = file,
            mimeType = "text/csv",
            chooserTitle = title,
            subject = title
        )
    }

    /**
     * Shares a Database Backup file (.db, .json, .zip).
     */
    fun shareDatabaseBackup(
        context: Context,
        file: File?,
        title: String = "📤 مشاركة النسخة الاحتياطية"
    ): Boolean {
        return shareFile(
            context = context,
            file = file,
            mimeType = "application/octet-stream",
            chooserTitle = title,
            subject = "نسخة احتياطية لقاعدة البيانات - ${file?.name ?: ""}"
        )
    }

    /**
     * Shares an Arabic installment reminder via standard Android Share Sheet.
     */
    fun shareCustomerInstallmentReminder(
        context: Context,
        customerName: String,
        invoiceNumber: String?,
        remainingAmount: Double,
        currency: String,
        dueDate: Long,
        overdueDays: Int,
        storeName: String
    ): Boolean {
        val amountFormatted = FormatUtils.formatAmount(remainingAmount)
        val dueDateStr = if (dueDate > 0) DateTimeUtils.formatDateOnly(dueDate) else "مستحق الآن"
        val overdueStr = if (overdueDays > 0) "\n⚠️ متأخر بالسداد منذ: $overdueDays يوم" else ""
        val invoiceStr = if (!invoiceNumber.isNullOrBlank()) "\n📄 رقم الفاتورة: $invoiceNumber" else ""

        val message = """
🔔 تذكير استحقاق قسط
━━━━━━━━━━━━━━━━━
عزيزي العميل المحترم: $customerName
تحية طيبة وبعد،

نود إحاطتكم علمًا بموعد استحقاق القسط التالي:$invoiceStr
💰 المبلغ المستحق: $amountFormatted $currency
📅 تاريخ الاستحقاق: $dueDateStr$overdueStr

شاكرين ومقدّرين حسن تعاونكم الدائم.
━━━━━━━━━━━━━━━━━
🏪 $storeName
        """.trimIndent()

        return shareText(context, message, "📤 مشاركة تنبيه القسط", "تذكير بموعد قسط - $customerName")
    }
}

