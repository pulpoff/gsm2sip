// Minimal raw PCM pipe for Android — cross-compiled in Go.
// Plays raw PCM from stdin into an ALSA playback device, or captures an ALSA
// capture device to stdout, via /dev/snd/pcmC<card>D<dev>{p,c} ioctls.
// No external dependencies (no libtinyalsa needed).
//
// tinyplay only reads WAV files and the audio HAL only opens the PCMs it was
// configured for.  The Exynos 9820 gateway needs neither: it plays the agent
// straight into an ABOX RDMA the HAL never uses and captures the call
// downlink from an ABOX WDMA, both as a stream that lasts the call.
// No WAV header in either direction — the caller asked for the format, so it
// already knows it.
//
// -q is the mode the gateway runs in.  The process at the other end of the
// pipe keeps network time, the PCM keeps the codec's, and in ABOX a stream
// that stops does not just go quiet: a capture that stops reading stalls
// every other consumer of the same SIFS, the call's own uplink included.  So
// with -q neither side ever waits on the other.  Playback writes silence when
// nothing has arrived and drops the oldest audio when too much has; capture
// drops a period rather than let a slow reader back up into the DMA.
//
// The parts of the PCM ABI that depend on the word size — `long` fields in
// three structs and the ioctl request numbers that encode their sizes — live
// in abi64.go and abi32.go.  Build for the device's ABI, not the host's:
// GOARCH=arm64 for arm64-v8a, GOARCH=arm GOARM=7 for armeabi-v7a.
//
// Usage:
//
//	tinypcm play -D <card> -d <device> [-c 2] [-r 48000] [-b 16] [-p 480] [-n 4] [-s 2] [-w 1000] [-m] [-q N] [-P dev]
//	tinypcm cap  -D <card> -d <device> [-c 2] [-r 48000] [-b 16] [-p 480] [-n 4] [-w 1000] [-m] [-q N]
//
//	-b 16 = S16_LE, 24 = S24_LE (24 bits in the low 3 bytes of 32), 32 = S32_LE
//	-p period size in frames, -n periods in the buffer
//	-s periods queued before playback starts
//	-w give up after this many ms without a frame moving
//	-m mono on the pipe (16-bit only): duplicated into every channel on the
//	   way out, the channels averaged on the way in
//	-q real time, with a queue of at most N periods (see above)
//	-P also run capture device <dev> of the same card, reading and discarding:
//	   an ABOX SIFS is only clocked while a capture stream runs through it
package main

import (
	"flag"
	"fmt"
	"io"
	"os"
	"syscall"
	"time"
	"unsafe"
)

// ALSA PCM ioctl numbers whose argument is the same width on every ABI.
// _IOC(dir, type, nr, size) = (dir << 30) | (size << 16) | (type << 8) | nr
// ALSA PCM type = 'A' = 0x41.  The word-size-dependent ones — HW_PARAMS,
// SW_PARAMS, WRITEI_FRAMES and READI_FRAMES — are in abi64.go / abi32.go.
const (
	SNDRV_PCM_IOCTL_PREPARE = 0x4140 // _IO('A', 0x40)
	SNDRV_PCM_IOCTL_DROP    = 0x4143 // _IO('A', 0x43)
)

const SNDRV_PCM_ACCESS_RW_INTERLEAVED = 3

// ALSA sample formats, by the -b value that selects them.
var formats = map[int]uint32{
	16: 2,  // SNDRV_PCM_FORMAT_S16_LE
	24: 6,  // SNDRV_PCM_FORMAT_S24_LE
	32: 10, // SNDRV_PCM_FORMAT_S32_LE
}

// snd_pcm_hw_params parameter indices.  Masks come first, intervals start at
// SAMPLE_BITS; each array in the struct is indexed from its own first entry.
const (
	paramAccess        = 0
	paramFormat        = 1
	paramSubformat     = 2
	paramChannels      = 10
	paramRate          = 11
	paramPeriodSize    = 13
	paramPeriods       = 15
	paramBufferSize    = 17
	paramFirstInterval = 8
)

// snd_mask — 256 bits
type mask struct {
	Bits [8]uint32
}

// snd_interval — min, max, then the openmin/openmax/integer/empty bitfield
type interval struct {
	Min   uint32
	Max   uint32
	Flags uint32
}

const intervalInteger = 1 << 2

// snd_pcm_hw_params — 608 bytes on 64-bit, 604 on 32-bit (fifo_size is a long)
type hwParams struct {
	Flags     uint32
	Masks     [3]mask
	Mres      [5]mask
	Intervals [12]interval
	Ires      [9]interval
	Rmask     uint32
	Cmask     uint32
	Info      uint32
	Msbits    uint32
	RateNum   uint32
	RateDen   uint32
	FifoSize  ulong
	_         [64]byte
}

// snd_pcm_sw_params — 136 bytes on 64-bit, 104 on 32-bit
type swParams struct {
	TstampMode       int32
	PeriodStep       uint32
	SleepMin         uint32
	_                [swPad]byte // the longs that follow are 8-aligned on 64-bit
	AvailMin         ulong
	XferAlign        ulong
	StartThreshold   ulong
	StopThreshold    ulong
	SilenceThreshold ulong
	SilenceSize      ulong
	Boundary         ulong
	Proto            uint32
	TstampType       uint32
	_                [56]byte
}

// snd_xferi — the argument of WRITEI_FRAMES / READI_FRAMES
type xferi struct {
	Result int // snd_pcm_sframes_t, a long
	Buf    uintptr
	Frames ulong
}

func ioctl(fd int, req uint, arg unsafe.Pointer) error {
	_, _, errno := syscall.Syscall(syscall.SYS_IOCTL, uintptr(fd), uintptr(req), uintptr(arg))
	if errno != 0 {
		return errno
	}
	return nil
}

func (hw *hwParams) setMask(param int, val uint32) {
	m := &hw.Masks[param]
	m.Bits = [8]uint32{}
	m.Bits[val>>5] |= 1 << (val & 31)
}

func (hw *hwParams) setInt(param int, val uint32) {
	iv := &hw.Intervals[param-paramFirstInterval]
	iv.Min, iv.Max, iv.Flags = val, val, intervalInteger
}

func (hw *hwParams) get(param int) uint32 {
	return hw.Intervals[param-paramFirstInterval].Min
}

// openPCM opens and configures one PCM.  The kernel refines whatever is left
// open (sample bits, buffer time...) and picks a value for it, as tinyalsa's
// pcm_open does.
func openPCM(card, dev int, capture bool, ch, rate, bits, period, periods, startPeriods int) (int, *hwParams, error) {
	dir := 'p'
	if capture {
		dir = 'c'
	}
	path := fmt.Sprintf("/dev/snd/pcmC%dD%d%c", card, dev, dir)
	fd, err := syscall.Open(path, syscall.O_RDWR|syscall.O_NONBLOCK, 0)
	if err != nil {
		return -1, nil, fmt.Errorf("open %s: %v", path, err)
	}

	var hw hwParams
	for i := range hw.Masks {
		for j := range hw.Masks[i].Bits {
			hw.Masks[i].Bits[j] = ^uint32(0)
		}
	}
	for i := range hw.Intervals {
		hw.Intervals[i].Max = ^uint32(0)
	}
	hw.Rmask = ^uint32(0)
	hw.Info = ^uint32(0)
	hw.setMask(paramAccess, SNDRV_PCM_ACCESS_RW_INTERLEAVED)
	hw.setMask(paramFormat, formats[bits])
	hw.setMask(paramSubformat, 0)
	hw.setInt(paramChannels, uint32(ch))
	hw.setInt(paramRate, uint32(rate))
	hw.setInt(paramPeriodSize, uint32(period))
	hw.setInt(paramPeriods, uint32(periods))
	if err := ioctl(fd, SNDRV_PCM_IOCTL_HW_PARAMS, unsafe.Pointer(&hw)); err != nil {
		syscall.Close(fd)
		return -1, nil, fmt.Errorf("%s: hw_params %dch %d Hz %d-bit period %d x%d: %v",
			path, ch, rate, bits, period, periods, err)
	}

	// Boundary is output only: the kernel fills it in.  Stop at a full buffer
	// (an xrun), which transfer() recovers from.
	sw := swParams{
		AvailMin:       ulong(hw.get(paramPeriodSize)),
		StartThreshold: 1,
		StopThreshold:  ulong(hw.get(paramBufferSize)),
	}
	if !capture {
		sw.StartThreshold = ulong(hw.get(paramPeriodSize) * uint32(startPeriods))
	}
	if err := ioctl(fd, SNDRV_PCM_IOCTL_SW_PARAMS, unsafe.Pointer(&sw)); err != nil {
		syscall.Close(fd)
		return -1, nil, fmt.Errorf("%s: sw_params: %v", path, err)
	}
	if err := ioctl(fd, SNDRV_PCM_IOCTL_PREPARE, nil); err != nil {
		syscall.Close(fd)
		return -1, nil, fmt.Errorf("%s: prepare: %v", path, err)
	}
	return fd, &hw, nil
}

// errStalled: the DMA position has not moved for the -w timeout.
var errStalled = fmt.Errorf("stalled: no frames moved")

// transfer moves one period.  The PCM is opened non-blocking and polled: the
// ABOX driver advances a stream's position only when the firmware reports a
// period, and it does not report every DMA (RDMA6 during a call, WDMA5 at
// all).  A blocking call would then sit out the vendor kernel's 1 s timeout
// and fail with a bare EIO; polling turns that into errStalled after `stall`,
// which says what happened.
//
// An xrun is recovered the way tinyalsa does: re-prepare and carry on.
// Returns the number of xruns it had to recover.
func transfer(fd int, req uint, buf []byte, frames, frameBytes int, wait, stall time.Duration) (int, error) {
	xruns := 0
	last := time.Now()
	for done := 0; done < frames; {
		x := xferi{Buf: uintptr(unsafe.Pointer(&buf[done*frameBytes])), Frames: ulong(frames - done)}
		err := ioctl(fd, req, unsafe.Pointer(&x))
		switch err {
		case nil:
			done += int(x.Result)
			last = time.Now()
		case syscall.EAGAIN:
			if time.Since(last) > stall {
				return xruns, errStalled
			}
			time.Sleep(wait)
		case syscall.EPIPE:
			xruns++
			if err := ioctl(fd, SNDRV_PCM_IOCTL_PREPARE, nil); err != nil {
				return xruns, err
			}
		default:
			return xruns, err
		}
	}
	return xruns, nil
}

// pcm is one open, prepared PCM and the shape of its periods.
type pcm struct {
	fd         int
	frames     int // per period
	frameBytes int
	wait       time.Duration // poll interval
	stall      time.Duration
}

func (p *pcm) periodBytes() int { return p.frames * p.frameBytes }

func open(card, dev int, capture bool, ch, rate, bits, period, periods, startPeriods, stallMs int) (*pcm, error) {
	fd, hw, err := openPCM(card, dev, capture, ch, rate, bits, period, periods, startPeriods)
	if err != nil {
		return nil, err
	}
	frames := int(hw.get(paramPeriodSize))
	dir := "play"
	if capture {
		dir = "cap"
	}
	fmt.Fprintf(os.Stderr, "tinypcm %s C%dD%d: %dch %d Hz %d-bit, period %d, buffer %d\n",
		dir, card, dev, hw.get(paramChannels), hw.get(paramRate), bits,
		frames, hw.get(paramBufferSize))

	sampleBytes := 2
	if bits != 16 {
		sampleBytes = 4
	}
	return &pcm{
		fd:         fd,
		frames:     frames,
		frameBytes: int(hw.get(paramChannels)) * sampleBytes,
		// Poll four times a period: a quarter period of slack either way.
		wait:  time.Duration(frames) * time.Second / time.Duration(hw.get(paramRate)) / 4,
		stall: time.Duration(stallMs) * time.Millisecond,
	}, nil
}

func (p *pcm) read(buf []byte) (int, error) {
	return transfer(p.fd, SNDRV_PCM_IOCTL_READI_FRAMES, buf, p.frames, p.frameBytes, p.wait, p.stall)
}

func (p *pcm) write(buf []byte) (int, error) {
	return transfer(p.fd, SNDRV_PCM_IOCTL_WRITEI_FRAMES, buf, p.frames, p.frameBytes, p.wait, p.stall)
}

// monoOut duplicates 16-bit mono samples into every channel of dst.
func monoOut(dst, mono []byte, ch int) {
	for i, j := 0, 0; i+1 < len(mono); i += 2 {
		for c := 0; c < ch; c++ {
			dst[j], dst[j+1] = mono[i], mono[i+1]
			j += 2
		}
	}
}

// monoIn averages the channels of 16-bit frames into dst.
func monoIn(dst, frames []byte, ch int) {
	for i, j := 0, 0; j+1 < len(dst); j += 2 {
		sum := 0
		for c := 0; c < ch; c++ {
			sum += int(int16(uint16(frames[i]) | uint16(frames[i+1])<<8))
			i += 2
		}
		v := uint16(int16(sum / ch))
		dst[j], dst[j+1] = byte(v), byte(v>>8)
	}
}

func fail(what string, err error) {
	fmt.Fprintf(os.Stderr, "Error: %s: %v\n", what, err)
	os.Exit(1)
}

// pace keeps a capture PCM running and throws away what it reads.
func pace(p *pcm) {
	buf := make([]byte, p.periodBytes())
	for {
		if _, err := p.read(buf); err != nil {
			fail("pacer read", err)
		}
	}
}

// playRealtime: see the -q paragraph at the top.  A reader fills a queue
// from stdin; the writer takes a period from it whenever the PCM has room,
// and silence when it has nothing.  After running dry it waits for two
// periods before taking audio again, so a frame that is merely a little late
// does not cost a gap of silence every time.
func playRealtime(p *pcm, ch, depth int, mono bool) {
	in := p.periodBytes()
	if mono {
		in = p.frames * 2
	}
	q := make(chan []byte, depth)
	var dropped, dry int
	go func() {
		for {
			b := make([]byte, in)
			n, err := io.ReadFull(os.Stdin, b)
			if n == 0 {
				close(q)
				return
			}
			clear(b[n:])
			select {
			case q <- b:
			default: // full: the oldest goes
				select {
				case <-q:
					dropped++
				default:
				}
				q <- b
			}
			if err != nil {
				close(q)
				return
			}
		}
	}()

	out := make([]byte, p.periodBytes())
	primed := false
	for {
		var b []byte
		if primed || len(q) >= 2 || len(q) == cap(q) {
			select {
			case got, ok := <-q:
				if !ok {
					ioctl(p.fd, SNDRV_PCM_IOCTL_DROP, nil)
					return
				}
				b = got
			default:
			}
		}
		primed = b != nil
		switch {
		case b == nil:
			clear(out)
			if dry++; dry%500 == 1 && dry > 1 {
				fmt.Fprintf(os.Stderr, "tinypcm: %d periods of silence filled in, %d dropped\n", dry, dropped)
			}
		case mono:
			monoOut(out, b, ch)
		default:
			copy(out, b)
		}
		if x, err := p.write(out); err != nil {
			fail("write", err)
		} else if x > 0 {
			fmt.Fprintf(os.Stderr, "tinypcm: underrun (%d)\n", x)
		}
	}
}

// capRealtime: the PCM is read at its own pace whatever the reader does; a
// period the reader has no room for is dropped, not waited for.
func capRealtime(p *pcm, ch, depth int, mono bool) {
	q := make(chan []byte, depth)
	go func() {
		for b := range q {
			if _, err := os.Stdout.Write(b); err != nil {
				os.Exit(0) // the reader went away: the call is over
			}
		}
	}()
	var dropped, total int
	buf := make([]byte, p.periodBytes())
	for {
		x, err := p.read(buf)
		if err != nil {
			fail("read", err)
		}
		if x > 0 {
			total += x
			fmt.Fprintf(os.Stderr, "tinypcm: overrun (%d so far)\n", total)
		}
		var b []byte
		if mono {
			b = make([]byte, p.frames*2)
			monoIn(b, buf, ch)
		} else {
			b = append([]byte(nil), buf...)
		}
		select {
		case q <- b:
		default:
			if dropped++; dropped%50 == 1 {
				fmt.Fprintf(os.Stderr, "tinypcm: reader too slow, %d periods dropped\n", dropped)
			}
		}
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "Usage: tinypcm {play|cap} -D <card> -d <device> [-c ch] [-r rate] [-b 16|24|32] [-p period] [-n periods] [-s start_periods] [-w stall_ms] [-m] [-q periods] [-P pacer_dev]")
	os.Exit(2)
}

func main() {
	if len(os.Args) < 2 || (os.Args[1] != "play" && os.Args[1] != "cap") {
		usage()
	}
	capture := os.Args[1] == "cap"
	fs := flag.NewFlagSet(os.Args[1], flag.ExitOnError)
	card := fs.Int("D", 0, "card")
	dev := fs.Int("d", -1, "device")
	ch := fs.Int("c", 2, "channels")
	rate := fs.Int("r", 48000, "rate")
	bits := fs.Int("b", 16, "16, 24 or 32")
	period := fs.Int("p", 480, "period size in frames")
	periods := fs.Int("n", 4, "periods in the buffer")
	startPeriods := fs.Int("s", 2, "periods queued before playback starts")
	stallMs := fs.Int("w", 1000, "give up when no frames move for this long (ms)")
	mono := fs.Bool("m", false, "mono on the pipe (16-bit only)")
	depth := fs.Int("q", 0, "real time, queueing at most this many periods")
	pacer := fs.Int("P", -1, "capture device to keep running alongside playback")
	fs.Parse(os.Args[2:])
	if *dev < 0 || formats[*bits] == 0 || (*mono && *bits != 16) || (*pacer >= 0 && capture) {
		usage()
	}

	p, err := open(*card, *dev, capture, *ch, *rate, *bits, *period, *periods, *startPeriods, *stallMs)
	if err != nil {
		fail("open", err)
	}
	defer syscall.Close(p.fd)
	if *pacer >= 0 {
		pp, err := open(*card, *pacer, true, *ch, *rate, *bits, *period, *periods, 1, *stallMs)
		if err != nil {
			fail("pacer", err)
		}
		go pace(pp)
	}

	if *depth > 0 {
		if capture {
			capRealtime(p, *ch, *depth, *mono)
		} else {
			playRealtime(p, *ch, *depth, *mono)
		}
		return
	}

	buf := make([]byte, p.periodBytes())
	pipe := buf
	if *mono {
		pipe = make([]byte, p.frames*2)
	}
	total := 0
	for {
		if capture {
			x, err := p.read(buf)
			if err != nil {
				fail("read", err)
			}
			if x > 0 {
				total += x
				fmt.Fprintf(os.Stderr, "tinypcm: overrun (%d so far)\n", total)
			}
			if *mono {
				monoIn(pipe, buf, *ch)
			}
			if _, err := os.Stdout.Write(pipe); err != nil {
				return // the reader went away: the call is over
			}
		} else {
			n, err := io.ReadFull(os.Stdin, pipe)
			if n == 0 {
				ioctl(p.fd, SNDRV_PCM_IOCTL_DROP, nil)
				return
			}
			clear(pipe[n:]) // a short last read is padded with silence
			if *mono {
				monoOut(buf, pipe, *ch)
			}
			x, werr := p.write(buf)
			if werr != nil {
				fail("write", werr)
			}
			if x > 0 {
				total += x
				fmt.Fprintf(os.Stderr, "tinypcm: underrun (%d so far)\n", total)
			}
			if err != nil {
				ioctl(p.fd, SNDRV_PCM_IOCTL_DROP, nil)
				return
			}
		}
	}
}
