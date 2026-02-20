// Minimal tinymix for Android ARM64 — cross-compiled in Go.
// Interacts with ALSA mixer via /dev/snd/controlC0 ioctls.
// No external dependencies (no libtinyalsa needed).
//
// Usage:
//   tinymix                     — list all controls
//   tinymix <id>                — get control by numeric ID
//   tinymix <id> <value>        — set control by numeric ID
//   tinymix '<name>'            — get control by name
//   tinymix '<name>' <value>    — set control by name (enum by name or index)
//   tinymix -e '<name>'         — dump enum items with hex (diagnostic)
//   tinymix -D <card> ...       — use specific ALSA card
package main

import (
	"encoding/binary"
	"fmt"
	"os"
	"strconv"
	"strings"
	"syscall"
	"unsafe"
)

// ALSA ioctl numbers for aarch64 (same as x86_64)
// _IOC(dir, type, nr, size) = (dir << 30) | (size << 16) | (type << 8) | nr
// ALSA control type = 'U' = 0x55
const (
	// _IOR('U', 0x01, snd_ctl_card_info) — 376 bytes
	SNDRV_CTL_IOCTL_CARD_INFO = 0x81785501
	// _IOWR('U', 0x10, snd_ctl_elem_list) — 80 bytes on 64-bit
	SNDRV_CTL_IOCTL_ELEM_LIST = 0xC0505510
	// _IOWR('U', 0x11, snd_ctl_elem_info) — 272 bytes
	SNDRV_CTL_IOCTL_ELEM_INFO = 0xC1105511
	// _IOWR('U', 0x12, snd_ctl_elem_value) — 1224 bytes on 64-bit
	// (long value[128] = 1024 bytes on aarch64)
	SNDRV_CTL_IOCTL_ELEM_READ = 0xC4C85512
	// _IOWR('U', 0x13, snd_ctl_elem_value) — 1224 bytes on 64-bit
	SNDRV_CTL_IOCTL_ELEM_WRITE = 0xC4C85513
)

// ALSA element types
const (
	SND_CTL_ELEM_TYPE_NONE       = 0
	SND_CTL_ELEM_TYPE_BOOLEAN    = 1
	SND_CTL_ELEM_TYPE_INTEGER    = 2
	SND_CTL_ELEM_TYPE_ENUMERATED = 3
	SND_CTL_ELEM_TYPE_BYTES      = 4
	SND_CTL_ELEM_TYPE_IEC958     = 5
	SND_CTL_ELEM_TYPE_INTEGER64  = 6
)

var typeNames = []string{"NONE", "BOOL", "INT", "ENUM", "BYTES", "IEC958", "INT64"}

func typeName(t uint32) string {
	if int(t) < len(typeNames) {
		return typeNames[t]
	}
	return fmt.Sprintf("TYPE_%d", t)
}

// snd_ctl_elem_id — 64 bytes
type elemID struct {
	Numid     uint32
	Iface     uint32
	Device    uint32
	Subdevice uint32
	Name      [44]byte
	Index     uint32
}

func (id *elemID) nameStr() string {
	n := 0
	for n < len(id.Name) && id.Name[n] != 0 {
		n++
	}
	return string(id.Name[:n])
}

func setName(dst *[44]byte, name string) {
	for i := range dst {
		dst[i] = 0
	}
	copy(dst[:], name)
}

// snd_ctl_elem_list — 80 bytes on 64-bit
type elemList struct {
	Offset  uint32
	Space   uint32
	Used    uint32
	Count   uint32
	PidsPtr uint64 // pointer to elemID array
	_       [80 - 4*4 - 8]byte
}

// snd_ctl_elem_info — 272 bytes
// Kernel layout: id(64) + type/access/count/pid(16) + value_union(128) + dimen+reserved(64)
// The value union contains:
//   ENUMERATED: items(4) + item(4) + name[64] + names_ptr(8) + names_length(4) + pad
//   INTEGER: min(8) + max(8) + step(8)
type elemInfo struct {
	ID     elemID // 64
	Type   uint32
	Access uint32
	Count  uint32
	Pid    int32
	Union  [128]byte // value union: enum items/item/name, or integer min/max/step
	_rest  [272 - 64 - 4*4 - 128]byte
}

// Enum helper: get item count from elemInfo union
func (info *elemInfo) enumItems() uint32 {
	return binary.LittleEndian.Uint32(info.Union[0:4])
}

// Enum helper: get a specific enum item name by querying the kernel.
// On some Samsung Exynos/ABOX drivers, the name[64] field may be empty.
// In that case we return "" and the caller falls back to numeric display.
func getEnumItemName(fd int, id elemID, itemIndex uint32) (string, error) {
	var info elemInfo
	info.ID = id
	// Set which item to query: enumerated.item at Union[4:8]
	binary.LittleEndian.PutUint32(info.Union[4:8], itemIndex)
	err := ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, unsafe.Pointer(&info))
	if err != nil {
		return "", err
	}
	// Name is at Union[8:72] (64 bytes) — enumerated.name[64]
	nameBytes := info.Union[8:72]
	n := 0
	for n < len(nameBytes) && nameBytes[n] != 0 {
		n++
	}
	return string(nameBytes[:n]), nil
}

// Dump all enum items for a control — for diagnostics when names are empty.
// Shows raw hex of the name[64] field so we can see if data is at a different offset.
func dumpEnumItems(fd int, id elemID, info elemInfo) {
	items := info.enumItems()
	fmt.Printf("Control '%s' (numid=%d): ENUM, %d items, %d values\n",
		id.nameStr(), id.Numid, items, info.Count)

	for i := uint32(0); i < items; i++ {
		var qi elemInfo
		qi.ID = id
		binary.LittleEndian.PutUint32(qi.Union[4:8], i)
		err := ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, unsafe.Pointer(&qi))
		if err != nil {
			fmt.Printf("  [%d] error: %v\n", i, err)
			continue
		}

		// Standard name location: Union[8:72]
		nameBytes := qi.Union[8:72]
		n := 0
		for n < len(nameBytes) && nameBytes[n] != 0 {
			n++
		}
		name := string(nameBytes[:n])

		if name != "" {
			fmt.Printf("  [%d] %s\n", i, name)
		} else {
			// Name field empty — dump first 96 bytes of union for analysis
			fmt.Printf("  [%d] (empty name) hex[0:96]:", i)
			limit := 96
			if limit > len(qi.Union) {
				limit = len(qi.Union)
			}
			for b := 0; b < limit; b++ {
				if b%16 == 0 {
					fmt.Printf("\n    [%3d] ", b)
				}
				fmt.Printf("%02x ", qi.Union[b])
			}
			fmt.Println()
		}
	}

	// Also show current value
	val, err := getElemValue(fd, id)
	if err == nil {
		fmt.Printf("Current value: %s\n", formatValue(fd, info, val))
	}
}

// Find enum index by name. Returns (index, true) if found.
func findEnumIndex(fd int, id elemID, info elemInfo, name string) (uint32, bool) {
	items := info.enumItems()
	for i := uint32(0); i < items; i++ {
		itemName, err := getEnumItemName(fd, id, i)
		if err != nil {
			continue
		}
		if strings.EqualFold(itemName, name) {
			return i, true
		}
	}
	return 0, false
}

// snd_ctl_elem_value — 1224 bytes on 64-bit (aarch64)
// Layout: id(64) + indirect(4) + pad(4) + value_union(1024) + tstamp+reserved(128)
// The value union is 1024 bytes because long value[128] on 64-bit.
// BOOLEAN/INTEGER use long (8 bytes each), ENUMERATED uses uint (4 bytes each).
type elemValue struct {
	ID       elemID     // 64
	Indirect uint32     // 4
	_pad     uint32     // 4 (alignment padding to 8)
	Value    [1024]byte // 1024 (value union: long value[128] on 64-bit)
	_rest    [128]byte  // 128 (struct timespec + reserved = 128 always)
}

func ioctl(fd int, req uint, arg unsafe.Pointer) error {
	_, _, errno := syscall.Syscall(syscall.SYS_IOCTL, uintptr(fd), uintptr(req), uintptr(arg))
	if errno != 0 {
		return errno
	}
	return nil
}

func openCard(card int) (int, error) {
	path := fmt.Sprintf("/dev/snd/controlC%d", card)
	fd, err := syscall.Open(path, syscall.O_RDWR, 0)
	if err != nil {
		return -1, fmt.Errorf("open %s: %v", path, err)
	}
	return fd, nil
}

func getElemCount(fd int) (uint32, error) {
	var list elemList
	err := ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, unsafe.Pointer(&list))
	if err != nil {
		return 0, err
	}
	return list.Count, nil
}

func getElemIDs(fd int, count uint32) ([]elemID, error) {
	if count == 0 {
		return nil, nil
	}
	ids := make([]elemID, count)
	var list elemList
	list.Offset = 0
	list.Space = count
	list.PidsPtr = uint64(uintptr(unsafe.Pointer(&ids[0])))
	err := ioctl(fd, SNDRV_CTL_IOCTL_ELEM_LIST, unsafe.Pointer(&list))
	if err != nil {
		return nil, err
	}
	return ids[:list.Used], nil
}

func getElemInfo(fd int, id elemID) (elemInfo, error) {
	var info elemInfo
	info.ID = id
	err := ioctl(fd, SNDRV_CTL_IOCTL_ELEM_INFO, unsafe.Pointer(&info))
	return info, err
}

func getElemValue(fd int, id elemID) (elemValue, error) {
	var val elemValue
	val.ID = id
	err := ioctl(fd, SNDRV_CTL_IOCTL_ELEM_READ, unsafe.Pointer(&val))
	return val, err
}

func setElemValue(fd int, id elemID, info elemInfo, valueStr string) error {
	var val elemValue
	val.ID = id

	switch info.Type {
	case SND_CTL_ELEM_TYPE_BOOLEAN:
		// On 64-bit, boolean uses long (8 bytes per value)
		v := uint64(0)
		if valueStr == "1" || strings.EqualFold(valueStr, "on") || strings.EqualFold(valueStr, "true") {
			v = 1
		}
		for i := uint32(0); i < info.Count; i++ {
			binary.LittleEndian.PutUint64(val.Value[i*8:], v)
		}

	case SND_CTL_ELEM_TYPE_INTEGER:
		// On 64-bit, integer uses long (8 bytes per value)
		n, err := strconv.ParseInt(valueStr, 10, 64)
		if err != nil {
			return fmt.Errorf("invalid integer: %s", valueStr)
		}
		for i := uint32(0); i < info.Count; i++ {
			binary.LittleEndian.PutUint64(val.Value[i*8:], uint64(n))
		}

	case SND_CTL_ELEM_TYPE_ENUMERATED:
		// Enumerated uses unsigned int (4 bytes per value)
		// Try as numeric index first
		n, err := strconv.ParseUint(valueStr, 10, 32)
		if err != nil {
			// Not numeric — resolve enum name to index
			idx, found := findEnumIndex(fd, id, info, valueStr)
			if !found {
				return fmt.Errorf("enum value '%s' not found (items: %d)", valueStr, info.enumItems())
			}
			n = uint64(idx)
		}
		for i := uint32(0); i < info.Count; i++ {
			binary.LittleEndian.PutUint32(val.Value[i*4:], uint32(n))
		}

	default:
		return fmt.Errorf("unsupported type: %s", typeName(info.Type))
	}

	return ioctl(fd, SNDRV_CTL_IOCTL_ELEM_WRITE, unsafe.Pointer(&val))
}

func formatValue(fd int, info elemInfo, val elemValue) string {
	parts := make([]string, 0, info.Count)
	for i := uint32(0); i < info.Count && i < 128; i++ {
		switch info.Type {
		case SND_CTL_ELEM_TYPE_BOOLEAN:
			// On 64-bit, boolean uses long (8 bytes per value)
			v := binary.LittleEndian.Uint64(val.Value[i*8:])
			if v != 0 {
				parts = append(parts, "On")
			} else {
				parts = append(parts, "Off")
			}
		case SND_CTL_ELEM_TYPE_INTEGER:
			// On 64-bit, integer uses long (8 bytes per value)
			v := int64(binary.LittleEndian.Uint64(val.Value[i*8:]))
			parts = append(parts, fmt.Sprintf("%d", v))
		case SND_CTL_ELEM_TYPE_ENUMERATED:
			// Enumerated uses unsigned int (4 bytes per value)
			v := binary.LittleEndian.Uint32(val.Value[i*4:])
			// Resolve enum name
			name, err := getEnumItemName(fd, info.ID, v)
			if err == nil && name != "" {
				parts = append(parts, name)
			} else {
				parts = append(parts, fmt.Sprintf("%d", v))
			}
		case SND_CTL_ELEM_TYPE_BYTES:
			parts = append(parts, fmt.Sprintf("0x%02x", val.Value[i]))
		case SND_CTL_ELEM_TYPE_INTEGER64:
			v := int64(binary.LittleEndian.Uint64(val.Value[i*8:]))
			parts = append(parts, fmt.Sprintf("%d", v))
		default:
			parts = append(parts, "?")
		}
	}
	return strings.Join(parts, " ")
}

func listControls(fd int) error {
	count, err := getElemCount(fd)
	if err != nil {
		return fmt.Errorf("ELEM_LIST count: %v", err)
	}
	if count == 0 {
		fmt.Println("No mixer controls found")
		return nil
	}

	ids, err := getElemIDs(fd, count)
	if err != nil {
		return fmt.Errorf("ELEM_LIST ids: %v", err)
	}

	fmt.Printf("Number of controls: %d\n", len(ids))
	for _, id := range ids {
		info, err := getElemInfo(fd, id)
		if err != nil {
			fmt.Fprintf(os.Stderr, "  %d: error getting info: %v\n", id.Numid, err)
			continue
		}
		val, err := getElemValue(fd, id)
		if err != nil {
			fmt.Printf("%-4d\t%-4s\t%-2d\t%-44s\t(read error)\n",
				id.Numid, typeName(info.Type), info.Count, id.nameStr())
			continue
		}
		fmt.Printf("%-4d\t%-4s\t%-2d\t%-44s\t%s\n",
			id.Numid, typeName(info.Type), info.Count, id.nameStr(),
			formatValue(fd, info, val))
	}
	return nil
}

func findControlByName(fd int, name string) (elemID, elemInfo, bool) {
	count, err := getElemCount(fd)
	if err != nil {
		return elemID{}, elemInfo{}, false
	}
	ids, err := getElemIDs(fd, count)
	if err != nil {
		return elemID{}, elemInfo{}, false
	}
	for _, id := range ids {
		if id.nameStr() == name {
			info, err := getElemInfo(fd, id)
			if err != nil {
				return id, elemInfo{}, false
			}
			return id, info, true
		}
	}
	return elemID{}, elemInfo{}, false
}

func main() {
	card := 0
	args := os.Args[1:]

	// Parse options: -D card, -e (enum dump)
	enumDump := false
	for i := 0; i < len(args); i++ {
		if args[i] == "-D" && i+1 < len(args) {
			c, err := strconv.Atoi(args[i+1])
			if err == nil {
				card = c
				args = append(args[:i], args[i+2:]...)
				i-- // re-check this index
			}
		} else if args[i] == "-e" {
			enumDump = true
			args = append(args[:i], args[i+1:]...)
			i--
		}
	}

	fd, err := openCard(card)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}
	defer syscall.Close(fd)

	if len(args) == 0 {
		// List all controls
		if err := listControls(fd); err != nil {
			fmt.Fprintf(os.Stderr, "Error: %v\n", err)
			os.Exit(1)
		}
		return
	}

	// Get or set a control
	controlName := args[0]
	var id elemID
	var info elemInfo
	var found bool

	// Try as numeric ID first
	if numid, err := strconv.ParseUint(controlName, 10, 32); err == nil {
		id.Numid = uint32(numid)
		info, err = getElemInfo(fd, id)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Error: control %d not found: %v\n", numid, err)
			os.Exit(1)
		}
		found = true
		id = info.ID
	} else {
		// Search by name
		id, info, found = findControlByName(fd, controlName)
	}

	if !found {
		fmt.Fprintf(os.Stderr, "Error: control '%s' not found\n", controlName)
		os.Exit(1)
	}

	// -e: dump enum items and exit
	if enumDump {
		if info.Type != SND_CTL_ELEM_TYPE_ENUMERATED {
			fmt.Fprintf(os.Stderr, "Error: control '%s' is %s, not ENUM\n",
				controlName, typeName(info.Type))
			os.Exit(1)
		}
		dumpEnumItems(fd, id, info)
		return
	}

	if len(args) == 1 {
		// Get control value
		val, err := getElemValue(fd, id)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Error reading control: %v\n", err)
			os.Exit(1)
		}
		fmt.Printf("%s: %s\n", id.nameStr(), formatValue(fd, info, val))
	} else {
		// Set control value
		err := setElemValue(fd, id, info, args[1])
		if err != nil {
			fmt.Fprintf(os.Stderr, "Error setting control '%s' to '%s': %v\n",
				controlName, args[1], err)
			os.Exit(1)
		}
		// Read back
		val, err := getElemValue(fd, id)
		if err == nil {
			fmt.Printf("%s: %s\n", id.nameStr(), formatValue(fd, info, val))
		}
	}
}
