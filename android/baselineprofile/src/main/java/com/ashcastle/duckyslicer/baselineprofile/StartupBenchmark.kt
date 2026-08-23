package com.ashcastle.duckyslicer.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartupWithoutCompilation() = benchmark(CompilationMode.None())

    @Test
    fun coldStartupWithBaselineProfile() = benchmark(
        CompilationMode.Partial(BaselineProfileMode.Require),
    )

    private fun benchmark(compilationMode: CompilationMode) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = 5,
        ) {
            pressHome()
            startActivityAndWait()
            Thread.sleep(FULLY_DRAWN_SETTLE_MILLIS)
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.ashcastle.duckyslicer"
        const val FULLY_DRAWN_SETTLE_MILLIS = 2_000L
    }
}
