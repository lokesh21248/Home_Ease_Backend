# HomeEase Worker Partner Mobile App — API & Frontend Integration Guide

**Base URL**: `http://3.107.161.126:8080/api/v1` *(or `http://localhost:8080/api/v1` for local development)*  
**Default Headers**:
* `Content-Type: application/json`
* `Authorization: Bearer <JWT_TOKEN>`
* `X-User-Id: <UUID>` *(Required on all authenticated worker endpoints)*

---

## ⚠️ Important Android Configuration (Cleartext HTTP)
Because the development backend server uses cleartext HTTP (`http://3.107.161.126:8080`), Android 9+ devices will block all network calls with `ERR_CLEARTEXT_NOT_PERMITTED` unless explicitly permitted.

* **In `app.json` (React Native / Expo)**:
  ```json
  {
    "expo": {
      "android": {
        "usesCleartextTraffic": true
      }
    }
  }
  ```
* **Or in `AndroidManifest.xml` (Bare React Native / Android Native)**:
  ```xml
  <application
      android:usesCleartextTraffic="true"
      ... >
  ```

---

## ⚠️ Database Identifier Contract (UUIDs vs Mock IDs)
The PostgreSQL backend strictly requires **36-character standard UUIDs** for all entity IDs.
* ❌ **Do not send**: `'wrk-101'`, `'sub-01'`, or integer IDs.
* ✅ **Do send**: Real backend UUIDs (e.g. `6f2e1a3b-4c5d-6e7f-6a9b-1c2d3e4f5a6b`).

---

## 🧭 Complete Worker Partner App Lifecycle

```
1. Mobile OTP Login / Register (Role: WORKER)
       │
       ▼
2. Register FCM Device Push Token (POST /user/fcm-token)
       │
       ▼
3. Upload Profile Photo & KYC Documents (PAN, Aadhaar)
       │
       ▼
4. Submit Bank & Skill Verification (POST /workers/register-kyc)
       │
       ▼
5. Fetch Partner Profile & Verification Status (GET /workers/profile)
       │
       ▼
6. Go Online / Offline Toggle (POST /workers/status)
       │
       ▼
7. Background GPS Location Stream (POST /workers/location every 15-30s)
       │
       ▼
8. Poll Dispatch Requests (GET /workers/bookings/requests) ──► Accept / Reject
       │
       ▼
9. Navigate to Customer Address ──► Ask for 4-Digit Security PIN (POST /verify-pin)
       │
       ▼
10. Optional: Upsell Extra Sub-Services (POST /add-sub-service)
       │
       ▼
11. Complete Job & Collect Payment (POST /complete)
       │
       ▼
12. View Partner Earnings & Commission Summary (GET /workers/earnings)
```

---

## 1. Authentication & Registration APIs

### 1.1 Send Mobile OTP
* **Method & Route**: `POST /auth/otp/send`
* **Headers**: `Content-Type: application/json`
* **Request Body**:
  | Field | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `phoneNumber` | `String` | **Yes** | Phone number in E.164 format (e.g. `"+919123456780"`). |
* **Sample Request**:
  ```json
  {
    "phoneNumber": "+919123456780"
  }
  ```
* **Response (`200 OK`)**:
  ```json
  {
    "message": "OTP sent successfully to +919123456780",
    "otpId": "otp_1728131234567",
    "status": "PENDING"
  }
  ```

---

### 1.2 Verify Mobile OTP
* **Method & Route**: `POST /auth/otp/verify`
* **Headers**: `Content-Type: application/json`
* **Request Body**:
  | Field | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `phoneNumber` | `String` | **Yes** | Worker's phone number. |
  | `otpCode` | `String` | **Yes** | 6-digit OTP code (e.g. `"123456"`). |
* **Sample Request**:
  ```json
  {
    "phoneNumber": "+919123456780",
    "otpCode": "123456"
  }
  ```
* **Response (`200 OK`)**:
  ```json
  {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "userId": "6f2e1a3b-4c5d-6e7f-6a9b-1c2d3e4f5a6b",
    "fullName": "Ramesh Kumar",
    "phoneNumber": "+919123456780",
    "email": "ramesh@partner.homeease.com",
    "role": "WORKER"
  }
  ```
  > 💡 **Frontend Note**: Store `token` and `userId` in local persistent storage (e.g. `AsyncStorage` or `SecureStore`). Attach `Authorization: Bearer <token>` and `X-User-Id: <userId>` to all future worker requests.

---

### 1.3 Register Worker Account (First-Time User)
* **Method & Route**: `POST /users/register` *(or `/user/register`)*
* **Headers**: `Content-Type: application/json`
* **Request Body**:
  | Field | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `fullName` | `String` | **Yes** | Worker partner's legal full name. |
  | `phoneNumber` | `String` | **Yes** | Registered phone number. |
  | `email` | `String` | No | Partner email address. |
  | `role` | `String` | **Yes** | Must be `"WORKER"`. |
* **Sample Request**:
  ```json
  {
    "fullName": "Ramesh Kumar",
    "phoneNumber": "+919123456780",
    "email": "ramesh@partner.homeease.com",
    "role": "WORKER"
  }
  ```

---

### 1.4 Register Push Notification Token (FCM)
Call this immediately upon successful login so the worker receives incoming dispatch notifications.
* **Method & Route**: `POST /user/fcm-token` *(or `/users/fcm-token`)*
* **Headers**: `Content-Type: application/json`, `X-User-Id: <UUID>`
* **Request Body**:
  ```json
  {
    "fcmToken": "fcm_token_device_abc123..."
  }
  ```
* **Response (`200 OK`)**:
  ```json
  {
    "message": "FCM token updated successfully."
  }
  ```

---

## 2. Worker Document Upload & KYC Registration

### 2.1 Upload Identity Documents & Avatar
Upload endpoints accept `multipart/form-data` with parameter name `file`.

| Document | Method & Endpoint | Target Storage Bucket |
| :--- | :--- | :--- |
| **Worker Avatar Photo** | `POST /upload/worker-profile/{workerId}` | `homeease-worker-profile` |
| **PAN Card Photo/PDF** | `POST /upload/worker-kyc/pan/{workerId}` | `homeease-worker-kyc` |
| **Aadhaar Card Photo/PDF** | `POST /upload/worker-kyc/aadhaar/{workerId}` | `homeease-worker-kyc` |

* **Headers**: `Content-Type: multipart/form-data`, `X-User-Id: <workerId>`
* **Sample Response (`200 OK`)**:
  ```json
  {
    "url": "https://gheiuyygjssqatziviix.supabase.co/storage/v1/object/public/homeease-worker-kyc/pan-docs/6f2e1a3b_pan.png",
    "bucket": "homeease-worker-kyc"
  }
  ```

---

### 2.2 Submit KYC Details & Skill Tags
* **Method & Route**: `POST /workers/register-kyc`
* **Headers**: `Content-Type: application/json`, `X-User-Id: <UUID>`
* **Request Body**:
  | Field | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `address` | `String` | **Yes** | Residential address of the worker. |
  | `panNumber` | `String` | **Yes** | 10-character PAN number (e.g. `"ABCDE1234F"`). |
  | `panDocUrl` | `String` | **Yes** | URL returned from PAN upload. |
  | `aadhaarDocUrl` | `String` | **Yes** | URL returned from Aadhaar upload. |
  | `bankAccountNo` | `String` | **Yes** | Bank account number for direct payouts. |
  | `bankIfsc` | `String` | **Yes** | Bank IFSC code (e.g. `"HDFC0001234"`). |
  | `subServiceIds` | `List<UUID>`| **Yes** | Array of sub-service UUIDs the worker is certified to perform. |
* **Sample Request**:
  ```json
  {
    "address": "Flat 204, Sai Residency, Hitec City, Hyderabad",
    "panNumber": "ABCDE1234F",
    "panDocUrl": "https://gheiuyygjssqatziviix.supabase.co/storage/v1/object/public/homeease-worker-kyc/pan-docs/6f2e1a3b_pan.png",
    "aadhaarDocUrl": "https://gheiuyygjssqatziviix.supabase.co/storage/v1/object/public/homeease-worker-kyc/aadhaar-docs/6f2e1a3b_aadhaar.png",
    "bankAccountNo": "50100234567891",
    "bankIfsc": "HDFC0001234",
    "subServiceIds": [
      "c34b4032-95ed-4ed5-b5e9-a7f5ab8cd4f7",
      "63fa806f-d1f3-4f92-aa7f-527c86c899ce"
    ]
  }
  ```
* **Response (`200 OK`)**:
  ```json
  {
    "workerId": "6f2e1a3b-4c5d-6e7f-6a9b-1c2d3e4f5a6b",
    "isVerified": true,
    "isOnline": false,
    "rating": 5.0,
    "jobsCompleted": 0,
    "user": {
      "id": "6f2e1a3b-4c5d-6e7f-6a9b-1c2d3e4f5a6b",
      "fullName": "Ramesh Kumar",
      "phoneNumber": "+919123456780",
      "email": "ramesh@partner.homeease.com",
      "role": "WORKER"
    }
  }
  ```

---

## 3. Worker Profile & Duty Status

### 3.1 Get Worker Profile
Load this on app launch to populate worker state, verification status, and ratings in your state store.
* **Method & Route**: `GET /workers/profile`
* **Headers**: `X-User-Id: <UUID>`
* **Response (`200 OK`)**:
  ```json
  {
    "workerId": "6f2e1a3b-4c5d-6e7f-6a9b-1c2d3e4f5a6b",
    "isVerified": true,
    "isOnline": true,
    "rating": 4.85,
    "jobsCompleted": 32,
    "user": {
      "id": "6f2e1a3b-4c5d-6e7f-6a9b-1c2d3e4f5a6b",
      "fullName": "Ramesh Kumar",
      "phoneNumber": "+919123456780",
      "email": "ramesh@partner.homeease.com",
      "role": "WORKER"
    }
  }
  ```

---

### 3.2 Toggle Duty (Go Online / Offline)
* **Method & Route**: `POST /workers/status`
* **Headers**: `Content-Type: application/json`, `X-User-Id: <UUID>`
* **Request Body**:
  | Field | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `isOnline` | `Boolean` | **Yes** | `true` to go online and receive jobs; `false` to go off-duty. |
* **Sample Request**:
  ```json
  {
    "isOnline": true
  }
  ```
* **Response (`200 OK`)**:
  ```json
  {
    "workerId": "6f2e1a3b-4c5d-6e7f-6a9b-1c2d3e4f5a6b",
    "isVerified": true,
    "isOnline": true,
    "rating": 4.85,
    "jobsCompleted": 32
  }
  ```

---

### 3.3 Live GPS Location Heartbeat Ping
> 💡 **Frontend Recommendation**: Send this request every 15–30 seconds while the worker is online. It updates Redis spatial indices so nearby customer dispatch algorithms can find this partner.

* **Method & Route**: `POST /workers/location`
* **Headers**: `Content-Type: application/json`, `X-User-Id: <UUID>`
* **Request Body**:
  | Field | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `lat` | `Double` | **Yes** | Worker's current latitude. |
  | `lng` | `Double` | **Yes** | Worker's current longitude. |
* **Sample Request**:
  ```json
  {
    "lat": 17.447812,
    "lng": 78.373954
  }
  ```
* **Response (`200 OK`)**:
  ```text
  "Worker live location updated successfully."
  ```

---

## 4. Job Dispatch, PIN Verification & Execution

### 4.1 Poll Incoming Dispatch Requests
Worker app polls this endpoint (every 3–5 seconds when online) or checks upon receiving a push notification to display incoming job alert popups.
* **Method & Route**: `GET /workers/bookings/requests` *(or `/workers/requests`)*
* **Headers**: `X-User-Id: <UUID>`
* **Response (`200 OK`)**:
  ```json
  [
    {
      "bookingId": "c4d32e1a-8f90-4a12-b34c-9f8e7d6c5b4a",
      "serviceName": "Deep House Cleaning",
      "subServices": ["2 BHK Deep Clean", "Balcony Power Wash"],
      "scheduledDate": "2026-10-06",
      "scheduledTimeSlot": "10:00 AM - 12:00 PM",
      "customerName": "Rahul Sharma",
      "customerPhone": "+919876543210",
      "customerAddress": "Flat 401, Rainbow Heights, Gachibowli, Hyderabad",
      "userLat": 17.448293,
      "userLng": 78.374182,
      "distanceKm": 1.4,
      "estimatedPayout": 780.00,
      "totalAmount": 918.20
    }
  ]
  ```

---

### 4.2 Accept Booking Request
When the worker taps "Accept Job" on the incoming request modal:
* **Method & Route**: `POST /workers/bookings/{bookingId}/accept`
* **Headers**: `X-User-Id: <UUID>`
* **Response (`200 OK`)**:
  ```json
  {
    "bookingId": "c4d32e1a-8f90-4a12-b34c-9f8e7d6c5b4a",
    "status": "ACCEPTED",
    "workerId": "6f2e1a3b-4c5d-6e7f-6a9b-1c2d3e4f5a6b",
    "workerName": "Ramesh Kumar",
    "workerPhone": "+919123456780",
    "userName": "Rahul Sharma",
    "userPhone": "+919876543210",
    "userLat": 17.448293,
    "userLng": 78.374182,
    "totalAmount": 918.20
  }
  ```

---

### 4.3 Reject Booking Request
If the worker taps "Reject" or if the countdown timer expires:
* **Method & Route**: `POST /workers/bookings/{bookingId}/reject`
* **Headers**: `X-User-Id: <UUID>`
* **Query Parameters**:
  | Param | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `targetSubServiceId` | `UUID` | No | Target sub-service UUID to ignore. |
* **Response (`200 OK`)**:
  ```text
  "Booking request rejected. Account temporarily blocked for 1 hour."
  ```

---

### 4.4 Verify Customer PIN & Start Job
When the worker arrives at the customer's location, the worker must ask the customer for their **4-digit security PIN** displayed on the customer's app screen.
* **Method & Route**: `POST /workers/bookings/{bookingId}/verify-pin`
* **Headers**: `Content-Type: application/json`, `X-User-Id: <UUID>`
* **Request Body**:
  | Field | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `pinCode` | `String` | **Yes** | 4-digit numeric string provided by the customer (e.g. `"4821"`). |
* **Sample Request**:
  ```json
  {
    "pinCode": "4821"
  }
  ```
* **Response (`200 OK`)**:
  ```json
  {
    "bookingId": "c4d32e1a-8f90-4a12-b34c-9f8e7d6c5b4a",
    "status": "IN_PROGRESS",
    "startedAt": "2026-10-06T10:15:30Z"
  }
  ```
* **Error Response (`400 Bad Request`)**:
  ```json
  {
    "error": "Invalid PIN code. Please confirm with the customer."
  }
  ```

---

### 4.5 Add Extra Sub-Service on Site (Upsell)
If the customer requests additional services or repairs while the worker is on-site:
* **Method & Route**: `POST /workers/bookings/{bookingId}/add-sub-service`
* **Headers**: `Content-Type: application/json`, `X-User-Id: <UUID>`
* **Request Body**:
  | Field | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `subServiceId` | `UUID` | **Yes** | Valid sub-service UUID from catalog. |
  | `quantity` | `Integer`| **Yes** | Quantity to add (minimum 1). |
* **Sample Request**:
  ```json
  {
    "subServiceId": "aa6fa960-d2fd-4cd6-8ed1-730d1e1a1cf0",
    "quantity": 1
  }
  ```
* **Response (`200 OK`)**:
  Returns updated `BookingResponse` with recalculated subtotal, taxes, and new total amount.

---

### 4.6 Complete Job
When all work is finished:
* **Method & Route**: `POST /workers/bookings/{bookingId}/complete`
* **Headers**: `X-User-Id: <UUID>`
* **Response (`200 OK`)**:
  ```json
  {
    "bookingId": "c4d32e1a-8f90-4a12-b34c-9f8e7d6c5b4a",
    "status": "COMPLETED",
    "completedAt": "2026-10-06T11:45:00Z",
    "paymentStatus": "COMPLETED",
    "totalAmount": 918.20
  }
  ```

---

## 5. Worker Earnings & Payout Summary

### 5.1 Get Earnings Overview
* **Method & Route**: `GET /workers/earnings`
* **Headers**: `X-User-Id: <UUID>`
* **Response (`200 OK`)**:
  ```json
  {
    "totalEarnings": 12500.00,
    "totalCommissionPaid": 1875.00,
    "netPayout": 10625.00,
    "completedBookingsCount": 14
  }
  ```

---

## 6. HTTP Status Code Conventions

| HTTP Code | Meaning | Handling on Frontend |
| :--- | :--- | :--- |
| `200 OK` | Request succeeded | Proceed with screen transition or state update. |
| `400 Bad Request` | Missing field / Invalid PIN / Malformed UUID | Display server error message (e.g. "Invalid PIN code"). |
| `401 Unauthorized` | Missing / Invalid Bearer token | Redirect to OTP login screen. |
| `403 Forbidden` | Worker not verified or role mismatch | Show verification banner / contact support modal. |
| `404 Not Found` | Booking or worker not found | Show "Job no longer available" and refresh list. |
| `500 Server Error` | Backend exception | Prompt user to retry in a moment. |
