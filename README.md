# Appointment Reservation Service

Backend independiente de Spring Boot para la gestión de turnos y reservas del
sistema distribuido de Programación 2.

## Requisitos

- Java 25
- No es necesario instalar Maven globalmente: el repositorio incluye Maven
  Wrapper.

## Configuración de base de datos

La aplicación requiere una base PostgreSQL propia configurada mediante
variables de entorno. No guardes credenciales reales en el repositorio.

```bash
export APPOINTMENT_DB_URL='jdbc:postgresql://<host>:<port>/appointment_db'
export APPOINTMENT_DB_USERNAME='appointment_user'
read -r -s -p 'APPOINTMENT_DB_PASSWORD: ' APPOINTMENT_DB_PASSWORD
echo
export APPOINTMENT_DB_PASSWORD
```

Hibernate no crea ni modifica el esquema de la base de datos. Spring Boot ejecuta
Flyway automáticamente y valida el historial al iniciar. Las convenciones están
en [db/migration/README.md](src/main/resources/db/migration/README.md).
La primera V1 productiva queda reservada para A2.1; A1.3 no inventa tablas ni
duplica los permisos del aprovisionamiento de A1.2.

## Configuración externa y perfiles

[.env.example](.env.example) documenta los nombres y valores de ejemplo.
**Spring Boot no carga `.env` automáticamente.** Configurá las variables desde
la shell, la configuración de ejecución del IDE o el entorno del contenedor.
Copiar la plantilla a `.env` sirve como referencia local; no configura la
aplicación por sí solo. No ejecutes la plantilla como script: contiene placeholders.

| Variable | Necesaria | Valor predeterminado / propósito |
| --- | --- | --- |
| `SERVER_PORT` | No | `8082`; puerto HTTP. |
| `APPOINTMENT_DB_URL` | Sí | URL JDBC PostgreSQL, sin usuario ni contraseña embebidos. |
| `APPOINTMENT_DB_USERNAME` | Sí | Usuario exclusivo de Appointment. |
| `APPOINTMENT_DB_PASSWORD` | Sí | Contraseña externa, sin valor predeterminado. |
| `APPOINTMENT_TEST_DB_URL` | Solo integración | URL de una base descartable de Appointment. |
| `APPOINTMENT_TEST_DB_USERNAME` | Solo integración | Usuario con permiso de crear esquemas de prueba. |
| `APPOINTMENT_TEST_DB_PASSWORD` | Solo integración | Contraseña externa para las pruebas PostgreSQL. |

La aplicación usa la configuración común de `application.yml` sin requerir un
perfil de desarrollo o producción. Para PostgreSQL instalado en el host o en
Docker, cambiá únicamente la URL externa según el host y puerto publicados.
Podés reutilizar el mismo JAR con distintos valores sin recompilar.

El perfil Spring `test`, definido exclusivamente en recursos de test, desactiva
el datasource para el test de bootstrap/HTTP. No se empaqueta en el JAR.
El perfil Maven `postgres-integration` selecciona las pruebas con PostgreSQL real;
no activa el perfil Spring `test` y exige las variables `APPOINTMENT_TEST_DB_*`.

Git ignora `.env`, `.env.*` y `application-local.*` de los formatos admitidos,
pero conserva `.env.example`. No agregues secretos al código, a URLs JDBC, a
argumentos de comandos ni a logs. Si guardás secretos en un archivo local,
restringí sus permisos al propietario (`chmod 600 .env`). Los permisos y el
archivo ignorado no reemplazan una gestión de secretos para producción.

JWT de usuarios, identidad interna de Catalog, credenciales técnicas de cátedra
y Kafka se configurarán en sus propias Issues. Se mantendrán como identidades
separadas y valores externos; esta Issue no define nombres ni secretos futuros.

## Ejecutar el servicio

```bash
./mvnw spring-boot:run
```

El servicio inicia en el puerto `8082` por defecto. Se puede reemplazar sin
modificar archivos mediante la variable de entorno `SERVER_PORT`:

```bash
SERVER_PORT=8090 ./mvnw spring-boot:run
```

## Health check

Una vez iniciado, el estado de la aplicación está disponible en:

```text
http://localhost:8082/actuator/health
```

La respuesta esperada incluye:

```json
{
  "status": "UP"
}
```

## Ejecución con Docker

Requiere Docker con acceso al registro de imágenes y a las dependencias Maven.
La construcción multietapa compila con Maven 3.10.0 y Java 25; no necesita un JAR
local, `.env`, credenciales ni archivos fuera del repositorio. Los tests se
ejecutan por separado mediante los comandos de verificación.

```bash
docker build -t appointment-reservation-service:local .
```

La imagen final usa Java 25 JRE, un usuario sin privilegios y `curl` para consultar
Actuator. `.dockerignore` limita el contexto al Dockerfile, POM y fuentes de
producción; excluye configuración local, secretos y recursos de prueba.
No pases secretos como argumentos de construcción.

Para usar el PostgreSQL de Appointment de la infraestructura compartida,
confirmá primero que está iniciado y verificá el nombre de su red. En el entorno
de desarrollo actual es `project-infrastructure_appointment-db-network`.
Configurá las variables en la shell sin imprimir la contraseña:

```bash
export APPOINTMENT_DB_URL='jdbc:postgresql://appointment-postgres:5432/appointment_db'
export APPOINTMENT_DB_USERNAME='appointment_user'
read -r -s -p 'APPOINTMENT_DB_PASSWORD: ' APPOINTMENT_DB_PASSWORD
echo
export APPOINTMENT_DB_PASSWORD

docker run -d --name appointment-service-local \
  --network project-infrastructure_appointment-db-network \
  -p 127.0.0.1:8082:8082 \
  -e APPOINTMENT_DB_URL \
  -e APPOINTMENT_DB_USERNAME \
  -e APPOINTMENT_DB_PASSWORD \
  appointment-reservation-service:local
```

`appointment-postgres` es el nombre DNS del servicio PostgreSQL en esa red.
Dentro del contenedor, `localhost` identifica al propio contenedor y el puerto
de la base es `5432`, no el `55433` publicado para conexiones desde el host.
No conectes Appointment a la red ni a la base de Catalog.

Para PostgreSQL instalado en el host, usá una URL externa con
`host.docker.internal:<puerto>`. En Linux agregá al `docker run`
`--add-host=host.docker.internal:host-gateway`. PostgreSQL debe escuchar en una
interfaz accesible desde Docker y autorizar esa conexión en sus reglas de acceso;
la publicación de puertos de la aplicación no configura PostgreSQL.

El health check consulta `/actuator/health` y respeta `SERVER_PORT` (8082 por
defecto). Si cambiás el puerto interno, agregá `-e SERVER_PORT=<puerto>` y ajustá
el puerto de destino de `-p`. `EXPOSE` documenta el puerto; no lo publica.

```bash
docker inspect --format '{{.State.Health.Status}}' appointment-service-local
curl --fail http://localhost:8082/actuator/health
docker stop appointment-service-local
```

Las variables de ejecución no forman parte de la imagen, aunque quienes tengan
acceso administrativo a Docker pueden inspeccionar el entorno del contenedor.
No publiques salidas de `docker inspect` que incluyan credenciales. El Compose
general se administra fuera de este repositorio.

## Verificación

```bash
./mvnw clean verify
```

La verificación normal no requiere PostgreSQL externo. Para las pruebas reales,
configurá explícitamente una base descartable de Appointment cuyo nombre empiece
con `appointment_` y un usuario con permiso de crear esquemas:

```bash
export APPOINTMENT_TEST_DB_URL='jdbc:postgresql://<host>:<port>/appointment_test'
export APPOINTMENT_TEST_DB_USERNAME='<test-user>'
read -r -s -p 'APPOINTMENT_TEST_DB_PASSWORD: ' APPOINTMENT_TEST_DB_PASSWORD
echo
export APPOINTMENT_TEST_DB_PASSWORD
./mvnw -Ppostgres-integration clean verify
```

El perfil falla si faltan variables o no puede conectar; no omite las pruebas.
Cada prueba crea un esquema `appointment_it_<uuid>` y elimina exclusivamente ese
esquema al terminar. Nunca apuntes estas pruebas a Catalog ni a producción.
Las fixtures SQL solo están en recursos de test y no se empaquetan en el JAR.
Verifican arranque automático, historial, orden, no reaplicación y rollback ante
SQL inválido. No representan una migración productiva de Appointment.

## Alcance actual

El servicio incluye la base ejecutable de Spring Boot, Maven Wrapper, Actuator,
configuración PostgreSQL, infraestructura Flyway y el test de salud. Todavía no incluye
entidades JPA, repositorios, tablas de negocio, migraciones productivas, usuarios,
autenticación, reservas, disponibilidad, Kafka, Redis ni integraciones con
Catalog o la cátedra.
