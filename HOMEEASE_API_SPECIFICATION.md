# 📘 HomeEase Platform - API Reference Manual

> **Backend Architecture**: Spring Boot 3.x Microservices  
> **Base URL**: `http://localhost:8080` (Main API Gateway / Backend Service)  
> **Auth Type**: Bearer JWT (`Authorization: Bearer <TOKEN>`)  
> **Data Format**: `application/json`

---

## 📑 Table of Contents
1. [Security & Authentication Guidelines](#-security--authentication)
2. [Authentication & Account APIs](#-1-authentication--account-apis)
3. [Customer Catalog & Discovery APIs](#-2-customer-catalog--discovery-apis)
4. [Booking & Order Lifecycle APIs](#-3-booking--order-lifecycle-apis)
5. [Worker Partner Operations APIs](#-4-worker-partner-operations-apis)
6. [Admin Portal Governance APIs](#-5-admin-portal-governance-apis)
7. [Microservices & Real-Time Tracking Sockets](#-6-microservices--real-time-tracking-sockets)
8. [Error Handling & HTTP Status Codes](#-7-error-handling--http-status-codes)

---

## 🔒 Security & Authentication

All protected endpoints require passing the Bearer JWT token in the HTTP Request Header:

| Header Key | Format | Example |
| :--- | :--- | :--- |
| `Authorization` | `Bearer <JWT_TOKEN>` | `Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...` |

### User Role Hierarchy
- 👤 **`CUSTOMER`**: Can browse service catalog, validate promo coupons, place bookings, view PIN code, and track assigned worker.
- 🛠️ **`WORKER`**: Can register KYC details, toggle online status, send live GPS updates, accept/reject dispatches, verify start PIN, add extra sub-services, complete jobs, and view earnings.
- 👑 **`ADMIN`**: Full administrative rights to inspect dashboard metrics, verify worker KYC, suspend/unblock accounts, manage catalog verticals, control banners/coupons, and dispatch notifications.

---

## 🔑 1. Authentication & Account APIs

---

### 🔵 `POST` `/api/v1/auth/firebase-login`

> **Summary**: Firebase Social / Phone Login  
> **Access Level**: `PUBLIC`

#### Request Body
| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `firebaseToken` | String | Yes | Firebase OAuth / Phone verification ID token |

**Example Request**:
```json
{
  "firebaseToken": "eyJhbGciOiJSUzI1NiIsImtpZCI6..."
}
```

#### Response (`200 OK`)
| Field | Type | Description |
| :--- | :--- | :--- |
| `token` | String | Signed JWT Session Bearer token |
| `userId` | UUID | Unique ID of the authenticated user |
| `fullName` | String | User's full name |
| `phoneNumber` | String | User's registered phone number |
| `email` | String | User's email address |
| `role` | String | User permission role (`CUSTOMER`, `WORKER`) |

**Example Response**:
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkMjkwZi...",
  "userId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "fullName": "John Doe",
  "phoneNumber": "+919876543210",
  "email": "john.doe@example.com",
  "role": "CUSTOMER"
}
```

---

### 🔵 `POST` `/api/v1/users/register`

> **Summary**: New User or Worker Registration  
> **Access Level**: `PUBLIC`

#### Request Body
| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `fullName` | String | Yes | Full name of the user |
| `phoneNumber` | String | Yes | Phone number |
| `email` | String | No | Email address |
| `role` | String | Yes | Account type (`CUSTOMER` or `WORKER`) |

**Example Request**:
```json
{
  "fullName": "Jane Smith",
  "phoneNumber": "+919876543211",
  "email": "jane.smith@example.com",
  "role": "WORKER"
}
```

#### Response (`200 OK`)
Returns the signed `AuthTokenResponse` object with JWT.

---

### 🔵 `POST` `/api/v1/admin/auth/login`

> **Summary**: Administrator Credential Login  
> **Access Level**: `PUBLIC`

#### Request Body
| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `email` | String | Yes | Admin account email |
| `password` | String | Yes | Admin account password |

**Example Request**:
```json
{
  "email": "admin@homeease.com",
  "password": "AdminSecurePassword123!"
}
```

#### Response (`200 OK`)
Returns `AuthTokenResponse` with `role: "ADMIN"`.

---

### 🟢 `GET` `/api/v1/users/me`

> **Summary**: Get Profile of Currently Logged-In User  
> **Access Level**: `AUTHENTICATED` (`CUSTOMER`, `WORKER`, `ADMIN`)

#### Response (`200 OK`):
```json
{
  "userId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "email": "john.doe@example.com",
  "role": "CUSTOMER"
}
```

---

## 🛍️ 2. Customer Catalog & Discovery APIs

---

### 🟢 `GET` `/api/v1/services`

> **Summary**: Get All Active Service Verticals  
> **Access Level**: `PUBLIC`

#### Response (`200 OK`):
```json
[
  {
    "serviceId": "c390f1ee-6c54-4b01-90e6-d701748f0111",
    "name": "Cleaning & Pest Control",
    "description": "Full home deep cleaning, bathroom & kitchen cleaning",
    "imageUrl": "https://assets.homeease.com/services/cleaning.png",
    "isActive": true,
    "subServices": []
  }
]
```

---

### 🟢 `GET` `/api/v1/services/{id}`

> **Summary**: Get Service Details by ID  
> **Access Level**: `PUBLIC`

| Parameter | Location | Type | Description |
| :--- | :--- | :--- | :--- |
| `id` | Path | UUID | Service category ID |

---

### 🟢 `GET` `/api/v1/services/{id}/sub-services`

> **Summary**: Get Sub-Service Packages & Addons for a Category  
> **Access Level**: `PUBLIC`

#### Response (`200 OK`):
```json
[
  {
    "subServiceId": "f490f1ee-6c54-4b01-90e6-d701748f0222",
    "serviceId": "c390f1ee-6c54-4b01-90e6-d701748f0111",
    "name": "Bathroom Deep Cleaning",
    "pricingType": "FIXED",
    "basePrice": 499.00,
    "unitLabel": "per bathroom",
    "estimatedMins": 45,
    "imageUrl": "https://assets.homeease.com/subservices/bathroom.png",
    "isActive": true,
    "addons": [
      {
        "addonId": "a190f1ee-6c54-4b01-90e6-d701748f0333",
        "subServiceId": "f490f1ee-6c54-4b01-90e6-d701748f0222",
        "name": "Exhaust Fan Cleaning",
        "price": 99.00,
        "isActive": true
      }
    ]
  }
]
```

---

### 🟢 `GET` `/api/v1/banners`

> **Summary**: Get Active Promotional Home Banners  
> **Access Level**: `PUBLIC`

---

### 🔵 `POST` `/api/v1/coupons/validate`

> **Summary**: Validate Coupon Code Eligibility  
> **Access Level**: `AUTHENTICATED` (`CUSTOMER`)

#### Request Body
| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `code` | String | Yes | Coupon promo code (e.g. `FESTIVE50`) |
| `orderValue` | Number | Yes | Current total cart order value |

**Example Request**:
```json
{
  "code": "FESTIVE50",
  "orderValue": 999.00
}
```

#### Response (`200 OK`):
```json
{
  "isValid": true,
  "code": "FESTIVE50",
  "discountType": "PERCENTAGE",
  "discountVal": 20.0,
  "calculatedDiscount": 199.80,
  "message": "Coupon applied successfully! You saved ₹199.80"
}
```

---

## 📅 3. Booking & Order Lifecycle APIs

---

### 🔵 `POST` `/api/v1/bookings`

> **Summary**: Create Service Booking Request  
> **Access Level**: `CUSTOMER`

> [!NOTE]
> Creating a booking generates a unique **4-digit PIN** (returned in response). This PIN must be given to the worker on-site to start the job timer.

#### Request Body
| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `serviceId` | UUID | Yes | Main Service Category ID |
| `subServices` | Array | Yes | List of sub-services `{ subServiceId, quantity }` |
| `addons` | Array | No | List of optional add-ons `{ addonId, quantity }` |
| `couponCode` | String | No | Validated promo code |
| `scheduledAt` | Instant | Yes | ISO 8601 scheduled timestamp |
| `userLat` | Double | Yes | Service address Latitude |
| `userLng` | Double | Yes | Service address Longitude |
| `paymentMethod` | String | Yes | Payment mode (`ONLINE`, `CASH_ON_DELIVERY`) |

**Example Request**:
```json
{
  "serviceId": "c390f1ee-6c54-4b01-90e6-d701748f0111",
  "subServices": [
    {
      "subServiceId": "f490f1ee-6c54-4b01-90e6-d701748f0222",
      "quantity": 2
    }
  ],
  "addons": [
    {
      "addonId": "a190f1ee-6c54-4b01-90e6-d701748f0333",
      "quantity": 1
    }
  ],
  "couponCode": "FESTIVE50",
  "scheduledAt": "2026-09-23T10:00:00Z",
  "userLat": 12.971598,
  "userLng": 77.594566,
  "paymentMethod": "ONLINE"
}
```

#### Response (`200 OK`):
```json
{
  "bookingId": "b100f1ee-6c54-4b01-90e6-d701748f0999",
  "userId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "userName": "John Doe",
  "userPhone": "+919876543210",
  "workerId": null,
  "workerName": null,
  "serviceName": "Cleaning & Pest Control",
  "status": "SEARCHING_WORKER",
  "pinCode": "4821",
  "scheduledAt": "2026-09-23T10:00:00Z",
  "baseAmount": 1097.00,
  "discountAmount": 199.80,
  "totalAmount": 897.20,
  "paymentStatus": "PENDING",
  "paymentMethod": "ONLINE"
}
```

---

### 🟢 `GET` `/api/v1/bookings/my-bookings`

> **Summary**: Get Customer Booking History & Active Orders  
> **Access Level**: `CUSTOMER`

---

### 🟢 `GET` `/api/v1/bookings/{id}`

> **Summary**: Get Booking Details & Live Status  
> **Access Level**: `AUTHENTICATED` (`CUSTOMER`, `WORKER`, `ADMIN`)

---

## 👷 4. Worker Partner Operations APIs

---

### 🔵 `POST` `/api/v1/workers/register-kyc`

> **Summary**: Submit Worker Partner KYC Documents  
> **Access Level**: `WORKER`

#### Request Body
| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `address` | String | Yes | Partner residential address |
| `panNumber` | String | Yes | PAN Card Number |
| `panDocUrl` | String | Yes | Uploaded PAN card image URL |
| `aadhaarDocUrl` | String | Yes | Uploaded Aadhaar card image URL |
| `bankAccountNo` | String | Yes | Bank Account Number for payouts |
| `bankIfsc` | String | Yes | Bank IFSC Code |
| `subServiceIds` | Array[UUID] | Yes | List of skilled sub-services worker can fulfill |

---

### 🔵 `POST` `/api/v1/workers/status`

> **Summary**: Toggle Worker Online/Offline Availability  
> **Access Level**: `WORKER`

**Example Request**:
```json
{
  "isOnline": true
}
```

---

### 🔵 `POST` `/api/v1/workers/location`

> **Summary**: Update Live GPS Coordinates  
> **Access Level**: `WORKER`

**Example Request**:
```json
{
  "lat": 12.972000,
  "lng": 77.595000
}
```

---

### 🔵 `POST` `/api/v1/workers/bookings/{id}/accept`

> **Summary**: Accept Incoming Job Dispatch  
> **Access Level**: `WORKER`

Transitions booking state from `SEARCHING_WORKER` to `ASSIGNED`.

---

### 🔵 `POST` `/api/v1/workers/bookings/{id}/reject`

> **Summary**: Reject Incoming Job Dispatch  
> **Access Level**: `WORKER`

> [!WARNING]
> Rejecting a dispatch request triggers an automated penalty: **Worker account is temporarily blocked for 1 hour** from receiving new dispatches.

---

### 🔵 `POST` `/api/v1/workers/bookings/{id}/verify-pin`

> **Summary**: Verify Customer Start PIN & Begin Work  
> **Access Level**: `WORKER`

#### Request Body:
```json
{
  "pinCode": "4821"
}
```
Transitions booking state to `IN_PROGRESS` and starts execution timer.

---

### 🔵 `POST` `/api/v1/workers/bookings/{id}/add-sub-service`

> **Summary**: Add On-Site Extra Sub-Services  
> **Access Level**: `WORKER`

Appends add-on services requested verbally by customer on-site and updates order total.

---

### 🔵 `POST` `/api/v1/workers/bookings/{id}/complete`

> **Summary**: Complete Service Execution  
> **Access Level**: `WORKER`

Transitions booking state to `COMPLETED` and initiates commission and payout calculation.

---

### 🟢 `GET` `/api/v1/workers/earnings`

> **Summary**: Get Worker Financial Earnings & Job Metrics  
> **Access Level**: `WORKER`

#### Response (`200 OK`):
```json
{
  "totalEarnings": 15480.00,
  "totalCommissionPaid": 3096.00,
  "netPayout": 12384.00,
  "completedBookingsCount": 28
}
```

---

## 👑 5. Admin Portal Governance APIs

---

### Dashboard & Analytics
- **`GET /api/v1/admin/dashboard/stats`**: System total users, total workers, active workers, total revenue, and total platform commission.

### Worker Management
- **`GET /api/v1/admin/workers`**: List all partner profiles.
- **`GET /api/v1/admin/workers/{id}`**: Get specific partner profile.
- **`PUT /api/v1/admin/workers/{id}/verify-kyc`**: Set `kycVerified = true` for a worker.
- **`PUT /api/v1/admin/workers/{id}/block?hours=24`**: Suspend worker account for given hours.
- **`PUT /api/v1/admin/workers/{id}/unblock`**: Lift suspension on worker account.

### User Management
- **`GET /api/v1/admin/users?role={role}`**: Filter and list users by role (`CUSTOMER`, `WORKER`).
- **`GET /api/v1/admin/users/{id}`**: Fetch user details.
- **`PATCH /api/v1/admin/users/{id}/role`**: Modify user role.

### Catalog & Content CRUD
- **`GET / POST / PUT / DELETE /api/v1/admin/services`**: Service verticals CRUD.
- **`GET / POST / PUT / DELETE /api/v1/admin/sub-services`**: Sub-service items & pricing CRUD.
- **`POST / PATCH / DELETE /api/v1/admin/banners`**: Banner promotion CRUD.
- **`GET / POST / PATCH / DELETE /api/v1/admin/coupons`**: Discount coupons CRUD.

### Fleet Location Tracking Map
- **`GET /api/v1/admin/locations/workers`**: Get current GPS of all online workers.
- **`GET /api/v1/admin/locations/customers`**: Get locations of pending/active bookings.
- **`GET /api/v1/admin/locations/live-map`**: Combined operational map data.

### Notifications & Push Alerts
- **`POST /api/v1/admin/notifications/instant`**: Dispatch instant push notification.
- **`POST /api/v1/admin/notifications/schedule`**: Schedule push notification for future time.
- **`GET /api/v1/admin/notifications`**: View dispatch logs.

---

## 📡 6. Microservices & Real-Time Tracking Sockets

---

### Real-Time STOMP WebSocket Live Tracking
- **WebSocket Endpoint**: `ws://localhost:8080/ws`
- **Destination Topic (Send)**: `/app/track/{bookingId}`
- **Subscription Topic (Listen)**: `/topic/track/{bookingId}`

**WebSocket Message Payload**:
```json
{
  "lat": 12.971598,
  "lng": 77.594566
}
```
*Coordinates are saved to Redis (`tracking:booking:{bookingId}`) and broadcast in real-time to the Customer App.*

---

### REST Fallback Live Tracking Service
- **`POST /api/v1/tracking/update-location`**: Direct HTTP fallback for sending worker location coordinates.
- **`GET /api/v1/tracking/latest/{bookingId}`**: Returns latest cached coordinates (`"12.971598,77.594566"`).

---

### Payment Webhook Controller
- **`POST /api/v1/payments/webhooks`**: Receives asynchronous payment callbacks from gateway (Razorpay/Stripe). Verified via `X-Webhook-Signature`.

---

## ⚠️ 7. Error Handling & HTTP Status Codes

The HomeEase API uses standard HTTP response status codes:

| Code | Status | Meaning |
| :--- | :--- | :--- |
| `200` | OK | Request executed successfully. |
| `400` | Bad Request | Invalid input parameters, missing required fields, or failed validation. |
| `401` | Unauthorized | Missing or expired Bearer JWT token. |
| `403` | Forbidden | Insufficient permissions for the requested endpoint role. |
| `404` | Not Found | Requested entity (User, Booking, Service, Worker) does not exist. |
| `409` | Conflict | Duplicate account registration or state transition conflict. |
| `500` | Internal Server Error | Server or database exception. |
