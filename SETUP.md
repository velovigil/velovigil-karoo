# veloVigil — Quick Setup

## Prerequisites
- Karoo 2 or 3 connected via USB
- USB debugging enabled on Karoo (Settings > System > Developer Options > USB Debugging)
- ADB available on your machine

## One-Command Install

Plug in your Karoo and run:

```bash
curl -sL https://raw.githubusercontent.com/velovigil/velovigil-karoo/main/setup.sh | bash
```

This will:
1. Download the latest APK
2. Install it on your Karoo
3. Register you with an invite code
4. Push your credentials directly to the app
5. You're ready to ride

## Manual Install

If you prefer to do it yourself:

1. Download the APK from [Releases](https://github.com/velovigil/velovigil-karoo/releases/latest)
2. `adb install velovigil-v*.apk`
3. Open veloVigil on Karoo, enter your invite code, tap Register

## Claude Code Install

If you use Claude Code, just say:

> Install veloVigil on my Karoo

Claude Code will handle the rest if it has the CLAUDE.md context from this repo.
