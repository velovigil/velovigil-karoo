# Tyrewiz Integration Spec

**Status:** spec only, no code written yet (deliberate — pending karoo-ext consumer API verification + Rob's review)
**Date:** 2026-04-17
**Prereq:** Rob has Tyrewiz v1 (FCC ID `C90-PMB1`, M/N `55501`), fresh CR1632 inbound

## Design decision: consumer, not producer

Karoo's **karoo-ext SDK already exposes native DataTypes** for tire pressure:

```kotlin
const val TIRE_PRESSURE_FRONT = "TYPE_TIRE_PRESSURE_FRONT_ID"
const val TIRE_PRESSURE_REAR  = "TYPE_TIRE_PRESSURE_REAR_ID"
// fields: TIRE_PRESSURE, TIRE_PRESSURE_TARGET, TIRE_PRESSURE_RANGE, TIRE_PRESSURE_ALARM_ENABLED
```

This means **Karoo firmware already handles the ANT+ TPMS profile**. When Tyrewiz is paired via the native Sensor menu, Karoo emits `TIRE_PRESSURE_FRONT`/`REAR` DataPoints on its event bus.

**veloVigil extension does NOT need to implement BLE reverse engineering.** It only needs to **consume** the native DataPoints and feed them into `TelemetryBuffer`. This is a fundamentally different pattern from `OnewheelConnector.kt` (which is a BLE client) or `BoardDataTypes.kt` (which is a producer).

## Integration steps

### 1. New file: `TyrewizConsumer.kt`

Subscribes to native tire-pressure DataPoints; writes to shared `TelemetryBuffer`. Pattern skeleton (pseudo-code — verify against karoo-ext consumer API before committing):

```kotlin
package com.velovigil.karoo

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType

class TyrewizConsumer(private val karoo: KarooSystemService) {

    fun start() {
        // TODO: verify exact consumer API in karoo-ext — likely pattern is:
        karoo.addConsumer<DataPoint>(
            filter = { it.dataTypeId == DataType.Source.TIRE_PRESSURE_FRONT }
        ) { dp -> onTirePressure(dp, isFront = true) }

        karoo.addConsumer<DataPoint>(
            filter = { it.dataTypeId == DataType.Source.TIRE_PRESSURE_REAR }
        ) { dp -> onTirePressure(dp, isFront = false) }
    }

    private fun onTirePressure(dp: DataPoint, isFront: Boolean) {
        val psi = dp.values[DataType.Field.TIRE_PRESSURE] as? Double ?: return
        VeloVigilExtension.instance?.telemetryBuffer?.apply {
            if (isFront) tirePressureFrontPsi = psi
            else tirePressureRearPsi = psi
        }
    }

    fun stop() {
        // TODO: removeConsumer calls
    }
}
```

**Verify before committing:**
- Exact consumer registration pattern (karoo-ext docs or sample extension)
- DataPoint value key — may be `values["TIRE_PRESSURE"]` or a typed accessor
- Whether native tire pressure is PSI or bar — convert if needed
- Target pressure field — could drive trailhead dashboard recommendations

### 2. Patch `TelemetryBuffer.kt`

Add three fields (mirror the `board*` field pattern from Onewheel integration):

```kotlin
@Volatile var tirePressureFrontPsi: Double? = null
@Volatile var tirePressureRearPsi: Double? = null
@Volatile var tireTempC: Double? = null  // optional — some TPMS also emit temp
```

Add to JSON serialization:

```kotlin
val tire = buildMap<String, Any> {
    tirePressureFrontPsi?.let { put("frontPsi", it) }
    tirePressureRearPsi?.let { put("rearPsi", it) }
    tireTempC?.let { put("tempC", it) }
}
if (tire.isNotEmpty()) put("tire", tire)
```

### 3. Register consumer in `VeloVigilExtension.kt`

In `onCreate()` / equivalent lifecycle:

```kotlin
private lateinit var tyrewizConsumer: TyrewizConsumer

// in onStart:
tyrewizConsumer = TyrewizConsumer(karoo)
tyrewizConsumer.start()

// in onStop:
tyrewizConsumer.stop()
```

### 4. Fleet Worker schema — `/root/projects/velovigil-fleet/schema.sql`

Add columns to telemetry table (or query `json_extract(raw_json, '$.tire.frontPsi')` pattern already used for `board.*` fields — preferred, keeps schema narrow):

Preferred (no schema change, uses existing `raw_json` blob pattern):
```sql
-- No DDL. Dashboard queries use:
-- json_extract(raw_json, '$.tire.frontPsi') AS tire_front_psi
-- json_extract(raw_json, '$.tire.rearPsi')  AS tire_rear_psi
```

### 5. Dashboard v2 chart additions

Mirror the board charts pattern from Dashboard v2 (2026-04-16):
- `chartTirePressure` — time series, front/rear on same axis, target pressure line overlay
- Annotate dropouts (>2 PSI in 60s = probable leak — webhook alert later)

### 6. `/vv` skill

Add `tire` subcommand:
```
/vv tire → latest PSI for front/rear from last ride
/vv tire history → last 10 rides with setup-attributed pressure
```

### 7. Geofenced recommendation layer (separate feature — DO NOT build in this change)

The trailhead dashboard "based on your last 3 rides at Blue Mound, target 22/24" is a SEPARATE feature. This spec only establishes the **data pipe**. Recommendation engine comes after.

## Test plan

Once Rob has fresh CR1632 installed and Tyrewiz paired to Karoo:

1. **Karoo sensor pair test.** Settings → Sensors → Add Sensor → scan. Tyrewiz should appear as a tire pressure sensor on ANT+. Assign front (or rear). Confirm PSI displays on a ride profile data field.
2. **Extension consumer test.** Install veloVigil Karoo ext (signed v2 release once built). Start a ride. Confirm `TelemetryBuffer.tirePressureFrontPsi` is non-null in the emitted payload.
3. **Worker ingest test.** Check `/vv latest` — tire block should appear in the JSON with non-null pressure.
4. **Dashboard test.** Tire pressure chart renders; historical data accumulates over 2-3 rides.

## Honest unknowns

- **karoo-ext consumer API specifics.** I have not read the exact `addConsumer` signature. This spec assumes the common pattern; verify before shipping.
- **ANT+ TPMS support on Rob's specific Karoo firmware version.** Most recent Karoo 2/3 support it; very old firmware may not. Test step 1 confirms.
- **Pressure units.** Different ANT+ TPMS sensors report kPa, PSI, or bar. Karoo normalizes before emitting, but verify units match veloVigil's expected PSI.
- **Target pressure field.** Karoo-ext exposes it; unclear whether it's rider-configurable in the Karoo UI or comes from the sensor. If rider-configurable, build the UX for it in veloVigil SettingsActivity.

## When to execute

**Prereq signal:** Rob has Tyrewiz pairing confirmed on Karoo (Step 1 of test plan above succeeds). Once paired, the code integration above is ~2-3 hours of focused work including a signed release APK.

**If pairing fails (native ANT+ path not available):** fall back to direct BLE client following the `OnewheelConnector` pattern — ~30-60 min of btsnoop mapping + a BLE client class. Worst-case, not best-case.
