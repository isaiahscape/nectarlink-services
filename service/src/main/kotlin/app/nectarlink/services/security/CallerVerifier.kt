// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.services.security

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Process
import app.nectarlink.services.R
import app.nectarlink.services.storage.ServicesPrefs
import java.security.MessageDigest

class CallerVerifier(context: Context) {
    enum class TrustKind {
        BUILTIN_CERT,
        SAME_AS_SERVICE,
        USER_APPROVED,
        UNTRUSTED,
    }

    data class ClientSummary(
        val packageName: String,
        val certSha256: String,
        val trust: TrustKind,
    ) {
        val isAuthorized: Boolean get() = trust != TrustKind.UNTRUSTED
    }

    private val appContext = context.applicationContext
    private val pm = appContext.packageManager

    val allowedPackages: Set<String> = appContext.resources
        .getStringArray(R.array.allowed_client_packages)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    private val trustedCertDigests: Set<String> = appContext.resources
        .getStringArray(R.array.trusted_client_cert_sha256)
        .map { it.trim().lowercase().replace(":", "") }
        .filter { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
        .toSet()

    private val ownCertDigests: Set<String> by lazy {
        signingCertSha256List(appContext.packageName).toSet()
    }

    fun isCallingUidAuthorized(uid: Int = Binder.getCallingUid()): Boolean {
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID) return true
        val packages = pm.getPackagesForUid(uid) ?: return false
        return packages.any { pkg -> pkg in allowedPackages && inspectPackage(pkg)?.isAuthorized == true }
    }

    fun requireAuthorized(featureEnabled: Boolean = true) {
        if (!ServicesPrefs.isServiceEnabled(appContext)) {
            throw SecurityException("Nectarlink Services is disabled by the user")
        }
        if (!featureEnabled) {
            throw SecurityException("Requested Nectarlink Services capability is disabled")
        }
        if (!isCallingUidAuthorized()) {
            throw SecurityException("Caller UID ${Binder.getCallingUid()} is not an authorized NectarLink client")
        }
    }

    fun inspectInstalledClients(): List<ClientSummary> =
        allowedPackages.mapNotNull { inspectPackage(it) }

    fun inspectPackage(packageName: String): ClientSummary? {
        if (packageName !in allowedPackages) return null
        val certs = signingCertSha256List(packageName)
        if (certs.size != 1) return null
        val sha = certs[0]
        val trust = when {
            sha in trustedCertDigests -> TrustKind.BUILTIN_CERT
            sha in ownCertDigests -> TrustKind.SAME_AS_SERVICE
            ServicesPrefs.isClientApproved(appContext, packageName, sha) -> TrustKind.USER_APPROVED
            else -> TrustKind.UNTRUSTED
        }
        return ClientSummary(packageName, sha, trust)
    }

    private fun signingCertSha256List(packageName: String): List<String> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info: PackageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo ?: return emptyList()
            if (signingInfo.hasMultipleSigners()) return emptyList()
            signingInfo.apkContentsSigners.map { sha256Hex(it.toByteArray()) }
        } else {
            @Suppress("DEPRECATION")
            val info: PackageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            val sigs = info.signatures ?: return emptyList()
            if (sigs.size != 1) return emptyList()
            listOf(sha256Hex(sigs[0].toByteArray()))
        }
    }.getOrDefault(emptyList())

    companion object {
        internal fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
