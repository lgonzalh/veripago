# VeriPago — v1.0 alfa

VeriPago organiza cobros QR, pedidos y confirmaciones para Nequi, Daviplata y Bre-B. El dinero se transfiere directamente al comercio. La aplicación web funciona bajo `/VeriPago/` y adapta las herramientas al perfil autenticado con Google.

## Estado del producto

- **Datos:** reglas iniciales de Firestore y Storage, índices, esquema Firestore/Room y contrato de conciliación.
- **Web:** landing, autenticación por Google, vista pública de pago por slug, checkout con seguimiento, paneles iniciales para comercios, compradores y administradores.
- **Servidor:** Cloud Functions para reservar slugs, crear pedidos con precios calculados en el servidor, limitar pedidos públicos por IP y reflejar estados en sesiones de checkout.
- **Android:** app nativa inicial con Compose, perfil Firebase, pedidos, conciliación local de notificaciones, Room y sincronización diferida. APK de depuración generado; falta validación funcional en dispositivo y configuración Firebase de desarrollo.
- **Despliegue y QA integral:** pendiente. Los QR personales de `assets/` se mantienen locales; no subirlos al repositorio.

## Desarrollo web

Requiere Node.js 20 o posterior y pnpm. Copia `.env.example` a `.env.local` y completa la configuración de una app Firebase de desarrollo. Habilita Google como proveedor de Firebase Authentication y crea Firestore y Storage en el mismo proyecto.

```powershell
pnpm install --ignore-scripts
pnpm build
pnpm test
pnpm dev
```

## Android

Requiere JDK 17 y Android SDK 35. Define `sdk.dir` en `local.properties` y configura `firebaseApiKey`, `firebaseAppId`, `firebaseProjectId`, `firebaseStorageBucket` y `webClientId` en ese archivo para habilitar Firebase y Google Sign-In. No se incluyen credenciales en Git.

```powershell
.\gradlew.bat assembleDebug
```

El APK de depuración se genera en `app/build/outputs/apk/debug/app-debug.apk`.

La aplicación local abre en `http://localhost:5173/VeriPago/`. El build se genera en `dist/VeriPago/` con los recursos apuntando a `/VeriPago/`.

## Cloud Functions

Las funciones están en `functions/`, requieren Node.js 22 y se despliegan en `us-central1`.

```powershell
npm ci --prefix functions
npm run build --prefix functions
firebase emulators:start --only firestore,storage,functions --project demo-veripago
```

Antes de usar datos reales, valida las reglas en los emuladores y configura Firebase App Check para limitar abuso de los endpoints públicos. Las reglas automáticas de Nequi y Daviplata vienen desactivadas hasta validarlas con ejemplos anonimizados de notificaciones vigentes.

## Firebase y alojamiento

`firebase.json` configura Firestore, Storage, Functions y emuladores. No incluye todavía un destino de Hosting: el repositorio principal de Lantonium y su sitio de Hosting no están en este checkout. Firebase Hosting reemplaza la publicación completa de un sitio, así que la ruta `/VeriPago/` debe integrarse con el artefacto del sitio principal antes de publicar; el build actual sí queda preparado en el subdirectorio `dist/VeriPago/`.

## Datos públicos y privacidad

- `public_payment_links/{slug}` permite buscar un enlace por ID sin habilitar la enumeración del catálogo.
- Los compradores anónimos reciben un ID aleatorio de sesión que solo expone el estado y el total. Los nombres, teléfonos, productos y pedidos completos permanecen en `orders`.
- Los precios se vuelven a calcular en Cloud Functions desde el catálogo del enlace.
- Nunca se guardan claves bancarias ni textos de notificación sin enmascarar.
- Las carpetas `assets/QR_*` contienen códigos de pago y deben permanecer fuera de GitHub.
