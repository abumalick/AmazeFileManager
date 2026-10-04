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

import com.amaze.filemanager.fileoperations.filesystem.OpenMode
import com.amaze.filemanager.filesystem.HybridFile
import jcifs.smb.SmbRandomAccessFile
import java.io.IOException

/** [RandomAccessRemoteFile] on an SMB share. */
class SmbRandomAccessRemoteFile private constructor(
    private val file: SmbRandomAccessFile,
) : RandomAccessRemoteFile {
    override fun length(): Long = file.length()

    override fun read(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        file.seek(position)
        return file.read(buffer, offset, length)
    }

    override fun close() = file.close()

    companion object {
        /** @param url full SMB URL, as in [HybridFile.getPath] */
        @JvmStatic
        @Throws(IOException::class)
        fun open(url: String): SmbRandomAccessRemoteFile {
            val smbFile = HybridFile(OpenMode.SMB, url).smbFile ?: throw IOException("Invalid SMB URL")
            return SmbRandomAccessRemoteFile(smbFile.openRandomAccess("r"))
        }
    }
}
