package io.github.drtron1c.cloudphotos.identity

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.drtron1c.cloudphotos.CloudPhotosApp
import io.github.drtron1c.cloudphotos.settings.AppIdentity
import io.github.drtron1c.cloudphotos.settings.Keys
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Your own app name and picture. Android never lets an installed app rename itself or change
 * its own icon to something arbitrary, so Cloud Photos builds a tiny companion app on the
 * device with the name and picture you choose; opening it opens Cloud Photos.
 */
object CustomLauncher {
    const val PACKAGE = LauncherApk.PACKAGE
    private const val KEY_ALIAS = "cloudphotos-companion-signing"
    private const val ACTION_RESULT = "io.github.drtron1c.cloudphotos.COMPANION_INSTALL_RESULT"

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Messages about installs finishing / failing, for the UI to show. */
    val events: SharedFlow<String> = _events

    // ---------- saved choice ----------

    fun iconFile(context: Context) = File(context.filesDir, "identity/icon.png")

    fun savedIcon(context: Context): Bitmap? =
        iconFile(context).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    fun saveIcon(context: Context, icon: Bitmap): ByteArray {
        val png = ByteArrayOutputStream().also { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val f = iconFile(context)
        f.parentFile?.mkdirs()
        f.writeBytes(png)
        return png
    }

    // ---------- state of the companion app ----------

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0); true
    }.getOrDefault(false)

    fun installedLabel(context: Context): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(PACKAGE, 0)).toString()
    }.getOrNull()

    /** False when a companion app signed with a different key is installed (e.g. after clearing app data). */
    fun canUpdateInstalled(context: Context): Boolean = runCatching {
        if (!isInstalled(context)) return true
        val info = context.packageManager.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners ?: return true
        val ours = signingKey().second.encoded
        signers.any { it.toByteArray().contentEquals(ours) }
    }.getOrDefault(true)

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** Settings page where you allow Cloud Photos to install apps. */
    fun allowInstallsIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun uninstallIntent() = Intent(Intent.ACTION_DELETE, Uri.parse("package:$PACKAGE"))

    // ---------- building & installing ----------

    /** Signing key that is created on, and never leaves, this device. */
    private fun signingKey(): Pair<PrivateKey, X509Certificate> {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) {
            val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            val now = System.currentTimeMillis()
            gen.initialize(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=Cloud Photos companion"))
                    .setCertificateSerialNumber(BigInteger.valueOf(now))
                    .setCertificateNotBefore(Date(now - 24L * 3600 * 1000))
                    .setCertificateNotAfter(Date(now + 40L * 365 * 24 * 3600 * 1000))
                    .build()
            )
            gen.generateKeyPair()
        }
        val key = ks.getKey(KEY_ALIAS, null) as PrivateKey
        val cert = ks.getCertificate(KEY_ALIAS) as X509Certificate
        return key to cert
    }

    /** Builds the companion app (slow-ish: run off the main thread). */
    fun make(context: Context, name: String, iconPng: ByteArray): File {
        val dir = File(context.cacheDir, "companion").apply { deleteRecursively(); mkdirs() }
        val template = File(dir, "template.apk")
        context.assets.open("launcher-template.apk").use { input -> template.outputStream().use { input.copyTo(it) } }
        val out = File(dir, "companion.apk")
        val (key, cert) = signingKey()
        LauncherApk.build(template, name, iconPng, key, cert, out)
        template.delete()
        return out
    }

    /** Hands the app to Android's installer; Android asks you to confirm. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(PACKAGE)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = Intent(context, InstallResultReceiver::class.java).setAction(ACTION_RESULT)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, result, flags).intentSender)
        }
        apk.delete()
    }

    internal fun onInstallResult(context: Context, status: Int, message: String?) {
        val settings = (context.applicationContext as CloudPhotosApp).graph.settings
        val text = when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                if (settings.hideOriginalIcon) AppIdentity.showOriginal(context, settings, false)
                "“${installedLabel(context) ?: settings.customName}” is on your home screen"
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Install cancelled"
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "A companion app made earlier (with a different key) is in the way — remove it, then try again"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the install" + (message?.let { ": $it" } ?: "")
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install"
            else -> "Couldn't install" + (message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        }
        settings.raw().edit().putString(Keys.COMPANION_STATUS, text).apply()
        _events.tryEmit(text)
    }

    /**
     * Never leave you without a way into the app: if the original icon is hidden but the
     * companion app is gone, bring the original icon back.
     */
    fun reconcile(context: Context) {
        val settings = (context.applicationContext as CloudPhotosApp).graph.settings
        if (!settings.hideOriginalIcon) return
        AppIdentity.showOriginal(context, settings, !isInstalled(context))
    }
}

/** Receives the installer's answer for the companion app. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(confirm) }
            return
        }
        CustomLauncher.onInstallResult(context, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}

/** Brings the original icon back if the companion app is uninstalled. */
class LauncherWatcher : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_PACKAGE_FULLY_REMOVED && intent.data?.schemeSpecificPart != CustomLauncher.PACKAGE) return
        CustomLauncher.reconcile(context)
    }
}
