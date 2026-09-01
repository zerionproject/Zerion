/*
 * Thin JNI bridge to the reference Argon2 implementation (phc-winner-argon2,
 * pinned commit f57e61e19229e23c4445b85494dbf7c07de721cb, release 20190702).
 * No cryptographic logic lives here: it forwards to argon2id_hash_raw with the
 * exact caller-supplied parameters and returns the raw derived key, or null on
 * any error so the Java side can fall back to its equivalent implementation.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "argon2.h"

JNIEXPORT jbyteArray JNICALL
Java_com_professor_zerion_android_vault_crypto_NativeArgon2_deriveRaw(
        JNIEnv *env, jclass clazz, jbyteArray pwd, jbyteArray salt,
        jint mCostKb, jint tCost, jint parallelism, jint hashLen) {
    (void) clazz;
    if (pwd == NULL || salt == NULL || hashLen <= 0 || mCostKb <= 0
            || tCost <= 0 || parallelism <= 0) {
        return NULL;
    }
    jsize pwdLen = (*env)->GetArrayLength(env, pwd);
    jsize saltLen = (*env)->GetArrayLength(env, salt);
    jbyte *pwdBuf = (*env)->GetByteArrayElements(env, pwd, NULL);
    jbyte *saltBuf = (*env)->GetByteArrayElements(env, salt, NULL);
    unsigned char *out = (unsigned char *) malloc((size_t) hashLen);
    jbyteArray result = NULL;

    if (out != NULL && pwdBuf != NULL && saltBuf != NULL) {
        int rc = argon2id_hash_raw(
                (uint32_t) tCost, (uint32_t) mCostKb, (uint32_t) parallelism,
                pwdBuf, (size_t) pwdLen, saltBuf, (size_t) saltLen,
                out, (size_t) hashLen);
        if (rc == ARGON2_OK) {
            result = (*env)->NewByteArray(env, hashLen);
            if (result != NULL) {
                (*env)->SetByteArrayRegion(env, result, 0, hashLen,
                        (const jbyte *) out);
            }
        }
    }

    if (out != NULL) {
        memset(out, 0, (size_t) hashLen);
        free(out);
    }
    if (pwdBuf != NULL) {
        memset(pwdBuf, 0, (size_t) pwdLen);
        (*env)->ReleaseByteArrayElements(env, pwd, pwdBuf, JNI_ABORT);
    }
    if (saltBuf != NULL) {
        (*env)->ReleaseByteArrayElements(env, salt, saltBuf, JNI_ABORT);
    }
    return result;
}
