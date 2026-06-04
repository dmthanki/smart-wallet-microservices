# 🏦 Smart Wallet Transaction & Fraud Monitoring System

An enterprise-grade, event-driven full-stack application designed to process financial transactions, detect fraudulent patterns in real-time, and dispatch asynchronous alerts.

## 🚀 System Architecture

This system relies on a **Database-Per-Service** backend pattern, communicating asynchronously via **Apache Kafka** to ensure high decoupling and scalability. A React (Vite) frontend consumes these services through a central API Gateway.

* **Client UI:** React + Tailwind CSS dashboard proxying requests to the backend.
* **API Gateway:** Spring Cloud Gateway MVC acting as the single entry point with Resilience4j circuit breakers.
* **Transaction Service:** Core ledger utilizing Pessimistic Locking (`SELECT FOR UPDATE`) to prevent double-spend exploits.
* **Fraud Detection Service:** A Java 21 sealed-interface rules engine that evaluates Kafka transaction events asynchronously.
* **Notification Service:** An asynchronous alert dispatcher utilizing a Transactional Outbox Pattern and PostgreSQL `FOR UPDATE SKIP LOCKED` for at-least-once delivery guarantees.

## 🛠️ Tech Stack & Engineering Highlights
* **Frontend:** React.js, Tailwind CSS v3, Vite
* **Backend:** Java 21, Spring Boot 3.x, Spring Cloud Gateway MVC
* **Event Broker:** Apache Kafka
* **Database:** PostgreSQL (Database-per-service isolation)
* **Concurrency:** Project Loom (Virtual Threads) enabled across Tomcat and Kafka listeners to maximize I/O throughput.
* **Modern Java Features:** Utilized Java 21 Sealed Interfaces, Record Deconstruction, and exhaustive Pattern Matching to eliminate instance-of casting and runtime bugs.

## 🐳 How to Run Locally

### 1. Boot the Infrastructure
Run the following command from the root directory to provision Kafka, Zookeeper, 3 distinct PostgreSQL databases, n8n, and Prometheus:
```bash
docker-compose -f docker/docker-compose.yml up -d
```

### 2. Compile & Run the Backend Microservices
Ensure you have Maven and Java 21 installed. Flyway migrations will automatically build the schemas on boot.
```bash
mvn clean install -DskipTests
```
Run the services on their respective ports (open separate terminal tabs for each):
* `cd api-gateway && mvn spring-boot:run` (Port 8080)
* `cd transaction-service && mvn spring-boot:run` (Port 8081)
* `cd fraud-detection-service && mvn spring-boot:run` (Async Kafka)
* `cd notification-service && mvn spring-boot:run` (Port 8083)

### 3. Run the Frontend UI
Navigate to the frontend directory to install dependencies and start the Vite development server:
```bash
cd smart-wallet-ui
npm install
npm run dev
```
Access the dashboard at **http://localhost:3000**. The Vite proxy will automatically route all `/api/v1/**` requests to the Gateway.

## 📡 API Endpoints (Via Gateway)
* **Create Transaction:** `POST http://localhost:8080/api/v1/transactions/p2p`
* **Check Notifications:** `GET http://localhost:8080/api/v1/notifications`