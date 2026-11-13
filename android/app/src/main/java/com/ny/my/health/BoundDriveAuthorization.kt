package com.ny.my.health

import com.ny.my.health.core.AuthorizationDiagnostics
import com.ny.my.health.core.DestinationGeneration
import com.ny.my.health.core.DrivePrincipalProof
import com.ny.my.health.core.DriveTransport
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException

/** 원시 응답과 계정·토큰을 원인 체인에 넣지 않는 고정 실패다. */
class BoundDriveAuthorizationException : IllegalStateException("Drive authorization required") {
    override val cause: Throwable? get() = null
}

/** 동의 결과는 메모리에만 보관하며 명시적 실행 때마다 동일 토큰의 사용자를 다시 확인한다. */
class BoundDriveAuthorization private constructor(
    val ticket: Ticket,
    private val credential: Credential,
) {
    data class AccountSelection(val name: String, val type: String)
    data class Ticket(val id: Long, val generation: DestinationGeneration, val account: AccountSelection)
    private class Credential(val token: String)

    suspend fun <T> withVerifiedTransport(
        isCurrent: () -> Boolean,
        block: suspend (DriveTransport) -> T,
    ): T = withVerifiedTransport(isCurrent, { GoogleDriveTransport(it) }, block)

    internal suspend fun <T> withVerifiedTransport(
        isCurrent: () -> Boolean,
        transportFactory: (String) -> DriveTransport,
        block: suspend (DriveTransport) -> T,
    ): T {
        if (!isCurrent()) throw BoundDriveAuthorizationException()
        val transport = try {
            transportFactory(credential.token)
        } catch (_: Exception) {
            throw BoundDriveAuthorizationException()
        }
        val confirmed = try {
            val response = transport.execute("GET", ABOUT_URL)
            response.status in 200..299 && proofMatches(ticket.account, response.body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!confirmed || !isCurrent()) throw BoundDriveAuthorizationException()
        return block(transport)
    }

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val ABOUT_URL = "https://www.googleapis.com/drive/v3/about?fields=user%28emailAddress%2Cme%29"

        fun pickerCandidate(
            resultOk: Boolean, currentTicket: Long?, returnedTicket: Long?, extrasPresent: Boolean,
            name: String?, type: String?,
        ): AccountSelection? = if (resultOk && currentTicket != null && currentTicket == returnedTicket &&
            extrasPresent && validAccountValue(name) && validAccountValue(type) && type == "com.google") {
            AccountSelection(checkNotNull(name), type)
        } else null

        fun authorizationEligible(
            ticket: Ticket, currentTicket: Ticket?, currentGeneration: DestinationGeneration?,
            hasResolution: Boolean, token: String?, grantedScopes: Collection<String>,
        ): Boolean = ticket == currentTicket && ticket.generation == currentGeneration &&
            validAccountValue(ticket.account.name) && ticket.account.type == "com.google" &&
            !hasResolution && AuthorizationDiagnostics.resultFailure(token, DRIVE_FILE_SCOPE in grantedScopes) == null

        fun acceptResult(
            ticket: Ticket, currentTicket: Ticket?, currentGeneration: DestinationGeneration?,
            hasResolution: Boolean, token: String?, grantedScopes: Collection<String>,
        ): BoundDriveAuthorization? = if (authorizationEligible(ticket, currentTicket, currentGeneration,
                hasResolution, token, grantedScopes)) {
            BoundDriveAuthorization(ticket, Credential(checkNotNull(token)))
        } else null

        fun proofMatches(account: AccountSelection, payload: String): Boolean =
            validAccountValue(account.name) && account.type == "com.google" &&
                DrivePrincipalProof.matches(payload, account.name)

        private fun validAccountValue(value: String?): Boolean {
            if (value.isNullOrBlank() || value.any { Character.isISOControl(it) }) return false
            return try {
                Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining() in 1..1024
            } catch (_: CharacterCodingException) {
                false
            }
        }
    }
}
