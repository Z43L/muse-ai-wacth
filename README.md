# Muse AI Watch

App para Wear OS que convierte tu reloj en la cara (y la voz) de tu asistente de IA.
La app **ES el asistente**: su avatar animado ocupa toda la pantalla, tocas para
hablar y el reloj te lee la respuesta en voz alta.

> **Lo que es:** un prototipo funcional. Hablas desde un Pixel Watch 3 y un
> agente de IA te responde con voz, con una latencia típica de 15–30 segundos.
>
> **Lo que no es:** una llamada en vivo en tiempo real ni una app oficial.
> No hay SDK público para llevar un asistente al reloj, así que el canal de
> comunicación es un "buzón": un repositorio privado de GitHub donde el reloj
> deja mensajes y el agente los recoge y responde.

---

## Arquitectura

```
┌──────────────┐      inbox.json       ┌──────────────────┐      ┌───────────┐
│  Pixel       │  ─── escribe ───────▶ │  Repo BUZÓN       │ ◀─── │  Agente   │
│  Watch 3     │                       │  (privado)       │      │  (cron)   │
│  (esta app)  │  ◀─── lee ─────────── │  outbox.json      │ ───▶ │           │
└──────────────┘      cada 3 s          └──────────────────┘      └───────────┘
```

1. Hablas al reloj → la app escribe tu mensaje en `inbox.json` del repo buzón.
2. Una tarea programada del lado del agente (cada 15–60 s) lee el mensaje,
   genera la respuesta y la escribe en `outbox.json`, marcando el mensaje como
   atendido.
3. La app vigila `outbox.json` cada 3 segundos; cuando aparece la respuesta,
   la muestra en el chat y la lee en voz alta con el TTS del reloj.

El buzón es un único repo privado con dos ficheros JSON; no hace falta ningún
servidor.

---

## Requisitos

- **Android Studio** (Ladybug o posterior) con el Android SDK instalado.
- Un **Pixel Watch 3** con la depuración por Wi-Fi activada
  (Ajustes → Opciones de desarrollador en el reloj), o un emulador de Wear OS 5.
- Una cuenta de GitHub (para el repo buzón).
- Un agente de IA capaz de ejecutar tareas programadas con acceso a GitHub
  (en el prototipo original: el asistente Muse con un cron).

---

## Parte 1 — Configurar el repo buzón

El buzón es un repositorio **privado** de GitHub con dos ficheros:

- `inbox.json` — mensajes pendientes del reloj hacia el agente.
- `outbox.json` — respuestas del agente hacia el reloj.

### 1.1. Crear el repositorio

Crea un repo privado (p. ej. `tu-usuario/watch-mailbox`) y añade los dos
ficheros con este contenido inicial:

`inbox.json`:
```json
[]
```

`outbox.json`:
```json
{
  "id": "",
  "reply_to": "",
  "ts": 0,
  "text": ""
}
```

> `inbox.json` es una **cola**: una lista de mensajes pendientes. Cada mensaje
> tiene `id`, `ts`, `status` (`"pending"`) y `text`. Cuando el agente responde
> un mensaje, lo saca de la cola.

### 1.2. Crear el token de acceso

La app necesita un token para leer/escribir en el repo. Crea un
**fine-grained Personal Access Token**:

1. GitHub → Settings → Developer settings → Personal access tokens →
   **Fine-grained tokens** → Generate new token.
2. *Resource owner*: tu usuario.
3. *Repository access*: **Only select repositories** → elige tu repo buzón.
4. *Permissions* → **Contents**: Read and write. Todo lo demás, sin acceso.
5. Genera y copia el token (empieza por `github_pat_...`).

---

## Parte 2 — Configurar la app

Abre el proyecto en Android Studio y edita:

```
app/src/main/java/com/wally/watchchat/MailboxConfig.kt
```

```kotlin
object MailboxConfig {
    const val API = "https://api.github.com"
    const val REPO = "tu-usuario/watch-mailbox"   // ← tu repo buzón
    const val TOKEN = "github_pat_..."            // ← tu token
}
```

> ⚠️ **Seguridad:** el token viaja dentro del APK. Vale para un prototipo
> personal que solo instalas en tu reloj. No publiques el APK ni lo
> distribuyas con un token real dentro.

### Cómo funciona la app por dentro

- `MainActivity` → `AvatarScreen`: el avatar 3D animado (`WallyAvatar.kt`,
  vídeos en `res/raw/`) ocupa toda la pantalla. Tocas la pantalla y hablas
  (entrada por voz con `RemoteInput`); mientras esperas respuesta se reproduce
  la animación de "trabajando".
- `MailboxBridge`: envía tu mensaje a `inbox.json` y hace polling a
  `outbox.json` cada 3 segundos hasta que llega la respuesta dirigida a tu
  mensaje (`reply_to == id`).
- La respuesta se muestra en el chat y se lee en voz alta con el TTS del
  reloj (español).
- `ChatListenerService` + `ChatViewModel`: plumbing del chat.

El icono de la app (`res/mipmap-*/ic_launcher.png`) es la imagen del avatar:
sustituye esos PNG por los tuyos si quieres otra cara.

### Tu propio avatar (cada persona el suyo)

El avatar no está programado en código: son dos vídeos en bucle y el icono.
Para poner el tuyo, sustituye estos ficheros **manteniendo los nombres**:

| Fichero | Qué es | Requisitos |
|---|---|---|
| `res/raw/wally_idle.mp4` | Animación en reposo (bucle) | MP4 cuadrado (p. ej. 720×720), pocos segundos, bucle limpio |
| `res/raw/wally_working.mp4` | Animación mientras "piensa" (se reproduce al esperar respuesta) | Igual que el anterior |
| `res/mipmap-*/ic_launcher.png` | Icono de la app | PNG cuadrado en cada densidad (mdpi 48px … xxxhdpi 192px) |

Ideas para generar tus vídeos: graba un bucle corto de tu propio avatar 3D,
o genera uno con una herramienta de vídeo por IA y recórtalo a cuadrado.
Para el icono, lo más cómodo es usar en Android Studio
*clic derecho en `res` → New → Image Asset* y generar todas las densidades
de una vez desde una sola imagen.

El nombre en pantalla y la voz del TTS los pone el reloj; el "quién" lo
decide cada instalación con sus propios ficheros.

---

## Parte 3 — Instalar en el reloj

1. Abre la carpeta del proyecto en **Android Studio**.
2. Deja que sincronice Gradle (descarga las dependencias la primera vez).
3. Activa la **depuración por Wi-Fi** en el reloj
   (Ajustes → Opciones de desarrollador → Depuración por Wi-Fi) y conéctalo
   con `adb` (`adb connect <ip-del-reloj>:5555`), o usa un emulador de
   Wear OS 5.
4. Pulsa **Run** en el módulo `app`.

Si al hablar ves un error de GitHub en el chat, revisa el token y el nombre
del repo en `MailboxConfig.kt`. Para probar solo la interfaz sin buzón,
cambia el puente en `ChatViewModel` a `SimulatedBridge()` (respuestas
simuladas en español).

---

## Parte 4 — Configurar el lado del agente (el "cerebro")

La app sola no responde: necesita un agente al otro lado del buzón. En el
prototipo original es el asistente Muse con una tarea programada, pero sirve
cualquier agente con acceso a GitHub y a un programador de tareas.

La carpeta [`mailbox/`](mailbox/) contiene `mailbox.py`, un CLI mínimo para
operar el buzón (usa la API de GitHub, autentica con el token del entorno):

```bash
export GITHUB_TOKEN="github_pat_..."
python3 mailbox/mailbox.py inbox              # ver mensajes pendientes
python3 mailbox/mailbox.py send "hola"        # encolar (lo que hace la app)
python3 mailbox/mailbox.py reply <id> "texto" # responder: escribe outbox.json y saca el mensaje de la cola
python3 mailbox/mailbox.py done <id>          # marcar como atendido sin responder
```

La tarea programada debe, cada 15–60 segundos:

1. Leer el primer mensaje pendiente de `inbox.json`.
2. Generar la respuesta (el agente).
3. Escribirla en `outbox.json` con `reply_to` = id del mensaje.
4. Sacar el mensaje de la cola (`done`).

Un mensaje por ejecución es suficiente; si hay varios pendientes, las
siguientes ejecuciones los atienden en orden.

---

## Solución de problemas

| Síntoma | Causa probable |
|---|---|
| Error de GitHub al hablar | Token mal copiado, caducado o sin permiso Contents: write en el repo |
| El reloj no responde nunca | La tarea del agente no está corriendo, o el repo de `MailboxConfig` no es el mismo que vigila el agente |
| Responde pero tarda mucho | Normal: la latencia la marca el intervalo del cron del agente (15–60 s) |
| Dos mensajes seguidos, solo responde uno | El buzón es una cola: el segundo se atiende en la siguiente ejecución del cron |

---

## Estructura del repo

```
muse-ai-wacth/
├── app/                    # App Wear OS (Kotlin + Compose para Wear OS)
│   └── src/main/
│       ├── java/com/wally/watchchat/  # AvatarScreen, MailboxBridge, ...
│       └── res/
│           ├── raw/        # Vídeos del avatar (idle / trabajando)
│           └── mipmap-*/   # Icono de la app (imagen del avatar)
├── mailbox/
│   └── mailbox.py          # CLI del buzón para el lado del agente
├── README.md
└── .gitignore
```

---

*Prototipo personal. Sin garantías, sin SLA y sin prisa: si tarda 20 segundos
en responder, es que está pensando.*
