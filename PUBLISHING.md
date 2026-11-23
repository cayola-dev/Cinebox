# Guía de Publicación en Google Play Store - Cinebox

Esta guía detalla los pasos finales para publicar Cinebox en la Google Play Store para Android TV.

## 1. Preparación de Identidad
*   **Nombre de la App:** Cinebox (o el nombre comercial que elijas).
*   **Categoría:** Aplicación / Entretenimiento.
*   **Público objetivo:** 13+ o 18+ (dependiendo del contenido que el usuario suela ver en Telegram).
*   **URL de Política de Privacidad (Requerido):** `https://cayola-dev.github.io/Cinebox/privacy.html`

## 2. Firma de la Aplicación (Keystore)
Google Play requiere que la app esté firmada. Sigue estos pasos en Android Studio:
1. Ve a **Build > Generate Signed Bundle / APK...**
2. Selecciona **Android App Bundle**.
3. Haz clic en **Create new...** bajo "Key store path".
4. Guarda el archivo `.jks` en un lugar seguro (fuera de esta carpeta del proyecto).
5. **IMPORTANTE:** Añade las credenciales a tu archivo `local.properties` o `.env` (que están en el `.gitignore`):
    ```properties
    CINEBOX_KEYSTORE_PATH=/ruta/a/tu/keystore.jks
    CINEBOX_KEYSTORE_PASSWORD=tu_password
    CINEBOX_KEY_ALIAS=tu_alias
    CINEBOX_KEY_PASSWORD=tu_password_de_llave
    ```

## 3. Generar el Bundle (.aab)
Ejecuta este comando en la terminal para generar el archivo que subirás a la consola:
```powershell
./gradlew :app:bundleRelease
```
El archivo se generará en: `app/build/outputs/bundle/release/app-release.aab`.

## 4. Material Gráfico Requerido
Prepara los siguientes archivos para la ficha de Play Store:
*   **Icono de la app:** 512 x 512 px (PNG/JPG).
*   **Gráfico de funciones:** 1024 x 500 px (PNG/JPG).
*   **Banner de Android TV (Obligatorio):** 320 x 180 px (Ya tienes uno en `app/src/main/res/drawable-nodpi/cinebox_banner.png`).
*   **Capturas de pantalla (TV):** Al menos 3 capturas en 1920x1080 o superior.

## 5. Checklist de la Google Play Console
1. **Configuración de la app:** Completa las declaraciones de anuncios (di que no tiene anuncios propios), acceso a la app (proporciona instrucciones de login si es necesario) y clasificación de contenido.
2. **Android TV:** En la sección de "Configuración avanzada", asegúrate de añadir el formato "Android TV".
3. **Subir el Bundle:** Crea un nuevo lanzamiento en la rama "Producción" y sube el archivo `.aab`.
4. **Revisión:** Google revisará que la interfaz sea navegable con D-pad y que el banner se vea bien.

---
*Nota: Recuerda que Google revisará la app manualmente. Asegúrate de que el login por QR funcione correctamente antes de enviar a revisión.*
