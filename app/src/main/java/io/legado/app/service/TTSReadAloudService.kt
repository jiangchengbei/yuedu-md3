package io.legado.app.service

import android.app.PendingIntent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.help.MediaHelp
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地朗读（系统TTS合成 + 转发器播放/缓存架构）
 *
 * 系统TTS仅负责把文本 synthesizeToFile 合成到本地缓存文件，
 * 播放调度、ExoPlayer 管理、字级进度、缓存清理全部复用 HttpReadAloudService 模式。
 * 音频缓存目录与转发器共用：externalCacheDir/httpTTS/
 */
class TTSReadAloudService : BaseReadAloudService(), TextToSpeech.OnInitListener, Player.Listener {

    companion object {
        private const val TAG = "TTSReadAloudService"
        private const val PRELOAD_TRIGGER_REMAIN = 5
    }

    // ====== TTS 核心 ======
    private var textToSpeech: TextToSpeech? = null
    private var ttsInitFinish = false
    /** 初始化后锁定的TTS引擎标识，用于缓存键，不随运行时语音切换变化 */
    private var ttsEngineKey: String = "default"
    /** 标记主线程已经请求播放但 TTS 尚未初始化完成，onInit 回调中据此补救调用 play() */
    private var pendingPlay = false

    // ====== ExoPlayer & 播放（完全复制转发器模式）======
    private val exoPlayer: ExoPlayer by lazy { ExoPlayer.Builder(this).build() }
    private var playIndexJob: Job? = null
    private var playErrorNo = 0

    // ====== 缓存目录（与 HttpReadAloudService 保持一致）======
    private val ttsFolderPath: String by lazy {
        val baseDir = externalCacheDir ?: cacheDir
        val dir = File(baseDir, "httpTTS")
        if (!dir.exists()) dir.mkdirs()
        dir.absolutePath + File.separator
    }

    // ====== 合成核心（来自旧备份）======
    /** 把 TTS onDone/onError 回调转成挂起函数的 CompletableDeferred */
    private val synthesisCompleters = ConcurrentHashMap<String, CompletableDeferred<File?>>()
    /** utteranceId -> 合成目标临时文件 */
    private val synthesisTargetFiles = ConcurrentHashMap<String, File>()
    /** 主合成协程 */
    private var synthesisTask: Job? = null
    /** 跨章预加载协程 */
    private var preloadJob: Job? = null
    /** 防止 STATE_ENDED 和 onMediaItemTransition 双重触发 updateNextPos */
    private var skipNextEndedUpdate = false

    // ====== 生命周期 ======

    override fun onCreate() {
        super.onCreate()
        initTts()
        exoPlayer.addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer.release()
        clearTTS()
        synthesisTask?.cancel()
        playIndexJob?.cancel()
        preloadJob?.cancel()
        removeCacheFile()
    }

    // ====== TTS 初始化 ======

    @Synchronized
    private fun initTts() {
        ttsInitFinish = false
        val engine = GSON.fromJsonObject<SelectItem<String>>(ReadAloud.ttsEngine).getOrNull()?.value
        LogUtils.d(TAG, "initTts engine:$engine")
        ttsEngineKey = engine ?: "default"
        textToSpeech = if (engine.isNullOrBlank()) {
            TextToSpeech(this, this)
        } else {
            TextToSpeech(this, this, engine)
        }
        upSpeechRate()
    }

    @Synchronized
    fun clearTTS() {
        textToSpeech?.runCatching {
            stop()
            shutdown()
        }
        textToSpeech = null
        ttsInitFinish = false
        pendingPlay = false
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let {
                it.setOnUtteranceProgressListener(ttsUtteranceListener)
                ttsInitFinish = true
                if (pendingPlay) {
                    pendingPlay = false
                    play()
                }
            }
        } else {
            toastOnUi(R.string.tts_init_failed)
        }
    }

    // ====== 合成回调监听 ======

    private val ttsUtteranceListener = object : UtteranceProgressListener() {
        override fun onStart(s: String?) {
            LogUtils.d(TAG, "onStart synthesis utteranceId:$s")
        }

        override fun onDone(s: String?) {
            LogUtils.d(TAG, "onDone synthesis utteranceId:$s")
            s ?: return
            val targetFile = synthesisTargetFiles.remove(s)
            val completer = synthesisCompleters.remove(s)
            if (completer != null) {
                if (targetFile != null && targetFile.exists() && targetFile.length() > 0) {
                    completer.complete(targetFile)
                } else {
                    completer.complete(null)
                }
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            LogUtils.d(TAG, "onError utteranceId:$utteranceId errorCode:$errorCode")
            synthesisTargetFiles.remove(utteranceId)
            val completer = synthesisCompleters.remove(utteranceId)
            completer?.complete(null)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(s: String?) {
            LogUtils.d(TAG, "onError utteranceId:$s")
            synthesisTargetFiles.remove(s)
            val completer = synthesisCompleters.remove(s)
            completer?.complete(null)
        }
    }

    // ====== 核心合成方法（旧备份）======

    /**
     * 通用 TTS 合成方法。
     * 先写入 .tmp 临时文件，合成成功且验证为有效音频后，再重命名为正式缓存文件。
     * 若验证失败，会自动重试最多2次。
     */
    private suspend fun synthesizeText(
        text: String,
        utteranceId: String,
        index: Int = -1,
        chapterTitle: String = ""
    ): File? {
        val cacheFile = getCacheFileForText(text, index, chapterTitle)
        if (cacheFile.exists() && cacheFile.length() > 0 && isValidAudioFile(cacheFile)) {
            return cacheFile
        }

        val tts = textToSpeech ?: return null

        suspend fun tryOnce(attempt: Int): File? {
            val currentUtteranceId = if (attempt == 0) utteranceId else "${utteranceId}_retry$attempt"
            val tempFile = File(ttsFolderPath, "${cacheFile.name}.${currentUtteranceId}.tmp")

            if (cacheFile.exists() && !isValidAudioFile(cacheFile)) {
                cacheFile.delete()
            }
            FileUtils.listDirsAndFiles(ttsFolderPath)?.filter {
                it.isFile && it.name.startsWith(cacheFile.name) && it.name.endsWith(".tmp")
            }?.forEach { it.delete() }

            val completer = CompletableDeferred<File?>()
            synthesisCompleters[currentUtteranceId] = completer
            synthesisTargetFiles[currentUtteranceId] = tempFile

            val params = Bundle().apply {
                putInt("stream", AudioManager.STREAM_MUSIC)
            }

            val result = tts.runCatching {
                synthesizeToFile(text, params, tempFile, currentUtteranceId)
            }.getOrElse {
                AppLog.put("tts合成提交失败(attempt=$attempt)\n${it.localizedMessage}", it, true)
                TextToSpeech.ERROR
            }

            val synthesizedFile = if (result == TextToSpeech.SUCCESS) {
                try {
                    withTimeout(60000) { completer.await() }
                } catch (_: Exception) {
                    synthesisCompleters.remove(currentUtteranceId)
                    synthesisTargetFiles.remove(currentUtteranceId)
                    null
                }
            } else {
                synthesisCompleters.remove(currentUtteranceId)
                synthesisTargetFiles.remove(currentUtteranceId)
                null
            }

            return if (synthesizedFile != null && isValidAudioFile(synthesizedFile)) {
                if (synthesizedFile.renameTo(cacheFile)) {
                    cacheFile
                } else {
                    synthesizedFile.delete()
                    null
                }
            } else {
                synthesizedFile?.delete()
                null
            }
        }

        repeat(3) { attempt ->
            val result = tryOnce(attempt)
            if (result != null) return result
        }
        return null
    }

    private suspend fun synthesizeSingle(index: Int): File? {
        val text = getSpeakText(index)
        if (text.matches(AppPattern.notReadAloudRegex)) {
            return getSilentFile()
        }
        val utteranceId = "${AppConst.APP_TAG}$index"
        val chapterTitle = textChapter?.chapter?.title ?: ""
        return synthesizeText(text, utteranceId, index, chapterTitle) ?: getSilentFile()
    }

    // ====== 播放调度（复制/适配转发器）======

    @Synchronized
    override fun play() {
        if (!ttsInitFinish) {
            pendingPlay = true
            return
        }
        pendingPlay = false
        pageChanged = false
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
            return
        }
        super.play()
        MediaHelp.playSilentSound(this@TTSReadAloudService)

        synthesisTask?.cancel()
        playIndexJob?.cancel()
        preloadJob?.cancel()
        playErrorNo = 0

        val chapterTitle = textChapter?.chapter?.title ?: ""
        synthesisTask = lifecycleScope.launch(Dispatchers.IO) {
            for (i in nowSpeak until contentList.size) {
                ensureActive()

                val text = getSpeakText(i)
                val isEndMarker = i == contentList.lastIndex
                val file = if (isEndMarker || text.matches(AppPattern.notReadAloudRegex)) {
                    if (isEndMarker) {
                        AppLog.putDebug("章节末尾静音占位符，使用静音文件")
                    }
                    getSilentFile()
                } else {
                    val cacheFile = getCacheFileForText(text, i, chapterTitle)
                    if (cacheFile.exists() && cacheFile.length() > 0 && isValidAudioFile(cacheFile)) {
                        cacheFile
                    } else {
                        synthesizeSingle(i)
                    }
                }

                if (file != null && file.exists()) {
                    withContext(Dispatchers.Main) {
                        exoPlayer.addMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    }
                }

                if (contentList.size - i <= PRELOAD_TRIGGER_REMAIN) {
                    preloadNextChapter()
                }
            }
        }
    }

    override fun playStop() {
        exoPlayer.stop()
        synthesisTask?.cancel()
        playIndexJob?.cancel()
        preloadJob?.cancel()
        textToSpeech?.runCatching { stop() }
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        playIndexJob?.cancel()
        exoPlayer.pause()
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        kotlin.runCatching {
            if (pageChanged) {
                play()
            } else {
                if (exoPlayer.playbackState == Player.STATE_READY) {
                    exoPlayer.play()
                    upPlayPos()
                } else {
                    play()
                }
            }
        }
    }

    override fun upSpeechRate(reset: Boolean) {
        if (AppConfig.ttsFlowSys) {
            if (reset) {
                clearTTS()
                initTts()
            }
        } else {
            textToSpeech?.setSpeechRate(AppConfig.speechRateFloat)
            play()
        }
    }

    // ====== ExoPlayer 监听器（完全复制转发器）======

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_READY -> {
                if (pause) return
                exoPlayer.play()
                upPlayPos()
            }
            Player.STATE_ENDED -> {
                playIndexJob?.cancel()
                playErrorNo = 0
                if (!skipNextEndedUpdate) {
                    updateNextPos()
                }
                skipNextEndedUpdate = false
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
            else -> {}
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        when (reason) {
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> {
                if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
            }
            else -> {}
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
        }
        if (mediaItem != null) {
            skipNextEndedUpdate = true
            updateNextPos()
        }
        upPlayPos()
        upMediaMetadata(showContent = true)
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        AppLog.put("TTS播放错误", error)
        playIndexJob?.cancel()
        playErrorNo++
        if (playErrorNo >= 5) {
            pauseReadAloud()
        } else {
            if (exoPlayer.hasNextMediaItem()) {
                exoPlayer.seekToNextMediaItem()
                exoPlayer.prepare()
            } else {
                exoPlayer.clearMediaItems()
                updateNextPos()
            }
        }
    }

    // ====== 状态推进 ======

    private fun updateNextPos() {
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
        } else {
            nextChapter()
        }
    }

    // ====== 字级进度同步（完全复制转发器）======

    private fun upPlayPos() {
        playIndexJob?.cancel()
        val textChapter = textChapter ?: return
        playIndexJob = lifecycleScope.launch {
            upTtsProgress(readAloudNumber + 1)
            if (exoPlayer.duration <= 0) {
                return@launch
            }
            val speakTextLength = contentList[nowSpeak].length
            if (speakTextLength <= 0) {
                return@launch
            }
            val sleep = exoPlayer.duration / speakTextLength
            val start = speakTextLength * exoPlayer.currentPosition / exoPlayer.duration
            for (i in start..contentList[nowSpeak].length) {
                if (pageIndex + 1 < textChapter.pageSize
                    && readAloudNumber + i > textChapter.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                    upTtsProgress(readAloudNumber + i.toInt())
                }
                delay(sleep)
            }
        }
    }

    // ====== 跨章节预加载（旧备份适配）======

    private fun preloadNextChapter() {
        if (preloadJob?.isActive == true) return
        preloadJob = lifecycleScope.launch(Dispatchers.IO) {
            val book = ReadBook.book ?: return@launch
            val currentIdx = ReadBook.durChapterIndex
            val limit = AppConfig.audioPreDownloadNum

            for (offset in 1..limit) {
                ensureActive()
                try {
                    val targetIdx = currentIdx + offset
                    val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, targetIdx)
                        ?: continue
                    val rawContent = BookHelp.getContent(book, chapter) ?: continue

                    val contentProcessor = ContentProcessor.get(book)
                    val bookContent = contentProcessor.getContent(
                        book = book,
                        chapter = chapter,
                        content = rawContent,
                        includeTitle = true,
                        useReplace = AppConfig.replaceEnableDefault && book.getUseReplaceRule(),
                        chineseConvert = AppConfig.chineseConverterType != 0,
                        reSegment = book.getReSegment()
                    )

                    val segments = bookContent.toString()
                        .split("\n")
                        .filter { it.isNotEmpty() }

                    if (segments.isEmpty()) continue

                    val segLimit = 50.coerceAtMost(segments.size)

                    for (i in 0 until segLimit) {
                        ensureActive()
                        val text = segments[i]
                        if (text.matches(AppPattern.notReadAloudRegex)) continue

                        val cacheFile = getCacheFileForText(text, i, chapter.title)
                        if (cacheFile.exists() && cacheFile.length() > 0) continue

                        val utteranceId = "PRELOAD_${AppConst.APP_TAG}_${System.currentTimeMillis()}_$i"
                        runCatching {
                            synthesizeText(text, utteranceId, i, chapter.title)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.put("预加载章节失败, offset=$offset", e)
                }
            }
        }
    }

    // ====== 缓存管理（复制转发器）======

    /**
     * 移除缓存文件
     * 如果时间设置为0，则不再保护当前章节，退出即全删。
     */
    private fun removeCacheFile() {
        val keepTime = AppConfig.audioCacheCleanTime
        val protectCurrentChapter = keepTime > 0
        val titleMd5 = if (protectCurrentChapter) MD5Utils.md5Encode16(this.textChapter?.chapter?.title?.trim() ?: "") else ""

        FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
            val isSilentSound = it.length() == 2160L

            val shouldDelete = if (keepTime == 0L) {
                true
            } else {
                !it.name.startsWith(titleMd5) && (System.currentTimeMillis() - it.lastModified() > keepTime)
            }

            if (shouldDelete || isSilentSound) {
                FileUtils.delete(it.absolutePath)
            }
        }
    }

    private fun deleteCurrentSpeakFile() {
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val filePath = mediaItem.localConfiguration!!.uri.path!!
        File(filePath).delete()
    }

    // ====== 工具方法 ======

    private fun getSpeakText(index: Int): String {
        var text = contentList.getOrNull(index) ?: return ""
        if (paragraphStartPos > 0 && index == nowSpeak) {
            text = text.substring(paragraphStartPos)
        }
        return text
    }

    private fun getCacheFileForText(text: String, index: Int = -1, chapterTitle: String = ""): File {
        val engine = ReadAloud.ttsEngine ?: "default"
        val rate = AppConfig.speechRateFloat
        val engKey = ttsEngineKey
        val indexPart = if (index >= 0) "|$index" else ""
        val key = MD5Utils.md5Encode16("$engine|$engKey|$rate$indexPart|$text")
        val titlePart = if (chapterTitle.isNotEmpty()) MD5Utils.md5Encode16(chapterTitle.trim()) + "_" else ""
        return File(ttsFolderPath, "$titlePart$key.mp3")
    }

    private fun getSilentFile(): File? {
        return try {
            val file = File(ttsFolderPath, "silent.mp3")
            if (!file.exists()) {
                file.writeBytes(resources.openRawResource(R.raw.silent_sound).readBytes())
            }
            file
        } catch (e: Exception) {
            AppLog.put("生成静音文件失败", e)
            null
        }
    }

    private fun isSilentFile(file: File): Boolean {
        return file.name == "silent.mp3" || file.length() == 2160L
    }

    /**
     * 验证音频文件是否有效（可解析且时长大于0）
     * 对系统TTS合成的WAV文件增加手动解析头fallback，
     * 避免 MediaMetadataRetriever 无法识别而导致误删。
     */
    private fun isValidAudioFile(file: File): Boolean {
        if (!file.exists() || file.length() <= 44) return false
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            var duration = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()
            retriever.release()

            if (duration == null || duration <= 0) {
                duration = parseWavDurationMs(file)
            }

            duration != null && duration > 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 手动解析 WAV 文件头，计算音频时长（毫秒）。
     */
    private fun parseWavDurationMs(file: File): Long? {
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(44)
                if (stream.read(header) < 44) return null
                if (!(header[0] == 'R'.toByte() && header[1] == 'I'.toByte() &&
                            header[2] == 'F'.toByte() && header[3] == 'F'.toByte() &&
                            header[8] == 'W'.toByte() && header[9] == 'A'.toByte() &&
                            header[10] == 'V'.toByte() && header[11] == 'E'.toByte())) {
                    return null
                }
                val sampleRate = java.nio.ByteBuffer.wrap(header, 24, 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                val channels = java.nio.ByteBuffer.wrap(header, 22, 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()
                val bitsPerSample = java.nio.ByteBuffer.wrap(header, 34, 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()
                if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) return null
                val byteRate = sampleRate * channels * bitsPerSample / 8
                if (byteRate <= 0) return null
                val dataSize = file.length() - 44
                (dataSize * 1000L / byteRate)
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }
}
