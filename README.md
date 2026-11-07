# Appointment Reservation Service

Backend de usuarios finales, turnos y reservas para el sistema distribuido de
Programación 2. Actualmente ofrece registro y autenticación de usuarios; las
operaciones de disponibilidad y reservas todavía no están disponibles.

## Requisitos

- Java 25; Maven se ejecuta con el wrapper incluido.
- PostgreSQL propio de Appointment con credenciales válidas.
- Configuración técnica de cátedra, incluso mientras no se invoquen endpoints funcionales.
- Docker, si se elige ejecutar la base o la aplicación en contenedores.

## Configuración

Usá [.env.example](.env.example) como referencia. Reemplazá todos los placeholders
por valores del entorno; no ejecutes la plantilla directamente. Las credenciales
reales deben quedar fuera de Git y de los logs.

Spring Boot no carga `.env` automáticamente. Suministrá las variables mediante
la terminal, el IDE o el entorno del contenedor. Si usás un `.env` propio para
cargarlo desde Bash, escribí sus valores entre comillas y protegelo con `chmod 600 .env`.

| Variable | Necesaria | Valor predeterminado / propósito |
| --- | --- | --- |
| `SERVER_PORT` | No | `8082`; puerto HTTP. |
| `APPOINTMENT_CORS_ALLOWED_ORIGINS` | No | Lista separada por comas de orígenes web exactos; vacía por defecto. |
| `APPOINTMENT_DB_URL` | Sí | URL JDBC PostgreSQL, sin usuario ni contraseña embebidos. |
| `APPOINTMENT_DB_USERNAME` | Sí | Usuario exclusivo de Appointment. |
| `APPOINTMENT_DB_PASSWORD` | Sí | Contraseña externa, sin valor predeterminado. |
| `APPOINTMENT_USER_JWT_SECRET` | Sí | Clave exclusiva para usuarios finales, Base64 de al menos 64 bytes aleatorios. |
| `APPOINTMENT_USER_JWT_TTL` | No | `1h`; duración normal, positiva y en segundos enteros. |
| `APPOINTMENT_USER_JWT_REMEMBER_ME_TTL` | No | `7d`; duración larga, positiva y no menor que la normal. |
| `CATEDRA_GROUP_ID` | Sí | Identificador técnico de la integración asignado por la cátedra; no es un usuario final. |
| `CATEDRA_TECHNICAL_JWT` | Sí | JWT técnico compartido con Catalog. Es secreto y permanece exclusivamente en los backends. |
| `CATEDRA_REST_BASE_URL` | Sí | URL base de la API REST de la cátedra. |
| `CATEDRA_REST_CONNECT_TIMEOUT` | Sí | Duración máxima para establecer la conexión REST; por ejemplo `5s`. |
| `CATEDRA_REST_READ_TIMEOUT` | Sí | Duración máxima de espera de respuesta REST; por ejemplo `30s`. |
| `CATEDRA_KAFKA_BOOTSTRAP_SERVERS` | Sí | Brokers Kafka provistos por la cátedra. |
| `CATEDRA_KAFKA_CONSUMER_GROUP_ID` | Sí | Consumer group provisto para la integración técnica. |
| `CATEDRA_KAFKA_APPOINTMENT_ACTIONS_TOPIC` | Sí | Topic asignado para eventos de acciones de turnos que envía la cátedra a Appointment. |
| `CATEDRA_KAFKA_APPOINTMENT_PHONE_TOPIC` | Sí | Topic asignado para que Appointment publique `AdditionalInformationSubmitted`. |
| `APPOINTMENT_TEST_DB_URL` | Solo integración | URL de una base descartable de Appointment. |
| `APPOINTMENT_TEST_DB_USERNAME` | Solo integración | Usuario con permiso de crear esquemas de prueba. |
| `APPOINTMENT_TEST_DB_PASSWORD` | Solo integración | Contraseña externa para las pruebas PostgreSQL. |

La clave `APPOINTMENT_USER_JWT_SECRET` debe ser Base64 de al menos 64 bytes
aleatorios y conservarse entre arranques para mantener válidos los tokens.
Es independiente del JWT técnico de cátedra. Los TTL deben ser positivos, en
segundos enteros; el de remember-me no puede ser menor que el normal.

Appointment y Catalog usan la misma cuenta técnica y JWT de cátedra, exclusivamente
en los backends. `groupId` identifica esa integración, no al usuario final.
Usá los nombres exactos de topics y consumer group recibidos de cátedra; no los
calcules a partir de `groupId`. Los dos timeouts son obligatorios, positivos y
aceptan formatos como `5s` y `30s`. La URL REST debe ser HTTP(S) absoluta, sin
credenciales embebidas, query ni fragmento; admite un prefijo de ruta.

Para clientes web, `APPOINTMENT_CORS_ALLOWED_ORIGINS` admite orígenes exactos separados
por coma. Vacío no habilita otros orígenes; no se permite `*` ni autenticación por
cookies. Los clientes móviles nativos no necesitan CORS.

## Ejecutar localmente

Configurá la conexión con la base de Appointment. Para la infraestructura Docker
del workspace, el puerto predeterminado desde el host es `55433`:

```dotenv
APPOINTMENT_DB_URL='jdbc:postgresql://127.0.0.1:55433/appointment_db'
APPOINTMENT_DB_USERNAME='appointment_user'
```

Si guardaste las variables reales en un `.env` propio y válido para Bash:

```bash
set -a
source ./.env
set +a
./mvnw spring-boot:run
```

El servicio usa el puerto `8082` por defecto. Cambialo mediante `SERVER_PORT`.
Flyway aplica y valida las migraciones al iniciar; Hibernate no genera el esquema.
Las convenciones SQL están en [db/migration/README.md](src/main/resources/db/migration/README.md).

Para crear por primera vez una clave de usuarios finales en memoria, sin imprimirla:

```bash
export APPOINTMENT_USER_JWT_SECRET="$(openssl rand -base64 64 | tr -d '\n')"
```

Conservala en configuración segura. No la regeneres en cada arranque ni la
sobrescribas después cargando un placeholder desde `.env`.

## API pública

| Ruta | Acceso |
| --- | --- |
| `GET` / `HEAD /actuator/health` | Público; estado esperado `UP`. |
| `POST /api/register` | Público; registra un usuario final. |
| `POST /api/authenticate` | Público; autentica por login y emite un JWT. |
| Resto de `/api/**` | Requiere JWT válido; una ruta no implementada responde 404 tras autenticar. |
| Otras rutas | Acceso denegado. |

```bash
curl --fail http://localhost:8082/actuator/health
```

Para las APIs protegidas, enviá `Authorization: Bearer <jwt-usuario-final>`.
Un token inválido produce 401 incluso si se presenta en una ruta pública.
Los tokens no se aceptan mediante cookies ni parámetros de consulta.

### Registro

`POST /api/register`, con `Content-Type: application/json`:

```json
{
  "login": "persona",
  "password": "<contraseña-del-usuario>",
  "email": "persona@example.test",
  "firstName": "Ana",
  "lastName": "Perez",
  "imageUrl": null,
  "langKey": "es"
}
```

`login` es obligatorio (1–50 caracteres, patrón JHipster); `email` debe ser válido
(5–254); `password` es obligatoria (4–100) y conserva espacios y mayúsculas.
Los nombres son opcionales, hasta 50 caracteres; `imageUrl`, opcional hasta 256;
`langKey`, 2–10, usa `es` si se omite o es nulo. Login y email no distinguen mayúsculas.
Los campos desconocidos o administrativos son rechazados.

Responde 201 con login y datos de perfil, sin contraseña, hash, ID interno ni JWT.
La cuenta queda activa y recibe `ROLE_USER`. El registro no inicia sesión.

### Autenticación

`POST /api/authenticate`, con `Content-Type: application/json`:

```json
{
  "username": "persona",
  "password": "<contraseña-del-usuario>",
  "rememberMe": false
}
```

`username` es el login, no el email: obligatorio, 1–254 caracteres. La contraseña
es obligatoria, 4–100, y se preserva íntegra. `rememberMe` omitido es false;
un nulo explícito o propiedades desconocidas producen 400.

Responde 200 con `{"id_token":"<jwt-usuario-final>"}` y cabecera
`Authorization: Bearer <jwt-usuario-final>`. TTL predeterminado: 1 hora o 7 días
con remember-me. Una cuenta inactiva, un login desconocido y una contraseña
incorrecta reciben el mismo error genérico. No se crean sesiones ni cookies.

Los errores usan `application/problem+json`. Códigos útiles:

| Operación | HTTP | Código |
| --- | --- | --- |
| Registro/login: campos inválidos | 400 | `VALIDATION_ERROR` |
| JSON/campos desconocidos | 400 | `INVALID_REQUEST` |
| Login o email duplicado al registrar | 400 | `LOGIN_ALREADY_EXISTS` / `EMAIL_ALREADY_EXISTS` |
| Credenciales inválidas | 401 | `INVALID_CREDENTIALS` |
| Bearer ausente o inválido en API protegida | 401 | `UNAUTHORIZED` |
| Acceso denegado por seguridad | 403 | `ACCESS_DENIED` |
| Persistencia no disponible al autenticar | 503 | `AUTHENTICATION_UNAVAILABLE` |
| Fallo interno de registro/login | 500 | `REGISTRATION_FAILED` / `AUTHENTICATION_FAILED` |

## Docker

```bash
docker build -t appointment-reservation-service:local .
```

Cargá previamente las variables reales en la terminal, como en el arranque local.
Si PostgreSQL se ejecuta en la infraestructura compartida, el nombre de red
predeterminado es `project-infrastructure_appointment-db-network`; verificá el
nombre si usás otro proyecto Compose. Dentro de esa red usá el DNS del servicio
y el puerto `5432`, en vez del puerto publicado en el host:

```bash
APPOINTMENT_DB_URL='jdbc:postgresql://appointment-postgres:5432/appointment_db' \
docker run -d --name appointment-service-local \
  --network project-infrastructure_appointment-db-network \
  -p 127.0.0.1:8082:8082 \
  -e APPOINTMENT_DB_URL -e APPOINTMENT_DB_USERNAME -e APPOINTMENT_DB_PASSWORD \
  -e APPOINTMENT_USER_JWT_SECRET \
  -e CATEDRA_GROUP_ID -e CATEDRA_TECHNICAL_JWT -e CATEDRA_REST_BASE_URL \
  -e CATEDRA_REST_CONNECT_TIMEOUT -e CATEDRA_REST_READ_TIMEOUT \
  -e CATEDRA_KAFKA_BOOTSTRAP_SERVERS -e CATEDRA_KAFKA_CONSUMER_GROUP_ID \
  -e CATEDRA_KAFKA_APPOINTMENT_ACTIONS_TOPIC -e CATEDRA_KAFKA_APPOINTMENT_PHONE_TOPIC \
  appointment-reservation-service:local
```

Para PostgreSQL instalado en el host, usá `host.docker.internal:<puerto>` en la
URL JDBC; en Linux agregá `--add-host=host.docker.internal:host-gateway` y verificá
que PostgreSQL permita conexiones desde Docker. El Compose compartido se administra
fuera de este repositorio; esta imagen no incluye las bases ni la infraestructura de cátedra.

El health check usa `/actuator/health`. Si configurás otro `SERVER_PORT`, pasalo
con `-e SERVER_PORT` y ajustá el puerto de destino de `-p`.

```bash
docker inspect --format '{{.State.Health.Status}}' appointment-service-local
docker stop appointment-service-local
```

## Pruebas

```bash
./mvnw clean verify
```

La suite normal no requiere PostgreSQL ni servicios reales de cátedra.
Para el perfil PostgreSQL, configurá una base descartable de Appointment cuyo
nombre comience con `appointment_` y un usuario con permiso de crear esquemas:

```bash
export APPOINTMENT_TEST_DB_URL='jdbc:postgresql://<host>:<port>/appointment_test'
export APPOINTMENT_TEST_DB_USERNAME='<test-user>'
read -r -s -p 'Contraseña de pruebas: ' APPOINTMENT_TEST_DB_PASSWORD
printf '\n'
export APPOINTMENT_TEST_DB_PASSWORD
./mvnw -Ppostgres-integration clean verify
```

Reemplazá host, puerto y usuario antes de ejecutar. El perfil falla si faltan
variables o no puede conectar. Cada test crea y elimina exclusivamente su esquema
`appointment_it_<uuid>`; no apuntes estas pruebas a Catalog ni a producción.
Las pruebas reciben datos ficticios de cátedra. Los tests HTTP del cliente REST
utilizan servidores locales, sin credenciales ni conexiones reales externas.

El reporte de cobertura HTML está en `target/site/jacoco/index.html`.

## CI y SonarQube Cloud

GitHub Actions ejecuta build y tests normales en PR hacia `develop`, `main` y
`milestone/**`, y en push a `develop`/`main`. El perfil PostgreSQL es explícito y
no se ejecuta en ese workflow. El análisis Sonar espera el Quality Gate.

Configuración requerida en Settings → Secrets and variables → Actions:

| Tipo | Nombre |
| --- | --- |
| Variable | `SONAR_ORGANIZATION` |
| Variable | `SONAR_PROJECT_KEY` |
| Secret | `SONAR_TOKEN` |

Las PR de forks y ejecuciones de Dependabot omiten Sonar por no disponer de los
secretos normales de Actions, pero conservan build y tests.
