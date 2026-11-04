//go:build arm || 386

package main

import "unsafe"

// 32-bit ALSA PCM ABI — see abi64.go for why this differs at all.
//
// Every struct with a `long` in it shrinks:
//
//	snd_pcm_hw_params  608 -> 604  (fifo_size 8 -> 4)
//	snd_pcm_sw_params  136 -> 104  (seven longs 8 -> 4, no alignment pad)
//	snd_xferi           24 -> 12   (result, buf and frames 8 -> 4 each)
const (
	// _IOWR('A', 0x11, snd_pcm_hw_params) — 604 bytes
	SNDRV_PCM_IOCTL_HW_PARAMS = 0xC25C4111
	// _IOWR('A', 0x13, snd_pcm_sw_params) — 104 bytes
	SNDRV_PCM_IOCTL_SW_PARAMS = 0xC0684113
	// _IOW('A', 0x50, snd_xferi) — 12 bytes
	SNDRV_PCM_IOCTL_WRITEI_FRAMES = 0x400C4150
	// _IOR('A', 0x51, snd_xferi) — 12 bytes
	SNDRV_PCM_IOCTL_READI_FRAMES = 0x800C4151
)

// ulong is the kernel's `unsigned long` (snd_pcm_uframes_t).
type ulong = uint32

// swPad: the longs in snd_pcm_sw_params are 4-aligned here, so no padding.
const swPad = 0

// Pinned at compile time, as in abi64.go.
const (
	_ = uint(unsafe.Sizeof(hwParams{}) - 604)
	_ = uint(604 - unsafe.Sizeof(hwParams{}))
	_ = uint(unsafe.Sizeof(swParams{}) - 104)
	_ = uint(104 - unsafe.Sizeof(swParams{}))
	_ = uint(unsafe.Sizeof(xferi{}) - 12)
	_ = uint(12 - unsafe.Sizeof(xferi{}))
)
