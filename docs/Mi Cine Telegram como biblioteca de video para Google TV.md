# Cinebox: Telegram como biblioteca de video para Google TV

Oct 20, 2026 · @ashe

## Visión

Cinebox es una app para Google TV / Android TV que convierte Telegram en una biblioteca de video estilo Netflix. Telegram guarda los videos (gratis e ilimitado); la app solo los organiza, los muestra como catálogo y los reproduce en la TV con el control remoto.

El contenido viene de la cuenta personal del usuario: sus **Mensajes guardados** y sus **canales privados**. Cada canal es una categoría (Películas, Series, Viajes) y los hashtags del mensaje funcionan como géneros o etiquetas.

## Qué es y qué no es

| Sí es | No es |
| --- | --- |
| Un cliente de Telegram para TV, solo para ver contenido | Un cliente de chat completo |
| Un launcher con catálogo, buscador y reproductor | Un servidor propio ni un backend |
| Login con la cuenta de Telegram del usuario (QR) | Un sistema de usuarios propio |
| Para contenido propio del usuario (sus videos y canales) | Una plataforma pública de películas |
| Una app que corre en la TV | Una app que depende del celular como servidor |

La app no guarda videos en ningún servidor propio. Todo vive en Telegram y la app lo lee con la cuenta del usuario.

## Repositorio base y stack

La base es **TDLib** ([github.com/tdlib/td](https://github.com/tdlib/td)), la librería oficial de Telegram para crear clientes. No se usa el fork de la app de Android ([DrKLO/Telegram](https://github.com/DrKLO/Telegram)): su interfaz es para celular táctil y adaptarla al control remoto cuesta más que hacer una app nueva.

| Pieza | Tecnología | Para qué |
| --- | --- | --- |
| Cliente de Telegram | TDLib (Java/JNI para Android) | Login, chats, canales, mensajes, descarga de archivos |
| Lenguaje | Kotlin | Toda la app |
| Interfaz | Jetpack Compose for TV (`androidx.tv:tv-material`) | Pantallas navegables con el control remoto (D-pad) |
| Reproductor | Media3 ExoPlayer (`androidx.media3`) | Reproducir video mientras se descarga |
| Imágenes | Coil | Cargar miniaturas y portadas |
| Datos locales | Room o DataStore | "Seguir viendo" y preferencias |
| Destino | Android TV / Google TV, API 26 o superior | Google TV Streamer, TV box o TV con Google TV |

TDLib se compila para Android siguiendo el ejemplo `example/android` del repo, que genera las librerías `.so` y la clase `TdApi.java`.

## Arquitectura

&#91;embedded content: arquitectura · celular, nube de Telegram y app de TV\]

El celular solo sube y organiza; la app de la TV habla con Telegram por TDLib y reproduce sin intermediarios. La pieza resaltada, el reproductor con `TdDataSource`, es la parte más difícil del proyecto.

## Organización del contenido

El usuario organiza todo desde la app normal de Telegram en su celular; la TV solo lee.

| En Telegram | En la app de TV |
| --- | --- |
| Canal privado (ej. "Películas") | Una fila del catálogo y una pestaña |
| Mensajes guardados | Fila "Guardados" |
| Texto del mensaje (primera línea) | Título del video |
| Hashtags del mensaje (`#accion`, `#viajes`) | Etiquetas y filtros |
| Miniatura del video | Portada |
| Foto enviada justo antes del video (opcional) | Portada personalizada |
| Video del mensaje | Lo que se reproduce |

Qué canales aparecen en la TV se elige en Ajustes de la app: el usuario marca cuáles de sus canales son "bibliotecas".

## Pantallas y funcionalidades

La primera versión (MVP) tiene cinco pantallas; todo lo demás viene después.

**MVP**

1. **Login con QR**: muestra un código QR; el usuario lo escanea desde Telegram en el celular (Ajustes → Dispositivos → Vincular dispositivo). Si la cuenta tiene verificación en dos pasos, pide la contraseña.
2. **Inicio**: arriba "Seguir viendo" con barra de progreso y botones Reanudar / Desde el inicio; abajo una fila horizontal por cada canal-biblioteca y una por Mensajes guardados. Pestañas: Inicio, Películas, Series, Viajes, Fotos.
3. **Buscador**: teclado en pantalla; busca por texto y hashtags dentro de los canales-biblioteca.
4. **Detalle**: portada, título, duración, etiquetas, botón Reproducir.
5. **Reproductor**: pantalla completa, play/pausa, adelantar y retroceder 10 s con el D-pad, barra de progreso con lo ya descargado.

**Después del MVP**

- [ ] Galería de fotos de viajes en pantalla completa (modo presentación)
- [ ] Subtítulos: archivo `.srt` enviado en el mismo canal con el mismo nombre
- [ ] Series: agrupar episodios por hashtag (`#serie_nombre`, `#t1e01`)
- [ ] Descarga completa en segundo plano para ver sin cortes
- [ ] Perfiles y control parental
- [ ] Ajustes: elegir canales-biblioteca, limpiar caché, cerrar sesión

## Detalles técnicos clave

La pieza difícil es el reproductor: ExoPlayer tiene que leer el video mientras TDLib lo descarga por partes. Lo demás son llamadas directas a TDLib.

| Tarea | Llamadas de TDLib |
| --- | --- |
| Inicializar | `setTdlibParameters` con `api_id`, `api_hash`, carpetas de base de datos y archivos, `use_file_database = true` |
| Login con QR | En `authorizationStateWaitPhoneNumber` → `requestQrCodeAuthentication`; en `authorizationStateWaitOtherDeviceConfirmation` se dibuja el `link` como QR; en `authorizationStateWaitPassword` → `checkAuthenticationPassword` |
| Mensajes guardados | `getMe` → `createPrivateChat(me.id)` → id del chat |
| Canales del usuario | `loadChats` / `getChats` en la lista principal, filtrar los canales |
| Videos de un canal | `searchChatMessages` con `filter = searchMessagesFilterVideo`, paginando con `from_message_id` |
| Fotos | Lo mismo con `searchMessagesFilterPhoto` |
| Buscador | `searchChatMessages` con `query` en cada canal-biblioteca |
| Miniaturas | `downloadFile` del `thumbnail` con prioridad baja |
| Avance de descargas | Escuchar `updateFile` |

**Reproducción mientras descarga**

Se implementa un `DataSource` propio de Media3 conectado a TDLib:

1. `open(dataSpec)` llama a `downloadFile(file_id, priority = 32, offset = dataSpec.position, limit = 0, synchronous = false)`.
2. `read()` consulta `getFileDownloadedPrefixSize(file_id, offset)`; si los bytes pedidos no están, espera el próximo `updateFile` de ese archivo.
3. Cuando están, los lee de `file.local.path` con `RandomAccessFile` (o con `readFilePart`).
4. Al adelantar, ExoPlayer vuelve a llamar a `open()` con otra posición y TDLib prioriza esa zona.
5. El buffer de ExoPlayer se configura para ir 30 a 60 s por delante.

**A tener en cuenta**

- Telegram limita la velocidad de descarga a cuentas sin Premium: mostrar en la barra cuánto va descargado.
- Si el índice del MP4 (moov atom) está al final, el primer `open()` pide el final del archivo: el `DataSource` tiene que soportar lecturas fuera de orden.
- "Seguir viendo" se guarda localmente: `chat_id`, `message_id`, posición en ms y fecha.

## Reglas, licencias y restricciones

- **`api_id` y `api_hash` propios**, sacados de [my.telegram.org](https://my.telegram.org). Nunca subirlos al repositorio: van en `local.properties` o variables de entorno.
- **Licencia de TDLib**: Boost Software License 1.0. Permite usarla en una app propia sin tener que publicar el código (a diferencia del fork de Android, que es GPL).
- **Marca**: no usar el nombre "Telegram" ni su logo en el nombre o ícono de la app. Nombre: Cinebox.
- **Contenido protegido**: si un canal tiene activado "Proteger contenido", respetarlo (no permitir descargas completas ni exportar).
- **Términos de la API de Telegram**: no hacer spam ni pedidos masivos; respetar los errores `FLOOD_WAIT` esperando el tiempo indicado.
- **Uso**: pensado para contenido propio del usuario. No es una plataforma para distribuir películas con derechos de autor.

## Estructura del proyecto

Un solo módulo de app en Kotlin, separado por capas. El usuario prepara la carpeta `libs/tdlib/` con TDLib ya compilado.

```
micine/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml          # leanback launcher, sin touchscreen
│       ├── java/com/micine/
│       │   ├── MiCineApp.kt
│       │   ├── MainActivity.kt
│       │   ├── telegram/
│       │   │   ├── TdClient.kt          # wrapper de TDLib con corrutinas
│       │   │   ├── AuthRepository.kt    # estados de login y QR
│       │   │   ├── LibraryRepository.kt # canales, guardados, videos, búsqueda
│       │   │   └── FileRepository.kt    # descargas y updateFile
│       │   ├── player/
│       │   │   ├── TdDataSource.kt      # DataSource de Media3 sobre TDLib
│       │   │   └── PlayerScreen.kt
│       │   ├── data/
│       │   │   └── WatchProgressDao.kt  # "Seguir viendo" (Room)
│       │   └── ui/
│       │       ├── login/LoginScreen.kt
│       │       ├── home/HomeScreen.kt
│       │       ├── search/SearchScreen.kt
│       │       ├── detail/DetailScreen.kt
│       │       ├── settings/SettingsScreen.kt
│       │       └── theme/Theme.kt
│       └── res/
├── libs/tdlib/                          # .so por ABI + TdApi.java + Client.java
├── local.properties                     # TG_API_ID, TG_API_HASH (no se sube)
└── settings.gradle.kts
```

## Instrucciones para la IA

Este documento es el contexto del proyecto. Al trabajar en él:

1. Usa el stack de este documento (Kotlin, TDLib, Compose for TV, Media3). No propongas el fork de DrKLO/Telegram ni otros clientes.
2. Avanza por fases y entrega archivos completos, no fragmentos:
   1. Proyecto base que compila con TDLib conectado.
   2. Login con QR hasta `authorizationStateReady`.
   3. Inicio con filas de canales y Mensajes guardados, solo videos, con miniaturas.
   4. Reproductor con `TdDataSource` (reproducir mientras descarga y adelantar).
   5. Buscador, detalle y "Seguir viendo".
3. Toda la interfaz se maneja con D-pad: foco visible en cada elemento, sin gestos táctiles.
4. Todas las llamadas a TDLib pasan por `TdClient.kt` con corrutinas; ninguna pantalla llama a TDLib directo.
5. No escribas `api_id` ni `api_hash` en el código: léelos de `BuildConfig` desde `local.properties`.
6. Al terminar cada fase, explica en pasos cortos cómo compilar, instalar en la TV (`adb install`) y probar.
7. Si algo de TDLib no está claro, consulta la documentación de `td_api.tl` del repo en vez de inventar nombres de métodos.
