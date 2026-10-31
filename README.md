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

Hibernate no crea ni modifica el esquema de la base de datos. Las migraciones
versionadas se incorporarán en una Issue posterior mediante Flyway.

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

## Verificación

```bash
./mvnw clean verify
```

## Alcance actual

El servicio incluye la base ejecutable de Spring Boot, Maven Wrapper, Actuator,
configuración de conexión PostgreSQL y el test de salud. Todavía no incluye
entidades JPA, repositorios, tablas, migraciones Flyway, usuarios,
autenticación, reservas, disponibilidad, Kafka, Redis ni integraciones con
Catalog o la cátedra.
