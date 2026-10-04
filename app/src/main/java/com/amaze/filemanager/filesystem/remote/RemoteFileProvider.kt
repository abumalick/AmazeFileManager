/*
 * Copyright (C) 2014-2026 Arpit Khurana <arpitkh96@gmail.com>, Vishal Nehra <vishalmeham2@gmail.com>,
 * Emmanuel Messulam<emmanuelbendavid@gmail.com>, Raymond Lai <airwave209gt at gmail.com> and Contributors.
 *
 * This file is part of Amaze File Manager.
 *
 * Amaze File Manager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.amaze.filemanager.filesystem.remote

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import com.amaze.filemanager.fileoperations.filesystem.OpenMode
import com.amaze.filemanager.ui.icons.MimeTypes
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * Hands files on SFTP and SMB servers to other apps as `content://` URIs.
 *
 * The provider is not exported: other apps reach a file only through the per-URI read grant
 * of the intent that carried it. URIs hold a random token and the display name, never the
 * server address or credentials. Name and size come from the directory listing, so [query]
 * does no network I/O. [openFile] returns a seekable descriptor that reads the remote file on
 * demand.
 *
 * Tokens live in memory: after the app process dies, the URIs it handed out stop resolving.
 */
class RemoteFileProvider : ContentProvider() {
    private data class RemoteFile(
        val mode: OpenMode,
        val path: String,
        val name: String,
        val size: Long,
    )

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = remoteFileFor(uri) ?: return null
        val columns =
            (projection ?: OPENABLE_COLUMNS).filter { it in OPENABLE_COLUMNS }.toTypedArray()
        val row =
            columns.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    else -> file.size.takeIf { size -> size >= 0 }
                }
            }
        return MatrixCursor(columns, 1).apply { addRow(row) }
    }

    override fun getType(uri: Uri): String? {
        return remoteFileFor(uri)?.let { MimeTypes.getMimeType(it.name, false) }
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (ParcelFileDescriptor.parseMode(mode) != ParcelFileDescriptor.MODE_READ_ONLY) {
            throw FileNotFoundException("Read only: $uri")
        }
        val file = remoteFileFor(uri) ?: throw FileNotFoundException("Unknown: $uri")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !canServe(file.mode)) {
            throw FileNotFoundException("Unsupported: $uri")
        }
        return openProxyFileDescriptor(file)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun openProxyFileDescriptor(file: RemoteFile): ParcelFileDescriptor {
        // One thread per descriptor, so a stalled server does not block reads of other files.
        val thread = HandlerThread("RemoteFileProvider").apply { start() }
        val handler = Handler(thread.looper)
        // This binder thread runs under the caller's StrictMode policy, which usually forbids
        // network access, so the file is opened on the descriptor's thread instead.
        val opening = FutureTask { openRemoteFile(file) }
        handler.post(opening)
        val (remoteFile, size) =
            try {
                opening.get()
            } catch (e: ExecutionException) {
                thread.quitSafely()
                throw FileNotFoundException("Cannot open ${file.name}: ${e.cause?.message}")
            } catch (e: InterruptedException) {
                handler.post {
                    runCatching { opening.get().first.close() }
                    thread.quitSafely()
                }
                Thread.currentThread().interrupt()
                throw FileNotFoundException("Interrupted opening ${file.name}")
            }
        val callback = RemoteFileProxyCallback(remoteFile, size) { thread.quitSafely() }
        return try {
            checkNotNull(context)
                .getSystemService(StorageManager::class.java)
                .openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, handler)
        } catch (e: IOException) {
            callback.onRelease()
            throw FileNotFoundException("Cannot serve ${file.name}: ${e.message}")
        }
    }

    private fun openRemoteFile(file: RemoteFile): Pair<RandomAccessRemoteFile, Long> {
        val remoteFile =
            when (file.mode) {
                OpenMode.SFTP -> SftpRandomAccessRemoteFile.open(file.path)
                else -> SmbRandomAccessRemoteFile.open(file.path)
            }
        try {
            return remoteFile to remoteFile.length()
        } catch (e: IOException) {
            remoteFile.close()
            throw e
        }
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = throw UnsupportedOperationException("Read only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Read only")

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Read only")

    companion object {
        private val OPENABLE_COLUMNS = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)

        private val remoteFiles = ConcurrentHashMap<String, RemoteFile>()

        @JvmStatic
        fun authority(context: Context): String = "${context.packageName}.remotefiles"

        /**
         * Whether files in [mode] can be handed to other apps through this provider. Serving
         * them needs [StorageManager.openProxyFileDescriptor], added in Android 8.0.
         */
        @JvmStatic
        fun canServe(mode: OpenMode): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                (mode == OpenMode.SFTP || mode == OpenMode.SMB)

        /**
         * Registers a remote file and returns a URI other apps can read it through once granted.
         *
         * @param path full URL of the file, as in [com.amaze.filemanager.filesystem.HybridFile.getPath]
         * @param name display name reported to readers
         * @param size size from the directory listing, or a negative value if unknown
         */
        @JvmStatic
        fun uriFor(
            context: Context,
            mode: OpenMode,
            path: String,
            name: String,
            size: Long,
        ): Uri {
            val token = UUID.randomUUID().toString()
            remoteFiles[token] = RemoteFile(mode, path, name, size)
            return Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(token)
                .appendPath(name)
                .build()
        }

        private fun remoteFileFor(uri: Uri): RemoteFile? = uri.pathSegments.firstOrNull()?.let { remoteFiles[it] }
    }
}
