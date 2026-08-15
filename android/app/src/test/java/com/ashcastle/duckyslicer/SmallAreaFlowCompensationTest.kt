package com.ashcastle.duckyslicer

import com.u1.slicer.data.DEFAULT_SMALL_AREA_FLOW_COMPENSATION_MODEL
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmallAreaFlowCompensationTest {
    @Test
    fun validatesTheOrcaCurveContract() {
        assertTrue(smallAreaFlowCompensationModelIsValid(DEFAULT_SMALL_AREA_FLOW_COMPENSATION_MODEL))
        assertTrue(smallAreaFlowCompensationModelIsValid("0,0\n0.5,0.6\n10,1"))

        listOf(
            "",
            "0,0",
            "1,0\n10,1",
            "0,0\n0.5,0.6\n0.4,1",
            "0,0\n10,0.9",
            "0,0\n10,2.1",
            "0,0;10,1",
            "0,NaN\n10,1",
        ).forEach { model ->
            assertFalse(model, smallAreaFlowCompensationModelIsValid(model))
            assertFalse(
                model,
                ProfileValidation.slicing(
                    QualityProfile.STANDARD.copy(smallAreaFlowCompensationModel = model),
                ),
            )
        }
    }
}
