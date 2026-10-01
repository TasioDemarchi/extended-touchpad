# Touchpad virtual para pantalla externa (Android, sin root)

## Contexto
- Dispositivo: Lenovo Legion Tab 5, Android 16 (API 36). Sin root, bootloader bloqueado.
- La tablet se usa en **modo tablet con pantalla extendida** (el modo escritorio de Lenovo solo permite mirror, no sirve).
- El monitor/TV externo es un display independiente con su propio launcher y teclado virtual. Sin mouse físico no se puede operar.
- Caso de uso: viajar solo con la tablet, conectarla al TV del hotel, abrir una app (Netflix, YouTube) y reproducir una película, sin cargar un mouse.

## Objetivo
App Android que dibuje un cursor en el display externo y lo controle desde un touchpad flotante en la pantalla de la tablet.

## Restricciones
- Sin root, sin Shizuku/ADB como requisito (puede evaluarse como respaldo).
- No se puede crear un mouse HID del sistema, así que el cursor es un overlay propio.

## Arquitectura
- `AccessibilityService` con `canPerformGestures="true"`.
- Detección del display externo con `DisplayManager` (display con `displayId != Display.DEFAULT_DISPLAY`). Reaccionar a conexión y desconexión con `DisplayListener`.
- **Cursor:** overlay creado con `createDisplayContext(externalDisplay)` + `WindowManager`, tipo `TYPE_ACCESSIBILITY_OVERLAY`, flags `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`.
- **Clic y scroll:** `dispatchGesture` con `GestureDescription.Builder().setDisplayId(externalDisplayId)` (API 32+).
- **Touchpad:** overlay en el display de la tablet (también accesibilidad), flotante y movible.

## Fase 1: prototipo de validación (hacer esto primero)
Objetivo: confirmar que los gestos llegan al display externo.
1. Servicio de accesibilidad declarado y activable desde Ajustes.
2. Detectar el display externo y mostrar su resolución en una pantalla de estado.
3. Overlay de un círculo visible en el centro del display externo.
4. Un botón en la tablet que dispare un tap con `setDisplayId` en ese punto.
5. **Criterio de éxito:** al presionar el botón, algo reacciona en el monitor (se abre o selecciona un ícono del launcher).
6. Si falla: probar variantes (sin `setDisplayId` + coordenadas absolutas, y como respaldo `input -d <displayId> tap` vía ADB inalámbrico/Shizuku) y reportar qué funcionó.

## Fase 2: touchpad completo (solo si la Fase 1 funciona)
Funciones:
- **Mover cursor:** un dedo desplazándose sobre el touchpad mueve el cursor de forma relativa (con aceleración simple), limitado a los bordes del display externo.
- **Clic:** tap de un dedo en el touchpad = tap en la posición actual del cursor.
- **Scroll:** dos dedos desplazándose verticalmente = gesto de scroll en el display externo.

Fuera de alcance (decidido):
- Arrastre (drag and drop).
- Lanzador de apps propio (el launcher del monitor ya muestra todas las apps).
- Teclado remoto: por ahora fuera. Queda como posible mejora futura, porque el teclado virtual se abre en el monitor y es incómodo.

## Requisitos de UI del panel
- Minimalista y **flotante**: no es una app a pantalla completa. Debe poder seguir viendo y usando la pantalla de la tablet.
- Recuadro gris chico con sombra, para que se note que es parte de la app.
- Movible por la pantalla de la tablet (arrastrando desde un borde o un asa).
- Tamaño razonable (referencia: alrededor de un cuarto del ancho de la pantalla), ajustable más adelante.
- El panel no debe capturar los toques fuera de su área.

## Stack
- Kotlin. Preferencia: Views para los overlays (más control con `WindowManager`); Compose solo si aporta algo en la pantalla de configuración.
- `minSdk` 32, `targetSdk` 36.

## Entregable
- Proyecto Android Studio/Gradle que compile desde línea de comandos (`./gradlew assembleDebug`) y genere el APK de debug.
- Instrucciones breves para instalar el APK en la tablet y activar el servicio de accesibilidad.

## Riesgos conocidos
1. Que `dispatchGesture` con `setDisplayId` no llegue al display externo en esta tablet (riesgo principal, se valida en la Fase 1).
2. Apps o pantallas protegidas (DRM, pantallas de seguridad) que ignoren los gestos de accesibilidad.
3. Latencia y fluidez del cursor inferiores a las de un mouse real: aceptable para el caso de uso.
