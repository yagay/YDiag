package com.yagay.ydiag.root

data class ProcessIdentity(val pid: Int, val uid: String, val name: String, val packageName: String)

object ProcessTracker {
    fun snapshot(packages: Set<String>): Map<Int, ProcessIdentity> {
        if (packages.isEmpty()) return emptyMap()
        val result = RootShell.exec("ps -A -o PID,UID,NAME", 5)
        if (result.code != 0) return emptyMap()
        val found = linkedMapOf<Int, ProcessIdentity>()
        result.stdout.lineSequence().drop(1).forEach { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 3)
            if (parts.size < 3) return@forEach
            val pid = parts[0].toIntOrNull() ?: return@forEach
            val uid = parts[1]
            val name = parts[2]
            val pkg = packages.firstOrNull { name == it || name.startsWith("$it:") } ?: return@forEach
            found[pid] = ProcessIdentity(pid, uid, name, pkg)
        }
        return found
    }
}
