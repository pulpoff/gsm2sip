//go:build arm64 || amd64

package main

import "unsafe"

// 64-bit ALSA PCM ABI.
//
// The uapi structs embed `long`, so their size — and with it the ioctl request
// number, which encodes that size — depends on the word size of the userspace
// the binary is built for.  A binary built for the wrong width does not fail
// loudly: the ioctl numbers simply do not match anything the driver handles
// and every call returns ENOTTY.
//
// _IOC(dir, type, nr, size) = (dir << 30) | (size << 16) | (type << 8) | nr,
// with the ALSA PCM type 'A' = 0x41.
const (
	// _IOWR('A', 0x11, snd_pcm_hw_params) — 608 bytes
	SNDRV_PCM_IOCTL_HW_PARAMS = 0xC2604111
	// _IOWR('A', 0x13, snd_pcm_sw_params) — 136 bytes
	SNDRV_PCM_IOCTL_SW_PARAMS = 0xC0884113
	// _IOW('A', 0x50, snd_xferi) — 24 bytes
	SNDRV_PCM_IOCTL_WRITEI_FRAMES = 0x40184150
	// _IOR('A', 0x51, snd_xferi) — 24 bytes
	SNDRV_PCM_IOCTL_READI_FRAMES = 0x80184151
)

// ulong is the kernel's `unsigned long` (snd_pcm_uframes_t).
type ulong = uint64

// swPad pads snd_pcm_sw_params' three leading ints to the 8-byte alignment
// of the longs after them.
const swPad = 4

// The ioctl request numbers above encode these sizes, so a layout that did not
// match the kernel's would not fail loudly at runtime — the driver would
// simply reject every call with ENOTTY.  Pin them at compile time instead:
// each pair is only non-negative when the size is exactly right.
const (
	_ = uint(unsafe.Sizeof(hwParams{}) - 608)
	_ = uint(608 - unsafe.Sizeof(hwParams{}))
	_ = uint(unsafe.Sizeof(swParams{}) - 136)
	_ = uint(136 - unsafe.Sizeof(swParams{}))
	_ = uint(unsafe.Sizeof(xferi{}) - 24)
	_ = uint(24 - unsafe.Sizeof(xferi{}))
)
