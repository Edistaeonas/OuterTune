package com.dd3boh.outertune.utils.scanners

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.TreeDocumentFileOt
import java.io.File

fun documentFileFromUri(context: Context, uris: List<Uri>): List<DocumentFile> {
    return uris.map { customDocFileFromTreeTreeUri(context, it) }.filter { it.isDirectory }
}

fun documentFileFromUri(context: Context, uri: Uri): DocumentFile? {
    return customDocFileFromTreeTreeUri(context, uri)
}

fun stringFromUriList(uris: List<Uri>): String {
    if (uris.isEmpty()) return ""
    return uris.distinctBy { it.toString() }.joinToString("\n")
}

fun uriListFromString(str: String): List<Uri> {
    return str.split("\n").map { it.toUri() }.filter { it.toString().isNotBlank() }.distinctBy { it.toString() }
}

fun fileFromUriOLD(context: Context, uri: Uri): File? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (!DocumentsContract.isTreeUri(uri)) return null
        if (uri.authority != "com.android.externalstorage.documents") return null

        val treeDocId = DocumentsContract.getDocumentId(uri)
        val rootId: String
        val relativePath: String

        if (treeDocId.contains(":")) {
            val parts = treeDocId.split(":", limit = 2)
            rootId = parts[0]
            relativePath = parts[1]
        } else {
            rootId = treeDocId
            relativePath = ""
        }

        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager

        val rootDir = if (rootId.equals("primary", ignoreCase = true)) {
            storageManager.primaryStorageVolume.directory
        } else {
            storageManager.storageVolumes.firstOrNull {
                it.uuid != null && it.uuid.equals(rootId, ignoreCase = true)
            }?.directory
        }

        return rootDir?.let { if (relativePath.isEmpty()) it else File(it, relativePath) }
    } else {
        if (!DocumentsContract.isDocumentUri(context, uri)) return null

        if (uri.authority != "com.android.externalstorage.documents") return null

        val docId = DocumentsContract.getDocumentId(uri)
        val parts = docId.split(":")

        if (parts.size < 2) return null

        val type = parts[0]
        val relativePath = parts[1]

        val rootDir = when (type.lowercase()) {
            "primary" -> Environment.getExternalStorageDirectory()
            else -> {
                // Try to handle secondary storage
                val secondaryStorage = "/storage/$type"
                if (File(secondaryStorage).exists()) {
                    File(secondaryStorage)
                } else {
                    null
                }
            }
        }

        return rootDir?.let { File(it, relativePath) }
    }
}

fun fileFromUri(context: Context, uri: Uri): File? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // Resolve the document ID from the URI
        val docId = try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.getDocumentId(uri)
            } else if (DocumentsContract.isTreeUri(uri)) {
                DocumentsContract.getTreeDocumentId(uri)
            } else {
                // For direct file:// uris or other providers
                if (uri.scheme == "file") return uri.path?.let { File(it) }
                return null
            }
        } catch (e: Exception) {
            return null
        }

        val rootId: String
        val relativePath: String

        if (docId.contains(":")) {
            val parts = docId.split(":", limit = 2)
            rootId = parts[0]
            relativePath = parts[1]
        } else {
            rootId = docId
            relativePath = ""
        }

        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager

        val rootDir = if (rootId.equals("primary", ignoreCase = true)) {
            storageManager.primaryStorageVolume.directory
        } else {
            storageManager.storageVolumes.firstOrNull {
                it.uuid != null && it.uuid.equals(rootId, ignoreCase = true)
            }?.directory
        }

        return rootDir?.let { if (relativePath.isEmpty()) it else File(it, relativePath) }
    } else {
        // Fallback for older Android versions
        if (!DocumentsContract.isDocumentUri(context, uri)) {
            if (uri.scheme == "file") return uri.path?.let { File(it) }
            return null
        }

        val docId = DocumentsContract.getDocumentId(uri)
        val parts = docId.split(":")
        if (parts.size < 2) return null

        val type = parts[0]
        val relativePath = parts[1]

        val rootDir = if ("primary".equals(type, ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage/$type")
        }

        return rootDir?.let { File(it, relativePath) }
    }
}


fun absoluteFilePathFromUri(context: Context, uri: Uri): String? {
//    val dfUri = documentFileFromUri(context, uri)?.uri
//    if (dfUri == null) return null
//    return fileFromUri(context, dfUri)?.absolutePath
    // FIX: resolve directly from the uri without the incorrect wrapping in customDocFileFromTreeTreeUri
    return fileFromUri(context, uri)?.absolutePath
}

private fun customDocFileFromTreeTreeUri(context: Context, uri: Uri) = TreeDocumentFileOt(
    null, context, DocumentsContract.buildDocumentUriUsingTree(
        uri, DocumentsContract.getTreeDocumentId(uri)
    )
)
