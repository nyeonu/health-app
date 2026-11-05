package com.ny.my.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.core.view.ViewCompat
import androidx.core.view.doOnAttach
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.ny.my.health.core.AuthorizationDiagnostics.Failure

class MainActivity : ComponentActivity() {
    private lateinit var model: SyncViewModel
    private val authorizationClient by lazy { Identity.getAuthorizationClient(this) }

    private val healthConsent = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { model.healthConsentReturned() }

    private val googleConsent = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val ticket = model.consumeAuthorizationResolution() ?: return@registerForActivityResult
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
        // 본문 제목을 사용하고 시스템 바 경계는 Compose에서 일관되게 처리한다.
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[SyncViewModel::class.java]
        setContent {
            val state by model.state.collectAsState()
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(
                        Modifier.safeDrawingPadding().testTag("health-window-content"),
                        propagateMinConstraints = true,
                    ) {
                        HealthHomeScreen(state, HistoryUiActions(
                            requestHealthConsent = {
                                if (model.beginHealthConsent()) {
                                    try {
                                        healthConsent.launch(HealthConnectReader.permissions)
                                    } catch (_: Exception) {
                                        model.healthConsentLaunchFailed()
                                    }
                                }
                            },
                            refreshPermissions = model::refreshPermissions,
                            changeOptIn = model::optIn,
                            authorize = ::authorizeDrive,
                            continueUpload = model::continueUpload,
                            requestDelete = model::requestDeleteHistory,
                            cancelDelete = model::cancelDeleteHistory,
                            confirmDelete = model::confirmDeleteHistory,
                            retryDelete = model::retryDeleteHistory,
                            keepExisting = model::keepExistingHistory,
                            reload = model::reloadHistory,
                            copyPhoneProfiling = {
                                model.copyPhoneProfiling { text -> writePhoneProfilingClipboard(this@MainActivity, text) }
                            },
                        ))
                    }
                }
            }
        }
        // 첫 측정 전에 현재 inset을 전달해 복원된 스크롤이 임시 큰 viewport에 잘리지 않게 한다.
        val host = findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        host.doOnAttach { container ->
            // Compose 1.9.2는 inner owner의 attach에서 content와 inset listener를 설치한다.
            (container as android.view.ViewGroup).getChildAt(0).doOnAttach { owner ->
                ViewCompat.getRootWindowInsets(owner)?.let { ViewCompat.dispatchApplyWindowInsets(owner, it) }
            }
        }
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
                                else if (model.registerAuthorizationResolution(ticket)) {
                                    googleConsent.launch(IntentSenderRequest.Builder(pendingIntent).build())
                                }
                            } catch (_: Exception) {
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
