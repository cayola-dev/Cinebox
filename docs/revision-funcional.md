# Revisión funcional de Cinebox

Prioridad: funcionamiento y consumo de recursos. La reproducción de medios todavía
no existe en la fase 0; no se han medido tiempos de inicio, buffering ni velocidad de descarga.

## Correcciones de la base

- Los estados y las llamadas se procesan secuencialmente fuera del hilo de interfaz.
  Antes, una corrutina independiente por actualización podía reordenar estados.
- Las actualizaciones ajenas a la autorización se descartan antes de crear tareas.
  En fases siguientes se incorporarán solo las actualizaciones de biblioteca y archivos necesarios.
- Al dejar la actividad se solicita `Close`, que guarda y cierra la base de TDLib.
  No se usa `LogOut`. Al volver durante el cierre se espera `AuthorizationStateClosed`
  antes de crear otro cliente; las respuestas antiguas no alteran el nuevo cliente.
- Un fallo al cargar la biblioteca ya no deja el arranque bloqueado permanentemente.
- Los errores `FLOOD_WAIT` y `retry after` bloquean nuevos arranques dentro del plazo
  indicado mientras el proceso vive. No hay reintentos automáticos. El plazo no se
  persiste todavía si Android termina el proceso.
- Se conserva la base de archivos y chats, pero no una caché persistente general de
  mensajes. El catálogo consultará los canales elegidos bajo demanda.

## Pruebas al terminar la instalación del emulador

La imagen Android TV API 36 **x86** corresponde a `lib/x86` del APK; no requiere x86_64.

1. Instalar y abrir Cinebox: esperar `AuthorizationStateWaitPhoneNumber` en Logcat.
2. Pulsar Home: esperar `AuthorizationStateClosing` / `AuthorizationStateClosed`.
3. Volver a la app, también rápidamente mientras cierra: debe conectar sin errores
   de bloqueo de base de datos ni dos instancias simultáneas.
4. Probar sin red y recuperarla. Registrar errores y tiempos; no asumir que compilar
   demuestra conexión real con Telegram.

## Verificación de medios cuando exista el reproductor

Siguiendo el alcance ya definido en los documentos del proyecto:

- Leer solo los bytes descargados contiguos desde el desplazamiento solicitado;
  `downloaded_size` total no indica que una región concreta esté disponible.
- Esperar cambios del archivo, sin bucles que consuman CPU, y despertar también al
  cancelar, cerrar o recibir un error de descarga.
- Adelantar debe priorizar el nuevo desplazamiento; probar MP4 con índice al final,
  búsquedas hacia atrás y archivos grandes usando posiciones de 64 bits.
- Separar la prioridad del video activo de las miniaturas. Cancelar trabajo que ya
  no necesita la pantalla y paginar los listados.
- Medir memoria, espacio libre y crecimiento de caché. No ejecutar una limpieza
  automática que pueda borrar el archivo abierto por el reproductor.
- Validar formatos y rendimiento en la TV real: el emulador sirve para el flujo,
  pero no demuestra la capacidad del decodificador ni el rendimiento de esa TV.

Estas condiciones están pendientes de implementación y medición; no son funciones
que la fase 0 ya proporcione.
