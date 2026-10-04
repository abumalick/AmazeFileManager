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

import android.os.Build.VERSION_CODES.P
import android.os.Build.VERSION_CODES.R
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(AndroidJUnit4::class)
@Config(sdk = [P, R])
class RemoteFileProxyCallbackTest {
    private val content = ByteArray(25) { it.toByte() }

    @Test
    fun testOnGetSizeReturnsSizeGivenAtOpen() {
        assertEquals(25L, RemoteFileProxyCallback(ChunkedFile(content), 25L) {}.onGetSize())
    }

    /** FUSE treats a short read as end of file, so partial remote reads must be combined. */
    @Test
    fun testOnReadFillsBufferFromShortRemoteReads() {
        val callback = RemoteFileProxyCallback(ChunkedFile(content, maxChunk = 3), 25L) {}
        val buffer = ByteArray(10)
        assertEquals(10, callback.onRead(5L, 10, buffer))
        assertArrayEquals(content.copyOfRange(5, 15), buffer)
    }

    @Test
    fun testOnReadStopsAtEndOfFile() {
        val callback = RemoteFileProxyCallback(ChunkedFile(content, maxChunk = 3), 25L) {}
        assertEquals(5, callback.onRead(20L, 10, ByteArray(10)))
    }

    @Test
    fun testOnReadReportsRemoteFailureAsEio() {
        val callback = RemoteFileProxyCallback(ChunkedFile(content, failReads = true), 25L) {}
        val e = assertThrows(ErrnoException::class.java) { callback.onRead(0L, 10, ByteArray(10)) }
        assertEquals(OsConstants.EIO, e.errno)
    }

    @Test
    fun testOnReleaseClosesFileAndNotifies() {
        val file = ChunkedFile(content)
        var released = false
        RemoteFileProxyCallback(file, 25L) { released = true }.onRelease()
        assertTrue(file.closed)
        assertTrue(released)
    }

    private class ChunkedFile(
        private val content: ByteArray,
        private val maxChunk: Int = Int.MAX_VALUE,
        private val failReads: Boolean = false,
    ) : RandomAccessRemoteFile {
        var closed = false

        override fun length(): Long = content.size.toLong()

        override fun read(
            position: Long,
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (failReads) throw IOException("connection lost")
            if (position >= content.size) return -1
            val count = minOf(length, maxChunk, content.size - position.toInt())
            content.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }

        override fun close() {
            closed = true
        }
    }
}
