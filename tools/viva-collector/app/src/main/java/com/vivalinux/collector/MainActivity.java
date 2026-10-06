package com.vivalinux.collector;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {
    private TextView status;
    private volatile boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Viva Linux Collector v0.1\nREAD-ONLY / ROOT-AWARE");
        title.setTextSize(22);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("Target: Xiaomi Redmi Note 11 Pro 4G (viva / MT6781). " +
                "A=ROM+hardware, B=boot+AVB+kernel, C=Wi-Fi+SSH. " +
                "No flash/erase/mount-rw/setprop/reboot commands exist in this build.");
        desc.setPadding(0, dp(8), 0, dp(8));
        root.addView(desc);

        addButton(root, "1) Root kontrol", v -> runRootCheck());
        addButton(root, "2) CORE topla", v -> collect(Arrays.asList(Profile.CORE)));
        addButton(root, "3) Branch A verileri", v -> collect(Arrays.asList(Profile.CORE, Profile.A)));
        addButton(root, "4) Branch B verileri", v -> collect(Arrays.asList(Profile.CORE, Profile.B)));
        addButton(root, "5) Branch C verileri", v -> collect(Arrays.asList(Profile.CORE, Profile.C)));
        addButton(root, "6) TÜMÜNÜ TOPLA (CORE+A+B+C)", v -> collect(Arrays.asList(Profile.CORE, Profile.A, Profile.B, Profile.C)));
        addButton(root, "7) Boot-chain SHA-256 (salt-okunur)", v -> collect(Arrays.asList(Profile.CORE, Profile.HASHES)));

        status = new TextView(this);
        status.setText("Hazır. Root kontrolüyle başlayın.");
        status.setTextIsSelectable(true);
        status.setMovementMethod(new ScrollingMovementMethod());
        status.setPadding(0, dp(12), 0, dp(32));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(status);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void addButton(LinearLayout root, String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        root.addView(b);
    }

    private int dp(int x) {
        return (int) (x * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void log(String s) {
        runOnUiThread(() -> status.append("\n" + s));
    }

    private void setStatus(String s) {
        runOnUiThread(() -> status.setText(s));
    }

    private void runRootCheck() {
        if (busy) return;
        busy = true;
        setStatus("Root kontrol ediliyor...");
        new Thread(() -> {
            CommandResult r = RootShell.run("id; echo SELINUX=$(getenforce 2>/dev/null || echo unknown)", 10, 128 * 1024);
            log("exit=" + r.exitCode + "\n" + Redactor.clean(r.output));
            log(r.exitCode == 0 && r.output.contains("uid=0") ? "ROOT: OK" : "ROOT: YOK / su izni verilmedi");
            busy = false;
        }).start();
    }

    private void collect(List<Profile> profiles) {
        if (busy) return;
        busy = true;
        setStatus("Toplama başlatıldı: " + profiles + "\nRoot isteği gelirse ONAYLAYIN.");

        new Thread(() -> {
            File workDir = null;
            try {
                CommandResult rootCheck = RootShell.run("id", 10, 64 * 1024);
                boolean rooted = rootCheck.exitCode == 0 && rootCheck.output.contains("uid=0");
                log("Root: " + (rooted ? "OK" : "YOK"));

                String stamp = utcStamp();
                workDir = new File(getCacheDir(), "VIVA_COLLECT_" + stamp);
                if (!workDir.mkdirs() && !workDir.isDirectory()) throw new Exception("workdir oluşturulamadı");

                JSONObject manifest = new JSONObject();
                manifest.put("schema", "viva-linux-collector/1");
                manifest.put("collector_version", "0.1.0");
                manifest.put("created_utc", isoUtc());
                manifest.put("root_available", rooted);
                manifest.put("read_only_policy", true);
                manifest.put("target_device", "Xiaomi Redmi Note 11 Pro 4G");
                manifest.put("target_codename", "viva");
                manifest.put("target_soc", "MediaTek MT6781 / Helio G96");
                manifest.put("expected_physical_baseline", "viva_tr / OS1.0.1.0.TGDTRXM / Android 13 package");
                manifest.put("expected_stock_kernel_baseline", "4.14.186-perf-g565ad7461fab");
                manifest.put("expected_wifi_reference", "MT6631 / CONSYS_6781 / wlan0 / gen4m");

                JSONArray profileArray = new JSONArray();
                for (Profile pp : profiles) profileArray.put(pp.name());
                manifest.put("profiles", profileArray);

                JSONArray records = new JSONArray();
                for (Profile p : profiles) {
                    File profileDir = new File(workDir, p.dir);
                    profileDir.mkdirs();
                    log("== " + p + " ==");
                    for (CollectorItem item : CollectorCatalog.items(p)) {
                        log("• " + item.name);
                        CommandResult r = RootShell.run(item.command, item.timeoutSeconds, item.maxBytes);
                        String body = "# name: " + item.name + "\n" +
                                "# command_id: " + item.id + "\n" +
                                "# root: " + rooted + "\n" +
                                "# exit_code: " + r.exitCode + "\n" +
                                "# timed_out: " + r.timedOut + "\n" +
                                "# truncated: " + r.truncated + "\n\n" +
                                Redactor.clean(r.output);
                        File out = new File(profileDir, item.id + ".txt");
                        writeUtf8(out, body);

                        JSONObject rec = new JSONObject();
                        rec.put("profile", p.name());
                        rec.put("id", item.id);
                        rec.put("name", item.name);
                        rec.put("exit_code", r.exitCode);
                        rec.put("timed_out", r.timedOut);
                        rec.put("truncated", r.truncated);
                        rec.put("sha256", sha256(out));
                        rec.put("bytes", out.length());
                        records.put(rec);
                    }
                }

                manifest.put("runtime_summary", RuntimeProbe.summarize());
                manifest.put("records", records);
                writeUtf8(new File(workDir, "manifest.json"), manifest.toString(2));

                File zip = new File(getCacheDir(), "VIVA_COLLECT_" + stamp + ".zip");
                zipDirectory(workDir, zip);
                String exported = exportToDownloads(zip);

                log("\nTAMAMLANDI");
                log("ZIP SHA-256: " + sha256(zip));
                log("Kayıt: " + exported);
                log("Bu ZIP'i A/B/C analizine doğrudan verebilirsiniz.");
            } catch (Throwable t) {
                log("HATA: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
                if (workDir != null) deleteTree(workDir);
                busy = false;
            }
        }).start();
    }

    private String exportToDownloads(File zip) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, zip.getName());
            v.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VivaLinuxCollector");
            ContentResolver resolver = getContentResolver();
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("Downloads URI oluşturulamadı");
            try (InputStream in = new FileInputStream(zip); OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new Exception("Downloads output açılamadı");
                copy(in, out);
            }
            return "Downloads/VivaLinuxCollector/" + zip.getName();
        }

        File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (dir == null) dir = getFilesDir();
        File out = new File(dir, zip.getName());
        try (InputStream in = new FileInputStream(zip); OutputStream os = new FileOutputStream(out)) {
            copy(in, os);
        }
        return out.getAbsolutePath();
    }

    private static void writeUtf8(File f, String s) throws Exception {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(f))) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void zipDirectory(File base, File outFile) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outFile)))) {
            zipRec(base, base, zos);
        }
    }

    private static void zipRec(File base, File f, ZipOutputStream zos) throws Exception {
        File[] children = f.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory()) {
                zipRec(base, c, zos);
            } else {
                String rel = base.toURI().relativize(c.toURI()).getPath();
                zos.putNextEntry(new ZipEntry(rel));
                try (InputStream in = new BufferedInputStream(new FileInputStream(c))) {
                    copy(in, zos);
                }
                zos.closeEntry();
            }
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    private static String utcStamp() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static String isoUtc() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static void deleteTree(File f) {
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) for (File c : cs) deleteTree(c);
        }
        f.delete();
    }

    enum Profile {
        CORE("00_core"), A("10_branch_a"), B("20_branch_b"), C("30_branch_c"), HASHES("40_boot_hashes");
        final String dir;
        Profile(String dir) { this.dir = dir; }
    }

    static class CollectorItem {
        final String id, name, command;
        final int timeoutSeconds, maxBytes;

        CollectorItem(String id, String name, String command, int timeoutSeconds, int maxBytes) {
            this.id = id;
            this.name = name;
            this.command = command;
            this.timeoutSeconds = timeoutSeconds;
            this.maxBytes = maxBytes;
        }
    }

    static class CollectorCatalog {
        static List<CollectorItem> items(Profile p) {
            List<CollectorItem> x = new ArrayList<>();

            if (p == Profile.CORE) {
                x.add(i("identity", "Android/device identity", "getprop; echo '--- uname ---'; uname -a; echo '--- id ---'; id; echo '--- selinux ---'; getenforce 2>/dev/null || true", 15, 2));
                x.add(i("cpu_mem", "CPU + RAM", "cat /proc/cpuinfo 2>/dev/null; echo '--- MEM ---'; cat /proc/meminfo 2>/dev/null", 10, 2));
                x.add(i("kernel_cmdline", "Kernel cmdline + bootconfig", "cat /proc/cmdline 2>/dev/null; echo '--- BOOTCONFIG ---'; cat /proc/bootconfig 2>/dev/null || true; echo '--- VERSION ---'; cat /proc/version 2>/dev/null", 10, 2));
                x.add(i("mounts", "Mount/filesystem map", "mount 2>/dev/null; echo '--- proc mounts ---'; cat /proc/mounts 2>/dev/null; echo '--- filesystems ---'; cat /proc/filesystems 2>/dev/null; echo '--- df ---'; df -hT 2>/dev/null || df -h 2>/dev/null", 15, 4));
                x.add(i("block_map", "Block + partition map", "cat /proc/partitions 2>/dev/null; echo '--- by-name ---'; ls -la /dev/block/by-name 2>/dev/null || true; echo '--- mapper ---'; ls -la /dev/block/mapper 2>/dev/null || true; echo '--- sys block ---'; ls -la /sys/class/block 2>/dev/null", 15, 4));
            } else if (p == Profile.A) {
                x.add(i("device_tree", "DT model/compatible/chosen", "echo model:; tr '\\000' '\\n' </proc/device-tree/model 2>/dev/null || true; echo compatible:; tr '\\000' '\\n' </proc/device-tree/compatible 2>/dev/null || true; echo bootargs:; tr '\\000' '\\n' </proc/device-tree/chosen/bootargs 2>/dev/null || true; echo '--- key nodes ---'; find /proc/device-tree -maxdepth 2 -type d 2>/dev/null | grep -Ei 'wifi|consys|ufs|reserved|chosen|soc' | head -n 500", 20, 2));
                x.add(i("ufs", "UFS runtime metadata", "for p in /sys/block/sd*; do [ -e \"$p\" ] || continue; echo ===$p===; cat \"$p/device/model\" 2>/dev/null || true; cat \"$p/device/rev\" 2>/dev/null || true; cat \"$p/size\" 2>/dev/null || true; done; echo '--- UFS nodes ---'; find /sys/devices -maxdepth 6 -iname '*ufs*' 2>/dev/null | head -n 1000", 25, 4));
                x.add(i("hal", "HAL/VINTF inventory", "lshal 2>/dev/null || true; echo '--- VINTF ---'; find /vendor/etc/vintf /system/etc/vintf /odm/etc/vintf -maxdepth 2 -type f -print 2>/dev/null | sort", 30, 8));
                x.add(i("sensors", "Sensor inventory", "dumpsys sensorservice 2>/dev/null || true", 25, 6));
                x.add(i("camera", "Camera service inventory", "dumpsys media.camera 2>/dev/null || dumpsys media.camera.proxy 2>/dev/null || true", 25, 6));
                x.add(i("firmware_index", "Firmware index relevant to MTK/WCN", "find /vendor/firmware /vendor/etc/firmware /odm/firmware /lib/firmware -maxdepth 3 -type f 2>/dev/null | grep -Ei 'wifi|wlan|wmt|conn|mt66|mt67|scp|sspm|ufs' | sort | head -n 3000", 30, 8));
            } else if (p == Profile.B) {
                x.add(i("verified_boot", "AVB / lock / slot state", "getprop ro.boot.slot_suffix; getprop ro.boot.slot; getprop ro.boot.verifiedbootstate; getprop ro.boot.vbmeta.device_state; getprop ro.boot.flash.locked; getprop ro.boot.veritymode; getprop ro.boot.avb_version; echo '--- avbctl ---'; avbctl get-verity 2>/dev/null || true; avbctl get-verification 2>/dev/null || true; echo '--- bootctl ---'; bootctl get-current-slot 2>/dev/null || true; bootctl hal-info 2>/dev/null || true", 15, 2));
                x.add(i("boot_partitions", "Boot-chain partition symlinks/sizes", "for n in boot_a boot_b vendor_boot_a vendor_boot_b init_boot_a init_boot_b dtbo_a dtbo_b vbmeta_a vbmeta_b vbmeta_system_a vbmeta_system_b vbmeta_vendor_a vbmeta_vendor_b lk_a lk_b; do p=/dev/block/by-name/$n; if [ -e \"$p\" ]; then echo ===$n===; readlink -f \"$p\"; blockdev --getsize64 \"$p\" 2>/dev/null || true; fi; done", 20, 2));
                x.add(i("fstab", "fstab/first-stage mount configs", "for f in /vendor/etc/fstab* /odm/etc/fstab* /system/etc/fstab* /system/system/etc/fstab*; do [ -f \"$f\" ] && { echo ===$f===; cat \"$f\"; }; done", 15, 4));
                x.add(i("init_services", "Init services", "getprop | grep '^\\[init.svc' | sort; echo '--- processes ---'; ps -A -o USER,PID,PPID,NAME,ARGS 2>/dev/null || ps -A 2>/dev/null", 20, 6));
                x.add(i("kernel_modules", "Kernel modules", "cat /proc/modules 2>/dev/null || true; echo '--- module dirs ---'; find /vendor/lib/modules /odm/lib/modules /lib/modules -maxdepth 2 -type f 2>/dev/null | sort | head -n 4000", 25, 8));
                x.add(i("kernel_config", "Kernel config if exposed", "if [ -r /proc/config.gz ]; then zcat /proc/config.gz; elif [ -r /sys/kernel/config ]; then echo configfs-present; else echo CONFIG_UNAVAILABLE; fi", 25, 8));
                x.add(i("dmesg", "Kernel ring buffer", "dmesg 2>/dev/null || true", 25, 8));
            } else if (p == Profile.C) {
                x.add(i("wifi_props", "Wi-Fi/WMT properties", "getprop | grep -Ei 'wifi|wlan|wmt|conn|dhcp|net\\.' | sort", 15, 4));
                x.add(i("wifi_interfaces", "Wi-Fi interfaces/phy", "ip -details link 2>/dev/null || ip link 2>/dev/null; echo '--- addr ---'; ip addr 2>/dev/null; echo '--- iw dev ---'; iw dev 2>/dev/null || true; echo '--- iw phy ---'; iw phy 2>/dev/null || true", 25, 8));
                x.add(i("routing", "IP routes/rules", "ip route show table all 2>/dev/null; echo '--- rules ---'; ip rule 2>/dev/null; echo '--- neigh ---'; ip neigh 2>/dev/null", 15, 4));
                x.add(i("wifi_dumpsys", "Android Wi-Fi service", "dumpsys wifi 2>/dev/null || true", 35, 12));
                x.add(i("connectivity", "Connectivity/netd", "dumpsys connectivity 2>/dev/null || true; echo '--- netd ---'; dumpsys netd 2>/dev/null || true", 35, 12));
                x.add(i("wmt_nodes", "MediaTek WMT/CONSYS nodes", "ls -la /dev 2>/dev/null | grep -Ei 'wmt|stp|wifi|wlan|conn' || true; echo '--- proc modules ---'; cat /proc/modules 2>/dev/null | grep -Ei 'wlan|wifi|wmt|stp|conn|bt' || true; echo '--- sys matches ---'; find /sys -maxdepth 6 2>/dev/null | grep -Ei '/(wlan|wifi|wmt|consys|connectivity)' | head -n 3000", 30, 8));
                x.add(i("ssh_ports", "SSH/listening services", "ss -lntup 2>/dev/null || netstat -lntup 2>/dev/null || true; echo '--- ssh processes ---'; ps -A 2>/dev/null | grep -Ei 'dropbear|sshd' || true; echo '--- init ssh ---'; getprop | grep -Ei 'init.svc.*(dropbear|sshd)' || true", 15, 4));
            } else if (p == Profile.HASHES) {
                x.add(i("boot_sha256", "Critical boot-chain SHA-256", "for n in boot_a boot_b vendor_boot_a vendor_boot_b init_boot_a init_boot_b dtbo_a dtbo_b vbmeta_a vbmeta_b vbmeta_system_a vbmeta_system_b vbmeta_vendor_a vbmeta_vendor_b lk_a lk_b; do p=/dev/block/by-name/$n; if [ -r \"$p\" ]; then echo ===$n===; sha256sum \"$p\"; fi; done", 180, 4));
            }
            return x;
        }

        private static CollectorItem i(String id, String name, String cmd, int sec, int mb) {
            return new CollectorItem(id, name, cmd, sec, mb * 1024 * 1024);
        }
    }

    static class CommandResult {
        int exitCode;
        String output;
        boolean timedOut;
        boolean truncated;
    }

    static class RootShell {
        static CommandResult run(String command, int timeoutSeconds, int maxBytes) {
            CommandResult r = new CommandResult();
            Process p = null;
            try {
                p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
                ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
                InputStream in = p.getInputStream();
                byte[] buf = new byte[8192];
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);

                while (true) {
                    while (in.available() > 0) {
                        int n = in.read(buf);
                        if (n < 0) break;
                        int room = maxBytes - out.size();
                        if (room <= 0) {
                            r.truncated = true;
                            p.destroyForcibly();
                            break;
                        }
                        out.write(buf, 0, Math.min(n, room));
                        if (n > room) {
                            r.truncated = true;
                            p.destroyForcibly();
                            break;
                        }
                    }

                    if (!p.isAlive()) break;
                    if (System.nanoTime() > deadline) {
                        r.timedOut = true;
                        p.destroyForcibly();
                        break;
                    }
                    Thread.sleep(25);
                }

                p.waitFor(2, TimeUnit.SECONDS);

                while (in.available() > 0 && out.size() < maxBytes) {
                    int n = in.read(buf, 0, Math.min(buf.length, maxBytes - out.size()));
                    if (n < 0) break;
                    out.write(buf, 0, n);
                }

                r.exitCode = p.isAlive() ? -1 : p.exitValue();
                r.output = out.toString("UTF-8");
                if (r.truncated) r.output += "\n[TRUNCATED_BY_COLLECTOR]\n";
                if (r.timedOut) r.output += "\n[TIMEOUT_BY_COLLECTOR]\n";
            } catch (Throwable t) {
                r.exitCode = -127;
                r.output = t.getClass().getSimpleName() + ": " + t.getMessage();
            } finally {
                if (p != null && p.isAlive()) p.destroyForcibly();
            }
            return r;
        }
    }

    static class Redactor {
        private static final Pattern MAC = Pattern.compile("(?i)\\b([0-9a-f]{2}:){5}[0-9a-f]{2}\\b");
        private static final Pattern SENSITIVE_LINE = Pattern.compile("(?i).*(imei|meid|iccid|imsi|subscriber[_ .-]?id|serialno|ro\\.serialno|persist\\.radio|psk|password|passphrase|private[_ .-]?key).*", Pattern.MULTILINE);
        private static final Pattern SSID = Pattern.compile("(?i)(ssid\\s*[:=]\\s*)([^\\r\\n,}]+)");

        static String clean(String s) {
            if (s == null) return "";
            String x = MAC.matcher(s).replaceAll("XX:XX:XX:XX:XX:XX");
            x = SSID.matcher(x).replaceAll("$1<REDACTED>");
            String[] lines = x.split("\\r?\\n", -1);
            StringBuilder out = new StringBuilder(x.length());
            for (String line : lines) {
                if (SENSITIVE_LINE.matcher(line).matches()) out.append("<REDACTED_SENSITIVE_LINE>");
                else out.append(line);
                out.append('\n');
            }
            return out.toString();
        }
    }

    static class RuntimeProbe {
        static JSONObject summarize() {
            JSONObject o = new JSONObject();
            try {
                String props = RootShell.run("getprop ro.product.device; getprop ro.board.platform; getprop ro.build.version.release; getprop ro.build.fingerprint; getprop ro.boot.verifiedbootstate; getprop ro.boot.vbmeta.device_state; getprop ro.boot.slot_suffix", 10, 256 * 1024).output;
                String[] a = props.split("\\r?\\n", -1);
                o.put("ro.product.device", val(a, 0));
                o.put("ro.board.platform", val(a, 1));
                o.put("android_release", val(a, 2));
                o.put("build_fingerprint", val(a, 3));
                o.put("verifiedbootstate", val(a, 4));
                o.put("vbmeta_device_state", val(a, 5));
                o.put("slot_suffix", val(a, 6));
                o.put("viva_match", "viva".equalsIgnoreCase(val(a, 0)));
            } catch (Throwable ignored) {
            }
            return o;
        }

        static String val(String[] a, int i) {
            return i < a.length ? Redactor.clean(a[i]).trim() : "";
        }
    }
}
