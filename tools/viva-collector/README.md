# Viva Linux Collector

Read-only, root-aware Android diagnostic collector for the **Xiaomi Redmi Note 11 Pro 4G (`viva`, MT6781)** native-Linux project.

## Profiles
- `00_core`: Android/kernel/mount/block identity
- `10_branch_a`: DT/UFS/HAL/sensor/camera/firmware inventory
- `20_branch_b`: AVB/slot/boot partitions/fstab/init/modules/config/dmesg
- `30_branch_c`: Wi-Fi/WMT/interfaces/routes/connectivity/SSH listeners
- `40_boot_hashes`: read-only SHA-256 of selected boot-chain partitions only

## Safety boundary
No flash, erase, partition write, `setprop`, RW remount, slot change, vbmeta modification, filesystem creation, or reboot action is present.

The collector does not intentionally read saved Wi-Fi passwords, account tokens, app-private data, NVRAM/NVDATA/persist/efuse contents, or SSH private keys. Output redaction masks common device identifiers, MAC addresses, SSID/PSK/password material.

## Output
Android 10+:
`Downloads/VivaLinuxCollector/VIVA_COLLECT_<UTC>.zip`

Each bundle includes `manifest.json`, per-command exit/timeout/truncation metadata, and SHA-256 hashes.
