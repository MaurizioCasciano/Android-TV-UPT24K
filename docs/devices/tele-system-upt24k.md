# UPT24K launcher: build, privacy and recovery

This is Maurizio Casciano's TELE System UPT24K adaptation of
[CleanYourTV by Gathin](https://github.com/Gathin23/cleanyourtv), based on commit
`6e8615eb0be0e843ceeebedae246d69f3e0a88d7`. The original Git history and
[MIT licence](../../LICENSE) are retained. GitHub registers this repository as a
public fork of CleanYourTV. It contains personal adaptations and is not an
official CleanYourTV release.

## Tested device and features

Tested on TELE System UPT24K, Android 12, firmware `V84511302.2033`.
Compatibility with other devices has not been established.

- Native CPU history, RAM and data-partition storage cards.
- LAN/public IP, country flag and network operator card.
- Network icon green with validated Internet access, amber when connected without Internet, red when offline.
- Parallel LibreSpeed download/upload test, HTTP latency and jitter.
- Automatic test after validated Internet access while Home is visible.
- Last completed speed measurement saved locally with its timestamp.
- Existing dock and app navigation, with a device-specific Home helper profile.

The displayed Ping is HTTPS request latency, not ICMP ping. This is a bounded
LibreSpeed estimate, not Ookla's engine; server, route, VPN, device and timing
can produce different readings.

## Network traffic and local data

The original reference launcher has no Internet permission. This derivative
adds `INTERNET` for the following features; cleartext traffic is disabled.

| Feature | Destination | Behaviour and data |
|---|---|---|
| IP/country/operator | `https://ipwho.is/?fields=success,ip,country_code,connection.isp,connection.org` | The provider sees the request's public source IP and a generic `UPT24K-Home/1.0` user agent. It returns IP/country/operator data. The LAN IP is read locally and is not included in the request. |
| Speed test | `https://st-be-rm2.infra.garr.it/` and `https://fra.speedtest.clouvider.net/backend/` | HTTPS latency probes select a server, then four parallel synthetic download/upload flows measure throughput. Both probe destinations can see the source IP; the upload contains synthetic bytes, not files from the decoder. |

Requests use Android's default route for this app, including any VPN policy
that applies to it. The launcher does not bind test sockets to a physical
interface or bypass a VPN. Third-party server logging and retention are
outside this project's control. No API keys or accounts are embedded.

The IP lookup runs only while Home is resumed, with a five-minute in-memory
cache and retry backoff. Public IP, LAN IP and provider responses are not
persisted by this card. Country flags are generated locally, without image
downloads or GPS queries.

The speed test starts after roughly 1.2 seconds of validated access while
Home is visible. Attempts are deduplicated per Android network handle and
boot, with at least a minute between automatic attempts on different networks.
Returning to Home does not start a new completed attempt on the same network.
An interrupted first automatic test can retry after the cooldown. OK starts
or stops a manual test. Leaving Home cancels transfers; no background speed
test service is installed.

Each complete test transfers up to **32 MiB down + 8 MiB up** (40 MiB,
approximately 42 MB of payload), plus HTTPS/probe overhead. New boots or
network/VPN changes can trigger another test. This matters on metered links.

Private app preferences retain the last completed speeds, HTTP ping/jitter,
timestamp and server name. They also retain bounded automatic-attempt state:
boot counter, monotonic attempt time and opaque network-handle/VPN pairs.
These records contain no IP address, SSID, account or Android ID. Clearing
the app's storage removes them and also resets the launcher's other settings.
The application declares `allowBackup=false`.

The added diagnostics contain no analytics SDK, advertising calls or result
submission API. This statement covers this launcher's additions, not other
installed apps or the third-party providers' logs. Build dependencies are
downloaded separately by Gradle on the development computer.

To omit both network cards from a build, set `show_network_card` to `false`
in `launcher/app/src/main/res/values/config.xml` before building. This switch
is a resource value, not a supported theme JSON key. The current personal
build enables the cards; no network-permission removal is claimed by hiding them.

## Build from a fresh checkout

Install a full JDK 21, Android SDK platform 34 and build-tools 34.0.0. Set
`JAVA_HOME` and `ANDROID_HOME` to your installations. The included wrapper
uses Gradle 8.9 and downloads it and the pinned dependencies on the first run.
The SDK, caches and signing keys are intentionally not in Git.

```sh
git clone https://github.com/MaurizioCasciano/Android-TV-UPT24K.git
cd Android-TV-UPT24K
git switch TELE-System-UPT24K
cd launcher
./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
```

Output: `launcher/app/build/outputs/apk/release/app-release.apk` from the
repository root. For Windows, use `gradlew.bat` in place of `./gradlew`.
The Python ADB tools are optional for building the launcher. Their local
validated dependency set is in `tools/requirements-local.lock.txt`.

The build uses the debug signing configuration inherited from upstream.
A fresh checkout creates/uses your own Android debug key. It cannot update an
existing installation signed by someone else's key. Preserve your key privately
for future updates; never publish or replace the key of an existing installation.
This repository distributes source, not a shared signing identity or release APK.

The original dashboard snapshot passed 341 JVM tests with zero lint errors; its
613,241-byte APK is recorded in the [original validation record](../../profiles/upt24k/validation.json).
After the upstream rebase and IPv6 correction, the installed launcher passed
353 JVM tests with zero lint errors. Its APK is 613,753 bytes. Both builds exceed
upstream's 589,824-byte size gate; this derivative does not claim to pass the
original project's full CI or size requirements.

The real-device evidence covers the Italian UPT24K setup. The inherited Hebrew
layout and translations have not been adapted to the new cards. Upstream Python
checks also flag the added `profiles/` configuration folder and the resource-only
`show_network_card` switch. These are known derivative-integration gaps, not a
passing upstream compatibility claim. In particular, `tools/manage.py set` does
not yet protect `profiles/` like `themes/` and `examples/`: copy the profile to a
folder of your own before editing it with that tool.

## Network troubleshooting

The country/IP card accepts numeric IPv4 and IPv6 addresses. Older personal
builds used Android's IPv4-only `Patterns.IP_ADDRESS` validator: an IPv6 response
from the provider was wrongly shown as a connection error despite a successful
HTTPS request. The validator now accepts both families without resolving
hostnames or changing the network route.

A separate YouTube failure on the tested unit was traced to DNS lookup timeouts
for YouTube domains over ordinary DNS, while other domains and IP connectivity
worked. Android Private DNS with `one.one.one.one` restored name resolution.
This is an optional device setting, not a launcher feature or an APK default;
the encrypted resolver receives the DNS queries handled by that setting. Save
previous Private DNS settings before changing them. A later transport check
still timed out when the same server was asked for YouTube's TLS server name,
but succeeded with Google's server name. This is consistent with network
filtering by TLS SNI. An owner-approved VPN trial restored the YouTube TLS
connection. No router setting was changed and the exact filter location was
not established. Test a VPN only with the owner's agreement. The launcher never
changes VPN routing, disables certificate validation or silently replaces YouTube.
No static Wi-Fi address or app-data reset was used.

## UPT24K Home safety and installation

On the tested ZTE-based device, **keep `com.google.android.tvlauncher` enabled
and set as the default Home**. The stock middleware has a factory-reset path
when it cannot find an acceptable system Home. Do not disable the stock Home,
remove ZTE control services, or open factory/test activities.

The generic upstream installation instructions below the repository's UPT24K
notice are reference material. Do not run a generic stock-launcher takeover on
this decoder. Install the APK alongside the stock launcher instead:

```sh
adb connect '<tv-ip>:5555'
adb install -r launcher/app/build/outputs/apk/release/app-release.apk
TV_HOST='<tv-ip>' bash tools/theme.sh profiles/upt24k
adb shell am start -n com.gathin.tvhome/.MainActivity
```

Run these commands from the repository root, substituting your decoder's IP.
`adb install -r` requires the same signing key if the app is already installed.
Authorize ADB on the TV yourself; no authorization key is shipped in Git.
Apply the profile **before** enabling HomeKeyService in Android accessibility
settings. The profile sets `stock_home_redirect` to the stock launcher and
`key_filter=false`; the latter preserves held volume-key repetition on this
device. The build's generic defaults do not replace that profile.

The helper observes window changes to open the personal Home. Redirecting a
stock Home already visible immediately after a full reboot remains a known
limitation. Updates or force-stopping the app may require re-enabling the helper.

For recovery, turn off this app's accessibility helper and return to the still
enabled stock Home. To roll back an APK, use your privately retained earlier
APK with the same signing key. No root or firmware flashing is required or
included. No generic debloat list is published for this device: personal app
removals are not a tested reversible list for other owners.

## Upstream contributions

Small generic fixes and tested device notes should be proposed separately
against a fresh upstream branch, following its
[contributor guide](https://github.com/Gathin23/cleanyourtv/blob/main/CONTRIBUTING.md).
The network diagnostics would need to be designed as independent add-ons to
fit upstream's module contract. This complete derivative belongs in a separate
repository and can be proposed separately to the upstream Showcase.

No upstream issue, PR or Showcase entry is implied by publishing this repository.
Inherited guides and translations describe the original reference project;
this page and [UPT24K.txt](../../UPT24K.txt) describe this adaptation.

## Publication review

The [sanitized publication audit](../../profiles/upt24k/publication-audit.json)
records the checked history, scanner version and reviewed false positives.
Private decoder backups, screenshots and signing/ADB keys are excluded.
Normal Git author names and email addresses remain part of the inherited and
personal commit history. The audit describes the earlier publication review;
the four personal commits were subsequently rebased onto upstream. No upstream
issue, PR or Showcase submission was made.
