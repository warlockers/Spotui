# Spotui - Spotify Clone with Deep Integration

Audio Stream Diagnostics & Build Guide

## 🔧 Stream Diagnostics (NEW)

### Zapnutí diagnostiky v aplikaci:

1. Jděte do **Settings** → **Diagnostics**
2. Zapněte **Recording enabled** switch
3. Přehrávejte hudbu na mobilní síti s nižší kvalitou
4. Diagnostika bude zaznamenávat:
   - ✓ Audio stream resolution (format, bitrate)
   - ✓ CDN validation & HTTP status codes
   - ✓ Buffer loading events
   - ✓ Network timeouts
   - ✓ Playback errors
   - ✓ Client fallback chain (YouTube clients)

### Zobrazení logů:

- **Real-time** - v Diagnostics screenu v aplikaci
- **Export** - tlačítko "Export" vytvoří `.txt` soubor v `/logs`
- **Clear** - vyčistí historii logů

Exportované soubory najdete v: `Android/data/com.music.spotui/files/logs/`

---

## 📱 Kompilace APK lokálně

### Požadavky:
- Android Studio (nebo command-line tools)
- JDK 17+
- Android SDK (compileSdk 37)

### Build Release APK:

```bash
# Clone repo
git clone https://github.com/warlockers/Spotui.git
cd Spotui

# Build APK
./gradlew assembleRelease

# APK je zde:
# app/build/outputs/apk/release/Spotui_v1.4.6.apk
```

### Instalace na telefon:

```bash
adb install -r app/build/outputs/apk/release/Spotui_v1.4.6.apk
```

---

## 🚀 Automatická kompilace na GitHub Actions

### Postup:

1. **Vytvořte `.github/workflows/build-apk.yml`** v vašem forku:

```yaml
name: Build APK

on:
  push:
    branches: [ main ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-java@v4
      with:
        java-version: '17'
        distribution: 'temurin'
        cache: gradle
    - run: chmod +x ./gradlew
    - run: ./gradlew assembleRelease
    - uses: actions/upload-artifact@v4
      with:
        name: spotui-apk
        path: app/build/outputs/apk/release/Spotui_v*.apk
        retention-days: 30
```

2. **Pushnout změny:**
```bash
git push origin main
```

3. **Jít na GitHub Actions** v vašem forku a sledovat build
4. **Stáhnout APK** - klik na run → Artifacts → spotui-apk

---

## 🔍 Diagnostika problému s přehráváním

Když se track přehraje jen minutu a skočí na dalších:

1. **Zapněte logging** (Settings → Diagnostics)
2. **Hrájte music** na mobilní síti se základní kvalitou
3. **Kdy přeskočí**, otevřete Diagnostics a **Export logs**
4. **Hledejte v logu:**

```
❌ TIMEOUT: Stream validation (...)
❌ TIMEOUT: Signature timestamp
❌ Stream URL validation: code=403
❌ PLAYBACK_ERROR at 60s: ...
```

---

## 📋 Informace o verzích

- **Kotlin**: 94.8%
- **Java**: 5.2%
- **compileSdk**: 37
- **minSdk**: 26
- **targetSdk**: 37
- **versionCode**: 202607146
- **versionName**: 1.4.6

---

## 🔗 Zdroje

- **Spotui Original**: https://github.com/H4zh4n/Spotui
- **Your Fork**: https://github.com/warlockers/Spotui
- **Audio Streaming**: YouTube (fallback), Tidal FLAC, Qobuz, Deezer

---

## ⚙️ Nastavení kvality zvuku

V aplikaci: **Settings** → **Cellular Quality**

- **Low** - DataSaver (60 kbps)
- **Normal** - AUTO (zvolí optimální bitrate)
- **High** - 160+ kbps (HD)
- **Lossless** - FLAC když dostupný

---

## 📞 Troubleshooting

**APK se nezkompiluje?**
```bash
./gradlew clean
./gradlew assembleRelease
```

**Permissions chyba?**
```bash
chmod +x gradlew
```

**Staré buildy?**
```bash
rm -rf app/build
./gradlew assembleRelease
```
