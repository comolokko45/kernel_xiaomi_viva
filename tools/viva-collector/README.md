# Viva Linux Collector v0.2

Read-only Android diagnostic collector for the **Xiaomi Redmi Note 11 Pro 4G (`viva`, MT6781)** native-Linux project.

## Execution modes
- **ROOT**: uses `su -c` for the widest read-only evidence set.
- **NON_ROOT**: automatically falls back to `sh -c` when root/SU is unavailable. Restricted items may be empty or permission-denied, but the rest of the bundle is still produced.

## Profiles
- `00_core`: Android/kernel/mount/block identity + boot-reason properties
- `10_branch_a`: DT/UFS/HAL/sensor/camera/firmware inventory
- `20_branch_b`: AVB, detailed A/B slot state, boot partitions, pstore/last_kmsg, fstab, init, modules, config, dmesg
- `30_branch_c`: Wi-Fi/WMT interfaces, wlan0 driver/modalias/module links, focused MTK dmesg, routing, connectivity, SSH listeners
- `40_boot_hashes`: read-only SHA-256 of selected boot-chain partitions

## v0.2 additions
- `ro.boot.bootreason`, `sys.boot.reason`, pstore and `/proc/last_kmsg`
- read-only `bootctl` slot count/current/bootable/successful checks
- focused `dmesg` for WMT/WLAN/Wi-Fi/CONSYS/MT6631/firmware
- `/sys/class/net/wlan0/device` driver/module/modalias/uevent evidence
- non-root automatic fallback
- raw serial is never exported; when readable, only its SHA-256 digest is placed in `manifest.json`

## Safety boundary
No flash, erase, partition write, `setprop`, RW remount, slot change, vbmeta modification, filesystem creation, or reboot action is present.

The collector does not intentionally read saved Wi-Fi passwords, account tokens, app-private data, NVRAM/NVDATA/persist/efuse contents, or SSH private keys. Output redaction masks common device identifiers, MAC addresses, SSID/PSK/password material.

## Output
Android 10+:
`Downloads/VivaLinuxCollector/VIVA_COLLECT_<UTC>.zip`

Each bundle includes `manifest.json`, execution mode, per-command exit/timeout/truncation metadata, and SHA-256 hashes.
