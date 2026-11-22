# OptimoChat (fase 0)

App Android independiente (`com.optimo.chat`), compañera de Optimo. Esta versión **0.0.1 no es un chat**: es la
pantalla de pruebas de viabilidad de la fase 0 de la especificación técnica. Sin dependencias (solo APIs del
sistema), misma versión de Gradle/AGP/Kotlin que Optimo y misma mecánica de firma con Fusion.

> **Estado:** el código no se ha compilado ni probado en dispositivo (en el entorno de redacción no hay SDK de
> Android ni compilador de Kotlin). Hay que compilar en Fusion y corregir lo que salga, como con otras versiones
> de Optimo.

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
- `MainActivity.kt` — pantalla de pruebas.

## Limitaciones conocidas de esta versión

- Solo IPv4 en la DHT (los candidatos IPv6 llegan en la fase 2).
- Las consultas a la DHT son secuenciales y con espera de 1,2 s: la búsqueda puede tardar decenas de segundos.
- La prueba TLS usa `SSLSocket` sobre TCP; el paso a `SSLEngine` sobre UDP fiable es de la fase 2.
- Los nodos de arranque están fijos en `Dht.ARRANQUE`.
