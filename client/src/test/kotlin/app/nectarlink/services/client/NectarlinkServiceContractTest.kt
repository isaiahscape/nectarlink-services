// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NectarlinkServiceContractTest {
    private val allowed = setOf("app.nectarlink", "app.nectarlink.dev")

    @Test
    fun uninstall_never_targets_nectarlink_or_invalid_packages() {
        assertTrue(NectarlinkServiceContract.isAllowedUninstallPackage("com.example.app", "app.nectarlink.services"))
        assertFalse(NectarlinkServiceContract.isAllowedUninstallPackage("app.nectarlink", "app.nectarlink.services"))
        assertFalse(NectarlinkServiceContract.isAllowedUninstallPackage("app.nectarlink.dev", "app.nectarlink.services"))
        assertFalse(NectarlinkServiceContract.isAllowedUninstallPackage("app.nectarlink.services", "app.nectarlink.services"))
        assertFalse(NectarlinkServiceContract.isAllowedUninstallPackage("com.example.app; rm -rf /"))
        assertFalse(NectarlinkServiceContract.isAllowedUninstallPackage("invalid"))
    }

    @Test
    fun background_launch_and_provisioning_enforce_fixed_activity_and_package() {
        val pcStart = NectarlinkServiceContract.ALLOWED_BACKGROUND_ACTIVITY
        assertTrue(NectarlinkServiceContract.isAllowedBackgroundLaunch("app.nectarlink", pcStart, allowed))
        assertFalse(NectarlinkServiceContract.isAllowedBackgroundLaunch("app.nectarlink", "app.nectarlink.android.MainActivity", allowed))
        assertFalse(NectarlinkServiceContract.isAllowedBackgroundLaunch("com.other.app", pcStart, allowed))

        assertTrue(NectarlinkServiceContract.isAllowedProvisionPackage("app.nectarlink.dev", allowed))
        assertFalse(NectarlinkServiceContract.isAllowedProvisionPackage("com.other.app", allowed))
    }

    @Test
    fun shell_command_allowlist_matches_helper_limits() {
        assertTrue(NectarlinkServiceContract.isAllowedShellCommand("cmd statusbar expand-notifications", allowed))
        assertTrue(NectarlinkServiceContract.isAllowedShellCommand("settings put system screen_brightness 200", allowed))
        assertTrue(NectarlinkServiceContract.isAllowedShellCommand("pm uninstall com.example.app", allowed))
        assertTrue(
            NectarlinkServiceContract.isAllowedShellCommand(
                "am start -n app.nectarlink/${NectarlinkServiceContract.ALLOWED_BACKGROUND_ACTIVITY}",
                allowed,
            ),
        )
        assertFalse(NectarlinkServiceContract.isAllowedShellCommand("settings put system screen_brightness 256", allowed))
        assertFalse(NectarlinkServiceContract.isAllowedShellCommand("pm uninstall app.nectarlink", allowed))
        assertFalse(NectarlinkServiceContract.isAllowedShellCommand("am start -n com.android.settings/.Settings", allowed))
        assertFalse(NectarlinkServiceContract.isAllowedShellCommand("id", allowed))
    }

    @Test
    fun supported_api_version_matches_known_range() {
        assertTrue(NectarlinkServiceContract.isSupportedApiVersion(NectarlinkServiceContract.API_VERSION))
        assertFalse(NectarlinkServiceContract.isSupportedApiVersion(0))
        assertFalse(NectarlinkServiceContract.isSupportedApiVersion(-1))
        assertFalse(NectarlinkServiceContract.isSupportedApiVersion(NectarlinkServiceContract.API_VERSION + 1))

        val full35 = NectarlinkServiceContract.requiredElevatedCapabilities(35)
        assertTrue(NectarlinkServiceContract.hasAllRequiredCapabilities(full35, 35))
        assertFalse(
            NectarlinkServiceContract.hasAllRequiredCapabilities(
                full35 and NectarlinkServiceContract.CAP_CLIPBOARD_SYNC.inv(),
                35,
            ),
        )
        assertFalse(
            NectarlinkServiceContract.hasAllRequiredCapabilities(
                full35 and NectarlinkServiceContract.CAP_APP_VIRTUAL_DISPLAY.inv(),
                35,
            ),
        )
        assertTrue(
            NectarlinkServiceContract.hasAllRequiredCapabilities(
                NectarlinkServiceContract.CORE_ELEVATED_CAPABILITIES,
                29,
            ),
        )
    }
}
