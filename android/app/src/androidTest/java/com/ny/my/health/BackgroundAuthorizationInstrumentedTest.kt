package com.ny.my.health

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundAuthorizationInstrumentedTest {
    @Test
    fun sdkRequestPinsSelectedAccountAndDriveFileWithoutOfflineAccess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = BackgroundDriveAuthorization.SdkAdapter(context)
        val account = BoundDriveAuthorization.AccountSelection("selected@example.invalid", "com.google")
        val request = adapter.request(account)
        assertEquals(account.name, request.account?.name)
        assertEquals(account.type, request.account?.type)
        assertEquals(listOf(Scope(BoundDriveAuthorization.DRIVE_FILE_SCOPE)), request.requestedScopes)
        assertFalse(request.isOfflineAccessRequested)
    }
    @Test
    fun sdkTaskFailureWithoutAccountReturnsNoAuthorization() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = BackgroundDriveAuthorization.SdkAdapter(context) { Tasks.forException(IllegalStateException()) }
        val done = CountDownLatch(1)
        var result: BackgroundDriveAuthorization.Result? = BackgroundDriveAuthorization.Result(false, "synthetic", emptyList())
        adapter.authorize(BoundDriveAuthorization.AccountSelection("selected@example.invalid", "com.google")) {
            result = it
            done.countDown()
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertNull(result)
    }
}
