#ifndef OPENZLCOMPRESSOR_H
#define OPENZLCOMPRESSOR_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jlong JNICALL Java_io_github_hybledav_OpenZLCompressor_createCompressor(JNIEnv*, jobject);
JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_setParameter(JNIEnv*, jobject, jint, jint);
JNIEXPORT jint JNICALL Java_io_github_hybledav_OpenZLCompressor_getParameter(JNIEnv*, jobject, jint);
JNIEXPORT jstring JNICALL Java_io_github_hybledav_OpenZLCompressor_serialize(JNIEnv*, jobject);
JNIEXPORT jstring JNICALL Java_io_github_hybledav_OpenZLCompressor_serializeToJson(JNIEnv*, jobject);
JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLCompressor_destroyCompressor(JNIEnv*, jobject);
JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_compress(JNIEnv*, jobject, jbyteArray);
JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLCompressor_decompress(JNIEnv*, jobject, jbyteArray);
JNIEXPORT jint JNICALL Java_io_github_hybledav_OpenZLCompressor_compressDirect(JNIEnv*, jobject,
        jobject, jint, jint, jobject, jint, jint);
JNIEXPORT jint JNICALL Java_io_github_hybledav_OpenZLCompressor_decompressDirect(JNIEnv*, jobject,
        jobject, jint, jint, jobject, jint, jint);

#ifdef __cplusplus
}
#endif

#endif // OPENZLCOMPRESSOR_H
