package com.example.util.pdf.mapper

import android.graphics.Color
import com.example.data.model.Client
import com.example.data.model.ExchangeRate
import com.example.data.model.Invoice
import com.example.data.model.InvoiceItem
import com.example.data.model.Item
import com.example.data.model.StoreSettings
import com.example.util.FormatUtils
import com.example.util.MonetaryUtils
import com.example.util.pdf.model.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

object InvoicePdfMapper {

    fun mapToPdfModel(
        invoice: Invoice,
        items: List<InvoiceItem>,
        client: Client?,
        settings: StoreSettings,
        rates: List<ExchangeRate> = emptyList(),
        itemsMap: Map<Int, Item> = emptyMap()
    ): PdfReportModel {
        val invoiceCurr = MonetaryUtils.normalizeCurrency(invoice.currency.ifBlank { settings.currency.ifBlank { MonetaryUtils.CURRENCY_YER } })
        val dateFmt = SimpleDateFormat("yyyy/MM/dd | hh:mm a", Locale("ar"))
        val formattedDate = dateFmt.format(Date(invoice.date))

        val storeName = settings.storeName.ifBlank { "الحكيمي للأدوية والمستلزمات الطبية" }

        // Exchange rates against YER
        val sarToYerConv = MonetaryUtils.convert(1.0, MonetaryUtils.CURRENCY_SAR, MonetaryUtils.CURRENCY_YER, rates)
        val sarToYer = sarToYerConv.convertedAmount
        val usdToYerConv = MonetaryUtils.convert(1.0, MonetaryUtils.CURRENCY_USD, MonetaryUtils.CURRENCY_YER, rates)
        val usdToYer = usdToYerConv.convertedAmount
        val usdToSarConv = MonetaryUtils.convert(1.0, MonetaryUtils.CURRENCY_USD, MonetaryUtils.CURRENCY_SAR, rates)
        val usdToSar = usdToSarConv.convertedAmount

        val metaFields = mutableListOf(
            PdfHeaderMeta("رقم الفاتورة", "#${invoice.invoiceNumber}"),
            PdfHeaderMeta("التاريخ والوقت", formattedDate),
            PdfHeaderMeta("اسم العميل", invoice.clientName.ifBlank { "عميل نقدي" }),
            PdfHeaderMeta("نوع الفاتورة", if (invoice.isQuickInvoice) "فاتورة سريعة" else "فاتورة مبيعات تفصيلية"),
            PdfHeaderMeta("عملة الحساب المعتمدة", invoice.currency.ifBlank { settings.currency }),
            PdfHeaderMeta("سعر صرف الريال السعودي", "1 ر.س = ${FormatUtils.formatAmount(sarToYer)} ر.ي"),
            PdfHeaderMeta("سعر صرف الدولار", "1 $ = ${FormatUtils.formatAmount(usdToYer)} ر.ي")
        )

        if (client != null && client.phone.isNotBlank()) {
            metaFields.add(PdfHeaderMeta("رقم هاتف العميل", client.phone))
        }

        val headerInfo = PdfHeaderInfo(
            storeName = storeName,
            storePhone = settings.storePhone,
            storeAddress = settings.storeAddress,
            reportTitle = if (invoice.isQuickInvoice) "🧾 فاتورة مبيعات سريعة" else "🧾 فاتورة مبيعات رسمية مفصلة",
            reportSubtitle = "فاتورة مبيعات رقم #${invoice.invoiceNumber} | تفصيل الصرف والعملات (سعودي / يمني / دولار)",
            metaFields = metaFields
        )

        // Clear 5-column layout with generous widths
        val columns = listOf(
            PdfTableColumn("#", "#", widthWeight = 0.4f, alignment = PdfAlignment.CENTER),
            PdfTableColumn("name", "اسم الصنف وتفاصيل الصرف", widthWeight = 3.2f, alignment = PdfAlignment.RIGHT),
            PdfTableColumn("qty", "الكمية", widthWeight = 0.8f, alignment = PdfAlignment.CENTER),
            PdfTableColumn("price", "سعر الوحدة (سعودي / يمني / دولار)", widthWeight = 2.6f, alignment = PdfAlignment.RIGHT),
            PdfTableColumn("total", "الإجمالي (سعودي / يمني / دولار)", widthWeight = 3.0f, alignment = PdfAlignment.RIGHT, isBold = true)
        )

        val rows = mutableListOf<PdfTableRow>()

        var grandTotalSar = 0.0
        var grandTotalYer = 0.0
        var grandTotalUsd = 0.0

        if (invoice.isQuickInvoice) {
            val desc = invoice.description?.ifBlank { "مبيعات عامة سريعة" } ?: "مبيعات عامة سريعة"
            val totalSar = MonetaryUtils.convert(invoice.totalAmount, invoiceCurr, MonetaryUtils.CURRENCY_SAR, rates).convertedAmount
            val totalYer = MonetaryUtils.convert(invoice.totalAmount, invoiceCurr, MonetaryUtils.CURRENCY_YER, rates).convertedAmount
            val totalUsd = MonetaryUtils.convert(invoice.totalAmount, invoiceCurr, MonetaryUtils.CURRENCY_USD, rates).convertedAmount

            grandTotalSar = totalSar
            grandTotalYer = totalYer
            grandTotalUsd = totalUsd

            rows.add(
                PdfTableRow(
                    cells = listOf(
                        PdfCell("1", alignment = PdfAlignment.CENTER, isBold = true, textSizeSp = 9.5f),
                        PdfCell(
                            "$desc\n💱 صرف معتمد: 1 ر.س = ${FormatUtils.formatAmount(sarToYer)} ر.ي | 1 $ = ${FormatUtils.formatAmount(usdToYer)} ر.ي",
                            alignment = PdfAlignment.RIGHT,
                            isBold = true,
                            textSizeSp = 9f
                        ),
                        PdfCell("1", alignment = PdfAlignment.CENTER, isBold = true, textSizeSp = 10f),
                        PdfCell(
                            "• ${FormatUtils.formatAmount(totalSar)} ر.س\n• ${FormatUtils.formatAmount(totalYer)} ر.ي\n• $${FormatUtils.formatAmount(totalUsd)}",
                            alignment = PdfAlignment.RIGHT,
                            isBold = true,
                            textColor = Color.parseColor("#047857"),
                            textSizeSp = 9.5f
                        ),
                        PdfCell(
                            "• ${FormatUtils.formatAmount(totalSar)} ر.س\n• ${FormatUtils.formatAmount(totalYer)} ر.ي\n• $${FormatUtils.formatAmount(totalUsd)}",
                            alignment = PdfAlignment.RIGHT,
                            isBold = true,
                            textColor = Color.parseColor("#1E3A8A"),
                            textSizeSp = 10f
                        )
                    )
                )
            )
        } else {
            items.forEachIndexed { index, item ->
                val storedItem = item.itemId?.let { itemsMap[it] }
                val rawCurr = item.currency.ifBlank { invoice.currency.ifBlank { settings.currency } }
                val itemCurr = MonetaryUtils.normalizeCurrency(rawCurr)
                val qty = item.quantity
                val unitPrice = item.unitPrice
                val itemTotalPrice = if (item.totalPrice > 0.001) item.totalPrice else unitPrice * qty

                // Calculate in SAR, YER, USD
                val unitPriceSar = when (itemCurr) {
                    MonetaryUtils.CURRENCY_SAR -> unitPrice
                    else -> MonetaryUtils.convert(unitPrice, itemCurr, MonetaryUtils.CURRENCY_SAR, rates).convertedAmount
                }
                val unitPriceYer = when (itemCurr) {
                    MonetaryUtils.CURRENCY_YER -> unitPrice
                    else -> MonetaryUtils.convert(unitPrice, itemCurr, MonetaryUtils.CURRENCY_YER, rates).convertedAmount
                }
                val unitPriceUsd = when (itemCurr) {
                    MonetaryUtils.CURRENCY_USD -> unitPrice
                    else -> MonetaryUtils.convert(unitPrice, itemCurr, MonetaryUtils.CURRENCY_USD, rates).convertedAmount
                }

                val totalPriceSar = when (itemCurr) {
                    MonetaryUtils.CURRENCY_SAR -> itemTotalPrice
                    else -> MonetaryUtils.convert(itemTotalPrice, itemCurr, MonetaryUtils.CURRENCY_SAR, rates).convertedAmount
                }
                val totalPriceYer = when (itemCurr) {
                    MonetaryUtils.CURRENCY_YER -> itemTotalPrice
                    else -> MonetaryUtils.convert(itemTotalPrice, itemCurr, MonetaryUtils.CURRENCY_YER, rates).convertedAmount
                }
                val totalPriceUsd = when (itemCurr) {
                    MonetaryUtils.CURRENCY_USD -> itemTotalPrice
                    else -> MonetaryUtils.convert(itemTotalPrice, itemCurr, MonetaryUtils.CURRENCY_USD, rates).convertedAmount
                }

                grandTotalSar += totalPriceSar
                grandTotalYer += totalPriceYer
                grandTotalUsd += totalPriceUsd

                val rateNote = when (itemCurr) {
                    MonetaryUtils.CURRENCY_SAR -> "1 ر.س = ${FormatUtils.formatAmount(sarToYer)} ر.ي"
                    MonetaryUtils.CURRENCY_USD -> "1 $ = ${FormatUtils.formatAmount(usdToYer)} ر.ي"
                    else -> "1 ر.س = ${FormatUtils.formatAmount(sarToYer)} ر.ي"
                }

                // Name & exchange details cell
                val nameBuilder = StringBuilder()
                nameBuilder.append(item.itemName)
                if (storedItem?.unit?.isNotBlank() == true && storedItem.unit != "قطعة") {
                    nameBuilder.append(" [${storedItem.unit}]")
                }
                nameBuilder.append("\n💱 صرف الصنف: $rateNote")
                if (itemCurr == MonetaryUtils.CURRENCY_USD) {
                    nameBuilder.append(" | 1 $ = ${FormatUtils.formatAmount(usdToSar)} ر.س")
                }
                if (storedItem != null && storedItem.purchasePrice > 0 && storedItem.purchaseCurrency.isNotBlank() && storedItem.purchaseCurrency != itemCurr) {
                    nameBuilder.append("\n📦 عملة الشراء المسجلة: ${FormatUtils.formatAmount(storedItem.purchasePrice)} ${storedItem.purchaseCurrency}")
                }

                // Unit price cell
                val priceBuilder = StringBuilder()
                priceBuilder.append("• ${FormatUtils.formatAmount(unitPriceSar)} ر.س")
                priceBuilder.append("\n• ${FormatUtils.formatAmount(unitPriceYer)} ر.ي")
                priceBuilder.append("\n• $${FormatUtils.formatAmount(unitPriceUsd)}")

                // Total cell
                val totalBuilder = StringBuilder()
                totalBuilder.append("• ${FormatUtils.formatAmount(totalPriceSar)} ر.س")
                totalBuilder.append("\n• ${FormatUtils.formatAmount(totalPriceYer)} ر.ي")
                totalBuilder.append("\n• $${FormatUtils.formatAmount(totalPriceUsd)}")

                val lineDiscount = (unitPrice * qty) - itemTotalPrice
                if (lineDiscount > 0.001) {
                    val discSar = MonetaryUtils.convert(lineDiscount, itemCurr, MonetaryUtils.CURRENCY_SAR, rates).convertedAmount
                    val discYer = MonetaryUtils.convert(lineDiscount, itemCurr, MonetaryUtils.CURRENCY_YER, rates).convertedAmount
                    val discUsd = MonetaryUtils.convert(lineDiscount, itemCurr, MonetaryUtils.CURRENCY_USD, rates).convertedAmount
                    totalBuilder.append("\n🏷️ خصم: -${FormatUtils.formatAmount(discSar)} ر.س / -${FormatUtils.formatAmount(discYer)} ر.ي / -$${FormatUtils.formatAmount(discUsd)}")
                }

                rows.add(
                    PdfTableRow(
                        cells = listOf(
                            PdfCell((index + 1).toString(), alignment = PdfAlignment.CENTER, isBold = true, textSizeSp = 9.5f),
                            PdfCell(nameBuilder.toString(), alignment = PdfAlignment.RIGHT, textColor = Color.parseColor("#0F172A"), textSizeSp = 9f),
                            PdfCell("$qty", alignment = PdfAlignment.CENTER, isBold = true, textSizeSp = 10f, textColor = Color.parseColor("#1E3A8A")),
                            PdfCell(priceBuilder.toString(), alignment = PdfAlignment.RIGHT, isBold = true, textSizeSp = 9.5f, textColor = Color.parseColor("#047857")),
                            PdfCell(totalBuilder.toString(), alignment = PdfAlignment.RIGHT, isBold = true, textSizeSp = 10f, textColor = Color.parseColor("#1E3A8A"))
                        )
                    )
                )
            }
        }

        // Summary row for table
        val summaryRows = if (!invoice.isQuickInvoice && items.isNotEmpty()) {
            val totalQty = items.sumOf { it.quantity }
            listOf(
                PdfTableRow(
                    cells = listOf(
                        PdfCell("∑", alignment = PdfAlignment.CENTER, isBold = true, textSizeSp = 10f),
                        PdfCell("إجمالي الأصناف (${items.size} أصناف)", alignment = PdfAlignment.RIGHT, isBold = true, textSizeSp = 10f),
                        PdfCell("$totalQty", alignment = PdfAlignment.CENTER, isBold = true, textSizeSp = 10f, textColor = Color.parseColor("#1E3A8A")),
                        PdfCell("-", alignment = PdfAlignment.CENTER),
                        PdfCell(
                            "• ${FormatUtils.formatAmount(grandTotalSar)} ر.س\n• ${FormatUtils.formatAmount(grandTotalYer)} ر.ي\n• $${FormatUtils.formatAmount(grandTotalUsd)}",
                            alignment = PdfAlignment.RIGHT,
                            isBold = true,
                            textSizeSp = 10f,
                            textColor = Color.parseColor("#1E3A8A")
                        )
                    ),
                    backgroundColor = Color.parseColor("#F1F5F9")
                )
            )
        } else emptyList()

        val table = PdfTable(
            title = "جدول تفاصيل الأصناف المبيعة وحساب الصرف",
            columns = columns,
            rows = rows,
            summaryRows = summaryRows
        )

        // Financial totals in SAR, YER & USD
        val discountSar = MonetaryUtils.convert(invoice.discount, invoiceCurr, MonetaryUtils.CURRENCY_SAR, rates).convertedAmount
        val discountYer = MonetaryUtils.convert(invoice.discount, invoiceCurr, MonetaryUtils.CURRENCY_YER, rates).convertedAmount
        val discountUsd = MonetaryUtils.convert(invoice.discount, invoiceCurr, MonetaryUtils.CURRENCY_USD, rates).convertedAmount

        val invoiceTotalSar = MonetaryUtils.convert(invoice.totalAmount, invoiceCurr, MonetaryUtils.CURRENCY_SAR, rates).convertedAmount
        val invoiceTotalYer = MonetaryUtils.convert(invoice.totalAmount, invoiceCurr, MonetaryUtils.CURRENCY_YER, rates).convertedAmount
        val invoiceTotalUsd = MonetaryUtils.convert(invoice.totalAmount, invoiceCurr, MonetaryUtils.CURRENCY_USD, rates).convertedAmount

        val paidSar = MonetaryUtils.convert(invoice.paidAmount, invoiceCurr, MonetaryUtils.CURRENCY_SAR, rates).convertedAmount
        val paidYer = MonetaryUtils.convert(invoice.paidAmount, invoiceCurr, MonetaryUtils.CURRENCY_YER, rates).convertedAmount
        val paidUsd = MonetaryUtils.convert(invoice.paidAmount, invoiceCurr, MonetaryUtils.CURRENCY_USD, rates).convertedAmount

        val remainingSar = (invoiceTotalSar - paidSar).coerceAtLeast(0.0)
        val remainingYer = (invoiceTotalYer - paidYer).coerceAtLeast(0.0)
        val remainingUsd = (invoiceTotalUsd - paidUsd).coerceAtLeast(0.0)

        val subtotalSar = if (invoice.isQuickInvoice) invoiceTotalSar else grandTotalSar
        val subtotalYer = if (invoice.isQuickInvoice) invoiceTotalYer else grandTotalYer
        val subtotalUsd = if (invoice.isQuickInvoice) invoiceTotalUsd else grandTotalUsd

        val summaryItems = mutableListOf<PdfSummaryItem>()

        // 1. Subtotal
        summaryItems.add(
            PdfSummaryItem(
                label = "المجموع الفرعي للأصناف",
                value = "${FormatUtils.formatAmount(subtotalSar)} ر.س  |  ${FormatUtils.formatAmount(subtotalYer)} ر.ي  |  $${FormatUtils.formatAmount(subtotalUsd)}"
            )
        )

        // 2. Discount
        if (invoice.discount > 0) {
            summaryItems.add(
                PdfSummaryItem(
                    label = "الخصم الممنوح على الفاتورة",
                    value = "-${FormatUtils.formatAmount(discountSar)} ر.س  |  -${FormatUtils.formatAmount(discountYer)} ر.ي  |  -$${FormatUtils.formatAmount(discountUsd)}",
                    isHighlighted = true,
                    highlightColor = Color.parseColor("#FEF2F2")
                )
            )
        }

        // 3. Tax if any
        if (settings.showVatAndSubtotal && invoice.taxRate > 0) {
            val taxSar = (subtotalSar - discountSar) * (invoice.taxRate / 100.0)
            val taxYer = (subtotalYer - discountYer) * (invoice.taxRate / 100.0)
            val taxUsd = (subtotalUsd - discountUsd) * (invoice.taxRate / 100.0)
            summaryItems.add(
                PdfSummaryItem(
                    label = "ضريبة القيمة المضافة (${invoice.taxRate}%)",
                    value = "${FormatUtils.formatAmount(taxSar)} ر.س  |  ${FormatUtils.formatAmount(taxYer)} ر.ي  |  $${FormatUtils.formatAmount(taxUsd)}"
                )
            )
        }

        // 4. Final Total (Highlighted)
        summaryItems.add(
            PdfSummaryItem(
                label = "⭐ الإجمالي النهائي للفاتورة",
                value = "${FormatUtils.formatAmount(invoiceTotalSar)} ر.س  |  ${FormatUtils.formatAmount(invoiceTotalYer)} ر.ي  |  $${FormatUtils.formatAmount(invoiceTotalUsd)}",
                isHighlighted = true,
                highlightColor = Color.parseColor("#E0F2FE")
            )
        )

        // 5. Paid
        summaryItems.add(
            PdfSummaryItem(
                label = "المبلغ المدفوع (نقداً/سند)",
                value = "${FormatUtils.formatAmount(paidSar)} ر.س  |  ${FormatUtils.formatAmount(paidYer)} ر.ي  |  $${FormatUtils.formatAmount(paidUsd)}"
            )
        )

        // 6. Remaining
        val isPending = invoice.remainingAmount > 0 || remainingSar > 0.001
        summaryItems.add(
            PdfSummaryItem(
                label = if (isPending) "⚠️ المبلغ المتبقي الآجل المطلوب" else "✅ المبلغ المتبقي (مسدد خالص)",
                value = if (isPending) "${FormatUtils.formatAmount(remainingSar)} ر.س  |  ${FormatUtils.formatAmount(remainingYer)} ر.ي  |  $${FormatUtils.formatAmount(remainingUsd)}" else "0.00 (مسدد بالكامل)",
                isHighlighted = isPending,
                highlightColor = if (isPending) Color.parseColor("#FEF2F2") else Color.parseColor("#F0FDF4")
            )
        )

        // 7. Exchange rates note
        summaryItems.add(
            PdfSummaryItem(
                label = "💱 أسعار الصرف المعتمدة مقابل الريال اليمني",
                value = "1 ر.س = ${FormatUtils.formatAmount(sarToYer)} ر.ي  |  1 $ = ${FormatUtils.formatAmount(usdToYer)} ر.ي  |  1 $ = ${FormatUtils.formatAmount(usdToSar)} ر.س",
                isHighlighted = true,
                highlightColor = Color.parseColor("#F8FAFC")
            )
        )

        // 8. Client current balance
        if (client != null) {
            val clientCurr = MonetaryUtils.normalizeCurrency(client.currency.ifBlank { settings.currency })
            val clientBal = client.balance
            val balSar = MonetaryUtils.convert(abs(clientBal), clientCurr, MonetaryUtils.CURRENCY_SAR, rates).convertedAmount
            val balYer = MonetaryUtils.convert(abs(clientBal), clientCurr, MonetaryUtils.CURRENCY_YER, rates).convertedAmount
            val balUsd = MonetaryUtils.convert(abs(clientBal), clientCurr, MonetaryUtils.CURRENCY_USD, rates).convertedAmount

            val statusText = when {
                clientBal > 0 -> "مدين / مستحق على العميل"
                clientBal < 0 -> "دائن / لصالح العميل"
                else -> "الحساب خالص"
            }

            val balValue = if (clientBal != 0.0) {
                "${FormatUtils.formatAmount(balSar)} ر.س  |  ${FormatUtils.formatAmount(balYer)} ر.ي  |  $${FormatUtils.formatAmount(balUsd)} ($statusText)"
            } else {
                "0.00 ($statusText)"
            }

            summaryItems.add(
                PdfSummaryItem(
                    label = "👤 إجمالي رصيد حساب العميل الحالي",
                    value = balValue,
                    isHighlighted = clientBal > 0,
                    highlightColor = if (clientBal > 0) Color.parseColor("#FEE2E2") else Color.parseColor("#F0FDF4")
                )
            )
        }

        return PdfReportModel(
            title = "فاتورة رقم #${invoice.invoiceNumber}",
            headerInfo = headerInfo,
            tables = listOf(table),
            summaryInfo = PdfSummaryInfo(title = "الملخص المالي ومطابقة الصرف للفاتورة", items = summaryItems),
            notes = invoice.notes,
            orientation = PdfOrientation.PORTRAIT,
            currency = invoice.currency.ifBlank { settings.currency }
        )
    }
}

