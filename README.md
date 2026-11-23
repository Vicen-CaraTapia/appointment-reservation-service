<!-- calidad:inicio -->
![Calidad](https://img.shields.io/badge/Calidad-sin%20c%C3%B3digo-lightgrey) ![Aprobar](https://img.shields.io/badge/Aprobar-no-red) ![Destacar](https://img.shields.io/badge/Destacar-no-lightgrey)

**Calidad (7 dimensiones):** sin código en `main` (no evaluable).
<!-- calidad:fin -->

# Appointment Reservation Service

Backend de usuarios finales, turnos y reservas para el sistema distribuido de
Programación 2. Ofrece registro, autenticación y consulta de disponibilidad de
turnos. Las operaciones de reserva todavía no están disponibles.

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
| `CATALOG_BASE_URL` | Sí | URL HTTP(S) absoluta de Catalog, sin credenciales embebidas, query ni fragmento. |
| `CATALOG_CONNECT_TIMEOUT` | Sí | Timeout de conexión positivo; por ejemplo `5s`. |
| `CATALOG_READ_TIMEOUT` | Sí | Timeout de lectura positivo; por ejemplo `30s`. |
| `INTERNAL_CATALOG_JWT_PRIVATE_KEY_LOCATION` | Sí | URI `file:/ruta/absoluta/private.pem` de la clave privada RSA externa de Appointment. |
| `INTERNAL_CATALOG_JWT_ISSUER` | Sí | `appointment-reservation-service`, según el contrato interno con Catalog. |
| `INTERNAL_CATALOG_JWT_AUDIENCE` | Sí | `catalog-sync-service`, según el contrato interno con Catalog. |
| `INTERNAL_CATALOG_JWT_TTL` | No | `5m`; duración del JWT interno, positiva y en segundos enteros. |
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

La configuración `catalog.integration` prepara la comunicación interna con Catalog.
La clave privada RSA de Appointment debe corresponder a la clave pública configurada
en Catalog mediante `INTERNAL_CATALOG_JWT_PUBLIC_KEY_LOCATION`; la clave privada
permanece en Appointment. La autenticación interna usa RS256, separada del JWT HS512
de usuarios finales y del JWT técnico de cátedra. No se configura un token interno estático.
Al iniciar se carga una vez la clave privada RSA externa: PEM PKCS#8 sin cifrar,
con encabezado `BEGIN PRIVATE KEY` y al menos 2048 bits. El archivo debe existir
y ser legible por el usuario del proceso; mantenelo fuera del repositorio y restringí
sus permisos. Se rechazan claves ausentes, inválidas o de tamaño insuficiente con
un error redactado. El emisor interno genera JWT RS256 con la identidad de Appointment,
audiencia de Catalog, permiso `catalog:read` y expiración configurable.
El cliente interno consulta `GET /internal/api/professionals/{id}` con el JWT
automático y los timeouts configurados. Recibe profesional, categoría y horarios
semanales; conserva sus estados habilitados/deshabilitados. Los errores conservan
el status HTTP y una respuesta incompatible se rechaza como error de contrato.
Los fallos técnicos se identifican como `HTTP` (con su status), `TIMEOUT`,
`TRANSPORT` o `INVALID_RESPONSE`. La detección de timeout usa tipos de excepción,
sin interpretar mensajes remotos. Una lectura interrumpida sin una causa de timeout
identificable se informa como `TRANSPORT`; nunca se devuelve un profesional parcial.

La consulta validada de la aplicación exige una lectura actual exitosa y un profesional
habilitado. Distingue `CATALOG_TIMEOUT`, `CATALOG_UNAVAILABLE`, `CATALOG_UNAUTHORIZED`,
`CATALOG_FORBIDDEN`, `INVALID_CATALOG_RESPONSE`, `NOT_FOUND` y `DISABLED`.
Los códigos de acceso a Catalog corresponden a la identidad entre servicios.
Cada invocación vuelve a consultar la fuente; no hay caché ni reemplazo por datos
históricos. Registro y autenticación de usuarios no dependen de esta consulta.
El cálculo de disponibilidad combina esta consulta con las ocupaciones de cátedra;
no mantiene una réplica propia de horarios ni persiste los slots calculados.

Appointment y Catalog usan la misma cuenta técnica y JWT de cátedra, exclusivamente
en los backends. `groupId` identifica esa integración, no al usuario final.
Usá los nombres exactos de topics y consumer group recibidos de cátedra; no los
calcules a partir de `groupId`. Los dos timeouts son obligatorios, positivos y
aceptan formatos como `5s` y `30s`. La URL REST debe ser HTTP(S) absoluta, sin
credenciales embebidas, query ni fragmento; admite un prefijo de ruta.

El cliente backend de holds utiliza `POST /api/appointment-holds` con el JWT técnico
y exige `201 Created` con los identificadores, estado `HELD` y vencimiento remoto.
Los rechazos funcionales se distinguen por status y code, nunca por `detail`.
No reintenta automáticamente: un timeout o una respuesta inválida pueden dejar
incierto el resultado remoto. El caso de uso de inicio requiere un usuario final
autenticado y una agenda vigente válida; tras un hold exitoso guarda el proceso
en `HELD` asociado a ese usuario. Un fallo de guardado local no cancela el hold
remoto. La API para KMP es `POST /api/reservation-processes`.

El consumidor Kafka escucha el topic externo de acciones y confirma cada offset
tras persistir el resultado o el recibo durable del evento. Si falla la persistencia,
el consumidor se detiene; después de corregir la causa, reiniciá el servicio para
reentregar el mensaje. Para un consumer group sin offsets previos comienza desde
el primer registro disponible (`earliest`). No crea topics. El adaptador de envío de teléfono usa el topic configurado, espera
el acknowledgment del broker y conserva los identificadores de la intención durable;
la intención se guarda antes de publicar, sin esperar al broker dentro de una transacción.
La API pública de teléfono corresponde a A8.4.

La recuperación de envíos pendientes se ejecuta al arrancar y cada 10 segundos, en lotes
de hasta 10. Cada intento toma una reserva temporal persistida (3 minutos por defecto).
Se permiten hasta 3 intentos por envío, conservados tras reinicios, con el mismo `eventId`
y payload. El acknowledgment se espera hasta 10 segundos; ante un resultado incierto,
el próximo intento espera al menos 5 segundos. El acknowledgment no confirma la reserva.

Los valores `APPOINTMENT_PHONE_PUBLICATION_*` de `.env.example` permiten ajustar estos
límites. La reserva debe superar el timeout de acknowledgment más los 60 segundos de
bloqueo máximo del producer. `APPOINTMENT_PHONE_PUBLICATION_ENABLED=false` desactiva
el worker. Tras agotar los intentos, el envío permanece durable sin nuevas publicaciones
automáticas; el contador `publication_attempts` permite diagnosticarlo sin consultar el
teléfono. No reinicies el contador ni generes otro evento para resolver un resultado
incierto: la reconciliación general corresponde a M-A10.
El health check no verifica que el consumidor siga activo.

La prueba aislada `./mvnw -Pembedded-kafka-integration verify` utiliza Kafka embebido
para comprobar recepción, offsets, reentrega y publicación del teléfono ficticio;
no necesita la infraestructura real.

Para clientes web, `APPOINTMENT_CORS_ALLOWED_ORIGINS` admite orígenes exactos separados
por coma. Vacío no habilita otros orígenes; no se permite `*` ni autenticación por
cookies. Los clientes móviles nativos no necesitan CORS.

### Consultar el progreso de un proceso propio

`GET /api/reservation-processes/{id}` requiere el JWT final-user de su propietario y
responde `200` con `id`, `status`, `expiresAt`, `requiredAction` y `reason`.
Los procesos ajenos e inexistentes devuelven el mismo `404`; todas las respuestas
usan `Cache-Control: no-store`.

`requiredAction` es `SUBMIT_PHONE` cuando hay una solicitud de teléfono aplicada y
vigente, `CORRECT_PHONE` tras un rechazo `INVALID_PHONE_NUMBER`, o `NONE` mientras
se espera una solicitud, publicación o resultado, y en estados terminales.
Al vencer el plazo también es `NONE`; la consulta no modifica el estado durable
ni inventa un resultado remoto. `reason` contiene únicamente un código controlado
o `null`. No devuelve teléfonos, nombres ni identificadores técnicos.

KMP puede repetir este GET periódicamente para observar los eventos ya persistidos.
La consulta no publica mensajes, no aplica eventos pendientes ni consulta Catalog.

### Enviar el teléfono solicitado

`POST /api/reservation-processes/{id}/phone` requiere el JWT final-user del
propietario y `Content-Type: application/json`:

```json
{"phoneNumber":"+00 (000) 001-234"}
```

El body acepta únicamente `phoneNumber` como string no vacío. La aplicación elimina
espacios, guiones, paréntesis y puntos y valida 7–15 dígitos con `+` opcional. No se
admiten campos de propietario, grupo técnico ni IDs de Kafka.

Una intención aceptada devuelve `202 Accepted` y el mismo formato de progreso del
GET, leído nuevamente después de la operación. El estado puede seguir pendiente o
reflejar un evento de confirmación, rechazo o vencimiento recibido entretanto.
Un `202` por sí solo no confirma una reserva; KMP debe consultar el progreso.

Los errores usan `application/problem+json`, códigos estables y `no-store`:

| HTTP | Código | Significado |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` / `VALIDATION_ERROR` | ID, JSON, campos o tipo de dato inválidos. |
| 400 | `INVALID_PHONE_NUMBER` | El teléfono normalizado no cumple el formato. |
| 401 | `UNAUTHORIZED` | Falta un JWT final-user válido. |
| 404 | `RESERVATION_PROCESS_NOT_FOUND` | Proceso inexistente o ajeno. |
| 409 | `PHONE_SUBMISSION_CONFLICT` | El estado o envío actual no permite esta operación. |
| 409 | `RESERVATION_PROCESS_EXPIRED` | El plazo no permite un nuevo envío. |
| 503 | `PHONE_SUBMISSION_OUTCOME_UNKNOWN` | Fallo local; la intención podría estar guardada. |
| 500 | `RESERVATION_PERSISTENCE_FAILED` | No pudo completarse la operación o la lectura final; consultá el progreso. |

Ante un timeout HTTP o un resultado incierto, consultá primero el GET. Repetir la
misma intención conserva el envío durable según A8.3; no envíes identificadores
nuevos para forzar otra publicación. La recuperación respeta los límites configurados.


### Cancelar una reserva propia

`POST /api/reservation-processes/{id}/cancel` requiere el JWT final-user del
propietario y el ID local del proceso. El body es opcional; si se envía, usá JSON:

```json
{"reason":"Cambio de planes"}
```

`reason` puede ser nulo o tener hasta 500 caracteres. No se aceptan campos de
propietario ni identificadores técnicos. Una cancelación verificada responde
`200` con `id` y `status: CANCELLED`. Un proceso ajeno o inexistente devuelve el
mismo `404`; un estado no cancelable, `409`. Los errores de autenticación técnica
de cátedra se reportan como errores de dependencia (`502`), separados del `401`
del usuario final. Un `503 CANCELLATION_OUTCOME_UNKNOWN` conserva el intento
durable: consultá el progreso antes de repetir. Una repetición pendiente intenta
una conciliación acotada sin reenviar el POST remoto. Las respuestas usan `no-store`.

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
| `GET /api/appointment-availability` | Protegido; consulta disponibilidad por profesional y fecha. |
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

### Disponibilidad

Con Catalog disponible y la integración REST de cátedra configurada:

```http
GET /api/appointment-availability?professionalId=202&date=2026-11-17
Authorization: Bearer <jwt-usuario-final>
Accept: application/json
```

`professionalId` es obligatorio y admite int64 con signo. `date` es obligatoria y
debe ser una fecha local ISO válida, sin hora ni offset. Los parámetros ausentes o
mal formados reciben 400 con código `INVALID_REQUEST`; un Bearer ausente o inválido
recibe 401.

Respuesta de ejemplo, según los horarios y ocupaciones actuales:

```json
{
  "professionalId": 202,
  "date": "2026-11-17",
  "slots": ["11:30:00", "12:00:00", "12:30:00"]
}
```

Responde 200 con `Cache-Control: no-store`; sin horarios disponibles devuelve
`"slots": []`. KMP utiliza exclusivamente el JWT del usuario final. Las credenciales
de Catalog y cátedra permanecen en Appointment. La consulta no crea un hold ni
garantiza que una posición siga libre al intentar reservarla.

Los errores de disponibilidad usan `application/problem+json` y `Cache-Control: no-store`:

| Situación | HTTP | Código |
| --- | --- | --- |
| Parámetros ausentes o mal formados | 400 | `INVALID_REQUEST` |
| JWT de usuario ausente o inválido | 401 | `UNAUTHORIZED` |
| Profesional inexistente en el catálogo vigente | 404 | `PROFESSIONAL_NOT_FOUND` |
| Profesional deshabilitado | 409 | `PROFESSIONAL_DISABLED` |
| No se pudo verificar la respuesta o autenticación de una dependencia | 502 | `AVAILABILITY_DEPENDENCY_ERROR` |
| Dependencia indisponible | 503 | `AVAILABILITY_UNAVAILABLE` |
| Timeout al consultar una dependencia | 504 | `AVAILABILITY_TIMEOUT` |
| Fallo interno inesperado | 500 | `AVAILABILITY_FAILED` |

Un fallo de consulta no devuelve slots ni se transforma en una lista vacía. Los
problemas de autenticación entre backends no producen un 401 del usuario final.

### Iniciar un proceso de reserva

Elegí un slot de la consulta de disponibilidad y enviá una única solicitud:

```http
POST /api/reservation-processes
Authorization: Bearer <jwt-usuario-final>
Content-Type: application/json

{"professionalId":202,"date":"2026-11-19","startTime":"18:00"}
```

Reemplazá los valores de ejemplo por un profesional, fecha y horario vigentes.
Los tres campos son obligatorios: ID numérico int64 y strings ISO de fecha y hora
locales, sin offset. Los campos adicionales se rechazan; el propietario y
`externalPatientId` los determina el backend desde el usuario autenticado.

Respuesta `201 Created`, con `Cache-Control: no-store`:

```json
{"id":123,"status":"HELD","expiresAt":"2026-11-18T17:30:00Z"}
```

`id` identifica el proceso local. `expiresAt` es el vencimiento remoto del hold,
no la fecha del turno. `HELD` representa un bloqueo temporal, no una reserva confirmada.

Los errores usan `application/problem+json`, un `code` estable y `Cache-Control: no-store`:

| Situación | HTTP | Código |
| --- | --- | --- |
| Campos obligatorios ausentes/nulos o JSON inválido/no soportado | 400 | `VALIDATION_ERROR` / `INVALID_REQUEST` |
| Profesional deshabilitado u horario fuera de agenda | 400 | `PROFESSIONAL_DISABLED` / `INVALID_SLOT` |
| JWT de usuario ausente o inválido | 401 | `UNAUTHORIZED` |
| Profesional inexistente | 404 | `PROFESSIONAL_NOT_FOUND` |
| Otra reserva o hold ocupó el slot | 409 | `SLOT_ALREADY_RESERVED` / `SLOT_ALREADY_HELD` |
| Fallo de dependencia antes de solicitar el hold | 502/503/504 | `RESERVATION_DEPENDENCY_ERROR` / `RESERVATION_DEPENDENCY_UNAVAILABLE` / `RESERVATION_DEPENDENCY_TIMEOUT` |
| Credenciales técnicas o rechazo no reconocido de una dependencia | 502 | `RESERVATION_DEPENDENCY_ERROR` |
| Resultado remoto incierto al solicitar el hold | 502/503/504 | `HOLD_OUTCOME_UNKNOWN` |
| Fallo de guardado local después del hold | 500 | `RESERVATION_PERSISTENCE_FAILED` |
| Error interno inesperado | 500 | `RESERVATION_START_FAILED` |

Decidí por status y code, no por `detail`. Ante un resultado incierto o un fallo
de persistencia, el hold puede existir: no repitas automáticamente el POST.
Las credenciales técnicas nunca se devuelven a KMP; un problema de autenticación
entre backends no requiere renovar el JWT del usuario final.

### Confirmar inicialmente un proceso propio

Usá el `id` local devuelto al iniciar el proceso y un JWT de usuario final:

```http
POST /api/reservation-processes/123/confirm
Authorization: Bearer <jwt-usuario-final>
Content-Type: application/json

{"patientFirstName":"Juan","patientLastName":"Perez"}
```

Nombre y apellido son obligatorios, no blancos, de 2 a 100 caracteres y se
conservan exactamente como se reciben. El ID debe ser un entero int64 positivo.
No se aceptan propiedades adicionales: propietario, paciente externo e
identificadores de cátedra los determina el backend.

Respuesta `202 Accepted`, con `Cache-Control: no-store`:

```json
{"id":123,"status":"WAITING_FOR_PHONE","expiresAt":"2026-11-18T17:40:00Z"}
```

Si Kafka adelanta el proceso antes de que termine esta respuesta, `status` refleja
el progreso persistido: también puede ser `PHONE_SUBMISSION_PENDING`,
`WAITING_FOR_CONFIRMATION`, `CONFIRMED`, `EXPIRED` o `INVALID`. Esto conserva los
mismos campos y el vencimiento autoritativo. Un `202` por sí solo no confirma la
reserva; consultá `GET /api/reservation-processes/{id}` para el progreso posterior.

`WAITING_FOR_PHONE` indica aceptación inicial verificada y persistida; todavía
falta el flujo asíncrono para confirmar la reserva definitivamente. `expiresAt`
es el vencimiento observado del proceso, no la fecha del turno. Repetir con el
mismo paciente sobre esa aceptación devuelve el estado observado sin otro POST
remoto. Si el intento anterior está en curso o su resultado sigue sin verificarse,
puede devolverse `202` con `CONFIRMATION_PENDING`: eso no acredita aceptación
remota ni extiende el vencimiento. Un paciente diferente produce conflicto.

Los errores usan `application/problem+json`, `Cache-Control: no-store` y estos códigos:

| Situación | HTTP | Código |
| --- | --- | --- |
| Nombre/apellido ausentes o inválidos | 400 | `VALIDATION_ERROR` |
| ID inválido, JSON inválido, tipos incorrectos o campos adicionales | 400 | `INVALID_REQUEST` |
| JWT de usuario ausente o inválido | 401 | `UNAUTHORIZED` |
| Proceso inexistente o de otro usuario | 404 | `RESERVATION_PROCESS_NOT_FOUND` |
| Hold inexistente o vencido | 404 | `HOLD_NOT_FOUND` / `HOLD_EXPIRED` |
| Profesional inexistente | 404 | `PROFESSIONAL_NOT_FOUND` |
| Profesional deshabilitado u horario fuera de agenda | 400 | `PROFESSIONAL_DISABLED` / `INVALID_SLOT` |
| Estado, versión o paciente incompatibles | 409 | `RESERVATION_PROCESS_CONFLICT` |
| Slot reservado por otra reserva | 409 | `SLOT_ALREADY_RESERVED` |
| Cátedra informa aceptación anterior sin verificación local | 409 | `RESERVATION_PROCESS_ALREADY_CONFIRMED` |
| Inconsistencia de propiedad técnica o paciente externo | 502 | `HOLD_OWNER_MISMATCH` / `EXTERNAL_PATIENT_MISMATCH` |
| Credenciales técnicas, rechazo no reconocido o agenda inválida de una dependencia | 502 | `RESERVATION_DEPENDENCY_ERROR` |
| Fallo de consulta de Catalog previo a la confirmación | 503/504 | `RESERVATION_DEPENDENCY_UNAVAILABLE` / `RESERVATION_DEPENDENCY_TIMEOUT` |
| Respuesta remota inválida, indisponibilidad o timeout de confirmación | 502/503/504 | `CONFIRMATION_OUTCOME_UNKNOWN` |
| Fallo de acceso o guardado local | 500 | `RESERVATION_PERSISTENCE_FAILED` |
| Aceptación remota sin persistencia local verificable | 500 | `CONFIRMATION_PERSISTENCE_FAILED` |
| Error interno inesperado | 500 | `RESERVATION_CONFIRMATION_FAILED` |

Decidí por HTTP y `code`, nunca por el texto de `detail`. Ante resultado incierto,
aceptación anterior no verificada o fallo local, el efecto remoto puede existir:
no repitas automáticamente la operación. Los fallos de autenticación entre
backends no requieren renovar el JWT del usuario final. El envío de teléfono y
la confirmación definitiva todavía no están implementados.

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
INTERNAL_CATALOG_JWT_PRIVATE_KEY_LOCATION='file:/run/secrets/catalog-private.pem' \
docker run -d --name appointment-service-local \
  --network project-infrastructure_appointment-db-network \
  --mount type=bind,source=/absolute/path/appointment-private.pem,target=/run/secrets/catalog-private.pem,readonly \
  -p 127.0.0.1:8082:8082 \
  -e APPOINTMENT_DB_URL -e APPOINTMENT_DB_USERNAME -e APPOINTMENT_DB_PASSWORD \
  -e APPOINTMENT_USER_JWT_SECRET \
  -e CATALOG_BASE_URL -e CATALOG_CONNECT_TIMEOUT -e CATALOG_READ_TIMEOUT \
  -e INTERNAL_CATALOG_JWT_PRIVATE_KEY_LOCATION \
  -e INTERNAL_CATALOG_JWT_ISSUER -e INTERNAL_CATALOG_JWT_AUDIENCE \
  -e INTERNAL_CATALOG_JWT_TTL \
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
Reemplazá `/absolute/path/appointment-private.pem` por el archivo externo real.
La URI de clave privada apunta a su montaje dentro del contenedor. El usuario
`appuser` (UID 10001) necesita permiso de lectura sobre ese archivo.

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
Las pruebas reciben datos ficticios de cátedra. Los tests HTTP de cátedra y Catalog
utilizan servidores locales y credenciales ficticias o claves efímeras, sin conexiones
reales a esos servicios.

El perfil PostgreSQL incluye la confirmación por HTTP con registro/login reales,
propiedad del proceso, doble clic, rechazos y fallo local después de aceptación
remota. Usa un servidor de cátedra simulado localmente; no crea reservas reales.
También verifica inbox e intenciones durables de teléfono, con duplicados,
reinicio y rollback. La prueba HTTP de teléfono recorre solicitud, consulta propia,
envío, corrección y confirmación; también cubre resultados durante la publicación y
recuperación tras reinicio. Usa el serializador productor y listener reales con
transporte Kafka controlado. Estas pruebas no contactan un broker real.
Los diagnósticos SQL de Hibernate que podrían imprimir filas con datos personales
están desactivados; los manejadores de la aplicación conservan mensajes controlados.

Para verificar HTTP, persistencia y Kafka juntos, exportá las mismas variables
`APPOINTMENT_TEST_DB_*` y ejecutá:

```bash
./mvnw -Pasynchronous-workflow-integration clean verify
```

Este perfil arranca Kafka embebido, crea topics y esquemas PostgreSQL exclusivos de
prueba y los limpia al terminar. Usa los adapters reales, con REST de cátedra y
Catalog simulados. No necesita variables ni infraestructura de cátedra. Las pruebas
normales y `embedded-kafka-integration` siguen sin requerir PostgreSQL.

Para probar el cliente de Appointment contra un Catalog en ejecución, configurá
las variables `CATALOG_*` e `INTERNAL_CATALOG_JWT_*` de la tabla anterior. Catalog
debe usar la clave pública correspondiente. Indicá un profesional existente y habilitado, y un
ID confirmado como inexistente en esa instancia:

```bash
export APPOINTMENT_TEST_CATALOG_PROFESSIONAL_ID='<existing-professional-id>'
export APPOINTMENT_TEST_CATALOG_MISSING_PROFESSIONAL_ID='<confirmed-missing-professional-id>'
./mvnw -Pcatalog-integration verify
```

Reemplazá ambos placeholders antes de ejecutar. Este perfil realiza únicamente
consultas HTTP autenticadas: comprueba el cliente, la consulta de aplicación validada
y la traducción del `404` a `NOT_FOUND`, sin crear ni modificar datos. Ejecuta la
configuración, firma y cliente reales de Appointment en un contexto sin servidor HTTP, PostgreSQL ni Kafka.
No requiere arrancar el servidor completo de Appointment ni cargar sus credenciales
de base, JWT de usuarios finales o cátedra. La salida muestra solo IDs, estado y
cantidad de horarios; nunca el JWT interno ni la clave privada.

Los perfiles de integración seleccionan sus propias pruebas y guardan resúmenes
Failsafe separados, incluido `asynchronous-workflow-integration`. CI ejecuta
PostgreSQL, Kafka embebido y el flujo conjunto en una sola invocación Maven;
la suite normal se ejecuta una vez y cada perfil conserva su resumen propio.

Para comprobar acceso a metadata de los dos topics reales de cátedra, suministrá
`CATEDRA_KAFKA_BOOTSTRAP_SERVERS`, `CATEDRA_KAFKA_CONSUMER_GROUP_ID`,
`CATEDRA_KAFKA_APPOINTMENT_ACTIONS_TOPIC` y
`CATEDRA_KAFKA_APPOINTMENT_PHONE_TOPIC`, y ejecutá
`./mvnw -Pkafka-integration verify`. Esta prueba consulta metadata; no consume ni publica mensajes.

El reporte de cobertura HTML está en `target/site/jacoco/index.html`.

## CI y SonarQube Cloud

GitHub Actions ejecuta build y tests normales en PR hacia `develop`, `main` y
`milestone/**`, y en push a `develop`/`main`. Los perfiles de integración externa
son explícitos y no se ejecutan en ese workflow. El análisis Sonar espera el Quality Gate.

Configuración requerida en Settings → Secrets and variables → Actions:

| Tipo | Nombre |
| --- | --- |
| Variable | `SONAR_ORGANIZATION` |
| Variable | `SONAR_PROJECT_KEY` |
| Secret | `SONAR_TOKEN` |

Las PR de forks y ejecuciones de Dependabot omiten Sonar por no disponer de los
secretos normales de Actions, pero conservan build y tests.
