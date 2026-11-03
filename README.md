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
La primera V1 productiva se incorpora en A2.1 para usuarios finales, autoridades y
su relación. A1.3 preparó el mecanismo sin inventar tablas ni duplicar permisos.

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

## Integración continua y SonarQube Cloud

El workflow `.github/workflows/appointment-ci.yml` ejecuta checkout completo,
Temurin 25 y `./mvnw --batch-mode --no-transfer-progress clean verify` en PR hacia
`develop`, `main` o `milestone/**`, y en push a `develop` o `main`.
No requiere PostgreSQL ni credenciales de base de datos: el perfil de integración
PostgreSQL sigue siendo explícito y no se ejecuta en esta CI inicial.
Los errores de compilación o de tests hacen fallar el job `build-and-test`.

JaCoCo genera `target/site/jacoco/jacoco.xml` durante `verify`; SonarScanner para
Maven importa ese reporte desde su ubicación estándar. El reporte HTML local
está en `target/site/jacoco/index.html`. No se excluye código para alterar la
cobertura ni se agregan umbrales Maven adicionales.

Crear un proyecto propio de Appointment en SonarQube Cloud vinculado a este
repositorio y seleccionar análisis mediante GitHub Actions, desactivando el
análisis automático si estaba habilitado. Configurar en GitHub, en
Settings → Secrets and variables → Actions:

| Tipo | Nombre | Propósito |
| --- | --- | --- |
| Variable | `SONAR_ORGANIZATION` | Organización de SonarQube Cloud. |
| Variable | `SONAR_PROJECT_KEY` | Identificador del proyecto propio de Appointment. |
| Secret | `SONAR_TOKEN` | Token con permiso de analizar ese proyecto. |

No copiar la clave de proyecto de Catalog ni guardar tokens en el repositorio,
en `.env.example` o en argumentos del scanner. El workflow inyecta el token como
variable de entorno únicamente durante el análisis y no imprime su valor.
Los permisos del token de GitHub se limitan a lectura; no se necesita un PAT.

Para PR del mismo repositorio, el análisis espera el Quality Gate: una falla del
scanner o del gate hace fallar el job. Si falta configuración, el paso falla con
el nombre de la variable o secret faltante, sin mostrar valores.
En PR de forks y ejecuciones de Dependabot se conserva build/tests y se omite
Sonar porque no disponen de los secretos normales de Actions. No se utiliza
`pull_request_target` para ejecutar código de PR con credenciales.

La ejecución real en Actions, la importación de cobertura, la decoración del PR
y el Quality Gate deben comprobarse después de publicar el workflow y configurar
Sonar. Para bloquear merges, configurar las reglas de protección de GitHub con
el check requerido correspondiente; crear este archivo no activa esa regla.
La disponibilidad de análisis de ramas y PR depende del plan de SonarQube Cloud.

## Hashing de contraseñas — incremento A2.1

`PasswordEncodingConfiguration` expone un bean `PasswordEncoder` implementado
mediante `Argon2PasswordEncoder` (Argon2id). La política aprobada reemplaza la
propuesta inicial de BCrypt; no existen hashes BCrypt de usuarios que migrar.
Los parámetros son explícitos: sal de 16 bytes, hash de 32 bytes, memoria de
19456 KiB (19 MiB), 2 iteraciones y paralelismo 1. Cada llamada a `encode`
genera una sal aleatoria nueva mediante el encoder de Spring Security.

Spring Boot 4.1.1 administra `spring-security-crypto` 7.1.1. Bouncy Castle no está
administrado por ese parent: se fija `bcprov-jdk18on` 1.85 como dependencia runtime,
alineada con la versión utilizada por Spring Security 7.1.1. Argon2 necesita las
clases de ese proveedor; no se requiere instalar una biblioteca nativa ni agregar
`bcpkix`, el starter de seguridad web o filtros de autenticación.

El resultado completo tiene formato PHC
`$argon2id$v=19$m=19456,t=2,p=1$<salt-base64>$<hash-base64>`: contiene algoritmo,
versión, parámetros, sal y hash. La V1 de A2.1 crea `password_hash VARCHAR(255)`
para guardar ese texto íntegro, sin truncarlo ni separar la sal. La migración
incluye app_user, authority y app_user_authority, índices únicos de login/email
sin distinguir mayúsculas, activación y auditoría. Solo crea ROLE_USER, no cuentas
de ejemplo. La prueba PostgreSQL verifica el almacenamiento del hash completo.
`UserEntity`, `AuthorityEntity` y `UserJpaRepository` mapean esa estructura sin
generar esquema. Hibernate sigue en `none`; las pruebas de mapeo usan `validate`
solo en su contexto aislado para comprobar que las entidades coinciden con V1.

El hashing se realiza antes de persistir, fuera de entidades y repositorios.
La contraseña original solo debe utilizarse transitoriamente para codificar o
comparar mediante `PasswordEncoder`: nunca almacenarse, registrarse en logs ni
incluirse en respuestas. La capa HTTP de A2.2 utiliza DTO que no exponen el
hash ni aceptan campos internos de activación, autoridades o auditoría.
El incremento A2.1 configuró la herramienta y sus pruebas; no introdujo registro,
login, JWT, filtros ni una API que reciba o devuelva contraseñas.

Los tests unitarios comprueban coincidencia correcta, rechazo de contraseña
incorrecta, sales distintas para el mismo texto y formato/parámetros de Argon2id.
Se ejecutan con `./mvnw clean verify` sin PostgreSQL. Los parámetros criptográficos
son una política explícita, no una variable que cambie entre entornos; cualquier
ajuste futuro debe revisar compatibilidad, coste y actualización de hashes.

Fuentes de compatibilidad:
[Argon2PasswordEncoder 7.1.1](https://github.com/spring-projects/spring-security/blob/7.1.1/crypto/src/main/java/org/springframework/security/crypto/argon2/Argon2PasswordEncoder.java)
y [dependencias de Spring Security 7.1.1](https://github.com/spring-projects/spring-security/blob/7.1.1/gradle/libs.versions.toml).

## Alcance actual

El servicio incluye la base ejecutable de Spring Boot, Maven Wrapper, Actuator,
configuración PostgreSQL, Flyway, hashing Argon2id, modelo JPA de usuario/autoridades
y repositorio de persistencia. A2.2 agrega registro público de usuarios con
activación inmediata y ROLE_USER. Todavía no incluye login, JWT, autorización de
recursos, reservas, disponibilidad, Kafka, Redis ni integraciones con
Catalog o la cátedra.

## Modelo persistente de usuario — A2.1

Las clases están en `user/infrastructure/persistence/`: son representaciones de
almacenamiento y no un contrato HTTP ni el modelo de dominio de futuros casos de
uso. No se agregan puertos o adaptadores sin un caso de uso que los necesite.
Nunca deben utilizarse como request/response de registro o autenticación.

`UserEntity` recibe un Argon2id ya calculado fuera de JPA, valida su formato y
no hace hashing. La comprobación de formato no verifica criptográficamente un
hash; la codificación/comparación siguen siendo responsabilidad de PasswordEncoder.
Login/email se normalizan con `Locale.ROOT`; PostgreSQL también protege su
unicidad. El ID se genera mediante IDENTITY y no tiene setter público.

La activación inicia en false y puede cambiarse mediante una operación explícita
del backend; A2.2 la activa durante el registro. Las autoridades se asignan
desde backend y el getter devuelve una colección no modificable. La relación es
lazy, sin cascadas sobre authority; la búsqueda por login del repositorio usa un
EntityGraph para recuperar las autoridades junto al usuario. No se crean roles
nuevos implícitamente al guardar una cuenta.

La auditoría utiliza Instant y callbacks JPA: created_date al insertar y
last_modified_date al actualizar. Las operaciones de modificación reciben un
actor suministrado por backend, sin simular una identidad autenticada inexistente.
No hay setters de fechas o de auditoría de creación. Esto no reemplaza la futura
autorización del caso de uso ni permite confiar en un actor enviado por clientes.
Los cambios de autoridades también marcan la auditoría del usuario propietario.

`UserEntityTest` verifica invariantes sin base externa. `UserPersistenceIT`, bajo
postgres-integration, prueba persistir/recuperar, hash íntegro verificable,
imageUrl nullable, restricciones únicas, roles y auditoría con PostgreSQL real.
Usa esquemas temporales de Appointment y los elimina al terminar; no toca Catalog
ni migra public. La V1 publicada no se modifica en este incremento.

## Registro público de usuarios — A2.2

`POST /api/register`, con `Content-Type: application/json`, permite registrar un
usuario final sin autenticación. No llama a Catalog ni registra la cuenta técnica
de la cátedra. El registro no inicia sesión ni entrega un JWT.

| Campo | Regla |
| --- | --- |
| `login` | Obligatorio, no blanco, 1–50 caracteres, patrón de login JHipster. |
| `password` | Obligatoria, no blanca, 4–100 caracteres; se conserva íntegra para calcular el hash, incluidos espacios. |
| `firstName`, `lastName` | Opcionales, hasta 50 caracteres; admiten nulo o cadena vacía. |
| `email` | Obligatorio, formato email válido, 5–254 caracteres. |
| `imageUrl` | Opcional, hasta 256 caracteres; admite nulo o cadena vacía. No se agrega una validación de URL distinta al contrato de referencia. |
| `langKey` | No blanco, 2–10 caracteres; omitido o nulo toma `es`. No se restringe a una lista de idiomas. |

El rango de contraseña conserva el contrato explícito de
[ManagedUserVM de JHipster](https://github.com/jhipster/generator-jhipster/blob/main/generators/spring-boot/templates/src/main/java/_package_/web/rest/vm/ManagedUserVM.java.ejs).
Los límites de perfil corresponden a
[AdminUserDTO](https://github.com/jhipster/generator-jhipster/blob/main/generators/spring-boot/templates/src/main/java/_package_/service/dto/AdminUserDTO.java.ejs)
y el patrón de login a
[generator-constants.ts](https://github.com/jhipster/generator-jhipster/blob/main/generators/generator-constants.ts).
El backend normaliza login y email con `Locale.ROOT`, de la misma forma que A2.1;
no recorta la contraseña ni modifica su contenido.

Ejemplo de estructura del request; la contraseña es un placeholder:

```json
{
  "login": "persona",
  "password": "<contraseña-de-4-a-100-caracteres>",
  "firstName": "Ana",
  "lastName": "Perez",
  "email": "persona@example.test",
  "imageUrl": null,
  "langKey": "es"
}
```

Toda propiedad desconocida provoca `400 INVALID_REQUEST`, incluso si es nula.
Esto incluye `id`, `activated`, `authorities`, `passwordHash`, `createdBy`,
`createdDate`, `lastModifiedBy` y `lastModifiedDate`. La política se aplica en el
DTO con `@JsonAnySetter`, independientemente de la tolerancia global del mapper.
El ID lo genera PostgreSQL; el backend activa la cuenta, asigna exclusivamente
`ROLE_USER` y utiliza el login normalizado como actor de creación. No se crean
autoridades implícitamente: ROLE_USER debe existir por V1.

Un registro exitoso responde `201 Created` con un `RegisterUserResponse` que solo
contiene `login`, `firstName`, `lastName`, `email`, `imageUrl` y `langKey`. No
devuelve ID, activación, autoridades, contraseña, hash, auditoría ni tokens. El
cuerpo de respuesta es una decisión de esta Issue, que exige un DTO seguro; la
activación inmediata sigue el enunciado y no requiere confirmación por correo.

| HTTP | Código de error | Significado |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | Campos inválidos; `fields` contiene solo sus nombres. |
| 400 | `INVALID_REQUEST` | JSON inválido, cuerpo ausente o propiedades no admitidas. |
| 400 | `LOGIN_ALREADY_EXISTS` | Login duplicado, sin distinguir mayúsculas. |
| 400 | `EMAIL_ALREADY_EXISTS` | Email duplicado, sin distinguir mayúsculas. |
| 500 | `REGISTRATION_FAILED` | Fallo interno; no se expone su diagnóstico técnico. |

Los errores usan `ProblemDetail` (`application/problem+json`). Los duplicados
mantienen `400`, en lugar de la propuesta inicial `409`, por la prioridad dada al
contrato explícito de
[AccountResource de JHipster](https://github.com/jhipster/generator-jhipster/blob/main/generators/spring-boot/templates/src/main/java/_package_/web/rest/AccountResource.java.ejs).
Las respuestas no incluyen valores rechazados, mensajes de parseo ni detalles
SQL. El manejador de registro registra solo el tipo de excepción ante un fallo
interno; no registra mensajes, causas, requests ni contraseñas. Los modelos que
transportan contraseña o hash tienen `toString()` redactado y el password del
request es de solo escritura para Jackson. Esto no autoriza agregar logging de
cuerpos HTTP o parámetros sensibles.

El controller valida y mapea DTOs; `RegisterUserUseCase` coordina la operación sin
importar HTTP, JPA ni Spring Security. `PasswordHasher` usa el `PasswordEncoder`
Argon2id existente. `UserRegistrationStore` se implementa con
`JpaUserRegistrationAdapter`, que guarda usuario y relación de autoridad en una
transacción local mediante `TransactionTemplate`. El hashing ocurre antes de
abrir esa transacción. `spring.jpa.open-in-view: false` evita mantener una sesión
JPA abierta durante toda la petición o ejecutar consultas durante la serialización
HTTP; los datos de respuesta se obtienen dentro del adaptador. Los prechequeos no
sustituyen los índices únicos de V1:
el adaptador identifica SQLSTATE `23505` junto con `ux_app_user_login_lower` o
`ux_app_user_email_lower`, sin interpretar mensajes humanos del driver. Otros
fallos de persistencia no se clasifican como duplicados.

La verificación normal cubre DTOs, caso de uso, adaptador, conexión del encoder y
contrato HTTP sin base externa. El test de arranque sustituye únicamente el
puerto `UserRegistrationStore` por un mock. Bajo `postgres-integration`,
`UserRegistrationIT` prueba el registro real, restricciones, concurrencia y
rollback de la cuenta si falla la relación de autoridad. `UserRegistrationHttpIT`
levanta HTTP en un puerto aleatorio y verifica registro, duplicados, rechazo de
campos administrativos, errores seguros y persistencia Argon2id. Ambos crean y
eliminan exclusivamente sus esquemas temporales `appointment_it_<uuid>`.

A2.2 no modifica V1, el encoder, las credenciales de A1.2 ni la infraestructura.
Login, JWT, filtros de autenticación, verificación por correo y recuperación de
contraseña quedan fuera de este incremento.
