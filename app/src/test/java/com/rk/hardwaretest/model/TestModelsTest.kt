package com.rk.hardwaretest.model

import org.junit.Assert.assertTrue
import org.junit.Test

class TestModelsTest {
    @Test
    fun result_defaults_to_no_details_or_evidence() {
        val result = TestResult(TestStatus.PASS, "完成")

        assertTrue(result.details.isEmpty())
        assertTrue(result.evidencePaths.isEmpty())
    }
}
