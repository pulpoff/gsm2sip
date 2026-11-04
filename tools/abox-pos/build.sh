#!/bin/sh
# Build abox_pos.ko against a prepared LineageOS S10e kernel tree, then strip
# its symbol CRCs so the one binary loads on every build of that kernel (see
# the header of abox_pos.c for why that is safe).
#
#   KDIR=/path/to/prepared/kernel/out ./build.sh
#
# KDIR must be configured with the ROM's own config (/proc/config.gz) and
# contain the vendor tree, sound/soc/samsung/abox included.
set -e
cd "$(dirname "$0")"
: "${KDIR:?set KDIR to the prepared kernel tree, see above}"

make -C "$KDIR" ARCH=arm64 CC=clang CLANG_TRIPLE=aarch64-linux-gnu- \
	CROSS_COMPILE=aarch64-linux-gnu- LD=aarch64-linux-gnu-ld M="$PWD" modules

# One placeholder entry instead of the real CRCs.  The section has to stay:
# without __versions at all the kernel treats the module as force-loaded and
# refuses it, while a section that names none of the imported symbols only
# earns a "no symbol version" warning.
python3 -I - versions.bin <<'EOF'
import struct, sys
name = b"abox_pos_unversioned"
open(sys.argv[1], "wb").write(struct.pack("<Q56s", 0, name))
EOF
aarch64-linux-gnu-objcopy --update-section __versions=versions.bin abox_pos.ko
rm -f versions.bin
aarch64-linux-gnu-strip --strip-debug abox_pos.ko
ls -l abox_pos.ko
