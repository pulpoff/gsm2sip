//go:build arm || 386

package main

import "unsafe"

// 32-bit ALSA control ABI — see abi64.go for why this differs at all.
//
// Two of the three structs shrink, because `long` and the pids pointer halve:
//
//	snd_ctl_elem_list   80 -> 72   (pids pointer 8 -> 4, then padded to 4)
//	snd_ctl_elem_value  1224 -> 712 (union 1024 -> 512, timespec 16 -> 8 with
//	                                 reserved growing 112 -> 120 to match)
//
// snd_ctl_elem_id (64) and snd_ctl_elem_info (272) are the same width on both,
// so SNDRV_CTL_IOCTL_CARD_INFO and SNDRV_CTL_IOCTL_ELEM_INFO are shared and
// live in main.go.
const (
	// _IOWR('U', 0x10, snd_ctl_elem_list) — 72 bytes
	SNDRV_CTL_IOCTL_ELEM_LIST = 0xC0485510
	// _IOWR('U', 0x12, snd_ctl_elem_value) — 712 bytes
	SNDRV_CTL_IOCTL_ELEM_READ = 0xC2C85512
	// _IOWR('U', 0x13, snd_ctl_elem_value) — 712 bytes
	SNDRV_CTL_IOCTL_ELEM_WRITE = 0xC2C85513
)

// longSize is sizeof(long) — the stride of the BOOLEAN and INTEGER value
// arrays inside snd_ctl_elem_value.
const longSize = 4

// longCount is the number of `long` slots in the value union:
// union { long value[128]; ... } — 512 bytes here.
const longCount = 128

// snd_ctl_elem_list — 72 bytes
type elemList struct {
	Offset  uint32
	Space   uint32
	Used    uint32
	Count   uint32
	PidsPtr uint32 // pointer to the elemID array
	_       [72 - 4*4 - 4]byte
}

func (l *elemList) setPids(p uintptr) { l.PidsPtr = uint32(p) }

// snd_ctl_elem_value — 712 bytes
//
//	id(64) + indirect(4) + pad(4) + value union(512) + tstamp/reserved(128)
//
// The union is 512 bytes: long value[128] and long long value[64] are both
// 512 here, where on a 64-bit ABI the former is twice that.
type elemValue struct {
	ID       elemID    // 64
	Indirect uint32    // 4
	_pad     uint32    // 4 (the union is 8-aligned)
	Value    [512]byte // value union
	_rest    [128]byte // struct timespec + reserved — 128 on both ABIs
}

// The ioctl request numbers above encode these sizes, so a layout that did not
// match the kernel's would not fail loudly at runtime — the driver would
// simply reject every call with ENOTTY.  Pin them at compile time instead:
// each pair is only non-negative when the size is exactly right.
const (
	_ = uint(unsafe.Sizeof(elemID{}) - 64)
	_ = uint(64 - unsafe.Sizeof(elemID{}))
	_ = uint(unsafe.Sizeof(elemInfo{}) - 272)
	_ = uint(272 - unsafe.Sizeof(elemInfo{}))
	_ = uint(unsafe.Sizeof(elemList{}) - 72)
	_ = uint(72 - unsafe.Sizeof(elemList{}))
	_ = uint(unsafe.Sizeof(elemValue{}) - 712)
	_ = uint(712 - unsafe.Sizeof(elemValue{}))
)
