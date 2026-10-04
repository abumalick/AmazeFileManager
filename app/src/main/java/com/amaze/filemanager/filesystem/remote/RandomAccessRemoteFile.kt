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

import java.io.Closeable
import java.io.IOException

/** A file on a remote server, kept open for positional reads. */
interface RandomAccessRemoteFile : Closeable {
    @Throws(IOException::class)
    fun length(): Long

    /**
     * Reads up to [length] bytes starting at [position] in the file into [buffer] at [offset].
     *
     * @return the number of bytes read, which may be less than requested, or -1 at end of file
     */
    @Throws(IOException::class)
    fun read(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int
}
