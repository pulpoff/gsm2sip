/* ITU-T G.722, mode 1 (64 kbit/s): 16 kHz PCM <-> one byte per two samples.
 * A straight port of app/.../rtp/G722Codec.kt, which follows spandsp and the
 * ITU-T reference; the tables and the arithmetic are the same. */
#pragma once
#include <stdint.h>
#include <stddef.h>

typedef struct {
	int s, sz;
	int r[3], p[3], a[3], b[7], ap[3], bp[7], d[7], sg[7];
	int nb, det;
} G722Band;

typedef struct {
	G722Band band[2];
	int x[24];
} G722State;

void g722_init(G722State *st);
/* n samples in (even), n/2 bytes out. */
void g722_encode(G722State *st, const int16_t *pcm, size_t n, uint8_t *out);
/* n bytes in, 2n samples out. */
void g722_decode(G722State *st, const uint8_t *code, size_t n, int16_t *out);
