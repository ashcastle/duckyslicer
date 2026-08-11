package com.ashcastle.duckyslicer

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.rules.ExternalResource

/** Keeps the test provider process non-cached while it is serving a blocking transfer. */
class BlockingProviderProcessRule : ExternalResource() {
    private var lease: BlockingProviderProcessLease? = null

    override fun before() {
        lease = BlockingProviderProcessLease.acquire()
    }

    override fun after() {
        lease?.close()
        lease = null
    }
}

class BlockingProviderProcessService : Service() {
    private val binder = Binder()

    override fun onBind(intent: Intent?): IBinder = binder
}

private class BlockingProviderProcessLease(
    private val context: Context,
    private val connection: ServiceConnection,
) : AutoCloseable {
    override fun close() {
        context.unbindService(connection)
    }

    companion object {
        fun acquire(): BlockingProviderProcessLease {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext.applicationContext
            val connected = CountDownLatch(1)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
            val intent = Intent().setComponent(
                ComponentName(
                    instrumentation.context.packageName,
                    BlockingProviderProcessService::class.java.name,
                ),
            )
            check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                "Could not bind the blocking-provider process"
            }
            if (!connected.await(10, TimeUnit.SECONDS)) {
                context.unbindService(connection)
                error("Blocking-provider process did not bind")
            }
            return BlockingProviderProcessLease(context, connection)
        }
    }
}
