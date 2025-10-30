#ifndef OPENZLPROTOBUF_H
#define OPENZLPROTOBUF_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jbyteArray JNICALL Java_io_github_hybledav_OpenZLProtobuf_convertNative(JNIEnv*, jclass,
        jbyteArray, jint, jint, jbyteArray);
JNIEXPORT jobjectArray JNICALL Java_io_github_hybledav_OpenZLProtobuf_trainNative(JNIEnv*, jclass,
        jobjectArray, jint, jint, jint, jint, jboolean);

#ifdef __cplusplus
}
#endif

#endif // OPENZLPROTOBUF_H
