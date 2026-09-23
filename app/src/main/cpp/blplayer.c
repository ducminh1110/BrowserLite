/*
 * BrowserLite built-in video player: H.264 / AAC / MP3 decoding with FFmpeg for devices whose ROM ships no usable
 * decoders at all. Frames are drawn straight into the SurfaceView's ANativeWindow (RGB565, or gray for e-ink
 * panels, which skips the colour conversion). Audio is pulled as 16-bit PCM by a Java AudioTrack thread; its play
 * head is the master clock that video frames wait for.
 *
 * Threads: demux (reads packets into two queues), video (decodes and presents), and the caller's audio thread.
 */
#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <stdarg.h>
#include <stdio.h>
#include <unistd.h>

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/cpu.h>
#include <libavutil/pixdesc.h>
#include <libavutil/time.h>

#define TAG "BLPlayer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

enum { ST_OPENING = 0, ST_READY, ST_PLAYING, ST_PAUSED, ST_BUFFERING, ST_ENDED, ST_ERROR };

#define MAX_QUEUE_PACKETS 600
#define NO_CLOCK INT64_MIN

typedef struct Node {
    AVPacket *pkt;
    int serial;
    struct Node *next;
} Node;

typedef struct {
    Node *first, *last;
    int count;
    int64_t bytes;
} Queue;

typedef struct {
    AVFormatContext *fmt;
    AVCodecContext *vdec, *adec;
    int vidx, aidx;
    int video_unsupported;

    pthread_mutex_t lock;
    pthread_cond_t cond;
    Queue vq, aq;
    int serial;
    int abort;
    int paused;
    int eof;
    int started;
    int seek_pending;
    int64_t seek_target_us;
    int64_t drop_before_us; /* after a seek, frames/samples before this are skipped */

    /* audio clock */
    int a_serial;
    int64_t a_start_pts_us; /* pts of the first sample written since the last flush */
    int64_t a_written;      /* sample frames written since the last flush */
    int64_t head_frames;
    int64_t head_time_us;
    int sample_rate, out_channels;
    AVFrame *aframe;
    int16_t *pcm;
    int pcm_cap, pcm_len, pcm_pos;
    int audio_done;

    /* wall clock (no audio, or audio that never started) */
    int64_t wall_base_us, wall_pts_us;
    int wall_fallback;

    /* video */
    pthread_t demux_thread, video_thread;
    pthread_mutex_t win_lock;
    ANativeWindow *win;
    int win_w, win_h;
    int gray, fps_cap;
    int64_t last_render_us;
    int64_t last_video_pts_us;
    int video_done;
    int frames_decoded, frames_shown, frames_late;
    int late_score, skip_level, frames_since_skip_change;
    int64_t max_queue_bytes;
    int64_t stats_at_us;
    int width, height;
    int64_t duration_us;
    int state;
    char error[200];
    uint16_t gray_lut[256], gray_lut_full[256];
} Player;

static int64_t now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000000 + ts.tv_nsec / 1000;
}

/* ------------------------------------------------------------------ packet queues (caller holds lock) */

static void q_push(Queue *q, AVPacket *pkt, int serial) {
    Node *n = malloc(sizeof(Node));
    if (!n) {
        av_packet_free(&pkt);
        return;
    }
    n->pkt = pkt;
    n->serial = serial;
    n->next = NULL;
    if (q->last) q->last->next = n;
    else q->first = n;
    q->last = n;
    q->count++;
    q->bytes += pkt ? pkt->size : 0;
}

static Node *q_pop(Queue *q) {
    Node *n = q->first;
    if (!n) return NULL;
    q->first = n->next;
    if (!q->first) q->last = NULL;
    q->count--;
    q->bytes -= n->pkt ? n->pkt->size : 0;
    return n;
}

static void q_clear(Queue *q) {
    Node *n;
    while ((n = q_pop(q)) != NULL) {
        av_packet_free(&n->pkt);
        free(n);
    }
}

/* ------------------------------------------------------------------ clock */

static int64_t ts_us(AVStream *st, int64_t ts) {
    if (ts == AV_NOPTS_VALUE) return NO_CLOCK;
    return av_rescale_q(ts, st->time_base, AV_TIME_BASE_Q);
}

/* Current playback position (caller holds lock). */
static int64_t clock_locked(Player *p) {
    if (p->aidx >= 0 && !p->audio_done && !(p->wall_fallback && p->a_start_pts_us == NO_CLOCK)) {
        if (p->a_start_pts_us == NO_CLOCK || p->sample_rate <= 0) return NO_CLOCK;
        int64_t c = p->a_start_pts_us + p->head_frames * 1000000 / p->sample_rate;
        if (!p->paused && p->head_time_us > 0) {
            int64_t extra = now_us() - p->head_time_us;
            if (extra > 300000) extra = 300000; /* audio stalled: don't run ahead */
            c += extra;
        }
        return c;
    }
    if (p->paused) return p->wall_pts_us;
    return p->wall_pts_us + (now_us() - p->wall_base_us);
}

/* ------------------------------------------------------------------ rendering */

static void build_luts(Player *p) {
    for (int y = 0; y < 256; y++) {
        int g = (y - 16) * 255 / 219;
        if (g < 0) g = 0;
        if (g > 255) g = 255;
        p->gray_lut[y] = (uint16_t) (((g >> 3) << 11) | ((g >> 2) << 5) | (g >> 3));
        p->gray_lut_full[y] = (uint16_t) (((y >> 3) << 11) | ((y >> 2) << 5) | (y >> 3));
    }
}

static inline int clamp8(int v) {
    return v < 0 ? 0 : v > 255 ? 255 : v;
}

static void draw(Player *p, AVFrame *f) {
    const AVPixFmtDescriptor *d = av_pix_fmt_desc_get((enum AVPixelFormat) f->format);
    if (!d || (d->flags & AV_PIX_FMT_FLAG_RGB) || d->nb_components < 1 || !(d->flags & AV_PIX_FMT_FLAG_PLANAR)) return;
    pthread_mutex_lock(&p->win_lock);
    ANativeWindow *win = p->win;
    if (!win) {
        pthread_mutex_unlock(&p->win_lock);
        return;
    }
    int w = f->width, h = f->height;
    if (w != p->win_w || h != p->win_h) {
        ANativeWindow_setBuffersGeometry(win, w, h, WINDOW_FORMAT_RGB_565);
        p->win_w = w;
        p->win_h = h;
    }
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(win, &buf, NULL) == 0) {
        int full = f->color_range == AVCOL_RANGE_JPEG || f->format == AV_PIX_FMT_YUVJ420P;
        int ww = w < buf.width ? w : buf.width, hh = h < buf.height ? h : buf.height;
        int cw = d->log2_chroma_w, ch = d->log2_chroma_h;
        int color = !p->gray && d->nb_components >= 3;
        for (int y = 0; y < hh; y++) {
            uint16_t *dst = (uint16_t *) buf.bits + (size_t) y * buf.stride;
            const uint8_t *Y = f->data[0] + (size_t) y * f->linesize[0];
            if (!color) {
                const uint16_t *lut = full ? p->gray_lut_full : p->gray_lut;
                for (int x = 0; x < ww; x++) dst[x] = lut[Y[x]];
                continue;
            }
            const uint8_t *U = f->data[1] + (size_t) (y >> ch) * f->linesize[1];
            const uint8_t *V = f->data[2] + (size_t) (y >> ch) * f->linesize[2];
            for (int x = 0; x < ww; x++) {
                int c = full ? Y[x] * 256 : (Y[x] - 16) * 298;
                int du = U[x >> cw] - 128, dv = V[x >> cw] - 128;
                int r = clamp8((c + 409 * dv + 128) >> 8);
                int g = clamp8((c - 100 * du - 208 * dv + 128) >> 8);
                int b = clamp8((c + 516 * du + 128) >> 8);
                dst[x] = (uint16_t) (((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3));
            }
        }
        ANativeWindow_unlockAndPost(win);
    }
    pthread_mutex_unlock(&p->win_lock);
}

/* ------------------------------------------------------------------ threads */

static int interrupt_cb(void *opaque) {
    Player *p = opaque;
    return p->abort;
}

static void *demux_main(void *arg) {
    Player *p = arg;
    AVPacket *pkt = NULL;
    while (1) {
        pthread_mutex_lock(&p->lock);
        if (p->abort) {
            pthread_mutex_unlock(&p->lock);
            break;
        }
        if (p->seek_pending) {
            int64_t target = p->seek_target_us;
            p->seek_pending = 0;
            pthread_mutex_unlock(&p->lock);
            int r = avformat_seek_file(p->fmt, -1, INT64_MIN, target, target, 0);
            if (r < 0) r = av_seek_frame(p->fmt, -1, target, AVSEEK_FLAG_BACKWARD);
            pthread_mutex_lock(&p->lock);
            q_clear(&p->vq);
            q_clear(&p->aq);
            p->serial++;
            p->eof = 0;
            p->video_done = 0;
            p->audio_done = 0;
            p->drop_before_us = target;
            p->wall_fallback = 0;
            p->wall_pts_us = target;
            p->wall_base_us = now_us();
            p->last_video_pts_us = target;
            pthread_cond_broadcast(&p->cond);
            pthread_mutex_unlock(&p->lock);
            continue;
        }
        if (p->eof || p->vq.bytes + p->aq.bytes > p->max_queue_bytes
                || (p->vq.count > MAX_QUEUE_PACKETS && (p->aidx < 0 || p->aq.count > 50))
                || (p->aq.count > MAX_QUEUE_PACKETS && (p->vidx < 0 || p->vq.count > 50))) {
            struct timespec ts;
            clock_gettime(CLOCK_REALTIME, &ts);
            ts.tv_nsec += 20 * 1000000;
            if (ts.tv_nsec >= 1000000000) {
                ts.tv_sec++;
                ts.tv_nsec -= 1000000000;
            }
            pthread_cond_timedwait(&p->cond, &p->lock, &ts);
            pthread_mutex_unlock(&p->lock);
            continue;
        }
        pthread_mutex_unlock(&p->lock);

        if (!pkt) pkt = av_packet_alloc();
        if (!pkt) break;
        int r = av_read_frame(p->fmt, pkt);
        if (r < 0) {
            pthread_mutex_lock(&p->lock);
            if (!p->seek_pending && !p->abort) {
                if (r == AVERROR(EAGAIN)) {
                    pthread_mutex_unlock(&p->lock);
                    av_usleep(10000);
                    continue;
                }
                p->eof = 1;
                if (r != AVERROR_EOF) LOGW("read error %d", r);
            }
            pthread_cond_broadcast(&p->cond);
            pthread_mutex_unlock(&p->lock);
            continue;
        }
        pthread_mutex_lock(&p->lock);
        if (pkt->stream_index == p->vidx && p->vdec) {
            q_push(&p->vq, pkt, p->serial);
            pkt = NULL;
        } else if (p->aidx >= 0 && pkt->stream_index == p->aidx && p->adec) {
            q_push(&p->aq, pkt, p->serial);
            pkt = NULL;
        } else {
            av_packet_unref(pkt);
        }
        pthread_cond_broadcast(&p->cond);
        pthread_mutex_unlock(&p->lock);
    }
    av_packet_free(&pkt);
    return NULL;
}

/* Waits until the frame's time; returns 0 to present, 1 to drop (late), 2 to present although late (no sound to
 * stay in sync with: the clock follows the picture instead), -1 when a seek/abort superseded it. */
static int wait_for(Player *p, int64_t pts, int serial) {
    int64_t waited = 0;
    while (1) {
        pthread_mutex_lock(&p->lock);
        if (p->abort || p->serial != serial || p->seek_pending) {
            pthread_mutex_unlock(&p->lock);
            return -1;
        }
        int paused = p->paused;
        int64_t c = clock_locked(p);
        if (c == NO_CLOCK && waited > 1500000) {
            /* Audio never started (or stalled): run on the wall clock instead. */
            p->wall_pts_us = pts;
            p->wall_base_us = now_us();
            p->wall_fallback = 1;
            c = pts;
        }
        pthread_mutex_unlock(&p->lock);
        if (paused || c == NO_CLOCK) {
            av_usleep(10000);
            if (!paused) waited += 10000;
            continue;
        }
        int64_t diff = pts - c;
        if (diff <= 2000) {
            if (diff >= -150000) return 0;
            pthread_mutex_lock(&p->lock);
            int silent = p->aidx < 0 || p->audio_done || p->wall_fallback;
            if (silent) {
                p->wall_pts_us = pts;
                p->wall_base_us = now_us();
            }
            pthread_mutex_unlock(&p->lock);
            return silent ? 2 : (diff < -200000 ? 1 : 0);
        }
        int64_t s = diff > 20000 ? 20000 : diff;
        av_usleep((unsigned) s);
    }
}

/*
 * Weak CPUs: when frames keep arriving late, stop deblocking and decoding the frames nothing else references (the
 * picture gets a little softer and runs at half rate), and go back once decoding keeps up. Only non-reference work
 * is ever skipped: skipping it on reference frames would smear errors into every following frame.
 */
static void adapt_skipping(Player *p, int late) {
    p->late_score = p->late_score * 7 / 8 + (late ? 32 : 0);
    p->frames_since_skip_change++;
    int level = p->skip_level;
    if (level == 0 && p->late_score > 16 && p->frames_since_skip_change >= 25) level = 1;
    else if (level == 1 && p->late_score < 2 && p->frames_since_skip_change >= 250) level = 0; /* ~10 s calm */
    if (level == p->skip_level) return;
    p->skip_level = level;
    p->frames_since_skip_change = 0;
    p->vdec->skip_frame = level >= 1 ? AVDISCARD_NONREF : AVDISCARD_DEFAULT;
    LOGI("decoder load: skip level %d", level);
}

static void *video_main(void *arg) {
    Player *p = arg;
    AVFrame *f = av_frame_alloc();
    int cur_serial = -1, drained = 0, late = 0;
    AVStream *st = p->fmt->streams[p->vidx];
    while (f) {
        pthread_mutex_lock(&p->lock);
        while (!p->abort && p->vq.count == 0 && !(p->eof && !drained)) {
            if (p->eof && drained) p->video_done = 1;
            pthread_cond_wait(&p->cond, &p->lock);
        }
        if (p->abort) {
            pthread_mutex_unlock(&p->lock);
            break;
        }
        Node *n = q_pop(&p->vq);
        int serial = p->serial;
        int64_t drop_before = p->drop_before_us;
        pthread_cond_broadcast(&p->cond);
        pthread_mutex_unlock(&p->lock);

        if (n && n->serial != serial) {
            av_packet_free(&n->pkt);
            free(n);
            continue;
        }
        if (serial != cur_serial) {
            avcodec_flush_buffers(p->vdec);
            cur_serial = serial;
            drained = 0;
        }
        if (n) {
            avcodec_send_packet(p->vdec, n->pkt);
            av_packet_free(&n->pkt);
            free(n);
        } else {
            avcodec_send_packet(p->vdec, NULL); /* end of stream: flush the decoder's delayed frames */
            drained = 1;
        }
        while (avcodec_receive_frame(p->vdec, f) == 0) {
            int64_t pts = ts_us(st, f->best_effort_timestamp);
            if (pts == NO_CLOCK) pts = p->last_video_pts_us + 33000;
            if (drop_before != NO_CLOCK && pts < drop_before - 40000) {
                av_frame_unref(f);
                continue;
            }
            int w = wait_for(p, pts, serial);
            if (w < 0) {
                av_frame_unref(f);
                break;
            }
            pthread_mutex_lock(&p->lock);
            p->last_video_pts_us = pts;
            p->width = f->width;
            p->height = f->height;
            pthread_mutex_unlock(&p->lock);
            int64_t t = now_us();
            int capped = p->fps_cap > 0 && t - p->last_render_us < 1000000 / p->fps_cap;
            p->frames_decoded++;
            if (w != 0) p->frames_late++;
            adapt_skipping(p, w != 0);
            if ((w != 1 || ++late > 4) && !capped) {
                draw(p, f);
                p->last_render_us = t;
                p->frames_shown++;
                late = 0;
            }
            if (t - p->stats_at_us > 10000000) {
                if (p->stats_at_us) LOGI("video: %d decoded, %d shown, %d late in %lld ms", p->frames_decoded,
                        p->frames_shown, p->frames_late, (long long) ((t - p->stats_at_us) / 1000));
                p->stats_at_us = t;
                p->frames_decoded = p->frames_shown = p->frames_late = 0;
            }
            av_frame_unref(f);
        }
        if (drained) {
            pthread_mutex_lock(&p->lock);
            if (p->serial == cur_serial && p->eof && p->vq.count == 0) p->video_done = 1;
            pthread_cond_broadcast(&p->cond);
            pthread_mutex_unlock(&p->lock);
        }
    }
    av_frame_free(&f);
    return NULL;
}

/* ------------------------------------------------------------------ audio (pulled from Java) */

static int convert_audio(Player *p, AVFrame *f) {
    int ch = f->ch_layout.nb_channels > 0 ? f->ch_layout.nb_channels : 1;
    int oc = p->out_channels;
    int n = f->nb_samples;
    int need = n * oc;
    if (need > p->pcm_cap) {
        int16_t *np = realloc(p->pcm, (size_t) need * sizeof(int16_t));
        if (!np) return 0;
        p->pcm = np;
        p->pcm_cap = need;
    }
    int planar = av_sample_fmt_is_planar((enum AVSampleFormat) f->format);
    enum AVSampleFormat packed = av_get_packed_sample_fmt((enum AVSampleFormat) f->format);
    for (int i = 0; i < n; i++) {
        for (int c = 0; c < oc; c++) {
            int src = c < ch ? c : ch - 1;
            int v = 0;
            const uint8_t *base = planar ? f->extended_data[src] : f->extended_data[0];
            int idx = planar ? i : i * ch + src;
            switch (packed) {
                case AV_SAMPLE_FMT_FLT: {
                    float s = ((const float *) base)[idx];
                    v = (int) (s * 32767.0f);
                    break;
                }
                case AV_SAMPLE_FMT_S16:
                    v = ((const int16_t *) base)[idx];
                    break;
                case AV_SAMPLE_FMT_S32:
                    v = ((const int32_t *) base)[idx] >> 16;
                    break;
                case AV_SAMPLE_FMT_DBL:
                    v = (int) (((const double *) base)[idx] * 32767.0);
                    break;
                default:
                    v = 0;
            }
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            p->pcm[i * oc + c] = (int16_t) v;
        }
    }
    p->pcm_len = need;
    p->pcm_pos = 0;
    return n;
}

/* Returns samples written (interleaved shorts), 0 when nothing is ready yet, -1 at the end, -2 when the caller must
 * flush its AudioTrack (after a seek) and start counting its play head from zero again. */
static int fill_audio(Player *p, int16_t *out, int cap, int64_t head) {
    pthread_mutex_lock(&p->lock);
    p->head_frames = head;
    p->head_time_us = now_us();
    int serial = p->serial;
    pthread_mutex_unlock(&p->lock);
    if (serial != p->a_serial) {
        avcodec_flush_buffers(p->adec);
        pthread_mutex_lock(&p->lock);
        p->a_serial = serial;
        p->a_written = 0;
        p->a_start_pts_us = NO_CLOCK;
        p->head_frames = 0;
        p->pcm_len = p->pcm_pos = 0;
        pthread_mutex_unlock(&p->lock);
        return -2;
    }
    int written = 0;
    AVStream *st = p->fmt->streams[p->aidx];
    while (written < cap) {
        if (p->pcm_pos < p->pcm_len) {
            int n = p->pcm_len - p->pcm_pos;
            if (n > cap - written) n = cap - written;
            memcpy(out + written, p->pcm + p->pcm_pos, (size_t) n * sizeof(int16_t));
            p->pcm_pos += n;
            written += n;
            continue;
        }
        int r = avcodec_receive_frame(p->adec, p->aframe);
        if (r == 0) {
            int64_t pts = ts_us(st, p->aframe->best_effort_timestamp);
            int64_t drop = p->drop_before_us;
            int64_t dur = (int64_t) p->aframe->nb_samples * 1000000 / (p->sample_rate > 0 ? p->sample_rate : 44100);
            if (drop != NO_CLOCK && pts != NO_CLOCK && pts + dur < drop) {
                av_frame_unref(p->aframe);
                continue;
            }
            if (p->aframe->sample_rate != p->sample_rate) {
                /* Mid-stream rate changes are rare (HE-AAC signalling); play on rather than garble timing. */
            }
            if (convert_audio(p, p->aframe) > 0) {
                pthread_mutex_lock(&p->lock);
                if (p->a_start_pts_us == NO_CLOCK) p->a_start_pts_us = pts != NO_CLOCK ? pts : p->last_video_pts_us;
                p->a_written += p->aframe->nb_samples;
                pthread_mutex_unlock(&p->lock);
            }
            av_frame_unref(p->aframe);
            continue;
        }
        if (r == AVERROR_EOF) {
            pthread_mutex_lock(&p->lock);
            p->audio_done = 1;
            pthread_cond_broadcast(&p->cond);
            pthread_mutex_unlock(&p->lock);
            return written > 0 ? written : -1;
        }
        /* EAGAIN: feed a packet */
        pthread_mutex_lock(&p->lock);
        if (p->aq.count == 0 && !p->eof && !p->abort && p->serial == serial) {
            struct timespec ts;
            clock_gettime(CLOCK_REALTIME, &ts);
            ts.tv_nsec += 30 * 1000000;
            if (ts.tv_nsec >= 1000000000) {
                ts.tv_sec++;
                ts.tv_nsec -= 1000000000;
            }
            pthread_cond_timedwait(&p->cond, &p->lock, &ts);
        }
        if (p->abort || p->serial != serial) {
            pthread_mutex_unlock(&p->lock);
            return written;
        }
        Node *n = q_pop(&p->aq);
        int eof = p->eof;
        pthread_cond_broadcast(&p->cond);
        pthread_mutex_unlock(&p->lock);
        if (!n) {
            if (eof) {
                avcodec_send_packet(p->adec, NULL);
                continue;
            }
            return written; /* buffering */
        }
        if (n->serial == serial) avcodec_send_packet(p->adec, n->pkt);
        av_packet_free(&n->pkt);
        free(n);
    }
    return written;
}

/* ------------------------------------------------------------------ open / close */

static AVCodecContext *open_decoder(AVStream *st, int threads) {
    const AVCodec *codec = avcodec_find_decoder(st->codecpar->codec_id);
    if (!codec) return NULL;
    AVCodecContext *c = avcodec_alloc_context3(codec);
    if (!c) return NULL;
    if (avcodec_parameters_to_context(c, st->codecpar) < 0) {
        avcodec_free_context(&c);
        return NULL;
    }
    c->pkt_timebase = st->time_base;
    if (codec->type == AVMEDIA_TYPE_VIDEO) {
        c->thread_count = threads;
        c->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
        c->flags2 |= AV_CODEC_FLAG2_FAST;
        /* Speed first: no deblocking on frames nothing references (saves ~10%, barely visible, never propagates). */
        c->skip_loop_filter = AVDISCARD_NONREF;
    }
    if (avcodec_open2(c, codec, NULL) < 0) {
        avcodec_free_context(&c);
        return NULL;
    }
    return c;
}

static void player_free(Player *p) {
    if (!p) return;
    pthread_mutex_lock(&p->lock);
    p->abort = 1;
    pthread_cond_broadcast(&p->cond);
    int started = p->started;
    pthread_mutex_unlock(&p->lock);
    if (started) {
        pthread_join(p->demux_thread, NULL);
        if (p->vdec) pthread_join(p->video_thread, NULL);
    }
    q_clear(&p->vq);
    q_clear(&p->aq);
    if (p->vdec) avcodec_free_context(&p->vdec);
    if (p->adec) avcodec_free_context(&p->adec);
    if (p->fmt) avformat_close_input(&p->fmt);
    av_frame_free(&p->aframe);
    free(p->pcm);
    pthread_mutex_lock(&p->win_lock);
    if (p->win) ANativeWindow_release(p->win);
    p->win = NULL;
    pthread_mutex_unlock(&p->win_lock);
    pthread_mutex_destroy(&p->lock);
    pthread_mutex_destroy(&p->win_lock);
    pthread_cond_destroy(&p->cond);
    free(p);
}

static Player *player_open(const char *url, int gray, int fps_cap, int want_video, int want_audio, int threads,
        int queue_kb) {
    Player *p = calloc(1, sizeof(Player));
    if (!p) return NULL;
    pthread_mutex_init(&p->lock, NULL);
    pthread_mutex_init(&p->win_lock, NULL);
    pthread_cond_init(&p->cond, NULL);
    p->vidx = p->aidx = -1;
    p->gray = gray;
    p->fps_cap = fps_cap;
    p->state = ST_OPENING;
    p->max_queue_bytes = (int64_t) (queue_kb > 256 ? queue_kb : 256) * 1024;
    p->a_start_pts_us = NO_CLOCK;
    p->drop_before_us = NO_CLOCK;
    p->wall_base_us = now_us();
    build_luts(p);

    p->fmt = avformat_alloc_context();
    if (!p->fmt) goto fail;
    p->fmt->interrupt_callback.callback = interrupt_cb;
    p->fmt->interrupt_callback.opaque = p;
    AVDictionary *opts = NULL;
    av_dict_set(&opts, "rw_timeout", "20000000", 0);
    av_dict_set(&opts, "reconnect", "1", 0);
    av_dict_set(&opts, "analyzeduration", "2000000", 0);
    av_dict_set(&opts, "probesize", "1000000", 0);
    int r = avformat_open_input(&p->fmt, url, NULL, &opts);
    av_dict_free(&opts);
    if (r < 0) {
        char e[128];
        av_strerror(r, e, sizeof(e));
        snprintf(p->error, sizeof(p->error), "open: %s", e);
        p->fmt = NULL;
        goto fail;
    }
    if (avformat_find_stream_info(p->fmt, NULL) < 0) {
        snprintf(p->error, sizeof(p->error), "no stream info");
        goto fail;
    }
    long cores = sysconf(_SC_NPROCESSORS_ONLN);
    if (threads <= 0) threads = (int) (cores > 4 ? 4 : cores < 1 ? 1 : cores);
    int vi = want_video ? av_find_best_stream(p->fmt, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0) : -1;
    int ai = want_audio ? av_find_best_stream(p->fmt, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0) : -1;
    if (vi >= 0) {
        p->vdec = open_decoder(p->fmt->streams[vi], threads);
        if (p->vdec) {
            p->vidx = vi;
            p->width = p->vdec->width;
            p->height = p->vdec->height;
        } else {
            p->video_unsupported = 1;
        }
    }
    if (ai >= 0) {
        p->adec = open_decoder(p->fmt->streams[ai], 1);
        if (p->adec) {
            p->aidx = ai;
            p->sample_rate = p->adec->sample_rate > 0 ? p->adec->sample_rate : 44100;
            int ch = p->adec->ch_layout.nb_channels;
            p->out_channels = ch >= 2 ? 2 : 1;
            p->aframe = av_frame_alloc();
        }
    }
    if (p->vidx < 0 && p->aidx < 0) {
        snprintf(p->error, sizeof(p->error), "no decodable stream");
        goto fail;
    }
    for (unsigned i = 0; i < p->fmt->nb_streams; i++) {
        if ((int) i != p->vidx && (int) i != p->aidx) p->fmt->streams[i]->discard = AVDISCARD_ALL;
    }
    p->duration_us = p->fmt->duration != AV_NOPTS_VALUE ? p->fmt->duration : 0;
    p->state = ST_READY;
    LOGI("opened %s: video %d (%dx%d, %d threads) audio %d (%d Hz, %d ch), %lld ms, queue %d KB", p->fmt->iformat->name,
            p->vidx, p->width, p->height, threads, p->aidx, p->sample_rate, p->out_channels,
            (long long) (p->duration_us / 1000), queue_kb);
    return p;
fail:
    p->state = ST_ERROR;
    return p;
}

/* ------------------------------------------------------------------ JNI */

#define PLAYER(h) ((Player *) (intptr_t) (h))

static jlong JNICALL n_open(JNIEnv *env, jclass cls, jstring jurl, jboolean gray, jint fps, jboolean video,
        jboolean audio, jint threads, jint queue_kb) {
    const char *url = (*env)->GetStringUTFChars(env, jurl, NULL);
    Player *p = player_open(url, gray, fps, video, audio, threads, queue_kb);
    (*env)->ReleaseStringUTFChars(env, jurl, url);
    return (jlong) (intptr_t) p;
}

static jstring JNICALL n_error(JNIEnv *env, jclass cls, jlong h) {
    Player *p = PLAYER(h);
    return (*env)->NewStringUTF(env, p ? p->error : "out of memory");
}

static jintArray JNICALL n_info(JNIEnv *env, jclass cls, jlong h) {
    Player *p = PLAYER(h);
    jint v[8] = {0};
    if (p) {
        pthread_mutex_lock(&p->lock);
        v[0] = p->width;
        v[1] = p->height;
        v[2] = (jint) (p->duration_us / 1000);
        v[3] = p->vidx >= 0;
        v[4] = p->aidx >= 0;
        v[5] = p->sample_rate;
        v[6] = p->out_channels;
        v[7] = p->video_unsupported;
        pthread_mutex_unlock(&p->lock);
    }
    jintArray a = (*env)->NewIntArray(env, 8);
    if (a) (*env)->SetIntArrayRegion(env, a, 0, 8, v);
    return a;
}

static void JNICALL n_start(JNIEnv *env, jclass cls, jlong h) {
    Player *p = PLAYER(h);
    if (!p || p->state == ST_ERROR || p->started) return;
    pthread_mutex_lock(&p->lock);
    p->started = 1;
    p->state = ST_PLAYING;
    p->wall_base_us = now_us();
    pthread_mutex_unlock(&p->lock);
    pthread_create(&p->demux_thread, NULL, demux_main, p);
    if (p->vdec) pthread_create(&p->video_thread, NULL, video_main, p);
}

static void JNICALL n_surface(JNIEnv *env, jclass cls, jlong h, jobject surface) {
    Player *p = PLAYER(h);
    if (!p) return;
    ANativeWindow *w = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    pthread_mutex_lock(&p->win_lock);
    if (p->win) ANativeWindow_release(p->win);
    p->win = w;
    p->win_w = p->win_h = 0;
    pthread_mutex_unlock(&p->win_lock);
}

static jint JNICALL n_fill(JNIEnv *env, jclass cls, jlong h, jshortArray buf, jlong head) {
    Player *p = PLAYER(h);
    if (!p || !p->adec || p->state == ST_ERROR) return -1;
    jint cap = (*env)->GetArrayLength(env, buf);
    jshort *out = (*env)->GetShortArrayElements(env, buf, NULL);
    if (!out) return 0;
    int n = fill_audio(p, out, cap, head);
    (*env)->ReleaseShortArrayElements(env, buf, out, 0);
    return n;
}

static void JNICALL n_pause(JNIEnv *env, jclass cls, jlong h, jboolean paused) {
    Player *p = PLAYER(h);
    if (!p) return;
    pthread_mutex_lock(&p->lock);
    if (paused && !p->paused) {
        p->wall_pts_us = clock_locked(p);
        if (p->wall_pts_us == NO_CLOCK) p->wall_pts_us = p->last_video_pts_us;
    }
    p->paused = paused;
    if (!paused) {
        p->wall_base_us = now_us();
        p->head_time_us = now_us();
    }
    pthread_cond_broadcast(&p->cond);
    pthread_mutex_unlock(&p->lock);
}

static void JNICALL n_drop_audio(JNIEnv *env, jclass cls, jlong h) {
    Player *p = PLAYER(h);
    if (!p || p->aidx < 0) return;
    pthread_mutex_lock(&p->lock);
    p->fmt->streams[p->aidx]->discard = AVDISCARD_ALL;
    p->aidx = -1;
    q_clear(&p->aq);
    p->wall_pts_us = p->last_video_pts_us;
    p->wall_base_us = now_us();
    pthread_cond_broadcast(&p->cond);
    pthread_mutex_unlock(&p->lock);
}

static void JNICALL n_seek(JNIEnv *env, jclass cls, jlong h, jlong ms) {
    Player *p = PLAYER(h);
    if (!p) return;
    pthread_mutex_lock(&p->lock);
    int64_t start = p->fmt && p->fmt->start_time != AV_NOPTS_VALUE ? p->fmt->start_time : 0;
    p->seek_target_us = ms * 1000 + start;
    p->seek_pending = 1;
    pthread_cond_broadcast(&p->cond);
    pthread_mutex_unlock(&p->lock);
}

static jlong JNICALL n_position(JNIEnv *env, jclass cls, jlong h) {
    Player *p = PLAYER(h);
    if (!p) return 0;
    pthread_mutex_lock(&p->lock);
    int64_t c = p->seek_pending ? p->seek_target_us : clock_locked(p);
    if (c == NO_CLOCK) c = p->last_video_pts_us;
    int64_t start = p->fmt && p->fmt->start_time != AV_NOPTS_VALUE ? p->fmt->start_time : 0;
    pthread_mutex_unlock(&p->lock);
    c -= start;
    return c < 0 ? 0 : c / 1000;
}

static jint JNICALL n_state(JNIEnv *env, jclass cls, jlong h) {
    Player *p = PLAYER(h);
    if (!p) return ST_ERROR;
    pthread_mutex_lock(&p->lock);
    int s = p->state;
    if (s == ST_PLAYING) {
        int vdone = p->vidx < 0 || p->video_done;
        int adone = p->aidx < 0 || p->audio_done;
        if (p->eof && vdone && adone && p->vq.count == 0 && p->aq.count == 0) s = ST_ENDED;
        else if (p->paused) s = ST_PAUSED;
        else if (!p->eof && p->vq.count == 0 && p->aq.count == 0) s = ST_BUFFERING;
    }
    pthread_mutex_unlock(&p->lock);
    return s;
}

static void JNICALL n_close(JNIEnv *env, jclass cls, jlong h) {
    player_free(PLAYER(h));
}

static const JNINativeMethod METHODS[] = {
        {"open", "(Ljava/lang/String;ZIZZII)J", (void *) n_open},
        {"error", "(J)Ljava/lang/String;", (void *) n_error},
        {"info", "(J)[I", (void *) n_info},
        {"start", "(J)V", (void *) n_start},
        {"setSurface", "(JLandroid/view/Surface;)V", (void *) n_surface},
        {"fillAudio", "(J[SJ)I", (void *) n_fill},
        {"pause", "(JZ)V", (void *) n_pause},
        {"dropAudio", "(J)V", (void *) n_drop_audio},
        {"seek", "(JJ)V", (void *) n_seek},
        {"position", "(J)J", (void *) n_position},
        {"state", "(J)I", (void *) n_state},
        {"close", "(J)V", (void *) n_close},
};

/* FFmpeg's warnings (decode errors, network trouble) to logcat, a limited number per process. */
static void log_cb(void *avcl, int level, const char *fmt, va_list vl) {
    static int count;
    if (level > AV_LOG_WARNING || count > 200) return;
    count++;
    __android_log_vprint(level <= AV_LOG_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, "FFmpeg", fmt, vl);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return -1;
    jclass cls = (*env)->FindClass(env, "com/browserlite/video/NativePlayer");
    if (!cls) return -1;
    if ((*env)->RegisterNatives(env, cls, METHODS, sizeof(METHODS) / sizeof(METHODS[0])) != 0) return -1;
    avformat_network_init();
    av_log_set_callback(log_cb);
#ifdef BL_NO_SIMD
    av_force_cpu_flags(0); /* experiment: plain C code paths */
#endif
    return JNI_VERSION_1_6;
}
