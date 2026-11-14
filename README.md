# Tello Pilot

[![CI](https://github.com/tt-box/tello-pilot/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/tt-box/tello-pilot/actions/workflows/ci.yml)
[![Coverage](https://github.com/tt-box/tello-pilot/blob/badges/coverage.svg?raw=true)](https://github.com/tt-box/tello-pilot/actions/workflows/ci.yml)
[![CodeQL](https://github.com/tt-box/tello-pilot/actions/workflows/codeql.yml/badge.svg?branch=main)](https://github.com/tt-box/tello-pilot/actions/workflows/codeql.yml)
[![Dependency scan](https://github.com/tt-box/tello-pilot/actions/workflows/dependencies.yml/badge.svg?branch=main)](https://github.com/tt-box/tello-pilot/actions/workflows/dependencies.yml)
[![Release](https://github.com/tt-box/tello-pilot/actions/workflows/release.yml/badge.svg)](https://github.com/tt-box/tello-pilot/releases/latest)
[![Version](https://img.shields.io/github/v/release/tt-box/tello-pilot?sort=semver&label=version)](https://github.com/tt-box/tello-pilot/releases/latest)
[![Dependabot](https://img.shields.io/badge/Dependabot-enabled-025E8C?logo=dependabot)](https://github.com/tt-box/tello-pilot/blob/main/.github/dependabot.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?logo=kotlin&logoColor=white)
![AGP](https://img.shields.io/badge/AGP-9.5.0-3DDC84?logo=android&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-30-3DDC84?logo=android&logoColor=white)
![ML-DSA-65](https://img.shields.io/badge/signature-ML--DSA--65%20(FIPS%20204)-5A2D82)

> Dépôt privé : les badges dynamiques (CI, CodeQL, couverture, version) ne s'affichent que pour les personnes ayant accès au dépôt ; le badge « version » (shields.io) ne s'affiche que si le dépôt devient public.

Application Android moderne (Kotlin, Jetpack Compose) pour piloter un drone **Ryze/DJI Tello**. Elle parle directement le protocole binaire du drone, comme l'app officielle.

## Fonctions (v1)
- **Connexion automatique** au Wi-Fi `TELLO-xxxx` : les sockets sont liés au réseau Wi-Fi même sans Internet, avec handshake `conn_req`/`conn_ack` et reconnexion automatique.
- **Pilotage par deux joysticks** en mode 2 (modes 1 à 4 disponibles dans `StickMixer`), avec zone morte, courbe expo et mode rapide.
- **Commandes** : décollage, atterrissage (répété jusqu'à confirmation), décollage lancé (Throw & Go), atterrissage dans la paume, flips.
- **Vidéo en direct** en H.264 matériel (MediaCodec) sur une surface dédiée.
- **Télémétrie** : batterie, hauteur, vitesse, temps de vol, Wi-Fi, avec alertes batterie et luminosité.

## Architecture
```
core/protocol   Kotlin JVM pur : CRC, framing 0xCC, commandes, sticks, télémétrie, réassemblage vidéo
core/control    Kotlin JVM pur : TelloController (session, 50 Hz, retransmissions, watchdog),
                VideoFrameGate, StickMixer, StickShaping, interface ControlTransport
app             Android : Hilt, Compose/Material 3, ViewModel + StateFlow (UDF),
                DroneSockets (UDP), DroneSession, H264Decoder, écran de vol
```
Toute la logique de pilotage se trouve dans `core/*`. Elle est testée sans Android grâce à un faux transport et au temps virtuel des coroutines.

## Latence et sécurité
| Chemin | Mesures |
|---|---|
| Toucher → UDP | Événements tactiles non bufferisés (`requestUnbufferedDispatch`). Le joystick est dessiné en phase draw, sans recomposition. Les sticks passent par une référence atomique, sans StateFlow. L'envoi est immédiat, au plus 10 ms après un mouvement (100 Hz max), avec en plus un maintien à 50 Hz. Thread de contrôle dédié en priorité `URGENT_DISPLAY` (executor planifié, sans saut de thread sur les timers). Socket en DSCP EF. `WifiLock` basse latence. |
| Vidéo | Un seul thread de la réception jusqu'au décodeur. MediaCodec en mode asynchrone avec `KEY_LOW_LATENCY` et priorité temps réel, image affichée dès qu'elle est décodée. Après une perte ou un retard, les images sont jetées jusqu'à la prochaine I-frame, redemandée aussitôt. |
| Sécurité | Les sticks repassent au neutre à la perte de liaison, en pause, au relâchement d'un stick et à l'arrêt (avec envoi de paquets neutres). Le flux de sticks neutres continue pendant la reconnexion. Les commandes acquittées sont retransmises (50 ms × 5). Atterrir reste accessible et est répété même pendant une reconnexion. Le geste retour est bloqué en vol. Une erreur réseau n'arrête jamais l'app : la session redémarre. |

## Versions (novembre 2026)
AGP 9.5.0 · Gradle 9.8.2 · Kotlin 2.4.21 · KSP 2.3.13 · Compose BOM 2026.09.00 · Hilt 2.60.1 · Coroutines 1.11.0 · Lifecycle 2.11.0 · JUnit 6.1.3 · compileSdk/targetSdk 37 · minSdk 30.

## Construire et tester
```bash
./gradlew test                 # tests unitaires (protocole, contrôle, état UI)
./gradlew koverHtmlReportCoverage  # couverture : build/reports/kover/htmlCoverage/index.html
./gradlew :app:assembleDebug   # APK : app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Références
Le protocole a été analysé à partir de l'app officielle (`../official-app/decompiled-java`) et de TelloMe (`../competitor-tellome`). Voir `../research/COMPARAISON_FONCTIONNALITES.md` pour la comparaison des fonctionnalités et la feuille de route.

## Vérifier une release
Chaque release (tag `vX.Y.Z`, workflow `Release`) est construite **une seule fois, sans aucun secret** (APK et AAB non signés, un seul appel Gradle), puis signée hors de Gradle. Elle publie :
- `tello-pilot-vX.Y.Z.apk` : APK signé avec la clé release par `apksigner` (certificat SHA-256 `55:0A:3C:40:C6:49:B3:EA:44:68:17:84:20:05:DE:71:97:4B:62:F9:D5:75:FC:1B:FD:6F:F0:BF:7C:FA:55:A1`, aussi dans `.github/release-signing/apk-signing-cert.sha256`) ;
- `tello-pilot-vX.Y.Z-unsigned.apk` : le même APK, non signé ;
- `tello-pilot-vX.Y.Z.aab` : Android App Bundle pour le Play Store, signé par `jarsigner` avec la **clé d'upload** Play (RSA 4096, `CN=Tello Pilot, O=tt-box`, certificat SHA-256 `80:03:98:D1:88:6B:10:37:6B:70:69:38:97:96:DD:0E:4C:E6:CD:5D:FA:AF:86:C3:D3:55:B2:99:11:EE:20:FC`, certificat et empreinte dans `.github/release-signing/upload-cert.pem` et `upload-cert.sha256`). Google re-signe l'app avec sa propre clé (Play App Signing) ;
- `SHA256SUMS` : empreintes SHA-256 des APK et de l'AAB ;
- `*.sig` : signatures **post-quantiques ML-DSA-65 (FIPS 204)** de chaque APK, de l'AAB et de `SHA256SUMS`.

La clé publique de référence est celle du dépôt, `.github/release-signing/ml-dsa-65.pub.pem` (SHA-256 de la clé en DER : `ab7dc579b67e6888370cf9c138dc4bfaee8809b48d9c308b76c02d539d2fbef6`). Il faut **OpenSSL ≥ 3.5** (`openssl version`) :
```sh
TAG=v0.1.0
gh release download "$TAG" -R tt-box/tello-pilot -D "release-$TAG" && cd "release-$TAG"
cp ../.github/release-signing/ml-dsa-65.pub.pem ref.pub.pem   # depuis un clone du dépôt

# 1. Intégrité des fichiers
sha256sum -c SHA256SUMS
# 2. Signatures ML-DSA-65 (doivent afficher « Signature Verified Successfully »)
openssl pkeyutl -verify -pubin -inkey ref.pub.pem -rawin -in SHA256SUMS -sigfile SHA256SUMS.sig
openssl pkeyutl -verify -pubin -inkey ref.pub.pem -rawin -in "tello-pilot-$TAG.apk" -sigfile "tello-pilot-$TAG.apk.sig"
# 3. Signature Android (Android SDK build-tools) : comparer le « certificate SHA-256 digest »
apksigner verify --print-certs "tello-pilot-$TAG.apk"
# 4. AAB (JDK) : « jar verified », puis comparer l'empreinte SHA256 à upload-cert.sha256
jarsigner -verify "tello-pilot-$TAG.aab" && keytool -printcert -jarfile "tello-pilot-$TAG.aab"
```
Sous Windows, `sha256sum` est disponible dans Git Bash (ou `certutil -hashfile <apk> SHA256`).

## CI/CD
| Workflow | Déclencheur | Rôle |
|---|---|---|
| `ci.yml` | push/PR sur `main` | JDK 17, `./gradlew test :app:lint :app:assembleDebug`, rapports de tests + couverture Kover + APK debug en artefacts ; sur `main`, badge de couverture publié sur la branche orpheline `badges` |
| `codeql.yml` | push/PR, hebdo | Analyse CodeQL java-kotlin (build manuel). Sans GitHub Advanced Security, le SARIF est conservé en artefact et le job échoue sur les alertes de sévérité ≥ 7 (variable `CODE_SCANNING_ENABLED=true` pour téléverser vers Code scanning) |
| `dependencies.yml` | push/PR, hebdo | Soumission du graphe de dépendances Gradle (alertes Dependabot) + OSV-Scanner sur le graphe résolu, échec sur CVSS ≥ 7 |
| `dependabot-automerge.yml` | PR Dependabot, fin de CI | Fusion automatique des mises à jour patch/minor uniquement après une CI verte sur le commit de tête |
| `release.yml` | tag `v*` (dry run : `workflow_dispatch`) | Un seul build Gradle sans secret (APK + AAB non signés), APK signé par `apksigner` (clé release), AAB signé par `jarsigner` (clé d'upload Play), `SHA256SUMS`, signatures ML-DSA-65, GitHub Release |

Secrets (environnement `release`) : `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, `UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `PQ_SIGNING_KEY` (optionnel : `AUTOMERGE_TOKEN`).
