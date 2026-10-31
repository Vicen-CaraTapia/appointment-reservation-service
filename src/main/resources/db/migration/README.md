# Migraciones de Appointment

Flyway administra exclusivamente la evolución del esquema de Appointment.
Spring Boot lo ejecuta automáticamente al iniciar y valida el historial existente.
Hibernate permanece con `ddl-auto: none`.

A1.3 prepara el mecanismo sin crear tablas de negocio ni duplicar los permisos
del aprovisionamiento de A1.2. La primera migración productiva V1 queda reservada
para A2.1, cuando se defina el modelo de persistencia de usuarios finales.

Usar `V<version>__<descripcion>.sql`, con versiones únicas y crecientes.
No modificar ni renombrar migraciones aplicadas o compartidas: agregar una nueva
versión correctiva. No agregar migraciones vacías ni usar `baseline-on-migrate`
para omitir inconsistencias. Los scripts no deben contener secretos ni acceder
a Catalog. Las migraciones de prueba viven únicamente en recursos de test.
