# Instagram reference for the port

The supplied APKMirror bundle identifies:

| Field | Value |
| --- | --- |
| Package | `com.instagram.android` |
| Version | `454.0.0.54.77` |
| Version code | `386014705` |
| ABI | `arm64-v8a` |
| Densities | mdpi and xxhdpi |
| App minimum / target SDK | 28 / 37 |

The module requires API 33 for its rendering stack. The app's lower minimum SDK does not change the module's requirement.

SHA-256 of the supplied APKM: `bab7df66e9fb99d6fcdbb9f0a716cae5d596bbcc9d7a7584b0c6515636837c04`.

SHA-256 of its extracted `base.apk`: `3a195be93636eee59a1d5bd55852ee2966e9aa49227af796b1950b1df235834f`.

## Verified integration points

These names and IDs come from this bundle's base APK resource table. The implementation resolves names at runtime.

| Resource | Reference ID | Purpose |
| --- | --- | --- |
| `id/tab_bar` | `0x7f0b404e` | Native horizontal main navigation |
| `id/tab_bar_shadow` | `0x7f0b404f` | Stock separator |
| `id/floating_tab_bar_nav_bar_gradient` | `0x7f0b1875` | Optional stock navigation gradient |
| `id/layout_container_main` | `0x7f0b228f` | Content with a bottom navigation margin |
| `id/layout_container_main_wrapper` | `0x7f0b2292` | Shared `IgFrameLayout` parent of content and tabs |
| `id/swipeable_tab_view_pager` | `0x7f0b402e` | Alternate content view inflated through a stub |
| `attr/igds_color_primary_background` | `0x7f0407dd` | Instagram theme background |

`com.instagram.mainactivity.InstagramMainActivity` is present in `classes2.dex`. Its tab creation code adds native button views directly to the active bar, tags them with native tab models, and installs Instagram's click, long-click, and touch listeners. The native tab implementation (`X.02aK` in this build) reads selection from the button view's `isSelected`, and changes it through `setSelected`. The module uses that view state and `performClick`, without calling obfuscated tab controller methods.

Both `layout_activity_main_internal` and `layout_activity_main_internal_viewpager2` place the horizontal `TouchInterceptorLinearLayout` named `tab_bar` under `layout_container_main_wrapper`, an `IgFrameLayout`. The bar's children use weighted native tab layouts. The module validates this structure and the tab count before installing.

The reference uses Meta's packed layouts: some resource values are strings such as `L|484C34|16D8|42D5`, rather than ordinary XML paths. `assets/layouts.bin.spo` contains an XZ stream starting at offset 131 in this artifact. Decompressing it produces `layouts.bin`; the first two hexadecimal fields give the binary XML offset and size. This was used to inspect the actual `layout_activity_main_internal`, `tab_bar`, and `tab_button` layouts, in addition to the ordinary viewpager2 XML. JADX alone does not decode those packed layouts as normal resources.

The native bar utility (`X.02bT`) reads the current background's color before changing bar styles. Its floating background (`X.03gQ`) is an inset drawable with separate fill and shadow paints. The module keeps the original drawable attached and hides its fill with reversible alpha changes, preserving RGB color reads. An optional color accessor in `X.02bT` is matched by its static `(View) -> Integer` signature to follow per-screen bar colors; failure to bind it falls back to the activity theme. No obfuscated tab controller methods are called.

## Validation boundary

The supplied bundle was inspected locally with JADX and Android SDK `aapt2`. Kotlin compilation, Android lint, and APK packaging are build checks. Android 14 / Vector checks confirmed the floating dock and raised Litho metadata/action controls. The crash buffer showed no further native crash after removing background draw hooks. The final native timeline binding was installed and logged successfully, but its final appearance, scrubbing behavior, and broader device/performance checks remain incomplete.

Do not add the reference APKM, extracted APKs, or decompiled Instagram code to this repository.

## Reels controls and iOS reference

On the connected phone, build **454.0.0.54.77 (386014758)** uses the same named navigation resources. Its packed `layout_clips_viewer_media_info` is a full-height overlay named `clips_viewer_media_info_container` containing the username/follow header, caption, attribution, and `ufi_stack_container`. Reducing this overlay's bottom boundary lifts these controls together without moving the video.

`clips_attached_scrubber_container` holds a native `com.instagram.ui.mediaactions.VideoScrubberSeekBar` named `scrubber`, plus thumbnail previews, timestamps, and `scrubber_hairline`. Only that native SeekBar's drawables, padding, and minimum height are restyled; native playback position, touch handling, preview callbacks, and accessibility remain attached. Both overlays reserve space from the floating dock's actual window position. The connected phone actually selects the Litho variant: `clips_media_component` contains sibling `clips_media_info_component` and `clips_ufi_component` hosts. These use reversible translations because Litho owns their layout bounds. Its native `scrubber` sits in a separate unnamed host below the same media root; that host moves together with its previews. Recycled views and hidden navigation restore their original values.

Apple's [WWDC25 UIKit session, Controls chapter](https://developer.apple.com/videos/play/wwdc2025/284/) describes thumbless sliders during passive media playback and a Liquid Glass thumb during interaction. The Android adaptation uses a slim rounded track at rest, a thicker track and translucent highlighted thumb while pressed/focused, and horizontal insets. This is an adaptation of Apple's published behavior, not a claim that every Instagram iOS account has an identical Reels layout.

## Native crash investigation (2026-11-09)

The connected phone's tombstone reports SIGSEGV in ART `DexCache::SetResolvedType` on a JIT thread. The nearby generated Vector bridge dex contains a hooked `draw` method; the referenced cache address is already occupied by a read-only library mapping. Retaining draw bridges did not resolve the crash on this device. Background suppression now uses drawable alpha, including wrapped/layered drawables, and installs **no draw hooks**. Native background objects and RGB color values remain available to Instagram. Cleanup restores the original drawable alpha values. Touch bridges remain registered for the process lifetime, with empty view registries after cleanup.
