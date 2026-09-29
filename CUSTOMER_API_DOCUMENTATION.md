# HomeEase Customer App — API & Frontend Integration Guide

**Base URL**: `http://3.107.161.126:8080/api/v1` *(or `http://localhost:8080/api/v1` for local)*  
**Headers**:
- `Content-Type: application/json`
- `X-User-Id: <UUID>` *(Required for all booking endpoints after authentication)*

---

## 🧭 Complete Customer App User Flow

```
1. Authentication (OTP / Firebase)
       │
       ▼
2. Home Feed (Banners + Categories)
       │
       ▼
3. Service Details (Sub-Services + Addons)
       │
       ▼
4. Cart & Coupon Verification
       │
       ▼
5. Create Booking (Set Address, Time & Payment Mode)
       │
       ▼
6. Live Tracking & Share 4-Digit Security PIN
       │
       ▼
7. Order History & Reviews
```

---

## 1. Authentication & Onboarding APIs

### 1.1 Send Mobile OTP
- **Method / Path**: `POST /auth/otp/send`
- **Request Body**:
```json
{
  "phoneNumber": "+919876543210"
}
```
- **Response** (`200 OK`):
```json
{
  "message": "OTP sent successfully",
  "otpId": "otp_882910",
  "status": "SENT"
}
```

---

### 1.2 Verify Mobile OTP
- **Method / Path**: `POST /auth/otp/verify`
- **Request Body**:
```json
{
  "phoneNumber": "+919876543210",
  "otpCode": "123456"
}
```
- **Response** (`200 OK`):
```json
{
  "token": "eyJhbGciOi...",
  "userId": "d3b07384-d113-4a1d-8d2a-c45f4486ecbc",
  "fullName": "Rahul Sharma",
  "phoneNumber": "+919876543210",
  "email": "rahul@gmail.com",
  "role": "CUSTOMER"
}
```
> **Frontend Note**: Store `userId` in `AsyncStorage` or `localStorage`. Pass `X-User-Id: <userId>` in all subsequent requests.

---

### 1.3 Register New Customer Profile (If First-Time User)
- **Method / Path**: `POST /users/register`
- **Request Body**:
```json
{
  "fullName": "Rahul Sharma",
  "phoneNumber": "+919876543210",
  "email": "rahul@gmail.com",
  "role": "CUSTOMER"
}
```
- **Response** (`200 OK`):
```json
{
  "token": "eyJhbGciOi...",
  "userId": "d3b07384-d113-4a1d-8d2a-c45f4486ecbc",
  "fullName": "Rahul Sharma",
  "phoneNumber": "+919876543210",
  "email": "rahul@gmail.com",
  "role": "CUSTOMER"
}
```

---

### 1.4 Get Current User Profile
- **Method / Path**: `GET /users/me`
- **Header**: `X-User-Id: <UUID>`
- **Response** (`200 OK`):
```json
{
  "userId": "d3b07384-d113-4a1d-8d2a-c45f4486ecbc",
  "fullName": "Rahul Sharma",
  "phoneNumber": "+919876543210",
  "email": "rahul@gmail.com",
  "role": "CUSTOMER"
}
```

---

## 2. Catalog & Home Screen APIs

### 2.1 Get Promotional Banners (Hero Carousel)
- **Method / Path**: `GET /banners`
- **Response** (`200 OK`):
```json
[
  {
    "bannerId": "cbe9a00e-bce0-45fe-a6e1-a4c79d5cdf36",
    "title": "Festival Home Cleaning Fest — Flat 25% Off",
    "imageUrl": "https://gheiuyygjssqatziviix.supabase.co/storage/v1/object/public/homeease-banners/banner-images/1af45e3e-7662-4f1d-9dd0-a8acf19c57e5.png",
    "targetType": "SERVICE",
    "targetId": "6beaaf16-cb8b-4107-9f8b-617b23c0bd21",
    "isActive": true
  }
]
```

---

### 2.2 Get All Active Services (Categories)
- **Method / Path**: `GET /services`
- **Response** (`200 OK`):
```json
[
  {
    "serviceId": "6beaaf16-cb8b-4107-9f8b-617b23c0bd21",
    "name": "Massage & Spa",
    "description": "Professional at-home massages and spa therapies.",
    "imageUrl": "https://gheiuyygjssqatziviix.supabase.co/storage/v1/object/public/homeease-services/service-images/327af16c-e323-4d77-9f89-48c144cbc4a1.png",
    "isActive": true
  },
  {
    "serviceId": "6e2e370b-7d5e-46d2-944b-afe7c15db6d5",
    "name": "Electrician Services",
    "description": "Fan repair, switches, wiring and home electrical diagnostics.",
    "imageUrl": "https://gheiuyygjssqatziviix.supabase.co/storage/v1/object/public/homeease-services/service-images/87d9ea84-283d-4890-99e6-ebf757a0a1a0.png",
    "isActive": true
  }
]
```

---

### 2.3 Get Sub-Services & Addons for Selected Service
- **Method / Path**: `GET /services/{serviceId}/sub-services`
- **Response** (`200 OK`):
```json
[
  {
    "subServiceId": "c34b4032-95ed-4ed5-b5e9-a7f5ab8cd4f7",
    "serviceId": "6beaaf16-cb8b-4107-9f8b-617b23c0bd21",
    "name": "Swedish Full Body Massage",
    "pricingType": "FIXED",
    "basePrice": 899.00,
    "unitLabel": "per session",
    "estimatedMins": 60,
    "imageUrl": "https://gheiuyygjssqatziviix.supabase.co/storage/v1/object/public/homeease-sub-services/sub-service-images/10db6ffc-db57-4912-a413-121ee48205c9.png",
    "isActive": true,
    "addons": [
      {
        "addonId": "87b1c02a-9f4a-4b12-9c3e-908123456789",
        "subServiceId": "c34b4032-95ed-4ed5-b5e9-a7f5ab8cd4f7",
        "name": "Aromatherapy Organic Oil Upgrade",
        "price": 199.00,
        "isActive": true
      }
    ]
  }
]
```

---

## 3. Cart & Coupon Verification

### 3.1 Validate Coupon Code
- **Method / Path**: `POST /coupons/validate`
- **Request Body**:
```json
{
  "code": "DIWALI50",
  "orderValue": 899.00
}
```
- **Response** (`200 OK`):
```json
{
  "isValid": true,
  "code": "DIWALI50",
  "discountType": "PERCENTAGE",
  "discountVal": 20.00,
  "calculatedDiscount": 179.80,
  "message": "Coupon applied successfully"
}
```
*(If invalid: `"isValid": false, "message": "Coupon code is invalid or expired"`)*

---

## 4. Booking Lifecycle APIs

### 4.1 Create Booking
- **Method / Path**: `POST /bookings`
- **Header**: `X-User-Id: <Customer-UUID>`
- **Request Body**:
```json
{
  "serviceId": "6beaaf16-cb8b-4107-9f8b-617b23c0bd21",
  "subServices": [
    {
      "subServiceId": "c34b4032-95ed-4ed5-b5e9-a7f5ab8cd4f7",
      "quantity": 1
    }
  ],
  "addons": [
    {
      "addonId": "87b1c02a-9f4a-4b12-9c3e-908123456789",
      "quantity": 1
    }
  ],
  "couponCode": "DIWALI50",
  "scheduledAt": "2026-09-30T10:00:00Z",
  "userLat": 17.448293,
  "userLng": 78.374182,
  "paymentMethod": "ONLINE"
}
```
*(Allowed `paymentMethod`: `CASH`, `ONLINE`, `WALLET`)*

- **Response** (`200 OK`):
```json
{
  "bookingId": "c4d32e1a-8f90-4a12-b34c-9f8e7d6c5b4a",
  "userId": "d3b07384-d113-4a1d-8d2a-c45f4486ecbc",
  "userName": "Rahul Sharma",
  "userPhone": "+919876543210",
  "workerId": null,
  "serviceId": "6beaaf16-cb8b-4107-9f8b-617b23c0bd21",
  "serviceName": "Massage & Spa",
  "status": "SEARCHING_WORKER",
  "pinCode": "4821",
  "scheduledAt": "2026-09-30T10:00:00Z",
  "startedAt": null,
  "completedAt": null,
  "userLat": 17.448293,
  "userLng": 78.374182,
  "baseAmount": 899.00,
  "extraAmount": 199.00,
  "discountAmount": 179.80,
  "totalAmount": 918.20,
  "paymentStatus": "COMPLETED",
  "paymentMethod": "ONLINE",
  "createdAt": "2026-09-29T11:45:00Z"
}
```
> ⚠️ **CRITICAL UI REQUIREMENT**: The customer app **must display the `pinCode`** (`4821`) on the live tracking screen. The customer must give this 4-digit PIN to the worker when the worker arrives at their door to unlock the job.

---

### 4.2 Track Single Booking (Polling / Details)
- **Method / Path**: `GET /bookings/{bookingId}`
- **Response** (`200 OK`):
```json
{
  "bookingId": "c4d32e1a-8f90-4a12-b34c-9f8e7d6c5b4a",
  "status": "IN_PROGRESS",
  "workerId": "6f2e1a3b-4c5d-6e7f-6a9b-1c2d3e4f5a6b",
  "workerName": "Ramesh Kumar",
  "workerPhone": "+919123456780",
  "pinCode": "4821",
  "totalAmount": 918.20,
  "paymentStatus": "COMPLETED"
}
```

#### Booking Stages for Customer UI:
- `SEARCHING_WORKER`: Animated radar pulse (Searching nearby partners).
- `ACCEPTED`: Worker found! Shows worker name, phone number, and arrival status.
- `IN_PROGRESS`: Job in execution (Worker verified the customer's PIN).
- `COMPLETED`: Service done! Open Rating & Review popup.
- `CANCELLED`: Booking cancelled.

---

### 4.3 Customer Booking History (My Bookings)
- **Method / Path**: `GET /bookings/my-bookings`
- **Header**: `X-User-Id: <Customer-UUID>`
- **Response** (`200 OK`):
Returns an array of all past and active bookings for the customer.
