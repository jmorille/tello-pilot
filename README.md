# Tello Pilot

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
./gradlew :app:assembleDebug   # APK : app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Références
Le protocole a été analysé à partir de l'app officielle (`../decompiled_src`) et de TelloMe (`../tellome`). Voir `../analysis/COMPARAISON_FONCTIONNALITES.md` pour la comparaison des fonctionnalités et la feuille de route.
