#!/bin/sh
# Pruebas JVM de la logica pura (Bencode, Dht sin red, Invitacion). No necesita Android ni red.
# Uso: pruebas/jvm/probar.sh [directorio_lib_de_gradle]
# Usa el compilador de Kotlin que trae Gradle (kotlin-compiler-embeddable). Si tienes kotlinc, tambien vale.
set -e
AQUI="$(cd "$(dirname "$0")" && pwd)"
RAIZ="$AQUI/../.."
FUENTES="$RAIZ/app/src/main/java/com/optimo/chat"
L="${1:-$(ls -d /opt/gradle*/lib 2>/dev/null | head -1)}"
SALIDA="$(mktemp -d)"
CP="$L/kotlin-compiler-embeddable-2.0.21.jar:$L/kotlin-stdlib-2.0.21.jar:$L/kotlin-script-runtime-2.0.21.jar:$L/kotlin-reflect-2.0.21.jar:$L/trove4j-1.0.20200330.jar:$L/kotlin-daemon-embeddable-2.0.21.jar:$L/annotations-24.0.1.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$L/kotlin-stdlib-2.0.21.jar" \
  -d "$SALIDA" "$FUENTES/Bencode.kt" "$FUENTES/Dht.kt" "$FUENTES/Informe.kt" "$FUENTES/SondaNat.kt" "$FUENTES/Invitacion.kt" "$FUENTES/UdpFiable.kt" "$FUENTES/Confianza.kt" "$FUENTES/TlsCanal.kt" "$FUENTES/Protocolo.kt" "$FUENTES/AlmacenMensajes.kt" "$FUENTES/Chat.kt" "$FUENTES/Puerto.kt" "$FUENTES/GestorConexion.kt" "$FUENTES/QrCodigo.kt" "$FUENTES/AlmacenCifrado.kt" "$FUENTES/Contactos.kt" "$FUENTES/Emparejamiento.kt" "$FUENTES/PruebaContacto.kt" \
  "$AQUI/PruebaDht.kt" "$AQUI/PruebaInvitacion.kt" "$AQUI/PruebaUdp.kt" "$AQUI/PruebaTls.kt" "$AQUI/PruebaChat.kt" "$AQUI/PruebaDemux.kt" "$AQUI/PruebaGestor.kt" "$AQUI/PruebaQr.kt" "$AQUI/PruebaAlmacen.kt" "$AQUI/PruebaPar.kt" "$AQUI/Principal.kt"
java -Dfile.encoding=UTF-8 -cp "$SALIDA:$L/kotlin-stdlib-2.0.21.jar" PrincipalKt
