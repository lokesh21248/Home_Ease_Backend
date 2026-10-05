# HomeEase Customer Mobile App — API & Frontend Integration Guide

**Base URL**: `http://3.107.161.126:8080/api/v1` *(or `http://localhost:8080/api/v1` for local development)*  
**Default Headers**:
* `Content-Type: application/json`
* `Authorization: Bearer <JWT_TOKEN>`
* `X-User-Id: <UUID>` *(Required on all authenticated customer endpoints)*

---

## ⚠️ Important Android Configuration (Cleartext HTTP)
Because the development server uses cleartext HTTP (`http://3.107.161.126:8080`), Android 9+ devices will block all requests with `ERR_CLEARTEXT_NOT_PERMITTED` unless explicitly allowed.

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
The PostgreSQL database strictly requires **36-character standard UUIDs** for all entity IDs.
* ❌ **Do not send**: `'cat-cleaning'`, `'srv-deep-clean'`, or `'job-001'`.
* ✅ **Do send**: Real backend UUIDs returned from `/categories` or `/services` (e.g. `be8bb640-cc11-4243-ab4a-5cbdf34a0937`).

---

## 🧭 Complete Customer Journey Flow

```
1. Authentication (OTP / Firebase)
       │
       ▼
2. Register FCM Device Push Token (POST /user/fcm-token)
       │
       ▼
3. Home Screen Feed (Promotional Banners + Service Categories)
       │
       ▼
4. Category Details ➔ Select Sub-Services & Addons
       │
       ▼
5. Select or Add Delivery Address (GET/POST /user/addresses)
       │
       ▼
6. Select Appointment Date & Time Slot (GET /bookings/time-slots)
       │
       ▼
7. Validate Promo Coupon & Calculate Bill (POST /coupons/validate)
       │
       ▼
8. Create Booking (POST /bookings) ➔ Customer receives 4-Digit Security PIN
       │
       ▼
9. Track Worker Arrival ➔ Share PIN with Partner to Start Job
       │
       ▼
10. Job Completed ➔ Payment Settlement (COD/Online) & History
```

---

## 1. Authentication & Profile APIs

### 1.1 Send Mobile OTP
* **Method & Route**: `POST /auth/otp/send`
* **Headers**: `Content-Type: application/json`
* **Request Body**:
  | Field | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `phoneNumber` | `String` | **Yes** | Phone number in E.164 format (e.g. `"+919876543210"`). |
* **Sample Request**:
  ```json
  {
    "phoneNumber": "+919876543210"
  }
  ```
* **Response (`200 OK`)**:
  ```json
  {
    "message": "OTP sent successfully to +919876543210",
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
  | `phoneNumber` | `String` | **Yes** | Phone number (e.g. `"+919876543210"`). |
  | `otpCode` | `String` | **Yes** | 6-digit OTP code (e.g. `"123456"`). |
* **Sample Request**:
  ```json
  {
    "phoneNumber": "+919876543210",
    "otpCode": "123456"
  }
  ```
* **Response (`200 OK`)**:
  ```json
  {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "userId": "d3b07384-d113-4a1d-8d2a-c45f4486ecbc",
    "fullName": "HomeEase User",
    "phoneNumber": "+919876543210",
    "email": null,
    "role": "CUSTOMER"
  }
  ```
  > 💡 **Frontend Note**: Store `token` and `userId` in secure storage. Attach `Authorization: Bearer <token>` and `X-User-Id: <userId>` to all future requests.

---

### 1.3 Register Profile (First-Time User)
* **Method & Route**: `POST /users/register` *(or `/user/register`)*
* **Headers**: `Content-Type: application/json`
* **Request Body**:
  | Field | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `fullName` | `String` | **Yes** | Full customer name (e.g. `"Rahul Sharma"`). |
  | `phoneNumber` | `String` | **Yes** | Customer phone number. |
  | `email` | `String` | No | Customer email address. |
  | `role` | `String` | No | Always `"CUSTOMER"`. |
* **Sample Request**:
  ```json
  {
    "fullName": "Rahul Sharma",
    "phoneNumber": "+919876543210",
    "email": "rahul@gmail.com",
    "role": "CUSTOMER"
  }
  ```

---

### 1.4 Get & Update User Profile
* **Get Current Profile**: `GET /user/profile` *(or `GET /users/me`)*
  * **Headers**: `X-User-Id: <userId>`
  * **Response (`200 OK`)**:
    ```json
    {
      "userId": "d3b07384-d113-4a1d-8d2a-c45f4486ecbc",
      "fullName": "Rahul Sharma",
      "phoneNumber": "+919876543210",
      "email": "rahul@gmail.com",
      "role": "CUSTOMER",
      "createdAt": "2026-10-04T12:00:00Z"
    }
    ```
* **Update Profile**: `PUT /user/profile` *(or `PUT /users/profile`)*
  * **Headers**: `X-User-Id: <userId>`
  * **Request Body**:
    ```json
    {
      "fullName": "Rahul Sharma",
      "email": "rahul.sharma@example.com"
    }
    ```

---

### 1.5 Register Device Push Token (FCM)
* **Method & Route**: `POST /user/fcm-token` *(or `/users/fcm-token`)*
* **Headers**: `X-User-Id: <userId>`
* **Request Body**:
  | Field | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `fcmToken` | `String` | **Yes** | Firebase device token. |
* **Response (`200 OK`)**:
  ```json
  {
    "message": "FCM token registered successfully."
  }
  ```

---

## 2. Catalog & Home Feed APIs

### 2.1 Get Promotional Banners (Hero Carousel)
* **Method & Route**: `GET /banners`
* **Response (`200 OK`)**:
  ```json
  [
    {
      "bannerId": "cbe9a00e-bce0-45fe-a6e1-a4c79d5cdf36",
      "title": "Festival Home Deep Cleaning — Flat 25% Off",
      "imageUrl": "https://gheiuyygjssqatziviix.supabase.co/storage/v1/object/public/homeease-banners/banner-images/festive-cleaning.png",
      "targetType": "SERVICE",
      "targetId": "6beaaf16-cb8b-4107-9f8b-617b23c0bd21",
      "isActive": true
    }
  ]
  ```

---

### 2.2 Get Master Service Categories
* **Method & Route**: `GET /categories` *(or `GET /services`)*
* **Response (`200 OK`)**:
  ```json
  [
    {
      "serviceId": "6beaaf16-cb8b-4107-9f8b-617b23c0bd21",
      "name": "Cleaning & Pest Control",
      "description": "Deep home cleaning, kitchen sanitization, bathroom scrubbing",
      "imageUrl": "https://gheiuyygjssqatziviix.supabase.co/storage/v1/object/public/homeease-services/service-images/cleaning.png",
      "isActive": true
    },
    {
      "serviceId": "6e2e370b-7d5e-46d2-944b-afe7c15db6d5",
      "name": "AC & Appliance Repair",
      "description": "AC servicing, washing machine, refrigerator repairs",
      "imageUrl": "https://gheiuyygjssqatziviix.supabase.co/storage/v1/object/public/homeease-services/service-images/appliances.png",
      "isActive": true
    }
  ]
  ```

---

### 2.3 Get Sub-Services & Addons for Selected Category
* **Method & Route**: `GET /services/{serviceId}/sub-services` *(or `/categories/{serviceId}/sub-services`)*
* **Response (`200 OK`)**:
  ```json
  [
    {
      "subServiceId": "c34b4032-95ed-4ed5-b5e9-a7f5ab8cd4f7",
      "serviceId": "6beaaf16-cb8b-4107-9f8b-617b23c0bd21",
      "name": "Full Home Deep Cleaning (2 BHK)",
      "pricingType": "FIXED",
      "basePrice": 2499.00,
      "unitLabel": "per apartment",
      "estimatedMins": 180,
      "imageUrl": "https://.../deep-cleaning.png",
      "isActive": true,
      "addons": [
        {
          "addonId": "87b1c02a-9f4a-4b12-9c3e-908123456789",
          "subServiceId": "c34b4032-95ed-4ed5-b5e9-a7f5ab8cd4f7",
          "name": "Balcony Pressure Wash Upgrade",
          "price": 299.00,
          "isActive": true
        }
      ]
    }
  ]
  ```

---

## 3. Customer Saved Addresses & Time Slots

### 3.1 Fetch Saved Customer Addresses
* **Method & Route**: `GET /user/addresses` *(or `GET /users/addresses`)*
* **Headers**: `X-User-Id: <userId>`
* **Response (`200 OK`)**:
  ```json
  [
    {
      "id": "7a8b9c0d-1e2f-3a4b-5c6d-7e8f9a0b1c2d",
      "userId": "d3b07384-d113-4a1d-8d2a-c45f4486ecbc",
      "title": "Home",
      "addressLine": "Flat 402, Green Valley Apartments, Hitech City",
      "city": "Hyderabad",
      "lat": 17.448293,
      "lng": 78.374182,
      "isDefault": true
    }
  ]
  ```

---

### 3.2 Save a New Customer Address
* **Method & Route**: `POST /user/addresses` *(or `POST /users/addresses`)*
* **Headers**: `X-User-Id: <userId>`
* **Request Body**:
  | Field | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `title` | `String` | **Yes** | Address label (e.g. `"Home"`, `"Work"`, `"Parents"`). |
  | `addressLine` | `String` | **Yes** | Street/building/flat address. |
  | `city` | `String` | **Yes** | City name (e.g. `"Hyderabad"`). |
  | `lat` | `Double` | **Yes** | Latitude coordinate. |
  | `lng` | `Double` | **Yes** | Longitude coordinate. |
  | `isDefault` | `Boolean` | No | Mark as default address. |
* **Sample Request**:
  ```json
  {
    "title": "Home",
    "addressLine": "Flat 402, Green Valley Apartments, Hitech City",
    "city": "Hyderabad",
    "lat": 17.448293,
    "lng": 78.374182,
    "isDefault": true
  }
  ```

---

### 3.3 Delete Saved Address
* **Method & Route**: `DELETE /user/addresses/{addressId}`
* **Headers**: `X-User-Id: <userId>`
* **Response (`200 OK`)**: `{"message": "Address deleted successfully."}`

---

### 3.4 Available Booking Time Slots
* **Method & Route**: `GET /bookings/time-slots?date=YYYY-MM-DD` *(or `GET /slots`)*
* **Query Parameters**:
  | Parameter | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `date` | `String` | No | Target date in `YYYY-MM-DD` format (defaults to today). |
* **Response (`200 OK`)**:
  ```json
  [
    { "time": "09:00 AM - 10:00 AM", "available": true, "date": "2026-10-06" },
    { "time": "10:00 AM - 11:00 AM", "available": true, "date": "2026-10-06" },
    { "time": "11:00 AM - 12:00 PM", "available": true, "date": "2026-10-06" },
    { "time": "02:00 PM - 03:00 PM", "available": true, "date": "2026-10-06" },
    { "time": "04:00 PM - 05:00 PM", "available": true, "date": "2026-10-06" },
    { "time": "06:00 PM - 07:00 PM", "available": true, "date": "2026-10-06" }
  ]
  ```

---

## 4. Coupons & Billing Calculation

### 4.1 Validate Promo Coupon Code
* **Method & Route**: `POST /coupons/validate`
* **Request Body**:
  | Field | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `code` | `String` | **Yes** | Coupon code (e.g. `"WELCOME50"`). |
  | `cartTotal` | `BigDecimal` | **Yes** | Total before discount. |
* **Response (`200 OK`)**:
  ```json
  {
    "couponId": "dddddddd-dddd-dddd-dddd-dddddddddddd",
    "code": "WELCOME50",
    "discountAmount": 50.00,
    "finalAmount": 2449.00,
    "valid": true,
    "message": "Coupon applied successfully"
  }
  ```

---

## 5. Booking Creation & Real-Time Tracking

### 5.1 Create Booking (Place Order)
* **Method & Route**: `POST /bookings`
* **Headers**: `X-User-Id: <userId>`
* **Request Body**:
  | Field | Type | Required | Description |
  | :--- | :--- | :--- | :--- |
  | `serviceId` | `UUID` | **Yes** | Category UUID. |
  | `subServices` | `Array` | **Yes** | List of `{"subServiceId": "<UUID>", "quantity": 1}`. |
  | `addons` | `Array` | No | List of `{"addonId": "<UUID>", "quantity": 1}`. |
  | `couponCode` | `String` | No | Promo code (e.g. `"WELCOME50"`). |
  | `scheduledAt` | `ISO-8601 String` | **Yes** | Appointment timestamp (e.g. `"2026-10-06T09:30:00Z"`). |
  | `userLat` | `Double` | **Yes** | Customer latitude. |
  | `userLng` | `Double` | **Yes** | Customer longitude. |
  | `paymentMethod` | `Enum` | **Yes** | `"COD"`, `"UPI"`, `"CARD"`, or `"NET_BANKING"`. |

* **Sample Request Body**:
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
    "couponCode": "WELCOME50",
    "scheduledAt": "2026-10-06T09:30:00Z",
    "userLat": 17.448293,
    "userLng": 78.374182,
    "paymentMethod": "COD"
  }
  ```

* **Response (`200 OK`)**:
  ```json
  {
    "bookingId": "c4d32e1a-8f90-4a12-b34c-9f8e7d6c5b4a",
    "userId": "d3b07384-d113-4a1d-8d2a-c45f4486ecbc",
    "userName": "Rahul Sharma",
    "userPhone": "+919876543210",
    "workerId": null,
    "workerName": null,
    "workerPhone": null,
    "serviceId": "6beaaf16-cb8b-4107-9f8b-617b23c0bd21",
    "serviceName": "Cleaning & Pest Control",
    "status": "SEARCHING",
    "pinCode": "4821",
    "scheduledAt": "2026-10-06T09:30:00Z",
    "startedAt": null,
    "completedAt": null,
    "userLat": 17.448293,
    "userLng": 78.374182,
    "baseAmount": 2499.00,
    "extraAmount": 299.00,
    "discountAmount": 50.00,
    "totalAmount": 2748.00,
    "paymentStatus": "PENDING",
    "paymentMethod": "COD",
    "createdAt": "2026-10-05T12:00:00Z"
  }
  ```
  > 🔑 **Important**: Display `pinCode` (e.g. `"4821"`) prominently on the customer's active booking card. The customer will share this PIN with the worker upon arrival.

---

### 5.2 My Bookings History
* **Method & Route**: `GET /bookings/my-bookings`
* **Headers**: `X-User-Id: <userId>`
* **Response (`200 OK`)**: Returns an array of `BookingResponse` objects.

---

### 5.3 Live Booking Tracking & Details
* **Method & Route**: `GET /bookings/{bookingId}`
* **Headers**: `X-User-Id: <userId>`
* **Status Lifecycle**:
  * `SEARCHING`: Backend is matching nearby partners.
  * `ACCEPTED`: Worker accepted and is en route.
  * `IN_PROGRESS`: Worker verified PIN and service is active.
  * `COMPLETED`: Work finished; payment settled.
  * `CANCELLED`: Booking was cancelled.
