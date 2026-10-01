# Extended Touchpad

Touchpad virtual para pantalla externa (Android, sin root). Requisitos completos en [REQUERIMIENTO.md](REQUERIMIENTO.md).

**Estado: validado en una Lenovo Legion Tab 5 (Android 16) con un TV por HDMI.**
Cursor en el display externo, panel flotante redimensionable, mover, clic, scroll con dos dedos, arrastre, teclado y ajustes de apariencia funcionan, también al rotar la tablet.

## Uso

Al conectar el HDMI en modo extendido, el cursor aparece en el TV y el panel gris en la tablet. Al desconectarlo desaparecen solos.

- **Un dedo:** mueve el cursor (con aceleración simple).
- **Tap con un dedo:** clic en la posición del cursor.
- **Dos dedos en vertical:** scroll (un único trazo continuo en el TV).
- **Tap y, sin soltar, apoyar de nuevo y mover (dentro de ~300 ms):** arrastre. El cursor se pone azul. Manteniéndolo quieto antes de mover se activa el long-press de la app (por ejemplo, para mover íconos del launcher).
- **Barra superior del panel**, de izquierda a derecha:
  - **◐ Transparencia:** la barra se transforma en un deslizador (20–100 %) con el valor en pantalla. Se cierra sola a los 3 s sin tocarla (el contador se detiene mientras la usas) o al tocar el ◐.
  - **Luna / sol:** alterna entre tema claro y oscuro.
  - **Puntos:** arrastrar para mover el panel.
  - **Tuerca:** abre los ajustes de apariencia de la app.
  - **✕:** cierra el panel. Se vuelve a abrir con **Activar touchpad** en la app.
- **Franja inferior:** el **icono de teclado** de la izquierda (el mismo dibujo que el mosaico del teclado; se rellena con el color de acento mientras el teclado está abierto) abre y cierra el teclado a mano; el **asa de la esquina derecha** (solo ella) cambia el tamaño del panel (ancho y alto). El resto de la franja no hace nada. Se recuerda. La velocidad del cursor depende del ancho del panel: más grande es más lento y preciso, más pequeño es más rápido.
- **Teclado:** al enfocar o tocar un campo de texto en el TV se abre solo un teclado flotante en la tablet, venga el foco del touchpad o de un mouse físico (se puede desactivar con **Desactivar teclado automático** en la app). También se abre a mano con el icono de la franja inferior o con el mosaico **Teclado** del centro de control.

## Atajos en el centro de control

La app incluye dos mosaicos de ajustes rápidos. Se agregan con los botones **Agregar atajo del touchpad/teclado al centro de control** de la app (Android 13+) o editando el centro de control, y se mantienen sincronizados con el panel y la app:

- **Touchpad:** activa y desactiva el touchpad. Muestra Activado, Desactivado, Esperando pantalla externa o Servicio apagado (en ese caso, al tocarlo abre los ajustes de accesibilidad).
- **Teclado:** abre y cierra el teclado. Muestra Abierto, Cerrado, Sin pantalla externa o Servicio apagado. No depende del touchpad.

## Imán entre paneles

En **Apariencia → Imantar los paneles** (desactivado por defecto). Con el imán activo, cuando el panel del touchpad y el teclado se acercan a menos de 12 dp por cualquier lado (izquierda, derecha, arriba o abajo) se pegan por el borde, **centrados** sobre el borde compartido, con un deslizamiento corto. Una vez pegados:

- Si cambia el tamaño de cualquiera de los dos (asa de la esquina, ancho del teclado o fila de números), el acoplado se vuelve a pegar y centrar al instante.
- Arrastrar uno de los dos los separa; se sueltan al alejarse más de 24 dp, y el otro no sigue.
- Se mantienen pegados al reabrir el teclado (las posiciones se guardan) y al girar la tablet. Si ya no caben pegados por ese lado, se desacoplan.
- Con el imán activo no se aplica la colocación que aparta el teclado del panel: se pegan en vez de repelerse.

Los paneles no llevan sombra y su ventana es exactamente su tarjeta, por lo que quedan borde con borde sin superponerse. Constantes: `SNAP_DP` y `RELEASE_DP` en `PanelMagnet.kt`, duración del deslizamiento en `WindowMover.kt`.

## Apariencia

En la app, **Apariencia** (o la tuerca del panel): transparencia del panel y del teclado, tema claro/oscuro, color de acento y cursor (forma, tamaño y color). Los cambios se aplican en vivo. La transparencia y el tema también se cambian desde el panel y el teclado.

Formas de cursor: Clásico, Contorno, Moderno (cuña de dos tonos), Punto y Mira. En Punto y Mira el clic cae en el centro de la figura; en las flechas, en la punta.

## Teclado

Teclado propio (QWERTY con ñ, mayúsculas, símbolos, borrar con repetición, Enter), flotante y **sin foco**. No depende del touchpad: solo necesita una pantalla externa conectada y la tablet desbloqueada. No usa el teclado del sistema: cada tecla lee el texto del campo del TV y lo reescribe con `ACTION_SET_TEXT`. Se arrastra desde su barra superior. En esa barra, de izquierda a derecha: el título con el texto del campo, **touchpad** (activa o desactiva el touchpad; se rellena con el acento si está activo), **◐** (transparencia), **123** (añade una fila de números sobre las letras; se recuerda), **tuerca** (abre los ajustes de apariencia) y **✕** (cerrar). El ancho mínimo del teclado es de 340 dp para que quepan los iconos y el título. El asa de la esquina inferior derecha cambia su tamaño (las teclas crecen con el ancho).

**Colocación.** Al abrirse, el teclado busca la posición libre más cercana a la habitual que no tape el panel del touchpad (izquierda, derecha, encima, debajo o las esquinas); si no hay espacio, elige la que lo tape menos, nunca por completo. También se aparta solo al terminar de mover o redimensionar el panel, al redimensionar el teclado o al activar el touchpad con el teclado abierto. Si lo arrastras tú encima del panel, se queda donde lo dejes.

- Requiere el permiso de accesibilidad de **leer el contenido de ventanas** (para encontrar el campo enfocado en el TV).
- En campos de contraseña Android no entrega el texto real, así que el teclado lleva su propio registro de lo escrito.
- Sin corrector, dictado ni flechas/Tab. Apps que dibujan su propio campo de texto (algunos juegos) no lo aceptan.
- Mientras el teclado propio está abierto se oculta el teclado del sistema (`SoftKeyboardController`, `SHOW_MODE_HIDDEN`), también en el TV, y se restaura al cerrarlo. El teclado del TV puede asomar ~0,5 s antes de esconderse, porque la orden llega cuando el TV ya empezó a abrirlo. Con el teclado propio abierto tampoco aparece el teclado del sistema en apps de la tablet.

**Apertura automática.** Se dispara por tres señales: foco o clic en un campo editable del TV, un reintento a los 0,2 s (la lista de ventanas del TV puede ir por detrás del evento) y la aparición de la ventana del teclado del sistema en el TV, que cubre los campos que se enfocan solos (por ejemplo la búsqueda de YouTube). Tras cerrar el teclado hay una pausa de 1,5 s para que no se reabra al instante.

Constantes de sensibilidad: `MOVE_SCALE` y `SCROLL_GAIN` en `TouchpadService.kt`, `MAX_ACCEL` en `TouchpadPanel.kt`, tiempos del scroll en `GestureInjector.kt`.

## Bloqueo de pantalla

Los overlays de accesibilidad se dibujan sobre la pantalla de bloqueo, así que con la tablet bloqueada o con la pantalla apagada se ocultan el panel, el teclado y el cursor del TV, y vuelven al desbloquear. El estado se consulta a `KeyguardManager` (con comprobación periódica mientras está bloqueada), porque `ACTION_USER_PRESENT` no llega en todos los dispositivos (no llega en la Legion Tab 5).

Tras un Enter en el teclado, la vista previa se vacía y la siguiente letra empieza un texto nuevo en ese campo (borrar sigue operando sobre el contenido real).

## Limitaciones conocidas

- Fuera de alcance por ahora: lanzador de apps propio, clic derecho y teclas de navegación.
- Apps o pantallas protegidas (DRM, pantallas de seguridad) pueden ignorar los gestos de accesibilidad.
- Con un teclado de la tablet (el del sistema) no es posible: cada escritura en el TV le quita el foco y lo cierra. Por eso se usa un teclado propio sin foco.

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
