// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.File

class EpisodePrefetchTest {
    private fun bytes(from: Int,count: Int)=ByteArray(count) { (from+it).toByte() }

    @Test fun rawStoreAcceptsOnlyContiguousWritesAndReadsWhatIsFilled() {
        val file=File.createTempFile("hgtv-raw",".bin")
        val store=RawStore(file,10)
        assertFalse(store.offer(2,bytes(2,3)))          // 有空洞：不收
        assertTrue(store.offer(0,bytes(0,4)))
        assertEquals(4L,store.filled)
        assertTrue(store.offer(2,bytes(2,4)))            // 重叠：只补新的那段
        assertEquals(6L,store.filled)
        assertFalse(store.offer(0,bytes(0,3)))           // 全部已写过
        assertArrayEquals(bytes(3,3),store.read(3,10))   // 只返回已落盘的部分
        assertArrayEquals(byteArrayOf(),store.read(6,4))
        assertTrue(store.offer(6,bytes(6,8)))            // 超出总长的部分截掉
        assertTrue(store.complete)
        assertArrayEquals(bytes(0,10),store.read(0,10))
        store.close()
        assertFalse(file.exists())
        assertArrayEquals(byteArrayOf(),store.read(0,10))
        assertFalse(store.offer(0,bytes(0,1)))
    }

    private class Item(var done: Boolean=false): Closeable {
        var closed=false
        override fun close() { closed=true }
    }

    @Test fun shelfKeepsSeveralEpisodesAndDropsStaleOnes() {
        var now=0L
        val shelf=PrefetchShelf<Item>({ now },{ it.done },ttl=1_000)
        val ticket=shelf.generation()
        val a=Item(); val b=Item(done=true); val c=Item()
        assertTrue(shelf.put(ticket,"a",a)); assertTrue(shelf.put(ticket,"b",b)); assertTrue(shelf.put(ticket,"c",c))
        val dup=Item(); assertFalse(shelf.put(ticket,"a",dup)); assertTrue(dup.closed)
        assertEquals(3,shelf.size())

        // 换集：只留下新窗口里的几集
        shelf.retain(setOf("b","c"))
        assertTrue(a.closed); assertFalse(b.closed); assertTrue(shelf.has("c"))

        // 没下完的过期作废；下完的不受时限
        now=5_000
        assertFalse(shelf.has("c")); assertNull(shelf.peek("c"))
        assertNull(shelf.take("c")); assertTrue(c.closed)
        assertSame(b,shelf.take("b")); assertFalse(b.closed)
        assertEquals(0,shelf.size())
    }

    @Test fun clearRejectsWorkStartedBeforeIt() {
        val shelf=PrefetchShelf<Item>({ 0L },{ false })
        val ticket=shelf.generation()
        val kept=Item(); shelf.put(ticket,"a",kept)
        shelf.clear()
        assertTrue(kept.closed)
        val late=Item()
        assertFalse(shelf.put(ticket,"b",late))
        assertTrue(late.closed)
        assertTrue(shelf.put(shelf.generation(),"b",Item()))
    }
}
