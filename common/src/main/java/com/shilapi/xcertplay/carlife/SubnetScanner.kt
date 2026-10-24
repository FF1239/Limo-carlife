package com.shilapi.xcertplay.carlife

import android.util.Log
import com.diplay.carlife.core.CarLife
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Finds the CarLife phone on the local network by probing its CMD port (7240).
 *
 * Wireless CarLife has no publicly documented discovery protocol (CarLife <=
 * 6.7.2 / CarWith open their six TCP ports after the user taps 无线连接), so we
 * scan the /24 of each active IPv4 interface. Ports appear only while the phone
 * waits for a head unit, so scans are cheap and user-triggered.
 */
object SubnetScanner {

    private const val TAG = "CarLifeScan"
    private const val PROBE_PORT = CarLife.MD_PORT_CMD
    private const val CONNECT_TIMEOUT_MS = 150

    /**
     * Probes the /24 of every active IPv4 interface with a bounded worker
     * pool (one thread per address once crashed phones that cap pthreads -
     * the 0.2.11 lesson never ship 250+ threads).
     */
    fun findPhone(onFound: (InetAddress) -> Unit, onDone: () -> Unit): Thread {
        return thread(name = "carlife-scan") {
            var reported = false
            // Once the phone is found the remaining workers must stop probing:
            // letting all 16 drain the /24 costs seconds for nothing and keeps
            // radio + threads busy after the hit.
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val lock = Any()
            fun report(found: Boolean, addr: InetAddress?) {
                synchronized(lock) {
                    if (reported) return
                    reported = true
                    stop.set(true)
                    if (found && addr != null) {
                        Log.i(TAG, "CarLife phone found at ${addr.hostAddress}")
                        onFound(addr)
                    } else {
                        onDone()
                    }
                }
            }
            try {
                val queue = java.util.concurrent.ConcurrentLinkedQueue(subnetAddresses())
                val workers = List(16) { index ->
                    thread(name = "carlife-probe-$index") {
                        while (true) {
                            if (stop.get()) return@thread
                            val addr = queue.poll() ?: break
                            if (probe(addr)) {
                                report(true, addr)
                                return@thread
                            }
                        }
                    }
                }
                workers.forEach { it.join() }
                report(false, null)
            } catch (t: Throwable) {
                Log.w(TAG, "scan aborted", t)
                report(false, null)
            }
        }
    }

    private fun probe(address: InetAddress): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(address, PROBE_PORT), CONNECT_TIMEOUT_MS)
            true
        }
    } catch (e: Exception) {
        false
    }

    /** All local /24 addresses across non-loopback IPv4 interfaces. */
    fun subnetAddresses(): List<InetAddress> {
        val result = ArrayList<InetAddress>()
        try {
            val nis = NetworkInterface.getNetworkInterfaces() ?: return result
            for (ni in Collections.list(nis)) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in Collections.list(ni.inetAddresses)) {
                    if (ia !is Inet4Address || ia.isLoopbackAddress) continue
                    val ip = ia.address
                    if (ip.size != 4) continue
                    val base = ip.copyOf()
                    base[3] = 0
                    for (host in 1..254) {
                        val candidate = base.copyOf()
                        candidate[3] = host.toByte()
                        if (!candidate.contentEquals(ip)) {
                            result.add(InetAddress.getByAddress(candidate))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "subnet enumeration failed", e)
        }
        return result
    }
}
