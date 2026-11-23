fun main() {
    val total = mainDht() + mainInvitacion() + mainUdp() + mainTls() + mainChat() + mainDemux() + mainGestor() + mainQr()
    println(if (total == 0) "\nTODAS LAS PRUEBAS OK" else "\nFALLOS TOTALES: $total")
    System.exit(if (total == 0) 0 else 1)
}
