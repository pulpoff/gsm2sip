#include "g722.h"
#include <string.h>

static const int QMF_COEFFS[12] = { 3, -11, 12, 32, -210, 951, 3876, -805, 362, -156, 53, -11 };
static const int q6[32] = {
	0, 35, 72, 110, 150, 190, 233, 276, 323, 370, 422, 473, 530, 587, 650, 714,
	786, 858, 940, 1023, 1121, 1219, 1339, 1458, 1612, 1765, 1980, 2195, 2557, 2919, 0, 0
};
static const int ilp[32] = {
	0, 61, 60, 59, 58, 57, 56, 55, 54, 53, 52, 51, 50, 49, 48, 47,
	46, 45, 44, 43, 42, 41, 40, 39, 38, 37, 36, 35, 34, 33, 32, 0
};
static const int iln[32] = {
	0, 63, 62, 31, 30, 29, 28, 27, 26, 25, 24, 23, 22, 21, 20, 19,
	18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 0
};
static const int qm4[16] = {
	0, -20456, -12896, -8968, -6288, -4240, -2584, -1200,
	20456, 12896, 8968, 6288, 4240, 2584, 1200, 0
};
static const int qm6[64] = {
	-136, -136, -136, -136, -24808, -21904, -19008, -16704,
	-14984, -13512, -12280, -11192, -10232, -9360, -8576, -7856,
	-7192, -6576, -6000, -5456, -4944, -4464, -4008, -3576,
	-3168, -2776, -2400, -2032, -1688, -1360, -1040, -728,
	24808, 21904, 19008, 16704, 14984, 13512, 12280, 11192,
	10232, 9360, 8576, 7856, 7192, 6576, 6000, 5456,
	4944, 4464, 4008, 3576, 3168, 2776, 2400, 2032,
	1688, 1360, 1040, 728, 432, 136, -432, -136
};
static const int ihp[3] = { 0, 3, 2 };
static const int ihn[3] = { 0, 1, 0 };
static const int qm2[4] = { -7408, -1616, 7408, 1616 };
static const int wl[8] = { -60, -30, 58, 172, 334, 538, 1198, 3042 };
static const int rl42[16] = { 0, 7, 6, 5, 4, 3, 2, 1, 7, 6, 5, 4, 3, 2, 1, 0 };
static const int wh[3] = { 0, -214, 798 };
static const int rh2[4] = { 2, 1, 2, 1 };
static const int ilb[32] = {
	2048, 2093, 2139, 2186, 2233, 2282, 2332, 2383, 2435, 2489, 2543, 2599, 2656, 2714, 2774, 2834,
	2896, 2960, 3025, 3091, 3158, 3228, 3298, 3371, 3444, 3520, 3597, 3676, 3756, 3838, 3922, 4008
};

static inline int sat(int x) { return x > 32767 ? 32767 : x < -32768 ? -32768 : x; }
static inline int clampi(int x, int lo, int hi) { return x < lo ? lo : x > hi ? hi : x; }
/* (a * b) >> n on 64 bits, as the Kotlin did with toLong(). Arithmetic shift. */
static inline int mulsh(int a, int b, int n) { return (int)(((int64_t)a * b) >> n); }

void g722_init(G722State *st)
{
	memset(st, 0, sizeof *st);
	st->band[0].det = 32;
	st->band[1].det = 8;
}

static void block4(G722Band *b, int d)
{
	int wd1, wd2, wd3;
	b->d[0] = d;
	b->r[0] = sat(b->s + d);
	b->p[0] = sat(b->sz + d);
	for (int i = 0; i < 3; i++) b->sg[i] = b->p[i] >> 15;
	wd1 = sat(b->a[1] * 4);
	wd2 = (b->sg[0] == b->sg[1]) ? -wd1 : wd1;
	if (wd2 > 32767) wd2 = 32767;
	wd3 = (b->sg[0] == b->sg[2]) ? 128 : -128;
	wd3 += wd2 >> 7;
	wd3 += mulsh(b->a[2], 32512, 15);
	b->ap[2] = clampi(wd3, -12288, 12288);
	b->sg[0] = b->p[0] >> 15;
	b->sg[1] = b->p[1] >> 15;
	wd1 = (b->sg[0] == b->sg[1]) ? 192 : -192;
	wd2 = mulsh(b->a[1], 32640, 15);
	b->ap[1] = sat(wd1 + wd2);
	wd3 = sat(15360 - b->ap[2]);
	if (b->ap[1] > wd3) b->ap[1] = wd3;
	else if (b->ap[1] < -wd3) b->ap[1] = -wd3;
	wd1 = (d == 0) ? 0 : 128;
	b->sg[0] = d >> 15;
	for (int i = 1; i < 7; i++) {
		b->sg[i] = b->d[i] >> 15;
		wd2 = (b->sg[i] == b->sg[0]) ? wd1 : -wd1;
		wd3 = mulsh(b->b[i], 32640, 15);
		b->bp[i] = sat(wd2 + wd3);
	}
	for (int i = 6; i >= 1; i--) {
		b->d[i] = b->d[i - 1];
		b->b[i] = b->bp[i];
	}
	for (int i = 2; i >= 1; i--) {
		b->r[i] = b->r[i - 1];
		b->p[i] = b->p[i - 1];
		b->a[i] = b->ap[i];
	}
	wd1 = sat(b->r[1] + b->r[1]);
	wd1 = mulsh(b->a[1], wd1, 15);
	wd2 = sat(b->r[2] + b->r[2]);
	wd2 = mulsh(b->a[2], wd2, 15);
	int sp = sat(wd1 + wd2);
	int sz = 0;
	for (int i = 6; i >= 1; i--) {
		wd1 = sat(b->d[i] + b->d[i]);
		sz += mulsh(b->b[i], wd1, 15);
	}
	b->sz = sat(sz);
	b->s = sat(sp + b->sz);
}

static int scale(int nb, int shift)
{
	int wd1 = (nb >> 6) & 31;
	int wd2 = shift - (nb >> 11);
	int wd3 = wd2 < 0 ? ilb[wd1] << -wd2 : ilb[wd1] >> wd2;
	return wd3 << 2;
}

static void block3l(G722Band *b, int ril)
{
	int wd = mulsh(b->nb, 127, 7);
	b->nb = clampi(wd + wl[rl42[ril]], 0, 18432);
	b->det = scale(b->nb, 8);
}

static void block3h(G722Band *b, int ihigh)
{
	int wd = mulsh(b->nb, 127, 7);
	b->nb = clampi(wd + wh[rh2[ihigh]], 0, 22528);
	b->det = scale(b->nb, 10);
}

void g722_encode(G722State *st, const int16_t *pcm, size_t n, uint8_t *out)
{
	G722Band *lo = &st->band[0], *hi = &st->band[1];
	for (size_t j = 0; j + 1 < n; j += 2) {
		memmove(st->x, st->x + 2, 22 * sizeof(int));
		st->x[22] = pcm[j];
		st->x[23] = pcm[j + 1];
		int64_t se = 0, so = 0;
		for (int i = 0; i < 12; i++) {
			so += (int64_t)st->x[2 * i] * QMF_COEFFS[i];
			se += (int64_t)st->x[2 * i + 1] * QMF_COEFFS[11 - i];
		}
		int xlow = (int)((se + so) >> 14);
		int xhigh = (int)((se - so) >> 14);

		int el = sat(xlow - lo->s);
		int sil = el >> 15;
		int wd = sil == 0 ? el : -(el + 1);
		int mil = 30;
		for (int i = 1; i < 30; i++) {
			if (wd < mulsh(q6[i], lo->det, 12)) { mil = i; break; }
		}
		int ilow = sil == 0 ? ilp[mil] : iln[mil];
		int ril = ilow >> 2;
		int dlow = mulsh(lo->det, qm4[ril], 15);
		block4(lo, dlow);
		block3l(lo, ril);

		int eh = sat(xhigh - hi->s);
		int sih = eh >> 15;
		int wdh = sih == 0 ? eh : -(eh + 1);
		int wdh1 = mulsh(564, hi->det, 12);
		int mih = wdh >= wdh1 ? 2 : 1;
		int ihigh = sih == 0 ? ihp[mih] : ihn[mih];
		int dhigh = mulsh(hi->det, qm2[ihigh], 15);
		block4(hi, dhigh);
		block3h(hi, ihigh);
		out[j / 2] = (uint8_t)((ihigh << 6) | ilow);
	}
}

void g722_decode(G722State *st, const uint8_t *code, size_t n, int16_t *out)
{
	G722Band *lo = &st->band[0], *hi = &st->band[1];
	for (size_t k = 0; k < n; k++) {
		int c = code[k];
		int ilow = c & 0x3f, ihigh = (c >> 6) & 3;
		int dlow = mulsh(lo->det, qm6[ilow], 15);
		int ril = ilow >> 2;
		int dlowt = mulsh(lo->det, qm4[ril], 15);
		int rlow = sat(lo->s + dlow);
		block4(lo, dlowt);
		block3l(lo, ril);
		int dhigh = mulsh(hi->det, qm2[ihigh], 15);
		int rhigh = sat(hi->s + dhigh);
		block4(hi, dhigh);
		block3h(hi, ihigh);

		memmove(st->x, st->x + 2, 22 * sizeof(int));
		st->x[22] = rlow + rhigh;
		st->x[23] = rlow - rhigh;
		int64_t se = 0, so = 0;
		for (int i = 0; i < 12; i++) {
			so += (int64_t)st->x[2 * i] * QMF_COEFFS[i];
			se += (int64_t)st->x[2 * i + 1] * QMF_COEFFS[11 - i];
		}
		out[2 * k] = (int16_t)sat((int)(se >> 11));
		out[2 * k + 1] = (int16_t)sat((int)(so >> 11));
	}
}
