# SAHAYAT band firmware (Raspberry Pi Pico W, MicroPython)

`main.py` is the whole firmware. It reads the MPU6050, runs the prototype abnormal-motion detector,
drives the LED and buzzer, handles the cancel button, and talks to the SAHAYAT app over Bluetooth LE
using MicroPython's built-in `bluetooth` module (no extra libraries).

> The detector is a simple prototype threshold algorithm. It is **not** medically validated.

## Wiring

| Part | Pico W |
|---|---|
| MPU6050 VCC / GND | 3V3 / GND |
| MPU6050 SDA / SCL | GP4 / GP5 (I2C0, address 0x68) |
| LED (+ resistor) | GP15 |
| Buzzer | GP14 |
| Cancel button | GP16 ↔ GND (internal pull-up, pressed = 0) |

## Flash

1. Install MicroPython **1.20 or newer** for the Pico W (the `RPI_PICO_W` build — it includes Bluetooth).
2. In Thonny, open `main.py` and **File → Save as → Raspberry Pi Pico → `main.py`**.
3. Delete any old `main.py`/`boot.py` on the Pico that used the `12345678-…` UUIDs.
4. Press Stop/Restart. The console shows `Advertising as SAHAYAT-BAND` and `STATUS -> MONITORING`.

## What a BLE scanner (nRF Connect) should show

```
SAHAYAT-BAND
Service 7B7E0001-6B8A-4D9E-9C5B-2A4F6D8E1001
  7B7E0005-…  Device ID   READ            → "SAH-0001"
  7B7E0002-…  Status      READ, NOTIFY    → "MONITORING"
     0x2902   (CCCD — enables Status notifications)
  7B7E0003-…  Alert       READ, NOTIFY
     0x2902   (CCCD — enables Alert notifications)
  7B7E0004-…  Command     READ, WRITE
```

`0x2902` is the Client Characteristic Configuration Descriptor. The Bluetooth stack adds one under each
NOTIFY characteristic automatically. It is **not** the SAHAYAT service; the app never scans for it.

## Protocol (UTF-8 text)

| Direction | Characteristic | Text |
|---|---|---|
| phone → band | Command | `PING`, `START`, `STOP`, `STATUS` |
| band → phone | Status | `SAHAYAT_OK` (reply to PING), `MONITORING`, `STOPPED`, `MPU_ERROR` (sensor not responding) |
| band → phone | Alert | `ALERT|SAH-0001|SEIZURE|<ref>` when abnormal motion is detected (LED + buzzer on) |
| band → phone | Alert | `ALERT|SAH-0001|CANCELLED|<ref>` if the button is pressed within 2 s |
| band → phone | Alert | `ALERT|SAH-0001|ALERT_ACTIVE|<ref>` if not cancelled |

`<ref>` = `<random boot id>-<alert number>` (e.g. `3fa1-2`), so the phone never processes one alert twice.
Messages are longer than 20 bytes, so the app requests a larger MTU (185) before enabling notifications.

The app only sends an SOS to caregivers after `ALERT_ACTIVE` (or if no `CANCELLED` arrives within 4 s),
so a cancelled alert never reaches caregivers.

## Testing with nRF Connect (before using the app)

1. Scan → connect to **SAHAYAT-BAND**.
2. Read Device ID → `SAH-0001`.
3. Tap the notify icon on Status and Alert.
4. Write the text `PING` to Command → Status notifies `SAHAYAT_OK`.
5. Shake the band hard → Alert notifies `…|SEIZURE|…`, then `…|ALERT_ACTIVE|…` (or `…|CANCELLED|…` if you press the button).

Disconnect nRF Connect before pairing from the app — the band accepts one phone at a time.
