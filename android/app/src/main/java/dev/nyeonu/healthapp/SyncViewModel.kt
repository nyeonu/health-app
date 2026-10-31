package dev.nyeonu.healthapp

import android.app.Application
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.nyeonu.healthapp.core.AuthorizationDiagnostics
import dev.nyeonu.healthapp.core.AuthorizationDiagnostics.Failure
import dev.nyeonu.healthapp.core.DataType
import dev.nyeonu.healthapp.core.DayWindow
import dev.nyeonu.healthapp.core.DefaultNormalizer
import dev.nyeonu.healthapp.core.HealthReader
import dev.nyeonu.healthapp.core.PrivateDriveUploader
import dev.nyeonu.healthapp.core.SyncCoordinator
import dev.nyeonu.healthapp.core.SyncStatus
import dev.nyeonu.healthapp.core.VersionedCsvExporter
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
) {
    val busy: Boolean get() = refreshing || pendingConsent || syncing
}

class SyncViewModel(application: Application) : AndroidViewModel(application) {
    private val reader = HealthConnectReader(application)
    private val clock = Clock.systemUTC()
    private val mutableState = MutableStateFlow(SyncUiState())
    internal val state = mutableState.asStateFlow()
    private var foreground = false
    private var syncJob: Job? = null
    private var refreshJob: Job? = null
    // Never put access tokens in SavedStateHandle, preferences, bundles or logs.
    private var accessToken: String? = null

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
        // Pending external consent may itself pause the activity. It never starts a read.
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
    }

    internal fun beginHealthConsent(): Boolean {
        val state = mutableState.value
        if (!foreground || state.busy || state.availability != HealthConnectClient.SDK_AVAILABLE) return false
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

    internal fun beginAuthorization(): Boolean {
        val state = mutableState.value
        if (!foreground || state.busy || !state.uploadOptIn) return false
        if (state.availability != HealthConnectClient.SDK_AVAILABLE || state.grantedCount == 0) {
            mutableState.value = state.copy(message = "PERMISSION_REQUIRED: 사용 가능한 Health Connect 읽기 권한이 필요합니다.")
            return false
        }
        accessToken = null
        mutableState.value = state.copy(
            pendingConsent = true, readyToUpload = false, uploadedFiles = 0, coverage = emptySet(),
            date = DayWindow.yesterday(clock),
            lastAttempt = clock.instant(), message = "Google Drive 권한 확인 중…",
        )
        return true
    }

    internal fun authorizationReturned(token: String?, scopes: List<String>) {
        if (!mutableState.value.pendingConsent) return
        val failure = AuthorizationDiagnostics.resultFailure(token, DRIVE_SCOPE in scopes)
        if (failure != null) {
            authorizationFailed(failure)
            return
        }
        accessToken = token
        mutableState.value = mutableState.value.copy(
            pendingConsent = false, readyToUpload = true,
            message = "Google 권한 확인됨. 아래 ‘업로드 계속’을 눌러 전경에서 읽기와 업로드를 시작하세요.",
        )
    }

    internal fun healthConsentLaunchFailed() {
        accessToken = null
        mutableState.value = mutableState.value.copy(
            pendingConsent = false, readyToUpload = false,
            message = "HEALTH_CONSENT_LAUNCH_FAILED: Health Connect 권한 화면을 열지 못했습니다. 다시 시도해 주세요.",
        )
    }

    internal fun authorizationFailed(failure: Failure, statusCode: Int? = null) {
        accessToken = null
        mutableState.value = mutableState.value.copy(
            pendingConsent = false, readyToUpload = false,
            message = AuthorizationDiagnostics.message(failure, statusCode),
        )
    }

    internal fun continueUpload() {
        val state = mutableState.value
        val token = accessToken ?: return
        if (!foreground || state.busy || !state.readyToUpload || !state.uploadOptIn) return
        accessToken = null
        mutableState.value = state.copy(syncing = true, readyToUpload = false, message = "허용된 데이터 읽기 및 업로드 중…")
        syncJob = viewModelScope.launch {
            try {
                var selectedDate: LocalDate? = null
                var coverage = emptySet<DataType>()
                val foregroundReader = object : HealthReader {
                    override suspend fun read(date: LocalDate): dev.nyeonu.healthapp.core.HealthSnapshot {
                    check(foreground)
                    val snapshot = reader.read(date)
                    currentCoroutineContext().ensureActive()
                    check(foreground)
                    coverage = snapshot.grantedTypes
                    mutableState.value = mutableState.value.copy(coverage = coverage)
                    return snapshot
                    }
                }
                val result = SyncCoordinator(
                    foregroundReader, DefaultNormalizer(), VersionedCsvExporter(),
                    PrivateDriveUploader(GoogleDriveTransport(token)), clock,
                ).syncYesterday { date ->
                    selectedDate = date
                    mutableState.value = mutableState.value.copy(date = date)
                }
                currentCoroutineContext().ensureActive()
                if (!foreground) return@launch
                val message = when (result.status) {
                    SyncStatus.SUCCESS -> "허용된 ${coverage.size}/5개 항목 범위의 내보내기 업로드 확인됨. 비어 있거나 미허용인 데이터는 포함하지 않습니다."
                    SyncStatus.EMPTY -> "EMPTY: 허용된 범위에서 내보낼 기록이 없습니다. 업로드 성공이 아닙니다."
                    SyncStatus.PERMISSION_REQUIRED -> "PERMISSION_REQUIRED: 읽기 권한이 없습니다."
                    SyncStatus.FAILED -> if (result.uploadedFiles > 0) {
                        "부분 업로드 후 실패: ${result.uploadedFiles}개 파일만 확인됨. 전체 작업은 성공하지 않았습니다."
                    } else "실패: 읽기 또는 업로드를 완료하지 못했습니다. 권한과 연결을 확인해 주세요."
                }
                val previous = mutableState.value
                mutableState.value = previous.copy(
                    syncing = false, message = message, uploadedFiles = result.uploadedFiles,
                    lastSuccess = if (result.status == SyncStatus.SUCCESS) clock.instant() else previous.lastSuccess,
                    lastSuccessDate = if (result.status == SyncStatus.SUCCESS) selectedDate else previous.lastSuccessDate,
                    lastSuccessCoverage = if (result.status == SyncStatus.SUCCESS) coverage else previous.lastSuccessCoverage,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(syncing = false, message = "동기화를 완료하지 못했습니다. 다시 수동으로 시도해 주세요.")
            } finally {
                mutableState.value = mutableState.value.copy(syncing = false)
            }
        }
    }

    internal fun forget() {
        if (mutableState.value.busy) return
        accessToken = null
        mutableState.value = SyncUiState(message = "이 프로세스의 동의·상태를 지웠습니다. 시스템 Health Connect와 Google 계정 설정에서 권한을 별도로 철회하세요. Drive 파일은 삭제되지 않습니다.")
        refreshPermissions()
    }

    companion object {
        internal const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }
}
