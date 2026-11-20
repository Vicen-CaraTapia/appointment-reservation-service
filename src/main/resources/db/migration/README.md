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
llamadas REST concurrentes ni revierte efectos externos. Las operaciones del
puerto/adaptador se describen abajo. La representación de resultados inciertos
y la coordinación REST se resolverán en A7.3; este cambio no ejecuta
confirmaciones remotas, no implementa Kafka ni agrega estados futuros de recuperación.

### Acceso desde aplicación y guardado de la aceptación (A7.1)

`ReservationProcessStore.findOwned` consulta por ID local y usuario autenticado,
en una transacción de lectura, y devuelve una observación inmutable con un token
de versión. Un ID inexistente y uno ajeno producen el mismo resultado vacío.
No se devuelve una entidad JPA ni se conserva una transacción para llamadas remotas.

`recordInitialConfirmation` vuelve a comprobar propietario, versión, estado
`HELD` e ID del proceso externo antes de registrar la aceptación. El vencimiento
es el recibido en la confirmación, con la precisión conservadora descrita arriba.
El resultado se entrega después del commit local. Una actualización obsoleta o
incompatible se diferencia de un fallo de almacenamiento, incluso si falla el
commit después del flush; las excepciones públicas de aplicación omiten causas
SQL y datos del paciente. Los modelos con datos personales redactan `toString()`.

Este puerto no realiza la confirmación REST. Un conflicto local después de una
aceptación remota no demuestra un fracaso remoto ni autoriza repetir el POST;
la coordinación y la incertidumbre siguen siendo responsabilidad de A7.3.

## Intento durable de confirmación — V4 (A7.3, primer incremento)

`V4__add_confirmation_attempt_state.sql` conserva V1–V3 y sus filas existentes.
Agrega el estado **local** `CONFIRMATION_PENDING` a los constraints y exige los
nombres del paciente, ambos IDs externos y vencimiento mientras el intento está
pendiente. No agrega tablas, columnas de Kafka ni estados finales externos.

Este incremento reemplaza la transición directa preparada en A7.1 por:

```text
HELD → CONFIRMATION_PENDING → WAITING_FOR_PHONE
                  ↓
                 HELD (solo si se sabe que no hubo aceptación remota)
```

- `claimInitialConfirmation` comprueba propietario, versión, estado `HELD` y
  vencimiento observado; recibe el instante del reloj controlable de aplicación.
  Guarda nombres y auditoría y entrega la observación con su nueva versión solo
  después del commit local. El siguiente incremento podrá enviar el POST después
  de obtener esta observación. La comprobación local no sustituye a cátedra ni
  elimina la necesidad de volver a revisar el plazo antes del envío.
- `recordInitialConfirmation` ahora exige el intento pendiente, su versión,
  proceso externo y los mismos nombres reclamados. Guarda el vencimiento remoto
  con precisión conservadora y pasa a `WAITING_FOR_PHONE`.
- `releaseInitialConfirmation` exige la versión del intento pendiente, vuelve a
  `HELD` y borra los nombres del intento. No extiende el vencimiento ni cambia IDs
  o propietario. Solo debe invocarse si el POST no se envió o existe un rechazo
  que permita afirmar que no hubo aceptación; nunca por un timeout, una respuesta
  perdida, un 5xx o `RESERVATION_PROCESS_ALREADY_CONFIRMED`.

Cada modificación usa una transacción local corta y control optimista. Dos
solicitudes concurrentes no pueden obtener permiso de envío para la misma
versión. Si se libera un intento y se reclama otro, la nueva versión impide que
una respuesta o liberación vieja actúe sobre el intento nuevo.

`CONFIRMATION_PENDING` no afirma que cátedra recibió la solicitud: puede representar
un envío en curso o un resultado todavía no verificado, incluso una caída antes
del POST. No se restablece automáticamente al reiniciar ni al vencer el hold:
ninguna de esas condiciones demuestra que el efecto remoto no ocurrió. Una
operación local rechazada por vencimiento tampoco inventa un estado externo.

El caso de uso será responsable de elegir las transiciones según la evidencia
remota, sin mantener una transacción durante REST. La conciliación y recuperación
automática siguen fuera de este incremento. No hay todavía nuevas rutas HTTP,
llamadas de confirmación desde aplicación, procesamiento Kafka ni reintentos.

### Coordinación de aplicación del intento (A7.3)

`ConfirmReservationProcessUseCase` usa la identidad autenticada y el ID local para
consultar el proceso propio. No recibe holdId, ID de proceso externo, identidad
de paciente ni versión desde un controlador. Los nombres llegan mediante
`ConfirmationPatient`, con las validaciones contractuales y `toString()` redactado.

Para un proceso `HELD`, comprueba el plazo con un `Clock` inyectable, consulta
profesional y agenda vigentes a través de `ReservationSlotValidator`/Catalog y
revisa nuevamente el plazo. No consulta ocupaciones ni crea otro hold. Después
del commit de `claimInitialConfirmation`, vuelve a revisar el plazo antes del
POST. Si todavía no se envió y ya venció, libera esa versión del intento y
reporta el vencimiento observado; no afirma un fracaso remoto.

`AppointmentHoldConfirmer` expresa el límite REST en términos de aplicación.
`CatedraAppointmentHoldConfirmationAdapter` reutiliza el cliente de A7.2 y
traduce sus resultados y errores, sin propagar DTO HTTP ni causas técnicas al
núcleo. No se agregan credenciales, clientes HTTP alternativos ni nuevos filtros.
El reloj de producción usa UTC, siguiendo las configuraciones existentes.

Solo una aceptación correlacionada guarda `WAITING_FOR_PHONE` mediante el puerto
de persistencia. La aplicación no suma un TTL ni redondea el vencimiento remoto.
Catalog y el POST se ejecutan fuera de las transacciones de persistencia.

Una repetición con los mismos nombres sobre `CONFIRMATION_PENDING` o
`WAITING_FOR_PHONE` devuelve el estado observado sin consultar Catalog, revisar
otra vez su plazo ni ejecutar un nuevo POST. Nombres distintos generan conflicto
sin reemplazar los del intento. Si se pierde la carrera por la versión al
reclamar, se consulta una vez el estado propio; se devuelve progreso compatible
si existe, o se informa el conflicto. No se intenta reclamar o enviar en bucle.

Los siete rechazos funcionales inequívocos del contrato permiten liberar el
intento antes de informar su código. Un fallo de esa liberación se informa como
fallo local y no habilita un POST nuevo. `RESERVATION_PROCESS_ALREADY_CONFIRMED`
no libera el intento ni demuestra confirmación final: solo permite devolver una
aceptación local que pueda verificarse con los mismos IDs y paciente; si no está
verificada se mantiene el error controlado. Esa comprobación usa únicamente una
lectura local, no un endpoint remoto inventado.

Timeout, transporte, 5xx, respuestas incompatibles y rechazos HTTP no clasificados
conservan el intento pendiente. Ante un fallo de guardado posterior a aceptación,
se consulta una vez el estado local: una aceptación ya persistida y correlacionada
puede devolverse; en otro caso se informa `LOCAL_STATE_FAILURE`, sin liberar ni
reenviar. Los errores de autenticación técnica siguen separados de los del
usuario final. No se conservan causas con diagnósticos externos.

No hay todavía un controlador público de confirmación (A7.4), un consumidor
Kafka (M-A8) ni recuperación automática (M-A10). El estado local de espera no es
una reserva definitivamente confirmada.

## Modelo asíncrono — A8.1, primer incremento

Esta sección concreta la propuesta aprobada para Open-003. Se basa en las
secciones 15.1 y 15.4–15.9 de la referencia oficial v2 y en DD-008/DD-009.
Es un modelo de dominio preparatorio: este incremento no agrega migraciones,
columnas, consumidores, productores ni rutas. V1–V4 permanecen intactas y todavía
no admiten los estados nuevos en PostgreSQL. Ningún flujo productivo los escribe.

### Estados locales y evidencia

Se conservan `PENDING_HOLD`, `HELD`, `CONFIRMATION_PENDING` y `WAITING_FOR_PHONE`.
Se preparan los siguientes estados:

| Estado local | Significado |
| --- | --- |
| `PHONE_SUBMISSION_PENDING` | Intención de envío aceptada de forma durable; publicación todavía pendiente o incierta. |
| `WAITING_FOR_CONFIRMATION` | Publicación reconocida por el broker; todavía falta un resultado autoritativo de cátedra. |
| `CONFIRMED` | Confirmación final correlacionada, con `reservationId` externo. |
| `EXPIRED` | Expiración comunicada por el contrato de cátedra. |
| `INVALID` | Invalidez comunicada por el contrato de cátedra. |

Los últimos tres son terminales para M-A8. No incorporan cancelación de M-A9.
`PHONE_PENDING` pertenece a la reserva externa y no se copia como otro estado
local. `WAITING_FOR_PHONE` ya existía después del 202 REST: por sí solo no prueba
que se haya recibido la solicitud Kafka ni habilita el envío del teléfono.
Esa acción exige además una solicitud `PHONE_NUMBER` vigente y correlacionada,
con su `eventId` original disponible como `requestEventId`.

### Tabla de transiciones

Las señales son conceptos de dominio. El futuro adaptador mapeará los eventos
v1 del contrato sin introducir DTO Kafka en el núcleo. Antes de aplicar la tabla,
la aplicación debe verificar IDs, propietario persistido, solicitud e intención
vigentes, versión y vencimiento cuando corresponda. Una coincidencia de estado
no sustituye esas comprobaciones.

| Estado observado | Señal/evidencia | Resultado de la política |
| --- | --- | --- |
| `PENDING_HOLD`, `HELD` o `CONFIRMATION_PENDING` | Cualquiera de los cinco eventos entrantes de M-A8 | `DEFER`: conservar el evento pendiente; no inventar aceptación REST ni marcarlo aplicado. |
| Esos mismos estados | Aceptación local de envío o acknowledgment de publicación | `CONFLICT`: no se ha habilitado ese paso del flujo. |
| `WAITING_FOR_PHONE` | `AdditionalInformationRequested` válido y correlacionado | `APPLY`, conservando el estado y registrando la solicitud, correlación y plazo. |
| `WAITING_FOR_PHONE` | Nueva intención de teléfono validada y persistida | `APPLY` → `PHONE_SUBMISSION_PENDING`. |
| `PHONE_SUBMISSION_PENDING` | Acknowledgment del broker para la intención vigente | `APPLY` → `WAITING_FOR_CONFIRMATION`. |
| `PHONE_SUBMISSION_PENDING` o `WAITING_FOR_CONFIRMATION` | `AdditionalInformationRejected` correlacionado | `APPLY` → `WAITING_FOR_PHONE`, con razón controlada y solicitud original; permite corrección dentro del plazo. |
| `WAITING_FOR_PHONE` o `WAITING_FOR_CONFIRMATION` | Acknowledgment tardío/repetido de publicación | `IGNORE`, sin reemplazar el progreso. |
| Cualquiera de los tres estados de espera/envío asíncrono | `AppointmentConfirmed` correlacionado | `APPLY` → `CONFIRMED`, con el identificador externo de reserva. |
| Cualquiera de esos tres estados | `AppointmentProcessExpired` o `AppointmentProcessInvalid` correlacionado | `APPLY` → `EXPIRED` o `INVALID`, respectivamente. |
| `PHONE_SUBMISSION_PENDING` o `WAITING_FOR_CONFIRMATION` | Otra solicitud que no fue deduplicada o nueva intención incompatible | `CONFLICT`: no reemplazar la solicitud/intención actual silenciosamente. |
| `WAITING_FOR_PHONE` | Rechazo sin una intención activa correlacionada | `CONFLICT`, sin inventar un envío previo. |
| Terminal | Nueva intención de teléfono | `CONFLICT`; no publicar. |
| Terminal | Solicitud/rechazo tardío, acknowledgment tardío o resultado terminal compatible | `IGNORE`, conservando la evidencia y el estado final. |
| Terminal | Otro resultado terminal contradictorio | `CONFLICT`, sin sobrescribir el primero ni resolver por orden de llegada. |

`APPLY` también puede conservar el estado: recibir y guardar una solicitud produce
un efecto útil aunque el proceso ya esté en `WAITING_FOR_PHONE`. `IGNORE` solo
significa que no cambia el proceso; no autoriza un descarte silencioso ni un ack
antes de registrar el tratamiento durable. `DEFER` y `CONFLICT` mantienen el
estado actual y exigen una continuación o revisión explícita.

Un resultado puede llegar mientras todavía se registra el acknowledgment del
producer. Por eso puede avanzar directamente desde `PHONE_SUBMISSION_PENDING`
y el acknowledgment posterior no hace retroceder el estado. La aplicación debe
comparar también el ID de la intención actual: un acknowledgment viejo no puede
completar una corrección nueva aunque coincida el estado.

La política no detecta duplicados ni correlaciones por sí sola. Dos resultados
`CONFIRMED` solo son compatibles si los identificadores/datos contractuales
también coinciden; la aplicación debe detectar una contradicción antes de usar
la decisión de estado. No se decide por `message`, `detail` ni por el simple
orden de `occurredAt`.

### Persistencia y continuación que faltan implementar

- Inbox específica de eventos de reserva: distinguir pendiente, aplicado y
  tratamiento incompatible/ignorado, sin equiparar recepción con aplicación.
  `eventId` será la identidad funcional única. Comparar contenido contractual
  validado para detectar reutilización incompatible; campos adicionales de v1
  y diferencias de orden JSON no deben crear un conflicto falso.
- Efecto de negocio, auditoría y registro de aplicación del evento en una misma
  transacción PostgreSQL. Un rollback no deja un evento falsamente aplicado.
- Correlación durable por proceso/hold/solicitud y propietario ya existente.
  Eventos desconocidos, inválidos o todavía no correlacionados requieren un
  tratamiento durable explícito; no se inventa un topic DLT de cátedra.
- Los eventos tempranos se conservarán pendientes. A8.2 deberá continuar los
  pendientes por proceso después del commit de aceptación y mediante revisión
  acotada al reiniciar/periódicamente, sin bloquear indefinidamente una partición.
  Los límites y la política de errores se concretarán antes de activar el listener.
  El barrido por sí solo no resuelve un `CONFIRMATION_PENDING` cuyo REST quedó
  incierto: deberá concretarse cómo consolidar la aceptación con evidencia Kafka
  autoritativa y correlacionada, antes de aplicar la solicitud pendiente, sin
  reenviar el POST. La política de estados de este incremento no implementa esa
  coordinación ni debe invocarse en bucle esperando que cambie el estado solo.
- Intención de publicación específica y durable: conservar `eventId`,
  `requestEventId`, correlación, teléfono normalizado y progreso del envío.
  Repetir esa intención conserva su identidad; corregir después de rechazo crea
  otra identidad de envío sobre la solicitud original. A8.3 implementará la
  coordinación con el broker, sin transacción distribuida ni exactamente una vez.
- Una reserva final requiere conservar el `reservationId` externo separado del
  ID local y del `reservationProcessId`. Los rechazos corregibles conservan su
  razón controlada y el vencimiento autoritativo, sin reemplazar al propietario.

No se agrega un TTL ni se renueva el plazo por lectura, duplicado o retry. El reloj
local puede impedir un envío nuevo fuera de plazo, pero no inventa un evento de
expiración ni convierte un resultado incierto en fracaso definitivo.

La integración deberá revisar la carrera con A7.3 y su respuesta HTTP: un guardado
REST tardío no podrá sobrescribir progreso Kafka o estados finales. En este primer
incremento A7.3, su API y sus respuestas siguen sin cambios; todavía no existe un
consumidor capaz de producir esa carrera. Las decisiones anteriores no equivalen
a haber implementado idempotencia, outbox, recuperación ni persistencia nueva.
