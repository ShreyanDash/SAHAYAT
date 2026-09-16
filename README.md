# SAHAYAT — seizure alert band + Android app

Native Android app (Kotlin + Jetpack Compose), Firebase backend, and Raspberry Pi Pico W firmware.

```
Band (MPU6050 + Pico W) ─Bluetooth─► patient's phone ─┬─ internet ─► Firestore ─live listener─► caregiver phones ring
                                                       └─ SMS from its own SIM ─────────────────► caregiver phones ring
```

Runs on Firebase's **free Spark plan** — no Cloud Functions, no Blaze, no card. Two independent alert paths:

1. **Firestore (free):** the patient's phone writes the alert; each caregiver phone keeps a live listener
   (a small "SAHAYAT alerts" notification) and rings the full-screen alarm as soon as it appears.
2. **SMS (patient's normal SMS plan):** the patient's phone texts every SMS contact at the same time. If the
   caregiver has SAHAYAT, that text itself rings the alarm — this works even when either phone has no internet.
   Caregivers without the app still get a readable text.

No SIM800L or Wi-Fi is needed on the band. One app, two modes chosen on first launch:

* **Patient's phone** — pairs with the band, stays connected in the background, relays alerts and status.
* **Caregiver's phone** — joins the patient with an invite code and rings with a full-screen SOS alarm.

The app never invents data: battery, sensor, signal, connection, push and SMS results are shown only when the
band, Firestore or the phone's radio actually reported them. Otherwise it says **Unavailable** or **Not connected**.

## Project layout

```
app/                     Android app
  ble/                   BLE protocol, scanner/GATT client, BandLinkService (relay), BootReceiver
  core/ data/            Firebase access, models, services (Auth, Patient, Device, Alert, Caregiver, History,
                         Notification, Relay), settings, session
  emergency/             Alert listener, SOS SMS receiver, alarm service, notifications, SmsFallback
  ui/                    Screens (onboarding, band pairing, invite/join, home, SOS, history, contacts, settings…)
backend/                 Firebase Functions (TypeScript), Firestore rules & indexes
firmware/pico_w/         MicroPython firmware for the band (see its README)
docs/INTEGRATION.md      Full band ↔ phone ↔ backend contract
```

## 1. Firebase (once, free Spark plan)

1. Create a Firebase project and add the Android app `com.sahayat.app`; put `google-services.json` in `app/`.
2. **Authentication → Sign-in method → Email/Password → Enable.**
3. **Firestore Database → Create database** (production mode, location e.g. `asia-south1`).
4. Deploy the security rules and indexes (free):
   ```bash
   npm install -g firebase-tools
   firebase login
   cd backend
   firebase use --add          # pick your project, alias "default"
   firebase deploy --only firestore
   ```
   Answer **n** to "Enable Gemini". Do **not** deploy functions — `backend/functions` is the old Blaze-only
   version and is no longer used.

## 2. The band

Flash MicroPython 1.20+ (Pico W build) and save `firmware/pico_w/main.py` to the Pico as `main.py` — details,
wiring (MPU6050 GP4/GP5, LED GP15, buzzer GP14, cancel button GP16) and an nRF Connect checklist in
`firmware/pico_w/README.md`. The band advertises as **SAHAYAT-BAND** with service `7B7E0001-6B8A-4D9E-9C5B-2A4F6D8E1001`
and Device ID `SAH-0001`.

## 3. Build & install the app

Android Studio (Ladybug+), JDK 17. Open the folder, Sync, Run. Android 8.0+ with Bluetooth LE.
Install on **two phones** (the patient's and a caregiver's). On an existing install, uninstall first so the
first-launch setup appears.

## 4. Try the whole flow

1. **Patient's phone:** first launch → choose *Patient's phone* → fill in your and the patient's details →
   create an account → add caregiver contacts (turn on SMS for those who should get texts) → *Pair your band* →
   allow Bluetooth → tap **SAHAYAT-BAND** (the app connects, reads `SAH-0001` and enables notifications before
   linking) → allow **SMS** and **Run in background**. Home shows **Band link · Connected over Bluetooth**, the band
   state (Monitoring/Stopped), the last PING reply, and Start/Stop/Ping buttons.
2. Settings → **Invite a caregiver** → share the 8-character code (valid 24 h).
3. **Caregiver's phone:** first launch → *Caregiver's phone* → create an account (different e-mail) →
   enter the code. On Home, the **alert paths** card must show: listener running, SMS allowed, battery unrestricted.
   Also check Settings → Notifications (notifications + full-screen alerts allowed).
   Save the caregiver's number as an SMS contact on the patient's phone (Contacts tab, SMS on).
4. **Test (internet path):** on the patient's phone turn on **Home → Band link → Test mode**, then shake the band hard
   (LED + buzzer turn on). Don't press the button. About 2 s later the caregiver's phone rings with an SOS screen
   labelled **TEST**. Acknowledge it — the alarm stops on both phones. Pressing the band button within 2 s cancels it
   and nothing is sent.
5. **Test (SMS path):** trigger a TEST alert (test mode on) and don't acknowledge it. After the escalation time (Detection
   settings, default 60 s) the patient's phone sends one text starting `[TEST] SAHAYAT SOS`. Turn off the
   caregiver's mobile data first to see the text alone ring the alarm.
6. **Real alerts** text every SMS contact immediately *and* post to Firestore. If the patient's phone is offline the
   alert is queued and posted when the internet returns (the text already went out).

## Emergency alert behaviour (Android)

| Situation | What happens |
|---|---|
| Caregiver phone, any app state, screen locked | Live Firestore listener (foreground service) or SOS text → alarm service (ALARM stream + vibration) + full-screen SOS |
| Nobody acknowledges | The patient's phone marks the alert ESCALATED (caregivers get a notice) and, for TEST alerts or if the first text failed, sends a text |
| Stop | Only after the backend confirms Acknowledge / Cancel (safety cap: sound stops after 10 min) |
| Service can't start | Insistent notification on a backup channel |
| Band out of range | Relay keeps reconnecting; caregivers get "band not reporting" after 3 min without an update |

Android / phone makers control Silent, Do Not Disturb, full-screen permission and battery optimisation; the
Notifications screen shows each state and links to the right system page. Some brands (Xiaomi, Oppo, Vivo, Realme,
Samsung) also need *Autostart* / *No restrictions* for SAHAYAT so the Bluetooth relay keeps running.

**Honest limits of the free design:** the internet path needs the caregiver phone to have data and the
listener to be allowed to run (battery "Unrestricted"; on Xiaomi/Oppo/Vivo/Realme also enable Autostart). It is
not as instant as server push when a phone is in deep sleep — that is why every real alert is also sent by SMS.
Escalation runs on the patient's phone, so it needs that phone to stay on.

`SEND_SMS` / `RECEIVE_SMS` are restricted permissions on Google Play; side-loaded / Android Studio installs work as is.

## TEST alerts

* **Test mode** (Home → Band link) → a real band detection sent end-to-end as `alertType = DEVICE_TEST`, labelled
  TEST in the SOS screen, History and PDF export. It never texts immediately; only on escalation, with a `[TEST]` prefix.
  Turn it off for real use.
* **Settings → Developer → Send TEST ALERT** (debug builds only) → local alarm check on one phone, never stored.

## Notes

* **Location:** the patient's phone attaches its real position (fused GPS) to each alert, adds a Google Maps link to
  the SOS text, and updates the position every 30 s for 15 min while the alert is open. Caregivers see it on an
  in-app OpenStreetMap map (free, no API key) with a Directions button. It needs Location permission set to
  **Allow all the time** and Location switched on (Home shows a card for this); otherwise alerts honestly say
  *Location unavailable*.
* The band firmware reports no battery level, so battery shows *Unavailable*. Its motion threshold is fixed, so the
  app's sensitivity setting is saved but not applied on the band (the Detection screen says so).
* The motion detector is a prototype threshold algorithm, not a medically validated seizure detector.
* No longer used, safe to delete: `backend/functions/` (Blaze-only version), `ui/screens/pairing/DevicePairingScreen.kt`,
  `QrScanner.kt`. `SahayatMessagingService` (FCM) stays registered but receives nothing on the free plan.
