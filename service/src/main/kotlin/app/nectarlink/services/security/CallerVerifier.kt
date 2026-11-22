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

    private val trustedCertDigests: List<ByteArray> = appContext.resources
        .getStringArray(R.array.trusted_client_cert_sha256)
        .mapNotNull(::decodeSha256Hex)

    private val ownCertDigests: List<ByteArray> by lazy {
        signingCertSha256BytesList(appContext.packageName)
    }

    fun isCallingUidAuthorized(uid: Int = Binder.getCallingUid()): Boolean {
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID) return true
        val packages = pm.getPackagesForUid(uid) ?: return false
        return packages.any { pkg -> pkg in allowedPackages && trustKindFor(pkg) != TrustKind.UNTRUSTED }
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
        val certs = signingCertSha256BytesList(packageName)
        if (certs.size != 1) return null
        val sha = certs[0].joinToString("") { "%02x".format(it) }
        return ClientSummary(packageName, sha, trustKindFor(packageName))
    }

    private fun trustKindFor(packageName: String): TrustKind {
        if (trustedCertDigests.any { hasSigningCertificate(packageName, it) }) {
            return TrustKind.BUILTIN_CERT
        }
        if (ownCertDigests.any { hasSigningCertificate(packageName, it) }) {
            return TrustKind.SAME_AS_SERVICE
        }
        val prefix = "$packageName:"
        val approvedDigests = ServicesPrefs.approvedClients(appContext)
            .asSequence()
            .filter { it.startsWith(prefix) }
            .mapNotNull { decodeSha256Hex(it.removePrefix(prefix)) }
            .toList()
        if (approvedDigests.any { hasSigningCertificate(packageName, it) }) {
            return TrustKind.USER_APPROVED
        }
        return TrustKind.UNTRUSTED
    }

    private fun hasSigningCertificate(packageName: String, certSha256: ByteArray): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.hasSigningCertificate(packageName, certSha256, PackageManager.CERT_INPUT_SHA256)
        } else {
            val certs = signingCertSha256BytesList(packageName)
            certs.size == 1 && MessageDigest.isEqual(certs[0], certSha256)
        }
    }.getOrDefault(false)

    private fun signingCertSha256BytesList(packageName: String): List<ByteArray> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info: PackageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo ?: return emptyList()
            if (signingInfo.hasMultipleSigners()) return emptyList()
            signingInfo.apkContentsSigners.map { sha256Bytes(it.toByteArray()) }
        } else {
            @Suppress("DEPRECATION")
            val info: PackageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            val sigs = info.signatures ?: return emptyList()
            if (sigs.size != 1) return emptyList()
            listOf(sha256Bytes(sigs[0].toByteArray()))
        }
    }.getOrDefault(emptyList())

    companion object {
        internal fun sha256Bytes(bytes: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(bytes)

        internal fun sha256Hex(bytes: ByteArray): String =
            sha256Bytes(bytes).joinToString("") { "%02x".format(it) }

        internal fun decodeSha256Hex(hex: String): ByteArray? {
            val clean = hex.trim().lowercase().replace(":", "")
            if (clean.length != 64 || !clean.all { it in '0'..'9' || it in 'a'..'f' }) return null
            return ByteArray(32) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
