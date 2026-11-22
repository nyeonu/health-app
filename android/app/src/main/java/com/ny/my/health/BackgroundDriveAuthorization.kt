package com.ny.my.health

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.tasks.Task
import com.google.android.gms.common.api.Scope
import com.ny.my.health.core.DestinationGeneration
import com.ny.my.health.core.DriveTransport
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class BackgroundDriveAuthorization private constructor(private val adapter: Adapter) {
    internal data class Result(val resolution: Boolean, val token: String?, val scopes: List<String>)
    internal fun interface Adapter {
        fun authorize(account: BoundDriveAuthorization.AccountSelection, callback: (Result?) -> Unit)
    }

    constructor(context: Context) : this(SdkAdapter(context.applicationContext))
    internal constructor(adapter: Adapter, unused: Unit = Unit) : this(adapter)

    private val sequence = AtomicLong()

    suspend fun <T> authorize(
        boundAccount: BoundDriveAuthorization.AccountSelection,
        generation: DestinationGeneration,
        isCurrent: suspend () -> Boolean,
        block: suspend (DriveTransport) -> T,
    ): T = authorize(boundAccount, generation, isCurrent, { GoogleDriveTransport(it) }, block)

    internal suspend fun <T> authorize(
        boundAccount: BoundDriveAuthorization.AccountSelection,
        generation: DestinationGeneration,
        isCurrent: suspend () -> Boolean,
        transportFactory: (String) -> DriveTransport,
        block: suspend (DriveTransport) -> T,
    ): T {
        val ticket = BoundDriveAuthorization.Ticket(sequence.incrementAndGet(), generation, boundAccount)
        val job = currentCoroutineContext()[Job]
        fun current() = sequence.get() == ticket.id && (job?.isActive != false)
        suspend fun requireCurrent() {
            currentCoroutineContext().ensureActive()
            if (!current()) throw BoundDriveAuthorizationException()
            val durableCurrent = isCurrent()
            currentCoroutineContext().ensureActive()
            if (!durableCurrent || !current()) throw BoundDriveAuthorizationException()
        }
        requireCurrent()
        if (!BoundDriveAuthorization.authorizationEligible(
                ticket, ticket, generation, false, "validation-token",
                listOf(BoundDriveAuthorization.DRIVE_FILE_SCOPE))) throw BoundDriveAuthorizationException()
        val result = suspendCancellableCoroutine<Result?> { continuation ->
            try {
                adapter.authorize(boundAccount) { value ->
                    if (continuation.isActive) continuation.resume(value)
                }
            } catch (cancelled: CancellationException) {
                continuation.cancel(cancelled)
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
        requireCurrent()
        val accepted = BoundDriveAuthorization.acceptResult(
            ticket, if (current()) ticket else null, if (current()) generation else null,
            result?.resolution ?: false, result?.token, result?.scopes ?: emptyList(),
        ) ?: throw BoundDriveAuthorizationException()
        return accepted.withVerifiedTransport(::current, transportFactory) { transport ->
            requireCurrent()
            block(transport)
        }
    }

    internal class SdkAdapter(
        context: Context,
        private val dispatch: ((AuthorizationRequest) -> Task<AuthorizationResult>)? = null,
    ) : Adapter {
        private val app = context.applicationContext
        private val client by lazy { Identity.getAuthorizationClient(app) }

        internal fun request(account: BoundDriveAuthorization.AccountSelection): AuthorizationRequest =
            AuthorizationRequest.builder()
                .setAccount(Account(account.name, account.type))
                .setRequestedScopes(listOf(Scope(BoundDriveAuthorization.DRIVE_FILE_SCOPE)))
                .build()

        override fun authorize(account: BoundDriveAuthorization.AccountSelection, callback: (Result?) -> Unit) {
            (dispatch?.invoke(request(account)) ?: client.authorize(request(account))).addOnSuccessListener { authorization ->
                callback(if (authorization == null) null else Result(
                    authorization.hasResolution(), authorization.accessToken, authorization.grantedScopes ?: emptyList(),
                ))
            }.addOnFailureListener { callback(null) }.addOnCanceledListener { callback(null) }
        }
    }
}
