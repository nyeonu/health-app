package com.ny.my.health

import android.app.Application
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ny.my.health.core.AuthorizationDiagnostics
import com.ny.my.health.core.AuthorizationDiagnostics.Failure
import com.ny.my.health.core.DataType
import com.ny.my.health.core.DayWindow
import com.ny.my.health.core.DefaultNormalizer
import com.ny.my.health.core.HealthReader
import com.ny.my.health.core.PrivateDriveUploader
import com.ny.my.health.core.SyncCoordinator
import com.ny.my.health.core.SyncDiagnostics
import com.ny.my.health.core.SyncStatus
import com.ny.my.health.core.VersionedCsvExporter
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class SyncUiState(
    val date: LocalDate = DayWindow.yesterday(Clock.systemUTC()),
    val availability: Int = HealthConnectClient.SDK_UNAVAILABLE,
    val grantedCount: Int = 0,
    val refreshing: Boolean = false,
    val pendingConsent: Boolean = false,
    val syncing: Boolean = false,
    val uploadOptIn: Boolean = false,
    val readyToUpload: Boolean = false,
    val message: String = "권한을 확인하고 비공개 폴더 업로드에 동의해 주세요.",
    val lastAttempt: Instant? = null,
    val lastSuccess: Instant? = null,
    val lastSuccessDate: LocalDate? = null,
    val lastSuccessCoverage: Set<DataType> = emptySet(),
    val uploadedFiles: Int = 0,
    val coverage: Set<DataType> = emptySet(),
    val history: HistorySessionState? = null,
    val transientRemoteStatus: SyncStatus? = null,
    val transientConfirmedFiles: Int? = null,
) {
    val busy: Boolean get() = refreshing || pendingConsent || syncing || history?.operation?.let { it != HistoryOperation.IDLE } == true
}

class SyncViewModel(application: Application) : AndroidViewModel(application) {
    private val reader = HealthConnectReader(application)
    private val clock = Clock.systemUTC()
    private val mutableState = MutableStateFlow(SyncUiState())
    internal val state = mutableState.asStateFlow()
    private var foreground = false
    private var syncJob: Job? = null
    private var refreshJob: Job? = null
    // 토큰은 저장 상태, 번들, 기록 또는 로그에 넣지 않는다.
    private var accessToken: String? = null
    internal var authorizationTicket: Long? = null
        private set
    private val historySession = HistorySession(SqliteHistoryStore(application), viewModelScope) {
        accessToken = null
        authorizationTicket = null
        mutableState.value = mutableState.value.copy(uploadOptIn = false, readyToUpload = false, pendingConsent = false)
    }
    private val effects = HistoryEffects(historySession)

    init {
        viewModelScope.launch {
            historySession.state.collect { history ->
                val attempt = history.read.history?.attempt
                val success = history.read.history?.success
                mutableState.value = mutableState.value.copy(
                    history = history,
                    lastAttempt = attempt?.authorizationRequestedAt,
                    lastSuccess = success?.confirmedAt,
                    lastSuccessDate = success?.date,
                    lastSuccessCoverage = success?.coverage ?: emptySet(),
                    message = if (history.error != null) {
                        "로컬 기록을 확인하거나 저장하지 못했습니다. 이전 성공과 원격 결과는 다를 수 있습니다. 기록을 다시 읽어 확인해 주세요."
                    } else mutableState.value.message,
                )
            }
        }
        viewModelScope.launch { historySession.restore() }
    }

    internal fun resume() {
        foreground = true
        refreshPermissions()
    }

    internal fun pause() {
        foreground = false
        if (mutableState.value.syncing) {
            syncJob?.cancel()
            accessToken = null
            mutableState.value = mutableState.value.copy(
                readyToUpload = false,
                message = "중단됨: 앱이 전경을 벗어났습니다. 일부 파일은 업로드되었을 수 있습니다. 다시 수동 동기화해 주세요.",
            )
        }
        // 외부 동의 화면의 일시 정지는 읽기나 업로드를 시작하지 않는다.
    }

    internal fun refreshPermissions() {
        refreshJob?.cancel()
        mutableState.value = mutableState.value.copy(refreshing = true)
        refreshJob = viewModelScope.launch {
            try {
                val availability = reader.availability()
                val granted = if (availability == HealthConnectClient.SDK_AVAILABLE) {
                    reader.grantedPermissions().intersect(HealthConnectReader.permissions).size
                } else 0
                mutableState.value = mutableState.value.copy(
                    availability = availability, grantedCount = granted, refreshing = false,
                    date = DayWindow.yesterday(clock),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    refreshing = false, grantedCount = 0,
                    message = "Health Connect 권한을 확인할 수 없습니다. 다시 확인해 주세요.",
                )
            }
        }
    }

    internal fun optIn(value: Boolean) {
        if (mutableState.value.busy) return
        accessToken = null
        mutableState.value = mutableState.value.copy(uploadOptIn = value, readyToUpload = false)
        if (authorizationTicket != null) viewModelScope.launch { historySession.reload() }
    }

    internal fun beginHealthConsent(): Boolean {
        val state = mutableState.value
        if (!foreground || state.busy || state.readyToUpload || authorizationTicket != null || state.availability != HealthConnectClient.SDK_AVAILABLE) return false
        accessToken = null
        mutableState.value = state.copy(pendingConsent = true, readyToUpload = false)
        return true
    }

    internal fun healthConsentReturned() {
        mutableState.value = mutableState.value.copy(
            pendingConsent = false,
            message = "권한 요청이 끝났습니다. 허용한 항목만 읽습니다. 0/5이면 권한이 필요합니다.",
        )
        refreshPermissions()
    }

    internal fun beginAuthorization(onReady: () -> Unit) {
        val state = mutableState.value
        if (!foreground || state.busy || !state.uploadOptIn || state.readyToUpload) return
        if (state.availability != HealthConnectClient.SDK_AVAILABLE || state.grantedCount == 0) {
            mutableState.value = state.copy(message = "PERMISSION_REQUIRED: 사용 가능한 Health Connect 읽기 권한이 필요합니다.")
            return
        }
        accessToken = null
        val date = DayWindow.yesterday(clock)
        mutableState.value = state.copy(pendingConsent = true, readyToUpload = false,
            uploadedFiles = 0, coverage = emptySet(), date = date,
            transientRemoteStatus = null, transientConfirmedFiles = null,
            message = "Google Drive 권한 확인 중…")
        viewModelScope.launch {
            effects.authorize(date, clock.instant()) { ticket ->
                authorizationTicket = ticket
                if (foreground) onReady()
                else authorizationFailed(ticket, Failure.FOREGROUND_DEPARTURE)
            }
            if (authorizationTicket == null) mutableState.value = mutableState.value.copy(pendingConsent = false)
        }
    }

    internal fun authorizationReturned(ticket: Long, token: String?, scopes: List<String>) {
        if (ticket != authorizationTicket || !mutableState.value.pendingConsent) return
        val failure = AuthorizationDiagnostics.resultFailure(token, DRIVE_SCOPE in scopes)
        if (failure != null) {
            authorizationFailed(ticket, failure)
            return
        }
        viewModelScope.launch {
            val accepted = effects.acceptAuthorization(ticket) { accessToken = token }
            if (ticket != authorizationTicket) return@launch
            mutableState.value = mutableState.value.copy(pendingConsent = false, readyToUpload = accepted,
                message = if (accepted) "Google 권한 확인됨. 아래 ‘업로드 계속’을 눌러 전경에서 읽기와 업로드를 시작하세요."
                    else "로컬 기록 저장을 확인하지 못했습니다. 기록을 다시 읽어 주세요.")
        }
    }

    internal fun healthConsentLaunchFailed() {
        accessToken = null
        mutableState.value = mutableState.value.copy(
            pendingConsent = false, readyToUpload = false,
            message = "HEALTH_CONSENT_LAUNCH_FAILED: Health Connect 권한 화면을 열지 못했습니다. 다시 시도해 주세요.",
        )
    }

    internal fun authorizationFailed(ticket: Long, failure: Failure, statusCode: Int? = null) {
        if (ticket != authorizationTicket || !mutableState.value.pendingConsent) return
        accessToken = null
        mutableState.value = mutableState.value.copy(pendingConsent = false, readyToUpload = false,
            message = AuthorizationDiagnostics.message(failure, statusCode))
        viewModelScope.launch {
            historySession.authorizationFailed(ticket, failure, clock.instant())
            if (authorizationTicket == ticket) authorizationTicket = null
        }
    }

    internal fun continueUpload() {
        val state = mutableState.value
        val token = accessToken ?: return
        val ticket = authorizationTicket ?: return
        if (!foreground || state.busy || !state.readyToUpload || !state.uploadOptIn) return
        val selectedDate = DayWindow.yesterday(clock)
        accessToken = null
        if (historySession.state.value.read.history?.attempt?.date != selectedDate) {
            mutableState.value = state.copy(readyToUpload = false,
                message = "날짜가 바뀌었습니다. Google 권한을 새로 확인하고 업로드 계속을 눌러 주세요.")
            viewModelScope.launch { historySession.reload() }
            return
        }
        mutableState.value = state.copy(syncing = true, readyToUpload = false, date = selectedDate,
            message = "허용된 데이터 읽기 및 업로드 중…")
        syncJob = viewModelScope.launch {
            try {
                effects.execute(ticket, selectedDate, clock.instant(), clock::instant, { foreground }, { outcome ->
                    mutableState.value = mutableState.value.copy(
                        uploadedFiles = outcome.result.uploadedFiles, coverage = outcome.coverage,
                        transientRemoteStatus = outcome.result.status,
                        transientConfirmedFiles = outcome.result.uploadedFiles,
                        message = when (outcome.result.status) {
                            SyncStatus.SUCCESS -> "허용된 ${outcome.coverage.size}/5개 항목 범위의 내보내기 업로드 확인됨. 비어 있거나 미허용인 데이터는 포함하지 않습니다."
                            SyncStatus.EMPTY -> "EMPTY: 허용된 범위에서 내보낼 기록이 없습니다. 업로드 성공이 아닙니다."
                            SyncStatus.PERMISSION_REQUIRED -> "PERMISSION_REQUIRED: 읽기 권한이 없습니다."
                            SyncStatus.FAILED -> SyncDiagnostics.failureMessage(outcome.result)
                        },
                    )
                }) { date ->
                    var coverage = emptySet<DataType>()
                    val foregroundReader = object : HealthReader {
                        override suspend fun read(date: LocalDate): com.ny.my.health.core.HealthSnapshot {
                            check(foreground)
                            val snapshot = reader.read(date)
                            currentCoroutineContext().ensureActive()
                            check(foreground)
                            coverage = snapshot.grantedTypes
                            return snapshot
                        }
                    }
                    val result = SyncCoordinator(foregroundReader, DefaultNormalizer(), VersionedCsvExporter(),
                        PrivateDriveUploader(GoogleDriveTransport(token)), clock).sync(date)
                    HistoryExecutionResult(result, coverage)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (authorizationTicket == ticket) authorizationTicket = null
                mutableState.value = mutableState.value.copy(syncing = false)
            }
        }
    }

    internal fun reloadHistory() { viewModelScope.launch { historySession.reload() } }
    internal fun requestDeleteHistory() { historySession.requestDelete() }
    internal fun cancelDeleteHistory() { historySession.cancelDelete() }
    internal fun confirmDeleteHistory() { viewModelScope.launch { historySession.confirmDelete() } }
    internal fun retryDeleteHistory() { viewModelScope.launch { historySession.retryDelete() } }
    internal fun keepExistingHistory() { viewModelScope.launch { historySession.keepExistingAfterDeleteFailure() } }

    internal fun forget() { requestDeleteHistory() }

    companion object {
        internal const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }
}
