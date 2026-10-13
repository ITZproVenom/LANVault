# LANVault

Turn an Android TV into a storage server for your whole home network. Plug in a USB drive, point
a browser or the iPhone Files app at it, and your media is streamable from the TV wherever it
lives.

Nothing is uploaded anywhere. Everything stays on your LAN.

## What you get

- **The TV's own storage** and every **USB / SD drive** plugged into it, browsable as one file tree.
- **A web interface** on your LAN — browse, stream and transfer files from any browser.
- **The iPhone Files app** through *Connect to Server* — native WebDAV support, no third-party app
  needed. Upload, download, move, rename, delete, same as any other file location.
- **Direct streaming** — video and audio play straight from the source through HTTP range
  requests, so playback starts before the file has finished arriving.
- **Two-way transfers** — phone to TV and TV to phone, both from the browser and from Files.
- **Display-only TV screen** — no menus to get lost in. It shows the server status, the live log,
  the web interface link and the exact Connect to Server link to type.
- **Everything is configured from the browser.** The TV never asks you to touch a setting.

## Install

Download `app-release.apk` from the [latest release](https://github.com/ITZproVenom/LANVault/releases/latest),
then install it on the TV. If the installer asks, allow installation from unknown sources.

```
adb install -r app-release.apk
```

## First run

1. Launch **LANVault** on the TV. The server starts on its own and will start again after every
   reboot.
2. The TV screen shows two links. Open the **web interface** link in a browser on your network,
   or tap **Grant storage access** on the TV if the screen asks for it.
   Android 11+ TVs need *All files access* before the app can read a USB drive.
3. That is the whole setup. Everything else lives in the web interface's **Settings** tab.

## Connecting

Both links are printed on the TV screen, and when the TV has more than one network address
**every** address is listed. Use the one on the same network as your device — the web interface
marks the address that actually reached it, so you can see which one works.

### From any browser

```
http://<tv-ip-address>:<port>/ui/
```

`<port>` is **8080** unless something on the TV already held that port (see below).

### From the iPhone Files app

The exact address is printed on the TV screen and in the web interface:

1. Files app → **Browse** → **…** (top right) → **Connect to Server**
2. Enter `http://<tv-ip-address>:<port>/` — no path after the port.
3. Tap **Connect**, then pick the branch that matches your settings:
   - **Access is open (the default)** — choose **Guest**. No username, no password.
   - **"Require a username and password" is turned on** — choose **Registered User**, then enter
     the username (default `lanvault`) and the password you set.
4. The share appears under **Shared** in the sidebar.

The TV screen always names the branch you should take, and the username to use when a password is
required, so you never have to guess which one applies.

### If a video will not preview

The web player can only play formats your browser can decode. iPhone and iPad Safari cannot play
Matroska (`.mkv`), `.avi`, `.wmv`, `.flv` or `.flac`, among others — the player says so instead of
failing silently, and offers a download. MP4/H.264 plays natively everywhere. Streaming itself is
not affected: any format the browser supports is streamed with range requests.

### If 8080 is already taken

Many Android TVs run their own remote control service on port 8080. If LANVault cannot bind the
port you asked for, it falls forward to the next free one instead of failing, and then tells you
exactly where it landed — on the TV screen, in the log, in the notification and in the web
interface. Your preferred port is never overwritten, so if the other app later releases 8080 a
restart picks it up again.

The web interface shows a **Keep port N** button that pins the fallback port permanently.

> **Protocol:** the iPhone Files app connects to file servers over SMB or WebDAV. LANVault speaks
> **WebDAV** (RFC 4918) at the server root, which needs no native libraries and keeps the whole
> server inside the app. AFP is no longer supported by iOS.

### From Android, Windows, macOS, Linux

Any WebDAV client connects to `http://<tv-ip-address>:8080/`. macOS Finder, Windows Explorer and
GNOME Files can mount it directly.

## Settings

Everything below is editable at `/ui/` → **Settings** while the server is running:

| Setting | What it does |
| --- | --- |
| WebDAV & web port | Port for both the web interface and iPhone Files. Needs a restart to apply. If it is busy the server falls forward to the next free port and says so. |
| Server name | Name shown to iPhone Files as the share name. |
| Require username and password | Applies a single set of HTTP Basic credentials to the web interface and WebDAV. |
| Allow uploads / deletions / new folders | Client write permissions. |
| Show hidden files | Includes names starting with a dot. |
| Start after boot | Keeps the server running when the TV powers on. |
| Keep CPU awake | Stops the screen dimming from interrupting a transfer. |
| Serve WebDAV | Turns the WebDAV share off, leaving only the web interface. |

## Network monitoring

The **Network monitor** tab is read-only and does not replace Every Proxy or AdGuard VPN.

- **TV metrics:** Android VPN transport when reported, memory, exposed thermal sensors, uptime, network-interface byte counters, and the kernel ARP neighbour table when readable.
- **Every Proxy:** optionally probe its local TCP listening port. This checks whether a listener accepts a connection; it does not validate proxy auth or change Every Proxy settings.
- **AdGuard Home:** connect to an already-running instance to read status, DNS statistics, known clients and recent query logs. DNS query counts are not internet bandwidth totals.
- **AdGuard DNS account:** sign in to the official API and sync existing DNS profiles, devices, and available last-24-hour query statistics. The account password is not stored; session tokens and AdGuard Home credentials are encrypted using Android Keystore. This integration is read-only.
- **Per-device bandwidth:** requires router traffic counters, SNMP, flow export or a compatible router API. The monitor does not infer per-device byte counts from DNS queries.

### Monitoring security

The `/api/monitoring/*` API requires LANVault username/password protection. Enable it in **Settings** before adding credentials. LANVault's current browser interface uses HTTP, not HTTPS: use sign-in/configuration only on a trusted LAN because browser-to-TV requests are not encrypted in transit. Cloud access tokens and local AdGuard Home credentials are encrypted at rest with an Android Keystore key.

AdGuard Home needs to be installed and running separately before you connect it here. The monitoring page does not install AdGuard Home or change router DNS settings. For network-wide filtering, configure the router's DHCP/DNS settings to use a reachable AdGuard Home DNS listener only after confirming the Home service works.

## How it is put together

```
app/src/main/java/com/lanvault/
├── MainActivity.kt        status screen: links, drives, live log (display only)
├── ServerService.kt       data-sync foreground service that keeps the server alive
├── BootReceiver.kt        starts the service on BOOT_COMPLETED
├── LanVaultApp.kt         initialises settings and wipes the cache on every start
├── TreePickerActivity.kt  grants a persistent SAF tree for drives the TV hides
├── net/LiteHttpServer.kt  HTTP/1.1: keep-alive, chunked bodies, ranged writes
├── storage/               volume discovery, direct filesystem and SAF access
├── web/WebDavHandler.kt   the RFC 4918 endpoint iPhone Files talks to
├── web/ApiHandler.kt      JSON API behind the web interface
└── web/MimeTypes.kt       extension to MIME type, video/audio/image detection
```

The storage root is virtual: the first path segment names the volume, the rest is the path inside
it. `/` lists the drives, `/tv-storage/…` walks the internal storage, `/usb-1234-5678/…` walks a
plugged-in disk.

## Building from source

Requirements: JDK 17, Android SDK with platform 34 and build-tools 34.0.0.

```bash
./gradlew assembleRelease    # produces app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest  # runs the WebDAV, HTTP range and API test suites
```

### Signing

Release builds fall back to the local **debug keystore** so the project builds out of the box. To
sign with your own key, create `keystore.properties` in the project root (it is git-ignored):

```properties
storeFile=/absolute/path/to/your.jks
storePassword=your-store-password
keyAlias=your-alias
keyPassword=your-key-password
```

## Tests

```
./gradlew testDebugUnitTest
```

52 tests covering the WebDAV method surface (OPTIONS, PROPFIND, PROPPATCH, GET/HEAD, PUT, DELETE,
MKCOL, MOVE, COPY, LOCK, UNLOCK, REPORT), byte ranges including suffix and out-of-range requests,
a 2 MB payload round-trip, chunked uploads, keep-alive, path-traversal safety, the JSON API,
Basic authentication and static asset routing.

## Notes and limits

- The port must be reachable from the TV's network. Ethernet and Wi-Fi are both used; all
  reachable IPv4 addresses are printed on the TV screen.
- Android 11+ requires *All files access*; if it is not granted the drive shows as **locked** and
  the web interface offers the SAF picker as a fallback.
- Only IPv4 addresses are shown on the TV screen.
- There is no encryption. The connection is plain HTTP, which is normal for a local server but
  means anyone on the LAN can read the traffic if they sniff it. Keep it on a network you trust.
