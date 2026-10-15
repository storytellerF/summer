package com.storytellerf.summer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.storytellerf.summer.data.llmd.*
import com.storytellerf.summer.ui.recognition.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LlmdStatusRowsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun anIncompleteProbeDoesNotGuessInstallationOrAuthorization() {
        val state = LlmdStatusState(statuses = mapOf(
            LlmdTarget.Release to LlmdStatus.NotInstalled,
            LlmdTarget.Alpha to LlmdStatus.Unavailable("No response", installationKnown = true),
            LlmdTarget.Debug to LlmdStatus.Unavailable("Could not inspect app"),
        ))
        compose.setContent {
            MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                LlmdStatusRows(state, LlmdTarget.Debug, {}, {}, {}, {})
            } }
        }
        compose.onNode(hasText("Installation: Installed") and hasAnyAncestor(hasTestTag("llmd-status-alpha")))
            .performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Installation: Unknown") and hasAnyAncestor(hasTestTag("llmd-status-debug")))
            .performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Authorization: Unknown") and hasAnyAncestor(hasTestTag("llmd-status-debug")))
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun everyVariantShowsItsOwnStatus_andAuthorizationTargetsTheCorrectVariant() {
        var selected: LlmdTarget? = null
        var authorized: LlmdTarget? = null
        var opened: LlmdTarget? = null
        val state = LlmdStatusState(statuses = mapOf(
            LlmdTarget.Release to LlmdStatus.NotInstalled,
            LlmdTarget.Alpha to LlmdStatus.AuthorizationRequired,
            LlmdTarget.Debug to LlmdStatus.Available(LlmdServiceStatus.Ready, "explicit-model", "explicit-model", listOf("other-model", "explicit-model")),
        ))
        compose.setContent {
            MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                LlmdStatusRows(state, LlmdTarget.Debug, { selected = it }, {}, { authorized = it }, { opened = it })
            } }
        }
        compose.onNodeWithText("Installation: Not installed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Authorize Alpha").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(LlmdTarget.Alpha, authorized) }
        compose.onNodeWithText("LLMD Release").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(LlmdTarget.Release, selected) }
        compose.onNodeWithText("Running model: explicit-model").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Imported models: other-model, explicit-model").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open Debug").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(LlmdTarget.Debug, opened) }
    }

    @Test fun oldVersionsAndMissingModelReportsRemainUnknown_withoutFallback() {
        val state = LlmdStatusState(statuses = mapOf(
            LlmdTarget.Release to LlmdStatus.UpdateRequired,
            LlmdTarget.Alpha to LlmdStatus.Available(LlmdServiceStatus.Off, null, null, emptyList()),
            LlmdTarget.Debug to LlmdStatus.Available(LlmdServiceStatus.Ready, null, null, listOf("imported-only")),
        ))
        compose.setContent {
            MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                LlmdStatusRows(state, LlmdTarget.Debug, {}, {}, {}, {})
            } }
        }
        compose.onNodeWithText("Authorization: Unknown").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Update LLMD to check status without loading a model.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Running model: None").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Running model: Not reported").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Running model: imported-only").assertDoesNotExist()
    }
}
