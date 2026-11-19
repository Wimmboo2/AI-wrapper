# Build Omni Android APK on Google Colab

Use a **shell** cell (`%%bash` or lines starting with `!`), not `%cd ... && ...`. In Colab, `%cd` only changes directory; it does not run `&&` chains.

Assume the repo is at `/content/AI-wrapper` (clone or upload as you already do).

## One-shot build (debug APK + Omni icon)

```bash
%%bash
set -euo pipefail
REPO=/content/AI-wrapper
cd "$REPO"

# Colab often has local commits; prefer matching GitHub Cursor exactly
git fetch origin Cursor
git checkout Cursor
git reset --hard origin/Cursor

cd mobile-app
chmod +x scripts/*.sh

cd app
# First run on a fresh runtime: install dx 0.7.10 + Android SDK/NDK (your existing setup)
dx bundle --platform android --arch arm64

cd ..
# Match the folder dx created: debug or release (see target/dx/omni-mobile/)
PROFILE=debug
if [[ -d target/dx/omni-mobile/release/android ]] || [[ -d app/target/dx/omni-mobile/release/android ]]; then
  PROFILE=release
fi
./scripts/patch-android-launcher.sh "$PROFILE"

GRADLE_ROOT=$(find . app -path "*/$PROFILE/android/app/gradlew" 2>/dev/null | head -1 | xargs dirname)
cd "$GRADLE_ROOT"
./gradlew assembleDebug

APK=$(find . -path "*/apk/debug/app-debug.apk" | head -1)
cp "$APK" /content/Omni-mobile-debug-with-icon.apk
ls -lh /content/Omni-mobile-debug-with-icon.apk
sha256sum /content/Omni-mobile-debug-with-icon.apk
```

Upload `/content/Omni-mobile-debug-with-icon.apk` to Drive.

## Quick sanity check before upload

```bash
!unzip -l /content/Omni-mobile-debug-with-icon.apk | grep mipmap
```

You want **`ic_launcher.png`** in the mipmap folders (from the patch script). If you still see only **`.webp`** and `mipmap-anydpi-v26/ic_launcher.xml`, the patch step did not run.

## Git pull errors in Colab

`fatal: Need to specify how to reconcile divergent branches` means the notebook repo has commits that are not on GitHub. On a throwaway Colab VM this is normal:

```bash
!cd /content/AI-wrapper && git fetch origin Cursor && git checkout Cursor && git reset --hard origin/Cursor
```

That discards local Colab-only changes and matches `origin/Cursor` (including `scripts/patch-android-launcher.sh` and `assets/android-res/`).

## `gradlew: Permission denied`

```bash
!chmod +x /content/AI-wrapper/mobile-app/**/android/app/gradlew
```

## Where the APK lands

Typical path after `assembleDebug`:

`mobile-app/target/dx/omni-mobile/<debug|release>/android/app/app/build/outputs/apk/debug/app-debug.apk`

(or the same under `mobile-app/app/target/dx/...` if you run `dx` only from `app/`).