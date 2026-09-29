# HomeEase Worker Partner App — API & Frontend Integration Guide

**Base URL**: `http://3.107.161.126:8080/api/v1` *(or `http://localhost:8080/api/v1` for local)*  
**Headers**:
- `Content-Type: application/json`
- `X-User-Id: <UUID>` *(Required for all worker endpoints after authentication)*

---

## 🧭 Complete Worker Partner App Lifecycle

```
1. Mobile OTP Login / Register (Role: WORKER)
       │
       ▼
2. Upload Profile & KYC Documents (PAN, Aadhaar)
       │
       ▼
3. Submit Bank & Skill Verification (/workers/register-kyc)
       │
       ▼
4. Go Online / Offline Toggle
       │
       ▼
5. Background GPS Location Stream (/workers/location)
       │
       ▼
6. Incoming Dispatch Alert ───► Accept or Reject
       │
       ▼
7. Arrive at Customer Door ───► Ask for 4-Digit Security PIN
       │
       ▼
8. Upsell Extra Sub-Services (Optional)
       │
       ▼
9. Complete Job & Collect Payment
       │
       ▼
10. Earnings & Daily Settlement Dashboard
```

---

## 1. Worker Auth & Registration

### 1.1 Send OTP
- **Method / Path**: `POST /auth/otp/send`
- **Request Body**:
```json
{
  "phoneNumber": "+919123456780"
}
```

---

### 1.2 Verify OTP
- **Method / Path**: `POST /auth/otp/verify`
- **Request Body**:
```json
{
  "phoneNumber": "+919123456780",
  "otpCode": "123456"
}
```
- **Response** (`200 OK`):
```json
{
  "token": "eyJhbGciOi...",
  "userId": "6f2e1a3b-4c5d-6e7f-6a9b-1c2d3e4f5a6b",
  "fullName": "Ramesh Kumar",
  "phoneNumber": "+919123456780",
  "email": "ramesh@partner.homeease.com",
  "role": "WORKER"
}
```

---

### 1.3 Register Worker Account (If First Time)
- **Method / Path**: `POST /users/register`
- **Request Body**:
```json
{
  "fullName": "Ramesh Kumar",
  "phoneNumber": "+919123456780",
  "email": "ramesh@partner.homeease.com",
  "role": "WORKER"
}
```

---

## 2. Worker Document Upload & KYC Registration

### 2.1 Upload Profile Photo & Identity Documents
Use `multipart/form-data` with parameter name `file`:

| Document | Method / Endpoint | Description |
| :--- | :--- | :--- |
| **Worker Avatar** | `POST /upload/worker-profile/{workerId}` | Stores in `homeease-worker-profile` |
| **PAN Document** | `POST /upload/worker-kyc/pan/{workerId}` | Stores in `homeease-worker-kyc` |
| **Aadhaar Document** | `POST /upload/worker-kyc/aadhaar/{workerId}` | Stores in `homeease-worker-kyc` |

- **Sample Upload Response** (`200 OK`):
```json
{
  "url": "https://gheiuyygjssqatziviix.supabase.co/storage/v1/object/public/homeease-worker-kyc/pan-docs/6f2e1a3b_pan.png",
  "bucket": "homeease-worker-kyc"
}
```

---

### 2.2 Submit Full KYC Details & Skill Tags
- **Method / Path**: `POST /workers/register-kyc`
- **Header**: `X-User-Id: <Worker-UUID>`
- **Request Body**:
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
- **Response** (`200 OK`):
```json
{
  "workerId": "6f2e1a3b-4c5d-6e7f-6a9b-1c2d3e4f5a6b",
  "isVerified": true,
  "isOnline": false,
  "rating": 5.0,
  "jobsCompleted": 0
}
```

---

## 3. Availability & Location Tracking

### 3.1 Toggle Duty (Go Online / Offline)
- **Method / Path**: `POST /workers/status`
- **Header**: `X-User-Id: <Worker-UUID>`
- **Request Body**:
```json
{
  "isOnline": true
}
```
- **Response** (`200 OK`):
```json
{
  "workerId": "6f2e1a3b-4c5d-6e7f-6a9b-1c2d3e4f5a6b",
  "isOnline": true
}
```

---

### 3.2 GPS Location Heartbeat Ping
> 💡 **Frontend Recommendation**: Send this request every 15–30 seconds while the worker is online and the app is in foreground.

- **Method / Path**: `POST /workers/location`
- **Header**: `X-User-Id: <Worker-UUID>`
- **Request Body**:
```json
{
  "lat": 17.447812,
  "lng": 78.373954
}
```
- **Response** (`200 OK`):
```text
"Worker live location updated successfully."
```

---

## 4. Job Dispatch, PIN Verification & Execution

### 4.1 Accept Assigned Booking
When an incoming booking modal pops up on the worker app:
- **Method / Path**: `POST /workers/bookings/{bookingId}/accept`
- **Header**: `X-User-Id: <Worker-UUID>`
- **Response** (`200 OK`):
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

### 4.2 Reject Assigned Booking
- **Method / Path**: `POST /workers/bookings/{bookingId}/reject`
- **Header**: `X-User-Id: <Worker-UUID>`
- **Query Params**: `targetSubServiceId` (optional UUID)
- **Response** (`200 OK`):
```text
"Booking request rejected. Account temporarily blocked for 1 hour."
```
> Note: Rejection causes a temporary dispatch penalty. The engine re-routes the customer's job to the next nearest partner.

---

### 4.3 Verify Customer PIN & Start Work (Unlocks the Job)
When the worker reaches the customer's house, the worker must ask the customer for their 4-digit PIN code to start the service:
- **Method / Path**: `POST /workers/bookings/{bookingId}/verify-pin`
- **Header**: `X-User-Id: <Worker-UUID>`
- **Request Body**:
```json
{
  "pinCode": "4821"
}
```
- **Response** (`200 OK`):
```json
{
  "bookingId": "c4d32e1a-8f90-4a12-b34c-9f8e7d6c5b4a",
  "status": "IN_PROGRESS",
  "startedAt": "2026-09-30T10:15:30Z"
}
```
*(If the wrong PIN is typed, it throws a `400 Bad Request: "Invalid PIN code"`)*.

---

### 4.4 Add Extra Sub-Service on Job Site (Optional Upsell)
If the customer asks for extra repairs or services while the worker is on-site:
- **Method / Path**: `POST /workers/bookings/{bookingId}/add-sub-service`
- **Header**: `X-User-Id: <Worker-UUID>`
- **Request Body**:
```json
{
  "subServiceId": "aa6fa960-d2fd-4cd6-8ed1-730d1e1a1cf0",
  "quantity": 1
}
```
- **Response** (`200 OK`):
The booking amount recalculates with the added item.

---

### 4.5 Complete Job
- **Method / Path**: `POST /workers/bookings/{bookingId}/complete`
- **Header**: `X-User-Id: <Worker-UUID>`
- **Response** (`200 OK`):
```json
{
  "bookingId": "c4d32e1a-8f90-4a12-b34c-9f8e7d6c5b4a",
  "status": "COMPLETED",
  "completedAt": "2026-09-30T11:15:00Z",
  "paymentStatus": "COMPLETED",
  "totalAmount": 918.20
}
```

---

## 5. Worker Earnings & Payout Summary

### 5.1 Get Earnings Overview
- **Method / Path**: `GET /workers/earnings`
- **Header**: `X-User-Id: <Worker-UUID>`
- **Response** (`200 OK`):
```json
{
  "totalEarnings": 12500.00,
  "totalCommissionPaid": 1875.00,
  "netPayout": 10625.00,
  "completedBookingsCount": 14
}
```
