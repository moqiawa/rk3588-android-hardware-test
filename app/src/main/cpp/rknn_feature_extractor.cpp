#include <jni.h>
#include <android/asset_manager_jni.h>
#include <vector>
#include "rknn_api.h"

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_rk_hardwaretest_camera_RknnFeatureExtractor_nativeExtract(
    JNIEnv* env, jobject, jobject assetManager, jbyteArray rgb) {
  if (env->GetArrayLength(rgb) != 224 * 224 * 3) return nullptr;
  AAsset* asset = AAssetManager_open(AAssetManager_fromJava(env, assetManager), "camera_quality_feature.rknn", AASSET_MODE_BUFFER);
  if (!asset) return nullptr;
  const void* model = AAsset_getBuffer(asset); const off_t size = AAsset_getLength(asset);
  rknn_context ctx = 0;
  if (!model || rknn_init(&ctx, const_cast<void*>(model), size, 0, nullptr) != RKNN_SUCC) { AAsset_close(asset); return nullptr; }
  rknn_input input{}; input.index = 0; input.type = RKNN_TENSOR_UINT8; input.fmt = RKNN_TENSOR_NHWC;
  input.size = 224 * 224 * 3; input.buf = env->GetByteArrayElements(rgb, nullptr);
  int ret = rknn_inputs_set(ctx, 1, &input); env->ReleaseByteArrayElements(rgb, static_cast<jbyte*>(input.buf), JNI_ABORT);
  if (ret != RKNN_SUCC || rknn_run(ctx, nullptr) != RKNN_SUCC) { rknn_destroy(ctx); AAsset_close(asset); return nullptr; }
  rknn_tensor_attr attr{}; attr.index = 0;
  if (rknn_query(ctx, RKNN_QUERY_OUTPUT_ATTR, &attr, sizeof(attr)) != RKNN_SUCC) { rknn_destroy(ctx); AAsset_close(asset); return nullptr; }
  rknn_output output{}; output.index = 0; output.want_float = 1;
  if (rknn_outputs_get(ctx, 1, &output, nullptr) != RKNN_SUCC) { rknn_destroy(ctx); AAsset_close(asset); return nullptr; }
  jfloatArray answer = env->NewFloatArray(attr.n_elems);
  env->SetFloatArrayRegion(answer, 0, attr.n_elems, static_cast<jfloat*>(output.buf));
  rknn_outputs_release(ctx, 1, &output); rknn_destroy(ctx); AAsset_close(asset); return answer;
}
