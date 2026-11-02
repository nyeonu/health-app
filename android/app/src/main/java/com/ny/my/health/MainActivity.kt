package com.ny.my.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.ny.my.health.core.AuthorizationDiagnostics.Failure
import com.ny.my.health.core.DayWindow

class MainActivity : ComponentActivity() {
    private lateinit var model: SyncViewModel
    private var consentTicket: Long? = null
    private val authorizationClient by lazy { Identity.getAuthorizationClient(this) }

    private val healthConsent = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { model.healthConsentReturned() }

    private val googleConsent = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val ticket = consentTicket ?: return@registerForActivityResult
        consentTicket = null
        when {
            result.resultCode == RESULT_CANCELED -> model.authorizationFailed(ticket, Failure.CONSENT_CANCELED)
            result.resultCode != RESULT_OK -> model.authorizationFailed(ticket, Failure.UNEXPECTED_RESULT)
            result.data == null -> model.authorizationFailed(ticket, Failure.MISSING_INTENT)
            else -> try {
                val authorization = authorizationClient.getAuthorizationResultFromIntent(result.data)
                if (authorization == null) model.authorizationFailed(ticket, Failure.MISSING_RESULT)
                else model.authorizationReturned(ticket, authorization.accessToken, authorization.grantedScopes)
            } catch (failure: Exception) {
                reportGoogleFailure(ticket, failure)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[SyncViewModel::class.java]
        consentTicket = savedInstanceState?.takeIf { it.containsKey("authorization-generation") }?.getLong("authorization-generation")
        setContent {
            val state by model.state.collectAsState()
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Health App · P0", style = MaterialTheme.typography.headlineMedium)
                        Text("수동 동기화 · 어제 ${state.date} · Asia/Seoul")
                        Text("${DayWindow.start(state.date).atZone(DayWindow.zone)} 부터\n${DayWindow.end(state.date).atZone(DayWindow.zone)} 미만")
                        val availability = when (state.availability) {
                            HealthConnectClient.SDK_AVAILABLE -> "사용 가능"
                            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "설치 또는 업데이트 필요"
                            else -> "사용 불가"
                        }
                        Text("Health Connect: $availability")
                        Text("읽기 권한: ${state.grantedCount}/5개 · 걸음, 체중, 심박, 수면, 운동")
                        Text("일부 허용 시 허용된 항목만 처리합니다. 미허용·빈 데이터는 완료된 5개 항목으로 간주하지 않습니다.")
                        Button(
                            enabled = !state.busy && state.availability == HealthConnectClient.SDK_AVAILABLE,
                            onClick = {
                                if (model.beginHealthConsent()) {
                                    try {
                                        healthConsent.launch(HealthConnectReader.permissions)
                                    } catch (_: Exception) {
                                        model.healthConsentLaunchFailed()
                                    }
                                }
                            },
                        ) { Text("1. Health Connect 권한 요청") }
                        Row {
                            Checkbox(
                                checked = state.uploadOptIn, enabled = !state.busy,
                                onCheckedChange = model::optIn,
                            )
                            Text("내 Google Drive의 비공개 HealthApp 폴더 생성·사용과 날짜별 CSV 갱신에 동의합니다. 공유 권한은 추가하지 않습니다.")
                        }
                        Button(
                            enabled = !state.busy && state.uploadOptIn && !state.readyToUpload,
                            onClick = ::authorizeDrive,
                        ) { Text("2. Sync now · Google 권한 확인") }
                        if (state.readyToUpload) {
                            Button(enabled = !state.busy, onClick = model::continueUpload) {
                                Text("3. 업로드 계속 · 전경에서 실행")
                            }
                        }
                        Text(state.message)
                        if (state.pendingConsent) Text("동의 결과 대기 중…")
                        if (state.refreshing) Text("현재 권한 확인 중…")
                        Text("최근 시도: ${state.lastAttempt?.atZone(DayWindow.zone) ?: "없음"}")
                        Text("최근 시도에서 확인된 업로드: ${state.uploadedFiles}개 파일")
                        Text("최근 읽기에서 허용된 범위: ${state.coverage.joinToString().ifEmpty { "없음" }}")
                        Text("마지막 확인된 성공 (현재 앱 프로세스 메모리에만 보관): ${state.lastSuccess?.atZone(DayWindow.zone) ?: "없음"}")
                        Text("성공 날짜: ${state.lastSuccessDate ?: "없음"} · 허용 범위: ${state.lastSuccessCoverage.joinToString().ifEmpty { "없음" }}")
                        Text("앱을 떠나면 실행 중인 작업을 중단합니다. 일부 원격 파일은 남을 수 있으며, 다시 실행하면 같은 날짜 파일을 갱신합니다.")
                        Button(enabled = !state.busy, onClick = model::refreshPermissions) {
                            Text("권한 다시 확인")
                        }
                        Button(enabled = !state.busy, onClick = model::forget) {
                            Text("로컬 동의·토큰·상태 잊기")
                        }
                        Text("권한 철회: 시스템 Health Connect 앱 권한 및 Google 계정의 연결된 앱 설정을 이용하세요. 로컬 상태 삭제는 원격 파일 삭제나 권한 철회가 아닙니다.")
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // 요청 세대만 보관한다. 토큰과 업로드 동의는 저장하지 않는다.
        consentTicket?.let { outState.putLong("authorization-generation", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        model.resume()
    }

    override fun onPause() {
        model.pause()
        super.onPause()
    }

    private fun authorizeDrive() {
        model.beginAuthorization {
            val ticket = model.authorizationTicket ?: return@beginAuthorization
            launchDriveAuthorization(ticket)
        }
    }

    private fun launchDriveAuthorization(ticket: Long) {
        try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(SyncViewModel.DRIVE_SCOPE)))
                .build()
            authorizationClient.authorize(request)
                .addOnSuccessListener { result ->
                    if (result == null) {
                        model.authorizationFailed(ticket, Failure.MISSING_RESULT)
                    } else if (result.hasResolution()) {
                        // 이전 세대나 전경을 떠난 화면은 외부 동의를 새로 열지 않는다.
                        if (ticket != model.authorizationTicket) return@addOnSuccessListener
                        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            model.authorizationFailed(ticket, Failure.FOREGROUND_DEPARTURE)
                        } else {
                            try {
                                val pendingIntent = result.pendingIntent
                                if (pendingIntent == null) model.authorizationFailed(ticket, Failure.MISSING_RESOLUTION)
                                else {
                                    consentTicket = ticket
                                    googleConsent.launch(IntentSenderRequest.Builder(pendingIntent).build())
                                }
                            } catch (_: Exception) {
                                consentTicket = null
                                model.authorizationFailed(ticket, Failure.RESOLUTION_LAUNCH_FAILED)
                            }
                        }
                    } else {
                        model.authorizationReturned(ticket, result.accessToken, result.grantedScopes)
                    }
                }
                .addOnFailureListener { failure -> reportGoogleFailure(ticket, failure) }
                .addOnCanceledListener { model.authorizationFailed(ticket, Failure.TASK_CANCELED) }
        } catch (failure: Exception) {
            reportGoogleFailure(ticket, failure)
        }
    }

    private fun reportGoogleFailure(ticket: Long, failure: Exception) {
        // 공급자 예외 원문은 경계를 넘기지 않고 숫자 상태만 전달한다.
        if (failure is ApiException) model.authorizationFailed(ticket, Failure.GOOGLE_API, failure.statusCode)
        else model.authorizationFailed(ticket, Failure.UNKNOWN)
    }
}
