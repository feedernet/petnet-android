# petnet-android

This in an Android app for configuring the PetNet SmartFeeder V2. It uses
Bluetooth LE to configure the PetNet SmartFeeder to talk to WiFi and work with
the [petnet-feeder-service](https://github.com/feedernet/petnet-feeder-service)
project.

## Local firmware setup (app 1.1)

Stock firmware keeps the existing Wi-Fi setup. On compatible local firmware,
service discovery enables broker host/port and login settings, a saved
HTTP/MQTT selector, temporary web access, and MQTT resume. Missing or
unsupported capabilities never cause extension writes to stock devices.

A blank MQTT password preserves the saved secret; use the explicit checkbox to
clear it. Enabling MQTT warns that web access pauses. BLE remains the recovery
path. Status distinguishes saved mode from active/connecting mode.

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. Build the
existing Gradle 6.5 project with JDK 11 and Android API 29/build-tools 30.0.2:

```sh
./gradlew --no-daemon testDebugUnitTest assembleDebug
./gradlew --no-daemon connectedDebugAndroidTest
```

Full wire specification and bench notes are in the companion firmware's
`BLE_SETUP.md`. Live phone-to-feeder validation is pending; codec and Android
emulator compatibility tests pass. BLE attribute-value logging is disabled so
Wi-Fi/MQTT credentials are not written to debug logs.

## Stock DNS workaround (app 1.2)

On a feeder with only the stock GATT setup service, the app offers an
experimental DNS setup section. Enter the real WPA Wi-Fi credentials, a
migration DNS IPv4 address (standard port 53), and the feeder's reserved static
IPv4 address, subnet mask and gateway. Obtain these from the router after
normal DHCP provisioning; the stock BLE IP notification does not provide the
lease details.

Apply sends separate DHCP-disable, static-address and DNS-replacement password
payloads, waits 45 seconds after each, then sends the clean Wi-Fi password.
Keep the setup screen connected throughout. Intermediate Wi-Fi errors are
expected.  The app waits for connection/IP notifications after the final
submission, but cannot verify DNS routing or OTA from that notification. The
bench DNS override has reverted during reconnect, so this remains experimental.
Router redirection was still required for the completed DNS-server migration
test.

Restore DHCP and Wi-Fi sends a DHCP-enable payload, waits 45 seconds, then
sends clean credentials. If a write or connection fails, the app stops rather
than replaying an unknown outcome; reconnect and restore DHCP or retry the
complete sequence. Temporary command passwords may remain on stock after
interruption.  No Wi-Fi/MQTT secrets are logged or saved in instance state by
this flow.  Rust local-setup capabilities and `SF R...` advertisements block
this stock-only workaround. Ordinary Wi-Fi setup remains unchanged.

Rust 0.6.2 advertises `SF R0.6.2 <12-hex-device-id>` while keeping the original
service UUID. This app prefers the current scan's name over Android's cached
name, so the firmware change is visible before connecting. The exact full build
ID is also available in Device Information / Firmware Revision (`0x2A26`).

Validation: clean unit tests and both debug/test APK builds pass. Added tests
cover address/command bounds, exact credential framing, stock/Rust layout and
Rust-write prevention. The physical Pixel completed the stock static/DNS
command sequence and Wi-Fi IP notification. DNS retention still needs
independent verification; a later stock DHCP restore attempt could not
reconnect before sending.

## DHCP / Static IPv4 toggle

Both stock and Rust setup now present a DHCP toggle (off means Static IPv4).
DHCP is the default. On stock the toggle selects commands to apply; stock
cannot report its currently saved mode. Rust reads the saved mode directly.
Static fields are feeder address, subnet mask, gateway and DNS; the mask starts
at `255.255.255.0` and remains editable. No private migration DNS address is
baked into the app. Selecting DHCP on stock requests DHCP restore and clean
Wi-Fi association. Selecting static runs the experimental stock stages.

Rust 0.6.3 advertises the native IPv4 capability. On that firmware the app
reads saved network settings, uses revision-checked native BLE writes, and does
not inject commands into Wi-Fi passwords. Rust persists the choice and
reapplies it at every modem boot. Older Rust firmware without this capability
retains its existing setup controls. DHCP uses automatic DNS; static mode
requires a DNS IPv4 address. Wi-Fi credentials are only needed for the stock
workaround, not for changing Rust's saved IPv4 mode.

Physical Rust validation: the Pixel discovered the versioned feeder, saved
native Static IPv4, read it back after a feeder reset, and restored DHCP. HTTP,
DNS capture, DHCP packets and flash-journal readback confirmed the result.
Existing feeder configuration was preserved. Eight JVM tests and six physical
Android instrumentation tests pass. The phone was moved closer after early BLE
drops.
