package com.nuvio.app.features.cast

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.nuvio.app.features.cast.model.CastVideoCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Turns a source the receiver cannot play into one it can.
 *
 * Deliberately an interface: the Media3 implementation below covers the common cases with
 * hardware encoders and no added binary weight, but it inherits MediaCodec's limits — it can
 * only decode what the handset itself decodes, so exotic audio such as DTS-HD or TrueHD will
 * fail here. Swapping in an FFmpeg-backed implementation is the way to close that gap without
 * touching the planner, the server or the UI.
 */
interface CastMediaProcessor {

    /**
     * Produces a receiver-playable file at [output].
     *
     * [onProgress] reports 0..100 and is advisory; Media3 cannot always estimate progress.
     */
    suspend fun process(
        sourceUrl: String,
        plan: CastDeliveryPlan,
        output: File,
        onProgress: (Int) -> Unit = {},
    ): Result<File>

    /** Aborts an in-flight export. */
    fun cancel()
}

/**
 * Hardware-accelerated implementation built on Media3 Transformer.
 *
 * Transformer transmuxes rather than re-encodes whenever the requested output format already
 * matches the input, so the remux path costs roughly a file copy. Encoding, when required,
 * goes through MediaCodec and therefore uses the device's hardware encoder.
 */
@OptIn(UnstableApi::class)
class Media3CastMediaProcessor(private val context: Context) : CastMediaProcessor {

    private var transformer: Transformer? = null

    override suspend fun process(
        sourceUrl: String,
        plan: CastDeliveryPlan,
        output: File,
        onProgress: (Int) -> Unit,
    ): Result<File> = withContext(Dispatchers.Main) {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        suspendCancellableCoroutine { continuation ->
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    transformer = null
                    if (continuation.isActive) continuation.resume(Result.success(output))
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException,
                ) {
                    transformer = null
                    if (continuation.isActive) continuation.resume(Result.failure(exception))
                }
            }

            val builder = Transformer.Builder(context)
                .addListener(listener)
                // The receiver profile always lands on H.264 + AAC in an MP4, which is the
                // one combination every Cast device accepts.
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)

            val videoEffects = buildList {
                plan.videoTarget?.let { target ->
                    if (target.codec == CastVideoCodec.H264) {
                        add(Presentation.createForWidthAndHeight(
                            target.width,
                            target.height,
                            Presentation.LAYOUT_SCALE_TO_FIT,
                        ))
                    }
                }
            }

            val item = EditedMediaItem.Builder(MediaItem.fromUri(sourceUrl))
                .setEffects(Effects(emptyList(), videoEffects))
                .build()

            val instance = builder.build()
            transformer = instance
            continuation.invokeOnCancellation { runCatching { instance.cancel() } }

            try {
                instance.start(item, output.absolutePath)
            } catch (error: Throwable) {
                transformer = null
                if (continuation.isActive) continuation.resume(Result.failure(error))
            }
        }
    }

    override fun cancel() {
        transformer?.let { runCatching { it.cancel() } }
        transformer = null
    }
}
