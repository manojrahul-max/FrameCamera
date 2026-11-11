package app.frame.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import kotlin.math.abs
import kotlin.math.roundToInt

data class VideoOption(val quality: Quality, val fps: Int) {
    val label: String get() = "${CompatMatrix.qualityName(quality)} ${fps}"
}

/** One physical lens behind the bound camera and what it can record. */
class Lens(val id: String, val nativeZoom: Float, val maxFps: Map<Quality, Int>) {
    val name: String get() = when {
        nativeZoom < 0.85f -> "Ultra wide"
        nativeZoom < 1.7f -> "Main"
        else -> "Telephoto"
    }
}

/**
 * Builds the lens x zoom x resolution x frame rate matrix from the hardware itself.
 *
 * Android does not publish limits per zoom value, so the matrix is derived: each physical lens behind
 * the logical camera is read from its Camera2 stream map (sizes and minimum frame durations), the zoom
 * ratio where each lens takes over is computed from focal length and sensor size, and the result is
 * intersected with what CameraX and the auto-exposure frame rate ranges allow. Combinations that fail
 * when actually bound are remembered and hidden from then on.
 */
class CompatMatrix(private val ctx: Context, cameraInfo: CameraInfo, private val facing: Int) {

    val lenses: List<Lens>
    val qualities: List<Quality>
    val minZoom: Float
    val maxZoom: Float
    private val rateRanges: List<IntRange>
    private val prefs = ctx.getSharedPreferences("frame_compat", Context.MODE_PRIVATE)

    init {
        val zs = cameraInfo.zoomState.value
        minZoom = zs?.minZoomRatio ?: 1f
        maxZoom = zs?.maxZoomRatio ?: 1f
        qualities = try {
            @Suppress("DEPRECATION")
            QualitySelector.getSupportedQualities(cameraInfo)
                .filter { it == Quality.UHD || it == Quality.FHD || it == Quality.HD || it == Quality.SD }
        } catch (e: Exception) { emptyList() }
        rateRanges = try {
            cameraInfo.supportedFrameRateRanges.map { it.lower..it.upper }
        } catch (e: Exception) { listOf(15..30) }

        val sizes = HashMap<Quality, Size>()
        for (q in qualities) {
            try { QualitySelector.getResolution(cameraInfo, q)?.let { sizes[q] = it } } catch (_: Exception) {}
        }
        lenses = try { readLenses(cameraInfo, sizes) } catch (e: Exception) { emptyList() }.ifEmpty {
            // Could not read Camera2 details: fall back to what CameraX alone reports, without claiming 60.
            listOf(Lens("logical", 1f, qualities.associateWith { 30 }))
        }
    }

    private fun readLenses(info: CameraInfo, sizes: Map<Quality, Size>): List<Lens> {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val logicalId = Camera2CameraInfo.from(info).cameraId
        val lc = mgr.getCameraCharacteristics(logicalId)
        val refN = normFocal(lc) ?: 0f
        val physical = lc.physicalCameraIds.toList()
        val ids = if (physical.size >= 2) physical else listOf(logicalId)
        val out = ArrayList<Lens>()
        for (id in ids) {
            val c = if (id == logicalId) lc else mgr.getCameraCharacteristics(id)
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val supported = map.getOutputSizes(MediaCodec::class.java)?.toList() ?: emptyList()
            val aeMax = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.maxOfOrNull { it.upper } ?: 30
            val fps = HashMap<Quality, Int>()
            for ((q, size) in sizes) {
                if (!supported.contains(size)) continue
                val dur = map.getOutputMinFrameDuration(MediaCodec::class.java, size)
                val byStream = if (dur > 0) (1_000_000_000.0 / dur).roundToInt() else 30
                fps[q] = minOf(byStream, aeMax)
            }
            val n = normFocal(c)
            val native = if (ids.size >= 2 && refN > 0f && n != null) n / refN else 1f
            out.add(Lens(id, native, fps))
        }
        return out.sortedBy { it.nativeZoom }
    }

    private fun normFocal(c: CameraCharacteristics): Float? {
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val w = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: return null
        return if (w > 0f) f / w else null
    }

    fun lensAt(zoom: Float): Lens {
        var pick = lenses.first()
        for (l in lenses) if (zoom >= l.nativeZoom * 0.97f) pick = l
        return pick
    }

    /** Zoom shortcuts that match the lenses that really exist, plus 2x and 10x when the range allows. */
    fun presets(): List<Float> {
        val set = sortedSetOf<Float>()
        if (minZoom < 0.95f) set.add(round1(minZoom))
        set.add(1f)
        for (l in lenses) {
            val r = (l.nativeZoom * 2f).roundToInt() / 2f
            if (r > 1.4f && r <= maxZoom + 0.01f) set.add(r)
        }
        if (maxZoom >= 2f && set.none { abs(it - 2f) < 0.6f }) set.add(2f)
        if (maxZoom >= 10f && set.none { it >= 9f }) set.add(10f)
        return set.toList()
    }

    private fun round1(v: Float) = (v * 10f).roundToInt() / 10f

    /** Only combinations the hardware reports for the lens active at [zoom]. [effectActive] applies the effect ceiling. */
    fun options(zoom: Float, effectActive: Boolean): List<VideoOption> {
        val lens = lensAt(zoom)
        val result = ArrayList<VideoOption>()
        for (q in qualities) {
            if (effectActive && q == Quality.UHD) continue
            val cap = lens.maxFps[q] ?: continue
            for (fps in CANDIDATE_FPS) {
                if (fps > cap) continue
                if (effectActive && fps > EFFECT_MAX_FPS) continue
                if (rateRanges.none { fps in it }) continue
                if (isBlocked(lens.id, q, fps)) continue
                result.add(VideoOption(q, fps))
            }
        }
        return result
    }

    fun isSupported(zoom: Float, effectActive: Boolean, q: Quality, fps: Int): Boolean =
        options(zoom, effectActive).any { it.quality == q && it.fps == fps }

    /** The valid option closest to what the user asked for, or null when nothing is available. */
    fun nearest(zoom: Float, effectActive: Boolean, q: Quality, fps: Int): VideoOption? {
        val opts = options(zoom, effectActive)
        return opts.minWithOrNull(compareBy({ abs(rank(it.quality) - rank(q)) }, { abs(it.fps - fps) }))
    }

    fun block(zoom: Float, q: Quality, fps: Int) {
        val key = key(lensAt(zoom).id, q, fps)
        prefs.edit().putStringSet("blocked", (prefs.getStringSet("blocked", emptySet()) ?: emptySet()) + key).apply()
    }

    private fun isBlocked(lensId: String, q: Quality, fps: Int): Boolean =
        prefs.getStringSet("blocked", emptySet())?.contains(key(lensId, q, fps)) == true

    private fun key(lensId: String, q: Quality, fps: Int) = "$facing:$lensId:${qualityName(q)}:$fps"

    private fun rank(q: Quality) = when (q) { Quality.UHD -> 3; Quality.FHD -> 2; Quality.HD -> 1; else -> 0 }

    companion object {
        private val CANDIDATE_FPS = intArrayOf(24, 30, 60)
        /** With an effect active the recorder is limited to 30 fps: 60 fps through the GPU effect path is not verified. */
        const val EFFECT_MAX_FPS = 30

        fun qualityName(q: Quality): String = when (q) {
            Quality.UHD -> "4K"
            Quality.FHD -> "1080p"
            Quality.HD -> "720p"
            Quality.SD -> "480p"
            else -> "Auto"
        }
    }
}
