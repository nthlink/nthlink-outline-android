package com.nthlink.outline

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class OutlineVpnService : VpnService(), outline.PacketWriter, outline.SocketProtector {
    companion object {
        private const val HOST = "1.2.3.4"
        private const val PORT = 443
        private const val PASSWORD = "password"
        private const val METHOD = "method"
        private const val PREFIX = ""

        private const val TAG = "OutlineVpnService"
        private const val ACTION_START = "action.start"
        private const val ACTION_STOP = "action.stop"

        fun start(context: Context) {
            context.startService(newIntent(context, ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(newIntent(context, ACTION_STOP))
        }

        private fun newIntent(context: Context, action: String): Intent {
            return Intent(context, OutlineVpnService::class.java).apply {
                this.action = action
            }
        }
    }

    private val isRunning = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val buffer = ByteBuffer.allocate(1501)
    private lateinit var tunFd: ParcelFileDescriptor
    private lateinit var inputStream: FileInputStream
    private lateinit var outputStream: FileOutputStream

    override fun onCreate() {
        Log.i(TAG, "onCreate: ")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: ")
        val action = intent?.action
        return when {
            action == ACTION_START && !isRunning.get() -> {
                start()
                START_STICKY
            }

            action == ACTION_STOP -> {
                stop()
                START_NOT_STICKY
            }

            else -> START_STICKY
        }
    }

    private fun start() {
        Log.i(TAG, "VPN connecting...")

        val tun = Builder()
            .addAddress("10.255.0.1", 30)
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
            // Must add our VPN package to the disallowed list or pass a socket
            // protector to outline to make outgoing traffic bypass the VPN.
            .addDisallowedApplication(applicationContext.packageName)
            .establish()

        tunFd = tun ?: run {
            Log.e(TAG, "Cannot establish tun interface")
            return
        }

        // Put TUN FD in blocking mode
        outline.Outline.setNonblock(tunFd.fd.toLong(), false)

        inputStream = FileInputStream(tunFd.fileDescriptor)
        outputStream = FileOutputStream(tunFd.fileDescriptor)

        try {
            // packetWriter, socketProtector, address, cipher, secret, prefix
            outline.Outline.start(this, this, "$HOST:$PORT", METHOD, PASSWORD, PREFIX)
        } catch (e: Exception) {
            stop()
            Log.e(TAG, "Start outline failed: ", e)
            return
        }

        isRunning.set(true)

        // Handle IP packet reading in another thread
        handlePackets()
        startForegroundWithNotification()
        Log.i(TAG, "VPN connected !")
    }

    // Implement the PacketWriter interface in order to write back IP packets.
    override fun writePacket(pkt: ByteArray?) {
        try {
            outputStream.write(pkt)
        } catch (e: Exception) {
            Log.e(TAG, "writePacket: failed to write bytes to TUN:", e)
        }
    }

    override fun protect(fd: Long): Boolean {
        return this.protect(fd.toInt())
    }

    private fun stop() {
        Log.i(TAG, "VPN disconnecting...")
        isRunning.set(false)
        scope.cancel()

        try {
            outline.Outline.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Stop outline failed: ", e)
        }

        if (this::tunFd.isInitialized) {
            tunFd.close()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN disconnected !")
    }

    override fun onRevoke() {
        Log.i(TAG, "onRevoke: ")
        stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: ")
    }

    // Read IP packets from TUN FD and feed to outline
    private fun handlePackets() = scope.launch(Dispatchers.IO) {
        while (isRunning.get()) {
            try {
                val n = inputStream.read(buffer.array())
                if (n > 0) {
                    buffer.limit(n)
                    outline.Outline.writePacket(buffer.array())
                    buffer.clear()
                }
            } catch (e: Exception) {
                Log.e(TAG, "handlePackets: failed to read bytes from TUN: ", e)
            }
        }
    }

    private fun startForegroundWithNotification() {
        // Set notification content intent, start launch activity
        val clickIntent = packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        } ?: throw NullPointerException("Get launch intent failed!")

        // Set notification
        createNotificationChannel(this)
        val notification = createNotification(this, clickIntent)

        // Move background service to foreground
        startForeground(1, notification)
    }
}