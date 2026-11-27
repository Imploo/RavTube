<img width="96" src="smarttubetv/src/ststable/res/mipmap-nodpi/app_icon.png" alt="RavTube logo"/>

# RavTube

RavTube is a personal fork of [SmartTube](https://github.com/yuliskov/SmartTube) by yuliskov, an open-source media client for Android TV. It keeps everything SmartTube does and adds a new look that matches [RavLauncher](https://github.com/Imploo/RavLauncher):

- Overview in the RavLauncher style, with section buttons on top and 5 poster columns
- New player, channel pages and comments (long comments scroll step by step)
- More sections published as Android TV home screen channels
- Its own name, logo and package (`app.ravtube.stable`), so it can sit next to SmartTube

## Install

Download the APK for your device from the [latest release](https://github.com/Imploo/RavTube/releases/latest) (`armeabi-v7a` for most TVs, `universal` if unsure) and sideload it. For devices, sideloading methods and troubleshooting, follow SmartTube's [installation guide](https://github.com/yuliskov/SmartTube#installation). Its device notes apply to RavTube too.

After that, the built-in updater (_Settings > About_) takes updates from RavTube's own releases.

## How it stays up to date

- [`ravtube-upstream-sync.yml`](.github/workflows/ravtube-upstream-sync.yml) merges upstream SmartTube into `master` every night. When the merge conflicts, it opens a PR with the conflicts to resolve by hand.
- [`ravtube-release.yml`](.github/workflows/ravtube-release.yml) builds and signs every push to `master` and publishes a GitHub release with the APKs and the update manifest the app reads.
- Versions are `<SmartTube version>-r<commit count>` (see [`ravtube.gradle`](smarttubetv/ravtube.gradle)), so every build counts as an update.

## Build

```sh
git clone --recursive https://github.com/Imploo/RavTube.git
cd RavTube
./gradlew assembleStstableDebug   # JDK 17
```

Signing uses `keystore.properties` in the repo root when present (see `smarttubetv/build.gradle`). It's git-ignored.

## Credits and license

All the heavy lifting is SmartTube's: see the [upstream repo](https://github.com/yuliskov/SmartTube) for features, FAQ, contributors and donations. MIT licensed, see [LICENSE](LICENSE).
