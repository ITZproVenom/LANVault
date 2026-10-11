package com.lanvault

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.lanvault.net.EventLog

/**
 * Trampoline used to grant a persistent SAF tree for a drive the TV does not expose on the
 * filesystem. Launched from the web interface so the TV only shows the system picker briefly.
 */
class TreePickerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
        runCatching { startActivityForResult(intent, REQUEST_PICK_TREE) }
            .onFailure {
                EventLog.shared.error("Could not open the drive picker: ${it.message}")
                finish()
            }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_TREE && resultCode == RESULT_OK && data?.data != null) {
            val uri: Uri = data.data!!
            AppRuntime.handlePickedTree(uri, displayName(data))
            EventLog.shared.info("Drive added to the share.")
        } else {
            EventLog.shared.info("Drive picker dismissed without selecting a drive.")
        }
        finish()
    }

    private fun displayName(data: Intent?): String? = runCatching {
        val uri = data?.data ?: return null
        val documentId = android.provider.DocumentsContract.getTreeDocumentId(uri)
        documentId.substringAfterLast(':').takeIf { it.isNotBlank() }
    }.getOrNull()

    companion object {
        const val REQUEST_PICK_TREE = 4210
    }
}
