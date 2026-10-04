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

import android.os.Build
import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import androidx.annotation.RequiresApi
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * Serves reads on a proxy file descriptor from a [RandomAccessRemoteFile].
 *
 * @param size file size reported to readers, fetched once when the file was opened
 * @param onReleased called after the descriptor is closed and [file] has been closed
 */
@RequiresApi(Build.VERSION_CODES.O)
class RemoteFileProxyCallback(
    private val file: RandomAccessRemoteFile,
    private val size: Long,
    private val onReleased: () -> Unit,
) : ProxyFileDescriptorCallback() {
    override fun onGetSize(): Long = size

    override fun onRead(
        offset: Long,
        size: Int,
        data: ByteArray,
    ): Int {
        var total = 0
        try {
            // A short read means end of file to FUSE, so keep reading until it is full.
            while (total < size) {
                val read = file.read(offset + total, data, total, size - total)
                if (read <= 0) break
                total += read
            }
        } catch (e: IOException) {
            LOG.warn("Failed to read remote file at offset {}", offset, e)
            throw ErrnoException("onRead", OsConstants.EIO)
        }
        return total
    }

    override fun onRelease() {
        try {
            file.close()
        } catch (e: IOException) {
            LOG.warn("Failed to close remote file", e)
        }
        onReleased()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(RemoteFileProxyCallback::class.java)
    }
}
