# SAHAYAT band firmware — Raspberry Pi Pico W, MicroPython (built-in `bluetooth` module).
#
# MPU6050 → prototype abnormal-motion detector → LED + buzzer → BLE Alert notification → Android app.
# The detector is a PROTOTYPE threshold algorithm. It is NOT medically validated.
#
# Wiring
#   MPU6050 SDA → GP4, SCL → GP5 (I2C0, address 0x68), VCC → 3V3, GND → GND
#   LED → GP15 (through a resistor to GND)
#   Buzzer → GP14
#   Cancel button → GP16 and GND (internal pull-up; pressed = 0)
#
# BLE (must match app/src/main/java/com/sahayat/app/ble/BandProtocol.kt)
#   Name     SAHAYAT-BAND  (in the scan response)
#   Service  7B7E0001-6B8A-4D9E-9C5B-2A4F6D8E1001  (in the advertising packet — the app filters on this)
#     Device ID  7B7E0005-…  READ           "SAH-0001"
#     Status     7B7E0002-…  READ + NOTIFY  "MONITORING" | "STOPPED" | "SAHAYAT_OK" | "MPU_ERROR"
#     Alert      7B7E0003-…  READ + NOTIFY  "ALERT|SAH-0001|SEIZURE|<ref>" then
#                                           "ALERT|SAH-0001|CANCELLED|<ref>" or "ALERT|SAH-0001|ALERT_ACTIVE|<ref>"
#     Command    7B7E0004-…  READ + WRITE   "PING" | "START" | "STOP" | "STATUS"
#   Each NOTIFY characteristic gets its CCCD (0x2902) automatically from the Bluetooth stack.
#   <ref> = "<boot id>-<alert number>", so the phone never processes the same alert twice.

import bluetooth
import struct
import time
import random
from machine import Pin, I2C
from micropython import const

# ---------------------------------------------------------------- configuration
DEVICE_ID = "SAH-0001"
BLE_NAME = "SAHAYAT-BAND"

MPU_ADDR = const(0x68)
THRESHOLD = 12000          # sum of |Δax| + |Δay| + |Δaz| (raw units)
STRONG_REQUIRED = 3        # consecutive strong movements
SAMPLE_MS = 100            # delay between accelerometer samples
CANCEL_STEPS = 20          # 20 × 100 ms cancellation window
CANCEL_STEP_MS = 100

# ---------------------------------------------------------------- BLE constants
_IRQ_CENTRAL_CONNECT = const(1)
_IRQ_CENTRAL_DISCONNECT = const(2)
_IRQ_GATTS_WRITE = const(3)
_IRQ_MTU_EXCHANGED = const(21)

_FLAG_READ = const(0x0002)
_FLAG_WRITE_NO_RESPONSE = const(0x0004)
_FLAG_WRITE = const(0x0008)
_FLAG_NOTIFY = const(0x0010)

_ADV_TYPE_FLAGS = const(0x01)
_ADV_TYPE_UUID128_COMPLETE = const(0x07)
_ADV_TYPE_NAME = const(0x09)

SERVICE_UUID = bluetooth.UUID("7B7E0001-6B8A-4D9E-9C5B-2A4F6D8E1001")
STATUS_UUID = bluetooth.UUID("7B7E0002-6B8A-4D9E-9C5B-2A4F6D8E1001")
ALERT_UUID = bluetooth.UUID("7B7E0003-6B8A-4D9E-9C5B-2A4F6D8E1001")
COMMAND_UUID = bluetooth.UUID("7B7E0004-6B8A-4D9E-9C5B-2A4F6D8E1001")
DEVICE_ID_UUID = bluetooth.UUID("7B7E0005-6B8A-4D9E-9C5B-2A4F6D8E1001")

SAHAYAT_SERVICE = (
    SERVICE_UUID,
    (
        (DEVICE_ID_UUID, _FLAG_READ),
        (STATUS_UUID, _FLAG_READ | _FLAG_NOTIFY),
        (ALERT_UUID, _FLAG_READ | _FLAG_NOTIFY),
        (COMMAND_UUID, _FLAG_READ | _FLAG_WRITE | _FLAG_WRITE_NO_RESPONSE),
    ),
)

# ---------------------------------------------------------------- hardware
led = Pin(15, Pin.OUT, value=0)
buzzer = Pin(14, Pin.OUT, value=0)
cancel_button = Pin(16, Pin.IN, Pin.PULL_UP)
i2c = I2C(0, sda=Pin(4), scl=Pin(5), freq=400000)


def mpu_init():
    try:
        i2c.writeto_mem(MPU_ADDR, 0x6B, b"\x00")   # wake up (PWR_MGMT_1)
        time.sleep_ms(100)
        return True
    except OSError as e:
        print("MPU6050 not found at 0x68:", e)
        return False


def mpu_read_accel():
    data = i2c.readfrom_mem(MPU_ADDR, 0x3B, 6)
    return struct.unpack(">hhh", data)


def cancel_pressed():
    return cancel_button.value() == 0


# ---------------------------------------------------------------- BLE peripheral
def _adv_field(adv_type, value):
    return struct.pack("BB", len(value) + 1, adv_type) + value


class SahayatBLE:
    def __init__(self):
        self._ble = bluetooth.BLE()
        self._ble.active(True)
        try:
            self._ble.config(gap_name=BLE_NAME)
        except Exception:
            pass
        try:
            self._ble.config(mtu=185)   # text messages are longer than the 20-byte default payload
        except Exception:
            pass
        self._ble.irq(self._irq)
        ((self.h_device_id, self.h_status, self.h_alert, self.h_command),) = \
            self._ble.gatts_register_services((SAHAYAT_SERVICE,))
        for h in (self.h_status, self.h_alert, self.h_command):
            self._ble.gatts_set_buffer(h, 64)
        self._connections = set()
        self.on_command = None

        self._ble.gatts_write(self.h_device_id, DEVICE_ID.encode())
        self._ble.gatts_write(self.h_status, b"")
        self._ble.gatts_write(self.h_alert, b"")
        self._ble.gatts_write(self.h_command, b"")

        # 128-bit UUID in the advertising packet (the app scans for it); the name goes in the
        # scan response because both together exceed the 31-byte advertising limit.
        self._adv = _adv_field(_ADV_TYPE_FLAGS, b"\x06") + \
            _adv_field(_ADV_TYPE_UUID128_COMPLETE, bytes(SERVICE_UUID))
        self._resp = _adv_field(_ADV_TYPE_NAME, BLE_NAME.encode())
        self.advertise()

    @property
    def connected(self):
        return len(self._connections) > 0

    def advertise(self):
        self._ble.gap_advertise(100000, adv_data=self._adv, resp_data=self._resp)
        print("Advertising as", BLE_NAME, "with service", SERVICE_UUID)

    def _irq(self, event, data):
        if event == _IRQ_CENTRAL_CONNECT:
            conn_handle, _, _ = data
            self._connections.add(conn_handle)
            print("Phone connected:", conn_handle)
        elif event == _IRQ_CENTRAL_DISCONNECT:
            conn_handle, _, _ = data
            self._connections.discard(conn_handle)
            print("Phone disconnected:", conn_handle)
            self.advertise()
        elif event == _IRQ_GATTS_WRITE:
            conn_handle, value_handle = data
            if value_handle == self.h_command:
                raw = self._ble.gatts_read(self.h_command)
                try:
                    cmd = raw.decode().strip().upper()
                except Exception:
                    cmd = ""
                print("Command:", cmd)
                if self.on_command:
                    self.on_command(cmd)
        elif event == _IRQ_MTU_EXCHANGED:
            conn_handle, mtu = data
            print("MTU:", mtu)

    def _publish(self, handle, text):
        payload = text.encode()
        # The stored value stays readable for phones that (re)connect later.
        try:
            # MicroPython 1.20+: notifies every phone that enabled notifications (CCCD 0x2902).
            self._ble.gatts_write(handle, payload, True)
            return
        except TypeError:
            pass
        # Older MicroPython: store, then notify each connected phone with the stored value.
        self._ble.gatts_write(handle, payload)
        for conn in tuple(self._connections):
            try:
                self._ble.gatts_notify(conn, handle)
            except OSError as e:
                print("Notify failed:", e)

    def send_status(self, text):
        print("STATUS ->", text)
        self._publish(self.h_status, text)

    def send_alert(self, kind, ref):
        text = "ALERT|{}|{}|{}".format(DEVICE_ID, kind, ref)
        print("ALERT  ->", text, "(phone connected)" if self.connected else "(NO PHONE CONNECTED)")
        self._publish(self.h_alert, text)


# ---------------------------------------------------------------- application
ble = SahayatBLE()
mpu_ok = mpu_init()
monitoring = True
boot_id = "{:04x}".format(random.getrandbits(16))
alert_count = 0


def current_state():
    if not mpu_ok:
        return "MPU_ERROR"
    return "MONITORING" if monitoring else "STOPPED"


def handle_command(cmd):
    global monitoring
    if cmd == "PING":
        ble.send_status("SAHAYAT_OK")
    elif cmd == "START":
        monitoring = True
        ble.send_status(current_state())
    elif cmd == "STOP":
        monitoring = False
        ble.send_status("STOPPED")
    elif cmd == "STATUS":
        ble.send_status(current_state())
    else:
        print("Unknown command ignored:", cmd)


ble.on_command = handle_command
ble.send_status(current_state())


def raise_alert():
    """LED + buzzer, BLE alert, cancellation window, then reset."""
    global alert_count
    alert_count += 1
    ref = "{}-{}".format(boot_id, alert_count)
    led.on()
    buzzer.on()
    ble.send_alert("SEIZURE", ref)

    cancelled = False
    for _ in range(CANCEL_STEPS):
        if cancel_pressed():
            cancelled = True
            break
        time.sleep_ms(CANCEL_STEP_MS)

    led.off()
    buzzer.off()
    ble.send_alert("CANCELLED" if cancelled else "ALERT_ACTIVE", ref)
    if cancelled:
        # Wait for release so one press doesn't cancel the next alert too.
        while cancel_pressed():
            time.sleep_ms(20)


def main():
    global mpu_ok
    print("SAHAYAT firmware v2 (text protocol, service 7B7E0001)", DEVICE_ID, "MPU ok:", mpu_ok)
    ax = ay = az = 0
    have_baseline = False
    strong = 0
    while True:
        if not mpu_ok:
            time.sleep_ms(2000)
            mpu_ok = mpu_init()
            if mpu_ok:
                ble.send_status(current_state())
            continue

        if not monitoring:
            have_baseline = False
            strong = 0
            time.sleep_ms(SAMPLE_MS)
            continue

        try:
            nax, nay, naz = mpu_read_accel()
        except OSError as e:
            print("MPU6050 read failed:", e)
            mpu_ok = False
            ble.send_status("MPU_ERROR")
            continue

        if have_baseline:
            change = abs(nax - ax) + abs(nay - ay) + abs(naz - az)
            if change > THRESHOLD:
                strong += 1
            else:
                strong = 0
        ax, ay, az = nax, nay, naz
        have_baseline = True

        if strong >= STRONG_REQUIRED:
            print("Abnormal motion detected")
            raise_alert()
            strong = 0
            have_baseline = False

        time.sleep_ms(SAMPLE_MS)


main()
