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
