package com.example.util

import com.example.data.model.ExchangeRate
import java.math.BigDecimal
import java.math.RoundingMode

data class ConversionResult(
    val originalAmount: Double,
    val fromCurrency: String,
    val toCurrency: String,
    val exchangeRate: Double,
    val convertedAmount: Double,
    val isSameCurrency: Boolean,
    val rateDescription: String
)

object MonetaryUtils {

    const val CURRENCY_YER = "الريال اليمني"
    const val CURRENCY_USD = "الدولار الأمريكي"
    const val CURRENCY_SAR = "الريال السعودي"
    const val CURRENCY_EUR = "اليورو"

    val SUPPORTED_CURRENCIES = listOf(
        CURRENCY_YER,
        CURRENCY_USD,
        CURRENCY_SAR,
        CURRENCY_EUR
    )

    fun roundMoney(amount: Double): Double {
        if (amount.isNaN() || amount.isInfinite()) return 0.0
        return BigDecimal.valueOf(amount)
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
    }

    fun roundRate(rate: Double): Double {
        if (rate.isNaN() || rate.isInfinite() || rate <= 0.0) return 1.0
        return BigDecimal.valueOf(rate)
            .setScale(4, RoundingMode.HALF_UP)
            .toDouble()
    }

    fun normalizeCurrency(currency: String?): String {
        val trimmed = currency?.trim() ?: return CURRENCY_YER
        return when {
            trimmed.contains("يمني", ignoreCase = true) || trimmed.equals("YER", ignoreCase = true) || trimmed.equals("ر.ي", ignoreCase = true) -> CURRENCY_YER
            trimmed.contains("دولار", ignoreCase = true) || trimmed.equals("USD", ignoreCase = true) || trimmed.equals("$", ignoreCase = true) -> CURRENCY_USD
            trimmed.contains("سعودي", ignoreCase = true) || trimmed.equals("SAR", ignoreCase = true) || trimmed.equals("ر.س", ignoreCase = true) -> CURRENCY_SAR
            trimmed.contains("يورو", ignoreCase = true) || trimmed.equals("EUR", ignoreCase = true) || trimmed.equals("€", ignoreCase = true) -> CURRENCY_EUR
            trimmed.isNotBlank() -> trimmed
            else -> CURRENCY_YER
        }
    }

    fun getCurrencyCode(currency: String): String {
        return when (normalizeCurrency(currency)) {
            CURRENCY_YER -> "YER"
            CURRENCY_USD -> "USD"
            CURRENCY_SAR -> "SAR"
            CURRENCY_EUR -> "EUR"
            else -> currency
        }
    }

    fun getCurrencySymbol(currency: String): String {
        return when (normalizeCurrency(currency)) {
            CURRENCY_YER -> "ر.ي"
            CURRENCY_USD -> "$"
            CURRENCY_SAR -> "ر.س"
            CURRENCY_EUR -> "€"
            else -> currency
        }
    }

    /**
     * Converts an amount from `fromCurrency` to `toCurrency` using the database exchange rates.
     */
    fun convert(
        amount: Double,
        fromCurrency: String,
        toCurrency: String,
        rates: List<ExchangeRate>,
        customOverrideRate: Double? = null
    ): ConversionResult {
        val normFrom = normalizeCurrency(fromCurrency)
        val normTo = normalizeCurrency(toCurrency)

        val roundedAmount = roundMoney(amount)

        if (normFrom == normTo) {
            return ConversionResult(
                originalAmount = roundedAmount,
                fromCurrency = normFrom,
                toCurrency = normTo,
                exchangeRate = 1.0,
                convertedAmount = roundedAmount,
                isSameCurrency = true,
                rateDescription = "1 $normFrom = 1 $normTo"
            )
        }

        // Check if custom override rate provided
        if (customOverrideRate != null && customOverrideRate > 0.0) {
            val rate = roundRate(customOverrideRate)
            // Determine if rate is direct (e.g. 1 USD = 1500 YER) or inverted based on typical convention
            val converted = roundMoney(roundedAmount * rate)
            val desc = "1 $normTo = ${FormatUtils.formatAmount(rate)} $normFrom"
            return ConversionResult(
                originalAmount = roundedAmount,
                fromCurrency = normFrom,
                toCurrency = normTo,
                exchangeRate = rate,
                convertedAmount = converted,
                isSameCurrency = false,
                rateDescription = desc
            )
        }

        // 1. Direct rate search in DB (from -> to or to -> from)
        val directRate = rates.find { 
            (normalizeCurrency(it.fromCurrency) == normFrom && normalizeCurrency(it.toCurrency) == normTo) ||
            (normalizeCurrency(it.fromCurrency) == normTo && normalizeCurrency(it.toCurrency) == normFrom)
        }

        if (directRate != null) {
            val rateVal = directRate.rate
            if (normalizeCurrency(directRate.fromCurrency) == normTo && normalizeCurrency(directRate.toCurrency) == normFrom) {
                // Rate is defined as To -> From (e.g., 1 USD = 1500 YER, converting YER to USD)
                // Converted Amount = amount / 1500
                val converted = roundMoney(BigDecimal.valueOf(roundedAmount).divide(BigDecimal.valueOf(rateVal), 6, RoundingMode.HALF_UP).toDouble())
                val desc = "1 $normTo = ${FormatUtils.formatAmount(rateVal)} $normFrom"
                return ConversionResult(
                    originalAmount = roundedAmount,
                    fromCurrency = normFrom,
                    toCurrency = normTo,
                    exchangeRate = rateVal,
                    convertedAmount = converted,
                    isSameCurrency = false,
                    rateDescription = desc
                )
            } else {
                // Rate is defined as From -> To (e.g., 1 USD = 1500 YER, converting USD to YER)
                val converted = roundMoney(BigDecimal.valueOf(roundedAmount).multiply(BigDecimal.valueOf(rateVal)).toDouble())
                val desc = "1 $normFrom = ${FormatUtils.formatAmount(rateVal)} $normTo"
                return ConversionResult(
                    originalAmount = roundedAmount,
                    fromCurrency = normFrom,
                    toCurrency = normTo,
                    exchangeRate = rateVal,
                    convertedAmount = converted,
                    isSameCurrency = false,
                    rateDescription = desc
                )
            }
        }

        // 2. Default fallback rates if DB rate not explicitly set
        val fallbackRate = getFallbackRate(normFrom, normTo)
        val converted = if (fallbackRate.isInverted) {
            roundMoney(BigDecimal.valueOf(roundedAmount).divide(BigDecimal.valueOf(fallbackRate.rate), 6, RoundingMode.HALF_UP).toDouble())
        } else {
            roundMoney(BigDecimal.valueOf(roundedAmount).multiply(BigDecimal.valueOf(fallbackRate.rate)).toDouble())
        }

        val desc = if (fallbackRate.isInverted) {
            "1 $normTo = ${FormatUtils.formatAmount(fallbackRate.rate)} $normFrom"
        } else {
            "1 $normFrom = ${FormatUtils.formatAmount(fallbackRate.rate)} $normTo"
        }

        return ConversionResult(
            originalAmount = roundedAmount,
            fromCurrency = normFrom,
            toCurrency = normTo,
            exchangeRate = fallbackRate.rate,
            convertedAmount = converted,
            isSameCurrency = false,
            rateDescription = desc
        )
    }

    private data class FallbackRateResult(val rate: Double, val isInverted: Boolean)

    private fun getFallbackRate(fromCurrency: String, toCurrency: String): FallbackRateResult {
        return when {
            fromCurrency == CURRENCY_USD && toCurrency == CURRENCY_YER -> FallbackRateResult(1500.0, false)
            fromCurrency == CURRENCY_YER && toCurrency == CURRENCY_USD -> FallbackRateResult(1500.0, true)
            fromCurrency == CURRENCY_SAR && toCurrency == CURRENCY_YER -> FallbackRateResult(400.0, false)
            fromCurrency == CURRENCY_YER && toCurrency == CURRENCY_SAR -> FallbackRateResult(400.0, true)
            fromCurrency == CURRENCY_USD && toCurrency == CURRENCY_SAR -> FallbackRateResult(3.75, false)
            fromCurrency == CURRENCY_SAR && toCurrency == CURRENCY_USD -> FallbackRateResult(3.75, true)
            fromCurrency == CURRENCY_EUR && toCurrency == CURRENCY_USD -> FallbackRateResult(1.08, false)
            fromCurrency == CURRENCY_USD && toCurrency == CURRENCY_EUR -> FallbackRateResult(1.08, true)
            else -> FallbackRateResult(1.0, false)
        }
    }
}
