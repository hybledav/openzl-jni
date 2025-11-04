#ifndef OPENZLPROTOBUF_H
#define OPENZLPROTOBUF_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLProtobuf_convertNative(JNIEnv*, jclass,
        jbyteArray, jint, jint, jbyteArray, jstring);
JNIEXPORT jobjectArray JNICALL Java_io_github_hybledav_OpenZLProtobuf_trainNative(JNIEnv*, jclass,
        jobjectArray, jint, jint, jint, jint, jboolean, jstring);
JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLProtobuf_configureTrainingNative(JNIEnv*, jclass,
        jstring, jint);
JNIEXPORT void JNICALL Java_io_github_hybledav_OpenZLProtobuf_registerSchemaNative(JNIEnv*, jclass,
        jbyteArray);

#ifdef __cplusplus
}
#endif

#endif // OPENZLPROTOBUF_H
