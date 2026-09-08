//go:build arm64 || amd64

package main

import "unsafe"

// 64-bit ALSA control ABI.
//
// The uapi structs embed `long`, so their size — and with it the ioctl request
// number, which encodes that size — depends on the word size of the userspace
// the binary is built for.  A binary built for the wrong width does not fail
// loudly: the ioctl numbers simply do not match anything the driver handles
// and every call returns ENOTTY.
//
// _IOC(dir, type, nr, size) = (dir << 30) | (size << 16) | (type << 8) | nr,
// with dir = _IOWR = 3 and the ALSA control type 'U' = 0x55.
const (
	// _IOWR('U', 0x10, snd_ctl_elem_list) — 80 bytes
	SNDRV_CTL_IOCTL_ELEM_LIST = 0xC0505510
	// _IOWR('U', 0x12, snd_ctl_elem_value) — 1224 bytes
	SNDRV_CTL_IOCTL_ELEM_READ = 0xC4C85512
	// _IOWR('U', 0x13, snd_ctl_elem_value) — 1224 bytes
	SNDRV_CTL_IOCTL_ELEM_WRITE = 0xC4C85513
)

// longSize is sizeof(long) — the stride of the BOOLEAN and INTEGER value
// arrays inside snd_ctl_elem_value.
const longSize = 8

// longCount is the number of `long` slots in the value union:
// union { long value[128]; ... } — 1024 bytes here.
const longCount = 128

// snd_ctl_elem_list — 80 bytes
//
//	offset/space/used/count(16) + pids pointer(8) + reserved[50], padded to 8
type elemList struct {
	Offset  uint32
	Space   uint32
	Used    uint32
	Count   uint32
	PidsPtr uint64 // pointer to the elemID array
	_       [80 - 4*4 - 8]byte
}

func (l *elemList) setPids(p uintptr) { l.PidsPtr = uint64(p) }

// snd_ctl_elem_value — 1224 bytes
//
//	id(64) + indirect(4) + pad(4) + value union(1024) + tstamp/reserved(128)
//
// The union is 1024 bytes because its widest member is long value[128].
type elemValue struct {
	ID       elemID     // 64
	Indirect uint32     // 4
	_pad     uint32     // 4 (the union is 8-aligned)
	Value    [1024]byte // value union
	_rest    [128]byte  // struct timespec + reserved — 128 on both ABIs
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
	_ = uint(unsafe.Sizeof(elemList{}) - 80)
	_ = uint(80 - unsafe.Sizeof(elemList{}))
	_ = uint(unsafe.Sizeof(elemValue{}) - 1224)
	_ = uint(1224 - unsafe.Sizeof(elemValue{}))
)
