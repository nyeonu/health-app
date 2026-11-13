package com.ny.my.health

import android.os.Bundle
import android.accounts.Account
import android.accounts.AccountManager
import com.google.android.gms.common.AccountPicker
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope

/** 테스트는 SDK 객체나 실제 계정 없이 외부 경계의 합성 값만 전달한다. */
internal interface ManualDriveBridge {
    fun isBusy(): Boolean = false
    fun selectAccount(requestId: Long, result: (Boolean, Boolean, String?, String?) -> Unit)
    fun authorize(ticket: BoundDriveAuthorization.Ticket, result: (Boolean, String?, List<String>) -> Unit)
}

class MainActivity : ComponentActivity() {
    private lateinit var model: SyncViewModel
    private val authorizationClient by lazy { Identity.getAuthorizationClient(this) }

    private val healthConsent = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { model.healthConsentReturned() }

    // 요청 객체는 launch별로 고정하며 외부 결과가 돌아오기 전에는 슬롯을 교체하지 않는다.
    private class BridgeRequest(val id: Long, val generation: com.ny.my.health.core.DestinationGeneration?) {
        var valid = true
        var external = false
    }
    private class PendingExternalResult(val request: BridgeRequest?, val apply: () -> Unit)
    private var pendingExternalResult: PendingExternalResult? = null
    private var pickerRequest: BridgeRequest? = null
    private var activeAuthorization: BridgeRequest? = null
    private var consentRequest: BridgeRequest? = null
    private var pickerResult: ((Boolean, Boolean, String?, String?) -> Unit)? = null
    private var authorizationResult: ((Boolean, String?, List<String>) -> Unit)? = null
    private fun bridgeBusy() = pickerRequest != null || activeAuthorization != null || consentRequest != null
    private fun current(request: BridgeRequest): Boolean = request.valid &&
        request.generation == model.manual.state.value.generation && model.manual.state.value.privateFolderConsent &&
        model.manual.state.value.grantedCategories.isNotEmpty() &&
        (request === pickerRequest || request === activeAuthorization || request === consentRequest)

    private fun finishRequest(request: BridgeRequest) {
        request.valid = false
        if (pickerRequest === request) { pickerRequest = null; pickerResult = null }
        if (activeAuthorization === request) activeAuthorization = null
        if (consentRequest === request) { consentRequest = null; authorizationResult = null }
    }

    private fun invalidateBridgeRequests() {
        pickerRequest?.valid = false
        activeAuthorization?.valid = false
        consentRequest?.valid = false
        pendingExternalResult?.request?.let(::finishRequest)
        pendingExternalResult = null
        // 이미 연 외부 launcher는 결과가 돌아올 때까지 무효 슬롯을 유지한다.
        activeAuthorization?.takeIf { it !== consentRequest }?.let(::finishRequest)
    }

    private fun deliver(request: BridgeRequest, result: () -> Unit) {
        if (!current(request)) { finishRequest(request); return }
        val apply = {
            if (current(request) && model.manual.state.value.foreground) {
                finishRequest(request)
                result()
            } else finishRequest(request)
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && model.manual.state.value.foreground) apply()
        else if (request.external && pendingExternalResult == null) pendingExternalResult = PendingExternalResult(request, apply)
        else finishRequest(request)
    }

    internal var manualBridge: ManualDriveBridge = object : ManualDriveBridge {
        override fun isBusy() = bridgeBusy()
        override fun selectAccount(requestId: Long, result: (Boolean, Boolean, String?, String?) -> Unit) {
            if (bridgeBusy() || !model.manual.expectExternalResult(requestId)) return
            val request = BridgeRequest(requestId, model.manual.state.value.generation)
            request.external = true
            pickerRequest = request
            pickerResult = result
            try {
                accountPicker.launch(AccountPicker.newChooseAccountIntent(AccountPicker.AccountChooserOptions.Builder()
                    .setAllowableAccountsTypes(listOf("com.google"))
                    .setAlwaysShowAccountPicker(true).build()))
            } catch (_: Exception) { deliver(request) { result(false, false, null, null) } }
        }

        override fun authorize(ticket: BoundDriveAuthorization.Ticket,
            result: (Boolean, String?, List<String>) -> Unit) {
            if (bridgeBusy() || ticket.generation != model.manual.state.value.generation) return
            val owner = BridgeRequest(ticket.id, ticket.generation)
            activeAuthorization = owner
            try {
                val request = AuthorizationRequest.builder()
                    .setAccount(Account(ticket.account.name, ticket.account.type))
                    .setRequestedScopes(listOf(Scope(BoundDriveAuthorization.DRIVE_FILE_SCOPE)))
                    .build()
                authorizationClient.authorize(request).addOnSuccessListener { authorization ->
                    if (!current(owner)) return@addOnSuccessListener
                    if (authorization == null) deliver(owner) { result(false, null, emptyList()) }
                    else if (authorization.hasResolution()) {
                        val pendingIntent = authorization.pendingIntent
                        if (pendingIntent == null || !model.manual.expectExternalResult(ticket.id)) {
                            deliver(owner) { result(true, null, emptyList()) }
                        } else {
                            owner.external = true
                            consentRequest = owner
                            authorizationResult = result
                            try { googleConsent.launch(IntentSenderRequest.Builder(pendingIntent).build()) }
                            catch (_: Exception) { deliver(owner) { result(true, null, emptyList()) } }
                        }
                    } else {
                        val token = authorization.accessToken
                        val scopes = authorization.grantedScopes
                        deliver(owner) { result(false, token, scopes) }
                    }
                }.addOnFailureListener { deliver(owner) { result(false, null, emptyList()) } }
                    .addOnCanceledListener { deliver(owner) { result(false, null, emptyList()) } }
            } catch (_: Exception) { deliver(owner) { result(false, null, emptyList()) } }
        }
    }

    private val accountPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { returned ->
        val request = pickerRequest ?: return@registerForActivityResult
        val callback = pickerResult ?: return@registerForActivityResult
        if (!current(request)) { finishRequest(request); return@registerForActivityResult }
        try {
            val extras = returned.data?.extras
            // 공급자 값은 controller의 엄격한 길이·형식·현재 ticket 검사로 다시 검증한다.
            val name = extras?.getString(AccountManager.KEY_ACCOUNT_NAME)
            val type = extras?.getString(AccountManager.KEY_ACCOUNT_TYPE)
            deliver(request) { callback(returned.resultCode == RESULT_OK, extras != null, name, type) }
        } catch (_: Exception) { deliver(request) { callback(false, false, null, null) } }
    }

    private val googleConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { returned ->
        val request = consentRequest ?: return@registerForActivityResult
        val callback = authorizationResult ?: return@registerForActivityResult
        if (!current(request)) { finishRequest(request); return@registerForActivityResult }
        if (returned.resultCode != RESULT_OK || returned.data == null) {
            deliver(request) { callback(false, null, emptyList()) }
        } else try {
            val authorization = authorizationClient.getAuthorizationResultFromIntent(returned.data)
            val resolution = authorization?.hasResolution() ?: false
            val token = authorization?.accessToken
            val scopes = authorization?.grantedScopes.orEmpty()
            deliver(request) { callback(resolution, token, scopes) }
        } catch (_: Exception) { deliver(request) { callback(false, null, emptyList()) } }
    }

    /** 실제 전경 대기 자체만 관찰하는 계정 없는 lifecycle 검증 경계다. */
    internal fun deliverForeground(externalResult: Boolean = false, result: () -> Unit) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && model.manual.state.value.foreground) result()
        else if (externalResult && pendingExternalResult == null) pendingExternalResult = PendingExternalResult(null, result)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 본문 제목을 사용하고 시스템 바 경계는 Compose에서 일관되게 처리한다.
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        // edge-to-edge 초기화 전에 보존된 Window의 inset 처리를 맞춘다.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[SyncViewModel::class.java]
        setContent {
            val owner = LocalView.current
            val initialInsetsDispatched = remember(owner) { mutableStateOf(false) }
            val state by model.state.collectAsState()
            val manual by model.manual.state.collectAsState()
            val categories by model.manualCategories.collectAsState()
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
                            authorize = { model.authorizeManual(manualBridge) },
                            continueUpload = model::continueManual,
                            requestDelete = model::requestDeleteHistory,
                            cancelDelete = model::cancelDeleteHistory,
                            confirmDelete = model::confirmDeleteHistory,
                            retryDelete = model::retryDeleteHistory,
                            keepExisting = model::keepExistingHistory,
                            reload = model::reloadHistory,
                            copyPhoneProfiling = {
                                model.copyPhoneProfiling { text -> writePhoneProfilingClipboard(this@MainActivity, text) }
                            },
                        ), manual, ManualDriveActions(
                            changeConsent = {
                                if (!it) invalidateBridgeRequests()
                                model.manual.setPrivateFolderConsent(it)
                            },
                            changeCategories = {
                                invalidateBridgeRequests()
                                model.changeManualCategories(it)
                            },
                            requestHealthConsent = {
                                if (model.beginHealthConsent()) try {
                                    healthConsent.launch(HealthConnectReader.permissions)
                                } catch (_: Exception) { model.healthConsentLaunchFailed() }
                            },
                            refreshGrants = model::refreshPermissions,
                            selectAccount = { model.selectManualAccount(manualBridge) },
                            authorize = { model.authorizeManual(manualBridge) },
                            continueManual = model::continueManual,
                            resetConfiguration = {
                                invalidateBridgeRequests()
                                model.resetManualConfiguration()
                            },
                        ), categories)
                    }
                }
            }
            // inset listener 설치 뒤 첫 측정 전에 현재 값을 한 번만 전달한다.
            SideEffect {
                if (!initialInsetsDispatched.value) {
                    ViewCompat.getRootWindowInsets(owner)?.let {
                        ViewCompat.dispatchApplyWindowInsets(owner, it)
                        initialInsetsDispatched.value = true
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.resume {
            val pending = pendingExternalResult
            pendingExternalResult = null
            if (model.manual.state.value.phase != ManualDrivePhase.BLOCKED) pending?.apply?.invoke()
            else pending?.request?.let(::finishRequest)
        }
    }

    override fun onDestroy() {
        invalidateBridgeRequests()
        super.onDestroy()
    }

    override fun onPause() {
        pendingExternalResult?.request?.let(::finishRequest)
        pendingExternalResult = null
        activeAuthorization?.takeIf { !it.external }?.let(::finishRequest)
        model.pause()
        super.onPause()
    }

}
