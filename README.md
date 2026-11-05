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
| `APPOINTMENT_CORS_ALLOWED_ORIGINS` | No | Lista separada por comas de orígenes web exactos; vacía por defecto. |
| `APPOINTMENT_DB_URL` | Sí | URL JDBC PostgreSQL, sin usuario ni contraseña embebidos. |
| `APPOINTMENT_DB_USERNAME` | Sí | Usuario exclusivo de Appointment. |
| `APPOINTMENT_DB_PASSWORD` | Sí | Contraseña externa, sin valor predeterminado. |
| `APPOINTMENT_USER_JWT_SECRET` | Sí | Clave exclusiva para usuarios finales, Base64 de al menos 64 bytes aleatorios. |
| `APPOINTMENT_USER_JWT_TTL` | No | `1h`; duración normal, positiva y en segundos enteros. |
| `APPOINTMENT_USER_JWT_REMEMBER_ME_TTL` | No | `7d`; duración larga, positiva y no menor que la normal. |
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

Para consumir la API desde un cliente web, configurá
`APPOINTMENT_CORS_ALLOWED_ORIGINS` con el origen exacto de ese cliente, por
ejemplo `http://localhost:3000`. Se permiten Authorization y Content-Type,
incluyendo solicitudes preflight, y se expone la cabecera Authorization usada
por el login. CORS no permite cookies ni credenciales automáticas. El valor vacío
no concede acceso a navegadores de otros orígenes; los clientes móviles nativos
no necesitan CORS. No uses `*` ni copies orígenes de ejemplo a producción.

Git ignora `.env`, `.env.*` y `application-local.*` de los formatos admitidos,
pero conserva `.env.example`. No agregues secretos al código, a URLs JDBC, a
argumentos de comandos ni a logs. Si guardás secretos en un archivo local,
restringí sus permisos al propietario (`chmod 600 .env`). Los permisos y el
archivo ignorado no reemplazan una gestión de secretos para producción.

La clave de usuarios finales se configura en A2.3. La identidad interna de Catalog,
las credenciales técnicas de cátedra y Kafka se configurarán en sus propias Issues.
Esas identidades y sus credenciales permanecen separadas.

## Ejecutar el servicio

Además de PostgreSQL, configurá `APPOINTMENT_USER_JWT_SECRET` en la shell o IDE
antes del arranque. Debe ser Base64 estándar de al menos 64 bytes generados por un
generador criptográfico. Para generar una nueva clave en memoria sin imprimirla
ni escribir el valor en el historial de comandos, podés usar OpenSSL:

```bash
APPOINTMENT_USER_JWT_SECRET="$(openssl rand -base64 64 | tr -d '\n')"
export APPOINTMENT_USER_JWT_SECRET
```

Conservá la clave en un gestor de secretos o archivo local protegido; no la
regeneres en cada arranque si necesitás conservar la validez de tokens emitidos.
Una clave ausente, mal codificada o demasiado corta impide iniciar el servicio.
No reutilices tokens ni claves técnicas de cátedra o de integración con Catalog.

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

# Supply an existing protected final-user signing key, without printing it.
read -r -s -p 'APPOINTMENT_USER_JWT_SECRET (Base64): ' APPOINTMENT_USER_JWT_SECRET
echo
export APPOINTMENT_USER_JWT_SECRET

docker run -d --name appointment-service-local \
  --network project-infrastructure_appointment-db-network \
  -p 127.0.0.1:8082:8082 \
  -e APPOINTMENT_DB_URL \
  -e APPOINTMENT_DB_USERNAME \
  -e APPOINTMENT_DB_PASSWORD \
  -e APPOINTMENT_USER_JWT_SECRET \
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

`FinalUserSecurityConfiguration` expone el único bean `PasswordEncoder`, creado
originalmente en A2.1 e implementado
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
activación inmediata y ROLE_USER. A2.3 agrega login por credenciales y emisión
de JWT propios mediante `/api/authenticate`, con una cadena HTTP stateless.
A2.4 incorpora validación Bearer e identidad autenticada para las APIs de usuario.
Todavía no incluye CORS, control de propiedad de reservas, reservas,
disponibilidad, Kafka, Redis ni integraciones con Catalog o la cátedra.

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
contrato HTTP sin base externa. El test de arranque sustituye el puerto
`UserRegistrationStore` por un mock; A2.3 también sustituye el repositorio usado
por el adaptador de autenticación. Bajo `postgres-integration`,
`UserRegistrationIT` prueba el registro real, restricciones, concurrencia y
rollback de la cuenta si falla la relación de autoridad. `UserRegistrationHttpIT`
levanta HTTP en un puerto aleatorio y verifica registro, duplicados, rechazo de
campos administrativos, errores seguros y persistencia Argon2id. Ambos crean y
eliminan exclusivamente sus esquemas temporales `appointment_it_<uuid>`.

A2.2 no modifica V1, el encoder, las credenciales de A1.2 ni la infraestructura.
Login, JWT, filtros de autenticación, verificación por correo y recuperación de
contraseña quedan fuera de este incremento.

## Seguridad y firma de usuarios finales — incremento A2.3

`FinalUserDetailsService` busca exclusivamente por login sin distinguir mayúsculas
y convierte el resultado de persistencia a `FinalUserPrincipal`; no busca por
email ni devuelve entidades JPA a capas externas. El principal conserva el ID y
las autoridades del usuario. Su `toString()` está redactado y Spring Security
borra sus credenciales después de una autenticación exitosa.

`FinalUserSecurityConfiguration` conecta un `DaoAuthenticationProvider`
con ese servicio y el mismo bean Argon2id de A2.1. `ProviderManager` rechaza
contraseñas incorrectas, identidades desconocidas y usuarios inactivos.
`SpringFinalUserAuthenticator` traduce esos rechazos a una excepción genérica,
sin conservar mensajes ni causas de Spring. Los fallos del servicio de
autenticación se distinguen de credenciales inválidas. El token de credenciales
enviado al manager también se limpia al terminar, incluso ante un rechazo.

`FinalUserHttpSecurityConfiguration` mantiene públicos exclusivamente
`POST /api/register`, `POST /api/authenticate` y GET/HEAD de
`/actuator/health`. Desde el segundo incremento de A2.4, las demás rutas de
`/api/**` requieren un JWT válido de usuario final; el resto de las rutas
permanece denegado. El
renderizado de errores del contenedor puede ejecutarse en un dispatch ERROR;
esto no permite acceder directamente a `/error`. No hay formularios de login,
HTTP Basic, sesiones persistentes ni cookies de remember-me. CSRF se desactiva
porque el mecanismo aprobado utiliza credenciales explícitas y JWT, sin
autenticación automática por cookies de sesión.

Spring Boot 4.1.1 administra Spring Security y OAuth2 JOSE 7.1.1. La firma se
realiza con `NimbusJwtEncoder` y una clave `HmacSHA512`; no se agrega JJWT,
otro encoder de contraseñas ni filtros JWT propios. A2.4 incorpora el decoder
y el soporte Bearer de OAuth2 Resource Server administrado por Spring Boot.
`FinalUserJwtProperties` distingue la configuración de usuario final del resto
de las identidades. El issuer es `appointment-reservation-service` y
la audiencia para KMP es `turnos-kmp-app`. El issuer es un identificador textual,
no un endpoint que deba resolverse por HTTP.

La validación de clave ocurre al crear el bean firmante, con mensajes fijos que
no incluyen el valor recibido ni causas del decoder Base64. Las duraciones deben
ser positivas, expresarse en segundos enteros y la duración remember-me no puede
ser menor que la normal. El objeto de propiedades también tiene `toString()`
redactado. Ninguna clave real se guarda en ejemplos o recursos de prueba.

Los tests generan claves aleatorias efímeras en memoria. La verificación normal
no requiere `APPOINTMENT_USER_JWT_SECRET` ni PostgreSQL externo. Los tests MVC
cargan explícitamente la cadena real de seguridad y excluyen el usuario de
desarrollo automático de Spring; el test de bootstrap sustituye los límites de
persistencia por mocks. Los tests PostgreSQL mantienen sus esquemas temporales
aislados y reciben la clave de prueba por propiedades internas del contexto.

### Contrato de autenticación

`POST /api/authenticate`, con `Content-Type: application/json`, recibe únicamente:

```json
{
  "username": "persona",
  "password": "<contraseña-del-usuario>",
  "rememberMe": false
}
```

`username` es obligatorio, no blanco, de 1–254 caracteres, y se interpreta
exclusivamente como el login registrado. La búsqueda no distingue mayúsculas;
no existe una búsqueda alternativa por email. `password` es obligatoria, no
blanca y de 4–100 caracteres, preservada exactamente, incluidos espacios y
mayúsculas. `rememberMe` omitido vale false; un null explícito se rechaza.
Las propiedades desconocidas, incluidos roles, IDs, hashes y claims enviados
por el cliente, provocan 400 antes de autenticar.

El éxito responde `200 OK`, cuerpo `{"id_token":"<jwt-usuario-final>"}` y
cabecera `Authorization: Bearer <jwt-usuario-final>`. La respuesta usa
`Cache-Control: no-store` y no crea una sesión ni una cookie de autenticación.
El cuerpo contiene solo el token; el principal de Spring nunca se serializa.

`AuthenticateUserUseCase` normaliza el login y llama a un único puerto,
`FinalUserAuthenticator.authenticate(login, password, rememberMe)`, que devuelve
el token de aplicación sin exponer Spring Security, HTTP o JPA. El adaptador
`SpringFinalUserAuthenticator` verifica las credenciales con el manager y solo
entonces delega en el emisor concreto. No se necesita un modelo de identidad
intermedio ni un segundo puerto dedicado a firmar.
`NimbusFinalUserTokenIssuer` construye una lista explícita de claims; nunca
serializa una entidad o principal completo:

| Claim | Valor |
| --- | --- |
| `sub` | Login normalizado del usuario autenticado. |
| `userId` | Identificador interno obtenido del backend. |
| `auth` | Autoridades del principal cargadas desde Appointment, ordenadas y separadas por espacios. |
| `iat` | Instante de emisión UTC, con precisión de segundos. |
| `exp` | Emisión más el TTL normal o remember-me configurado. |
| `iss` | `appointment-reservation-service`. |
| `aud` | `turnos-kmp-app`. |

La firma usa explícitamente HS512. El TTL predeterminado es 1 hora o 7 días
con `rememberMe: true`; ambos se configuran externamente. Un `Clock` inyectado
permite probar tiempos de emisión y expiración sin esperas. No se agregan refresh
tokens, tablas de sesión ni mecanismos de revocación en esta Issue.
Los claims no incluyen contraseñas, hashes, claves de firma ni credenciales
técnicas. El JWT de usuario final no reutiliza los JWT de cátedra o Catalog.
Spring Security 7 agrega marcadores de factor como `FACTOR_PASSWORD` al resultado
de autenticación; esos marcadores técnicos no se copian al claim de permisos.

| HTTP | Código | Comportamiento |
| --- | --- | --- |
| 400 | `VALIDATION_ERROR` | Campos inválidos; solo se devuelven sus nombres. |
| 400 | `INVALID_REQUEST` | JSON inválido, cuerpo ausente o propiedades no admitidas. |
| 401 | `INVALID_CREDENTIALS` | Respuesta idéntica para contraseña incorrecta, login desconocido o cuenta inactiva. |
| 503 | `AUTHENTICATION_UNAVAILABLE` | Fallo del servicio de autenticación, por ejemplo persistencia no disponible. |
| 500 | `AUTHENTICATION_FAILED` | Error interno inesperado, incluida una imposibilidad de emitir el token. |

Los errores usan `ProblemDetail`; no incluyen valores rechazados, SQL, hashes,
contraseñas, tokens ni detalles internos. El manejador registra mensajes fijos
o el tipo de excepción, sin mensajes técnicos ni causas. Request, comando,
token de aplicación y response tienen `toString()` redactado.

Las pruebas unitarias verifican firma con clave correcta/incorrecta, algoritmo,
claims, duraciones normal/remember-me y configurables, expiración, normalización,
errores y ausencia de tokens o contraseñas en los logs capturados. Desde A2.4,
las pruebas del decoder usan también la configuración productiva y JWT reales
firmados con claves efímeras; no requieren una clave externa ni PostgreSQL.
`FinalUserAuthenticationHttpIT`, bajo `postgres-integration`, prueba registro y
login reales, los dos TTL, rechazo de email como identidad alternativa, cuentas
inactivas, errores 401 idénticos y un fallo de almacenamiento inyectado solo en
su esquema temporal. También confirma que health y registro siguen públicos,
y que el JWT emitido supera la autenticación Bearer: una ruta inexistente
devuelve 404 en MVC, en lugar del rechazo 401 anterior a A2.4.

La configuración de contraseñas, autenticación y composición JWT se consolida en
`FinalUserSecurityConfiguration`. La cadena HTTP permanece en su configuración
servlet independiente y `FinalUserJwtProperties` conserva el binding tipado.
Clave y encoder son detalles privados de `NimbusFinalUserTokenIssuer`, no beans
de Spring. Su construcción como singleton no lazy valida clave y duraciones al
arrancar, con los mismos mensajes protegidos. El reloj sigue siendo inyectable
en su constructor para las pruebas. Registro y autenticación reciben el mismo
bean Argon2id; no se modifican algoritmo, parámetros ni contrato HTTP.


## Validación Bearer e identidad autenticada — incremento A2.4

Las APIs protegidas reciben el JWT exclusivamente mediante
`Authorization: Bearer <jwt-usuario-final>`. No se aceptan tokens desde cookies,
formularios ni parámetros de consulta. OAuth2 Resource Server procesa la
cabecera mediante los filtros estándar de Spring Security, utilizando el
`JwtDecoder` de `FinalUserSecurityConfiguration`.

El decoder reutiliza la clave externa de A2.3 y la misma rutina de validación de
Base64 y longitud mínima. Encoder y decoder mantienen sus propias instancias
criptográficas privadas; no hay un segundo secreto ni beans `SecretKey` o
`JwtEncoder`. Solamente se acepta HS512. Se exige `exp` y se validan expiración y
`nbf`, si está presente, sin tolerancia temporal adicional. Se comprueban el
issuer exacto, la audiencia KMP, un `sub` no blanco, un `userId` numérico entero
positivo representable como `long` y un `auth` no vacío con roles `ROLE_*`
separados por un único espacio. También se comprueban los tipos originales de
claims firmados para evitar que las conversiones de Nimbus oculten valores mal
formados, por ejemplo un sujeto numérico convertido automáticamente a texto.

`JwtAuthenticationConverter` usa `sub` como nombre y convierte `auth` en
autoridades sin agregar `SCOPE_`. El principal del `SecurityContext` es el `Jwt`
validado proporcionado por Spring, diferente del principal utilizado para
comprobar contraseñas durante el login. No se crea otro tipo de principal.
Los futuros adaptadores web podrán obtener ese principal con
`@AuthenticationPrincipal Jwt` y entregar el identificador autenticado al caso
de uso correspondiente. Un `userId` libre enviado por KMP no establece ni
modifica esa identidad. No se introduce un puerto de usuario actual antes de
que un caso de uso concreto lo necesite.

Spring Security 7 añade `FACTOR_BEARER` al contexto como metadato del mecanismo
de autenticación. Ese marcador no se incorpora al JWT ni sustituye los roles
persistidos. Cada petición vuelve a validar su Bearer token; no se crea una
sesión y no se recupera autenticación de una sesión HTTP existente.

| Petición | Política |
| --- | --- |
| `POST /api/register` y `POST /api/authenticate` sin token | Públicas. |
| `GET` y `HEAD /actuator/health` sin token | Públicos. |
| Resto de `/api/**` | Requiere JWT válido. |
| Otras rutas, incluidos otros endpoints Actuator | Denegadas. |

Un Bearer inválido se rechaza incluso si acompaña una petición a una ruta
pública; `permitAll` no omite el procesamiento de una credencial presentada.
Los errores de filtros usan `application/problem+json`, `Cache-Control:
no-store` y detalles fijos: 401 con código `UNAUTHORIZED` y desafío
`WWW-Authenticate: Bearer`; 403 con código `ACCESS_DENIED`. No incluyen el token,
la clave ni la causa técnica del rechazo. Los errores propios del login
conservan su contrato de A2.3, incluido `INVALID_CREDENTIALS`.

Los tests HTTP usan el decoder real y tokens emitidos con claves efímeras.
Comprueban identidad, roles, suplantación por parámetros, rechazo de tokens
inválidos, vencidos o firmados con otra clave, ausencia de sesión, aislamiento
entre peticiones y errores 401/403. El endpoint que muestra identidad existe
únicamente en tests y está excluido del escaneo normal de componentes.

Este incremento no agrega CORS, control de propiedad de reservas ni endpoints
de negocio. La desactivación de una cuenta impide nuevos logins; los JWT ya
emitidos mantienen su validez hasta expirar. No se consulta la base de datos por
cada petición ni se implementa revocación de tokens en este alcance.
