# 🏠 HomeEase Backend

A production-ready, microservices-oriented backend for an **on-demand home services platform** — built with **Spring Boot 3.2**, **Java 17**, and a modern cloud-native stack.

HomeEase connects customers with verified service professionals for home maintenance, repairs, cleaning, and more — with real-time booking, dispatch, tracking, and billing.

## 🏗️ Tech Stack

- **Framework:** Spring Boot 3.2 (Spring Web, Security, Data JPA, WebSocket, Validation)
- **Database:** MySQL with Flyway migrations & Hibernate Spatial
- **Caching:** Redis
- **Auth & Notifications:** Firebase Admin SDK (Authentication + FCM Push Notifications)
- **Build Tool:** Maven
- **Java Version:** 17

## ✨ Key Features

- **Service Catalog Management** — Hierarchical services, sub-services, and add-ons with flexible pricing models
- **Booking State Machine** — Complete booking lifecycle with stage-based transitions
- **Smart Dispatch Engine** — Radius-based (5 km) worker matching with timeout-aware candidate assignment
- **Real-Time Tracking** — WebSocket-based live location tracking for ongoing bookings
- **Billing Engine** — Automated invoicing with platform commission (2%) and multi-mode payments
- **Coupon System** — Discount and promotional coupon management
- **Push Notifications** — Firebase Cloud Messaging with scheduled cron-based notification jobs
- **Admin Panel APIs** — Full CRUD for services, workers, banners, coupons, bookings, and analytics
- **Review System** — Post-service ratings and feedback

## 📦 Microservices

| Service | Description |
|---|---|
| `homeease-backend` | Core monolith — auth, catalog, bookings, billing, admin |
| `homeease-dispatch-service` | Worker dispatch and assignment logic |
| `homeease-notification-service` | FCM push notification delivery |
| `homeease-tracking-service` | Real-time GPS tracking via WebSocket |

## 🗄️ Data Model

16 entities including User, Worker, Booking, Payment, Service, SubService, ServiceAddon, Coupon, Banner, Review, Notification, and more.

## 🚀 Getting Started

### Prerequisites
- Java 17+
- MySQL 8.x
- Redis
- Firebase Service Account JSON

### Run Locally
```bash
# Clone the repo
git clone https://github.com/<your-username>/homeease-backend.git

# Configure environment variables
export DB_HOST=localhost DB_PORT=3306 DB_NAME=homeease_db
export DB_USER=root DB_PASS=root
export REDIS_HOST=localhost REDIS_PORT=6379

# Build & Run
./mvnw spring-boot:run
