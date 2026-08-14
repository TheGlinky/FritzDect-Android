# THEGLINKY FRITZ!DECT Controller
## Android App für FRITZ!Box Steckdosen-Steuerung

![Status](https://img.shields.io/badge/Status-Ready%20to%20Build-00D9FF)
![API Level](https://img.shields.io/badge/API%20Level-28+-9D4EDD)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-FF006E)

---

## 🎯 Features
- ✅ **Live Toggle** - Steckdosen instant ein/aus (keine Verzögerung)
- ✅ **Timer-System** - AN-Zeit + Pause-Zeit konfigurierbar
- ✅ **Repeat-Modus** - Einmalig oder täglich wiederholen
- ✅ **Live Status** - Echtzeit Power & Temperatur-Anzeige
- ✅ **Lokal + VPN** - Funktioniert im Heimnetzwerk und über VPN
- ✅ **THEGLINKY Branding** - Cyan→Purple→Pink Neon-Aesthetic

---

## 🔧 Setup & Build

### Voraussetzungen
- Android Studio **Giraffe** oder neuer
- Android SDK 34
- JDK 11+
- FRITZ!Box mit FRITZ!DECT Steckdosen

### 1. Projekt in Android Studio erstellen

```bash
# Neues Projekt
File → New → New Android Studio Project
- Name: FritzDect
- Package: com.theglinky.fritzdect
- Language: Kotlin
- API Level: 28+
```

### 2. Dateien in das Projekt kopieren

**Kotlin Dateien** → `app/src/main/java/com/theglinky/fritzdect/`
- `MainActivity.kt`
- `FritzViewModel.kt`

**Ressourcen** → `app/src/main/res/`
- `AndroidManifest.xml` → `app/src/main/`
- `strings.xml` → `app/src/main/res/values/`
- `themes.xml` → `app/src/main/res/values/`

**Build Config**:
- `build.gradle.kts` → `app/build.gradle.kts`

### 3. Gradle synchronisieren

```bash
# Android Studio: Tools → Gradle → Sync Now
# oder Terminal:
./gradlew clean build
```

### 4. Build & Release

#### Debug APK (Testen)
```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

#### Release APK (Google Play / Verteilen)
```bash
# Keystore erstellen (einmalig)
keytool -genkey -v -keystore theglinky-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias theglinky

# Signing Config in app/build.gradle.kts hinzufügen:
# (siehe Abschnitt "Release Signing Config" unten)

./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

### Release Signing Config
Füg das in `app/build.gradle.kts` ein (nach `android {`):

```gradle
signingConfigs {
    release {
        storeFile = file("../theglinky-release.jks")
        storePassword = "YOUR_PASSWORD"
        keyAlias = "theglinky"
        keyPassword = "YOUR_PASSWORD"
    }
}

buildTypes {
    release {
        signingConfig = signingConfigs.release
        isMinifyEnabled = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
}
```

---

## 📱 Installation

### Auf Gerät laden

```bash
# Debug APK direkt installieren
adb install -r app/build/outputs/apk/debug/app-debug.apk

# oder Release APK
adb install -r app/build/outputs/apk/release/app-release.apk
```

### Manuell via Datei
1. APK auf Android-Gerät kopieren
2. Dateimanager → APK-Datei → Installieren

---

## ⚙️ FRITZ!Box Konfiguration

### 1. FRITZ!Box IP-Adresse herausfinden
```
192.168.178.1  (Standard)
# oder über Netzwerk-Scanner in der App finden
```

### 2. Sichere Authentifizierung aktivieren (optional)
- FRITZ!Box Login → Startseite → "Sicherheit"
- Kennwort für lokale Anmeldung setzen
- In der App eingeben

### 3. UPnP aktivieren (für API-Zugriff)
- FRITZ!Box Login → Heimnetzwerk → Netzwerk
- "UPnP aktivieren" ✅

---

## 🚀 App-Nutzung

### Erste Nutzung
1. App starten
2. FRITZ!Box IP eingeben (z.B. `192.168.178.1`)
3. Optional: Passwort (falls gesetzt)
4. **"CONNECT"** drücken

### Steckdosen steuern
1. **ON/OFF Button** (50x50) = Instant Toggle (live!)
2. **SET TIMER Button** = Timer-Dialog öffnen
   - ON Duration (Minuten): wie lange soll die Steckdose AN sein?
   - Pause Duration (Stunden): wie lange Pause?
   - **Repeat daily** ✅/❌
3. **START** = Timer läuft! ⏱️

### VPN-Zugriff
- VPN verbinden (bevor App startet)
- FRITZ!Box IP eingeben (funktioniert über VPN-Tunnel)
- Steuern wie normal ✅

---

## 🎨 Design & Aesthetic

**THEGLINKY Brand Colors:**
- Dark Background: `#0A0E27`
- Cyan: `#00D9FF`
- Purple: `#9D4EDD`
- Pink: `#FF006E`
- Gradient: Cyan → Purple → Pink

**Typography:**
- Display: `FontFamily.Monospace` (Terminal-Feel)
- All-Caps Labels für Impact
- Konsistente `12sp` & `14sp` Sizes

---

## 🔐 Security Notes

- **Lokales Netzwerk**: Keine Authentifizierung nötig (gesichert durch LAN)
- **VPN**: Verbindung verschlüsselt ✅
- **Cloud**: NICHT implementiert (lokal-only für Sicherheit)
- **Credentials**: Lokal gespeichert in SharedPreferences (optional: verschlüsselt via EncryptedSharedPreferences)

---

## 🐛 Troubleshooting

### "Connection failed"
- ✅ FRITZ!Box eingeschaltet?
- ✅ IP-Adresse richtig?
- ✅ FRITZ!DECT Steckdosen angebunden?
- ✅ UPnP aktiviert?

### "Timer funktioniert nicht"
- ✅ App im Hintergrund aktiv lassen (nicht minimieren)
- ✅ Battery Saver könnte Timer pausieren (Ausnahme hinzufügen)

### "Verzögerte Reaktion"
- ✅ Netzwerk-Ping checken: `ping 192.168.178.1`
- ✅ FRITZ!Box Last checken (System → Ereignisse)

---

## 📦 APK Distribution

### Google Play Store
1. Google Play Developer Account erstellen (~$25)
2. Release APK hochladen
3. Testing → Production rollout

### Alternative: Direct Distribution
- APK über Discord/GitHub teilen
- QR-Code für Download
- `adb install` für Development

---

## 🔄 Update-Prozess

1. Code ändern
2. Version in `build.gradle.kts` erhöhen
3. Neu bauen & signieren
4. APK verteilen

---

## 📝 Lizenz & Credits

**THEGLINKY FRITZ!DECT Controller v1.0**
- Entwickelt für Heimautomation
- FRITZ!Box API Dokumentation: https://avm.de/service/schnittstellen/

---

## 🎯 Nächste Features (TODO)
- [ ] Graphische Verbrauchshistorie
- [ ] Home Assistant Integration (optional)
- [ ] Widget für Lock-Screen
- [ ] Offline-Mode (cached state)
- [ ] Backup/Restore von Timer-Profilen

---

**Viel Spaß mit der App! 🚀✨**
