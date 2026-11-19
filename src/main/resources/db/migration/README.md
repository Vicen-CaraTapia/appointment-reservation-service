# Migraciones de Appointment

Flyway administra exclusivamente la evolución del esquema de Appointment.
Spring Boot lo ejecuta automáticamente al iniciar y valida el historial existente.
Hibernate permanece con `ddl-auto: none`.

A1.3 preparó el mecanismo sin inventar tablas ni duplicar los permisos del
aprovisionamiento de A1.2. A2.1 incorpora la primera migración productiva,
`V1__create_final_user_persistence_model.sql`: usuarios finales, autoridades y
su relación. No crea usuarios ni contraseñas iniciales; solo incorpora ROLE_USER.

`password_hash VARCHAR(255)` conserva el Argon2id completo calculado fuera de
JPA. Login y email tienen índices únicos sobre lower(...); la aplicación deberá
normalizarlos a minúsculas antes de guardar. La activación inicia en false como
valor seguro de persistencia; el registro de A2.2 deberá marcarla true desde
backend, sin confirmación por correo. Los campos de auditoría son internos:
created_by es obligatorio y created_date admite el default de PostgreSQL;
last_modified_by/date son opcionales hasta que haya una modificación.

Los tests aplican las migraciones en esquemas temporales, nunca en Catalog.

## Proceso de reserva — V2

`V2__create_reservation_process.sql` agrega `reservation_process`. El ID local es
BIGINT identity y se mantiene separado de `hold_id` y
`external_reservation_process_id`, que son identificadores opacos externos de tipo
TEXT. Sus restricciones UNIQUE permiten múltiples NULL antes de obtener un hold.
No existe una restricción única por profesional/fecha/hora: la concurrencia del
hold pertenece a la cátedra.

`owner_user_id` es una FK obligatoria a `app_user`, sin borrado en cascada. El
profesional se almacena únicamente como un BIGINT escalar, sin relación con Catalog.
La fecha usa DATE, la hora local TIME(6) WITHOUT TIME ZONE y las expiraciones y
fechas de auditoría TIMESTAMP WITH TIME ZONE. PostgreSQL conserva precisión de
microsegundos para los valores temporales persistidos.
La hora utiliza el mapeo JDBC directo de `LocalTime` para conservar esa precisión,
evitando su conversión intermedia a `java.sql.Time`.

La lógica de aplicación deriva `externalPatientId` como `user-<id-interno>` del
usuario final confiable del backend. La entidad y una restricción SQL verifican
que coincida con el propietario. No se obtiene de login, email, groupId ni de un
campo de propietario enviado por un cliente. Esta Issue no implementa todavía la
extracción del usuario autenticado ni una API de inicio de reserva.

Los estados locales actuales son `PENDING_HOLD` y `HELD`, almacenados como texto.
Un proceso pendiente puede carecer de identificadores externos y expiración;
`HELD` exige ambos IDs y `expires_at`. La entidad solo permite adjuntar una vez un
resultado de hold completo, sin realizar ninguna llamada externa ni modificar su
expiración. La auditoría sigue las convenciones de usuarios: creación obligatoria
y modificación con actor del backend y fecha cuando cambia una fila existente.

`PENDING_HOLD` no demuestra que la cátedra haya rechazado la solicitud ni que no
exista un hold remoto. Un timeout ambiguo no debe transformarse automáticamente
en un fallo definitivo o en un nuevo POST. A6.1 no implementa solicitudes,
reintentos, reconciliación ni transiciones por timeout. La secuencia de persistencia
y llamada remota se concretará al implementar el caso de uso de inicio.

Los futuros estados se incorporarán mediante cambios explícitos en el enum y
nuevas migraciones para sus restricciones, sin reescribir V2. No se agregan aún
tablas de eventos Kafka, teléfonos ni flujos de confirmación o cancelación.

Usar `V<version>__<descripcion>.sql`, con versiones únicas y crecientes.
No modificar ni renombrar migraciones aplicadas o compartidas: agregar una nueva
versión correctiva. No agregar migraciones vacías ni usar `baseline-on-migrate`
para omitir inconsistencias. Los scripts no deben contener secretos ni acceder
a Catalog. Las migraciones de prueba viven únicamente en recursos de test.

## Confirmación inicial — V3 (A7.1, primer incremento)

`V3__add_initial_confirmation_state.sql` agrega `WAITING_FOR_PHONE`, los nombres
históricos del paciente y una versión para control optimista con JPA `@Version`.
Las filas existentes conservan sus datos: versión inicial cero y nombres nulos.

La transición local admitida en este incremento es `HELD → WAITING_FOR_PHONE`.
Requiere nombre y apellido no blancos de 2–100 caracteres, los identificadores
externos del hold/proceso y el vencimiento autoritativo. No equivale a una reserva
finalmente confirmada: `PHONE_PENDING` es el estado de la reserva externa, no otro
estado local introducido aquí. Los datos de paciente no se toman automáticamente
de los campos opcionales del perfil de usuario.

La entidad conserva nombres tal como fueron validados y trunca el vencimiento
recibido a microsegundos para no redondearlo hacia adelante. No calcula un TTL.
La transición actualiza auditoría; el guardado transaccional confirma o revierte
nombres, estado, vencimiento, auditoría y versión juntos.

La versión detecta escrituras sobre una observación obsoleta y evita que otra
confirmación sobrescriba silenciosamente la primera. No evita por sí sola dos
llamadas REST concurrentes ni revierte efectos externos. El puerto/adaptador de
confirmación, los resultados inciertos y la coordinación REST se implementarán
en los siguientes incrementos/Issues; este cambio no ejecuta confirmaciones,
no implementa Kafka ni agrega estados futuros de recuperación.
