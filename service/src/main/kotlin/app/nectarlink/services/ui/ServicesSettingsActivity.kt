// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.ui

import android.Manifest
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import app.nectarlink.services.R
import app.nectarlink.services.security.CallerVerifier
import app.nectarlink.services.storage.ServicesPrefs

class ServicesSettingsActivity : Activity() {
    private lateinit var verifier: CallerVerifier
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        verifier = CallerVerifier(this)
        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(container)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        container.removeAllViews()
        val isPriv = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

        addHeader(getString(R.string.settings_title))
        addBody(
            if (isPriv) getString(R.string.status_privileged_yes)
            else getString(R.string.status_privileged_no),
        )

        addSwitchRow(
            title = getString(R.string.toggle_master),
            subtitle = getString(R.string.toggle_master_desc),
            checked = ServicesPrefs.isServiceEnabled(this),
        ) { on -> ServicesPrefs.setServiceEnabled(this, on) }

        addSwitchRow(
            title = getString(R.string.toggle_input),
            subtitle = null,
            checked = ServicesPrefs.isInputEnabled(this),
        ) { on -> ServicesPrefs.setInputEnabled(this, on) }

        addSwitchRow(
            title = getString(R.string.toggle_display),
            subtitle = null,
            checked = ServicesPrefs.isDisplayEnabled(this),
        ) { on -> ServicesPrefs.setDisplayEnabled(this, on) }

        addSwitchRow(
            title = getString(R.string.toggle_audio),
            subtitle = null,
            checked = ServicesPrefs.isAudioEnabled(this),
        ) { on -> ServicesPrefs.setAudioEnabled(this, on) }

        addSwitchRow(
            title = getString(R.string.toggle_clipboard),
            subtitle = null,
            checked = ServicesPrefs.isClipboardEnabled(this),
        ) { on -> ServicesPrefs.setClipboardEnabled(this, on) }

        addSwitchRow(
            title = getString(R.string.toggle_system),
            subtitle = null,
            checked = ServicesPrefs.isSystemEnabled(this),
        ) { on -> ServicesPrefs.setSystemEnabled(this, on) }

        addHeader(getString(R.string.section_clients))
        val clients = verifier.inspectInstalledClients()
        if (clients.isEmpty()) {
            addBody(getString(R.string.no_clients_found))
        } else {
            for (client in clients) {
                addClientRow(client)
            }
        }

        addHeader(getString(R.string.section_permissions))
        val perms = listOf(
            Manifest.permission.INJECT_EVENTS,
            Manifest.permission.CAPTURE_VIDEO_OUTPUT,
            "android.permission.ADD_TRUSTED_DISPLAY",
            "android.permission.CREATE_VIRTUAL_DEVICE",
            "android.permission.DEVICE_POWER",
            Manifest.permission.CAPTURE_AUDIO_OUTPUT,
            "android.permission.CALL_AUDIO_INTERCEPTION",
            "android.permission.READ_CLIPBOARD_IN_BACKGROUND",
            "android.permission.START_ACTIVITIES_FROM_BACKGROUND",
            "android.permission.DELETE_PACKAGES",
            "android.permission.TETHER_PRIVILEGED",
            Manifest.permission.WRITE_SECURE_SETTINGS,
        )
        for (perm in perms) {
            val granted = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
            val shortName = perm.removePrefix("android.permission.")
            addBody("${if (granted) "[OK]" else "[MISSING]"} $shortName")
        }
    }

    private fun addClientRow(client: CallerVerifier.ClientSummary) {
        val statusText = when (client.trust) {
            CallerVerifier.TrustKind.BUILTIN_CERT -> getString(R.string.client_trusted_builtin)
            CallerVerifier.TrustKind.SAME_AS_SERVICE -> getString(R.string.client_trusted_same_cert)
            CallerVerifier.TrustKind.USER_APPROVED -> getString(R.string.client_trusted_user)
            CallerVerifier.TrustKind.UNTRUSTED -> getString(R.string.client_untrusted)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(TextView(this).apply {
            text = "${client.packageName} — $statusText"
            textSize = 15f
        })
        row.addView(TextView(this).apply {
            text = "SHA-256: ${client.certSha256.chunked(8).joinToString(" ")}"
            textSize = 11f
        })
        if (client.trust == CallerVerifier.TrustKind.UNTRUSTED) {
            row.addView(Button(this).apply {
                text = getString(R.string.action_approve)
                setOnClickListener {
                    ServicesPrefs.approveClient(this@ServicesSettingsActivity, client.packageName, client.certSha256)
                    render()
                }
            })
        } else if (client.trust == CallerVerifier.TrustKind.USER_APPROVED) {
            row.addView(Button(this).apply {
                text = getString(R.string.action_revoke)
                setOnClickListener {
                    ServicesPrefs.revokeClient(this@ServicesSettingsActivity, client.packageName, client.certSha256)
                    render()
                }
            })
        }
        container.addView(row)
    }

    private fun addHeader(title: String) {
        container.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setPadding(0, dp(14), 0, dp(6))
        })
    }

    private fun addBody(text: String) {
        container.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setPadding(0, dp(2), 0, dp(2))
        })
    }

    private fun addSwitchRow(title: String, subtitle: String?, checked: Boolean, onToggle: (Boolean) -> Unit) {
        val sw = Switch(this).apply {
            text = if (subtitle != null) "$title\n$subtitle" else title
            isChecked = checked
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        }
        container.addView(sw)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
