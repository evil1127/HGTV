// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 一集视频的**原始（未解密）字节**落盘缓存：只接受从头开始、连续写入，
 * [filled] 之前的字节一律可以直接从磁盘读，之后的才需要走网络。
 *
 * 用途是「预先下载 2–3 集」：后台线程把当前集与后面几集整集写进这里，
 * 播放时 [RemoteVideo.read] 先读磁盘，网络再慢也不会卡。
 * 存的是原始字节，解密仍按绝对偏移在读出时做（与内存里的 prefix 同一套规则）。
 *
 * 磁盘写失败（空间不足等）时进入 [broken]：不再接受写入，已写好的部分照常可读。
 * [close] 会删掉文件——视频对象关掉，缓存也就没用了。
 */
class RawStore(private val file: File, val total: Long): Closeable {
    private val access: RandomAccessFile
    @Volatile var filled=0L; private set
    @Volatile var broken=false; private set
    private var closed=false

    init {
        require(total>0)
        file.parentFile?.mkdirs()
        access=RandomAccessFile(file,"rw")
    }

    val complete: Boolean get() = filled>=total

    /**
     * 写入 [start] 起的一段字节。只有和已写部分**衔接或重叠**才会被接受（重叠的那部分跳过），
     * 中间有空洞的写入直接忽略并返回 false。
     */
    @Synchronized fun offer(start: Long,bytes: ByteArray): Boolean {
        if(closed || broken || start<0 || bytes.isEmpty()) return false
        val end=minOf(total,start+bytes.size)
        if(start>filled || end<=filled) return false
        val skip=(filled-start).toInt(); val count=(end-filled).toInt()
        return try {
            access.seek(filled); access.write(bytes,skip,count); filled=end; true
        } catch(_: IOException) { broken=true; false }
    }

    /** 从 [start] 读最多 [length] 字节，只返回已落盘的部分（可能为空）。 */
    @Synchronized fun read(start: Long,length: Int): ByteArray {
        if(closed || start<0 || length<=0) return byteArrayOf()
        val count=(minOf(filled,start+length)-start).coerceAtLeast(0L).toInt()
        if(count==0) return byteArrayOf()
        val out=ByteArray(count)
        return try { access.seek(start); access.readFully(out); out } catch(_: IOException) { byteArrayOf() }
    }

    @Synchronized override fun close() {
        if(closed) return
        closed=true
        runCatching { access.close() }
        file.delete()
    }
}
