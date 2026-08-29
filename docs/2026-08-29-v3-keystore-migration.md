# v3 keystore migration — staged 2026-08-29

## What happened

The v2 release keystore died in the 08-28 repo re-clone — it was untracked, gitignored, and lived only on Lares. The private key can't be rebuilt from APKs. Android refuses updates across a signing-cert change, so every v2-signed install has to be uninstalled once.

This is the cheapest it will ever be: all 18 beta signups are still `pending` in D1 — nobody outside this house has installed anything.

## What's minted

- `velovigil-release-v3.keystore`, alias `velovigil`, RSA-4096, valid ~30 years
- Cert SHA-256: `63:E3:9D:05:9E:E6:EF:2A:8F:8A:C2:9D:86:0B:EC:FA:EC:2E:43:8B:F5:07:AD:0B:0F:B8:35:7F:38:7C:5C:94`
- Signs BOTH veloVigil and Player Zero (same NorthWoods key, like before)
- Off-box copies verified byte-identical in 1Password Fleet-Shared: document `velovigil-release-v3.keystore` + item `velovigil keystore v3 PASS`. If Lares dies tonight, the key survives.

## What migration does, per Karoo

Backs up the app's files dir, uninstalls, installs the v3-signed APK, pushes the files back, prints a versionName receipt. The H10 bond is OS-level and survives — no re-pairing, no shade-tap. Internal-storage config is lost by the uninstall; veloVigil re-imports `config.json` from the files dir (the 0.3.2 feature), which the restore covers.

## Run it when you're ready (not before a ride you care about)

```
cd /root/projects/velovigil-karoo
adb devices    # get the serial: KAROO20ALC061506370 or KAROO20ALC061503273
scripts/migrate-to-v3.sh <serial> <velovigil-v3 apk> <player-zero-v3 apk>
```

The APKs are in Downloads with today's date. The script demands an explicit serial — it will never pick a device on its own.

## Beta riders

Never see any of this. releases/latest now serves v3-signed APKs, so their first install is v3 from day one.

— Margin
