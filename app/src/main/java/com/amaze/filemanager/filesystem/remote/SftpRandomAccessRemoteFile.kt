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

import com.amaze.filemanager.filesystem.ftp.NetCopyClientUtils.extractRemotePathFrom
import com.amaze.filemanager.filesystem.ssh.SFtpClientTemplate
import com.amaze.filemanager.filesystem.ssh.SshClientUtils
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.IOException

/**
 * [RandomAccessRemoteFile] on an SFTP server.
 *
 * Other operations may disconnect the pooled SSH client while this file is held open, so a
 * failed call reopens the file once before giving up.
 */
class SftpRandomAccessRemoteFile private constructor(
    private val url: String,
    private var handle: Handle,
) : RandomAccessRemoteFile {
    override fun length(): Long = retryingOnce { it.file.length() }

    override fun read(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = retryingOnce { it.file.read(position, buffer, offset, length) }

    override fun close() = handle.close()

    private fun <T> retryingOnce(operation: (Handle) -> T): T {
        return try {
            operation(handle)
        } catch (e: IOException) {
            LOG.warn("Reopening {} after failure", extractRemotePathFrom(url), e)
            handle.close()
            handle = openHandle(url)
            operation(handle)
        }
    }

    private class Handle(val client: SFTPClient, val file: RemoteFile) : Closeable {
        override fun close() {
            try {
                file.close()
            } catch (e: IOException) {
                LOG.warn("Error closing remote file", e)
            }
            try {
                client.close()
            } catch (e: IOException) {
                LOG.warn("Error closing SFTP client", e)
            }
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(SftpRandomAccessRemoteFile::class.java)

        /** @param url full SFTP URL, as in [com.amaze.filemanager.filesystem.HybridFile.getPath] */
        @JvmStatic
        @Throws(IOException::class)
        fun open(url: String): SftpRandomAccessRemoteFile = SftpRandomAccessRemoteFile(url, openHandle(url))

        private fun openHandle(url: String): Handle {
            return SshClientUtils.execute(
                object : SFtpClientTemplate<Handle>(url, false) {
                    override fun execute(client: SFTPClient): Handle {
                        try {
                            return Handle(client, client.open(extractRemotePathFrom(url)))
                        } catch (e: IOException) {
                            client.close()
                            throw e
                        }
                    }
                },
            ) ?: throw IOException("Cannot open ${extractRemotePathFrom(url)}")
        }
    }
}
