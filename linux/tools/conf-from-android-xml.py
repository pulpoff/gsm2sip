#!/usr/bin/env python3
"""Write a gsm2sipd gateway.conf from the Android app's settings.

The input is the app's shared preferences file,
/data/data/com.callagent.gateway/shared_prefs/gateway.xml (pulled with
`adb shell run-as` or from a backup).  The output holds the SIP password, so
it is created with mode 600 and nothing it contains is ever printed: the tool
reports which settings it found by name only.

    conf-from-android-xml.py gateway.xml gateway.conf [--force]
"""
import argparse
import os
import sys
import xml.etree.ElementTree as ET

# pref name -> (section, conf key); the Android defaults apply when a pref is absent
MAPPING = {
    "server": ("sip", "server"),
    "port": ("sip", "port"),
    "user": ("sip", "user"),
    "pass": ("sip", "password"),
    "sip_tls": ("sip", "tls"),
    "srtp_enabled": ("sip", "srtp"),
    "codec": ("sip", "codec"),
    "use_stun": ("sip", "stun"),
    "own_number": ("gateway", "own_number"),
}
# set in the app but with no meaning on Linux (yet); named so nobody wonders
NOT_PORTED = {
    "translit_ascii": "SMS transliteration to ASCII",
    "autoconnect": "the daemon always connects",
    "call_monitor": "no call monitor on Linux",
    "agent_vol_step": "volume steps are the Android audio HAL's",
}

AUDIO_S10E = """[audio]
# S10e call audio taps (exynos9820-abox): the GSM downlink to capture,
# the GSM uplink to play into.
capture=hw:0,2
capture_rate=32000
capture_channels=2
# mix | left | right
capture_channel=mix
playback=hw:0,3
playback_rate=48000
playback_channels=2
control_card=hw:0
uplink_switch=Call Uplink Switch
"""


def read_prefs(path):
    prefs = {}
    for el in ET.parse(path).getroot():
        name = el.get("name")
        if name is None:
            continue
        if el.tag == "string":
            prefs[name] = el.text or ""
        elif el.tag in ("int", "long"):
            prefs[name] = int(el.get("value"))
        elif el.tag == "boolean":
            prefs[name] = el.get("value") == "true"
        elif el.tag == "float":
            prefs[name] = float(el.get("value"))
    return prefs


def keyfile_escape(value):
    """A GKeyFile value: backslash escapes, and a leading space kept as \\s."""
    out = (value.replace("\\", "\\\\").replace("\n", "\\n")
           .replace("\t", "\\t").replace("\r", "\\r"))
    if out.startswith(" "):
        out = "\\s" + out[1:]
    return out


def conf_value(v):
    if isinstance(v, bool):
        return "true" if v else "false"
    return keyfile_escape(str(v))


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("xml", help="the app's shared_prefs/gateway.xml")
    ap.add_argument("conf", help="the gateway.conf to write")
    ap.add_argument("--force", action="store_true", help="replace an existing conf")
    a = ap.parse_args()

    prefs = read_prefs(a.xml)
    # dual-SIM builds keep the number per slot; the daemon drives one modem
    if not prefs.get("own_number") and prefs.get("own_number_slot_1"):
        prefs["own_number"] = prefs["own_number_slot_1"]

    missing = [k for k in ("server", "user", "pass") if not prefs.get(k)]
    if missing:
        sys.exit("gateway.xml has no " + ", ".join(missing) + " -- not writing a conf")

    sections = {"sip": [], "gateway": []}
    found = []
    for pref, (section, key) in MAPPING.items():
        if pref in prefs and prefs[pref] != "":
            sections[section].append(f"{key}={conf_value(prefs[pref])}")
            found.append(pref)
    if prefs.get("srtp_enabled") and not prefs.get("sip_tls"):
        print("note: srtp is on but tls is off -- gsm2sipd, like the app, offers SRTP only over TLS")

    text = ("# Written by conf-from-android-xml.py from the app's gateway.xml.\n"
            "# Holds the SIP password: keep it mode 600.\n\n")
    for section, lines in sections.items():
        text += f"[{section}]\n" + "".join(l + "\n" for l in lines) + "\n"
    text += AUDIO_S10E

    flags = os.O_WRONLY | os.O_CREAT | (os.O_TRUNC if a.force else os.O_EXCL)
    try:
        fd = os.open(a.conf, flags, 0o600)
    except FileExistsError:
        sys.exit(f"{a.conf} exists -- pass --force to replace it")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        os.fchmod(f.fileno(), 0o600)	# O_CREAT's mode does not apply to an existing file
        f.write(text)

    print(f"wrote {a.conf} (mode 600) with: " + ", ".join(found))
    skipped = [f"{k} ({why})" for k, why in NOT_PORTED.items() if k in prefs]
    if skipped:
        print("not carried over: " + "; ".join(skipped))
    other = sorted(set(prefs) - set(MAPPING) - set(NOT_PORTED) - {"own_number_slot_1"})
    if other:
        print("unknown prefs ignored: " + ", ".join(other))


if __name__ == "__main__":
    main()
