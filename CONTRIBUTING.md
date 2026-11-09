# Contributing

## Build and check

Needs JDK 21 and Android SDK 37. Set `ANDROID_HOME` or `sdk.dir` in `local.properties`.

```sh
./build.sh --lint
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am force-stop com.instagram.android
```

Runtime testing needs a rooted phone with an Xposed API 82 framework, Android 13+, and Instagram **454.0.0.54.77 (386014705)**. Enable `dev.dimka.igglass` for `com.instagram.android` only.

Module logs:

```sh
adb shell su -c 'grep -h IgGlass /data/adb/lspd/log/modules_*.log | tail -50'
```

## Device verification

Compilation and lint do not verify injected Compose rendering or touch dispatch. Before calling a build device-tested, check:

- Cold launch, warm launch, login, account switching, and activity recreation.
- Every tab, reselection of the current tab, and selection changes from page swipes.
- Dragging the selected lens, cancelling a drag, moving vertically, and multi-touch cancellation.
- Profile/account long press and any other native tab long presses; opening Create without changing the selected tab.
- Avatars, notification counts, and badge animations.
- Instagram light/dark theme, RTL, rotation, gesture navigation, three-button navigation, and keyboard visibility.
- Scrolling photos and playing Reels behind the dock; verify performance and any separate-surface limitations.
- Bar hide/show animations, camera/fullscreen screens, and layouts with different tab sets.
- Disabling the module restores Instagram; an unsupported version logs a skip and receives no UI changes.

Record the phone, Android version, Instagram version, and framework with screenshots and module logs.

## Implementation rules

- Prefer resource names, platform types, and member signatures over obfuscated `X.*` names. Record evidence from the APK when changing a target; see [reference notes](docs/instagram-reference.md).
- Guard the package, process, version, and layout before changing views.
- Keep every mutation reversible. A failed installation must release view registrations/compositions and restore stock backgrounds, margins, visibility, and padding.
- Never capture the glass view or its ancestors into its own backdrop node.
- Preserve native controls and accessibility. Keep normal taps and long presses in Instagram's dispatch path.
- Preserve native background objects: Instagram reads their colors when changing tab bar styles. Use reversible drawable alpha instead of replacing them. Avoid hooks on hot drawable draw methods: these caused native ART/JIT crashes with Vector on the test phone.
- `catalog/` is adapted from Kyant0/AndroidLiquidGlass under Apache-2.0; preserve its license file.

Keep APKs, decompiled app sources, SDKs, and machine-specific paths out of version control.
