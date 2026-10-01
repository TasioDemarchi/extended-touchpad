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
  - **✕:** cierra el panel. Se vuelve a abrir con el interruptor **Touchpad activado** de la app.
- **Franja inferior:** el **icono de teclado** de la izquierda (el mismo dibujo que el mosaico del teclado; se rellena con el color de acento mientras el teclado está abierto) abre y cierra el teclado a mano; el **asa de la esquina derecha** (solo ella) cambia el tamaño del panel (ancho y alto). El resto de la franja no hace nada. Se recuerda. La velocidad del cursor depende del ancho del panel: más grande es más lento y preciso, más pequeño es más rápido.
- **Teclado:** al enfocar o tocar un campo de texto en el TV se abre solo un teclado flotante en la tablet, venga el foco del touchpad o de un mouse físico (se puede desactivar con el interruptor **Teclado automático** de la app). También se abre a mano con el icono de la franja inferior o con el mosaico **Teclado** del centro de control.

## Atajos en el centro de control

La app incluye dos mosaicos de ajustes rápidos. Se agregan con los botones **Agregar Touchpad** y **Agregar Teclado** de la tarjeta «Atajos en el centro de control» de la app (Android 13+, que además trae el tutorial paso a paso) o editando el centro de control, y se mantienen sincronizados con el panel y la app:

- **Touchpad:** activa y desactiva el touchpad. Muestra Activado, Desactivado, Esperando pantalla externa o Servicio apagado (en ese caso, al tocarlo abre los ajustes de accesibilidad).
- **Teclado:** abre y cierra el teclado. Muestra Abierto, Cerrado, Sin pantalla externa o Servicio apagado. No depende del touchpad.
- **Audio:** cada toque pasa al siguiente dispositivo de salida de audio (ver «Mosaico de audio»).

## Mosaico de audio

Al conectar un monitor o TV, el audio suele salir por sus parlantes. El mosaico **Audio** del centro de control pasa, en cada toque, al **siguiente dispositivo de salida** (parlante interno, monitor HDMI/DisplayPort, auriculares con cable, Bluetooth…) y vuelve a empezar al llegar al final. Se agrega con el botón **Agregar Audio** de la app.

- **Icono del mosaico:** unos auriculares, fijo (el panel de Lenovo no repintaba de forma fiable un icono dinámico); el nombre del dispositivo actual va en el texto del mosaico. En la pantalla de cambio, el icono del dispositivo nuevo es el símbolo **USB** (por cable) o el de **Bluetooth** (inalámbrico), con el color de acento.
- **Aviso:** al cambiar aparece un mensaje corto con el dispositivo elegido. Usa el nombre real si el sistema lo ofrece (Bluetooth, monitor que informa su modelo) y, si no, uno genérico: «Parlante interno», «Monitor HDMI», «Auriculares con cable», «Auriculares USB», «Dispositivo Bluetooth». Los nombres de nodos de hardware como `soc:qcom,msm-ext-disp` se tratan como «no informa nombre».
- **Lista dinámica:** se actualiza sola al conectar o desconectar una salida (`AudioDeviceCallback`).
- **Requiere el servicio de accesibilidad activo.** Sin él, el mosaico abre el selector de audio del sistema para elegir a mano.

**Cómo funciona.** Android no permite a una app normal elegir por dónde sale el audio de otras apps: `setCommunicationDevice` solo afecta a llamadas y elegir el dispositivo de medios exige `MODIFY_AUDIO_ROUTING`, reservado al sistema. Lo que sí permite es abrir el selector nativo (el diálogo «Dispositivos disponibles para salida de audio»; así lo hace también la app *Audio Output Switcher*, que deja elegir al usuario). `AudioOutputSwitcher` abre ese diálogo (`com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG`), lo lee con el servicio de accesibilidad, **toca la fila del siguiente dispositivo como lo haría el usuario** y lo cierra; el cambio real lo hace el sistema. Para que el diálogo no se vea, mientras trabaja por debajo se muestra a pantalla completa una **pantalla oscura** (`AudioCover`) que, al terminar, muestra el icono y el nombre del dispositivo nuevo durante 0,8 s; si no se puede mostrar, el diálogo se ve un instante y el cambio sigue igual. El sondeo es de 40 ms y el diálogo se cierra 300 ms después del toque.

`MediaRouter2` (API pública) no sirve para evitar el diálogo: en la Legion Tab 5 solo expone la ruta «Tablet», no la del monitor HDMI.

- Las filas no tienen identificadores de vista, así que se reconocen por su forma: una fila tocable con el control de volumen es el dispositivo actual; debajo, una fila tocable con un único texto por dispositivo; la última puede ser «Conectar un dispositivo» (se descarta por texto en varios idiomas); y un botón «Listo» para cerrar.
- El diálogo pone el dispositivo actual arriba y reordena tras cada cambio, así que el ciclo usa un **orden propio y estable** (el de primera aparición, guardado en la preferencia `audio_order`).
- El dispositivo actual y su tipo se leen con `AudioManager.getAudioDevicesForAttributes` (API 33, pública).
- Probado en la Legion Tab 5 (ZUI): parlante interno → monitor HDMI → parlante interno. **Depende del diseño del diálogo de Lenovo**: si lo cambian, habría que ajustar el reconocimiento de filas.
- `uiautomator dump` no sirve para depurarlo en esta tablet (vuelca la ventana del TV); usar un volcado propio con `windowsOnAllDisplays`.

## Mantener el servicio activo

Si se fuerza la detención de la app, Android apaga su servicio de accesibilidad y hay que volver a activarlo en Ajustes. Para evitarlo, la app puede reactivarlo sola, pero necesita el permiso `WRITE_SECURE_SETTINGS`, que **solo se concede por ADB, una vez, desde una PC** (con la depuración USB activada y la tablet conectada). La tarjeta **Mantener el servicio activo** de la app lo explica y permite copiar los comandos:

```bash
adb kill-server
adb start-server
adb wait-for-device shell pm grant dev.tasio.extendedtouchpad android.permission.WRITE_SECURE_SETTINGS
```

Reiniciar el servidor de ADB y esperar a la tablet evita el fallo habitual de `no devices/emulators found`: el servidor suele perder el dispositivo si pasa tiempo entre un comando y otro. Si `adb` no está en el PATH, usa la ruta completa (`~/Android/Sdk/platform-tools/adb`).

Con el permiso concedido (la tarjeta pasa a «Permiso concedido» y muestra un interruptor), el servicio se reactiva al **abrir la app**, al **desplegar el centro de control** con un mosaico agregado y al **encender la tablet**. Tras una detención forzada la app no puede ejecutar nada por sí sola hasta una de esas acciones. Sin el permiso la función no hace nada.

## Paneles por debajo del centro de control

Por defecto los paneles son overlays de accesibilidad, que Android dibuja por encima de todo, también del centro de control y de las notificaciones. Con el permiso **«Mostrar sobre otras apps»** (`SYSTEM_ALERT_WINDOW`, se concede desde Ajustes, sin ADB) y el interruptor **Paneles por debajo del centro de control** (activado por defecto) de la app, el panel del touchpad y el teclado usan `TYPE_APPLICATION_OVERLAY`, que Android coloca **por debajo** de la barra de estado, las notificaciones y el centro de control, y que se oculta con la pantalla de bloqueo. El cursor del display externo no cambia (sigue siendo overlay de accesibilidad: el centro de control es de la tablet y no lo tapa).

- Sin el permiso, con el interruptor apagado o si algo falla al crear la ventana, se usa la capa de accesibilidad de siempre (queda anotado en el registro): nunca se pierde el panel.
- Al conceder o retirar el permiso, o cambiar el interruptor, los paneles se reconstruyen solos.
- Implementación en `OverlayLayer.kt`. Un contexto de ventana de tipo overlay solo se puede crear a partir de un contexto asociado a una pantalla: `service.createDisplayContext(display).createWindowContext(type, null)`; pedírselo directamente al servicio lanza `UnsupportedOperationException` y tumba el servicio al conectarse.

## Color de acento

12 colores a elegir (azul, cian, verde azulado, verde, lima, amarillo, naranja, rojo, rosa, violeta, índigo y gris azulado), o el interruptor **Usar el color de la tablet**, que sigue el color de acento del sistema (paleta dinámica *Material You*, `android.R.color.system_accent1_500`, Android 12+). Se vuelve a leer al abrir la app y al encender o desbloquear la tablet, y los paneles se reconstruyen solos si cambia. El acento se usa en las teclas pulsadas, la tecla de mayúsculas activa, el icono de teclado o touchpad activo, el cursor mientras se arrastra y el icono de la pantalla de cambio de audio. Si Lenovo (ZUI) tuviera un color de acento propio distinto del de Android, este ajuste sigue el de Android.

## Tema de la app

La pantalla de ajustes tiene tema **oscuro** (por defecto), **claro** o **del sistema** (sigue el tema claro u oscuro de la tablet), en **Apariencia → Tema de la aplicación**. Es independiente del tema de los paneles. Además, **el color de acento elegido (o el de la tablet) se aplica también a la propia app**: botones, interruptores, deslizadores, marcas y selecciones. El color de texto sobre el acento se elige solo (casi negro con acentos muy claros, como el amarillo). `Palette` (en `Ui.kt`) calcula los colores al pedirlos; al cambiar de tema o de acento, `SettingsActivity.rebuildUi()` repinta la pantalla conservando el scroll. La cortina oscura del cambio de audio no cambia con el tema.

## Imán entre paneles

Con el interruptor **Imantar los paneles** de la app (desactivado por defecto). Con el imán activo, cuando el panel del touchpad y el teclado se acercan a menos de 12 dp por cualquier lado (izquierda, derecha, arriba o abajo) se pegan por el borde, **centrados** sobre el borde compartido, con un deslizamiento corto. El imán solo actúa en el **tramo central de cada lado** (el centro del panel que arrastras debe quedar a ±30 % del largo del lado respecto al centro de ese lado): cerca de las esquinas no se pega por ningún lado, para que no salte de uno a otro. Una vez pegados:

- Si cambia el tamaño de cualquiera de los dos (asa de la esquina, ancho del teclado o fila de números), el acoplado se vuelve a pegar y centrar al instante.
- Arrastrar uno de los dos los separa; se sueltan al alejarse más de 24 dp, y el otro no sigue.
- Se mantienen pegados al reabrir el teclado (las posiciones se guardan) y al girar la tablet. Si ya no caben pegados por ese lado, se desacoplan.
- Con el imán activo no se aplica la colocación que aparta el teclado del panel: se pegan en vez de repelerse.

Los paneles no llevan sombra y su ventana es exactamente su tarjeta, por lo que quedan borde con borde sin superponerse. Constantes: `SNAP_DP` y `RELEASE_DP` en `PanelMagnet.kt`, duración del deslizamiento en `WindowMover.kt`.

## La app

La app es una única pantalla de configuración, oscura y minimalista (grises azulados con acento violeta; paleta en `Ui.kt`), organizada en tarjetas:

- **Primeros pasos:** guía con estado en vivo para activar el servicio de accesibilidad (el único permiso que necesita), con la ayuda de «ajustes restringidos» y el paso de conectar una pantalla externa. Cuando el servicio está activo se reduce a «Listo», con un botón para volver a ver la guía.
- **Atajos en el centro de control:** tutorial de los mosaicos y botones para agregarlos.
- **Touchpad y teclado:** interruptores de touchpad activado, teclado automático e imán entre paneles.
- **Apariencia:** transparencias, tema y acento de los paneles, y cursor (forma, tamaño y color). Se aplica en vivo.
- **Diagnóstico** (plegado): displays detectados, registro y tap de prueba.

## Apariencia

En la app, sección **Apariencia** (la tuerca de los paneles abre la app): transparencia del panel y del teclado, tema claro/oscuro, color de acento y cursor (forma, tamaño y color). Los cambios se aplican en vivo. La transparencia y el tema también se cambian desde el panel y el teclado.

Formas de cursor: Clásico, Contorno, Moderno (cuña de dos tonos), Punto y Mira. En Punto y Mira el clic cae en el centro de la figura; en las flechas, en la punta.

## Teclado

Teclado propio (QWERTY con ñ, mayúsculas, símbolos, borrar con repetición, Enter), flotante y **sin foco**. No depende del touchpad: solo necesita una pantalla externa conectada y la tablet desbloqueada. No usa el teclado del sistema: cada tecla lee el texto del campo del TV y lo reescribe con `ACTION_SET_TEXT`. Se arrastra desde su barra superior. En esa barra, de izquierda a derecha: el título con el texto del campo, **◐** (transparencia), **123** (añade una fila de números sobre las letras; se recuerda), **tuerca** (abre los ajustes de apariencia) y **✕** (cerrar). El ancho mínimo del teclado es de 340 dp para que quepan los iconos y el título. La franja inferior (igual de alta que la del panel del touchpad) lleva a la izquierda el **icono de touchpad** (activa o desactiva el touchpad; se rellena con el acento si está activo) y a la derecha el asa de la esquina, que cambia su tamaño (las teclas crecen con el ancho); el resto de la franja no hace nada.

**Colocación.** Al abrirse, el teclado busca la posición libre más cercana a la habitual que no tape el panel del touchpad (izquierda, derecha, encima, debajo o las esquinas); si no hay espacio, elige la que lo tape menos, nunca por completo. También se aparta solo al terminar de mover o redimensionar el panel, al redimensionar el teclado o al activar el touchpad con el teclado abierto. Si lo arrastras tú encima del panel, se queda donde lo dejes.

- Requiere el permiso de accesibilidad de **leer el contenido de ventanas** (para encontrar el campo enfocado en el TV).
- En campos de contraseña Android no entrega el texto real, así que el teclado lleva su propio registro de lo escrito.
- Sin corrector, dictado ni flechas/Tab. Apps que dibujan su propio campo de texto (algunos juegos) no lo aceptan.
- Mientras el teclado propio está abierto se oculta el teclado del sistema (`SoftKeyboardController`, `SHOW_MODE_HIDDEN`), también en el TV, y se restaura al cerrarlo. El teclado del TV puede asomar ~0,5 s antes de esconderse, porque la orden llega cuando el TV ya empezó a abrirlo. Con el teclado propio abierto tampoco aparece el teclado del sistema en apps de la tablet.

**Apertura automática.** Se dispara por tres señales: foco o clic en un campo editable del TV, un reintento a los 0,2 s (la lista de ventanas del TV puede ir por detrás del evento) y la aparición de la ventana del teclado del sistema en el TV, que cubre los campos que se enfocan solos (por ejemplo la búsqueda de YouTube). Tras cerrar el teclado hay una pausa de 1,5 s para que no se reabra al instante.

Constantes de sensibilidad: `MOVE_SCALE` y `SCROLL_GAIN` en `TouchpadService.kt`, `MAX_ACCEL` en `TouchpadPanel.kt`, tiempos del scroll en `GestureInjector.kt`.

## Bloqueo de pantalla

Los overlays de accesibilidad se dibujan sobre la pantalla de bloqueo, así que con la tablet bloqueada o con la pantalla apagada se ocultan el panel, el teclado y el cursor del TV, y vuelven al desbloquear. El estado se consulta a `KeyguardManager` (con comprobación periódica mientras está bloqueada), porque `ACTION_USER_PRESENT` no llega en todos los dispositivos (no llega en la Legion Tab 5).

Tras un Enter en el teclado, la vista previa se vacía y la siguiente letra empieza un texto nuevo (borrar sigue operando sobre el contenido real). Es un estado "justo después de un Enter" que no depende del campo concreto, porque al lanzar una búsqueda la página se recarga y el campo pasa a ser otro nodo para Android. Termina al escribir, al borrar, al hacer clic en un campo del TV o al cerrar el teclado.

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

La tarjeta **Primeros pasos** de la app lo guía:

1. Abre la app **Extended Touchpad** y pulsa **Abrir ajustes de accesibilidad**.
2. Entra en **Extended Touchpad** (puede estar en "Apps instaladas") y actívalo.
3. Si el interruptor sale bloqueado ("Ajuste restringido"): pulsa **Abrir información de la app** → menú ⋮ (arriba a la derecha) → **Permitir ajustes restringidos**, y vuelve al paso 2. Android 13+ bloquea así los servicios de accesibilidad de APKs instalados a mano.

## Diagnóstico

En la tarjeta plegable **Diagnóstico** de la app: displays detectados (id, resolución, flags), registro y el botón **Tap de prueba en el centro del TV**, que dispara un tap en el centro del display externo. También se puede leer el log por ADB:

```bash
~/Android/Sdk/platform-tools/adb logcat -s ExtTouchpad
```

## Notas técnicas validadas en el dispositivo

- El overlay en el display externo **requiere** `createDisplayContext(display).createWindowContext(TYPE_ACCESSIBILITY_OVERLAY, null)`. Con `createDisplayContext` solo, la ventana queda con las medidas de la tablet.
- `dispatchGesture` con `setDisplayId(<id del display externo>)` y coordenadas locales del display externo llega al TV. Sin `setDisplayId` no reacciona, ni con coordenadas locales ni desplazadas.
- Mandar texto con teclado propio: no usar un `EditText` en la tablet. `ACTION_SET_TEXT` sobre el campo del TV activa su ventana, le quita el foco del sistema a la tablet y cierra el teclado del sistema tras cada carácter.
- `findFocus(FOCUS_INPUT)` deja de ver el campo del TV cuando otra ventana toma el foco; por eso se recuerda el último campo editable que recibió el foco en el display externo (`RemoteInput`).
