package com.rk.hardwaretest.camera

import android.content.Context
import android.graphics.Rect
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaRecorder
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.io.File
import kotlin.math.max

data class CameraRequestConfig(
    val cameraId: String,
    val exposure: Int,
    val zoom: Float,
    val focusMode: Int,
    val fps: Range<Int>?,
    val previewSize: Size?,
)

/** Owns exactly one Camera2 preview session and applies every setting to its repeating request. */
class Camera2PreviewController(
    private val context: Context,
    private val textureView: TextureView,
    private val onState: (String) -> Unit,
    private val onLumaStats: (FrameLumaStats) -> Unit = {},
    private val onMetrics: (CameraPreviewMetrics) -> Unit = {},
) : TextureView.SurfaceTextureListener {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("HardwareCameraPreview").apply { start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var analysisReader: ImageReader? = null
    private var lastAnalysisMs = 0L
    private var lastCaptureTimestamp = 0L
    private var latestLuma: FrameLumaStats? = null
    private var latestMetrics: CameraPreviewMetrics? = null
    private var configuring = false
    private var current: CameraRequestConfig? = null
    private var openedId: String? = null
    private var activePreviewSize: Size? = null
    private var recorder: MediaRecorder? = null
    private var recordingOutput: File? = null
    private var recordingRequested = false
    private var recordingStarted = false
    private var onRecordingStarted: (() -> Unit)? = null
    private var onRecordingError: ((String) -> Unit)? = null

    init { textureView.surfaceTextureListener = this }

    fun setConfig(config: CameraRequestConfig) {
        val unchanged = current == config && (configuring || (device != null && session != null))
        val changedCamera = openedId != config.cameraId
        current = config
        if (!textureView.isAvailable) return
        if (unchanged) return
        if (changedCamera || device == null) open(config.cameraId) else submit()
    }

    fun startRecording(output: File, onStarted: () -> Unit, onError: (String) -> Unit) {
        handler.post {
            if (recordingRequested || recorder != null) return@post onError("录像已在进行中")
            val size = activePreviewSize
            if (current == null || device == null || size == null) return@post onError("相机预览尚未就绪")
            try {
                output.parentFile?.mkdirs()
                recorder = MediaRecorder().apply {
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setVideoSize(size.width, size.height)
                    setVideoFrameRate(30)
                    setVideoEncodingBitRate((size.width * size.height * 4).coerceIn(1_000_000, 20_000_000))
                    setOutputFile(output.absolutePath)
                    prepare()
                }
                recordingOutput = output
                recordingRequested = true
                recordingStarted = false
                onRecordingStarted = onStarted
                onRecordingError = onError
                submit()
            } catch (e: Exception) {
                releaseRecorder()
                onError("准备录像失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun stopRecording(onStopped: (File) -> Unit, onError: (String) -> Unit) {
        handler.post {
            val output = recordingOutput
            if (!recordingRequested || output == null) return@post onError("当前没有可停止的录像")
            try {
                session?.stopRepeating()
                if (!recordingStarted) throw IllegalStateException("录像尚未开始")
                recorder?.stop()
                releaseRecorder()
                recordingRequested = false
                recordingStarted = false
                submit()
                onStopped(output)
            } catch (e: Exception) {
                releaseRecorder()
                recordingRequested = false
                recordingStarted = false
                submit()
                onError("停止录像失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun open(id: String) {
        closeCamera()
        openedId = id
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { device = camera; submit() }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); onState("相机已断开") }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); onState("打开相机失败：$error") }
            }, handler)
        } catch (e: SecurityException) { onState("未授予摄像头权限") }
        catch (e: Exception) { onState("打开相机失败：${e.message}") }
    }

    private fun submit() {
        val config = current ?: return
        val camera = device ?: return
        val texture = textureView.surfaceTexture ?: return
        val characteristics = manager.getCameraCharacteristics(config.cameraId)
        val exposureSupported = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            ?.let { it.lower != 0 || it.upper != 0 } == true
        val size = config.previewSize ?: characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)?.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
        activePreviewSize = size
        texture.setDefaultBufferSize(size.width, size.height)
        val viewWidth = textureView.width.toFloat()
        val viewHeight = textureView.height.toFloat()
        if (viewWidth > 0f && viewHeight > 0f) {
            val bufferAspect = size.width.toFloat() / size.height
            val viewAspect = viewWidth / viewHeight
            // Keep both scales positive: TextureView must letterbox, never mirror.
            textureView.scaleX = if (bufferAspect < viewAspect) bufferAspect / viewAspect else 1f
            textureView.scaleY = if (bufferAspect > viewAspect) viewAspect / bufferAspect else 1f
            textureView.setTransform(Matrix())
        }
        val surface = Surface(texture)
        analysisReader?.close()
        analysisReader = if (recordingRequested) null else {
            val lumaSize = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.minByOrNull { it.width * it.height } ?: size
            ImageReader.newInstance(lumaSize.width, lumaSize.height, ImageFormat.YUV_420_888, 2).also { reader ->
                reader.setOnImageAvailableListener({ source ->
                    val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val now = System.currentTimeMillis()
                        if (now - lastAnalysisMs >= 1500L) {
                            val buffer = image.planes[0].buffer
                            val luma = ByteArray(buffer.remaining()).also { buffer.get(it) }
                            lastAnalysisMs = now
                            latestLuma = calculateLumaStats(luma)
                            onLumaStats(latestLuma!!)
                            latestMetrics = (latestMetrics ?: CameraPreviewMetrics(
                                actualExposure = null,
                                exposureSupported = exposureSupported,
                                fps = null,
                                outputSize = activePreviewSize,
                                luma = null,
                            )).withLuma(latestLuma!!)
                            onMetrics(latestMetrics!!)
                        }
                    } finally { image.close() }
                }, handler)
            }
        }
        val recordingSurface = if (recordingRequested) recorder?.surface else null
        val request = camera.createCaptureRequest(if (recordingRequested) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            analysisReader?.surface?.let { addTarget(it) }
            recordingSurface?.let { addTarget(it) }
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, config.exposure)
            set(CaptureRequest.CONTROL_AF_MODE, config.focusMode)
            config.fps?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            val maxZoom = max(1f, characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
            val ratio = config.zoom.coerceIn(1f, maxZoom)
            val zoomRatioRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            } else null
            if (zoomRatioRange != null) {
                set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio)
            } else characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { active ->
                val cropWidth = (active.width() / ratio).toInt()
                val cropHeight = (active.height() / ratio).toInt()
                val left = (active.width() - cropWidth) / 2
                val top = (active.height() - cropHeight) / 2
                set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropWidth, top + cropHeight))
            }
        }.build()
        try {
            configuring = true
            // Several vendor Camera2 implementations ignore a new session while the old
            // repeating request is alive. Close it before applying changed controls.
            session?.close()
            session = null
            val targets = listOfNotNull(surface, analysisReader?.surface, recordingSurface)
            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(value: CameraCaptureSession) {
                    session = value
                    configuring = false
                    try { value.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                            val actualExposure = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)
                            val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
                            val realFps = if (lastCaptureTimestamp > 0L && timestamp > lastCaptureTimestamp) {
                                (1_000_000_000.0 / (timestamp - lastCaptureTimestamp)).toFloat()
                            } else null
                            lastCaptureTimestamp = timestamp
                            latestMetrics = CameraPreviewMetrics(actualExposure, exposureSupported, realFps, size, latestLuma)
                            onMetrics(latestMetrics!!)
                        }
                    }, handler)
                    if (recordingRequested && !recordingStarted) {
                        try {
                            recorder?.start() ?: throw IllegalStateException("录像编码器不可用")
                            recordingStarted = true
                            onRecordingStarted?.invoke()
                            onRecordingStarted = null
                        } catch (e: Exception) {
                            failRecording("启动录像失败：${e.message ?: e.javaClass.simpleName}")
                            submit()
                        }
                    }
                    } catch (e: IllegalStateException) { onState("相机会话已关闭，正在恢复") }
                }
                override fun onConfigureFailed(value: CameraCaptureSession) {
                    configuring = false
                    if (recordingRequested) {
                        failRecording("相机不支持当前分辨率下的录像输出：请降低分辨率后重试")
                        submit()
                    } else onState("相机拒绝该组合：请降低分辨率或帧率")
                }
            }, handler)
        } catch (e: Exception) { configuring = false; onState("应用相机设置失败：${e.message}") }
    }

    private fun failRecording(message: String) {
        releaseRecorder()
        recordingRequested = false
        recordingStarted = false
        onRecordingError?.invoke(message)
        onRecordingError = null
        onRecordingStarted = null
    }

    private fun releaseRecorder() {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        recordingOutput = null
    }

    private fun closeCamera() {
        if (recordingRequested) failRecording("相机已关闭，录像已取消") else releaseRecorder()
        session?.close(); session = null; analysisReader?.close(); analysisReader = null; device?.close(); device = null
    }
    fun release() { closeCamera(); thread.quitSafely() }
    override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { current?.let { setConfig(it) } }
    override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) = Unit
    override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean { closeCamera(); return true }
    override fun onSurfaceTextureUpdated(s: SurfaceTexture) = Unit
}
