# Changelog

All notable changes to the BaluHost Android app will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Changed

- CI/CD rework: `ci.yml` now runs on every push to `development` (in addition
  to PRs to `main`) with auto-merge gated on the event type, a `concurrency`
  group to cancel superseded runs, and scoped-down `permissions` reasoning.
  `release.yml` now builds from tags instead of pushes to `main`, deriving
  `versionName`/`versionCode` from the tag and gating the build on tests
  passing, the tag being reachable from `main`, and a matching `CHANGELOG.md`
  section. Hardened the tag regex to reject leading-zero components and
  `v0.0.0`, isolated the signed release build from the shared Gradle build
  cache, added a post-build check that the APK's `versionName` matches the
  tag, made the GitHub release publish as a draft-then-publish so a failed
  asset upload can't strand a public, empty release, and made the `main`
  fetch's refspec explicit.

## [1.0.0] - 2026-07-31

### Added

- Device pairing with a BaluHost server via QR code, including WireGuard VPN import
- File browser with upload, download, move and delete, plus an offline queue for
  actions taken without a connection
- Folder sync with schedules, and a sync status view
- Shares: create, list and manage server shares from the app
- Dashboard with system telemetry, RAID and S.M.A.R.T. status, energy figures and
  server uptime
- Push and in-app notifications with quiet hours and per-category preferences
- Power control: soft sleep, suspend, wake, and Wake-on-LAN via the Fritz!Box
- Display toggle — turn the server's displays off to drop the GPU to idle, and
  back on, including an optional session unlock
- Gaming mode — displays on plus Steam Big Picture, contributed by the server's
  bundled `steam_gaming` plugin
- Always-awake override with 1h / 4h / 8h presets, a free expiry and a permanent
  mode, admin only
- App lock with PIN and biometric unlock, and an automatic lock timeout
