### Downloads

| Platform | File | Requirements |
|---|---|---|
| **macOS** | `IR-Controller-__VERSION__-macos.dmg` (or `.zip`) | macOS 26 or later, Apple silicon or Intel |
| **Android** | `IR-Controller-__VERSION__-android.apk` | Android 8.0 (API 26) or later with USB host (OTG) support |

Both apps need an **ELK Smart USB IR blaster** (USB vendor ID `0x045C`), such as the EKX5S-T USB-C model.

### Installing on macOS
1. Open the `.dmg` and drag **IR Controller** into Applications.
2. The app is signed ad hoc, not with an Apple Developer ID, and isn't notarized. On first launch macOS says it can't verify the developer: open **System Settings → Privacy & Security** and click **Open Anyway**, or run `xattr -dr com.apple.quarantine "/Applications/IR Controller.app"`.
3. Plug in the blaster. Its status appears at the bottom of the sidebar.

### Installing on Android
1. Download the `.apk` on your phone or tablet and open it. Allow your browser or file manager to install unknown apps when Android asks.
2. Plug in the blaster and tick **Always open** when Android offers to open IR Controller.

__ANDROID_SIGNING_NOTE__
