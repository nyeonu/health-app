package com.ny.my.health

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented, account-free smoke and safety tests.
 *
 * These checks exercise the actual Activity and Android lifecycle on an emulator,
 * not real Samsung Health records, Google OAuth, or live Google Drive uploads.
 * Never use real health measurements or credentials in test fixtures.
 */
@RunWith(AndroidJUnit4::class)
class HealthAppInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstLaunchNeverStartsGoogleAuthorizationWithoutExplicitOptIn() {
        compose.onNodeWithText("Health App · 수동 동기화").assertIsDisplayed()
        compose.onNodeWithText("Google 권한 확인").assertIsNotEnabled()
        compose.onNodeWithText("마지막 업로드 확인", substring = true).assertExists()
    }

    @Test
    fun grantingDriveOptInWithoutHealthPermissionsDoesNotStartUpload() {
        // Fresh emulator install: the app has never been granted Health Connect access.
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Google 권한 확인").performScrollTo().performClick()
        compose.onNodeWithText("PERMISSION_REQUIRED:", substring = true).assertExists()
        compose.onNodeWithText("확인된 업로드 이력이 없습니다.").assertExists()
        compose.onNodeWithText("확인된 파일 수:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("업로드 계속").assertDoesNotExist()
    }

    @Test
    fun backgroundAndReturnDoNotFabricateSuccessfulSynchronization() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithText("Health App · 수동 동기화").assertExists()
        compose.onNodeWithText("마지막 업로드 확인", substring = true).assertExists()
        compose.onNodeWithText("확인된 업로드 이력이 없습니다.").assertExists()
        compose.onNodeWithText("확인된 파일 수:", substring = true).assertDoesNotExist()
    }
}
