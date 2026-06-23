# BluetoothJammer (WIP)

A Bluetooth **exposure probe** for testing your own devices. It scans for
devices and, against a target you select, repeatedly attempts unpaired
RFCOMM connections and reports whether the device **accepts or refuses**
them. Use it to check whether your own Bluetooth gear is reachable by
unpaired devices, then harden it.

> ⚠️ **What this is not:** it is **not** an RF jammer. It does not transmit
> interference and cannot block a radio link. It is an application-layer
> connection probe, so it will *not* reliably disrupt a normal Bluetooth
> speaker — most devices simply refuse the connection (which is the secure
> result you want to see).

## Use it responsibly
Only probe devices **you own or are explicitly authorised to test**.
Interfering with or jamming devices you don't own is illegal in most
countries.

## How to read the result
- **"⚠ Target ACCEPTED a connection"** — the device let an unpaired phone
  connect. That's an exposure worth hardening.
- **"✓ Target refused every connection attempt"** — good; it is not
  accepting unpaired connections.

## Hardening tips for your own devices
- Keep firmware up to date; many BT issues are fixed in updates.
- Turn off discoverability / pairing mode when not actively pairing.
- Unpair devices you no longer use; prefer devices that require a PIN/passkey.
- Power off or disable Bluetooth on speakers/peripherals when idle.
- For phones/laptops, disable Bluetooth when not in use in untrusted areas.

# Preview
<table style="padding:10px">
  <tr>
    <td>
        <img src="./assets/attack.png" alt="1">
    </td>
   </tr>
</table>

# TODO
- [X] Material UI
- [X] Thread Option
- [X] Devices List (now runs real discovery + paired devices, deduped)
- [X] Log Switch
- [X] Start/Stop button (Stop now cancels every worker - no force-close needed)
- [X] Auto randomize UUID
- [X] Optimize Attack Thread

# Special Thanks
- ChatGPT-4o