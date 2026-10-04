package com.example.domain.currency

import java.math.BigDecimal
import java.math.RoundingMode

data class ConversionDetail(
    val originalMoney: Money,
    val targetCurrency: Currency,
    val exchangeRate: BigDecimal,
    val convertedMoney: Money,
    val rateDescription: String
)

object CurrencyConversionService {

    /**
     * Resolves the exchange rate between fromCurrency and toCurrency given a map of rates.
     */
    fun getExchangeRate(
        fromCurrency: Currency,
        toCurrency: Currency,
        rateMap: Map<Pair<Currency, Currency>, BigDecimal>
    ): BigDecimal {
        if (fromCurrency == toCurrency) {
            return BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP)
        }

        // 1. Direct pair match
        rateMap[fromCurrency to toCurrency]?.let { rate ->
            if (rate.compareTo(BigDecimal.ZERO) > 0) {
                return rate.setScale(4, RoundingMode.HALF_UP)
            }
        }

        // 2. Inverse pair match (e.g. rate defined for to -> from)
        rateMap[toCurrency to fromCurrency]?.let { inverseRate ->
            if (inverseRate.compareTo(BigDecimal.ZERO) > 0) {
                return BigDecimal.ONE.divide(inverseRate, 6, RoundingMode.HALF_UP)
                    .setScale(4, RoundingMode.HALF_UP)
            }
        }

        // 3. Triangular rate conversion via YER as anchor
        val fromToYer = if (fromCurrency == Currency.YER) BigDecimal.ONE else {
            rateMap[fromCurrency to Currency.YER]
                ?: rateMap[Currency.YER to fromCurrency]?.let { BigDecimal.ONE.divide(it, 6, RoundingMode.HALF_UP) }
        }

        val toToYer = if (toCurrency == Currency.YER) BigDecimal.ONE else {
            rateMap[toCurrency to Currency.YER]
                ?: rateMap[Currency.YER to toCurrency]?.let { BigDecimal.ONE.divide(it, 6, RoundingMode.HALF_UP) }
        }

        if (fromToYer != null && toToYer != null && toToYer.compareTo(BigDecimal.ZERO) > 0) {
            return fromToYer.divide(toToYer, 6, RoundingMode.HALF_UP)
                .setScale(4, RoundingMode.HALF_UP)
        }

        // 4. Default fallback rates
        return getFallbackRate(fromCurrency, toCurrency)
    }

    /**
     * Converts Money from its native currency to targetCurrency using exchange rates.
     */
    fun convert(
        money: Money,
        targetCurrency: Currency,
        rateMap: Map<Pair<Currency, Currency>, BigDecimal>
    ): ConversionDetail {
        if (money.currency == targetCurrency) {
            val unitRate = BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP)
            return ConversionDetail(
                originalMoney = money,
                targetCurrency = targetCurrency,
                exchangeRate = unitRate,
                convertedMoney = money,
                rateDescription = "1 ${money.currency.code} = 1 ${targetCurrency.code}"
            )
        }

        val rate = getExchangeRate(money.currency, targetCurrency, rateMap)
        val convertedAmount = money.amount.multiply(rate).setScale(Money.SCALE, RoundingMode.HALF_UP)
        val convertedMoney = Money(convertedAmount, targetCurrency)

        val desc = "1 ${money.currency.code} = ${rate.setScale(4, RoundingMode.HALF_UP)} ${targetCurrency.code}"

        return ConversionDetail(
            originalMoney = money,
            targetCurrency = targetCurrency,
            exchangeRate = rate.setScale(4, RoundingMode.HALF_UP),
            convertedMoney = convertedMoney,
            rateDescription = desc
        )
    }

    /**
     * Converts Money using an explicit user-provided exchange rate.
     */
    fun convertWithExplicitRate(
        money: Money,
        targetCurrency: Currency,
        explicitRate: BigDecimal
    ): ConversionDetail {
        if (money.currency == targetCurrency) {
            val unitRate = BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP)
            return ConversionDetail(
                originalMoney = money,
                targetCurrency = targetCurrency,
                exchangeRate = unitRate,
                convertedMoney = money,
                rateDescription = "1 ${money.currency.code} = 1 ${targetCurrency.code}"
            )
        }

        val rate = explicitRate.setScale(4, RoundingMode.HALF_UP)
        val convertedAmount = money.amount.multiply(rate).setScale(Money.SCALE, RoundingMode.HALF_UP)
        val convertedMoney = Money(convertedAmount, targetCurrency)
        val desc = "1 ${money.currency.code} = $rate ${targetCurrency.code}"

        return ConversionDetail(
            originalMoney = money,
            targetCurrency = targetCurrency,
            exchangeRate = rate,
            convertedMoney = convertedMoney,
            rateDescription = desc
        )
    }

    private fun getFallbackRate(from: Currency, to: Currency): BigDecimal {
        return when {
            from == Currency.USD && to == Currency.YER -> BigDecimal("1500.0000")
            from == Currency.YER && to == Currency.USD -> BigDecimal("0.0007")
            from == Currency.SAR && to == Currency.YER -> BigDecimal("400.0000")
            from == Currency.YER && to == Currency.SAR -> BigDecimal("0.0025")
            from == Currency.USD && to == Currency.SAR -> BigDecimal("3.7500")
            from == Currency.SAR && to == Currency.USD -> BigDecimal("0.2667")
            else -> BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP)
        }
    }
}
