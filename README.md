# Flick

Turn a Galaxy Watch 8 (or any Wear OS watch with NFC) into a programmable NFC tag.
Pick a preset on the watch, hold the watch face to a phone, and the phone opens the
link: your website, a YouTube video, a rickroll, anything.

- **`app/`**: the Wear OS app. Uses Host Card Emulation to act as an NFC Forum
  Type 4 tag holding one NDEF message (a URI or a text record). Category `other`
  with the standard NDEF AID, so it never interferes with Wallet payments.
  Turn beaming off and the watch stops answering as a tag at all.
- **`server/`**: a tiny stdlib-Python preset editor on the Latitude. Open it in any
  browser, paste links, rename, reorder; it autosaves. The watch syncs from it and
  caches presets, so beaming works with no network.

## How a phone sees it

Android: unlocked phone opens the link right away (YouTube links open the YouTube app).
iPhone XS and newer: a banner appears; tap it. Hold the *watch face* flat against the
phone's NFC spot (Android: middle of the back; iPhone: top edge). The watch buzzes
twice when the phone has read it. The watch screen needs to be on.

## Server (Latitude)

```bash
# server/flick.env (gitignored, mode 600): FLICK_KEY=<random 32+ chars>, PORT=8480
ssh latitude 'mkdir -p ~/flick ~/.config/systemd/user'
scp server/flick.py server/editor.html server/flick.env latitude:~/flick/
scp server/systemd/flick.service latitude:~/.config/systemd/user/
ssh latitude 'systemctl --user daemon-reload && systemctl --user enable --now flick'
ssh latitude 'sudo ufw allow from 192.168.1.0/24 to any port 8480 proto tcp'
```

Editor: `http://<latitude>:8480/#k=<FLICK_KEY>` once; the browser remembers the key,
so bookmark the plain `http://<latitude>:8480/` afterwards.

## Watch

```bash
cp flick.properties.example flick.properties   # fill in server.key = FLICK_KEY
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package compile -m speed -f com.rusty.flick
```

## License

AGPL-3.0-or-later.
