package com.ashcastle.duckyslicer

internal val DefaultFilamentColors: List<Int> = listOf(
    0xF6C945,
    0x44D7FF,
    0xFF62D0,
    0x5EE6A8,
    0xFF6B6B,
    0xA78BFA,
    0xFF9F43,
    0xE7E7E2,
    0x78D6C6,
    0xE99873,
    0x8FB8FF,
    0xD6A6E8,
    0xA8D477,
    0xFFB86B,
    0xB8B8B2,
    0xFFFFFF,
)

internal const val MIN_FILAMENT_RGB = 0x000000
internal const val MAX_FILAMENT_RGB = 0xFFFFFF

internal fun defaultFilamentColor(slot: Int): Int =
    DefaultFilamentColors[Math.floorMod(slot, DefaultFilamentColors.size)]

internal fun defaultFilamentColors(count: Int): List<Int> {
    require(count in 1..MAX_FILAMENT_SLOTS) { "Filament color count is invalid" }
    return List(count, ::defaultFilamentColor)
}

internal fun requireValidFilamentColors(colors: List<Int>) {
    require(colors.size <= MAX_FILAMENT_SLOTS) { "Too many filament colors" }
    require(colors.all { it in MIN_FILAMENT_RGB..MAX_FILAMENT_RGB }) {
        "Filament color is invalid"
    }
}

internal fun List<Int>.resolvedFilamentColors(count: Int): List<Int> {
    require(count in 1..MAX_FILAMENT_SLOTS) { "Filament color count is invalid" }
    requireValidFilamentColors(this)
    return List(count) { slot -> getOrNull(slot) ?: defaultFilamentColor(slot) }
}

internal fun List<Int>.previewFilamentColors(): List<Int> {
    requireValidFilamentColors(this)
    return List(MAX_FILAMENT_SLOTS) { slot -> getOrNull(slot) ?: defaultFilamentColor(slot) }
}
