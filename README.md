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
