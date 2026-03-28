package com.velovigil.karoo

import android.util.Log
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.*

/**
 * veloVigil Karoo Extension — Phase 1
 *
 * Registers with the Karoo system, provides a Fleet Status data field,
 * and logs RideState changes. Foundation for HRV processing and
 * fleet telemetry in later phases.
 */
class VeloVigilExtension : KarooExtension("velovigil", BuildConfig.VERSION_NAME) {

    companion object {
        private const val TAG = "veloVigil"
    }

    override val types by lazy {
        listOf(
            FleetStatusDataType(extension),
        )
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "veloVigil extension v${BuildConfig.VERSION_NAME} starting")
    }

    override fun onDestroy() {
        Log.i(TAG, "veloVigil extension stopping")
        super.onDestroy()
    }
}

/**
 * Fleet Status data field — displays connection state on the Karoo screen.
 * Phase 1: shows "VIGIL READY" via numeric config.
 * Phase 2+: will show H10 connection, sync status, HRV.
 */
class FleetStatusDataType(extension: String) : DataTypeImpl(extension, "fleet_status") {

    companion object {
        private const val TAG = "veloVigil.Status"
    }

    override fun startView(
        context: android.content.Context,
        config: ViewConfig,
        emitter: ViewEmitter,
    ) {
        Log.i(TAG, "Status tile view started")
        emitter.onNext(ShowCustomStreamState("VIGIL READY", null))
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        Log.i(TAG, "Status stream started")
        val dataPoint = DataPoint(
            dataTypeId = dataTypeId,
            values = mapOf("status" to 1.0),
        )
        emitter.onNext(StreamState.Streaming(dataPoint))
    }
}
