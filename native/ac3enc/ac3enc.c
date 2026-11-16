// The JNI wrapper around FFmpeg's AC-3 (Dolby Digital) encoder, for Ac3Encoder.kt. FFmpeg is built with nothing else.
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

#define EXPORT __attribute__((visibility("default")))

EXPORT JNIEXPORT jlong JNICALL
Java_com_biskyt_mytvplayer_player_Ac3Encoder_nativeOpen(JNIEnv *env, jclass cls, jint channels, jint rate, jint bitrate) {
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

EXPORT JNIEXPORT jint JNICALL
Java_com_biskyt_mytvplayer_player_Ac3Encoder_nativeFrameSize(JNIEnv *env, jclass cls, jlong handle) {
    return ((Encoder *) (intptr_t) handle)->ctx->frame_size;
}

/** Encodes one frame of interleaved 16-bit samples; returns the bytes written to out, 0 if none yet, or < 0 on error. */
EXPORT JNIEXPORT jint JNICALL
Java_com_biskyt_mytvplayer_player_Ac3Encoder_nativeEncode(JNIEnv *env, jclass cls, jlong handle, jshortArray pcm, jbyteArray out) {
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

EXPORT JNIEXPORT void JNICALL
Java_com_biskyt_mytvplayer_player_Ac3Encoder_nativeClose(JNIEnv *env, jclass cls, jlong handle) {
    Encoder *e = (Encoder *) (intptr_t) handle;
    if (!e) return;
    av_packet_free(&e->pkt);
    av_frame_free(&e->frame);
    avcodec_free_context(&e->ctx);
    free(e);
}
