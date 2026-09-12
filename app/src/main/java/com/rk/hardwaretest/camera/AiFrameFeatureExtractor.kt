package com.rk.hardwaretest.camera

sealed interface AiFeatureResult {
    data class Available(val embedding: FloatArray) : AiFeatureResult
    data class Unavailable(val reason: String) : AiFeatureResult
}

interface AiFrameFeatureExtractor { fun extract(rgb224: ByteArray): AiFeatureResult }

class UnavailableFeatureExtractor(private val reason: String) : AiFrameFeatureExtractor {
    override fun extract(rgb224: ByteArray): AiFeatureResult = AiFeatureResult.Unavailable(reason)
}

class RknnFeatureExtractor(private val context: android.content.Context) : AiFrameFeatureExtractor {
    init { System.loadLibrary("camera_quality_ai") }
    override fun extract(rgb224: ByteArray): AiFeatureResult = runCatching { nativeExtract(context.assets, rgb224) }
        .getOrNull()?.takeIf { it.isNotEmpty() }?.let(AiFeatureResult::Available)
        ?: AiFeatureResult.Unavailable("RKNN 模型加载或推理失败")
    private external fun nativeExtract(assets: android.content.res.AssetManager, rgb224: ByteArray): FloatArray?
}
