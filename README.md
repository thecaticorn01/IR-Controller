# IR Controller

**IR Controller** turns an **ELK Smart USB-C IR blaster** into a universal remote. You can search thousands of remotes online, try them before importing, learn the ones that aren't in any database, and control your TV, projector, air conditioner or soundbar.

IR Controller is cross-platform, but every platform gets a **native app** built with that platform's own tools and design language. There's no shared web-view shell like Electron, so each app stays small, fast and at home on its system.

| | macOS | Android |
|---|---|---|
| **App** | SwiftUI + Liquid Glass, macOS 26+ | Jetpack Compose + Material 3 Expressive, Android 8.0+ |
| **Code** | [`macos`](https://github.com/thecaticorn01/IR-Controller/tree/macos) branch | [`android`](https://github.com/thecaticorn01/IR-Controller/tree/android) branch |
| **Download** | `IR-Controller-<version>-macos.dmg` | `IR-Controller-<version>-android.apk` |

**[⬇ Download the latest release](https://github.com/thecaticorn01/IR-Controller/releases/latest)**

| macOS | Android |
|---|---|
| <img src="https://github.com/thecaticorn01/IR-Controller/raw/macos/docs/screenshots/ir-controller-remote.png" alt="IR Controller on macOS" width="520"> | <img src="https://github.com/thecaticorn01/IR-Controller/raw/android/docs/screenshots/remote.png" alt="IR Controller on Android" width="240"> |

## Features

Both apps offer:

- **Online remote databases, built in**: search and filter remotes from [Flipper-IRDB](https://github.com/Lucaslhm/Flipper-IRDB) and [IRDB](https://github.com/probonopd/irdb) by brand, model and device type. You can try a remote's buttons with your blaster before adding it. **Power Scan** finds a working code when you don't know the model, and you can add any GitHub repository of `.ir`/`.csv` files as an extra source.
- **Learn new remotes**: a guided wizard captures each button from your physical remote, shows the waveform, and recognises NEC, Samsung, Sony SIRC and RC5 codes. You can also enter codes by hand (protocol + address/command, Pronto hex, Global Caché `sendir`, raw timings).
- **A physical-remote layout**: power, volume and channel rockers around a D-pad, transport controls, number pad and colour keys, plus a grid for everything else.
- **26 protocol encoders**: every Flipper Zero protocol plus the common IRP protocols used by IRDB. RC5/RC6 toggle bits alternate correctly between presses.
- **Library management**: favourites, editing and reordering, plus Flipper Zero `.ir` import and export.

Each platform also has its own integrations: a menu bar remote, keyboard control and Shortcuts on macOS; a home-screen widget, pinned buttons, volume-key control and automation broadcasts on Android. See each branch's README for details.

## Hardware

IR Controller works with ELK Smart USB IR blasters (USB vendor ID `0x045C`; known product IDs `0x0131`, `0x0132`, `0x0134`, `0x014A`, `0x0184`, `0x0195`, `0x02AA`), sold under the ELK Smart, Ocrustar and ZaZa Remote names. Both apps talk to the dongle directly over USB, with no drivers.

The dongle's protocol was reverse-engineered by Přemysl Eric Janouch in [`elksmart-comm`](https://git.janouch.name/p/usb-drivers) (0BSD licence). Each app has its own independent implementation, tested byte-for-byte against that reference.

## Repository layout

Each platform lives on its own branch, with its own history, so reading the code or the log for one app never involves the others:

| Branch | Contents |
|---|---|
| `main` | This overview and the GitHub Actions workflows that build, test and release every platform. No app code. |
| [`macos`](https://github.com/thecaticorn01/IR-Controller/tree/macos) | The macOS app (Xcode project, Swift package `IRCore`) and its README. |
| [`android`](https://github.com/thecaticorn01/IR-Controller/tree/android) | The Android app (Gradle project, `core` and `app` modules) and its README. |

To work on an app, check out its branch and open pull requests against that branch:

```sh
git clone --branch macos   https://github.com/thecaticorn01/IR-Controller.git IR-Controller-macos
git clone --branch android https://github.com/thecaticorn01/IR-Controller.git IR-Controller-android
```

## Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) checks out each platform branch and builds it with the reusable workflows [`macos.yml`](.github/workflows/macos.yml) and [`android.yml`](.github/workflows/android.yml):

- **macOS**: `IRCore` tests on macOS and Linux, then app builds with Xcode 26 (macOS 26 SDK) and Xcode 27 (macOS 27 SDK). It also launches the app and captures screenshots.
- **Android**: unit, protocol and screenshot tests, lint, and debug and release APKs.

GitHub only runs a branch's workflows from that branch's own files, and the platform branches deliberately contain no workflows. So CI runs on pushes and pull requests to `main`, every night, and on demand: **Actions → CI → Run workflow** lets you pick a platform and any branch, tag or commit. Run it after pushing to a platform branch to check your change straight away.

## Releases and versioning

Versions follow [Semantic Versioning](https://semver.org): `MAJOR.MINOR.PATCH`, with an optional pre-release suffix such as `1.1.0-beta.1`. A release is tagged and named `vX.Y.Z` and contains every platform's app at version `X.Y.Z`.

[`.github/workflows/release.yml`](.github/workflows/release.yml) builds both apps, verifies them and publishes a single GitHub release with:

- `IR-Controller-X.Y.Z-macos.dmg` and `.zip`: a universal (Apple silicon + Intel) app built with the latest Xcode and signed ad hoc.
- `IR-Controller-X.Y.Z-android.apk`: a minified release APK.
- `SHA256SUMS.txt`.

To cut a release:

1. Optionally add release notes in `.github/changelog/X.Y.Z.md` on `main`. They're placed above the standard download and install instructions from [`.github/release-notes.md`](.github/release-notes.md).
2. Either push a tag (`git tag vX.Y.Z && git push origin vX.Y.Z` from `main`), or run **Actions → Release → Run workflow** with the version number. A manual run can also build specific branches, tags or commits of each app.

App version numbers are stamped at build time, so neither platform branch needs a version bump:

- **macOS**: `CFBundleShortVersionString` is `X.Y.Z` (without any pre-release suffix) and `CFBundleVersion` is the workflow run number.
- **Android**: `versionName` is the full version. `versionCode` is `((MAJOR × 100 + MINOR) × 100 + PATCH) × 100 + N`, where N is 99 for a final release and the pre-release number otherwise. For example, `1.2.3` → `1020399` and `1.3.0-beta.2` → `1030002`, so pre-releases sort before the final release and updates always install.

### Android signing key

Android only installs an update when it's signed with the same key as the installed app. Add these repository secrets (**Settings → Secrets and variables → Actions**) so every release uses the same key:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | The keystore file, base64-encoded (`base64 -i release.keystore`) |
| `ANDROID_KEYSTORE_PASSWORD` | The keystore password |
| `ANDROID_KEY_ALIAS` | The key alias |
| `ANDROID_KEY_PASSWORD` | The key password (optional if it's the same as the keystore password) |

To create a keystore:

```sh
keytool -genkeypair -v -keystore release.keystore -alias ircontroller -keyalg RSA -keysize 4096 -validity 10000
```

Keep the keystore and passwords somewhere safe: if they're lost, existing installs can't be updated. Without these secrets, the release APK is signed with a temporary debug key and the release notes tell users to reinstall instead of updating.

## Credits

- Remote data is downloaded at runtime from [Flipper-IRDB](https://github.com/Lucaslhm/Flipper-IRDB) and [IRDB](https://github.com/probonopd/irdb). See those projects for their licences. No database content is bundled with the apps.
- ElkSmart protocol: [elksmart-comm](https://git.janouch.name/p/usb-drivers) by Přemysl Eric Janouch.
- Protocol timings follow the Flipper Zero firmware conventions and the IRP notation used by IrScrutinizer/IRDB.
