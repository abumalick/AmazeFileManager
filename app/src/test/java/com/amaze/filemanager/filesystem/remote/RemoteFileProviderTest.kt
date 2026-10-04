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

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build.VERSION_CODES.LOLLIPOP
import android.os.Build.VERSION_CODES.P
import android.os.Build.VERSION_CODES.R
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amaze.filemanager.fileoperations.filesystem.OpenMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.FileNotFoundException

@RunWith(AndroidJUnit4::class)
@Config(sdk = [P, R])
class RemoteFileProviderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var provider: RemoteFileProvider

    /** Port 1 has no server: anything that touched the network would fail or hang. */
    private val unreachableSftpPath = "ssh://alice:secret@192.0.2.1:1/home/alice/report.pdf"

    @Before
    fun setUp() {
        provider =
            Robolectric.setupContentProvider(
                RemoteFileProvider::class.java,
                RemoteFileProvider.authority(context),
            )
    }

    @Test
    fun testUriHidesConnectionDetails() {
        val uri = uriForReport(size = 1234L)
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.remotefiles", uri.authority)
        assertEquals("report.pdf", uri.lastPathSegment)
        for (secret in listOf("alice", "secret", "192.0.2.1", "home")) {
            assertFalse("URI leaks $secret: $uri", uri.toString().contains(secret))
        }
    }

    @Test
    fun testUrisForSameFileAreDistinct() {
        assertTrue(uriForReport(size = 1L) != uriForReport(size = 1L))
    }

    /** query() only reads what was registered from the listing; it never opens the file. */
    @Test
    fun testQueryReturnsListingNameAndSizeWithoutOpeningFile() {
        context.contentResolver.query(uriForReport(size = 1234L), null, null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals("report.pdf", it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(1234L, it.getLong(it.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
    }

    /** Receivers treat a negative size as a broken file; unknown must be null instead. */
    @Test
    fun testQueryReportsUnknownSizeAsNull() {
        context.contentResolver.query(uriForReport(size = -1L), null, null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertTrue(it.isNull(it.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
    }

    @Test
    fun testQueryDropsUnsupportedColumns() {
        val projection = arrayOf("_data", OpenableColumns.DISPLAY_NAME)
        context.contentResolver.query(uriForReport(size = 1L), projection, null, null, null)!!.use {
            assertArrayEquals(arrayOf(OpenableColumns.DISPLAY_NAME), it.columnNames)
        }
    }

    @Test
    fun testQueryUnknownUriReturnsNull() {
        assertNull(context.contentResolver.query(unknownUri(), null, null, null, null))
    }

    @Test
    fun testGetTypeFromName() {
        shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypMapping("pdf", "application/pdf")
        assertEquals("application/pdf", context.contentResolver.getType(uriForReport(size = 1L)))
    }

    @Test
    fun testOpenUnknownUriFails() {
        assertThrows(FileNotFoundException::class.java) {
            provider.openFile(unknownUri(), "r")
        }
    }

    @Test
    fun testOpenForWritingFails() {
        val uri = uriForReport(size = 1L)
        for (mode in listOf("w", "rw", "wa", "rwt")) {
            assertThrows(FileNotFoundException::class.java) { provider.openFile(uri, mode) }
        }
    }

    @Test
    fun testModificationsAreUnsupported() {
        val uri = uriForReport(size = 1L)
        assertThrows(UnsupportedOperationException::class.java) {
            provider.insert(uri, ContentValues())
        }
        assertThrows(UnsupportedOperationException::class.java) {
            provider.update(uri, ContentValues(), null, null)
        }
        assertThrows(UnsupportedOperationException::class.java) {
            provider.delete(uri, null, null)
        }
    }

    @Test
    fun testCanServeSftpAndSmbOnly() {
        assertTrue(RemoteFileProvider.canServe(OpenMode.SFTP))
        assertTrue(RemoteFileProvider.canServe(OpenMode.SMB))
        assertFalse(RemoteFileProvider.canServe(OpenMode.FTP))
        assertFalse(RemoteFileProvider.canServe(OpenMode.FILE))
    }

    @Test
    @Config(sdk = [LOLLIPOP])
    fun testCannotServeBeforeProxyFileDescriptorsExist() {
        assertFalse(RemoteFileProvider.canServe(OpenMode.SFTP))
        assertFalse(RemoteFileProvider.canServe(OpenMode.SMB))
    }

    private fun uriForReport(size: Long): Uri = RemoteFileProvider.uriFor(context, OpenMode.SFTP, unreachableSftpPath, "report.pdf", size)

    private fun unknownUri(): Uri = Uri.parse("content://${RemoteFileProvider.authority(context)}/no-such-token/report.pdf")
}
