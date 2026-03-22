# Especificación del Protocolo de Juego (Capa de Aplicación)

Este documento define las reglas de comunicación entre los clientes (jugadores) y el servidor central del juego, cumpliendo con los requerimientos de la arquitectura TCP/IP mediante Sockets de Berkeley.

## 1. Visión General
* **Arquitectura:** Cliente-Servidor.
* **Protocolo de Transporte:** TCP (SOCK_STREAM). Se elige TCP para garantizar la entrega fiable y ordenada de los eventos críticos del juego (ataques, movimientos, mitigaciones).
* **Formato de Mensajes:** Texto plano.
* **Delimitador de campos:** `|` (pipe).
* **Fin de mensaje:** Todo mensaje DEBE terminar con un salto de línea `\n`.

## 2. Comandos del Cliente al Servidor (Peticiones)
Estructura base: `COMANDO|PARAMETRO1|PARAMETRO2\n`

| Comando | Parámetros | Descripción | Respuesta de Éxito | Respuesta de Error |
| :--- | :--- | :--- | :--- | :--- |
| `LIST` | (Ninguno) | Solicita las salas activas. | `ROOMS|sala1,sala2\n` | `ERR|NO_ROOMS\n` |
| `JOIN` | `usuario|rol|sala` | Ingresa a una partida. Rol: 'A' o 'D'. | `OK|JOIN\n` | `ERR|ROOM_FULL\n` |
| `MOVE` | `pos_x|pos_y` | Mueve al jugador en el plano. | `OK|MOVE\n` | `ERR|OUT_OF_BOUNDS\n` |
| `SCAN` | (Ninguno) | (Solo Atacante) Busca recursos. | `OK|SCAN|id_recurso|x|y\n` | `OK|SCAN|NONE\n` |
| `ATTACK`| `id_recurso` | (Solo Atacante) Ataca un recurso. | `OK|ATTACK\n` | `ERR|NOT_FOUND\n` |
| `DEFEND`| `id_recurso` | (Solo Defensor) Repara un recurso. | `OK|DEFEND\n` | `ERR|TOO_FAR\n` |
| `EXIT` | (Ninguno) | Desconecta al jugador de la sala. | `OK|EXIT\n` | N/A |

## 3. Eventos del Servidor al Cliente (Notificaciones Asíncronas)
*Nota para los desarrolladores de clientes: Deben tener un hilo escuchando constantemente el socket, ya que el servidor puede enviar estos mensajes en cualquier momento.*

* `EVENT|START\n`: Se envía cuando la sala tiene al menos 1 Atacante y 1 Defensor.
* `EVENT|ALERT|id_recurso|pos_x|pos_y\n`: Se envía a los Defensores cuando un Atacante lanza un ataque.
* `EVENT|RESOLVED|id_recurso\n`: Se envía a todos cuando un recurso es reparado.
* `EVENT|GAMEOVER|ganador\n`: Fin del juego (ganador: 'A' o 'D').

## 4. Reglas de Procedimiento (Flujo de la Aplicación)
1. **Conexión:** El cliente inicia una conexión TCP al puerto definido del servidor.
2. **Identificación:** El primer comando enviado DEBE ser `JOIN`. El servidor no procesará otros comandos hasta que el jugador esté en una sala.
3. **Estado de Espera:** Una vez unido, el cliente espera el mensaje `EVENT|START`. No se permiten movimientos ni ataques antes de este evento.
4. **Ciclo de Juego:** Durante la partida, el servidor actúa como árbitro. Si un Atacante envía `ATTACK`, el servidor valida la posición y notifica a los Defensores mediante `EVENT|ALERT`.
5. **Finalización:** La partida termina cuando se cumplen las condiciones de victoria o un jugador envía `EXIT`. El servidor cierra el hilo del cliente tras confirmar con `OK|EXIT`.