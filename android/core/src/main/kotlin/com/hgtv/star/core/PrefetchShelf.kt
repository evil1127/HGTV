// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import java.io.Closeable

/**
 * 预载的多集视频（取代只放一集的 [PreparedSlot]）：键是「剧集 id:清晰度」。
 *
 * - 后台线程用 [put] 放进来；放之前若被 [clear] 过（[generation] 变了），放不进去并立刻关掉。
 * - 播放时 [take] 取走；整集已下载完（[isComplete]）的不受 [ttl] 限制——
 *   字节都在本地，不再需要那条会过期的播放地址；没下载完的超过 [ttl] 就作废。
 * - 换集后用 [retain] 只留下新窗口里的几集，其余关掉（会顺带删掉磁盘缓存）。
 */
class PrefetchShelf<T: Closeable>(
    private val clock: ()->Long,
    private val isComplete: (T)->Boolean,
    private val ttl: Long=10*60_000L,
) {
    private class Entry<T>(val value: T,val at: Long)
    private val entries=LinkedHashMap<String,Entry<T>>()
    private var revision=0L

    @Synchronized fun generation(): Long = revision

    @Synchronized fun put(ticket: Long,key: String,value: T): Boolean {
        if(ticket!=revision || entries.containsKey(key)) { runCatching { value.close() }; return false }
        entries[key]=Entry(value,clock()); return true
    }

    /** 不取走，只看一眼（后台线程用它接着把没下完的那集下完）。 */
    @Synchronized fun peek(key: String): T? = entries[key]?.takeIf { usable(it) }?.value

    @Synchronized fun has(key: String): Boolean = entries[key]?.let { usable(it) }==true

    @Synchronized fun take(key: String): T? {
        val entry=entries.remove(key) ?: return null
        if(usable(entry)) return entry.value
        runCatching { entry.value.close() }; return null
    }

    @Synchronized fun retain(keys: Set<String>) {
        val iterator=entries.entries.iterator()
        while(iterator.hasNext()) {
            val (key,entry)=iterator.next().let { it.key to it.value }
            if(key !in keys || !usable(entry)) { runCatching { entry.value.close() }; iterator.remove() }
        }
    }

    @Synchronized fun clear() {
        revision++
        entries.values.forEach { runCatching { it.value.close() } }
        entries.clear()
    }

    @Synchronized fun size(): Int = entries.size

    private fun usable(entry: Entry<T>): Boolean = isComplete(entry.value) || clock()-entry.at in 0..ttl
}
