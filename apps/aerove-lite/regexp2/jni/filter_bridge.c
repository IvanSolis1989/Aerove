/* AEROVE isolated-process byte ABI. */
#include <jni.h>
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <pthread.h>
#include <string.h>

typedef void *(*batch_fn)(const void *, size_t, size_t *);
typedef int32_t (*cancel_fn)(uint64_t);
typedef void (*free_fn)(void *);
typedef uint32_t (*version_fn)(void);
typedef void *(*age_fn)(const void *, size_t, const void *, size_t, uint64_t, size_t *);
static age_fn call_age;
static batch_fn call_batch;
static cancel_fn call_cancel;
static free_fn call_free;
static const char *load_error;
static pthread_once_t once = PTHREAD_ONCE_INIT;
static void load_helper(void) {
    void *library = dlopen("libaerove_filter_helper.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) { load_error = "Filter helper could not be loaded"; return; }
    call_age = (age_fn)dlsym(library, "aerove_helper_decrypt_age");
    call_batch = (batch_fn)dlsym(library, "aerove_filter_batch_utf8");
    call_cancel = (cancel_fn)dlsym(library, "aerove_filter_cancel");
    call_free = (free_fn)dlsym(library, "aerove_filter_free");
    version_fn version = (version_fn)dlsym(library, "aerove_filter_abi_version");
    if (!call_age || !call_batch || !call_cancel || !call_free || !version || version() != 2) load_error = "Filter helper ABI mismatch";
}
static void fail(JNIEnv *env, const char *kind, const char *message) {
    jclass type = (*env)->FindClass(env, kind);
    if (type) (*env)->ThrowNew(env, type, message);
}
JNIEXPORT jbyteArray JNICALL Java_dev_aerove_lite_regexp2_FilterNative_batch
  (JNIEnv *env, jclass type, jbyteArray request) {
    (void)type;
    if (!request) { fail(env, "java/lang/IllegalArgumentException", "Request is null"); return NULL; }
    jsize size = (*env)->GetArrayLength(env, request);
    if (size <= 0 || size > 4 * 1024 * 1024) { fail(env, "java/lang/IllegalArgumentException", "Request length is invalid"); return NULL; }
    pthread_once(&once, load_helper);
    if (load_error) { fail(env, "java/lang/IllegalStateException", load_error); return NULL; }
    uint8_t *bytes = (uint8_t *)malloc((size_t)size);
    if (!bytes) { fail(env, "java/lang/OutOfMemoryError", "Input allocation failed"); return NULL; }
    (*env)->GetByteArrayRegion(env, request, 0, size, (jbyte *)bytes);
    if ((*env)->ExceptionCheck(env)) { free(bytes); return NULL; }
    size_t output_size = 0;
    void *output = call_batch(bytes, (size_t)size, &output_size);
    free(bytes);
    if (!output || output_size > 4 * 1024 * 1024) {
        if (output) call_free(output);
        fail(env, "java/lang/IllegalStateException", "Invalid helper response"); return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)output_size);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)output_size, (const jbyte *)output);
    call_free(output);
    return result;
}
JNIEXPORT jint JNICALL Java_dev_aerove_lite_regexp2_FilterNative_cancel
  (JNIEnv *env, jclass type, jlong id) {
    (void)type;
    if (id <= 0) { fail(env, "java/lang/IllegalArgumentException", "Request id is invalid"); return -1; }
    pthread_once(&once, load_helper);
    if (load_error) { fail(env, "java/lang/IllegalStateException", load_error); return -1; }
    return (jint)call_cancel((uint64_t)id);
}

JNIEXPORT jbyteArray JNICALL Java_dev_aerove_lite_regexp2_FilterNative_decrypt
  (JNIEnv *env, jclass type, jbyteArray ciphertext, jbyteArray identities, jlong id) {
    (void)type;
    if (!ciphertext || !identities || id <= 0) { fail(env, "java/lang/IllegalArgumentException", "Invalid age request"); return NULL; }
    jsize cipher_size = (*env)->GetArrayLength(env, ciphertext);
    jsize key_size = (*env)->GetArrayLength(env, identities);
    if (cipher_size <= 0 || cipher_size > 8 * 1024 * 1024 || key_size <= 0 || key_size > 64 * 1024) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid age request size"); return NULL;
    }
    pthread_once(&once, load_helper);
    if (load_error) { fail(env, "java/lang/IllegalStateException", load_error); return NULL; }
    unsigned char *cipher = malloc((size_t)cipher_size), *keys = malloc((size_t)key_size);
    if (!cipher || !keys) { free(cipher); free(keys); fail(env, "java/lang/OutOfMemoryError", "Age allocation failed"); return NULL; }
    (*env)->GetByteArrayRegion(env, ciphertext, 0, cipher_size, (jbyte *)cipher);
    (*env)->GetByteArrayRegion(env, identities, 0, key_size, (jbyte *)keys);
    if ((*env)->ExceptionCheck(env)) { memset(keys, 0, key_size); free(keys); free(cipher); return NULL; }
    size_t output_size = 0;
    void *output = call_age(cipher, cipher_size, keys, key_size, (uint64_t)id, &output_size);
    /* Volatile writes prevent optimization from removing this owned secret-buffer wipe. */
    volatile unsigned char *wipe = keys;
    for (jsize i = 0; i < key_size; i++) wipe[i] = 0;
    free(keys); free(cipher);
    if (!output || output_size > 4 * 1024 * 1024 + 256) {
        if (output) call_free(output);
        fail(env, "java/lang/IllegalStateException", "Invalid age result size"); return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)output_size);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize)output_size, output);
    wipe = output;
    for (size_t i = 0; i < output_size; i++) wipe[i] = 0;
    call_free(output);
    return result;
}
