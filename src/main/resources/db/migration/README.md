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

Usar `V<version>__<descripcion>.sql`, con versiones únicas y crecientes.
No modificar ni renombrar migraciones aplicadas o compartidas: agregar una nueva
versión correctiva. No agregar migraciones vacías ni usar `baseline-on-migrate`
para omitir inconsistencias. Los scripts no deben contener secretos ni acceder
a Catalog. Las migraciones de prueba viven únicamente en recursos de test.
