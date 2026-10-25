// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.File
import java.io.IOException

/** One playback stream. No listening socket or local HTTP service. */
class RemoteVideo(private val http: OkHttpClient, val info: StreamInfo, private val cacheFile: File?=null): Closeable {
    @Volatile private var closed=false
    private val calls=mutableSetOf<Call>()
    data class Range(val bytes: ByteArray,val total: Long)
    var total: Long=0; private set
    private lateinit var prepared: MediaCrypto.Prepared
    @Volatile private var prefix=byteArrayOf()
    /** 整集原始字节的落盘缓存（见 [RawStore]）；没给 [cacheFile] 或建不起来时为 null，行为同以前。 */
    @Volatile private var store: RawStore?=null
    /** 能整集落盘（有缓存文件且没写坏）。 */
    val cacheable: Boolean get() = store?.broken==false
    /** 整集原始字节都已在本地磁盘上。 */
    val complete: Boolean get() = store?.complete==true
    private fun range(start: Long,end: Long): Range {
        if(closed) throw IOException("播放请求已取消")
        require(start>=0 && end>=start && end-start<8*1024*1024)
        val request=Request.Builder().url(info.url).header("User-Agent",VendorConstants.VIDEO_UA).header("Referer","https://novel.snssdk.com/").header("Range","bytes=$start-$end").header("Accept-Encoding","identity").build()
        val call=http.newCall(request)
        synchronized(calls) { if(closed) throw IOException("播放请求已取消"); calls+=call }
        try {
            return RequestScope.execute(call) { response ->
                if(response.code!=206) throw IOException("视频服务器不支持分段读取（HTTP ${response.code}）")
                val match=Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty()) ?: throw IOException("视频分段响应异常")
                val (begin,finish,size)=match.destructured
                val full=size.toLong(); val last=minOf(end,full-1)
                if(begin.toLong()!=start || finish.toLong()!=last || full<=0) throw IOException("视频分段范围不匹配")
                if(total!=0L && full!=total) throw IOException("视频资源已变化，请重试")
                val n=(last-start+1).toInt(); val body=response.body ?: throw IOException("视频分段为空")
                val bytes=body.source().readByteArray(n.toLong())
                Range(bytes,full)
            }
        } finally { synchronized(calls) { calls-=call } }
    }
    @Synchronized fun prepare(): RemoteVideo {
        if(::prepared.isInitialized) return this
        val first=range(0,256*1024-1); total=first.total
        prefix=first.bytes
        if(cacheFile!=null && !closed) store=runCatching { RawStore(cacheFile,total).also { it.offer(0,first.bytes) } }.getOrNull()
        if(closed) { store?.close(); store=null }
        try {
            val end=MediaCrypto.headerEnd(first.bytes,total)
            val header=if(end>first.bytes.size) first.bytes+range(first.bytes.size.toLong(),end-1L).bytes.also { store?.offer(first.bytes.size.toLong(),it) } else first.bytes.copyOf(end)
            prepared=MediaCrypto.prepare(header,info.key)
            if(prepared.samples.any { it.end>total }) throw IOException("视频样本超出资源大小")
        } catch(problem: Exception) { store?.close(); store=null; throw problem }
        return this
    }
    /** Bounded warm-up. Retains raw bytes so decryption still uses the absolute sample offset. */
    @Synchronized fun warm(): RemoteVideo {
        prepare()
        val size=minOf(total,1024*1024L).toInt()
        if(prefix.size<size) prefix=prefix+range(prefix.size.toLong(),size-1L).bytes.also { store?.offer(prefix.size.toLong(),it) }
        return this
    }
    /**
     * 把整集原始字节按顺序下载到磁盘（每次 [chunk] 字节），直到下完、视频被关掉或 [keepGoing] 返回 false。
     * 返回是否已整集落盘。网络错误照常抛出，由调用方决定要不要稍后再试。
     */
    fun download(chunk: Int=1024*1024,keepGoing: ()->Boolean={ true }): Boolean {
        prepare()
        val target=store ?: return false
        while(!closed && !target.broken && !target.complete && keepGoing()) {
            val start=target.filled; val end=minOf(total,start+chunk)
            target.offer(start,range(start,end-1).bytes)
        }
        return target.complete
    }
    fun read(start: Long,length: Int): ByteArray {
        require(length in 1..1024*1024 && start>=0)
        if(closed) throw IOException("播放请求已取消")
        if(start>=total) return byteArrayOf()
        val cached=prefix; val end=minOf(total,start+length)
        val available=(minOf(end,cached.size.toLong())-start).coerceAtLeast(0).toInt()
        var raw=if(available>0) cached.copyOfRange(start.toInt(),start.toInt()+available) else byteArrayOf()
        // 内存 prefix 之后先读磁盘缓存（后台已预先下载的部分），剩下的才走网络。
        val disk=store
        if(disk!=null && start+raw.size<end) raw+=disk.read(start+raw.size,(end-start-raw.size).toInt())
        if(start+raw.size<end) {
            val from=start+raw.size; val fetched=range(from,end-1).bytes
            // 播放自己拉到的字节也顺手落盘，后台下载就不用再拉一遍。
            disk?.offer(from,fetched)
            raw+=fetched
        }
        return MediaCrypto.decrypt(raw,start,info.key,prepared)
    }
    override fun close() {
        synchronized(calls) { closed=true; calls.toList().forEach { it.cancel() }; calls.clear() }
        store?.close(); store=null
    }
}
