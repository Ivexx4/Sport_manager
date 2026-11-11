# Clube Volei — Fase 0 (Fundações Multitenant & Autenticação)

Java 25, Spring Boot 4.1.2, Maven, PostgreSQL 17 e Flyway.
Isolamento por clube com `tenant_id` + Row Level Security (RLS).

## Requisitos

- JDK 25 e Maven 3.9+
- Docker e Docker Compose (PostgreSQL local e testes Testcontainers)

## Arrancar a base de dados e a aplicação

```bash
docker compose up -d
mvn spring-boot:run