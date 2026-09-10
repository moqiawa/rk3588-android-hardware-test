package com.rk.hardwaretest.model

enum class TestStatus { NOT_TESTED, RUNNING, PASS, WARNING, FAIL, PERMISSION_REQUIRED }
data class TestResult(val status: TestStatus, val summary: String, val details: Map<String, String> = emptyMap(), val evidencePaths: List<String> = emptyList())
data class TestRunReport(val runId: String, val createdAtEpochMs: Long, val device: Map<String, String>, val results: Map<String, TestResult>)
