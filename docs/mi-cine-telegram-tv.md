# Cinebox: Telegram como biblioteca de video para Google TV

Fecha: 2026-10-20 · Autor: Josue Zensano

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

```
┌──────────────────────────┐
│ Celular                  │
│ App oficial de Telegram: │
│ sube y organiza videos   │
└────────────┬─────────────┘
             │
             ▼
┌──────────────────────────┐        ┌───────────────────────────────────────────────┐
│ Nube de Telegram         │        │ Cinebox en Google TV                          │
│ Canales privados         │        │ ┌───────────────────────────────────────────┐ │
│ Mensajes guardados       │        │ │ Pantallas (Compose for TV)                │ │
└────────────┬─────────────┘        │ │ Login QR, Inicio, Buscador, Detalle,      │ │
             │                      │ │ Reproductor                               │ │
             │                      │ └──────────┬─────────────────────┬──────────┘ │
             │                      │            ▼                     ▼            │
             │                      │ ┌────────────────────┐ ┌────────────────────┐ │
             │                      │ │ Repositorios       │ │ Reproductor        │ │
             │                      │ │ TdClient, Auth,    │ │ ExoPlayer +        │ │
             │                      │ │ Library y File     │ │ TdDataSource       │ │
             │                      │ └─────────┬──────────┘ └─────────┬──────────┘ │
             │                      │           ▼                      ▼            │
             │      MTProto         │ ┌───────────────────────────────────────────┐ │
             └─────────────────────►│ │ TDLib                                     │ │
                                    │ │ Login, mensajes y descarga por partes     │ │
                                    │ │ con caché en la TV                        │ │
                                    │ └───────────────────────────────────────────┘ │
                                    └───────────────────────────────────────────────┘
```

El celular solo sube y organiza; la app de la TV habla con Telegram por TDLib y reproduce sin intermediarios. El reproductor con `TdDataSource` es la parte más difícil del proyecto.

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
- [ ] Ajustes: limpiar caché y cerrar sesión (elegir canales y "Acerca de" ya existen)

## Login y sesión

La TV inicia sesión una sola vez con un QR y la sesión queda guardada; no hay chats ni notificaciones, solo la biblioteca.

**Flujo**

1. Se abre la app en la TV y, si no hay sesión, aparece el QR.
2. El usuario lo escanea desde Telegram en el celular (Ajustes → Dispositivos → Vincular dispositivo).
3. Si la cuenta tiene verificación en dos pasos, la TV pide la contraseña.
4. La sesión queda iniciada y la app va directo a Inicio (la biblioteca).
5. Las siguientes veces la app entra directo, sin QR.

**Dónde se guarda la sesión**

- TDLib guarda la sesión en su base de datos (`database_directory`), dentro del almacenamiento interno de la app.
- Sobrevive a cerrar la app, apagar la TV y reiniciar.
- En el celular, la TV aparece en Ajustes → Dispositivos como una sesión activa, con el nombre definido en `setTdlibParameters`:
    - `device_model = "Cinebox TV"`
    - `application_version` = versión de la app
    - `system_version` = versión de Android TV

**Cuándo se cierra la sesión**

| Causa | Quién la provoca | Qué hace la app |
| --- | --- | --- |
| Terminar sesión desde el celular (Dispositivos) | Usuario | Detecta el cambio de estado y vuelve a mostrar el QR |
| Botón "Cerrar sesión" en Ajustes de la app | Usuario | Llama a `logOut` y muestra el QR |
| Inactividad (según Ajustes → Dispositivos → Cerrar sesiones inactivas: 1 semana a 1 año) | Telegram | Detecta el cambio de estado y muestra el QR |
| Borrar datos o desinstalar la app | Usuario | Al abrir no hay sesión: muestra el QR |

Mientras la TV se use al menos una vez dentro del plazo de inactividad, la sesión no se cierra nunca.

**Manejo de estados en la app**

- Escuchar siempre `updateAuthorizationState`.
- `authorizationStateReady` → ir a Inicio.
- `authorizationStateWaitPhoneNumber` → pedir `requestQrCodeAuthentication` y mostrar la pantalla de QR.
- `authorizationStateWaitOtherDeviceConfirmation` → dibujar el `link` como QR; si el QR expira, TDLib manda un `link` nuevo y se redibuja.
- `authorizationStateWaitPassword` → pantalla de contraseña, `checkAuthenticationPassword`.
- `authorizationStateLoggingOut` / `authorizationStateClosed` → crear un cliente TDLib nuevo y volver al QR.
- La app nunca pide número de teléfono ni código por SMS: el único login es el QR.

**Notificaciones**

- No se programan notificaciones ni se registra el dispositivo para push (`registerDevice` no se usa).
- TDLib recibe mensajes nuevos mientras la app está abierta: se ignoran todos, salvo los de canales-biblioteca y Mensajes guardados, que actualizan el catálogo en vivo cuando el usuario sube un video.
- Abrir un video o audio para reproducirlo lo marca como visto, como en la app oficial (ver "Cumplimiento de términos de Telegram"). Los mensajes que solo aparecen listados no se marcan.

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
| Marcar como visto al abrir | `openChat` → `viewMessages(chat_id, [message_id], messageSourceChatHistory, force_read = false)`; al salir, `closeChat` |
| Patrocinados | `getChatSponsoredMessages`; vista con `viewMessages` del id del patrocinado; `clickChatSponsoredMessage`; `reportChatSponsoredMessage` |
| Caché | `getStorageStatistics` y `optimizeStorage` con tope de tamaño, solo sin video abierto |

**Reproducción mientras descarga**

Se implementa un `DataSource` propio de Media3 conectado a TDLib:

1. `open(dataSpec)` reutiliza lo que ya está en caché (`getFileDownloadedPrefixSize`) y pide solo lo que falta con `downloadFile(file_id, priority = 32, offset, limit, synchronous = false)`. Nunca se usa `limit = 0`: el primer tramo es pequeño (2 MB o 5 s) y después una ventana de 15 s del video (entre 4 y 32 MB) por delante de la lectura, que se renueva cuando quedan menos de 5 s.
2. `read()` lee los bytes contiguos ya descargados desde `file.local.path` con `RandomAccessFile`; si no hay, espera el próximo `updateFile` de ese archivo.
3. Al adelantar, ExoPlayer vuelve a llamar a `open()` con otra posición y la nueva ventana reemplaza a la anterior.
4. El buffer de ExoPlayer (mínimo 20 s, máximo 60 s, 2 s para arrancar, 5 s tras un corte) es lo que limita cuánto se descarga: en pausa o con el buffer lleno, la descarga se detiene.
5. Al salir del video: `cancelDownloadFile(file_id, only_if_pending = false)` y recorte de caché (tope 5 GB).

**A tener en cuenta**

- Telegram limita la velocidad de descarga a cuentas sin Premium: mostrar en la barra cuánto va descargado.
- Si el índice del MP4 (moov atom) está al final, el primer `open()` pide el final del archivo: el `DataSource` tiene que soportar lecturas fuera de orden.
- "Seguir viendo" se guarda localmente: `chat_id`, `message_id`, posición en ms y fecha.

## Música: identificación con AcoustID

Al reproducir una canción por primera vez, Cinebox intenta reconocerla para mostrar título, artista, álbum y portada.

**Flujo** (`MusicIdentifier`)

1. Busca la canción en Room (`MusicMetadata`, clave = `remote.unique_id` del archivo de TDLib). Si ya existe, la usa sin analizar.
2. Si el interruptor "Identificar canciones automáticamente" (Ajustes → Música, activado por defecto) está apagado, termina con los datos del archivo o del mensaje.
3. Muestra "Analizando…" sobre la forma de onda y espera a que la reproducción haya descargado los primeros 120 s (no pide descargas extra).
4. `Fingerprinter` decodifica esos 120 s con `MediaExtractor` + `MediaCodec` y calcula la huella con Chromaprint (JNI `cineboxfp`).
5. `AcoustIdClient` consulta `https://api.acoustid.org/v2/lookup` (`meta = recordings releasegroups compress`) y toma el resultado de mayor score; con score < 0.8 no hay coincidencia.
6. Si encuentra: descarga `https://coverartarchive.org/release-group/{id}/front-250` a `filesDir/covers/`, guarda en Room con `source = "acoustid"` y el reproductor cambia los datos con un fundido de 300 ms.
7. Si AcoustID no la reconoce: guarda el intento (`source = "archivo"` si el MP3 tiene etiquetas, `"mensaje"` si no) para no volver a analizarla.
8. Sin red, timeout, respuesta inesperada o archivo aún incompleto: no guarda nada y lo reintenta la próxima vez que suene.

**Prioridad de datos:** AcoustID > etiquetas del archivo (`title`, `performer` de TDLib) > primera línea del mensaje > nombre del archivo.
Portada: AcoustID > portada incrustada en el MP3 (leída por ExoPlayer) > miniatura de Telegram.

**Reglas**

- Solo se analiza la canción que suena; cambiar de canción cancela el análisis anterior.
- Nunca se editan mensajes ni se sube audio: solo viaja la huella (texto) a AcoustID.
- Máximo 3 consultas por segundo, timeout de 10 s, User-Agent `CineBox/1.0 (uso personal)`.
- "Volver a identificar" (Ajustes) borra los intentos con `source` distinto de `"acoustid"`.
- La clave `ACOUSTID_KEY` vive fuera del código (`.env` o `local.properties`) y llega por `BuildConfig`.
- Chromaprint incluye código LGPL 2.1 (resample de FFmpeg); revisar la licencia antes de distribuir la app.

## Reglas, licencias y restricciones

- **`api_id` y `api_hash` propios**, sacados de [my.telegram.org](https://my.telegram.org). Nunca subirlos al repositorio: van en `local.properties` o variables de entorno.
- **Licencia de TDLib**: Boost Software License 1.0. Permite usarla en una app propia sin tener que publicar el código (a diferencia del fork de Android, que es GPL).
- **Marca**: no usar el nombre "Telegram" ni su logo en el nombre o ícono de la app. Nombre: Cinebox.
- **Contenido protegido**: si un canal tiene activado "Proteger contenido", respetarlo (no permitir descargas completas ni exportar).
- **Términos de la API de Telegram**: obligatorios; ver la sección siguiente.
- **Uso**: pensado para contenido propio del usuario. No es una plataforma para distribuir películas con derechos de autor.

## Cumplimiento de términos de Telegram

Fuente: [core.telegram.org/api/terms](https://core.telegram.org/api/terms). Tiene prioridad sobre cualquier fase.

1. **Marcar como visto (regla 1.4, sin "modo fantasma").** Al abrir un video o audio para reproducirlo, la app llama a `openChat`, luego a `viewMessages(chat_id, [message_id], messageSourceChatHistory, force_read = false)`, y a `closeChat` al salir. Así cuenta la vista en el canal. No se marcan los mensajes listados en Inicio ni en el buscador. No se oculta el estado en línea ni se tocan las confirmaciones de lectura o "escribiendo".
2. **Mensajes patrocinados (regla 3.3).** Cada fila de canal pide `getChatSponsoredMessages(chat_id)` al cargarse. Si hay, el primero se muestra como tarjeta en la 2.ª posición con:
    - etiqueta "Patrocinado" ("Recomendado" si `is_recommended`), título, texto completo, imagen y texto del botón;
    - el medio del anuncio se descarga completo antes de mostrarlo (lo exige TDLib).
    - **Vista:** `viewMessages(chat_id, [id del patrocinado])` cuando la tarjeta está entera en pantalla, una vez por anuncio y sesión. `viewSponsoredMessage` no existe en nuestra versión de TDLib; su documentación indica usar `viewMessages`.
    - **Clic:** `clickChatSponsoredMessage(chat_id, message_id, is_media_click = false, from_fullscreen = false)` y pantalla con el QR del enlace (canal o bot de Telegram, o web).
    - **Reporte:** "Reportar anuncio" con `reportChatSponsoredMessage`, mostrando las opciones que devuelva TDLib.
    - Nunca se ocultan, saltan, bloquean ni filtran, y no hay ajuste para desactivarlos. Si la lista viene vacía, como suele pasar en canales privados, la fila funciona igual.
3. **Transparencia (reglas 2.2–2.4).** Login y Ajustes → Acerca de muestran: "Cinebox es una app no oficial que usa la API de Telegram. No está afiliada a Telegram." Acerca de añade el nombre, la versión y "Esta app no contiene publicidad propia ni cobros." "Telegram" solo aparece en avisos e instrucciones; nunca como nombre, ícono, banner o logo. `device_model = "Cinebox TV"`.
4. **IA (regla 1.5).** El contenido de Telegram no se usa para entrenar, ajustar ni desarrollar modelos de IA. La app no incluye funciones de IA.
5. **Uso responsable de la API.** Todos los pedidos respetan `FLOOD_WAIT`: la biblioteca rechaza localmente los pedidos mientras dura la espera, y el reproductor reintenta solo cuando se cumple el plazo. Las filas piden 30 mensajes (máximo 50) solo cuando el foco llega al final de la fila; no se carga ningún historial completo en segundo plano. El buscador lanzará la búsqueda con un debounce de 500 ms.

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
