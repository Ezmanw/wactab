package com.wactab.app.net

import android.util.Log
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "PenServer"

/**
 * TCP server the daemon (running on the host, connected via `adb forward`) connects to.
 * Accepts a single client at a time and streams encoded [PenEvent]s to it.
 *
 * Stylus [MotionEvent]s (and therefore [send]) arrive on the UI thread, but socket writes
 * are illegal there (NetworkOnMainThreadException), so outgoing events are queued and
 * flushed by a dedicated writer thread instead of being written inline.
 */
class PenServer(private val port: Int, private val onStateChange: (Connected: Boolean) -> Unit) {
    private val running = AtomicBoolean(false)
    private val outputRef = AtomicReference<OutputStream?>(null)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var writerThread: Thread? = null

    // Bounded so a stalled connection can't grow this unboundedly; newest samples matter
    // more than old ones for drawing, so drop-oldest is fine under backpressure.
    private val queue = ArrayBlockingQueue<ByteArray>(256)

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (running.getAndSet(true)) return

        writerThread = Thread {
            while (running.get()) {
                val bytes = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    break
                }
                val out = outputRef.get() ?: continue
                try {
                    out.write(bytes)
                } catch (e: Exception) {
                    Log.i(TAG, "Write failed: ${e.message}")
                    outputRef.set(null)
                    onStateChange(false)
                }
            }
        }.also { it.isDaemon = true; it.start() }

        acceptThread = Thread {
            try {
                Log.i(TAG, "Binding ServerSocket on port $port")
                ServerSocket(port).use { server ->
                    serverSocket = server
                    Log.i(TAG, "Listening on port $port")
                    while (running.get()) {
                        val client: Socket = try {
                            server.accept()
                        } catch (e: Exception) {
                            Log.i(TAG, "accept() ended: ${e.message}")
                            break
                        }
                        Log.i(TAG, "Client connected: ${client.remoteSocketAddress}")
                        client.tcpNoDelay = true
                        outputRef.set(client.getOutputStream())
                        onStateChange(true)
                        // Block here until the client disconnects; we only support one client.
                        try {
                            val buf = ByteArray(1)
                            while (running.get() && client.getInputStream().read(buf) != -1) {
                                // Daemon doesn't send data back; this just detects disconnects.
                            }
                        } catch (e: Exception) {
                            Log.i(TAG, "Client read loop ended: ${e.message}")
                        } finally {
                            outputRef.set(null)
                            onStateChange(false)
                            client.close()
                            Log.i(TAG, "Client disconnected")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server thread failed to bind/listen on port $port", e)
                onStateChange(false)
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        writerThread?.interrupt()
        outputRef.set(null)
    }

    fun send(event: PenEvent) {
        if (outputRef.get() == null) return
        if (!queue.offer(event.encode())) {
            queue.poll()
            queue.offer(event.encode())
        }
    }
}
