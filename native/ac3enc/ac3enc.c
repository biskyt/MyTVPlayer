// The JNI wrapper around FFmpeg's AC-3 (Dolby Digital) encoder, for Ac3Encoder.kt in MyTVPlayer and MyPlexPlayer.
// FFmpeg is built with nothing else. JNI_OnLoad registers the functions, so one library serves both apps.
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>

typedef struct {
    AVCodecContext *ctx;
    AVFrame *frame;
    AVPacket *pkt;
    int channels;
} Encoder;


static jlong nativeOpen(JNIEnv *env, jclass cls, jint channels, jint rate, jint bitrate) {
    const AVCodec *codec = avcodec_find_encoder(AV_CODEC_ID_AC3);
    if (!codec) return 0;
    Encoder *e = calloc(1, sizeof(Encoder));
    e->channels = channels;
    e->ctx = avcodec_alloc_context3(codec);
    e->ctx->sample_rate = rate;
    e->ctx->bit_rate = bitrate;
    e->ctx->sample_fmt = AV_SAMPLE_FMT_FLTP;
    // Android's order (front left, front right, centre, LFE, then the two rears) is FFmpeg's 5.1 order too.
    av_channel_layout_default(&e->ctx->ch_layout, channels);
    if (avcodec_open2(e->ctx, codec, NULL) < 0) {
        avcodec_free_context(&e->ctx);
        free(e);
        return 0;
    }
    e->frame = av_frame_alloc();
    e->frame->nb_samples = e->ctx->frame_size;
    e->frame->format = e->ctx->sample_fmt;
    av_channel_layout_copy(&e->frame->ch_layout, &e->ctx->ch_layout);
    av_frame_get_buffer(e->frame, 0);
    e->pkt = av_packet_alloc();
    return (jlong) (intptr_t) e;
}

static jint nativeFrameSize(JNIEnv *env, jclass cls, jlong handle) {
    return ((Encoder *) (intptr_t) handle)->ctx->frame_size;
}

/** Encodes one frame of interleaved 16-bit samples; returns the bytes written to out, 0 if none yet, or < 0 on error. */
static jint nativeEncode(JNIEnv *env, jclass cls, jlong handle, jshortArray pcm, jbyteArray out) {
    Encoder *e = (Encoder *) (intptr_t) handle;
    int n = e->ctx->frame_size, ch = e->channels;
    if (av_frame_make_writable(e->frame) < 0) return -1;
    jshort *in = (*env)->GetShortArrayElements(env, pcm, NULL);
    for (int c = 0; c < ch; c++) {
        float *plane = (float *) e->frame->data[c];
        for (int i = 0; i < n; i++) plane[i] = in[i * ch + c] / 32768.0f;
    }
    (*env)->ReleaseShortArrayElements(env, pcm, in, JNI_ABORT);
    if (avcodec_send_frame(e->ctx, e->frame) < 0) return -2;
    int r = avcodec_receive_packet(e->ctx, e->pkt);
    if (r == AVERROR(EAGAIN)) return 0;
    if (r < 0) return -3;
    int size = e->pkt->size;
    if (size > (*env)->GetArrayLength(env, out)) size = -4;
    else (*env)->SetByteArrayRegion(env, out, 0, size, (jbyte *) e->pkt->data);
    av_packet_unref(e->pkt);
    return size;
}

static void nativeClose(JNIEnv *env, jclass cls, jlong handle) {
    Encoder *e = (Encoder *) (intptr_t) handle;
    if (!e) return;
    av_packet_free(&e->pkt);
    av_frame_free(&e->frame);
    avcodec_free_context(&e->ctx);
    free(e);
}

/* The Kotlin classes that can own these functions: one for each app that uses this library (MyTVPlayer, MyPlexPlayer). */
static const char *const CLASSES[] = {
    "com/biskyt/mytvplayer/player/Ac3Encoder",
    "com/biskyt/myplexplayer/player/Ac3Encoder",
};

static const JNINativeMethod METHODS[] = {
    {"nativeOpen", "(III)J", (void *) nativeOpen},
    {"nativeFrameSize", "(J)I", (void *) nativeFrameSize},
    {"nativeEncode", "(J[S[B)I", (void *) nativeEncode},
    {"nativeClose", "(J)V", (void *) nativeClose},
};

/* Registers the functions with the app's own class; with none of them, the library fails to load. */
JNIEXPORT __attribute__((visibility("default"))) jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    for (size_t i = 0; i < sizeof(CLASSES) / sizeof(CLASSES[0]); i++) {
        jclass cls = (*env)->FindClass(env, CLASSES[i]);
        if (cls == NULL) {
            (*env)->ExceptionClear(env);
            continue;
        }
        jint r = (*env)->RegisterNatives(env, cls, METHODS, sizeof(METHODS) / sizeof(METHODS[0]));
        (*env)->DeleteLocalRef(env, cls);
        if (r == JNI_OK) return JNI_VERSION_1_6;
        (*env)->ExceptionClear(env);
    }
    return JNI_ERR;
}
