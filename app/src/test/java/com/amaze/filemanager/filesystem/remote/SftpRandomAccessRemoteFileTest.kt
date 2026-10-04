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

import android.os.Environment
import com.amaze.filemanager.filesystem.ftp.NetCopyClientConnectionPool
import com.amaze.filemanager.filesystem.ssh.AbstractSftpServerTest
import com.amaze.filemanager.test.randomBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

class SftpRandomAccessRemoteFileTest : AbstractSftpServerTest() {
    private val content = randomBytes(200 * 1024)

    private val remoteUrl
        get() = "ssh://$USERNAME:$encryptedPassword@$HOST:$serverPort/docs/document.pdf"

    @Before
    override fun setUp() {
        super.setUp()
        File(Environment.getExternalStorageDirectory(), "docs").run {
            mkdirs()
            File(this, "document.pdf").writeBytes(content)
        }
    }

    @Test
    fun testLengthIsRemoteFileSize() {
        SftpRandomAccessRemoteFile.open(remoteUrl).use {
            assertEquals(content.size.toLong(), it.length())
        }
    }

    @Test
    fun testReadsAtArbitraryPositions() {
        SftpRandomAccessRemoteFile.open(remoteUrl).use { file ->
            for (position in listOf(150_000, 0, content.size - 10)) {
                val buffer = ByteArray(10)
                assertEquals(10, file.read(position.toLong(), buffer, 0, 10))
                assertArrayEquals(content.copyOfRange(position, position + 10), buffer)
            }
        }
    }

    @Test
    fun testReadAtEndOfFileReturnsEof() {
        SftpRandomAccessRemoteFile.open(remoteUrl).use {
            assertEquals(-1, it.read(content.size.toLong(), ByteArray(10), 0, 10))
        }
    }

    /**
     * Other operations may disconnect the pooled SSH client while a viewer still holds the
     * file open, so a read must reconnect rather than fail.
     */
    @Test
    fun testReadSucceedsAfterPooledConnectionIsClosed() {
        SftpRandomAccessRemoteFile.open(remoteUrl).use { file ->
            NetCopyClientConnectionPool.shutdown()
            val buffer = ByteArray(10)
            assertEquals(10, file.read(100L, buffer, 0, 10))
            assertArrayEquals(content.copyOfRange(100, 110), buffer)
        }
    }
}
