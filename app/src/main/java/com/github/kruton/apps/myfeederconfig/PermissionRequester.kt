package com.github.kruton.apps.myfeederconfig

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Parcelable
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.parcelize.Parcelize
import java.util.concurrent.ConcurrentHashMap

@Parcelize
class PermissionResult(val permission: String, val state: State) : Parcelable
enum class State { GRANTED, DENIED_TEMPORARILY, DENIED_PERMANENTLY }
typealias Cancellable = () -> Unit
private const val PERMISSIONS_ARGUMENT_KEY = "PERMISSIONS_ARGUMENT_KEY"
private const val REQUEST_CODE_ARGUMENT_KEY = "REQUEST_CODE_ARGUMENT_KEY"

object PermissionRequester {
    private val callbackMap = ConcurrentHashMap<Int, (List<PermissionResult>) -> Unit>(1)
    private var nextRequestCode = 0

    fun requestPermissions(context: Context, vararg permissions: String, callback: (List<PermissionResult>) -> Unit): Cancellable {
        val requestCode = nextRequestCode++
        val intent = Intent(context, PermissionRequestActivity::class.java)
            .putExtra(PERMISSIONS_ARGUMENT_KEY, permissions)
            .putExtra(REQUEST_CODE_ARGUMENT_KEY, requestCode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        callbackMap[requestCode] = callback
        context.startActivity(intent)
        return { callbackMap.remove(requestCode) }
    }

    internal fun onPermissionResult(responses: List<PermissionResult>, requestCode: Int) {
        callbackMap[requestCode]?.invoke(responses)
        callbackMap.remove(requestCode)
    }
}

class PermissionRequestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            requestPermissions()
        }
    }

    private fun requestPermissions() {
        val permissions = intent?.getStringArrayExtra(PERMISSIONS_ARGUMENT_KEY) ?: arrayOf()
        val requestCode = intent?.getIntExtra(REQUEST_CODE_ARGUMENT_KEY, -1) ?: -1
        when {
            permissions.isNotEmpty() && requestCode != -1 -> ActivityCompat.requestPermissions(this, permissions, requestCode)
            else -> finishWithResult()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        val permissionResults = grantResults.zip(permissions).map { (grantResult, permission) ->
            val state = when {
                grantResult == PackageManager.PERMISSION_GRANTED -> State.GRANTED
                ActivityCompat.shouldShowRequestPermissionRationale(this, permission) -> State.DENIED_TEMPORARILY
                else -> State.DENIED_PERMANENTLY
            }
            PermissionResult(permission, state)
        }

        finishWithResult(permissionResults)
    }

    private fun finishWithResult(permissionResult: List<PermissionResult> = listOf()) {
        val requestCode = intent?.getIntExtra(REQUEST_CODE_ARGUMENT_KEY, -1) ?: -1
        PermissionRequester.onPermissionResult(permissionResult, requestCode)
        finish()
    }
}
