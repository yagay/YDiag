package com.yagay.ydiag.export

import android.content.Context
import android.os.Build
import com.yagay.ydiag.model.SessionMeta
import com.yagay.ydiag.root.RootShell
import java.io.File

object EvidenceCollector {
    fun collect(context: Context, sessionDir: File, meta: SessionMeta) {
        val evidence = File(sessionDir, "evidence").apply { mkdirs() }
        File(evidence, "device-info.txt").writeText(buildString {
            appendLine("manufacturer=${Build.MANUFACTURER}")
            appendLine("brand=${Build.BRAND}")
            appendLine("model=${Build.MODEL}")
            appendLine("device=${Build.DEVICE}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("release=${Build.VERSION.RELEASE}")
            appendLine("fingerprint=${Build.FINGERPRINT}")
            appendLine("security_patch=${Build.VERSION.SECURITY_PATCH}")
            appendLine("ydiag_package=${context.packageName}")
        })

        if (!meta.rootAvailable) {
            File(evidence, "root-unavailable.txt").writeText("Root was not available for this session.\n")
            return
        }

        fun write(name: String, command: String, timeout: Long = 20) {
            val result = runCatching { RootShell.exec(command, timeout) }.getOrNull()
            File(evidence, name).writeText(
                if (result == null) "collector failed\n"
                else buildString {
                    append(result.stdout)
                    if (result.stderr.isNotBlank()) {
                        appendLine()
                        appendLine("=== stderr ===")
                        append(result.stderr)
                    }
                }
            )
        }

        val packages = meta.targetPackages.filter { it.matches(Regex("[A-Za-z0-9_.]+")) }
        if ("exit_info" in meta.enabledOptions) {
            packages.forEach { pkg -> write("exit-info-$pkg.txt", "dumpsys activity exit-info $pkg", 15) }
        }
        if ("memory" in meta.enabledOptions) {
            packages.forEach { pkg -> write("meminfo-$pkg.txt", "dumpsys meminfo $pkg", 20) }
        }
        if ("anr" in meta.enabledOptions) {
            val pattern = packages.joinToString("\\|")
            write(
                "anr.txt",
                "echo '=== /data/anr ==='; ls -lt /data/anr 2>/dev/null; " +
                    "for f in /data/anr/*; do [ -f \"\$f\" ] || continue; " +
                    "grep -Eq '$pattern' \"\$f\" 2>/dev/null && { echo; echo \"=== \$f ===\"; cat \"\$f\"; }; done",
                30,
            )
        }
        if ("tombstone" in meta.enabledOptions || "native" in meta.enabledOptions) {
            val pattern = packages.joinToString("\\|")
            write(
                "tombstones.txt",
                "echo '=== /data/tombstones ==='; ls -lt /data/tombstones 2>/dev/null; " +
                    "for f in /data/tombstones/tombstone_*; do [ -f \"\$f\" ] || continue; " +
                    "grep -Eq '$pattern' \"\$f\" 2>/dev/null && { echo; echo \"=== \$f ===\"; cat \"\$f\"; }; done",
                40,
            )
        }
        if ("kernel" in meta.enabledOptions) {
            write("kernel-dmesg.txt", "dmesg 2>&1 | tail -n 8000", 15)
        }
        if ("selinux" in meta.enabledOptions) {
            write("selinux-denials.txt", "dmesg 2>&1 | grep -i 'avc: denied' | tail -n 4000", 15)
        }
        if ("lsposed_log" in meta.enabledOptions) {
            write(
                "lsposed-log.txt",
                "echo '=== LSPosed logs ==='; ls -lt /data/adb/lspd/log 2>/dev/null; " +
                    "for f in /data/adb/lspd/log/*.log; do [ -f \"\$f\" ] || continue; " +
                    "echo; echo \"=== \$f ===\"; tail -n 3000 \"\$f\"; done",
                30,
            )
        }
        if ("root_module" in meta.enabledOptions) {
            write(
                "root-modules.txt",
                "echo '=== modules ==='; ls -la /data/adb/modules 2>/dev/null; " +
                    "find /data/adb/modules -type f -name '*.log' 2>/dev/null | head -n 80 | " +
                    "while read f; do echo; echo \"=== \$f ===\"; tail -n 400 \"\$f\"; done",
                30,
            )
        }
        if ("binder" in meta.enabledOptions) {
            write("binder-state.txt", "dumpsys binder_calls_stats 2>/dev/null || dumpsys activity processes", 20)
        }
        if ("network" in meta.enabledOptions) {
            write("network-state.txt", "dumpsys connectivity; echo; ss -tpn 2>/dev/null | head -n 4000", 20)
        }
    }
}
