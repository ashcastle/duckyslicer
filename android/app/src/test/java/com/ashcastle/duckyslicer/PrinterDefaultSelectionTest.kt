package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PrinterDefaultSelectionTest {
    @Test
    fun incompatibleSelectionsUseExactPrinterDefaultsAndPopulatePhysicalTools() {
        val printer = printer(
            extruderCount = 2,
            defaultPrintProfile = "Target quality",
            defaultFilamentProfiles = listOf("Target PLA", "Target support"),
        )
        val targetPla = filament("target-pla", "Target PLA", printer)
        val targetSupport = filament("target-support", "Target support", printer)
        val targetQuality = quality("target-quality", "Target quality", printer)
        val options = SliceOptions()
            .selectFilament(filament("foreign-filament", "Foreign filament", "Other printer"))
            .selectQuality(quality("foreign-quality", "Foreign quality", "Other printer"))

        val updated = options.selectPrinter(
            printer,
            ProfileCatalog(
                printers = listOf(printer),
                filaments = listOf(targetPla, targetSupport),
                slicing = listOf(targetQuality),
            ),
        )

        assertEquals(targetQuality.id, updated.quality.id)
        assertEquals(
            listOf(targetPla.id, targetSupport.id),
            updated.resolvedFilamentSlots().map(FilamentProfile::id),
        )
        assertEquals(targetPla.nozzleTemp, updated.nozzleTemp)
    }

    @Test
    fun compatibleCurrentSelectionsRemainSelected() {
        val printer = printer(
            defaultPrintProfile = "Vendor quality",
            defaultFilamentProfiles = listOf("Vendor filament"),
        )
        val currentFilament = filament("current-filament", "Current filament", printer)
        val currentQuality = quality("current-quality", "Current quality", printer)
        val options = SliceOptions()
            .selectFilament(currentFilament)
            .selectQuality(currentQuality)

        val updated = options.selectPrinter(
            printer,
            ProfileCatalog(
                printers = listOf(printer),
                filaments = listOf(
                    filament("vendor-filament", "Vendor filament", printer),
                    currentFilament,
                ),
                slicing = listOf(
                    quality("vendor-quality", "Vendor quality", printer),
                    currentQuality,
                ),
            ),
        )

        assertSame(currentFilament, updated.filamentProfile)
        assertSame(currentQuality, updated.quality)
    }

    @Test
    fun compatibleToolSlotsRemainWhileOnlyIncompatibleSlotsUseVendorDefaults() {
        val printer = printer(
            extruderCount = 2,
            defaultFilamentProfiles = listOf("Vendor primary", "Vendor support"),
        )
        val currentPrimary = filament("current-primary", "Current primary", printer)
        val incompatibleSecondary = filament("foreign-secondary", "Foreign secondary", "Other printer")
        val vendorPrimary = filament("vendor-primary", "Vendor primary", printer)
        val vendorSupport = filament("vendor-support", "Vendor support", printer)
        val options = SliceOptions()
            .selectFilament(currentPrimary)
            .copy(filamentSlots = listOf(currentPrimary, incompatibleSecondary))

        val updated = options.selectPrinter(
            printer,
            ProfileCatalog(
                printers = listOf(printer),
                filaments = listOf(vendorPrimary, vendorSupport, currentPrimary),
            ),
        )

        assertEquals(
            listOf(currentPrimary.id, vendorSupport.id),
            updated.resolvedFilamentSlots().map(FilamentProfile::id),
        )
    }

    @Test
    fun singleToolAlternativeDefaultsDoNotCreateVirtualFilamentSlots() {
        val printer = printer(
            extruderCount = 1,
            defaultFilamentProfiles = listOf("Preferred PLA", "Alternative PETG"),
        )
        val preferred = filament("preferred", "Preferred PLA", printer)
        val alternative = filament("alternative", "Alternative PETG", printer)
        val options = SliceOptions()
            .selectFilament(filament("foreign", "Foreign", "Other printer"))

        val updated = options.selectPrinter(
            printer,
            ProfileCatalog(
                printers = listOf(printer),
                filaments = listOf(preferred, alternative),
            ),
        )

        assertEquals(listOf(preferred.id), updated.resolvedFilamentSlots().map(FilamentProfile::id))
    }

    @Test
    fun semmDefaultAlternativesDoNotExpandToVirtualCapacity() {
        val printer = printer(
            extruderCount = MAX_FILAMENT_SLOTS,
            singleExtruderMultiMaterial = true,
            defaultFilamentProfiles = listOf("Preferred PLA", "Alternative PETG"),
        )
        val preferred = filament("preferred", "Preferred PLA", printer)
        val alternative = filament("alternative", "Alternative PETG", printer)
        val options = SliceOptions()
            .selectFilament(filament("foreign", "Foreign", "Other printer"))

        val updated = options.selectPrinter(
            printer,
            ProfileCatalog(
                printers = listOf(printer),
                filaments = listOf(preferred, alternative),
            ),
        )

        assertEquals(listOf(preferred.id), updated.resolvedFilamentSlots().map(FilamentProfile::id))
    }

    private fun printer(
        extruderCount: Int = 1,
        singleExtruderMultiMaterial: Boolean = false,
        defaultPrintProfile: String = "",
        defaultFilamentProfiles: List<String> = emptyList(),
    ) = PrinterProfile.CUSTOM_CARTESIAN.copy(
        id = "target-printer",
        name = "Target printer",
        extruderCount = extruderCount,
        singleExtruderMultiMaterial = singleExtruderMultiMaterial,
        defaultPrintProfile = defaultPrintProfile,
        defaultFilamentProfiles = defaultFilamentProfiles,
    )

    private fun filament(id: String, name: String, printer: PrinterProfile) =
        filament(id, name, printer.name)

    private fun filament(id: String, name: String, printerName: String) =
        FilamentProfile.GENERIC_PLA.copy(
            id = id,
            name = name,
            compatiblePrinters = listOf(printerName),
        )

    private fun quality(id: String, name: String, printer: PrinterProfile) =
        quality(id, name, printer.name)

    private fun quality(id: String, name: String, printerName: String) =
        QualityProfile.STANDARD.copy(
            id = id,
            name = name,
            nozzleDiameter = 0.4f,
            compatiblePrinters = listOf(printerName),
        )
}
