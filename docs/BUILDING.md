# Building OpenCode for Termux

This document describes how to build the APK of this fork from source.

## Requirements

| Tool | Version | Notes |
| --- | --- | --- |
| JDK | 17 or newer | The build uses the toolchain of Gradle 8.x. |
| Android SDK | Platform 36, Build-Tools 36 | `compileSdkVersion=36` and `targetSdkVersion=28` are defined in `gradle.properties`. |
| Android NDK | `29.0.14206865` | Needed for the `libtermux-exec` native library. The version is pinned in `gradle.properties` and must match exactly. |
| Gradle | Provided by the wrapper | Do not install Gradle manually, use `./gradlew`. |

The minimum Android version is Android 5.0 (`minSdkVersion=21`).

The Termux bootstrap (`bootstrap*.zip`) is downloaded by the build from
`https://github.com/termux/termux-packages/releases` — no extra setup is needed.

## Setup

```bash
git clone https://github.com/manhduonghn/opencode-termux.git
cd opencode-termux

export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# Accept the licenses and install the platform/NDK if needed.
yes | sdkmanager --licenses
sdkmanager "platforms;android-36" "ndk;29.0.14206865"
```

The NDK version is read from `gradle.properties`:

```properties
ndkVersion=29.0.14206865
```

If your NDK has a different version, change it there — but keep in mind that the native
library is built with that toolchain.

## Building

```bash
# Debug build, all ABIs in one APK.
./gradlew assembleDebug

# Release build (unsigned, uses the debug signing config unless one is configured).
./gradlew assembleRelease
```

The APKs are written to:

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

### ABI splits

The build can produce one APK per ABI:

```bash
./gradlew assembleRelease \
  -PsplitApksForReleaseBuilds=1
```

Supported ABIs: `arm64-v8a`, `x86_64`, `armeabi-v7a`, `x86`.

### Building a single ABI

With `TERMUX_BUILD_ABIS` only the given ABIs are compiled. The split APKs are then disabled,
only the bootstrap of the needed architecture is downloaded and the native library is only built
for that ABI — the build is roughly four times faster:

```bash
TERMUX_BUILD_ABIS=arm64-v8a ./gradlew assembleDebug
```

The resulting APK is a single file that only contains the libraries of that ABI:

```
app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk
```

This is what the `Build arm64-v8a APK` workflow of this repository does on every push.

> OpenCode itself is only published for `arm64` and `x64`. On a device with another ABI the
> installer reports *"The CPU architecture … has no official OpenCode build"*, but the rest of
> Termux works as usual.

## Changing the application name

The application name is a manifest placeholder, so it can be changed without touching the
package name:

```bash
TERMUX_APP_NAME="OpenCode Termux" ./gradlew assembleRelease
```

The default is `OpenCode Termux`. The package name stays `com.termux`, because the Termux
plugins (Termux:API, Termux:Boot, …) and the bootstrap require it.

## Signing

`app/build.gradle` contains three signing configurations:

| Configuration | Key |
| --- | --- |
| `debug` | The standard Android debug keystore. |
| `release` | `keystore/release.keystore` — **not** part of this repository. |
| `upload` | `keystore/upload.keystore` — used for the F-Droid/Play builds. |

To build a signed release APK, create the keystore and fill in the properties:

```bash
keytool -genkeypair -v -keystore keystore/release.keystore \
  -alias termux -keyalg RSA -keysize 2048 -validity 10000
```

```properties
# keystore/release.properties
storeFile=release.keystore
storePassword=...
keyAlias=termux
keyPassword=...
```

The build fails with a clear message if the keystore is missing — this is intentional, so that
nobody accidentally publishes an APK signed with a wrong key.

## The OpenCode bootstrapper

The whole OpenCode installation lives in a single class:

```
app/src/main/java/com/termux/app/OpenCodeBootstrap.java
```

It is called from `TermuxActivity`:

| Place | What happens |
| --- | --- |
| `onServiceConnected()` | `OpenCodeBootstrap.setupIfNeeded()` — installs the runtime if it is missing, then starts the TUI. |
| `onResume()` | `OpenCodeBootstrap.checkRuntime()` — repairs the binary if the OpenCode auto updater replaced it. |
| `onNewIntent()` | Handles the `com.termux.opencode.FORCE_UPDATE` extra sent by `$PREFIX/bin/oc-install`. |
| Context menu | *Restart OpenCode*, *Toggle the OpenCode input bar*, *Install/Update OpenCode*. |

The floating input bar is `app/src/main/java/com/termux/app/terminal/FloatingPromptInput.java`
with the layout `app/src/main/res/layout/view_opencode_prompt.xml`.

### Debugging the installer

```bash
# Full log of every installation run.
cat ~/.opencode/setup.log

# Report of the last failure.
cat ~/.opencode/last-error.txt

# Snapshot of the environment (ABI, sources.list, loader, …).
cat ~/.opencode/diagnostics.txt

# The logcat of the app.
adb logcat -s OpenCodeBootstrap
```

The installer never calls `pkg` (it would change the mirror of the user) and never writes to the
`sources.list` of the user — when the configuration of the user is broken it uses its own
temporary `sources.list` in `~/.opencode/apt/`.

## Continuous integration

The workflow `.github/workflows/build-arm64-v8a.yml` builds the `arm64-v8a` debug APK on every
push and pull request and attaches it to the run as an artifact
(`opencode-termux-arm64-v8a-debug`).

Download it from the [Actions tab](https://github.com/manhduonghn/opencode-termux/actions) of the
repository, or with the GitHub CLI:

```bash
gh run list --repo manhduonghn/opencode-termux
gh run download --repo manhduonghn/opencode-termux
```

> **Note** — pushing a file to `.github/workflows/` needs a token with the `workflow` scope. The
> token of the maintainer did not have it, so the workflows of the Termux project were removed
> from this fork. To enable the CI again, refresh the token with:
>
> ```bash
> gh auth refresh -h github.com -s workflow
> ```

## License

GPL-3.0, see [`../LICENSE.md`](../LICENSE.md). The Termux sources are the work of the
[Termux project](https://github.com/termux/termux-app) and its contributors.
