package com.nthlink.outline

import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val vpnPreparation = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.start).setOnClickListener { prepareVpn() }
        findViewById<Button>(R.id.stop).setOnClickListener { stop() }
    }

    private fun prepareVpn() = VpnService.prepare(this)?.let {
        vpnPreparation.launch(it)
    } ?: start()

    private fun start() {
        OutlineVpnService.start(this)
    }

    private fun stop() {
        OutlineVpnService.stop(this)
    }
}