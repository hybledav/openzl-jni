#ifndef OPENZLCOMPRESSOR_H
#define OPENZLCOMPRESSOR_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jlong JNICALL Java_io_github_hybledav_OpenZLCompressor_nativeCreate(JNIEnv*, jobject, jint);
JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_setParameter(JNIEnv*, jobject, jint, jint);
JNIEXPORT jint JNICALL Java_io_github_hybledav_OpenZLCompressor_getParameter(JNIEnv*, jobject, jint);
JNIEXPORT jstring JNICALL Java_io_github_hybledav_OpenZLCompressor_serialize(JNIEnv*, jobject);
JNIEXPORT jstring JNICALL Java_io_github_hybledav_OpenZLCompressor_serializeToJson(JNIEnv*, jobject);
JNIEXPORT jlong JNICALL Java_io_github_hybledav_OpenZLCompressor_maxCompressedSizeNative(JNIEnv*, jclass, jint);
JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_resetNative(JNIEnv*, jobject);
JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_destroyCompressor(JNIEnv*, jobject);
JNIEXPORT jint JNICALL Java_io_github_hybledav_OpenZLCompressor_compressIntoNative(JNIEnv*, jobject,
        jbyteArray, jint, jint, jbyteArray, jint, jint);
JNIEXPORT jint JNICALL Java_io_github_hybledav_OpenZLCompressor_decompressIntoNative(JNIEnv*, jobject,
        jbyteArray, jint, jint, jbyteArray, jint, jint);
JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_compress(JNIEnv*, jobject, jbyteArray);
JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_decompress(JNIEnv*, jobject, jbyteArray);
JNIEXPORT jint JNICALL Java_io_github_hybledav_OpenZLCompressor_compressDirect(JNIEnv*, jobject,
        jobject, jint, jint, jobject, jint, jint);
JNIEXPORT jint JNICALL Java_io_github_hybledav_OpenZLCompressor_decompressDirect(JNIEnv*, jobject,
        jobject, jint, jint, jobject, jint, jint);
JNIEXPORT jlong JNICALL Java_io_github_hybledav_OpenZLCompressor_getDecompressedSizeNative(JNIEnv*, jobject, jbyteArray);
JNIEXPORT jlong JNICALL Java_io_github_hybledav_OpenZLCompressor_getDecompressedSizeDirect(JNIEnv*, jobject,
        jobject, jint, jint);
JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_compressIntsNative(JNIEnv*, jobject, jintArray);
JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_compressLongsNative(JNIEnv*, jobject, jlongArray);
JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_compressFloatsNative(JNIEnv*, jobject, jfloatArray);
JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_compressDoublesNative(JNIEnv*, jobject, jdoubleArray);
JNIEXPORT jintArray JNICALL Java_io_github_hybledav_OpenZLCompressor_decompressIntsNative(JNIEnv*, jobject, jbyteArray);
JNIEXPORT jlongArray JNICALL Java_io_github_hybledav_OpenZLCompressor_decompressLongsNative(JNIEnv*, jobject, jbyteArray);
JNIEXPORT jfloatArray JNICALL Java_io_github_hybledav_OpenZLCompressor_decompressFloatsNative(JNIEnv*, jobject, jbyteArray);
JNIEXPORT jdoubleArray JNICALL Java_io_github_hybledav_OpenZLCompressor_decompressDoublesNative(JNIEnv*, jobject, jbyteArray);
JNIEXPORT jlongArray JNICALL Java_io_github_hybledav_OpenZLCompressor_describeFrameNative(JNIEnv*, jobject, jbyteArray);
JNIEXPORT jlongArray JNICALL Java_io_github_hybledav_OpenZLCompressor_describeFrameDirectNative(JNIEnv*, jobject, jobject, jint, jint);
JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_configureSddlNative(JNIEnv*, jobject, jbyteArray);
JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_configureProfileNative(JNIEnv*, jobject, jstring, jobjectArray, jobjectArray);
JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_setDataArenaNative(JNIEnv*, jobject, jint);
JNIEXPORT jobjectArray JNICALL Java_io_github_hybledav_OpenZLCompressor_listProfilesNative(JNIEnv*, jclass);
JNIEXPORT jobjectArray JNICALL Java_io_github_hybledav_OpenZLCompressor_trainNative(JNIEnv*, jclass,
        jstring, jobjectArray, jint, jint, jint, jboolean);
JNIEXPORT jobjectArray JNICALL Java_io_github_hybledav_OpenZLCompressor_trainFromDirectoryNative(JNIEnv*, jclass,
        jstring, jstring, jint, jint, jint, jboolean);

JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_compressWithProfileNative(JNIEnv*, jclass,
        jstring, jbyteArray);
JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_compressWithSerializedNative(JNIEnv*, jclass,
        jstring, jbyteArray, jbyteArray);

JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLSddl_compileNative(JNIEnv*, jclass, jstring, jboolean, jint);

#ifdef __cplusplus
}
#endif

#endif // OPENZLCOMPRESSOR_H
