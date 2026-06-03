# 🏦 Smart Wallet Transaction & Fraud Monitoring System

An enterprise-grade, event-driven microservices architecture designed to process financial transactions, detect fraudulent patterns in real-time, and dispatch asynchronous alerts.

## 🚀 System Architecture

This system relies on a **Database-Per-Service** pattern, communicating asynchronously via **Apache Kafka** to ensure high decoupling and scalability. All external traffic is routed through a central API Gateway.

[Client / Front-End]
|
v (HTTP POST)
[API Gateway :8080] ---(Fallback 503)---> [Resilience4j Circuit Breaker]
|
v (Route: /v1/transactions)
[Transaction Service :8081]
|---> [Pessimistic Lock] ---> [(Postgres: txn_db)]
|---> [Publish Event] ------> [Kafka: txn.created]

[Kafka Broker]
|---> [Consume Event] ------> [Fraud Detection :8082]
|---> [(Postgres: fraud_db)]
|---> [Publish: fraud.verdict] ---> [Kafka Broker]

[Kafka Broker]
|---> [Consume Event] ------> [Notification Service :8083]
|---> [(Postgres: notif_db)]
|---> [Skip Locked Polling] ---> [Outbox Scheduler]
|
v (POST)
[n8n Webhook :5678]

## 🛠️ Tech Stack & Engineering Highlights
* **Language & Framework:** Java 21 / 25, Spring Boot 3.x, Spring Cloud Gateway MVC
* **Concurrency:** Project Loom (Virtual Threads) enabled across Tomcat and Kafka listeners to maximize I/O throughput.
* **Data Integrity:** Implemented `@Lock(LockModeType.PESSIMISTIC_WRITE)` to prevent race conditions and double-spend exploits during concurrent account balance updates.
* **Resilience:** Engineered a **Transactional Outbox Pattern** utilizing PostgreSQL `FOR UPDATE SKIP LOCKED` and an exponential backoff scheduler for 100% at-least-once webhook delivery guarantees.
* **Fault Tolerance:** Configured global and instance-specific **Resilience4j Circuit Breakers** at the API Gateway to prevent cascading system failures.
* **Modern Java Features:** Utilized Java 21 Sealed Interfaces, Record Deconstruction, and exhaustive Pattern Matching inside the Fraud Rules Engine to eliminate instance-of casting and runtime bugs.

## 🐳 How to Run Locally

**1. Boot the Infrastructure (WSL / Docker)**
Run the following command from the root directory to provision Kafka, Zookeeper, 3 distinct PostgreSQL databases, n8n, and Prometheus:
`docker compose -f docker/docker-compose.yml up -d`

**2. Compile & Run the Services**
Ensure you have Maven installed. Flyway migrations will automatically build the schemas on boot.
`mvn clean compile`

Run the services on their respective ports:
* `api-gateway` (Port 8080)
* `transaction-service` (Port 8081)
* `fraud-detection-service` (Async Kafka)
* `notification-service` (Port 8083)

## 📡 API Endpoints (Via Gateway)
* **Create Transaction:** `POST http://localhost:8080/v1/transactions`
* **Check Notifications:** `GET http://localhost:8080/admin/notifications`