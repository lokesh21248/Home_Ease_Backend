# HomeEase Admin Portal — API & Frontend Integration Guide

**Base URLs**:
* **Monolith Backend (Primary)**: `http://3.107.161.126:8080/api/v1` *(or `http://localhost:8080/api/v1`)*
* **Dispatch Service**: `http://3.107.161.126:8081/api/v1`
* **Live Tracking Service**: `http://3.107.161.126:8082/api/v1`
* **Notification Service**: `http://3.107.161.126:8083/api/v1`

**Default Headers for Admin Endpoints**:
* `Content-Type: application/json`
* `Authorization: Bearer <ADMIN_JWT_TOKEN>`

---

## ⚡ Quick Start: Axios Client Setup

```javascript
import axios from 'axios';

export const MONOLITH_BASE_URL = 'http://3.107.161.126:8080/api/v1';

export const adminClient = axios.create({
  baseURL: MONOLITH_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Automatically inject JWT Token from LocalStorage
adminClient.interceptors.request.use((config) => {
  const token = localStorage.getItem('adminToken');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});
```

---

## 🔐 1. Admin Authentication

### 1.1 Admin Login
Authenticate admin credentials and retrieve a JWT bearer token.
* **Method**: `POST`
* **Path**: `/admin/auth/login` (Full URL: `http://3.107.161.126:8080/api/v1/admin/auth/login`)
* **Headers**: `Content-Type: application/json`
* **Request Body**:
  ```json
  {
    "email": "admin@homeease.com",
    "password": "AdminPassword123!"
  }
  ```
* **Response (200 OK)**:
  ```json
  {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "userId": "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
    "fullName": "Super Admin",
    "email": "admin@homeease.com",
    "phoneNumber": "+919876543210",
    "role": "ADMIN",
    "isVerified": true
  }
  ```
* **Frontend Action**: Store `token` in `localStorage.setItem('adminToken', response.data.token)`.

### 1.2 Get Current Admin Profile
* **Method**: `GET`
* **Path**: `/users/me`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`
* **Response (200 OK)**:
  ```json
  {
    "userId": "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
    "fullName": "Super Admin",
    "email": "admin@homeease.com",
    "phoneNumber": "+919876543210",
    "role": "ADMIN",
    "createdAt": "2026-01-01T00:00:00Z"
  }
  ```

---

## 📊 2. Dashboard Analytics

### 2.1 Get Summary KPI Statistics
Fetches system-wide aggregate metrics for the dashboard hero cards.
* **Method**: `GET`
* **Path**: `/admin/dashboard/stats`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`
* **Response (200 OK)**:
  ```json
  {
    "totalUsersCount": 1420,
    "totalWorkersCount": 350,
    "activeWorkersCount": 85,
    "totalBookingsCount": 5120,
    "completedBookingsCount": 4890,
    "totalRevenue": 1254300.00,
    "totalPlatformCommission": 188145.00
  }
  ```

---

## 💳 3. Payments & Financials Management

### 3.1 Get All Payments
* **Method**: `GET`
* **Path**: `/admin/payments`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`
* **Response (200 OK)**:
  ```json
  [
    {
      "paymentId": "550e8400-e29b-41d4-a716-446655440000",
      "bookingId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
      "customerName": "Rahul Sharma",
      "customerPhone": "+919876543210",
      "transactionRef": "pay_O7d8f9g0h1j2k3",
      "grossAmount": 899.00,
      "platformCommissionPercent": 15.00,
      "platformCommissionAmount": 134.85,
      "workerPayoutAmount": 764.15,
      "status": "SUCCESS",
      "createdAt": "2026-10-26T10:15:30Z"
    }
  ]
  ```

### 3.2 Get Payment By ID
* **Method**: `GET`
* **Path**: `/admin/payments/{id}`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`

### 3.3 Update Payment Status
* **Method**: `PATCH`
* **Path**: `/admin/payments/{id}/status`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`, `Content-Type: application/json`
* **Request Body**:
  ```json
  {
    "status": "REFUNDED"
  }
  ```
  *(Allowed statuses: `PENDING`, `SUCCESS`, `FAILED`, `REFUNDED`)*

---

## 👷 4. Worker Partner Governance

### 4.1 Get All Worker Partners
* **Method**: `GET`
* **Path**: `/admin/workers`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`
* **Response (200 OK)**:
  ```json
  [
    {
      "workerId": "7b8c9d0e-1f2a-3b4c-5d6e-7f8a9b0c1d2e",
      "user": {
        "userId": "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
        "fullName": "Ramesh Kumar",
        "phoneNumber": "+919123456789",
        "email": "ramesh@example.com"
      },
      "address": "Madhapur, Hyderabad",
      "avatarUrl": "https://...",
      "experienceYears": 4,
      "panNumber": "ABCDE1234F",
      "panDocUrl": "https://...",
      "aadhaarNumber": "123456789012",
      "aadhaarDocUrl": "https://...",
      "bankAccountNo": "912345678901",
      "bankIfsc": "HDFC0001234",
      "kycStatus": "PENDING",
      "isVerified": false,
      "isOnline": true,
      "currentLat": 17.4485,
      "currentLng": 78.3748,
      "blockedUntil": null
    }
  ]
  ```

### 4.2 Get Worker By ID
* **Method**: `GET`
* **Path**: `/admin/workers/{id}`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`

### 4.3 Verify Worker KYC
Approves the worker's submitted PAN/Aadhaar documents and marks partner verified.
* **Method**: `PUT`
* **Path**: `/admin/workers/{id}/verify-kyc`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`

### 4.4 Block Worker
Temporarily suspends worker dispatch matching for specified hours (default 24h).
* **Method**: `PUT`
* **Path**: `/admin/workers/{id}/block?hours=24`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`

### 4.5 Unblock Worker
Removes dispatch lock immediately.
* **Method**: `PUT`
* **Path**: `/admin/workers/{id}/unblock`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`

---

## 👥 5. User / Customer Management

### 5.1 Get All Users
* **Method**: `GET`
* **Path**: `/admin/users` *(Optional Query Param: `?role=CUSTOMER` or `?role=WORKER`)*
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`
* **Response (200 OK)**:
  ```json
  [
    {
      "userId": "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
      "fullName": "Rahul Sharma",
      "phoneNumber": "+919876543210",
      "email": "rahul@example.com",
      "role": "CUSTOMER",
      "createdAt": "2026-09-15T08:00:00Z",
      "isWorker": false,
      "isWorkerVerified": false,
      "isWorkerBlocked": false
    }
  ]
  ```

### 5.2 Get User By ID
* **Method**: `GET`
* **Path**: `/admin/users/{id}`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`

### 5.3 Update User Role
* **Method**: `PATCH`
* **Path**: `/admin/users/{id}/role`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`, `Content-Type: application/json`
* **Request Body**:
  ```json
  {
    "role": "ADMIN"
  }
  ```
  *(Allowed roles: `CUSTOMER`, `WORKER`, `ADMIN`)*

---

## 🛠️ 6. Services Catalog Management

### 6.1 Get All Services (Admin View)
Returns all active and inactive service categories.
* **Method**: `GET`
* **Path**: `/admin/services`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`
* **Response (200 OK)**:
  ```json
  [
    {
      "serviceId": "be8bb640-cc11-4243-ab4a-5cbdf34a0937",
      "name": "AC & Appliance Repair",
      "description": "Expert diagnostics, maintenance & installation",
      "imageUrl": "https://supabase.../services/ac.png",
      "isActive": true,
      "createdAt": "2026-01-10T12:00:00Z"
    }
  ]
  ```

### 6.2 Create Service
* **Method**: `POST`
* **Path**: `/admin/services`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`, `Content-Type: application/json`
* **Request Body**:
  ```json
  {
    "name": "Plumbing Services",
    "description": "Pipes, leak fixes, tap & bathroom fitting repairs",
    "imageUrl": "https://...",
    "isActive": true
  }
  ```

### 6.3 Update Service
* **Method**: `PUT`
* **Path**: `/admin/services/{id}`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`, `Content-Type: application/json`
* **Request Body**: Same fields as create.

### 6.4 Delete Service (Soft Delete)
* **Method**: `DELETE`
* **Path**: `/admin/services/{id}`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`

---

## 📦 7. Sub-Services Catalog Management

### 7.1 Get All Sub-Services
* **Method**: `GET`
* **Path**: `/admin/sub-services`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`
* **Response (200 OK)**:
  ```json
  [
    {
      "subServiceId": "d3a2b1c0-4e5f-6a7b-8c9d-0e1f2a3b4c5d",
      "service": {
        "serviceId": "be8bb640-cc11-4243-ab4a-5cbdf34a0937",
        "name": "AC & Appliance Repair"
      },
      "name": "AC Foam Jet Service",
      "pricingType": "FIXED",
      "basePrice": 599.00,
      "unitLabel": "per AC unit",
      "estimatedMins": 45,
      "imageUrl": "https://...",
      "isActive": true
    }
  ]
  ```

### 7.2 Create Sub-Service
* **Method**: `POST`
* **Path**: `/admin/sub-services`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`, `Content-Type: application/json`
* **Request Body**:
  ```json
  {
    "service": {
      "serviceId": "be8bb640-cc11-4243-ab4a-5cbdf34a0937"
    },
    "name": "Deep Clean Water Heater",
    "pricingType": "FIXED",
    "basePrice": 499.00,
    "unitLabel": "per appliance",
    "estimatedMins": 40,
    "imageUrl": "https://...",
    "isActive": true
  }
  ```
  *(Pricing types: `FIXED`, `HOURLY`, `VARIABLE`)*

### 7.3 Update Sub-Service
* **Method**: `PUT`
* **Path**: `/admin/sub-services/{id}`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`

### 7.4 Delete Sub-Service (Soft Delete)
* **Method**: `DELETE`
* **Path**: `/admin/sub-services/{id}`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`

---

## 🎨 8. Promotional Banners Management

### 8.1 Get All Banners
* **Method**: `GET`
* **Path**: `/admin/banners?activeOnly=false`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`

### 8.2 Get Banner By ID
* **Method**: `GET`
* **Path**: `/admin/banners/{id}`

### 8.3 Create Banner
* **Method**: `POST`
* **Path**: `/admin/banners`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`, `Content-Type: application/json`
* **Request Body**:
  ```json
  {
    "title": "Summer AC Fest — Flat ₹150 Off",
    "imageUrl": "https://...",
    "targetType": "SERVICE",
    "targetId": "be8bb640-cc11-4243-ab4a-5cbdf34a0937",
    "isActive": true
  }
  ```

### 8.4 Patch Banner
* **Method**: `PATCH`
* **Path**: `/admin/banners/{id}`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`, `Content-Type: application/json`
* **Request Body**:
  ```json
  {
    "isActive": false
  }
  ```

### 8.5 Delete Banner
* **Method**: `DELETE`
* **Path**: `/admin/banners/{id}?hard=false` *(Use `hard=true` for permanent deletion)*
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`

---

## 🎟️ 9. Coupons & Discounts Management

### 9.1 Get All Coupons
* **Method**: `GET`
* **Path**: `/admin/coupons?activeOnly=false`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`

### 9.2 Create Coupon
* **Method**: `POST`
* **Path**: `/admin/coupons`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`, `Content-Type: application/json`
* **Request Body**:
  ```json
  {
    "code": "FIRSTDOOR50",
    "discountType": "PERCENTAGE",
    "discountVal": 20.00,
    "minOrderValue": 399.00,
    "maxDiscountAmount": 150.00,
    "validFrom": "2026-10-01T00:00:00Z",
    "validUntil": "2026-12-31T23:59:59Z",
    "usageLimit": 1000,
    "isActive": true
  }
  ```
  *(Discount types: `PERCENTAGE`, `FLAT`)*

### 9.3 Patch Coupon
* **Method**: `PATCH`
* **Path**: `/admin/coupons/{id}`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`, `Content-Type: application/json`

### 9.4 Delete Coupon
* **Method**: `DELETE`
* **Path**: `/admin/coupons/{id}?hard=false`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`

---

## 🗺️ 10. Live Map & Operations Overview

### 10.1 Get Real-time Map Data
Returns all online workers and currently in-transit bookings with live coordinates for an operations control room map.
* **Method**: `GET`
* **Path**: `/admin/locations/live-map`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`
* **Response (200 OK)**:
  ```json
  {
    "totalOnlineWorkers": 42,
    "totalActiveBookings": 18,
    "activeWorkers": [
      {
        "workerId": "7b8c9d0e-1f2a-3b4c-5d6e-7f8a9b0c1d2e",
        "userId": "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
        "fullName": "Suresh Raina",
        "phoneNumber": "+919876543211",
        "isOnline": true,
        "isVerified": true,
        "currentLat": 17.4485,
        "currentLng": 78.3748,
        "lastUpdated": "2026-10-26T15:10:00Z"
      }
    ],
    "activeBookings": [
      {
        "bookingId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
        "userId": "2b3c4d5e-6f7a-8b9c-0d1e-2f3a4b5c6d7e",
        "customerName": "Pooja Hegde",
        "customerPhone": "+919876543212",
        "serviceName": "AC & Appliance Repair",
        "bookingStatus": "IN_PROGRESS",
        "userLat": 17.4435,
        "userLng": 78.3772,
        "assignedWorkerId": "7b8c9d0e-1f2a-3b4c-5d6e-7f8a9b0c1d2e",
        "assignedWorkerName": "Suresh Raina"
      }
    ]
  }
  ```

---

## 🔔 11. Push Notifications (Admin Broadcast & Schedule)

### 11.1 Send Instant Notification (Single User or Broadcast)
* **Method**: `POST`
* **Path**: `/admin/notifications/instant`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`, `Content-Type: application/json`
* **Request Body**:
  ```json
  {
    "recipientUserId": null, 
    "title": "🎉 Festival Offer Live Now!",
    "body": "Book any home cleaning service and get flat ₹200 off with code FEST200."
  }
  ```
  *(Note: Setting `recipientUserId: null` automatically broadcasts to the Firebase topic `all_users`)*

### 11.2 Schedule a Notification
* **Method**: `POST`
* **Path**: `/admin/notifications/schedule`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`, `Content-Type: application/json`
* **Request Body**:
  ```json
  {
    "recipientUserId": "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
    "title": "Your Service is Tomorrow",
    "message": "Friendly reminder: your AC service appointment is scheduled for tomorrow at 10:00 AM.",
    "type": "REMINDER",
    "scheduledAt": "2026-10-27T04:30:00Z"
  }
  ```

### 11.3 Get All Notifications Log
* **Method**: `GET`
* **Path**: `/admin/notifications`
* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`

---

## 📁 12. Supabase Storage Image Uploads

All file upload endpoints take `multipart/form-data` with parameter `file`.

| Endpoint | Purpose | Bucket Target |
|----------|---------|---------------|
| `POST /api/v1/upload/service-image` | Service Vertical Icon | `homeease-services` |
| `POST /api/v1/upload/sub-service-image` | Sub-Service Illustration | `homeease-sub-services` |
| `POST /api/v1/upload/banner-image` | Promotional Carousel Banner | `homeease-banners` |
| `POST /api/v1/upload/worker-profile/{workerId}` | Partner Avatar Photo | `homeease-worker-profile` |
| `POST /api/v1/upload/worker-kyc/pan/{workerId}` | Partner PAN Card Photo | `homeease-worker-kyc` |
| `POST /api/v1/upload/worker-kyc/aadhaar/{workerId}` | Partner Aadhaar Card Photo | `homeease-worker-kyc` |

* **Headers**: `Authorization: Bearer <ADMIN_TOKEN>`, `Content-Type: multipart/form-data`
* **Response (200 OK)**:
  ```json
  {
    "status": "SUCCESS",
    "url": "https://xyz.supabase.co/storage/v1/object/public/homeease-services/uuid.png",
    "bucket": "homeease-services"
  }
  ```

---

## 🌐 13. Microservices Reference (Optional Direct Control)

| Service | Port | Endpoint | Description |
|---------|------|----------|-------------|
| **Dispatch** | 8081 | `POST /api/v1/dispatch/trigger` | Manually trigger worker spatial search for a booking |
| **Dispatch** | 8081 | `POST /api/v1/dispatch/accept` | Accept a dispatch job |
| **Dispatch** | 8081 | `POST /api/v1/dispatch/reject` | Reject a dispatch job |
| **Tracking** | 8082 | `GET /api/v1/tracking/latest/{bookingId}` | Query current partner GPS location |
| **Tracking** | 8082 | `POST /api/v1/tracking/update-location` | Submit a tracking location update |
| **Notification** | 8083 | `POST /api/v1/notifications/push` | Send direct FCM notification payload |

---

## 📍 14. Service Area Management

These endpoints manage the state → city → pincode hierarchy used for serviceability.
Write requests must include the admin's user ID in the `X-User-Id` header; the backend
checks that this ID belongs to an admin. Pincode searches use zero-based pagination.

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/admin/service-areas` | Overview statistics and states |
| `GET` | `/admin/service-areas/states` | List states |
| `POST` | `/admin/service-areas/states` | Create a state |
| `PUT` | `/admin/service-areas/states/{id}?confirm=false` | Update a state |
| `DELETE` | `/admin/service-areas/states/{id}?cascade=false` | Delete a state (set cascade=true to delete children) |
| `GET` | `/admin/service-areas/cities?stateId={stateId}` | List cities, optionally filtered by state |
| `POST` | `/admin/service-areas/cities` | Create a city |
| `PUT` | `/admin/service-areas/cities/{id}?confirm=false` | Update a city |
| `DELETE` | `/admin/service-areas/cities/{id}?cascade=false` | Delete a city (set cascade=true to delete children) |
| `GET` | `/admin/service-areas/pincodes` | Search/filter pincodes |
| `POST` | `/admin/service-areas/pincodes` | Create a pincode |
| `POST` | `/admin/service-areas/pincodes/bulk` | Create multiple pincodes |
| `PUT` | `/admin/service-areas/pincodes/{id}?confirm=false` | Update a pincode |
| `DELETE` | `/admin/service-areas/pincodes/{id}` | Delete a pincode |

Send `X-User-Id: <admin-user-uuid>` on create/update calls. For update calls that
deactivate an area, retry with `confirm=true` if the server responds with a warning.

**Create state** (`POST /admin/service-areas/states`):
```json
{
  "name": "Telangana",
  "isActive": true
}
```

**Create city** (`POST /admin/service-areas/cities`):
```json
{
  "stateId": "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
  "name": "Hyderabad",
  "isActive": true
}
```

**Create pincode** (`POST /admin/service-areas/pincodes`):
```json
{
  "pincode": "500072",
  "cityId": "b1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
  "stateId": "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
  "areaName": "Miyapur",
  "isActive": true
}
```

Pincode search accepts optional `stateId`, `cityId`, `active`, and `q` parameters,
plus `page` (default `0`) and `size` (default `20`). The public customer check is
`GET /serviceability/check?pincode=500072`; it does not require the admin client.

---

## 🧾 15. Booking Inspection & Audit History

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/bookings/{id}` | Fetch booking details |
| `POST` | `/bookings/{id}/cancel?reason={reason}` | Cancel a booking |
| `PATCH` | `/bookings/{id}/status?status={status}` | Update booking status |
| `GET` | `/bookings/{id}/history?page=0&size=20` | Read paginated booking event history |

The cancellation, status update, and history calls require `X-User-Id`. Booking
history also accepts `X-User-Role: ADMIN`. The client helpers
`cancelBookingByAdmin`, `updateBookingStatusByAdmin`, and `getBookingHistory` are
available in `adminApi.js`.

---

## 🔄 16. App Version & Update Management

The public check is used by clients to decide whether to prompt for an update.
The admin routes read or update the version configuration for an app type
(`CUSTOMER`, `ADMIN`, or `PARTNER`).

### 16.1 Check for an App Update
* **Method**: `GET`
* **Path**: `/app/check-update?appType=CUSTOMER&currentVersion=1.0.0&platform=android`
* **Authentication**: Not required.

### 16.2 Get App Version Configurations
* **Method**: `GET`
* **Path**: `/admin/app-versions`

### 16.3 Update an App Version Configuration
* **Method**: `PUT`
* **Path**: `/admin/app-versions/{appType}`
* **Request Body**:
  ```json
  {
    "latestVersion": "1.2.0",
    "minimumVersion": "1.0.0",
    "forceUpdate": false,
    "releaseNotes": "Reliability improvements and bug fixes.",
    "storeUrl": "https://example.com/app"
  }
  ```

`latestVersion`, `minimumVersion`, `releaseNotes`, and `storeUrl` are retained from
the current configuration when omitted. These configurations are currently held in
memory and reset to defaults when the backend restarts. The app-version controller
does not enforce admin authentication; do not treat these routes as access-controlled
until authorization is implemented in the backend.
