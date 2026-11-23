#!/usr/bin/env python3
"""Plays the main phone from a PC, to test a phone that is set up as the Grippy camera.

    pip install bleak
    scripts/grippy-camtest.py                 # find the camera, say hello, print its status
    scripts/grippy-camtest.py wake            # wake it and watch what it sees for 20 s
    scripts/grippy-camtest.py set 30 --hand right --name "wrist curl"
                                              # wake, count a 30 s set, print the reps, sleep

The wire format is the one in android/app/src/main/kotlin/app/grippy/camera/CamProtocol.kt.
"""
import argparse
import asyncio
import struct
import sys
import time

from bleak import BleakClient, BleakScanner

SERVICE = "7e5f0001-6c1d-4b8e-9a37-677269707079"
COMMAND = "7e5f0002-6c1d-4b8e-9a37-677269707079"
EVENT = "7e5f0003-6c1d-4b8e-9a37-677269707079"
STATES = ["Idle", "Starting", "Framing", "Counting", "Failed"]
TRACKING = ["None", "Hand", "Motion"]


def hello():
    return bytes([0x01, 1])


def wake(front, name):
    raw = name.encode()[:80]
    return bytes([0x02, 1 if front else 0, len(raw)]) + raw


def begin(hand):
    return bytes([0x03, 1 if hand == "right" else 0])


END = bytes([0x04])
SLEEP = bytes([0x05])


def decode(b):
    kind = b[0]
    if kind == 0x81:
        state, tracking, fps, battery, protocol, n = b[1:7]
        return {"type": "status", "state": STATES[state], "tracking": TRACKING[tracking], "fps": fps,
                "battery": battery, "protocol": protocol, "note": b[7:7 + n].decode(errors="replace")}
    if kind == 0x82:
        t, count, signal, tracking, aspect = struct.unpack_from("<iHbBB", b, 1)
        return {"type": "frame", "tMs": t, "count": count, "signal": signal, "tracking": TRACKING[tracking],
                "aspect": aspect, "hand": len(b) - 10 >= 42}
    if kind == 0x83:
        count, t, ms, amp = struct.unpack_from("<HiHB", b, 1)
        return {"type": "rep", "count": count, "tMs": t, "ms": ms, "range": amp}
    if kind == 0x84:
        duration, tracking, n = struct.unpack_from("<iBH", b, 1)
        reps = [struct.unpack_from("<iHB", b, 8 + i * 7) for i in range(n)]
        return {"type": "summary", "durationMs": duration, "tracking": TRACKING[tracking], "reps": reps}
    return {"type": "unknown", "hex": b.hex()}


class Link:
    def __init__(self, client, quiet_frames):
        self.client = client
        self.pending = b""
        self.events = asyncio.Queue()
        self.status = None
        self.frames = 0
        self.hand_frames = 0
        self.quiet_frames = quiet_frames
        self.t0 = time.time()

    def on_packet(self, _, data):
        self.pending += bytes(data[1:])
        if data[0]:
            return
        message, self.pending = self.pending, b""
        event = decode(message)
        if event["type"] == "status":
            self.status = event
        self.events.put_nowait(event)
        if event["type"] == "frame":
            self.frames += 1
            self.hand_frames += 1 if event["hand"] else 0
            if self.quiet_frames and self.frames % 20:
                return
        print(f"{time.time() - self.t0:7.2f}s  {event}", flush=True)

    async def send(self, message):
        room = max(self.client.mtu_size - 3, 20) - 1
        parts = [message[i:i + room] for i in range(0, len(message), room)] or [b""]
        for i, part in enumerate(parts):
            await self.client.write_gatt_char(COMMAND, bytes([1 if i < len(parts) - 1 else 0]) + part, response=True)

    async def wait_for(self, kind, seconds, **match):
        end = time.time() + seconds
        while time.time() < end:
            try:
                event = await asyncio.wait_for(self.events.get(), max(0.05, end - time.time()))
            except asyncio.TimeoutError:
                return None
            if event["type"] == kind and all(event.get(k) == v for k, v in match.items()):
                return event
        return None


async def run(args):
    print("Looking for a Grippy camera…", flush=True)
    device = await BleakScanner.find_device_by_filter(
        lambda d, ad: SERVICE in [u.lower() for u in ad.service_uuids], timeout=args.scan)
    if device is None:
        print("No Grippy camera found. Is the switch on in Settings → Camera phone, and Bluetooth on?")
        return 2
    print(f"Found {device.address}; connecting…", flush=True)
    async with BleakClient(device) as client:
        link = Link(client, quiet_frames=not args.frames)
        await client.start_notify(EVENT, link.on_packet)
        print(f"Connected, packet size {client.mtu_size}.", flush=True)
        await link.send(hello())
        if await link.wait_for("status", 5) is None:
            print("FAIL: the camera did not answer Hello.")
            return 1
        if args.action == "hello":
            return 0

        t = time.time()
        opened = None
        while opened is None and time.time() - t < args.wake_timeout:
            await link.send(wake(not args.back, args.name))
            opened = await link.wait_for("status", 4, state="Framing")
        if opened is None:
            print(f"FAIL: the camera did not open within {args.wake_timeout} s (last status: {link.status}).")
            return 1
        print(f"Camera open {time.time() - t:.1f} s after Wake.", flush=True)

        if args.action == "wake":
            await asyncio.sleep(args.seconds)
        else:
            await asyncio.sleep(args.settle)
            await link.send(begin(args.hand))
            if await link.wait_for("status", 5, state="Counting") is None:
                print("FAIL: the camera did not start counting.")
                return 1
            await asyncio.sleep(args.seconds)
            await link.send(END)
            summary = await link.wait_for("summary", 6)
            if summary is None:
                print("FAIL: no summary after End.")
                return 1
            print(f"RESULT: {len(summary['reps'])} reps in {summary['durationMs'] / 1000:.1f} s, counted from {summary['tracking']}.")
        print(f"Frames received: {link.frames}, with a hand: {link.hand_frames}.")
        if not args.stay:
            await link.send(SLEEP)
            await link.wait_for("status", 5, state="Idle")
    return 0


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("action", nargs="?", default="hello", choices=["hello", "wake", "set"])
    p.add_argument("seconds", nargs="?", type=float, default=20)
    p.add_argument("--hand", default="left", choices=["left", "right"])
    p.add_argument("--name", default="test")
    p.add_argument("--back", action="store_true", help="use the back camera")
    p.add_argument("--frames", action="store_true", help="print every frame, not every 20th")
    p.add_argument("--stay", action="store_true", help="leave the camera open afterwards")
    p.add_argument("--scan", type=float, default=15)
    p.add_argument("--settle", type=float, default=3, help="seconds between the camera opening and Begin")
    p.add_argument("--wake-timeout", type=float, default=30)
    sys.exit(asyncio.run(run(p.parse_args())))


if __name__ == "__main__":
    main()
