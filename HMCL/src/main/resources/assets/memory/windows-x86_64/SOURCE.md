# Source and maintenance boundary

This is an independently built BF656 component derived from Mem Reduct, not an official Henry++ release or the full Mem Reduct GUI.

- Upstream: https://github.com/henrypp/memreduct
- Reviewed source commit: `04402de8b6a8ff9d1c5517c675d87c346655323c`.
- Source operations: `src/main.c` (`_app_memoryclean`, `_app_flushvolumecache`) and `src/main.h` (cleanup masks).
- Native structure layouts were checked against the public `routine` SDK, commit `1acd9394395b7cee9e1ea7b985fb54e76be86b11`, `src/ntapi.h`.
- Upstream copyright: Henry++, 2011–2026. Adaptation: BF656 contributors.
- License: GPL-3.0; see LICENSE. Modified source is included in the component source package.

The Native API information classes, commands and default/full operation selections follow the reviewed upstream implementation. Documented Win32 APIs replace the GUI SDK's privilege and volume-enumeration helpers. Volume-cache failures are aggregated instead of being silently discarded.

The `safe` profile is an additional restricted selection: working sets, system file cache and low-priority standby pages. `default` retains the reviewed upstream mask, including registry reconciliation, memory combination and volume-cache flushing. `full` additionally requests standby-list purge and modified-page flushing.

The original GUI source remains unchanged. Its public SDK incompatibilities are not declared fixed. The independent target compiles only the necessary cleanup adaptation and uses MSVC / Windows SDK directly, without routine, GUI, localization, background schedules or self-update code.

Upstream changes must be reviewed against cleaner.c and the recorded masks/layouts before producing a new component version. The protocol version and component version are separate from the upstream GUI version.
