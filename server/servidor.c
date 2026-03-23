#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <time.h>
#include <signal.h>

#define MAX_SALAS 3
#define MAX_JUGADORES_POR_SALA 10
#define BUFFER_SIZE 1024
#define LIMITE_PLANO_X 100
#define LIMITE_PLANO_Y 100
#define TIEMPO_LIMITE_DEFENSA 30 
#define TIEMPO_MAX_PARTIDA 180   

// ESTRUCTURAS DE DATOS

typedef struct {
    int id;
    int x, y;
    int estado; 
    time_t tiempo_ataque;
} Recurso;

typedef struct {
    int socket;
    char ip[INET_ADDRSTRLEN];
    int puerto;
    char nombre[32];
    char rol;       
    int x, y;
    int activo;
} Jugador;

typedef struct {
    int id_sala;
    int partida_iniciada; // 0 = Esperando, 1 = En curso, 2 = Finalizada
    time_t tiempo_inicio; 
    Jugador jugadores[MAX_JUGADORES_POR_SALA];
    Recurso recursos[2];
    pthread_mutex_t mutex_sala;
} Sala;

Sala salas[MAX_SALAS];
FILE *archivo_log;
pthread_mutex_t log_mutex = PTHREAD_MUTEX_INITIALIZER;

typedef struct {
    int socket;
    char ip[INET_ADDRSTRLEN];
    int puerto;
} ClientePendiente;

//FUNCIONES DE SOPORTE

void escribir_log(const char *ip, int puerto, const char *direccion, const char *mensaje) {
    pthread_mutex_lock(&log_mutex); 
    char msg_limpio[BUFFER_SIZE];
    strncpy(msg_limpio, mensaje, sizeof(msg_limpio) - 1);
    msg_limpio[sizeof(msg_limpio) - 1] = '\0';
    msg_limpio[strcspn(msg_limpio, "\n")] = 0; 

    printf("[IP: %s | Pto: %d] %s: %s\n", ip, puerto, direccion, msg_limpio);
    if (archivo_log != NULL) {
        fprintf(archivo_log, "[IP: %s | Pto: %d] %s: %s\n", ip, puerto, direccion, msg_limpio);
        fflush(archivo_log);
    }
    pthread_mutex_unlock(&log_mutex);
}

char consultar_servicio_identidad(const char *nombre) {
    char inicial = nombre[0];
    if ((inicial >= 'A' && inicial <= 'M') || (inicial >= 'a' && inicial <= 'm')) return 'A';
    return 'D';
}

void resolver_nombre_dominio(const char *hostname) {
    struct addrinfo hints, *res;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    
    printf("[SISTEMA] Resolviendo DNS para %s...\n", hostname);
    if (getaddrinfo(hostname, NULL, &hints, &res) != 0) {
        printf("[ADVERTENCIA] Falló DNS para %s. El servicio continuará (Req 3).\n", hostname);
        escribir_log("SISTEMA", 0, "EXCEPCION", "Fallo resolucion DNS");
        return;
    }
    char ip_resuelta[INET_ADDRSTRLEN];
    inet_ntop(AF_INET, &(((struct sockaddr_in *)res->ai_addr)->sin_addr), ip_resuelta, INET_ADDRSTRLEN);
    printf("[SISTEMA] DNS Resuelto: %s -> %s\n", hostname, ip_resuelta);
    freeaddrinfo(res);
}

void enviar_a_sala(int id_sala, const char *mensaje, char rol_filtro) {
    int sockets_destino[MAX_JUGADORES_POR_SALA];
    int cantidad = 0;

    pthread_mutex_lock(&salas[id_sala].mutex_sala);
    for (int i = 0; i < MAX_JUGADORES_POR_SALA; i++) {
        Jugador *j = &salas[id_sala].jugadores[i];
        if (j->activo && (rol_filtro == 'T' || j->rol == rol_filtro)) {
            sockets_destino[cantidad++] = j->socket;
        }
    }
    pthread_mutex_unlock(&salas[id_sala].mutex_sala);

    for (int i = 0; i < cantidad; i++) {
        send(sockets_destino[i], mensaje, strlen(mensaje), 0);
    }
}

//HILO RELOJ (ASÍNCRONO) 
void *hilo_reloj_juego(void *arg) {
    (void)arg; // Evita warning de variable sin usar
    while (1) {
        sleep(1); 
        for (int i = 0; i < MAX_SALAS; i++) {
            int notificar_destruccion[2] = {0, 0};
            int notificar_gameover_A = 0;
            int notificar_gameover_D = 0;

            pthread_mutex_lock(&salas[i].mutex_sala);
            if (salas[i].partida_iniciada == 1) {
                int destruidos = 0;
                
                for (int r = 0; r < 2; r++) {
                    if (salas[i].recursos[r].estado == 1) {
                        if (time(NULL) - salas[i].recursos[r].tiempo_ataque >= TIEMPO_LIMITE_DEFENSA) {
                            salas[i].recursos[r].estado = 2; 
                            notificar_destruccion[r] = 1;
                        }
                    }
                    if (salas[i].recursos[r].estado == 2) destruidos++;
                }

                if (destruidos == 2) {
                    salas[i].partida_iniciada = 2;
                    notificar_gameover_A = 1;
                } else if (time(NULL) - salas[i].tiempo_inicio >= TIEMPO_MAX_PARTIDA) {
                    salas[i].partida_iniciada = 2;
                    notificar_gameover_D = 1;
                }
            }
            pthread_mutex_unlock(&salas[i].mutex_sala);

            if (notificar_destruccion[0]) enviar_a_sala(i, "EVENT|DESTROYED|0\n", 'T');
            if (notificar_destruccion[1]) enviar_a_sala(i, "EVENT|DESTROYED|1\n", 'T');
            if (notificar_gameover_A) enviar_a_sala(i, "EVENT|GAMEOVER|A\n", 'T');
            if (notificar_gameover_D) enviar_a_sala(i, "EVENT|GAMEOVER|D\n", 'T');
        }
    }
    return NULL;
}

//LÓGICA PRINCIPAL DEL CLIENTE

void *manejar_cliente(void *arg) {
    ClientePendiente *cliente = (ClientePendiente *)arg;
    char buffer_red[BUFFER_SIZE];
    char mensaje_acumulado[BUFFER_SIZE * 2] = "";
    int bytes_leidos;
    
    int id_sala_actual = -1;
    int id_jugador_actual = -1;

    escribir_log(cliente->ip, cliente->puerto, "SISTEMA", "Conexión entrante");

    while ((bytes_leidos = recv(cliente->socket, buffer_red, sizeof(buffer_red) - 1, 0)) > 0) {
        buffer_red[bytes_leidos] = '\0';

        // REQ 1: SERVIDOR HTTP BÁSICO 
        if (strncmp(buffer_red, "GET ", 4) == 0) {
            escribir_log(cliente->ip, cliente->puerto, "HTTP GET", "Petición Web recibida");
            char http_response[4096]; // Buffer más grande por seguridad
            strcpy(http_response, "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n");
            strcat(http_response, "<html><head><title>Salas</title></head><body style='font-family: Arial;'>");
            strcat(http_response, "<h1>Simulador de Ciberseguridad</h1><h2>Salas Activas:</h2><ul>");
            
            for(int i=0; i<MAX_SALAS; i++) {
                pthread_mutex_lock(&salas[i].mutex_sala);
                int j_activos = 0;
                for(int j=0; j<MAX_JUGADORES_POR_SALA; j++) if(salas[i].jugadores[j].activo) j_activos++;
                
                const char *estado_texto = salas[i].partida_iniciada == 0 ? "Esperando Jugadores" :
                                           salas[i].partida_iniciada == 1 ? "En Juego" : "Finalizada";
                
                char item[256];
                snprintf(item, sizeof(item), "<li><b>Sala %d</b> - Jugadores: %d/10 - Estado: <span style='color:blue;'>%s</span></li>", 
                         i, j_activos, estado_texto);
                strcat(http_response, item);
                pthread_mutex_unlock(&salas[i].mutex_sala);
            }
            strcat(http_response, "</ul></body></html>");
            send(cliente->socket, http_response, strlen(http_response), 0);
            goto desconectar; 
        }

        int espacio_libre = sizeof(mensaje_acumulado) - strlen(mensaje_acumulado) - 1;
        if (bytes_leidos > espacio_libre) {
            escribir_log(cliente->ip, cliente->puerto, "SEGURIDAD", "Buffer overflow detectado, desconectando");
            goto desconectar; 
        }
        strncat(mensaje_acumulado, buffer_red, espacio_libre);

        char *linea;
        while ((linea = strchr(mensaje_acumulado, '\n')) != NULL) {
            *linea = '\0'; 
            char comando_actual[BUFFER_SIZE];
            strncpy(comando_actual, mensaje_acumulado, BUFFER_SIZE - 1);
            comando_actual[BUFFER_SIZE - 1] = '\0';
            
            memmove(mensaje_acumulado, linea + 1, strlen(linea + 1) + 1);

            escribir_log(cliente->ip, cliente->puerto, "RECIBIDO", comando_actual);
            char respuesta[BUFFER_SIZE] = "";
            char *saveptr_cmd; 

            int enviar_start = 0;
            char broadcast_msg[BUFFER_SIZE] = "";
            char broadcast_rol = 'N';

            // LÓGICA DE LIST: Muestra salas que no hayan finalizado
            if (strncmp(comando_actual, "LIST", 4) == 0) {
                strcpy(respuesta, "ROOMS|");
                int activas = 0;
                for(int i=0; i<MAX_SALAS; i++) {
                    pthread_mutex_lock(&salas[i].mutex_sala);
                    if (salas[i].partida_iniciada != 2) { // Muestra 0 (Esperando) y 1 (En Juego)
                        char num[10]; 
                        snprintf(num, sizeof(num), "%d,", i); 
                        strcat(respuesta, num);
                        activas++;
                    }
                    pthread_mutex_unlock(&salas[i].mutex_sala);
                }
                if (activas == 0) strcpy(respuesta, "ERR|NO_ROOMS\n");
                else respuesta[strlen(respuesta)-1] = '\n'; 
            }
            else if (strncmp(comando_actual, "JOIN", 4) == 0) {
                strtok_r(comando_actual, "|", &saveptr_cmd); // Ignoramos el "JOIN" (Evita warning)
                char *nombre = strtok_r(NULL, "|", &saveptr_cmd);
                char *sala_str = strtok_r(NULL, "|", &saveptr_cmd);

                if (nombre && sala_str) {
                    int sala_req = atoi(sala_str);
                    if (sala_req >= 0 && sala_req < MAX_SALAS) {
                        pthread_mutex_lock(&salas[sala_req].mutex_sala);
                        if (salas[sala_req].partida_iniciada == 2) {
                            strcpy(respuesta, "ERR|PARTIDA_FINALIZADA\n");
                        } else {
                            for (int i = 0; i < MAX_JUGADORES_POR_SALA; i++) {
                                if (salas[sala_req].jugadores[i].activo == 0) {
                                    id_sala_actual = sala_req;
                                    id_jugador_actual = i;
                                    Jugador *j = &salas[sala_req].jugadores[i];
                                    j->activo = 1;
                                    j->socket = cliente->socket;
                                    strcpy(j->ip, cliente->ip);
                                    j->puerto = cliente->puerto;
                                    strncpy(j->nombre, nombre, 31);
                                    j->rol = consultar_servicio_identidad(nombre);
                                    j->x = 0; j->y = 0;
                                    
                                    sprintf(respuesta, "OK|JOIN|%s|%c|%d\n", j->nombre, j->rol, id_sala_actual);
                                    break;
                                }
                            }
                            
                            if (id_sala_actual != -1 && salas[id_sala_actual].partida_iniciada == 0) {
                                int atacantes = 0, defensores = 0;
                                for (int i = 0; i < MAX_JUGADORES_POR_SALA; i++) {
                                    if (salas[id_sala_actual].jugadores[i].activo) {
                                        if (salas[id_sala_actual].jugadores[i].rol == 'A') atacantes++;
                                        if (salas[id_sala_actual].jugadores[i].rol == 'D') defensores++;
                                    }
                                }
                                if (atacantes >= 1 && defensores >= 1) {
                                    salas[id_sala_actual].partida_iniciada = 1;
                                    salas[id_sala_actual].tiempo_inicio = time(NULL);
                                    enviar_start = 1;
                                }
                            }
                            if (id_sala_actual == -1) strcpy(respuesta, "ERR|SALA_LLENA\n");
                        }
                        pthread_mutex_unlock(&salas[sala_req].mutex_sala);
                    } else strcpy(respuesta, "ERR|SALA_INEXISTENTE\n");
                } else strcpy(respuesta, "ERR|FORMATO_INVALIDO\n");
            }
            else if (id_sala_actual == -1) {
                strcpy(respuesta, "ERR|DEBES_HACER_JOIN_PRIMERO\n");
            }
            else {
                pthread_mutex_lock(&salas[id_sala_actual].mutex_sala);
                Jugador *mi_jugador = &salas[id_sala_actual].jugadores[id_jugador_actual];
                Sala *mi_sala = &salas[id_sala_actual];

                if (mi_sala->partida_iniciada != 1 && strncmp(comando_actual, "EXIT", 4) != 0) {
                    strcpy(respuesta, "ERR|PARTIDA_NO_ACTIVA\n");
                }
                else if (strncmp(comando_actual, "MOVE", 4) == 0) {
                    strtok_r(comando_actual, "|", &saveptr_cmd);
                    char *x_str = strtok_r(NULL, "|", &saveptr_cmd);
                    char *y_str = strtok_r(NULL, "|", &saveptr_cmd);
                    
                    if (x_str && y_str) {
                        int nx = atoi(x_str), ny = atoi(y_str);
                        if (nx >= 0 && nx <= LIMITE_PLANO_X && ny >= 0 && ny <= LIMITE_PLANO_Y) {
                            mi_jugador->x = nx; mi_jugador->y = ny;
                            strcpy(respuesta, "OK|MOVE\n");
                            sprintf(broadcast_msg, "EVENT|MOVE|%s|%d|%d\n", mi_jugador->nombre, nx, ny);
                            broadcast_rol = 'T';
                        } else strcpy(respuesta, "ERR|OUT_OF_BOUNDS\n");
                    }
                }
                else if (strncmp(comando_actual, "SCAN", 4) == 0) {
                    if (mi_jugador->rol == 'A') {
                        int encontrado = 0;
                        for (int i=0; i<2; i++) {
                            Recurso *r = &mi_sala->recursos[i];
                            if (r->estado != 2) { 
                                double dx = mi_jugador->x - r->x;
                                double dy = mi_jugador->y - r->y;
                                if ((dx*dx + dy*dy) <= 400.0) { 
                                    sprintf(respuesta, "OK|SCAN|%d|%d|%d\n", r->id, r->x, r->y);
                                    encontrado = 1; break;
                                }
                            }
                        }
                        if (!encontrado) strcpy(respuesta, "OK|SCAN|NONE\n");
                    } else strcpy(respuesta, "ERR|NO_ERES_ATACANTE\n");
                }
                else if (strncmp(comando_actual, "ATTACK", 6) == 0) {
                    if (mi_jugador->rol == 'A') {
                        strtok_r(comando_actual, "|", &saveptr_cmd);
                        char *id_rec_str = strtok_r(NULL, "|", &saveptr_cmd);
                        if (id_rec_str) {
                            int id_rec = atoi(id_rec_str);
                            if (id_rec >= 0 && id_rec < 2) {
                                Recurso *r = &mi_sala->recursos[id_rec];
                                if (r->estado == 0) {
                                    r->estado = 1; 
                                    r->tiempo_ataque = time(NULL);
                                    strcpy(respuesta, "OK|ATTACK\n");
                                    sprintf(broadcast_msg, "EVENT|ALERT|%d|%d|%d|%d\n", r->id, r->x, r->y, TIEMPO_LIMITE_DEFENSA);
                                    broadcast_rol = 'D';
                                } else strcpy(respuesta, "ERR|YA_ATACADO_O_DESTRUIDO\n");
                            } else strcpy(respuesta, "ERR|RECURSO_INVALIDO\n");
                        }
                    } else strcpy(respuesta, "ERR|NO_ERES_ATACANTE\n");
                }
                else if (strncmp(comando_actual, "DEFEND", 6) == 0) {
                    if (mi_jugador->rol == 'D') {
                        strtok_r(comando_actual, "|", &saveptr_cmd);
                        char *id_rec_str = strtok_r(NULL, "|", &saveptr_cmd);
                        if (id_rec_str) {
                            int id_rec = atoi(id_rec_str);
                            if (id_rec >= 0 && id_rec < 2) {
                                Recurso *r = &mi_sala->recursos[id_rec];
                                double dx = mi_jugador->x - r->x;
                                double dy = mi_jugador->y - r->y;
                                if ((dx*dx + dy*dy) <= 25.0 && r->estado == 1) { 
                                    r->estado = 0; 
                                    strcpy(respuesta, "OK|DEFEND\n");
                                    sprintf(broadcast_msg, "EVENT|RESOLVED|%d\n", r->id);
                                    broadcast_rol = 'T';
                                } else strcpy(respuesta, "ERR|TOO_FAR_OR_NOT_ATTACKED\n");
                            }
                        }
                    } else strcpy(respuesta, "ERR|NO_ERES_DEFENSOR\n");
                }
                else if (strncmp(comando_actual, "EXIT", 4) == 0) {
                    strcpy(respuesta, "OK|EXIT\n");
                    send(cliente->socket, respuesta, strlen(respuesta), 0);
                    pthread_mutex_unlock(&mi_sala->mutex_sala);
                    goto desconectar;
                }
                else strcpy(respuesta, "ERR|COMANDO_DESCONOCIDO\n");
                
                pthread_mutex_unlock(&mi_sala->mutex_sala);
            }

            if (strlen(respuesta) > 0) {
                send(cliente->socket, respuesta, strlen(respuesta), 0);
                escribir_log(cliente->ip, cliente->puerto, "ENVIADO", respuesta);
            }
            if (enviar_start) enviar_a_sala(id_sala_actual, "EVENT|START\n", 'T');
            if (broadcast_rol != 'N') enviar_a_sala(id_sala_actual, broadcast_msg, broadcast_rol);
        }
    }

desconectar:
    escribir_log(cliente->ip, cliente->puerto, "SISTEMA", "Cliente desconectado");
    close(cliente->socket);
    
    if (id_sala_actual != -1 && id_jugador_actual != -1) {
        pthread_mutex_lock(&salas[id_sala_actual].mutex_sala);
        salas[id_sala_actual].jugadores[id_jugador_actual].activo = 0;
        
        int vacia = 1;
        for(int i=0; i<MAX_JUGADORES_POR_SALA; i++) {
            if(salas[id_sala_actual].jugadores[i].activo) vacia = 0;
        }
        if(vacia) {
            salas[id_sala_actual].partida_iniciada = 0;
            salas[id_sala_actual].recursos[0].estado = 0;
            salas[id_sala_actual].recursos[1].estado = 0;
            salas[id_sala_actual].tiempo_inicio = 0; 
        }
        pthread_mutex_unlock(&salas[id_sala_actual].mutex_sala);
    }
    
    free(cliente);
    pthread_exit(NULL);
}

//FUNCIÓN PRINCIPAL

int main(int argc, char *argv[]) {
    if (argc != 3) {
        printf("Uso: %s <puerto> <archivoDeLogs>\n", argv[0]);
        return 1;
    }

    signal(SIGPIPE, SIG_IGN);

    int puerto = atoi(argv[1]);
    archivo_log = fopen(argv[2], "a");
    if (!archivo_log) { perror("Error log"); return 1; }

    for(int i=0; i<MAX_SALAS; i++) {
        salas[i].id_sala = i;
        salas[i].partida_iniciada = 0;
        salas[i].tiempo_inicio = 0;
        pthread_mutex_init(&salas[i].mutex_sala, NULL);
        salas[i].recursos[0] = (Recurso){0, 20, 20, 0, 0};
        salas[i].recursos[1] = (Recurso){1, 80, 80, 0, 0};
        for(int j=0; j<MAX_JUGADORES_POR_SALA; j++) salas[i].jugadores[j].activo = 0;
    }

    resolver_nombre_dominio("identidad.juego.local"); 

    int socket_servidor = socket(AF_INET, SOCK_STREAM, 0);
    if (socket_servidor < 0) { perror("Error creando socket"); return 1; } 

    int opt = 1;
    setsockopt(socket_servidor, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in direccion_servidor;
    direccion_servidor.sin_family = AF_INET;
    direccion_servidor.sin_addr.s_addr = INADDR_ANY;
    direccion_servidor.sin_port = htons(puerto);

    if (bind(socket_servidor, (struct sockaddr *)&direccion_servidor, sizeof(direccion_servidor)) < 0) {
        perror("Error en bind"); return 1;
    }

    listen(socket_servidor, 20);
    printf("Servidor Iniciado en el puerto %d...\n", puerto);

    pthread_t hilo_reloj;
    pthread_create(&hilo_reloj, NULL, hilo_reloj_juego, NULL);
    pthread_detach(hilo_reloj);

    while (1) {
        struct sockaddr_in dir_cliente;
        socklen_t tamano = sizeof(dir_cliente);
        int socket_cliente = accept(socket_servidor, (struct sockaddr *)&dir_cliente, &tamano);
        
        if (socket_cliente < 0) continue;

        ClientePendiente *nuevo_cliente = malloc(sizeof(ClientePendiente));
        if (nuevo_cliente == NULL) { 
            close(socket_cliente);
            continue; 
        }
        
        nuevo_cliente->socket = socket_cliente;
        inet_ntop(AF_INET, &(dir_cliente.sin_addr), nuevo_cliente->ip, INET_ADDRSTRLEN);
        nuevo_cliente->puerto = ntohs(dir_cliente.sin_port);

        pthread_t hilo;
        pthread_create(&hilo, NULL, manejar_cliente, nuevo_cliente);
        pthread_detach(hilo);
    }
    return 0;
}