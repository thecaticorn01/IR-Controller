MacIR __VERSION__ turns an **ELK Smart USB-C IR blaster** into a universal remote for your Mac.

### Highlights
- **Online remote databases:** search and filter about 12,000 remotes from Flipper-IRDB and IRDB by brand, model and device type. Try a remote's buttons before importing it, and use **Power Scan** to find a working code when you don't know the model.
- **Learn new remotes:** a guided wizard captures each button from your physical remote and recognises common protocols. You can also enter codes by hand or paste Pronto, sendir or raw timings.
- **Remote layout:** buttons are arranged like a physical remote using Liquid Glass. Also includes keyboard control, a menu bar remote, a Shortcuts action, and Flipper `.ir` import and export.

### Requirements
- macOS 26 Tahoe or later (built with Xcode 27 for macOS 27 "Golden Gate"). Universal binary for Apple silicon and Intel.
- An ELK Smart USB IR blaster (USB vendor ID `0x045C`, e.g. the EKX5S-T USB-C model).

### Installing
1. Download **MacIR-__VERSION__.dmg** and drag MacIR into Applications (or unzip **MacIR-__VERSION__.zip**).
2. This build is signed ad hoc, not with an Apple Developer ID, and isn't notarized. On first launch macOS says it can't verify the developer. Open **System Settings → Privacy & Security**, scroll to the message about MacIR and click **Open Anyway**. Or run this in Terminal:
   ```sh
   xattr -dr com.apple.quarantine /Applications/MacIR.app
   ```
3. Plug in the blaster. Its status appears at the bottom of the sidebar.

### Known limitations
- This is the first release. It hasn't yet been tested with physical ElkSmart hardware, so please open an issue with your blaster model if sending or learning doesn't work.
- If macOS attaches its own driver to the blaster, MacIR talks to it through the serial port that driver creates. Learning on the older EKX4S is known to be unreliable, so retry if a capture fails.
