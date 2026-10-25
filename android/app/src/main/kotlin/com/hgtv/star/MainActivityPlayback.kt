// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.*
import android.text.TextUtils
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.hgtv.star.core.*
import java.util.concurrent.Executors
import com.hgtv.star.player.PlaybackMenu

// 本文件是从 MainActivity.kt 拆出来的「播放中控：缓冲抑制、下一集预取、重试恢复、HUD 与传输层回收」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

internal fun MainActivity.closeTransport(http: okhttp3.OkHttpClient?) {
    if(http==null) return
    // Closing an idle TLS connection can write close_notify; keep it off the UI thread.
    networkCleanup.execute { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
}
/**
 * 无感切集时的缓冲指示：过渡那一两百毫秒不转圈，画面停在上一集最后一帧就够了。
 *
 * 只关到新源 READY 为止，而且**最多 1.2 秒**——万一预热的数据被换出、新源还得走网络，
 * 转圈该回来就得回来，不能让用户对着一帧静止画面干等。
 */
// media3 的 `PlayerView` / `DefaultLoadControl` 这些成员标了 `@UnstableApi`，lint 的
// `UnsafeOptInUsageError` 要求使用处显式 opt-in（行为不变，只是把「我知道这是不稳定 API」写在代码上）。
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun MainActivity.suppressBufferingUi() {
    if(bufferingSuppressed) return
    bufferingSuppressed=true
    playerView?.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
    main.removeCallbacks(restoreBuffering); main.postDelayed(restoreBuffering,1200)
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun MainActivity.restoreBufferingUi() {
    if(!bufferingSuppressed) return
    bufferingSuppressed=false
    main.removeCallbacks(restoreBuffering)
    playerView?.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
}
/**
 * 上一集的视频与传输：等新源出画面（READY）、这一集出错、或退出播放时再关。
 *
 * [scheduleRetire] 是兜底：万一新源一直没到 READY（切走、出错这些极端路径），
 * 3 秒后也把它收掉——那时候旧源早就被换下、不可能还在读，收掉不会打断任何东西。
 */
internal fun MainActivity.scheduleRetire() {
    main.removeCallbacks(retireForced); main.postDelayed(retireForced,3000)
}
internal fun MainActivity.retirePreviousEpisode() {
    main.removeCallbacks(retireForced)
    retiringVideo?.let { runCatching { it.close() } }
    retiringVideo=null
    closeTransport(retiringHttp); retiringHttp=null
}
/** 预先下载的集数（不含正在播的这一集）。 */
internal const val PRELOAD_EPISODES=3
/** 磁盘剩余空间低于这个值就不再落盘（退回只预热开头 1MB 的旧行为）。 */
internal const val PRELOAD_MIN_FREE_BYTES=300L*1024*1024

/** 预载好的一集：视频对象连同它专用的那条传输，一起交给播放器或一起回收。 */
internal class PrefetchedEpisode(val video: RemoteVideo,val http: okhttp3.OkHttpClient,private val release: (okhttp3.OkHttpClient)->Unit): java.io.Closeable {
    override fun close() { runCatching { video.close() }; release(http) }
}
internal fun MainActivity.episodeCacheDir()=java.io.File(cacheDir,"episodes")
internal fun MainActivity.newEpisodeCacheFile(): java.io.File? = runCatching {
    val dir=episodeCacheDir(); dir.mkdirs()
    if(dir.usableSpace<PRELOAD_MIN_FREE_BYTES) null else java.io.File(dir,"${java.util.UUID.randomUUID()}.bin")
}.getOrNull()

/** 停掉在途的预载下载，但保留已经放上架的几集。 */
internal fun MainActivity.pausePrefetch() {
    prefetchScope?.cancel(); prefetchScope=null
    prefetchJob?.cancel(true); prefetchJob=null
}
/** 全部作废：停下载，并关掉（删掉）已预载的几集。 */
internal fun MainActivity.cancelPrefetch() {
    pausePrefetch(); preloaded.clear()
}
/**
 * 预先下载：当前这一集开播 3 秒后，后台单线程依次
 *   1. 把**当前这一集**剩下的部分整集下载到磁盘；
 *   2. 取后面 [PRELOAD_EPISODES] 集的播放地址，逐集整集下载到磁盘。
 * 播放时 [RemoteVideo.read] 先读磁盘，网络慢也不卡；切到已下好的那集直接无感接上。
 *
 * 由每秒一次的 tick 调用。计量网络（手机热点）、定时停止后都不预载；关了自动连播只下当前这一集。
 * 取地址的接口约一半概率回空响应：当场重试；仍失败就先下后面几集，5 秒后下一轮再补。
 */
internal fun MainActivity.maybePrefetch() {
    val p=player ?: return; val data=detail ?: return
    if(!foreground || !p.isPlaying || playError || sleepStopped) return
    if(prefetchJob?.isDone==false) return
    val now=android.os.SystemClock.elapsedRealtime()
    if(readySince==0L || now-readySince<3000) return
    if(connectivity.isActiveNetworkMetered || !networkAvailable()) return
    val quality=library.maxQuality
    // 关了自动连播就只把当前这一集下完，不预载后面几集。
    val last=if(library.autoNext) minOf(episodeIndex+PRELOAD_EPISODES,data.episodes.lastIndex) else episodeIndex
    val window=(episodeIndex+1..last).map { data.episodes[it] to "${data.episodes[it]}:$quality" }
    val current=video
    val currentPending=current!=null && current.cacheable && !current.complete
    val missing=window.filter { (_,key) -> preloaded.peek(key)?.video?.let { it.complete || !it.cacheable }!=true }
    preloaded.retain(window.map { it.second }.toSet())
    if(!currentPending && missing.isEmpty()) return
    if(prefetchAttempts>=20 || (prefetchAttempts>0 && now-prefetchAttemptAt<5_000)) return
    prefetchAttemptAt=now; prefetchAttempts++
    val ticket=preloaded.generation()
    val scope=RequestScope(); prefetchScope=scope
    val credentials=repository.credentials
    val keepGoing={ !Thread.currentThread().isInterrupted }
    fun log(line: String)=android.util.Log.i("HGTV",line)
    prefetchJob=prefetchWorker.submit {
        // 1. 正在播的这一集：剩下的部分先下完（下不动也不影响后面几集）。
        if(currentPending) try {
            if(scope.run { current!!.download(keepGoing=keepGoing) }) log("Preload current complete episode=${episodeIndex+1}")
        } catch(problem: Exception) { log("Preload current failed: ${problem.javaClass.simpleName} ${problem.message}") }
        // 2. 后面几集按顺序来（下一集永远排第一）；某一集取不到地址或下载出错，
        //    先去下后面那几集，下一轮（5 秒后）再回头补它。
        for((id,key) in missing) {
            if(Thread.currentThread().isInterrupted) break
            try {
                val remote=preloaded.peek(key)?.video ?: run {
                    val http=okhttp3.OkHttpClient.Builder().connectTimeout(8,java.util.concurrent.TimeUnit.SECONDS).readTimeout(15,java.util.concurrent.TimeUnit.SECONDS).callTimeout(30,java.util.concurrent.TimeUnit.SECONDS).build()
                    // 预取走的是另一个仓库实例：登录态与**设备身份 / 注册 Cookie**都要带过去，
                    // 少了后者它签名时只剩内置那份档案（`device_id` 被平台拉黑），取播放地址一律空响应。
                    val source=ContentRepository(http); source.credentials=credentials; source.inheritDevice(repository)
                    var made: RemoteVideo?=null
                    try {
                        if(!library.deviceRegistered()) { registerDeviceOnce(); source.inheritDevice(repository) }
                        // 取地址的接口约一半概率回空响应：当场隔 1.5 秒重试，最多 4 次。
                        var info: StreamInfo?=null; var lastError: Exception?=null
                        for(attempt in 1..4) {
                            try { info=scope.run { source.stream(id,quality) }; break }
                            catch(problem: InterruptedException) { throw problem }
                            catch(problem: Exception) { lastError=problem; if(attempt<4) Thread.sleep(1_500) }
                        }
                        val stream=info ?: throw (lastError ?: java.io.IOException("取不到播放地址"))
                        scope.run { RemoteVideo(http,stream,newEpisodeCacheFile()).also { video ->
                            made=video
                            video.refresh={ source.stream(id,quality) }
                            video.log={ line -> log("$line key=$key") }
                            video.warm()
                        } }.also { created ->
                            if(!preloaded.put(ticket,key,PrefetchedEpisode(created,http) { closeTransport(it) })) throw java.io.IOException("预载已作废")
                            log("Preload prepared $key")
                        }
                    } catch(problem: Exception) { made?.close(); closeTransport(http); throw problem }
                }
                if(!remote.cacheable) continue
                if(scope.run { remote.download(keepGoing=keepGoing) }) log("Preload complete $key")
            } catch(problem: InterruptedException) { break }
            catch(problem: Exception) {
                if(Thread.currentThread().isInterrupted) break
                log("Preload failed $key: ${problem.javaClass.simpleName} ${problem.message}")
            }
        }
    }
}
internal fun MainActivity.networkAvailable(): Boolean = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
internal fun MainActivity.currentPosition(): Long = pendingSeek ?: player?.currentPosition?.takeIf { playbackReady } ?: startPosition
internal fun MainActivity.cancelRecovery() { main.removeCallbacks(retryRunnable); retryPending=false; autoRecovery=false }
internal fun MainActivity.scheduleRecovery() {
    if(!foreground || !playError || !autoRecovery || retryPending) return
    if(!recovery.remaining) { autoRecovery=false; playbackText.text="已自动重试 3 次，仍无法播放。可手动重试或切换清晰度。"; return }
    if(!networkAvailable()) { playbackText.text="网络已断开，联网后自动续播 · 第 ${episodeIndex+1} 集 ${formatTime(retryPosition)}"; return }
    val delay=recovery.delayMillis ?: return
    playbackText.text="${if(retryAutoplay) "准备恢复播放" else "准备恢复暂停位置"} · ${delay/1000f} 秒后重试 ${recovery.attempts+1}/3 · ${formatTime(retryPosition)}"
    retryPending=true; main.postDelayed(retryRunnable,delay)
}
internal fun MainActivity.playerFailure(problem: Throwable) {
    retryPosition=currentPosition().coerceAtLeast(0); retryAutoplay=requestedAutoplay && !sleepStopped
    saveProgress(); cancelRecovery(); cancelPrefetch()
    // 无感切集留下的两个尾巴：缓冲指示要恢复，上一集的视频与传输要收掉（这一集已经出错，不会再出画面了）。
    restoreBufferingUi(); retirePreviousEpisode()
    playError=true; player?.pause(); progressBar.visibility=View.GONE; hud.visibility=View.VISIBLE; controls.removeAllViews(); controls.visibility=View.VISIBLE; panel=true
    // 出错界面要让用户看清是哪一集，标题条这时必须露出来。
    playbackTopBar?.visibility=View.VISIBLE
    dismissPlaybackMenu()
    val decoding=problem is PlaybackException && problem.errorCode in 3000..4999
    val retryable=if(problem is PlaybackException) problem.errorCode in 2000..2999 || problem.errorCode==PlaybackException.ERROR_CODE_TIMEOUT else problem is java.io.IOException
    playbackText.text=if(decoding) "电视无法解码当前视频，可尝试更低清晰度。" else "播放失败，可重试或切换清晰度。"
    val actions=row(); controls.addView(actions)
    addButton(actions,"立即重试") { playEpisode(episodeIndex,retryPosition,retryAutoplay) }.requestFocus()
    addButton(actions,"尝试 720P") { library.maxQuality=720; playEpisode(episodeIndex,retryPosition,retryAutoplay) }
    addButton(actions,"返回选集") { returnToDetail(true) }
    if(retryable && !sleepStopped) {
        addButton(controls,"停止自动重试") { cancelRecovery(); playbackText.text="自动重试已停止，观看位置已保留。" }
        autoRecovery=true; scheduleRecovery()
    }
    android.util.Log.w("HGTV","Playback failure: ${problem.javaClass.simpleName}"+(if(problem is PlaybackException) " code=${problem.errorCodeName}" else ""))
}
internal fun MainActivity.hidePlaybackOverlay() {
    main.removeCallbacks(hideHud)
    panel=false; dismissPlaybackMenu(); controls.visibility=View.GONE; controls.clearFocus(); hud.visibility=View.GONE
    // 标题条跟底部控制条同一套显隐：播放时屏幕上不留任何文字。
    playbackTopBar?.visibility=View.GONE
}
internal fun MainActivity.showHud() {
    // 设置菜单打开时底部控制条保持收起，避免和菜单抢同一块画面。
    if(screen!="player" || playbackMenu!=null) return
    hud.visibility=View.VISIBLE; updatePlaybackText(); main.removeCallbacks(hideHud)
    playbackTopBar?.visibility=View.VISIBLE
    if(!panel && player?.isPlaying==true) main.postDelayed(hideHud,4500)
}
internal fun MainActivity.updatePlaybackText() {
    playerClock?.text=clockFormat.format(java.util.Date())
    if(screen!="player") return
    // 播放键图标与选项行的当前值：爆米花把状态放在图标和选项文字里，不额外占一行。
    transportIcon?.setImageResource(if(player?.playWhenReady==true) R.drawable.ic_player_pause else R.drawable.ic_player_play)
    qualityOption?.text="清晰度 "+if(quality.isBlank()) "${library.maxQuality}P" else quality
    speedOption?.text="倍速 "+PlaybackSpeed.label(if(playbackReady) player?.playbackParameters?.speed ?: library.playbackSpeed else library.playbackSpeed)
    frameOption?.text="画面模式 "+library.frameMode.label
    // 状态行只放显示方式：弹幕条数放在「设置 → 弹幕显示」那一行，
    // 播放界面的这一排已经够长，再加数字会被屏宽裁掉。
    danmakuOption?.text="弹幕 "+library.danmakuMode.label
    sleepOption?.text="定时 "+sleepTimer.label()
    if(playError) return
    if(sleepStopped) { playbackText.text="定时停止 · 按播放继续"; return }
    if(!playbackReady) { playbackText.text=if(video==null) "正在获取播放地址…" else "正在缓冲…"; return }
    val p=player ?: return
    val duration=p.duration.coerceAtLeast(0); val position=pendingSeek ?: p.currentPosition
    val span="${formatSpan(position)} / ${formatSpan(duration)}"
    playbackText.text=if(p.playbackState==Player.STATE_BUFFERING) "缓冲中 · $span" else if(p.playbackState==Player.STATE_ENDED) "本集已结束 · $span" else span
    progressBar.progress=if(duration>0) position.toFloat()/duration else 0f
}
internal fun MainActivity.requestPlayback(play: Boolean) {
    requestedAutoplay=play; pausedForLifecycle=!play
    if(play) sleepStopped=false
    if(!play) { player?.pause(); retryAutoplay=false; saveProgress() }
    else if(playError) { playEpisode(episodeIndex,retryPosition,true); return }
    else { if(player?.playbackState==Player.STATE_ENDED) player?.seekTo(0); player?.play() }
    if(!play) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    showHud(); updatePlaybackText(); syncMediaSession()
}
internal fun MainActivity.togglePlayback()=requestPlayback(!(if(playbackReady) player?.playWhenReady ?: requestedAutoplay else requestedAutoplay))
internal fun MainActivity.seekTo(position: Long) {
    val p=player ?: return; if(!playbackReady || p.duration<=0) return
    main.removeCallbacks(seekRunnable); pendingSeek=null
    p.seekTo(position.coerceIn(0,(p.duration-500).coerceAtLeast(0))); updatePlaybackText(); syncMediaSession(); showHud()
}
internal fun MainActivity.skipEpisode(direction: Int) {
    if(!foreground || screen!="player") return
    val count=detail?.episodes?.size ?: return
    val target=episodeIndex+direction
    if(target in 0 until count) playEpisode(target)
}
internal fun MainActivity.syncMediaSession() {
    if(!foreground || screen!="player") { mediaSession.deactivate(); return }
    val data=detail ?: return; val p=player
    val state=when {
        playError -> android.media.session.PlaybackState.STATE_ERROR
        sleepStopped || !requestedAutoplay || (p!=null && !p.playWhenReady) -> android.media.session.PlaybackState.STATE_PAUSED
        p==null || !playbackReady || p.playbackState==Player.STATE_BUFFERING -> android.media.session.PlaybackState.STATE_BUFFERING
        p.playbackState==Player.STATE_ENDED -> android.media.session.PlaybackState.STATE_STOPPED
        p.isPlaying -> android.media.session.PlaybackState.STATE_PLAYING
        else -> android.media.session.PlaybackState.STATE_PAUSED
    }
    mediaSession.update(data.series.title,episodeIndex,p?.duration?.coerceAtLeast(0) ?: 0,currentPosition(),library.playbackSpeed,state,episodeIndex>0,episodeIndex<data.episodes.lastIndex)
}
internal fun MainActivity.stopForSleep() {
    sleepStopped=true; requestedAutoplay=false; retryAutoplay=false; pausedForLifecycle=true
    sleepChoice=0
    cancelRecovery(); player?.pause(); saveProgress()
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"
    showHud(); if(playbackMenu!=null) showPlaybackMenu(sleepRow(library.danmakuMode.showsStyle)); syncMediaSession()

}
/** 定时项目的 8 个选项：关闭排在最前，选中态跟随本次观看已生效的定时。 */
internal fun MainActivity.sleepChoices(menu: PlaybackMenu): List<PlaybackMenu.Choice> {
    val current=if(sleepTimer.active) sleepChoice else 0
    return sleepLabels.mapIndexed { index,label ->
        PlaybackMenu.Choice(label,current==index) {
            sleepChoice=index
            when(index) {
                0 -> sleepTimer.cancel()
                in 1..4 -> sleepTimer.afterMinutes(listOf(15,30,60,90)[index-1])
                else -> sleepTimer.afterEpisodes(listOf(1,3,5)[index-5])
            }
            menu.select(sleepRow(library.danmakuMode.showsStyle),index); updatePlaybackText()
        }
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun MainActivity.frameResizeMode()=when(library.frameMode) {
    VideoFrameMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    VideoFrameMode.ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    VideoFrameMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun MainActivity.showFramePicker(anchor: TextView,inPlayer: Boolean) {
    tvTools.choose("全局画面模式",listOf("完整画面 · 保留比例与全部内容","等比铺满 · 会裁掉部分画面与字幕","拉伸铺满 · 画面比例会改变"),anchor,{ index ->
        library.frameMode=VideoFrameMode.entries[index]; playerView?.resizeMode=frameResizeMode()
        if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "画面 " else "画面模式  ·  ")+library.frameMode.label
    })
}
/**
 * 弹幕显示方式：只显一行 / 上方显示 / 全屏显示 / 隐藏弹幕。
 *
 * 排列顺序与每一档的说明都取自 [DanmakuMode.entries]（`hint`），设置页与播放设置面板不再各写一份文案——
 * 档位顺序是需求里定死的一条验收项，抄成三份迟早会走样。
 *
 * 弹幕数据本身就是原 App 播放时用的那一份（见 [ContentRepository.danmaku]）。
 *
 * [onChanged] 在档位真的变了之后回调一次——设置页用它就地收起 / 放出「弹幕速度」「弹幕大小」
 * 「弹幕透明度」三行（[DanmakuMode.showsStyle]）；播放设置面板走的是整块重建，不需要这个回调。
 */
internal fun MainActivity.showDanmakuPicker(anchor: TextView,inPlayer: Boolean,onChanged: ()->Unit={}) {
    tvTools.choose("弹幕显示方式",DanmakuMode.entries.map { it.hint },anchor,{ index ->
        val selected=DanmakuMode.entries[index]
        val changed=library.danmakuMode!=selected
        library.danmakuMode=selected; danmakuView?.mode=selected
        if(anchor.tag!="playback-settings") anchor.text=danmakuLabel(if(inPlayer) "弹幕 " else "弹幕显示  ·  ")
        if(changed) onChanged()
    })
}
/**
 * 弹幕移动速度：慢速 / 标准 / 快速。
 *
 * 三档只改「整体多快」：弹幕层里所有弹幕同速（见 [com.hgtv.star.player.DanmakuView]），
 * 换档不会让某一条追上前面那一条——快的档位只是让同一屏通过得更多、同一条轨道排得更密。
 * [onChanged] 在档位真的变了之后回调一次（设置页用它刷新那一行文案）。
 */
internal fun MainActivity.showDanmakuSpeedPicker(anchor: TextView,inPlayer: Boolean,onChanged: ()->Unit={}) {
    tvTools.choose("弹幕移动速度",DanmakuSpeed.entries.map { it.hint },anchor,{ index ->
        val selected=DanmakuSpeed.entries[index]
        val changed=library.danmakuSpeed!=selected
        library.danmakuSpeed=selected; danmakuView?.speed=selected
        if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "弹幕速度 " else "弹幕速度  ·  ")+selected.label
        if(changed) onChanged()
    })
}
/**
 * 「弹幕显示」那一行的文案：显示方式 + 最近一集实际取到多少条。
 *
 * 平台只对部分剧集放开匿名弹幕，屏幕上「一条都没有」和「功能没生效」从画面上分不出来，
 * 这一行就是给这件事一个明确的读数（播放界面那排状态项太窄，放不下数字）。
 */
internal fun MainActivity.danmakuLabel(prefix: String)=prefix+library.danmakuMode.label+
    if(danmakuCount<0) "" else "（上一集 ${if(danmakuCount>0) "$danmakuCount 条" else "无弹幕"}）"
internal fun MainActivity.seek(direction: Int,repeat: Int) {
    val p=player ?: return; if(!playbackReady) return
    val duration=p.duration; if(duration<=0) return
    val step=if(repeat>4) 30000 else 10000
    pendingSeek=((pendingSeek ?: p.currentPosition)+direction*step).coerceIn(0,(duration-500).coerceAtLeast(0))
    main.removeCallbacks(seekRunnable); main.postDelayed(seekRunnable,250); updatePlaybackText(); showHud()
}
internal fun MainActivity.dismissPlaybackMenu() {
    playbackMenu?.let { menu -> if(menu.hasFocus()) menu.clearFocus() }
    // 面板现在是「外壳 → 滚动容器 → 面板」三层，直接 removeView(menu) 摘不掉任何东西
    // （menu 的父级是滚动容器），浮层会留在画面上。这里按最外层摘。
    playbackMenuShell?.let { shell -> (shell.parent as? ViewGroup)?.removeView(shell) }
    playbackMenuShell=null
    playbackMenu=null
}
/**
 * 遥控器「上」（或菜单键）打开底部设置面板：
 * 清晰度 / 倍速 / 画面模式 / 弹幕 / 弹幕大小 / 弹幕透明度 / 定时。
 *
 * 「弹幕大小 / 弹幕透明度」只在弹幕可见时存在：隐藏档下整层不画、也不发请求，
 * 这两项改完看不到任何变化，因此那两行**直接不添加**（行号见 [sleepRow]）。
 *
 * 功能竖排、选项横排，上下换功能、左右换选项，确认立即生效，返回收起整个浮层。
 * 面板高度**固定为屏幕下方三分之一**，装不下的行留在滚动容器里，遥控器焦点走到哪一行
 * 就自动把哪一行带进可视区（ScrollView 自己的 `requestChildFocus` 行为），因此所有行都能按到。
 *
 * [focusRow] 与 [focusChoice] 用来在改清晰度重建菜单、或点 HUD 状态行时定位焦点；
 * [focusChoice] 为负数时落在该项目当前选中的选项上。
 */
