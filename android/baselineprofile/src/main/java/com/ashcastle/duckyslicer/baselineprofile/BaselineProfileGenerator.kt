package com.ashcastle.duckyslicer.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startupAndRestoreWorkspace() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
        strictStability = true,
        filterPredicate = { rule -> rule.contains(FIRST_PARTY_PROFILE_PREFIX) },
    ) {
        startActivityAndWait()
        Thread.sleep(FULLY_DRAWN_SETTLE_MILLIS)
    }

    private companion object {
        const val TARGET_PACKAGE = "com.ashcastle.duckyslicer"
        const val FIRST_PARTY_PROFILE_PREFIX = "Lcom/ashcastle/duckyslicer/"
        const val FULLY_DRAWN_SETTLE_MILLIS = 2_000L
    }
}
