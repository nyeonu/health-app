package com.ny.my.health

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithText
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
        compose.onNodeWithText("Health App · P0").assertIsDisplayed()
        compose.onNodeWithText("2. Sync now · Google 권한 확인").assertIsNotEnabled()
        compose.onNodeWithText("마지막 확인된 성공", substring = true).assertExists()
    }

    @Test
    fun grantingDriveOptInWithoutHealthPermissionsDoesNotStartUpload() {
        // Fresh emulator install: the app has never been granted Health Connect access.
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("2. Sync now · Google 권한 확인").performClick()
        compose.onNodeWithText("PERMISSION_REQUIRED:", substring = true).assertExists()
        compose.onNodeWithText("최근 시도에서 확인된 업로드: 0개 파일").assertExists()
        compose.onNodeWithText("3. 업로드 계속 · 전경에서 실행").assertDoesNotExist()
    }

    @Test
    fun backgroundAndReturnDoNotFabricateSuccessfulSynchronization() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithText("Health App · P0").assertExists()
        compose.onNodeWithText("마지막 확인된 성공", substring = true).assertExists()
        compose.onNodeWithText("최근 시도에서 확인된 업로드: 0개 파일").assertExists()
    }
}
