package com.wally.watchchat

/**
 * Configuración del buzón (repo privado de GitHub que hace de tablón
 * entre el reloj y Wally).
 *
 * 1. Crea un fine-grained Personal Access Token en GitHub:
 *    Settings → Developer settings → Personal access tokens →
 *    Fine-grained tokens → Generate new token.
 * 2. Resource owner: Z43L. Repository access: "Only select repositories" →
 *    elige wally-watch-mailbox.
 * 3. Permissions → Contents: Read and write. Todo lo demás, sin acceso.
 * 4. Pega el token aquí abajo.
 *
 * El token viaja dentro del APK: vale para este prototipo personal,
 * no lo uses en una app que distribuyas.
 */
object MailboxConfig {
    const val API = "https://api.github.com"
    const val REPO = "Z43L/wally-watch-mailbox"
    const val TOKEN = ""
}
