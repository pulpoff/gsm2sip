// SPDX-License-Identifier: GPL-2.0
/*
 * abox_pos — period reports for the ABOX DMAs the firmware stays silent on.
 *
 * Galaxy S10e (Exynos 9820), LineageOS 4.14 kernel, vendor ABOX driver.
 *
 * The vendor driver moves an ALSA stream's position only when the "calliope"
 * firmware sends it a PCM_PLTDAI_POINTER IPC: prepare() sets data->pointer to
 * the buffer's IOVA, and from then on pointer() reports data->pointer - IOVA,
 * so the hardware-status fallback in pointer() is never reached.  The
 * firmware sends those IPCs for the RDMAs the audio HAL plays into, but never
 * for WDMA5-7, and not for RDMA6 while a call is up.  A read or write on those
 * devices therefore blocks for the driver's 1 s timeout and fails with EIO,
 * although the DMA itself is running.
 *
 * This module plays the firmware's part for the DMAs it is told to watch.  It
 * polls the status register of each running stream on them, derives the ring
 * position from it the way the vendor fallback does, stores IOVA + position
 * in data->pointer and calls snd_pcm_period_elapsed() for every period
 * crossed — exactly what the IPC handler does.  It waits a grace period after
 * a stream starts and backs off for the rest of that stream the moment the
 * firmware reports a position of its own, so the two never fight over one
 * stream.
 *
 * One binary for every build of this kernel.  build.sh strips the symbol CRCs,
 * and a module carrying a __versions section without them loads anywhere the
 * vermagic flags match: same_magic() skips the release string when CRCs are in
 * use, and a missing CRC is only a warning.  What a CRC would have caught — a
 * changed struct — is checked here instead: the module touches nothing of the
 * vendor driver but the leading fields of struct abox_platform_data and its
 * substream pointer, and verifies all of them against the device tree before
 * it starts, refusing to load if they do not line up.
 *
 *   insmod abox_pos.ko rdma=0x100 wdma=0x60     # RDMA8, WDMA5 and WDMA6
 *
 * Both masks are writable at runtime under /sys/module/abox_pos/parameters/.
 */

#define pr_fmt(fmt) KBUILD_MODNAME ": " fmt

#include <linux/delay.h>
#include <linux/io.h>
#include <linux/kthread.h>
#include <linux/module.h>
#include <linux/of.h>
#include <linux/platform_device.h>
#include <linux/pm_runtime.h>
#include <uapi/linux/sched/types.h>
#include <sound/pcm.h>
#include <sound/soc.h>

#include "abox.h"

#define NUM_RDMA 12
#define NUM_WDMA 8

/* The status register, relative to each DMA's own "sfr" window. */
#define RDMA_STATUS (ABOX_RDMA_STATUS(0) - ABOX_RDMA_CTRL0(0))
#define WDMA_STATUS (ABOX_WDMA_STATUS(0) - ABOX_WDMA_CTRL(0))

static unsigned int rdma;
module_param(rdma, uint, 0644);
MODULE_PARM_DESC(rdma, "bitmask of RDMAs to report positions for");

static unsigned int wdma;
module_param(wdma, uint, 0644);
MODULE_PARM_DESC(wdma, "bitmask of WDMAs to report positions for");

static unsigned int poll_us = 1000;
module_param(poll_us, uint, 0644);
MODULE_PARM_DESC(poll_us, "status poll interval while a watched stream runs");

static unsigned int grace_ms = 30;
module_param(grace_ms, uint, 0644);
MODULE_PARM_DESC(grace_ms, "how long a new stream is left to the firmware");

static unsigned int trace;
module_param(trace, uint, 0644);
MODULE_PARM_DESC(trace, "log every Nth poll of a watched stream (0 = off)");

struct slot {
	struct abox_platform_data *data;
	struct device *dev;		/* the DMA's platform device */
	struct device *abox;		/* its parent, for runtime PM */
	bool wdma;
	/* the stream being tracked; NULL until one is seen running */
	struct snd_pcm_substream *substream;
	struct snd_pcm_runtime *runtime;
	unsigned long started;		/* jiffies */
	unsigned int written;		/* last value stored in data->pointer */
	unsigned int period;		/* period index last reported */
	bool owned;			/* we report for this stream */
	bool firmware;			/* the firmware does; hands off */
	unsigned int polls;
};

static struct slot rslots[NUM_RDMA], wslots[NUM_WDMA];
static struct task_struct *poller;
static int layout_err;

/*
 * Check that the driver data really has the layout this module was built
 * against: a pointer back to its own device, the id the device tree gives it,
 * and a mapped register window.  A struct that moved fails at least one.
 */
static bool layout_ok(struct device *dev, struct abox_platform_data *data)
{
	u32 id;

	if (of_property_read_u32(dev->of_node, "samsung,id", &id))
		return false;
	return data->pdev == to_platform_device(dev) && data->id == id &&
			!IS_ERR_OR_NULL(data->sfr_base) && dev->parent;
}

static int find_dma(struct device *dev, void *unused)
{
	struct abox_platform_data *data;
	struct slot *s;
	bool is_wdma;

	if (!dev->driver)
		return 0;
	if (!strcmp(dev->driver->name, "samsung-abox-rdma"))
		is_wdma = false;
	else if (!strcmp(dev->driver->name, "samsung-abox-wdma"))
		is_wdma = true;
	else
		return 0;

	data = dev_get_drvdata(dev);
	if (!data)
		return 0;
	if (!layout_ok(dev, data)) {
		pr_err("%s: driver data does not match this build\n", dev_name(dev));
		layout_err = -ENOEXEC;
		return 0;
	}
	if (is_wdma ? data->id >= NUM_WDMA : data->id >= NUM_RDMA)
		return 0;

	s = is_wdma ? &wslots[data->id] : &rslots[data->id];
	s->data = data;
	s->dev = dev;
	s->abox = dev->parent;
	s->wdma = is_wdma;
	return 0;
}

/* The vendor pointer() fallback, abox_rdma.c / abox_wdma.c. */
static size_t status_to_pos(struct slot *s, struct snd_pcm_substream *ss,
		unsigned int status)
{
	ssize_t buffer_bytes = snd_pcm_lib_buffer_bytes(ss);
	ssize_t period_bytes = snd_pcm_lib_period_bytes(ss);
	ssize_t offset, count;

	if (s->wdma) {
		offset = ((status & ABOX_WDMA_RBUF_OFFSET_MASK) >>
				ABOX_WDMA_RBUF_OFFSET_L) << 4;
		count = period_bytes > ABOX_WDMA_RBUF_CNT_MASK + 1 ?
				0 : status & ABOX_WDMA_RBUF_CNT_MASK;
	} else {
		offset = ((status & ABOX_RDMA_RBUF_OFFSET_MASK) >>
				ABOX_RDMA_RBUF_OFFSET_L) << 4;
		count = period_bytes > ABOX_RDMA_RBUF_CNT_MASK + 1 ?
				0 : status & ABOX_RDMA_RBUF_CNT_MASK;
	}

	while ((offset % period_bytes) && (buffer_bytes >= 0)) {
		buffer_bytes -= period_bytes;
		if ((buffer_bytes & offset) == offset)
			offset = buffer_bytes;
	}

	return (offset + count) % snd_pcm_lib_buffer_bytes(ss);
}

/* True if @ss is a stream on this slot's DMA, not a stale or foreign pointer. */
static bool substream_ok(struct slot *s, struct snd_pcm_substream *ss)
{
	struct snd_soc_pcm_runtime *rtd = ss->private_data;

	return rtd && rtd->platform && rtd->platform->dev == s->dev &&
			ss->stream == (s->wdma ? SNDRV_PCM_STREAM_CAPTURE :
					SNDRV_PCM_STREAM_PLAYBACK);
}

/* Returns true while the slot has a stream open, running or not. */
static bool poll_slot(struct slot *s, unsigned int id, bool watched)
{
	struct abox_platform_data *data = s->data;
	struct snd_pcm_substream *ss;
	struct snd_pcm_runtime *rt;
	unsigned int status, base, period, progress;
	unsigned long flags;
	bool elapsed = false;
	size_t pos;

	if (!data)
		return false;
	ss = READ_ONCE(data->substream);
	if (!watched || !ss) {
		s->substream = NULL;
		return ss != NULL;
	}

	/* Only touch ABOX registers while something else keeps it powered. */
	if (pm_runtime_get_if_in_use(s->abox) <= 0)
		return true;
	status = readl_relaxed(data->sfr_base +
			(s->wdma ? WDMA_STATUS : RDMA_STATUS));
	pm_runtime_put(s->abox);
	progress = s->wdma ? ABOX_WDMA_PROGRESS_MASK : ABOX_RDMA_PROGRESS_MASK;
	base = s->wdma ? IOVA_WDMA_BUFFER(id) : IOVA_RDMA_BUFFER(id);

	snd_pcm_stream_lock_irqsave(ss, flags);
	rt = ss->runtime;
	if (!rt || rt->status->state != SNDRV_PCM_STATE_RUNNING ||
			!(status & progress)) {
		s->substream = NULL;
		goto out;
	}

	if (s->substream != ss || s->runtime != rt) {
		if (!substream_ok(s, ss)) {
			pr_err_ratelimited("%cDMA%u: substream does not belong to it, ignored\n",
					s->wdma ? 'W' : 'R', id);
			goto out;
		}
		/* A new stream; prepare() just reset data->pointer to base. */
		s->substream = ss;
		s->runtime = rt;
		s->started = jiffies;
		s->written = base;
		s->period = 0;
		s->owned = false;
		s->firmware = data->pointer != base;
		s->polls = 0;
	}

	if (!s->firmware && data->pointer != s->written) {
		s->firmware = true;
		if (s->owned)
			pr_info("%cDMA%u: the firmware reports again, backing off\n",
					s->wdma ? 'W' : 'R', id);
	}

	pos = status_to_pos(s, ss, status);
	period = pos / snd_pcm_lib_period_bytes(ss);

	if (trace && !(s->polls++ % trace))
		pr_info("%cDMA%u: status %08x pos %zu period %u pointer %u%s\n",
				s->wdma ? 'W' : 'R', id, status, pos, period,
				data->pointer - base, s->firmware ? " (firmware)" :
				s->owned ? " (ours)" : "");
	if (s->firmware)
		goto out;

	if (!s->owned) {
		if (time_before(jiffies, s->started + msecs_to_jiffies(grace_ms)))
			goto out;
		s->owned = true;
		s->period = period;
		pr_info("%cDMA%u: the firmware is silent, reporting for it\n",
				s->wdma ? 'W' : 'R', id);
	}

	s->written = base + pos;
	data->pointer = s->written;
	if (period != s->period) {
		s->period = period;
		elapsed = true;
	}
out:
	snd_pcm_stream_unlock_irqrestore(ss, flags);

	if (elapsed)
		snd_pcm_period_elapsed(ss);
	return true;
}

static int poll_thread(void *unused)
{
	struct sched_param param = { .sched_priority = 10 };
	unsigned int i, us;
	bool open;

	sched_setscheduler_nocheck(current, SCHED_FIFO, &param);

	while (!kthread_should_stop()) {
		open = false;
		for (i = 0; i < NUM_RDMA; i++)
			open |= poll_slot(&rslots[i], i, rdma & BIT(i));
		for (i = 0; i < NUM_WDMA; i++)
			open |= poll_slot(&wslots[i], i, wdma & BIT(i));

		/* Fast only while a watched stream is open. */
		us = open && (rdma || wdma) ? max(poll_us, 200U) : 50000;
		usleep_range(us, us + us / 4);
	}
	return 0;
}

static int __init abox_pos_init(void)
{
	unsigned int i, found = 0;

	bus_for_each_dev(&platform_bus_type, NULL, NULL, find_dma);
	if (layout_err)
		return layout_err;
	for (i = 0; i < NUM_RDMA; i++)
		found += !!rslots[i].data;
	for (i = 0; i < NUM_WDMA; i++)
		found += !!wslots[i].data;
	if (!found) {
		pr_err("no ABOX DMA devices bound\n");
		return -ENODEV;
	}

	poller = kthread_run(poll_thread, NULL, "abox_pos");
	if (IS_ERR(poller))
		return PTR_ERR(poller);

	pr_info("watching %u DMAs, rdma=%#x wdma=%#x\n", found, rdma, wdma);
	return 0;
}

static void __exit abox_pos_exit(void)
{
	kthread_stop(poller);
}

module_init(abox_pos_init);
module_exit(abox_pos_exit);
MODULE_DESCRIPTION("ABOX DMA position reports the firmware does not send");
MODULE_LICENSE("GPL");
