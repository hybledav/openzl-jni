#ifndef OPENZLPROTOBUF_H
#define OPENZLPROTOBUF_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLProtobuf_convertNative(JNIEnv*, jclass,
        jbyteArray, jint, jint, jbyteArray, jstring);
JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLProtobuf_convertSliceNative(JNIEnv*, jclass,
        jbyteArray, jint, jint, jint, jint, jbyteArray, jstring);
JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLProtobuf_convertDirectNative(JNIEnv*, jclass,
        jobject, jint, jint, jint, jbyteArray, jstring);
JNIEXPORT jint JNICALL Java_io_github_hybledav_OpenZLProtobuf_convertDirectIntoNative(JNIEnv*, jclass,
        jobject, jint, jint, jint, jbyteArray, jstring, jobject, jint, jint);
JNIEXPORT jlongArray JNICALL Java_io_github_hybledav_OpenZLProtobuf_directIntoProfileNative(JNIEnv*, jclass);
JNIEXPORT jobjectArray JNICALL Java_io_github_hybledav_OpenZLProtobuf_trainNative(JNIEnv*, jclass,
        jobjectArray, jint, jint, jint, jint, jboolean, jstring);
JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLProtobuf_configureTrainingNative(JNIEnv*, jclass,
        jstring, jint);
JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLProtobuf_registerSchemaNative(JNIEnv*, jclass,
        jbyteArray);
JNIEXPORT jstring JNICALL Java_io_github_hybledav_OpenZLProtobuf_graphJsonNative(JNIEnv*, jclass,
                                                                               jstring);
JNIEXPORT jstring JNICALL Java_io_github_hybledav_OpenZLProtobuf_graphJsonFromCompressorNative(JNIEnv*, jclass,
                                                                                               jbyteArray);
JNIEXPORT jstring JNICALL Java_io_github_hybledav_OpenZLProtobuf_graphDetailJsonNative(JNIEnv*, jclass,
                                                                                     jstring);

#ifdef __cplusplus
}
#endif

#endif // OPENZLPROTOBUF_H
