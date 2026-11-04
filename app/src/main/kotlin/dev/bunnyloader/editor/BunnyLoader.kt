package dev.bunnyloader.editor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract

/**
 * The conversation with Bunny Loader (com.bunnyloader), which is another APK.
 * Everything goes through its EditorBridgeActivity, open only to apps with the
 * same signature.
 */
object BunnyLoader {
    const val PACKAGE = "com.bunnyloader"
    private const val BRIDGE = "dev.bunnyloader.EditorBridgeActivity"
    private const val ACTION_CONNECT = "com.bunnyloader.action.CONNECT_EDITOR"
    private const val ACTION_TEST = "com.bunnyloader.action.TEST_SOURCE"
    private const val EXTRA_PROJECT = "project"
    private const val EXTRA_ERROR = "error"
    private const val EXTRA_ERROR_CODE = "errorCode"
    const val RESULT_ERROR = android.app.Activity.RESULT_FIRST_USER

    /** Bunny Loader's ModSources folder, to open the folder picker right there. */
    val SOURCES_ROOT: Uri = DocumentsContract.buildDocumentUri("com.bunnyloader.documents", "mod-sources")

    enum class Status { READY, NOT_INSTALLED, OUTDATED, WRONG_SIGNATURE }

    fun status(context: Context): Status {
        val pm = context.packageManager
        try {
            pm.getPackageInfo(PACKAGE, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return Status.NOT_INSTALLED
        }
        if (pm.resolveActivity(connectIntent(), 0) == null) return Status.OUTDATED
        if (pm.checkSignatures(context.packageName, PACKAGE) != PackageManager.SIGNATURE_MATCH) {
            return Status.WRONG_SIGNATURE
        }
        return Status.READY
    }

    fun connectIntent(): Intent = Intent(ACTION_CONNECT).setComponent(ComponentName(PACKAGE, BRIDGE))

    fun testIntent(project: String): Intent = Intent(ACTION_TEST)
        .setComponent(ComponentName(PACKAGE, BRIDGE))
        .putExtra(EXTRA_PROJECT, project)

    /** What Test returned: null worked; otherwise, the error code and text. */
    data class TestFailure(val code: String, val message: String)

    fun testFailure(resultCode: Int, data: Intent?): TestFailure? = when (resultCode) {
        android.app.Activity.RESULT_OK -> null
        RESULT_ERROR -> TestFailure(
            data?.getStringExtra(EXTRA_ERROR_CODE).orEmpty(),
            data?.getStringExtra(EXTRA_ERROR).orEmpty(),
        )
        else -> TestFailure("cancelled", "")
    }

    /**
     * Packages, installs and enables the project in Bunny Loader, without a screen:
     * "saving installs". Blocks; call it off the main thread. Null worked;
     * otherwise, the error text.
     */
    fun install(context: Context, project: String): String? = try {
        val result = context.contentResolver.call(BRIDGE_PROVIDER, "install", project, null)
        if (result?.getBoolean("ok") == true) null else result?.getString("error") ?: "No answer from Bunny Loader"
    } catch (_: IllegalArgumentException) {
        // Unknown provider: an old Bunny Loader, from before saving installed.
        context.getString(R.string.Outdated)
    } catch (e: SecurityException) {
        context.getString(R.string.WrongSignature)
    }

    private val BRIDGE_PROVIDER: Uri = Uri.parse("content://com.bunnyloader.editorbridge")

    fun openLauncher(context: Context) {
        context.packageManager.getLaunchIntentForPackage(PACKAGE)?.let { context.startActivity(it) }
    }
}
