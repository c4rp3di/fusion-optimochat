# OptimoChat (fase 0)

App Android independiente (`com.optimo.chat`), compañera de Optimo. Esta versión **0.0.1 no es un chat**: es la
pantalla de pruebas de viabilidad de la fase 0 de la especificación técnica. Sin dependencias (solo APIs del
sistema), misma versión de Gradle/AGP/Kotlin que Optimo y misma mecánica de firma con Fusion.

> **Estado:**
> - **Compilado y probado en la JVM** (`pruebas/jvm/probar.sh`): `Bencode`, `Dht` (sin red y contra un nodo DHT falso en loopback), `Puerto`/`Demux` (sockets UDP reales), `GestorConexion`, `QrCodigo`, `AlmacenCifrado`,
>   `Informe`, `SondaNat` (identificador de encuentro), `Invitacion`, `UdpFiable`, `Confianza`, `TlsCanal`, `Protocolo`, `AlmacenMensajes` y `Chat`. Incluye vectores del RFC 4648
>   (base32) y del RFC 5869 (HKDF). `UdpFiable` se prueba en una red simulada con pérdida, duplicados y reordenación
>   (hasta 45 % de pérdida): no sustituye a la prueba en red real.
> - **Sin compilar ni probar:** `Identidad`, `EstadoRed`, `TlsFijado`, `FilasSqlite`, `ClaveAlmacen` y `MainActivity`, porque usan APIs de Android
>   y en el entorno de redacción no hay SDK. Hay que compilar en Fusion y corregir lo que salga.
> - **Sin probar en red real:** la parte de red de `Dht` y el punching. Es justo lo que mide la fase 0.

## Pruebas (botones de la pantalla)

| Botón | Prueba | Qué comprueba |
|---|---|---|
| Identidad | P1 | Crea la clave EC P-256 y el certificado en Keystore; muestra la huella |
| Red | P2 | Tipo de red, IPv4, IPv6 global, VPN. Los cambios (Wi-Fi ↔ datos) se anotan solos con su hora |
| NAT | P4 | Pregunta desde un solo socket a varios nodos DHT y compara el puerto externo: estable o simétrico |
| Encuentro + punching | P3 y P5 | Anuncia y busca en la DHT con un secreto de prueba y lanza ráfagas UDP al otro móvil |
| TLS servidor / cliente | P1 | TLS mutuo con huella fijada, por TCP. Debe fallar si la huella no coincide |
| Copiar informe | — | Copia el informe con las IPs enmascaradas (`83.45.*.*:5000`) |

P6 (mantenimiento y batería) y P7 (cambio de red con conexión abierta) se hacen a mano con estas pantallas: ver abajo.

## Cómo probar

1. Instalar en dos móviles. En cada uno: **Identidad** y **Red**.
2. **P1 (TLS), en la misma Wi-Fi:** en el móvil A pulsar *TLS servidor* (sin huella: acepta a cualquiera y anota la del cliente).
   En el móvil B escribir la IP de A y la huella de A, y pulsar *TLS cliente*. Debe salir `OK`.
   Repetir con una huella cambiada en un dígito: debe salir `RECHAZADO`. Probar en Android 8-9 y en 10 o superior si hay móviles de ambas.
3. **P4:** pulsar *NAT* en cada móvil y en cada red (Wi-Fi de casa, datos de cada operador).
4. **P5:** escribir el mismo secreto en los dos móviles y pulsar *Encuentro + punching* en los dos a la vez (en menos de un minuto de diferencia).
   Repetir para cada pareja de redes: misma Wi-Fi, Wi-Fi distinta, Wi-Fi con datos, datos con datos (operadores distintos). Mínimo 10 intentos por pareja.
5. **P7:** con la pantalla de *Red* abierta, activar y desactivar el Wi-Fi. Cada cambio debe aparecer en el registro en menos de 2 s.
6. **P6 (pendiente de herramienta):** el intervalo de latido y la batería con pantalla apagada necesitan una prueba de larga duración
   que se añadirá después de ver los resultados de P4 y P5. Mientras tanto, medir con `adb shell dumpsys batterystats`.
7. **Copiar informe** en cada móvil y juntar los resultados.

## Criterio de continuidad (propuesta)

Continuar con la fase 1 si el punching tiene éxito en más del 50 % de los intentos en la mayoría de las parejas de redes.
Si no, los nodos de ayuda pasan a ser requisito de la versión 1.

## Estructura

`app/src/main/java/com/optimo/chat/`

- `Identidad.kt` — clave y certificado en Keystore, huella.
- `EstadoRed.kt` — tipo de red, IPv6, VPN y cambios.
- `Bencode.kt` — codificación de la DHT (lógica pura, probable en la JVM).
- `Dht.kt` — cliente DHT: consultas, búsqueda iterativa, anuncio. Comparte socket con el transporte.
- `SondaNat.kt` — clasificación de NAT y prueba de encuentro con punching.
- `TlsFijado.kt` — TLS mutuo con confianza fijada por huella.
- `Informe.kt` — enmascarado de IPs.
- `Invitacion.kt` — (fase 1, lógica pura) enlace `optimo://invitar`, prueba de emparejamiento, código de 6 dígitos y HKDF.
  Aún no está conectada a la interfaz.
- `UdpFiable.kt` — (fase 2, lógica pura) flujo fiable y ordenado sobre UDP: ventana deslizante, SACK, RTO, congestión,
  latidos y cierre ordenado. Incluye las interfaces `Canal` y `Datagramas` (`DatagramasUdp` solo vale con socket exclusivo).
- `Puerto.kt` — `Puerto` y `Demux`: UN socket UDP compartido. Un hilo reparte los paquetes del transporte (primer byte 0xA?) a la
  cola de su remoto (`canal(remoto)`) y el resto (DHT, sondas de punching) a `recibir()`. Limita los remotos sin registrar (32).
  Probado en loopback: 500 KB por sentido, tráfico mezclado, paquetes tempranos, spam, nodo DHT falso, encuentro + punching +
  transporte sobre el mismo socket, y clasificación de NAT estable/simétrica.
- `Confianza.kt` — huella y `TrustManager` fijado por huella (lógica pura).
- `TlsCanal.kt` — TLS mutuo con `SSLEngine` sobre cualquier `Canal` (UDP fiable o TCP). Probado en la JVM con TLS 1.3 y 1.2
  sobre una red con pérdidas: huella fijada, rechazo de intrusos, cliente sin certificado, paquetes alterados y
  `close_notify`. Verifica además que el contenido no aparece en claro en los paquetes.
- `Protocolo.kt` — tramas y mensajes (HELLO, MSG, ACK, READ, PING, BYE) con validación estricta de longitudes y UTF-8.
- `AlmacenMensajes.kt` — interfaz de persistencia de una conversación, estados (pendiente, enviado, entregado, leído) y una
  implementación en memoria para pruebas. La de SQLite cifrado llegará con la interfaz.
- `Chat.kt` — conversación con un contacto: cola de salida, un único hilo de envío con ritmo controlado, duplicados,
  confirmaciones de lectura, límites de tasa (60 mensajes y 200 tramas por segundo) y política de reintentos (5 s a 15 min).
  Probado con 11 escenarios: pérdidas, caída y reanudación, inyección de duplicados, entradas inválidas, envíos
  concurrentes, cola acumulada de 200 mensajes y conversación completa sobre TLS y UDP.
- `GestorConexion.kt` — mantiene la conexión con un contacto: encuentro por DHT, punching, `UdpFiable`, TLS con huella fijada
  (servidor = huella menor) y `Chat.atender`; al caer espera con `Reintentos` y repite; `cortar()` para cambios de red y `parar()`.
  Probado en loopback con un nodo DHT falso: entrega de mensajes en cola, reconexión tras un corte sin duplicados, parada rápida
  y rechazo de una clave distinta de la fijada (nunca llega al chat).
- `QrCodigo.kt` — generador de QR (modo byte UTF-8, corrección M, versiones 1–20, hasta 666 bytes), solo para mostrar la
  invitación; la lectura la hace la cámara del sistema. Verificado decodificando 27 imágenes (de 1 a 666 bytes, UTF-8 y una
  invitación real) con OpenCV mediante `pruebas/jvm/qr_verificar.py` (necesita `python3` con `cv2`; si no está, la prueba avisa y se salta).
- `AlmacenCifrado.kt` — almacén de mensajes persistente: `AlmacenCifrado` (lógica de estados y secuencias) sobre un `AlmacenFilas`
  y un `Sellador` (AES-256-GCM, IV aleatorio, el contexto `id|dirección|seq` va como dato autenticado: un bloque movido a otra fila
  o alterado se rechaza). En claro solo quedan id aleatorio, dirección, seq y estado; hora, cuerpo y respuesta van cifrados.
  Probado en la JVM con filas en memoria: mismo contrato que `AlmacenMemoria`, alteración, cambio de bloques, otra clave,
  persistencia al reabrir, concurrencia y un chat completo (40 mensajes por lado) sobre él.
- `FilasSqlite.kt` y `ClaveAlmacen.kt` — (Android, sin compilar) SQLite por contacto y clave AES en Keystore. Las sentencias SQL se
  comprobaron con el SQLite de Python, pero la clase hay que compilarla y probarla en Fusion.
- `MainActivity.kt` — pantalla de pruebas.

`pruebas/jvm/` — pruebas de la lógica pura; se ejecutan con `pruebas/jvm/probar.sh` (usa el compilador de Kotlin de Gradle, sin Android ni red).

## Limitaciones conocidas de esta versión

- Solo IPv4 en la DHT (los candidatos IPv6 llegan en la fase 2).
- Las consultas a la DHT son secuenciales y con espera de 1,2 s: la búsqueda puede tardar decenas de segundos.
- La prueba TLS usa `SSLSocket` sobre TCP; el paso a `SSLEngine` sobre UDP fiable es de la fase 2.
- Los nodos de arranque están fijos en `Dht.ARRANQUE`.
