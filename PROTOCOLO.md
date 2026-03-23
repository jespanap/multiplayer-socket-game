# Especificación del Protocolo de Juego (Capa de Aplicación)

Este documento define las reglas de comunicación entre los clientes (jugadores) y el servidor central del juego, cumpliendo con los requerimientos de la arquitectura TCP/IP mediante Sockets de Berkeley.

## 1. Visión General
* **Arquitectura:** Cliente-Servidor con soporte para Multiplexación de Protocolos (TCP Game Protocol + HTTP Básico).
* **Protocolo de Transporte:** TCP (SOCK_STREAM). Garantiza la entrega fiable y ordenada de los eventos de seguridad.
* **Formato de Mensajes:** Texto plano.
* **Delimitador de campos:** `|` (pipe).
* **Fin de mensaje:** Todo mensaje DEBE terminar con un salto de línea `\n`.

## 2. Comandos del Cliente al Servidor (Peticiones)
Estructura base: `COMANDO|PARAMETRO1|PARAMETRO2\n`

| Comando | Parámetros | Descripción | Respuesta de Éxito | Respuesta de Error |
| :--- | :--- | :--- | :--- | :--- |
| `LIST` | (Ninguno) | Solicita el ID de las salas con partidas activas. | `ROOMS\|0,1,2\n` | `ERR\|NO_ROOMS\n` |
| `JOIN` | `usuario\|sala` | El cliente pide entrar. El servidor asigna el Rol (A/D) según el servicio de identidad. | `OK\|JOIN\|nombre\|rol\|sala\n` | `ERR\|SALA_LLENA\n` |
| `MOVE` | `x\|y` | Actualiza la posición del jugador en el plano (0-100). | `OK\|MOVE\n` | `ERR\|OUT_OF_BOUNDS\n` |
| `SCAN` | (Ninguno) | (Atacante) Busca recursos en un radio de 20 unidades. | `OK\|SCAN\|id\|x\|y\n` | `OK\|SCAN\|NONE\n` |
| `ATTACK`| `id_recurso` | (Atacante) Inicia ataque. Activa cronómetro de destrucción. | `OK\|ATTACK\n` | `ERR\|YA_ATACADO\n` |
| `DEFEND`| `id_recurso` | (Defensor) Repara un recurso. Requiere estar a dist < 5. | `OK\|DEFEND\n` | `ERR\|TOO_FAR\n` |
| `EXIT` | (Ninguno) | Finaliza la sesión y libera el cupo en la sala. | `OK\|EXIT\n` | N/A |

## 3. Eventos del Servidor al Cliente (Notificaciones Asíncronas)
*Nota: El cliente debe manejar estos mensajes mediante un hilo de escucha dedicado.*

* **`EVENT|START\n`**: La partida inicia al haber al menos 1 Atacante y 1 Defensor en la sala.
* **`EVENT|MOVE|usuario|x|y\n`**: Notifica a todos la nueva posición de un jugador para actualización visual.
* **`EVENT|ALERT|id|x|y|tiempo\n`**: (Solo a Defensores) Indica qué recurso está bajo ataque y cuánto tiempo queda para repararlo.
* **`EVENT|RESOLVED|id\n`**: Indica que un ataque fue mitigado exitosamente.
* **`EVENT|DESTROYED|id\n`**: Indica que el tiempo de defensa expiró y el recurso ha sido eliminado.
* **`EVENT|GAMEOVER|ganador\n`**: Fin del juego. Ganador: `A` (Atacante) o `D` (Defensor).

## 4. Interfaz de Monitoreo (Protocolo HTTP Fallback)

El servidor implementa un mecanismo de "Protocol Multiplexing" que permite el monitoreo administrativo mediante cualquier navegador web estándar, sin requerir un cliente de juego especializado.

* **Método:** `GET`
* **Ruta:** `/`
* **Cabecera de Respuesta:** `HTTP/1.1 200 OK`
* **Tipo de Contenido:** `text/html`
* **Función:** Proporcionar una vista en tiempo real del estado de las 3 salas, el número de jugadores conectados y si la partida está en curso o finalizada.

> **Nota técnica:** El servidor identifica la petición HTTP analizando los primeros 4 bytes del flujo de datos. Si detecta la cadena "GET ", interrumpe el protocolo de juego para servir la página web y cierra la conexión inmediatamente (Stateless).

## 5. Reglas de Procedimiento (Flujo)
1. **Conexión:** El cliente establece el socket TCP.
2. **Identificación:** Se envía `JOIN`. El servidor consulta el "Servicio de Identidad" (basado en la inicial del nombre) y devuelve el rol asignado.
3. **Sincronización:** Los jugadores esperan en la sala hasta recibir `EVENT|START`.
4. **Interacción:** - Los Atacantes escanean y atacan. 
    - El Servidor arbitra la distancia y el tiempo. 
    - Si un recurso atacado no es defendido en 30 segundos, cambia a estado `DESTROYED`.
5. **Victoria:** Si todos los recursos son destruidos, el servidor envía `EVENT|GAMEOVER|A`.
