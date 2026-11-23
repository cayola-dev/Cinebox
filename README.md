# Cinebox

Biblioteca personal para Android TV / Google TV, en Kotlin, Compose for TV, TDLib y Media3.

## Implementación v3

La interfaz nativa toma como referencia `desing/handoff/mi-cine-para-android-tv/project/Mi Cine v3.dc.html` y su `support.js`. No utiliza WebView ni el catálogo simulado del prototipo.

- Inicio, Videos, Series, Música y Explorar; búsqueda y ajustes conservados.
- Portadas con transición direccional y desplazamiento lento, entrada escalonada, foco ampliado y controles con animación.
- Exploración de videos, fotos y audios por biblioteca, con páginas de ocho archivos y carga incremental.
- Series agrupadas por `#serie_nombre`; episodios ordenados por `#t1e01` o `S01E01` cuando están presentes.
- Música con tocadiscos animado, favoritos, conteos locales, cola y minirreproductor al volver al catálogo. Al abrir un video se libera el reproductor de audio y se restaura después si estaba reproduciendo. Abrir Ajustes detiene la música para permitir limpiar la caché.
- Progreso de video persistido en Room por cuenta; reanudar, empezar desde cero y Seguir viendo. Se guarda cada cinco segundos y al salir. Los últimos diez segundos se consideran completados.
- Streaming por ventanas acotadas de TDLib, controles de ±10 segundos, anterior/siguiente y búfer real de Media3. Las miniaturas se reducen a un máximo de 640 píxeles y su caché en memoria tiene un límite de 24 MiB.
- QR y contraseña 2FA, sesión persistente, canales seleccionables y mensajes patrocinados.

Las cifras de Explorar describen los archivos cargados, no el total remoto. Favoritos, progreso y reproducciones son locales a esta TV; no se sincronizan con otros dispositivos. Al recuperar el historial se resuelven hasta 40 mensajes recientes mediante TDLib; no se guardan identificadores de archivo entre sesiones ni se descarga todo el historial.

## Preparación

1. Instala Android Studio, JDK 21 y Android SDK 36.1.
2. Ejecuta `powershell -ExecutionPolicy Bypass -File scripts/setup-tdlib.ps1` si faltan los binarios nativos.
3. Configura `TG_API_ID` y `TG_API_HASH` en `.env`, `local.properties` o variables de entorno. Precedencia: entorno > local.properties > .env.
4. Configura `sdk.dir` en `local.properties`.

## Compilar e instalar

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle"
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n com.ashe.cinemabox/.MainActivity
```

La APK está en `app/build/outputs/apk/debug/app-debug.apk`. `install -r` conserva los datos de una instalación compatible. Las credenciales se incorporan a BuildConfig y no quedan ocultas frente a una inspección de la APK.

## Probar

1. Vincula el QR desde Telegram → Ajustes → Dispositivos. Completa la contraseña si hay 2FA.
2. Elige canales en Ajustes. Guardados siempre está disponible.
3. Con el D-pad, prueba las cinco pestañas, búsqueda, una foto y las páginas de Explorar.
4. Reproduce un video, avanza, vuelve y comprueba Reanudar y Seguir viendo después de reiniciar.
5. Reproduce música, vuelve a Inicio, pausa desde el minirreproductor, marca un favorito y abre un video para comprobar el cambio entre reproductores.

Las pruebas unitarias cubren conexión, espera de Telegram, velocidad, reanudación y clasificación de series. La prueba instrumentada `PlaybackHistoryTest` comprueba persistencia, favoritos, conteos y separación de cuentas.

Ejecuta `connectedDebugAndroidTest` **solo en un emulador de pruebas desechable**: el runner puede desinstalar la aplicación al terminar y borrar la sesión local. Para pruebas manuales en una TV vinculada, utiliza `install -r`.

## Publicación

Para detalles sobre cómo firmar la aplicación y subirla a la Google Play Store, consulta la [Guía de Publicación](PUBLISHING.md). La política de privacidad requerida ya está disponible en `docs/privacy.html` (y en línea a través de GitHub Pages).

## Dependencias y licencias

TDLib Java/JNI: [TGX-Android/tdlib](https://github.com/TGX-Android/tdlib), revisión `b47cd822037a4c38916f0b78f57298f10a88821e`, con OpenSSL. No incluye la interfaz de Telegram X. Antes de distribuir, validar este bundle o sustituirlo por una compilación reproducible de TDLib oficial. Licencia en `libs/tdlib/LICENCE`.

ABI: arm64-v8a, armeabi-v7a, x86 y x86_64; minSdk 26. La compilación comprueba los ELF y empaqueta libc++_shared.so. Room y KSP generan la base local; documentación de [Room](https://developer.android.com/jetpack/androidx/releases/room).

Manrope y Urbanist se empaquetan localmente; licencias OFL en `licenses/`.

## Pulido visual

Explorar muestra únicamente los canales activados en Ajustes; Mensajes guardados sigue disponible en Inicio. Las tarjetas utilizan la foto del canal, con iniciales como alternativa, y el fondo cambia con una transición suave. Al reiniciar se recuperan los canales guardados cargando su lista en TDLib cuando sea necesario (incluidos los archivados). Los fallos de recuperación se muestran con una acción para reintentar y no borran la selección.

Los audios sin portada usan una ilustración vectorial local, con colores estables por título, tanto en las tarjetas como en búsqueda, fondo y tocadiscos. El catálogo alinea la fila enfocada y conserva margen para el título; las tarjetas musicales son cuadradas. El foco usa escala suave y un indicador blanco. Las entradas animan opacidad y desplazamiento sin desenfoque por fotograma. El fondo espera 90 ms antes de cambiar al navegar rápidamente, conserva como máximo dos capas durante la transición y libera la anterior al terminar. No se anima el fondo oculto de Música o Explorar.

