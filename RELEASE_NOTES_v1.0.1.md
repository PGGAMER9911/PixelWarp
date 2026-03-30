# PixelWarp v1.0.1 Release Notes

## Previous Tag for Generated Notes

Use previous tag: `v1.0.0`

If your older release/tag name is different, select that one in GitHub release UI.

## Release Description

PixelWarp `v1.0.1` focuses on stability, safer storage behavior, better runtime operations, and improved documentation.

### Highlights

- Added dual storage architecture with FILE and MySQL provider support.
- Added FILE mode hardening with encryption support, compression toggle, and backup restore flow.
- Added read-only failsafe protections for unsafe storage states.
- Added runtime reload safety controls and cooldown guard logic.
- Added shared create/delete cooldown config for warps.
- Added admin utilities: debug report, backup command, scoped reload command.
- Upgraded particle system to modular pattern-based engine.
- Improved migration safety by skipping duplicates instead of overwriting.
- Refreshed README and GUIDE with cleaner structure and visual diagrams.

### Added

- `FileStorageProvider` and `WarpStorageProvider` abstraction.
- `ReloadManager` with guarded runtime reload flow.
- `ConfigValidator` for startup and reload config safety.
- `AdminAccessManager` for global admin handling.
- Modular particle patterns: ring, spiral, pulse.
- New config key:
  - `warp.create-delete-cooldown-seconds` (default `5`)

### Changed

- Version updated to `1.0.1`.
- Storage migration now preserves existing target data and logs duplicate skips.
- Access persistence integrated for file-backed mode.
- Backup flow uses explicit confirmation command.

### Fixed

- Improved cache and persistence consistency behavior around write failures.
- Better handling for key mismatch/decryption failure fallback paths.
- Cleanup of command/diagnostic edge cases discovered during stabilization.

### Upgrade Notes

- Validate `file.secret-key` length when encryption is enabled (must be 16, 24, or 32 bytes).
- Review `storage.type` and `storage.last-type` after upgrading.
- Set `warp.create-delete-cooldown-seconds` as per your server preference.
- Keep a backup before switching between storage modes.

### Build Artifact

- `PixelWarp-1.0.1.jar`

---

Maintainer:
- Username: `PGGAMER9911`
- Email: `gamitparth04@gmail.com`
- Repository: https://github.com/PGGAMER9911/PixelWarp.git
