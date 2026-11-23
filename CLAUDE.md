# CLAUDE.md — Instrucciones de trabajo para la IA

> Este archivo dice **cómo** trabajar en el proyecto.
> El detalle técnico (stack, llamadas de TDLib, estructura, pantallas) está en `mi-cine-telegram-tv.md`. Léelo completo antes de empezar.

---

## 1. Objetivo (una sola frase)

Construir una app para **Google TV / Android TV** que inicia sesión en Telegram con un **QR** y muestra los videos de los **canales privados y Mensajes guardados** del usuario como un **catálogo estilo Netflix**, reproduciéndolos en la TV **mientras se descargan**.

Nombre: **Cinebox**. Package: `com.ashe.cinemabox`.

### Cómo se ve el éxito

El usuario enciende la TV, abre la app, la sesión ya está iniciada, navega con el control remoto por filas de videos, elige uno y empieza a verse en pocos segundos, sin celular, sin servidor propio y sin notificaciones.

---

## 2. Qué SÍ hay que construir (alcance)

1. Login **solo con QR** (más contraseña de verificación en dos pasos si la cuenta la tiene).
2. Sesión persistente: después del primer login, la app entra directo.
3. Pantalla de **Inicio** con:
   - "Seguir viendo".
   - Una fila por cada canal-biblioteca.
   - Una fila de Mensajes guardados.
4. **Buscador** por texto y hashtags dentro de los canales-biblioteca.
5. **Detalle** de video: portada, título, duración, etiquetas, botón Reproducir.
6. **Reproductor** con streaming mientras descarga (`TdDataSource` + ExoPlayer), con adelantar y retroceder.
7. **Ajustes**: elegir canales-biblioteca, limpiar caché, cerrar sesión.
8. Navegación **100% con D-pad** (control remoto).

---

## 3. Qué NO hay que hacer (fuera de alcance)

No implementes, no propongas y no prepares código "por si acaso" para nada de esto:

- ❌ Chats, enviar mensajes, responder o reaccionar.
- ❌ "Modo fantasma": no ocultar el estado en línea ni tocar confirmaciones de lectura o indicadores de "escribiendo". Abrir un video sí lo marca como visto (ver sección 5.1).
- ❌ Ocultar, saltar, bloquear o filtrar mensajes patrocinados, o agregar un ajuste para desactivarlos.
- ❌ Usar contenido de Telegram para entrenar, ajustar o desarrollar modelos de IA.
- ❌ Notificaciones, push, `registerDevice`, Firebase o servicios en segundo plano.
- ❌ Login por número de teléfono o código SMS. **El único login es QR.**
- ❌ Servidor propio, backend, base de datos remota o API propia.
- ❌ Sistema de usuarios o cuentas propio.
- ❌ Versión para celular o tablet, ni interfaz táctil.
- ❌ Enviar a la TV desde el celular (Cast o DLNA): la app corre **en** la TV.
- ❌ Usar el fork `DrKLO/Telegram` u otro cliente como base.
- ❌ Usar el nombre o el logo de Telegram en la app.
- ❌ Saltarse "Proteger contenido" de un canal (no permitir exportar ni descargar completo esos videos).
- ❌ Funciones de distribución pública de contenido (compartir links, catálogos públicos).
- ❌ Funciones de la sección "Después del MVP" del documento técnico, hasta que el usuario lo pida.

Si una tarea parece necesitar algo de esta lista, **detente y pregunta**.

---

## 4. Stack fijo (no cambiar sin preguntar)

| Pieza | Tecnología |
| --- | --- |
| Lenguaje | Kotlin |
| Cliente Telegram | TDLib (ya compilado en `libs/tdlib/`) |
| Interfaz | Jetpack Compose for TV (`androidx.tv:tv-material`) |
| Reproductor | Media3 ExoPlayer |
| Imágenes | Coil |
| Persistencia local | Room |
| Asincronía | Corrutinas + Flow |
| Build | Gradle Kotlin DSL (`build.gradle.kts`) |
| minSdk | 26 |
| HTTP (solo AcoustID y Cover Art Archive) | OkHttp |
| JSON | kotlinx.serialization |
| Huella de audio | Chromaprint 1.5.1 (NDK + CMake, `FFT_LIB=kissfft`, sin FFmpeg) con puente JNI `cineboxfp` |
| Decodificación de audio | `MediaExtractor` + `MediaCodec` de Android |

No agregues dependencias nuevas sin explicar por qué y esperar el OK.

---

## 5. Reglas de trabajo

1. **Una fase a la vez.** No empieces la siguiente hasta que el usuario confirme que la actual funciona.
2. **Archivos completos.** Entrega cada archivo entero, listo para copiar y pegar. Nada de fragmentos sueltos ni "… resto igual …".
3. **Indica la ruta** de cada archivo antes del código (ej. `app/src/main/java/com/micine/telegram/TdClient.kt`).
4. **Toda llamada a TDLib pasa por `TdClient.kt`.** Ninguna pantalla ni ViewModel llama a TDLib directo.
5. **No inventes métodos de TDLib.** Si no estás seguro de un nombre o parámetro, dilo y pide revisar `td_api.tl`.
6. **Secretos:** `api_id` y `api_hash` se leen de `local.properties` vía `BuildConfig`. Nunca escritos en el código.
7. **Foco visible** en todo elemento navegable. Si el foco no se ve, la pantalla no está terminada.
8. **Errores claros:** cualquier error de TDLib se muestra en pantalla con un mensaje entendible. Nunca un crash silencioso.
9. **`FLOOD_WAIT`:** espera el tiempo que indica el error. No reintentes en bucle.
10. **Sin sobre-ingeniería:** nada de módulos extra, inyección de dependencias compleja o capas que el MVP no necesita. Hilt solo si el usuario lo pide.
11. **Explica corto:** al final de cada entrega, pasos numerados para compilar, instalar (`adb install`) y probar.
12. **Si algo no está claro, pregunta** antes de escribir código.

---

## 5.1 Cumplimiento de los Términos de la API de Telegram (obligatorio)

Fuente: https://core.telegram.org/api/terms. Estas reglas tienen prioridad sobre cualquier fase. Detalle en `docs/mi-cine-telegram-tv.md`, sección "Cumplimiento de términos de Telegram".

1. **Marcar como visto (regla 1.4).** Al **abrir** un video o audio para reproducirlo: `openChat(chat_id)` → `viewMessages(chat_id, [message_id], messageSourceChatHistory, force_read = false)`; al salir, `closeChat`. Nunca marcar los mensajes que solo aparecen listados en Inicio ni en el buscador.
2. **Mensajes patrocinados (regla 3.3).** Cada fila de un canal llama a `getChatSponsoredMessages(chat_id)` al cargarse y muestra el primero en la 2.ª posición con la etiqueta "Patrocinado" (o "Recomendado" si `is_recommended`), título, texto completo, imagen si la trae y el texto del botón. El medio del anuncio se descarga completo antes de mostrarlo.
   - Vista: `viewMessages(chat_id, [id del patrocinado])` cuando la tarjeta entera está en pantalla, una vez por anuncio y sesión. (`viewSponsoredMessage` no existe en nuestra TDLib.)
   - Clic: `clickChatSponsoredMessage(chat_id, message_id, is_media_click = false, from_fullscreen = false)` y pantalla con QR del enlace.
   - Reporte: botón "Reportar anuncio" con `reportChatSponsoredMessage`, mostrando las opciones que devuelva TDLib.
   - Una lista vacía no es un error. Nunca ocultarlos ni ofrecer desactivarlos.
3. **Transparencia (reglas 2.2–2.4).** Aviso "Cinebox es una app no oficial que usa la API de Telegram. No está afiliada a Telegram." en el login y en Ajustes → Acerca de, junto con "Esta app no contiene publicidad propia ni cobros." "Telegram" solo puede aparecer en avisos e instrucciones, nunca como nombre, ícono o logo de la app. `device_model = "Cinebox TV"`.
4. **IA (regla 1.5).** Ningún contenido de Telegram se usa para entrenar, ajustar o desarrollar modelos de IA.
5. **Uso responsable.** Respetar `FLOOD_WAIT` en todos los pedidos (esperar lo indicado, sin reintentos en bucle). Filas: máximo 50 mensajes por pedido y solo al llegar al final de la fila; nunca cargar historiales completos en segundo plano. Búsquedas con debounce de 500 ms.

---

## 6. Hoja de ruta

Cada fase tiene un entregable y un criterio de "listo". Una fase está terminada **solo** si cumple su criterio en el emulador de Google TV o en una TV real.

### Fase 0 — Proyecto base con TDLib

- **Entregable:** proyecto Android TV que compila con TDLib enlazado; `TdClient.kt` inicializa TDLib con `setTdlibParameters` y escribe en el log el estado de autorización.
- **Listo cuando:** la app abre en el emulador y el log muestra `authorizationStateWaitPhoneNumber`.

### Fase 1 — Login con QR y sesión

- **Entregable:** `AuthRepository.kt`, `LoginScreen.kt` (QR + contraseña 2FA) y la navegación según el estado de autorización.
- **Listo cuando:**
  - Se escanea el QR desde el celular y la app llega a `authorizationStateReady`.
  - Al cerrar y reabrir la app, entra directo, sin QR.
  - Al terminar la sesión desde el celular (Dispositivos), la app vuelve sola al QR.
  - La sesión aparece en el celular como **"Cinebox TV"**.

### Fase 2 — Inicio con filas de videos

- **Entregable:** `LibraryRepository.kt`, `HomeScreen.kt` con una fila por canal y una fila de Mensajes guardados, solo videos, con miniaturas. En esta fase los canales-biblioteca se eligen en una pantalla simple de Ajustes.
- **Listo cuando:** se ven las filas con portadas y títulos reales, se navega con el D-pad y el foco siempre es visible. Las filas cargan más videos al llegar al final (paginación).

### Fase 3 — Reproductor con streaming

- **Entregable:** `TdDataSource.kt`, `FileRepository.kt` y `PlayerScreen.kt`.
- **Listo cuando:**
  - Un video de 1 hora empieza a reproducirse sin descargarlo completo.
  - Adelantar 10 minutos funciona (TDLib prioriza esa zona).
  - La barra muestra lo que ya está descargado.
  - Volver atrás detiene la descarga de ese video.

### Fase 4 — Seguir viendo, detalle y buscador

- **Entregable:** `WatchProgressDao.kt` (Room), `DetailScreen.kt` y `SearchScreen.kt`.
- **Listo cuando:**
  - Al salir de un video y volver, aparece en "Seguir viendo" con su progreso y se reanuda en el segundo correcto.
  - El buscador encuentra por texto y por hashtag.

### Fase 5 — Ajustes y pulido

- **Entregable:** `SettingsScreen.kt` completa (canales-biblioteca, limpiar caché, cerrar sesión), pantallas de error y de carga, ícono y banner de Android TV.
- **Listo cuando:** la app se instala en una TV real, aparece en el launcher de Google TV con su banner y todo el flujo funciona de punta a punta sin el celular (salvo el QR inicial).

### Fase 6 — Música: identificación con AcoustID

- **6A — Huella:** `cpp/CMakeLists.txt`, `cpp/chromaprint_jni.cpp`, `music/Fingerprinter.kt` (primeros 120 s a PCM, huella comprimida).
- **6B — Consulta:** `music/AcoustIdClient.kt` (lookup con score ≥ 0.8, portada `front-250` de Cover Art Archive, máx. 3 consultas/s, timeout 10 s).
- **6C — Integración:** `MusicMetadata.kt`, `MusicMetadataDao.kt` (Room, clave `remote.unique_id`), `MusicIdentifier.kt`, efecto "Analizando…" en el reproductor e interruptor en Ajustes.
- **Listo cuando:**
  - Una canción publicada muestra portada, título, artista y álbum en unos segundos.
  - Una grabación propia muestra los datos de su mensaje, sin ningún error visible.
  - La segunda vez que suena una canción no hay análisis ni llamadas a AcoustID (log `Cinebox.Identify`).
  - Con el interruptor apagado no hay ninguna llamada de red a AcoustID.
- **Reglas:** solo se analiza la canción que se está reproduciendo; nunca se editan mensajes ni se sube audio (solo la huella). `ACOUSTID_KEY` se lee de `.env`/`local.properties` vía `BuildConfig`.

---

## 7. Definición de terminado (MVP)

El MVP está terminado cuando, en una TV real con Google TV:

- [ ] El login con QR funciona y la sesión se conserva.
- [ ] El Inicio muestra los canales-biblioteca y Mensajes guardados con portadas.
- [ ] Un video empieza en pocos segundos y se puede adelantar.
- [ ] "Seguir viendo" recuerda el progreso.
- [ ] El buscador funciona.
- [ ] Todo se maneja con el control remoto.
- [ ] No hay notificaciones, chats ni login por teléfono.
- [ ] `api_id` y `api_hash` no están en el código ni en el repositorio.
- [ ] Los patrocinados aparecen en canales públicos y se registran vistas y clics.
- [ ] Abrir un video lo marca como visto.
- [ ] El aviso "app no oficial" aparece en el login y en Acerca de.

---

## 8. Cómo reportar cada entrega

Al terminar una tarea, responde con este formato:

```
Fase: N — nombre
Archivos creados/modificados:
- ruta/Archivo.kt (nuevo | modificado)
Qué hace: 1–3 líneas.
Cómo probar:
1. ...
2. ...
Pendiente / dudas:
- ...
```

---

## 9. Si te bloqueas

- **Error de compilación de TDLib:** muestra el error exacto y propone una sola causa probable. No reescribas el proyecto.
- **No sabes un método de TDLib:** dilo y pide revisar `td_api.tl`.
- **La tarea choca con la sección 3 (fuera de alcance):** detente y pregunta.
- **Hay dos caminos posibles:** describe ambos en 2 líneas cada uno, recomienda uno y espera la decisión.
