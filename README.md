# Appointment Reservation Service

Backend independiente de Spring Boot para la gestión de turnos y reservas del
sistema distribuido de Programación 2.

## Requisitos

- Java 25
- No es necesario instalar Maven globalmente: el repositorio incluye Maven
  Wrapper.

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

## Alcance actual

Este bootstrap contiene únicamente la base ejecutable de Spring Boot, Maven
Wrapper, Actuator y el test de salud. Todavía no incluye persistencia, usuarios,
autenticación, reservas, disponibilidad, Kafka, Redis ni integraciones con
Catalog o la cátedra.
