package com.ashcastle.duckyslicer

import java.math.BigDecimal
import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale

internal fun exactSettingInput(value: Float, scale: Float): String =
    BigDecimal(value.toString()).multiply(BigDecimal(scale.toString()))
        .stripTrailingZeros().toPlainString()

/** Keep the localized unit while replacing the rounded numeric prefix. */
internal fun exactSettingDisplay(label: String, value: Float, scale: Float, locale: Locale): String {
    if (!value.isFinite() || !scale.isFinite() || scale <= 0f) return label
    val format = NumberFormat.getNumberInstance(locale)
    val position = ParsePosition(0)
    if (format.parse(label, position) == null || position.index == 0) return label
    format.isGroupingUsed = false
    format.maximumFractionDigits = 340
    format.minimumFractionDigits = 0
    return format.format(BigDecimal(exactSettingInput(value, scale))) + label.substring(position.index)
}
