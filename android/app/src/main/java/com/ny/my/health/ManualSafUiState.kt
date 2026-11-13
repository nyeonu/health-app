package com.ny.my.health

import com.ny.my.health.core.BatchStatus
import com.ny.my.health.core.CoverageSummary
import com.ny.my.health.core.DataType
import com.ny.my.health.core.DestinationGeneration
import java.time.LocalDate

internal enum class ManualSafPhase { UNCONFIGURED, SELECTION_REQUIRED, READY, RUNNING, BLOCKED }

internal data class ManualSafState(
    val phase: ManualSafPhase = ManualSafPhase.UNCONFIGURED,
    val generation: DestinationGeneration? = null,
    val configured: Boolean = false,
    val foreground: Boolean = false,
    val privacyConsent: Boolean = false,
    val intendedCategories: Set<DataType> = emptySet(),
    val categories: Set<DataType> = emptySet(),
    val grantedCategories: Set<DataType> = emptySet(),
    val enrolledDay: LocalDate? = null,
    val coverage: CoverageSummary? = null,
    val outcome: BatchStatus? = null,
)

internal data class SafManualActions(
    val changeConsent: (Boolean) -> Unit,
    val changeCategories: (Set<DataType>) -> Unit,
    val requestHealthConsent: () -> Unit,
    val refreshGrants: () -> Unit,
    val selectTree: () -> Unit,
    val continueManual: () -> Unit,
    val retry: () -> Unit,
    val resetConfiguration: (DestinationGeneration) -> Unit,
)
