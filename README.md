# Extended Touchpad

Touchpad virtual para pantalla externa (Android, sin root). Requisitos completos en [REQUERIMIENTO.md](REQUERIMIENTO.md).

**Estado: validado en una Lenovo Legion Tab 5 (Android 16) con un TV por HDMI.**
Cursor en el display externo, panel flotante, mover, clic, scroll con dos dedos, arrastre y teclado funcionan, también al rotar la tablet.

## Uso

Al conectar el HDMI en modo extendido, el cursor aparece en el TV y el panel gris en la tablet. Al desconectarlo desaparecen solos.

- **Un dedo:** mueve el cursor (con aceleración simple).
- **Tap con un dedo:** clic en la posición del cursor.
- **Dos dedos en vertical:** scroll (un único trazo continuo en el TV).
- **Tap y, sin soltar, apoyar de nuevo y mover (dentro de ~300 ms):** arrastre. El cursor se pone azul. Manteniéndolo quieto antes de mover se activa el long-press de la app (por ejemplo, para mover íconos del launcher).
- **Barra superior del panel:** arrastrar para moverlo; la **X** lo cierra. Se vuelve a abrir con **Activar touchpad** en la app.
- **Teclado:** al enfocar o tocar un campo de texto en el TV se abre solo un teclado flotante en la tablet (se puede desactivar con **Desactivar teclado automático** en la app). También se abre a mano con el **icono de teclado** a la izquierda de la barra del panel.

## Teclado

Teclado propio (QWERTY con ñ, mayúsculas, símbolos, borrar con repetición, Enter), flotante y **sin foco**. No usa el teclado del sistema: cada tecla lee el texto del campo del TV y lo reescribe con `ACTION_SET_TEXT`. Se arrastra desde su barra superior; la ✕ lo cierra.

- Requiere el permiso de accesibilidad de **leer el contenido de ventanas** (para encontrar el campo enfocado en el TV).
- En campos de contraseña Android no entrega el texto real, así que el teclado lleva su propio registro de lo escrito.
- Sin corrector, dictado ni flechas/Tab. Apps que dibujan su propio campo de texto (algunos juegos) no lo aceptan.
- Mientras el teclado propio está abierto se oculta el teclado del sistema (`SoftKeyboardController`, `SHOW_MODE_HIDDEN`), también en el TV, y se restaura al cerrarlo. El teclado del TV puede asomar ~0,5 s antes de esconderse, porque la orden llega cuando el TV ya empezó a abrirlo. Con el teclado propio abierto tampoco aparece el teclado del sistema en apps de la tablet.

Constantes de sensibilidad: `MOVE_SCALE` y `SCROLL_GAIN` en `TouchpadService.kt`, `MAX_ACCEL` en `TouchpadPanel.kt`, tiempos del scroll en `GestureInjector.kt`.

## Bloqueo de pantalla

Los overlays de accesibilidad se dibujan sobre la pantalla de bloqueo, así que con la tablet bloqueada o con la pantalla apagada se ocultan el panel, el teclado y el cursor del TV, y vuelven al desbloquear. El estado se consulta a `KeyguardManager` (con comprobación periódica mientras está bloqueada), porque `ACTION_USER_PRESENT` no llega en todos los dispositivos (no llega en la Legion Tab 5).

Tras un Enter en el teclado, la vista previa se vacía y la siguiente letra empieza un texto nuevo en ese campo (borrar sigue operando sobre el contenido real).

## Limitaciones conocidas

- Fuera de alcance por ahora: lanzador de apps propio, clic derecho y teclas de navegación.
- Apps o pantallas protegidas (DRM, pantallas de seguridad) pueden ignorar los gestos de accesibilidad.
- Con un teclado de la tablet (el del sistema) no es posible: cada escritura en el TV le quita el foco y lo cierra. Por eso se usa un teclado propio sin foco.
- Un margen transparente de ~10 dp alrededor del panel (para la sombra) captura toques.

## Compilar

Requiere JDK 17+ y el Android SDK (plataforma 36). La ruta del SDK está en `local.properties` (ignorado por git).

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Instalar en la tablet

Con USB o con Depuración inalámbrica activada en la tablet (Opciones de desarrollador):

```bash
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Activar el servicio de accesibilidad

1. Abre la app **Extended Touchpad** y pulsa **Abrir ajustes de accesibilidad**.
2. Entra en **Extended Touchpad** (puede estar en "Apps instaladas") y actívalo.
3. Si el interruptor sale bloqueado ("Ajuste restringido"): Ajustes → Apps → Extended Touchpad → menú ⋮ (arriba a la derecha) → **Permitir ajustes restringidos**, y vuelve al paso 2. Android 13+ bloquea así los servicios de accesibilidad de APKs instalados a mano.

## Diagnóstico

La app muestra los displays detectados (id, resolución, flags), el estado del servicio y un registro. El botón **Tap de prueba** dispara un tap en el centro del display externo. También se puede leer el log por ADB:

```bash
~/Android/Sdk/platform-tools/adb logcat -s ExtTouchpad
```

## Notas técnicas validadas en el dispositivo

- El overlay en el display externo **requiere** `createDisplayContext(display).createWindowContext(TYPE_ACCESSIBILITY_OVERLAY, null)`. Con `createDisplayContext` solo, la ventana queda con las medidas de la tablet.
- `dispatchGesture` con `setDisplayId(<id del display externo>)` y coordenadas locales del display externo llega al TV. Sin `setDisplayId` no reacciona, ni con coordenadas locales ni desplazadas.
- Mandar texto con teclado propio: no usar un `EditText` en la tablet. `ACTION_SET_TEXT` sobre el campo del TV activa su ventana, le quita el foco del sistema a la tablet y cierra el teclado del sistema tras cada carácter.
- `findFocus(FOCUS_INPUT)` deja de ver el campo del TV cuando otra ventana toma el foco; por eso se recuerda el último campo editable que recibió el foco en el display externo (`RemoteInput`).
