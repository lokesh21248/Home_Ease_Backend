# 📘 HomeEase Platform - Complete API Master Reference Manual

> **Backend Stack**: Java 17 + Spring Boot 3.x Multi-Module Microservices  
> **Base URLs**:  
> - Main Backend Service: `http://localhost:8080`  
> - Dispatch Service: `http://localhost:8081`  
> - Notification Service: `http://localhost:8082`  
> - Tracking Service: `http://localhost:8083`  
> **Auth Scheme**: Bearer JWT (`Authorization: Bearer <TOKEN>`)  
> **Data Format**: `application/json`

---

## 📑 Table of Contents
1. [Security & Role Access Levels](#-security--role-access-levels)
2. [1. Authentication & OTP APIs](#-1-authentication--otp-apis)
3. [2. Catalog & Service Discovery APIs](#-2-catalog--service-discovery-apis)
4. [3. Booking Lifecycle & Job Execution APIs](#-3-booking-lifecycle--job-execution-apis)
5. [4. Worker Partner Operations APIs](#-4-worker-partner-operations-apis)
6. [5. Billing & Invoicing Engine APIs](#-5-billing--invoicing-engine-apis)
7. [6. Admin Portal Governance APIs](#-6-admin-portal-governance-apis)
8. [7. Microservices, Webhooks & WebSockets](#-7-microservices-webhooks--websockets)
9. [8. Status Code & Error Reference](#-8-status-code--error-reference)

---

## 🔒 Security & Role Access Levels

Pass the Bearer JWT token in the HTTP Request Header for protected endpoints:
```http
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

### Roles:
- 👤 **`CUSTOMER`**: Access to service catalog, promo code validation, booking creation, personal booking history, PIN view, and live tracking.
- 🛠️ **`WORKER`**: Access to KYC submission, status toggle (online/offline), live GPS location updates, job accept/reject, PIN verification, add-on additions, job completion, and earnings.
- 👑 **`ADMIN`**: Full administrative governance over dashboard analytics, user/worker management, KYC verification, worker suspensions, catalog CRUD, banner/coupon CRUD, and notification dispatching.

---

## 🔑 1. Authentication & OTP APIs

### 🔵 `POST` `/api/v1/auth/firebase-login` (or `/api/auth/firebase-login`)
- **Access**: `PUBLIC`
- **Description**: Validates Firebase OAuth / Phone Auth ID token and issues a signed session Bearer JWT token.
- **Request Body**:
```json
{
  "firebaseToken": "eyJhbGciOiJSUzI1NiIs..."
}
```
- **Response (`200 OK`)**:
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "userId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "fullName": "John Doe",
  "phoneNumber": "+919876543210",
  "email": "john.doe@example.com",
  "role": "CUSTOMER"
}
```

---

### 🔵 `POST` `/api/auth/otp/send` (or `/api/v1/auth/otp/send`)
- **Access**: `PUBLIC`
- **Description**: Triggers phone number OTP SMS delivery for user verification.
- **Request Body**:
```json
{
  "phoneNumber": "+919876543210"
}
```
- **Response (`200 OK`)**:
```json
{
  "message": "OTP sent successfully to +919876543210",
  "otpId": "otp_17269999000",
  "status": "PENDING"
}
```

---

### 🔵 `POST` `/api/auth/otp/verify` (or `/api/v1/auth/otp/verify`)
- **Access**: `PUBLIC`
- **Description**: Verifies phone number OTP code and returns signed JWT token.
- **Request Body**:
```json
{
  "phoneNumber": "+919876543210",
  "otpCode": "123456"
}
```
- **Response (`200 OK`)**: Returns signed `AuthTokenResponse` object.

---

### 🟢 `GET` `/api/auth/refresh` (or `/api/v1/auth/refresh`)
- **Access**: `AUTHENTICATED`
- **Description**: Issues a refreshed JWT session token for the active user.

---

### 🔵 `POST` `/api/v1/users/register`
- **Access**: `PUBLIC`
- **Description**: Registers a new Customer or Worker user account.
- **Request Body**:
```json
{
  "fullName": "Jane Smith",
  "phoneNumber": "+919876543211",
  "email": "jane.smith@example.com",
  "role": "WORKER"
}
```

---

### 🔵 `POST` `/api/v1/admin/auth/login`
- **Access**: `PUBLIC`
- **Description**: Authenticates admin credentials (email & password) and returns Admin JWT.

---

### 🟢 `GET` `/api/v1/users/me`
- **Access**: `AUTHENTICATED`
- **Description**: Returns profile info decoded from active Bearer JWT token.

---

## 🛍️ 2. Catalog & Service Discovery APIs

### 🟢 `GET` `/api/v1/services` (or `/api/catalog/services`)
- **Access**: `PUBLIC`
- **Description**: Retrieves all active top-level service categories (e.g. Cleaning, Plumbing, Appliance Repair).

---

### 🟢 `GET` `/api/v1/services/{id}` (or `/api/catalog/services/{id}`)
- **Access**: `PUBLIC`
- **Description**: Retrieves details for a specific top-level service category by ID.

---

### 🟢 `GET` `/api/v1/services/{id}/sub-services` (or `/api/catalog/subservices/{id}`)
- **Access**: `PUBLIC`
- **Description**: Retrieves sub-service packages and available add-ons for a category.
- **Response (`200 OK`)**:
```json
[
  {
    "subServiceId": "f490f1ee-6c54-4b01-90e6-d701748f0222",
    "name": "Bathroom Deep Cleaning",
    "basePrice": 499.00,
    "unitLabel": "per bathroom",
    "estimatedMins": 45,
    "addons": [
      {
        "addonId": "a190f1ee-6c54-4b01-90e6-d701748f0333",
        "name": "Exhaust Fan Cleaning",
        "price": 99.00
      }
    ]
  }
]
```

---

### 🟢 `GET` `/api/v1/banners` (or `/api/catalog/banners`)
- **Access**: `PUBLIC`
- **Description**: Returns active home screen promotional hero banners.

---

### 🔵 `POST` `/api/v1/coupons/validate` (or `/api/catalog/coupon/validate`)
- **Access**: `CUSTOMER`
- **Description**: Validates promo coupon code and calculates discount amount.
- **Request Body**:
```json
{
  "code": "FESTIVE50",
  "orderValue": 999.00
}
```

---

## 📅 3. Booking Lifecycle & Job Execution APIs

### 🔵 `POST` `/api/v1/bookings` (or `/api/bookings`)
- **Access**: `CUSTOMER`
- **Description**: Creates a new booking, generates a 4-digit security PIN code, and triggers spatial dispatch matching engine.
- **Request Body**:
```json
{
  "serviceId": "c390f1ee-6c54-4b01-90e6-d701748f0111",
  "subServices": [{ "subServiceId": "f490f1ee-6c54-4b01-90e6-d701748f0222", "quantity": 2 }],
  "addons": [{ "addonId": "a190f1ee-6c54-4b01-90e6-d701748f0333", "quantity": 1 }],
  "couponCode": "FESTIVE50",
  "scheduledAt": "2026-09-23T10:00:00Z",
  "userLat": 12.971598,
  "userLng": 77.594566,
  "paymentMethod": "ONLINE"
}
```
- **Response (`200 OK`)**: Includes `bookingId`, `status: "SEARCHING_WORKER"`, `pinCode: "4821"`, total amount, discount amount.

---

### 🟢 `GET` `/api/v1/bookings/my-bookings`
- **Access**: `CUSTOMER`
- **Description**: Returns list of active and historical bookings for the logged-in customer.

---

### 🟢 `GET` `/api/v1/bookings/{id}`
- **Access**: `AUTHENTICATED`
- **Description**: Returns real-time status, worker details, PIN code, and cost breakdown for a booking ID.

---

### 🟠 `PATCH` `/api/v1/bookings/{id}/status` (or `/api/bookings/{id}/status`)
- **Access**: `AUTHENTICATED`
- **Description**: Updates booking status transition directly (`ACCEPTED`, `COMPLETED`, `REJECTED`).

---

### 🔵 `POST` `/api/v1/workers/bookings/{id}/accept`
- **Access**: `WORKER`
- **Description**: Worker accepts assigned job request. State transitions to `ASSIGNED`.

---

### 🔵 `POST` `/api/v1/workers/bookings/{id}/reject`
- **Access**: `WORKER`
- **Description**: Worker rejects offered job request. **Penalty:** Account temporarily blocked for 1 hour.

---

### 🔵 `POST` `/api/v1/workers/bookings/{id}/verify-pin`
- **Access**: `WORKER`
- **Description**: Verifies customer 4-digit start PIN on arrival to transition state to `IN_PROGRESS`.
- **Request Body**:
```json
{
  "pinCode": "4821"
}
```

---

### 🔵 `POST` `/api/v1/workers/bookings/{id}/add-sub-service` (or `/api/bookings/{id}/line-items`)
- **Access**: `WORKER`
- **Description**: Appends extra sub-services requested by customer on-site and updates order total.

---

### 🔵 `POST` `/api/v1/workers/bookings/{id}/complete`
- **Access**: `WORKER`
- **Description**: Marks job execution as completed (`COMPLETED`) and triggers payment settlement.

---

## 👷 4. Worker Partner Operations APIs

### 🔵 `POST` `/api/v1/workers/register-kyc`
- **Access**: `WORKER`
- **Description**: Submits partner address, PAN card, Aadhaar details, bank IFSC, and skills for admin KYC approval.

---

### 🔵 `POST` `/api/v1/workers/status`
- **Access**: `WORKER`
- **Description**: Toggles worker online/offline availability in Redis spatial index (`active_workers_geo`).

---

### 🔵 `POST` `/api/v1/workers/location`
- **Access**: `WORKER`
- **Description**: Sends updated live GPS latitude and longitude coordinates.

---

### 🟢 `GET` `/api/v1/workers/earnings`
- **Access**: `WORKER`
- **Description**: Retrieves partner total earnings, platform commissions paid (2%), net payout, and completed booking count.

---

## 💳 5. Billing & Invoicing Engine APIs

### 🔵 `POST` `/api/v1/billing/calculate` (or `/api/billing/calculate`)
- **Access**: `AUTHENTICATED`
- **Description**: Calculates base amount, extra line items, subtotal, 2% platform commission, and net worker payout.
- **Request Body**:
```json
{
  "baseAmount": 1000,
  "extraAmount": 200,
  "discount": 100
}
```
- **Response (`200 OK`)**:
```json
{
  "subtotal": 1100.00,
  "commission": 22.00,
  "netPayout": 1078.00,
  "currency": "INR"
}
```

---

### 🟢 `GET` `/api/v1/billing/invoice/{bookingId}` (or `/api/billing/invoice/{bookingId}`)
- **Access**: `AUTHENTICATED`
- **Description**: Returns itemized billing invoice breakdown for a booking ID.

---

## 👑 6. Admin Portal Governance APIs

- `GET /api/v1/admin/dashboard/stats`: Returns total revenue, platform commission, active workers, total users, total bookings.
- `GET /api/v1/admin/workers`: Lists all partner profiles.
- `GET /api/v1/admin/workers/{id}`: Partner details and KYC status.
- `PUT /api/v1/admin/workers/{id}/verify-kyc`: Approves worker KYC (`kycVerified = true`).
- `PUT /api/v1/admin/workers/{id}/block`: Suspends worker account for given hours (e.g. 24h).
- `PUT /api/v1/admin/workers/{id}/unblock`: Restores suspended worker account.
- `GET /api/v1/admin/locations/live-map`: Combined operational map payload of online workers & active bookings.
- `GET / POST / PUT / DELETE /api/v1/admin/services`: Full CRUD over main service verticals.
- `GET / POST / PUT / DELETE /api/v1/admin/sub-services`: Full CRUD over sub-service items and prices.
- `POST / PATCH / DELETE /api/v1/admin/banners`: Banner promotional CRUD.
- `GET / POST / PATCH / DELETE /api/v1/admin/coupons`: Promo coupons CRUD.
- `POST /api/v1/admin/notifications/instant`: Sends immediate push notification broadcast.
- `POST /api/v1/admin/notifications/schedule`: Schedules automated push notification.

---

## 📡 7. Microservices, Webhooks & WebSockets

### A. Payment Gateway Webhooks
- **Endpoint**: `POST /api/v1/payments/webhooks` (or `POST /api/payments/webhook`)
- **Headers**: `X-Webhook-Signature: <signature>`
- **Description**: Asynchronous callback handler from payment gateway (Razorpay/Stripe).

---

### B. Real-Time STOMP WebSocket Tracking
- **WebSocket Endpoint**: `ws://localhost:8080/ws`
- **Destination Topic (Send)**: `/app/track/{bookingId}`
- **Subscription Topic (Listen)**: `/topic/track/{bookingId}`
- **Payload Schema**:
```json
{
  "lat": 12.971598,
  "lng": 77.594566
}
```
- **Description**: Workers stream live coordinates over WebSocket every 5 seconds. Coordinates are cached in Redis (`tracking:booking:{bookingId}`) and broadcast to subscribers (Customer Mobile App).

---

### C. Dispatch Microservice (`homeease-dispatch-service`)
- `POST /api/v1/dispatch/trigger` (or `POST /internal/dispatch/candidates`): Triggers 5 km spatial radius worker candidate search.
- `POST /api/v1/dispatch/accept`: Handles job dispatch lock.
- `POST /api/v1/dispatch/reject` (or `POST /internal/dispatch/reject`): Re-evaluates next candidate.

---

### D. Notification Microservice (`homeease-notification-service`)
- `POST /api/v1/notifications/push` (or `/internal/notify/candidate-alert`, `/assigned`, `/pin`, `/completed`): FCM push notification dispatcher.

---

## ⚠️ 8. Status Code & Error Reference

| Code | Status | Description |
| :--- | :--- | :--- |
| `200` | OK | Request executed successfully. |
| `400` | Bad Request | Validation error or missing required payload parameters. |
| `401` | Unauthorized | Missing or expired Bearer JWT token. |
| `403` | Forbidden | Insufficient permissions for the requested endpoint role. |
| `404` | Not Found | Target entity (User, Booking, Service, Worker) does not exist. |
| `500` | Internal Error | Server or database exception. |
