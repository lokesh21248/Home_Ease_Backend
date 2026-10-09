// Comprehensive API Test Suite for HomeEase Platform
// Tests Core Monolith (:8080) and Microservices (:8081, :8082, :8083)

const MONOLITH = 'http://3.107.161.126:8080/api/v1';
const DISPATCH = 'http://3.107.161.126:8081/api/v1';
const TRACKING = 'http://3.107.161.126:8082/api/v1';
const NOTIFY   = 'http://3.107.161.126:8083/api/v1';

const results = [];

async function request(name, url, options = {}) {
  const start = Date.now();
  const resObj = { name, url, method: options.method || 'GET', status: 0, durationMs: 0, ok: false, details: '' };
  try {
    const isFormData = options.body instanceof FormData;
    const defaultHeaders = isFormData ? {} : { 'Content-Type': 'application/json' };
    const res = await fetch(url, {
      ...options,
      headers: {
        ...defaultHeaders,
        ...(options.headers || {})
      }
    });
    resObj.status = res.status;
    resObj.durationMs = Date.now() - start;
    const text = await res.text();
    let body;
    try { body = JSON.parse(text); } catch { body = text; }
    resObj.body = body;
    if (res.status >= 200 && res.status < 300) {
      resObj.ok = true;
      resObj.details = 'SUCCESS';
    } else {
      resObj.ok = false;
      resObj.details = typeof body === 'object' ? JSON.stringify(body).slice(0, 160) : String(body).slice(0, 160);
    }
  } catch (err) {
    resObj.durationMs = Date.now() - start;
    resObj.ok = false;
    resObj.details = 'NETWORK_ERROR: ' + err.message;
  }
  results.push(resObj);
  const mark = resObj.ok ? '✓ PASS' : '✗ FAIL';
  console.log(`[${mark}] [${resObj.status}] ${resObj.method} ${resObj.url} - ${name} (${resObj.durationMs}ms)`);
  if (!resObj.ok) {
    console.log(`   ↳ Details: ${resObj.details}`);
  }
  return resObj;
}

async function runAllTests() {
  console.log('================================================================');
  console.log('🚀 Starting Comprehensive HomeEase API Test Suite');
  console.log(`Target: AWS Sydney Monolith + Microservices (3.107.161.126)`);
  console.log('================================================================\n');

  // ==========================================
  // Section 0: Health Checks
  // ==========================================
  console.log('\n--- 0. Microservices Health Checks ---');
  await request('Core Monolith Health (:8080)', `${MONOLITH}/health`);
  await request('Dispatch Service Health (:8081)', `${DISPATCH}/health`);
  await request('Tracking Service Health (:8082)', `${TRACKING}/health`);
  await request('Notification Service Health (:8083)', `${NOTIFY}/health`);

  // ==========================================
  // Section 1: Authentication & User Profile
  // ==========================================
  console.log('\n--- 1. Authentication & Users ---');
  const adminLoginRes = await request('Admin Login', `${MONOLITH}/admin/auth/login`, {
    method: 'POST',
    body: JSON.stringify({ email: 'admin@homeease.com', password: 'admin' })
  });
  const adminToken = adminLoginRes.body?.token;
  const adminUserId = adminLoginRes.body?.userId || '00000000-0000-0000-0000-000000000001';

  await request('Send Phone OTP', `${MONOLITH}/auth/otp/send`, {
    method: 'POST',
    body: JSON.stringify({ phoneNumber: '+919999900001' })
  });

  const testCustomerPhone = '+919999900001';
  const otpVerifyRes = await request('Verify Phone OTP (Customer)', `${MONOLITH}/auth/otp/verify`, {
    method: 'POST',
    body: JSON.stringify({ phoneNumber: testCustomerPhone, otpCode: '123456' })
  });
  const customerToken = otpVerifyRes.body?.token;
  const customerUserId = otpVerifyRes.body?.userId;

  await request('Firebase Token Login', `${MONOLITH}/auth/firebase-login`, {
    method: 'POST',
    body: JSON.stringify({ firebaseToken: 'mock_firebase_id_token' })
  });

  const testUniquePhone = `+9199${Math.floor(10000000 + Math.random() * 90000000)}`;
  await request('Register New User', `${MONOLITH}/users/register`, {
    method: 'POST',
    body: JSON.stringify({
      fullName: 'API Test Runner User',
      phoneNumber: testUniquePhone,
      email: `test_${Date.now()}@example.com`,
      role: 'CUSTOMER'
    })
  });

  if (customerUserId) {
    await request('Get Current User (Me)', `${MONOLITH}/users/me`, {
      headers: {
        'Authorization': `Bearer ${customerToken}`,
        'X-User-Id': customerUserId
      }
    });

    await request('Update User Profile', `${MONOLITH}/user/profile`, {
      method: 'PUT',
      headers: {
        'Authorization': `Bearer ${customerToken}`,
        'X-User-Id': customerUserId
      },
      body: JSON.stringify({
        fullName: 'Updated Test User',
        email: `updated_${Date.now()}@test.com`
      })
    });

    await request('Register FCM Token', `${MONOLITH}/user/fcm-token`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${customerToken}`,
        'X-User-Id': customerUserId
      },
      body: JSON.stringify({ fcmToken: 'test_fcm_token_device_abc123' })
    });
  }

  // ==========================================
  // Section 2: Public / Customer Catalog
  // ==========================================
  console.log('\n--- 2. Public & Customer Catalog ---');
  const servicesRes = await request('Get All Active Services', `${MONOLITH}/services`);
  let sampleServiceId = null;
  let sampleSubServiceId = null;

  if (Array.isArray(servicesRes.body)) {
    for (const s of servicesRes.body) {
      if (s.subServices && s.subServices.length > 0) {
        sampleServiceId = s.serviceId;
        sampleSubServiceId = s.subServices[0].subServiceId;
        break;
      }
    }
    if (!sampleServiceId && servicesRes.body.length > 0) {
      sampleServiceId = servicesRes.body[0].serviceId;
    }
  }

  if (sampleServiceId) {
    await request('Get Service Details by ID', `${MONOLITH}/services/${sampleServiceId}`);
    const subRes = await request('Get Sub-Services by Service ID', `${MONOLITH}/services/${sampleServiceId}/sub-services`);
    if (!sampleSubServiceId && Array.isArray(subRes.body) && subRes.body.length > 0) {
      sampleSubServiceId = subRes.body[0].subServiceId;
    }
  }

  await request('Get Active Customer Banners', `${MONOLITH}/banners`);
  await request('Get Active Customer Coupons', `${MONOLITH}/coupons`);
  await request('Validate Coupon Code', `${MONOLITH}/coupons/validate`, {
    method: 'POST',
    body: JSON.stringify({ couponCode: 'WELCOME50', orderAmount: 500 })
  });
  await request('Get Available Time Slots', `${MONOLITH}/bookings/time-slots?date=2026-10-10`);

  // ==========================================
  // Section 3: Admin - Dashboard, Payments & Live Map
  // ==========================================
  console.log('\n--- 3. Admin - Dashboard & Analytics ---');
  await request('Admin Dashboard Overview Stats', `${MONOLITH}/admin/dashboard/stats`, {
    headers: { 'Authorization': `Bearer ${adminToken}` }
  });

  await request('Admin Live Map Fleet Overview', `${MONOLITH}/admin/locations/live-map`, {
    headers: { 'Authorization': `Bearer ${adminToken}` }
  });

  const paymentsRes = await request('Admin List All Payments', `${MONOLITH}/admin/payments`, {
    headers: { 'Authorization': `Bearer ${adminToken}` }
  });

  let samplePaymentId = null;
  if (Array.isArray(paymentsRes.body) && paymentsRes.body.length > 0) {
    samplePaymentId = paymentsRes.body[0].paymentId;
    await request('Admin Get Payment by ID', `${MONOLITH}/admin/payments/${samplePaymentId}`, {
      headers: { 'Authorization': `Bearer ${adminToken}` }
    });
    await request('Admin Update Payment Status', `${MONOLITH}/admin/payments/${samplePaymentId}/status`, {
      method: 'PATCH',
      headers: { 'Authorization': `Bearer ${adminToken}` },
      body: JSON.stringify({ status: 'SUCCESS' })
    });
  }

  // ==========================================
  // Section 4: Admin - Banners Management
  // ==========================================
  console.log('\n--- 4. Admin - Banners Management ---');
  await request('Admin List All Banners', `${MONOLITH}/admin/banners?activeOnly=false`, {
    headers: { 'Authorization': `Bearer ${adminToken}` }
  });

  const createBannerRes = await request('Admin Create Banner', `${MONOLITH}/admin/banners`, {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${adminToken}` },
    body: JSON.stringify({
      title: 'Automated Test Banner',
      imageUrl: 'https://images.unsplash.com/photo-1581578731548-c64695cc6952?w=800',
      targetType: 'SERVICE',
      targetId: sampleServiceId || '00000000-0000-0000-0000-000000000000',
      isActive: true
    })
  });
  const createdBannerId = createBannerRes.body?.bannerId;

  if (createdBannerId) {
    await request('Admin Get Banner by ID', `${MONOLITH}/admin/banners/${createdBannerId}`, {
      headers: { 'Authorization': `Bearer ${adminToken}` }
    });

    await request('Admin Patch Banner', `${MONOLITH}/admin/banners/${createdBannerId}`, {
      method: 'PATCH',
      headers: { 'Authorization': `Bearer ${adminToken}` },
      body: JSON.stringify({ title: 'Updated Test Banner Title' })
    });

    await request('Admin Delete Banner (Soft)', `${MONOLITH}/admin/banners/${createdBannerId}?hard=false`, {
      method: 'DELETE',
      headers: { 'Authorization': `Bearer ${adminToken}` }
    });

    await request('Admin Delete Banner (Hard)', `${MONOLITH}/admin/banners/${createdBannerId}?hard=true`, {
      method: 'DELETE',
      headers: { 'Authorization': `Bearer ${adminToken}` }
    });
  }

  // ==========================================
  // Section 5: Admin - Coupons Management
  // ==========================================
  console.log('\n--- 5. Admin - Coupons Management ---');
  await request('Admin List All Coupons', `${MONOLITH}/admin/coupons?activeOnly=false`, {
    headers: { 'Authorization': `Bearer ${adminToken}` }
  });

  const uniqueCouponCode = 'TEST' + Math.floor(1000 + Math.random() * 9000);
  const createCouponRes = await request('Admin Create Coupon', `${MONOLITH}/admin/coupons`, {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${adminToken}` },
    body: JSON.stringify({
      code: uniqueCouponCode,
      discountType: 'PERCENTAGE',
      discountVal: 15,
      minOrderValue: 200,
      maxDiscountAmount: 100,
      validFrom: '2026-01-01T00:00:00Z',
      validUntil: '2026-12-31T23:59:59Z',
      usageLimit: 100,
      isActive: true
    })
  });
  const createdCouponId = createCouponRes.body?.couponId;

  if (createdCouponId) {
    await request('Admin Patch Coupon', `${MONOLITH}/admin/coupons/${createdCouponId}`, {
      method: 'PATCH',
      headers: { 'Authorization': `Bearer ${adminToken}` },
      body: JSON.stringify({ discountVal: 20 })
    });

    await request('Admin Delete Coupon (Hard)', `${MONOLITH}/admin/coupons/${createdCouponId}?hard=true`, {
      method: 'DELETE',
      headers: { 'Authorization': `Bearer ${adminToken}` }
    });
  }

  // ==========================================
  // Section 6: Admin - Services & Sub-Services CRUD
  // ==========================================
  console.log('\n--- 6. Admin - Services & Sub-Services Catalog ---');
  await request('Admin List All Services', `${MONOLITH}/admin/services`, {
    headers: { 'Authorization': `Bearer ${adminToken}` }
  });

  const createServiceRes = await request('Admin Create Service Vertical', `${MONOLITH}/admin/services`, {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${adminToken}` },
    body: JSON.stringify({
      name: 'Automated Test Service ' + Date.now(),
      description: 'Temporary service created by automated test suite',
      imageUrl: 'https://images.unsplash.com/photo-1581578731548-c64695cc6952?w=800',
      isActive: true
    })
  });
  const createdServiceId = createServiceRes.body?.serviceId;

  let createdSubServiceId = null;
  if (createdServiceId) {
    await request('Admin Update Service Vertical', `${MONOLITH}/admin/services/${createdServiceId}`, {
      method: 'PUT',
      headers: { 'Authorization': `Bearer ${adminToken}` },
      body: JSON.stringify({
        name: 'Automated Test Service Updated',
        description: 'Updated description',
        imageUrl: 'https://images.unsplash.com/photo-1581578731548-c64695cc6952?w=800',
        isActive: true
      })
    });

    const createSubRes = await request('Admin Create Sub-Service', `${MONOLITH}/admin/sub-services`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${adminToken}` },
      body: JSON.stringify({
        service: { serviceId: createdServiceId },
        name: 'Automated Sub Service Test',
        pricingType: 'FIXED',
        basePrice: 299,
        unitLabel: 'per job',
        estimatedMins: 45,
        imageUrl: 'https://images.unsplash.com/photo-1581578731548-c64695cc6952?w=800',
        isActive: true
      })
    });
    createdSubServiceId = createSubRes.body?.subServiceId;

    if (createdSubServiceId) {
      await request('Admin Update Sub-Service', `${MONOLITH}/admin/sub-services/${createdSubServiceId}`, {
        method: 'PUT',
        headers: { 'Authorization': `Bearer ${adminToken}` },
        body: JSON.stringify({
          service: { serviceId: createdServiceId },
          name: 'Automated Sub Service Updated',
          pricingType: 'FIXED',
          basePrice: 349,
          unitLabel: 'per job',
          estimatedMins: 50,
          imageUrl: 'https://images.unsplash.com/photo-1581578731548-c64695cc6952?w=800',
          isActive: true
        })
      });

      await request('Admin Delete Sub-Service', `${MONOLITH}/admin/sub-services/${createdSubServiceId}`, {
        method: 'DELETE',
        headers: { 'Authorization': `Bearer ${adminToken}` }
      });
    }

    await request('Admin Delete Service Vertical', `${MONOLITH}/admin/services/${createdServiceId}`, {
      method: 'DELETE',
      headers: { 'Authorization': `Bearer ${adminToken}` }
    });
  }

  await request('Admin List All Sub-Services', `${MONOLITH}/admin/sub-services`, {
    headers: { 'Authorization': `Bearer ${adminToken}` }
  });

  // ==========================================
  // Section 7: Admin - Worker Governance & KYC
  // ==========================================
  console.log('\n--- 7. Admin - Worker Governance ---');
  const workersListRes = await request('Admin List All Workers', `${MONOLITH}/admin/workers`, {
    headers: { 'Authorization': `Bearer ${adminToken}` }
  });

  let sampleWorkerId = null;
  let sampleWorkerUserId = null;
  if (Array.isArray(workersListRes.body) && workersListRes.body.length > 0) {
    sampleWorkerId = workersListRes.body[0].workerId;
    sampleWorkerUserId = workersListRes.body[0].userId;

    await request('Admin Get Worker by ID', `${MONOLITH}/admin/workers/${sampleWorkerId}`, {
      headers: { 'Authorization': `Bearer ${adminToken}` }
    });

    await request('Admin Verify Worker KYC', `${MONOLITH}/admin/workers/${sampleWorkerId}/verify-kyc`, {
      method: 'PUT',
      headers: { 'Authorization': `Bearer ${adminToken}` }
    });

    await request('Admin Block Worker (24h)', `${MONOLITH}/admin/workers/${sampleWorkerId}/block?hours=24`, {
      method: 'PUT',
      headers: { 'Authorization': `Bearer ${adminToken}` }
    });

    await request('Admin Unblock Worker', `${MONOLITH}/admin/workers/${sampleWorkerId}/unblock`, {
      method: 'PUT',
      headers: { 'Authorization': `Bearer ${adminToken}` }
    });
  }

  // ==========================================
  // Section 8: Admin - Users & Roles Management
  // ==========================================
  console.log('\n--- 8. Admin - Users & Roles ---');
  const allUsersRes = await request('Admin List All Users', `${MONOLITH}/admin/users`, {
    headers: { 'Authorization': `Bearer ${adminToken}` }
  });

  let targetUserToInspect = customerUserId || (Array.isArray(allUsersRes.body) && allUsersRes.body[0]?.userId);
  if (targetUserToInspect) {
    await request('Admin Get User by ID', `${MONOLITH}/admin/users/${targetUserToInspect}`, {
      headers: { 'Authorization': `Bearer ${adminToken}` }
    });

    await request('Admin Update User Role', `${MONOLITH}/admin/users/${targetUserToInspect}/role`, {
      method: 'PATCH',
      headers: { 'Authorization': `Bearer ${adminToken}` },
      body: JSON.stringify({ role: 'CUSTOMER' })
    });
  }

  // ==========================================
  // Section 9: Admin - Notification Broadcasts
  // ==========================================
  console.log('\n--- 9. Admin - Notification Broadcasts ---');
  await request('Admin Send Instant Notification', `${MONOLITH}/admin/notifications/instant`, {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${adminToken}` },
    body: JSON.stringify({
      title: 'Automated System Announcement',
      body: 'Maintenance check completed successfully'
    })
  });

  await request('Admin Schedule Notification', `${MONOLITH}/admin/notifications/schedule`, {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${adminToken}` },
    body: JSON.stringify({
      title: 'Scheduled Promo Notification',
      message: 'Weekend discount starting soon!',
      type: 'PROMO',
      scheduledAt: '2026-10-10T10:00:00Z'
    })
  });

  await request('Admin List Notifications', `${MONOLITH}/admin/notifications`, {
    headers: { 'Authorization': `Bearer ${adminToken}` }
  });

  // ==========================================
  // Section 10: Worker Partner APIs
  // ==========================================
  console.log('\n--- 10. Worker Partner Endpoints ---');
  const workerPhone = '+919876543210';
  const workerAuthRes = await request('Worker OTP Verification', `${MONOLITH}/auth/otp/verify`, {
    method: 'POST',
    body: JSON.stringify({ phoneNumber: workerPhone, otpCode: '123456' })
  });
  const workerToken = workerAuthRes.body?.token;
  const workerUserId = workerAuthRes.body?.userId || sampleWorkerUserId;

  if (workerUserId) {
    await request('Worker Register / Submit KYC', `${MONOLITH}/workers/register-kyc`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${workerToken}`,
        'X-User-Id': workerUserId
      },
      body: JSON.stringify({
        address: '#123 Tech Park, Whitefield, Bengaluru',
        panNumber: 'ABCDE1234F',
        panDocUrl: 'https://gheiuyygjssqatziviix.supabase.co/storage/v1/object/public/homeease-worker-kyc/pan.pdf',
        aadhaarDocUrl: 'https://gheiuyygjssqatziviix.supabase.co/storage/v1/object/public/homeease-worker-kyc/aadhaar.pdf',
        bankAccountNo: '987654321012',
        bankIfsc: 'SBIN0001234',
        currentLat: 12.9716,
        currentLng: 77.5946
      })
    });

    await request('Worker Get Profile', `${MONOLITH}/workers/profile`, {
      headers: {
        'Authorization': `Bearer ${workerToken}`,
        'X-User-Id': workerUserId
      }
    });

    await request('Worker Toggle Online Status (true)', `${MONOLITH}/workers/status`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${workerToken}`,
        'X-User-Id': workerUserId
      },
      body: JSON.stringify({ isOnline: true })
    });

    await request('Worker Send Live GPS Location', `${MONOLITH}/workers/location`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${workerToken}`,
        'X-User-Id': workerUserId
      },
      body: JSON.stringify({ lat: 12.971944, lng: 77.593688 })
    });

    await request('Worker Get Earnings Dashboard', `${MONOLITH}/workers/earnings`, {
      headers: {
        'Authorization': `Bearer ${workerToken}`,
        'X-User-Id': workerUserId
      }
    });

    await request('Worker Get Pending Booking Requests', `${MONOLITH}/workers/bookings/requests`, {
      headers: {
        'Authorization': `Bearer ${workerToken}`,
        'X-User-Id': workerUserId
      }
    });
  }

  // ==========================================
  // Section 11: Customer Bookings & Worker Fulfillment Cycle
  // ==========================================
  console.log('\n--- 11. Customer Booking Lifecycle & Worker Execution ---');
  let activeBookingId = null;
  let bookingPinCode = null;

  if (customerUserId && sampleServiceId && sampleSubServiceId) {
    const bookingRes = await request('Customer Create Booking', `${MONOLITH}/bookings`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${customerToken}`,
        'X-User-Id': customerUserId
      },
      body: JSON.stringify({
        serviceId: sampleServiceId,
        subServices: [
          {
            subServiceId: sampleSubServiceId,
            quantity: 1
          }
        ],
        scheduledAt: '2026-10-10T10:00:00Z',
        userLat: 12.9716,
        userLng: 77.5946,
        paymentMethod: 'COD',
        couponCode: 'WELCOME50'
      })
    });

    activeBookingId = bookingRes.body?.bookingId;
    bookingPinCode = bookingRes.body?.pinCode;

    await request('Customer Get My Bookings', `${MONOLITH}/bookings/my-bookings`, {
      headers: {
        'Authorization': `Bearer ${customerToken}`,
        'X-User-Id': customerUserId
      }
    });

    if (activeBookingId) {
      await request('Get Booking Details by ID', `${MONOLITH}/bookings/${activeBookingId}`, {
        headers: { 'Authorization': `Bearer ${customerToken}` }
      });

      // Worker Accept Booking
      if (workerUserId) {
        await request('Worker Accept Booking', `${MONOLITH}/workers/bookings/${activeBookingId}/accept`, {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${workerToken}`,
            'X-User-Id': workerUserId
          }
        });

        // Worker Add Extra Sub-Service
        await request('Worker Add Extra Sub-Service Line Item', `${MONOLITH}/workers/bookings/${activeBookingId}/add-sub-service`, {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${workerToken}`,
            'X-User-Id': workerUserId
          },
          body: JSON.stringify({
            subServiceId: sampleSubServiceId,
            quantity: 1
          })
        });

        // Verify PIN to Start Job
        if (bookingPinCode) {
          await request('Worker Verify PIN & Start Job', `${MONOLITH}/workers/bookings/${activeBookingId}/verify-pin`, {
            method: 'POST',
            headers: {
              'Authorization': `Bearer ${workerToken}`,
              'X-User-Id': workerUserId
            },
            body: JSON.stringify({ pinCode: bookingPinCode })
          });
        }

        // Complete Job
        await request('Worker Complete Job', `${MONOLITH}/workers/bookings/${activeBookingId}/complete`, {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${workerToken}`,
            'X-User-Id': workerUserId
          }
        });
      }
    }
  }

  // ==========================================
  // Section 12: Billing & Invoicing & Webhooks
  // ==========================================
  console.log('\n--- 12. Billing & Webhooks ---');
  await request('Billing Commission & Payout Calculation', `${MONOLITH}/billing/calculate`, {
    method: 'POST',
    body: JSON.stringify({
      baseAmount: 1000,
      extraAmount: 250,
      discount: 50
    })
  });

  if (activeBookingId) {
    await request('Get Booking Invoice', `${MONOLITH}/billing/invoice/${activeBookingId}`);
  }

  await request('Handle Payment Webhook (Razorpay/Mock)', `${MONOLITH}/payments/webhooks`, {
    method: 'POST',
    headers: { 'X-Webhook-Signature': 'mock_sha256_signature_hex' },
    body: JSON.stringify({
      event: 'payment.captured',
      payload: {
        payment: {
          entity: {
            id: 'pay_1234567890',
            amount: 50000,
            currency: 'INR',
            status: 'captured'
          }
        }
      }
    })
  });

  // ==========================================
  // Section 13: Microservice - Dispatch (:8081)
  // ==========================================
  console.log('\n--- 13. Dispatch Microservice (:8081) ---');
  await request('Dispatch Trigger Spatial Matching', `${DISPATCH}/dispatch/trigger`, {
    method: 'POST',
    body: JSON.stringify({
      bookingId: activeBookingId || '00000000-0000-0000-0000-000000000000',
      subServiceId: sampleSubServiceId || '00000000-0000-0000-0000-000000000000',
      userLat: 12.9716,
      userLng: 77.5946
    })
  });

  await request('Dispatch Accept Job', `${DISPATCH}/dispatch/accept`, {
    method: 'POST',
    body: JSON.stringify({
      bookingId: activeBookingId || '00000000-0000-0000-0000-000000000000',
      workerId: sampleWorkerId || '00000000-0000-0000-0000-000000000000'
    })
  });

  await request('Dispatch Reject Job', `${DISPATCH}/dispatch/reject`, {
    method: 'POST',
    body: JSON.stringify({
      bookingId: activeBookingId || '00000000-0000-0000-0000-000000000000',
      workerId: sampleWorkerId || '00000000-0000-0000-0000-000000000000'
    })
  });

  // ==========================================
  // Section 14: Microservice - Live Tracking (:8082)
  // ==========================================
  console.log('\n--- 14. Live Tracking Microservice (:8082) ---');
  await request('Tracking Update Live GPS Coordinates', `${TRACKING}/tracking/update-location`, {
    method: 'POST',
    body: JSON.stringify({
      bookingId: activeBookingId || 'test-booking-id',
      lat: 12.9716,
      lng: 77.5946
    })
  });

  await request('Tracking Fetch Latest Location', `${TRACKING}/tracking/latest/${activeBookingId || 'test-booking-id'}`);

  // ==========================================
  // Section 15: Microservice - Notification (:8083)
  // ==========================================
  console.log('\n--- 15. Notification Microservice (:8083) ---');
  await request('Notification Dispatch Push Alert', `${NOTIFY}/notifications/push`, {
    method: 'POST',
    body: JSON.stringify({
      recipientUserId: customerUserId || '00000000-0000-0000-0000-000000000000',
      title: 'Partner Arriving Soon',
      message: 'Worker is 2 mins away from your location.',
      fcmToken: 'mock_fcm_token'
    })
  });

  // ==========================================
  // Section 16: File Upload Service (:8080)
  // ==========================================
  console.log('\n--- 16. Supabase S3 File Upload Service (:8080) ---');
  const formData = new FormData();
  const dummyFile = new Blob(['PNG_TEST_CONTENT'], { type: 'image/png' });
  formData.append('file', dummyFile, 'test-image.png');
  await request('Upload Service Image to S3', `${MONOLITH}/upload/service-image`, {
    method: 'POST',
    body: formData
  });

  // ==========================================
  // Summary Report
  // ==========================================
  console.log('\n================================================================');
  console.log('📊 TEST EXECUTION SUMMARY REPORT');
  console.log('================================================================');
  const passed = results.filter(r => r.ok).length;
  const failed = results.filter(r => !r.ok).length;
  const total = results.length;
  const passRate = ((passed / total) * 100).toFixed(1);

  console.log(`Total APIs Tested: ${total}`);
  console.log(`Passed:            ${passed} (${passRate}%)`);
  console.log(`Failed:            ${failed}`);
  console.log('----------------------------------------------------------------');

  if (failed > 0) {
    console.log('\n❌ FAILED ENDPOINTS:');
    results.filter(r => !r.ok).forEach(r => {
      console.log(`- [${r.status}] ${r.method} ${r.url}`);
      console.log(`  Name: ${r.name}`);
      console.log(`  Reason: ${r.details}\n`);
    });
  } else {
    console.log('\n🎉 ALL APIS PASSED WITH 100% SUCCESS RATE!');
  }
}

runAllTests().catch(console.error);
