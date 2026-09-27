import axios from 'axios';

// ==========================================
// Base URLs for Production AWS EC2 (3.107.161.126)
// ==========================================
export const MONOLITH_BASE_URL = 'http://3.107.161.126:8080/api/v1';
export const DISPATCH_BASE_URL = 'http://3.107.161.126:8081/api/v1';
export const TRACKING_BASE_URL = 'http://3.107.161.126:8082/api/v1';
export const NOTIFY_BASE_URL   = 'http://3.107.161.126:8083/api/v1';

// Monolith Admin API Axios Client
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

// ==========================================
// 1. Authentication
// ==========================================
export const adminLogin = (email, password) =>
  adminClient.post('/admin/auth/login', { email, password });

// ==========================================
// 2. Dashboard Analytics
// ==========================================
export const getDashboardStats = () =>
  adminClient.get('/admin/dashboard/stats');

// ==========================================
// 3. Payments Management
// ==========================================
export const getAllPayments = () =>
  adminClient.get('/admin/payments');

export const getPaymentById = (id) =>
  adminClient.get(`/admin/payments/${id}`);

export const updatePaymentStatus = (id, status) =>
  adminClient.patch(`/admin/payments/${id}/status`, { status });

// ==========================================
// 4. Banners Management
// ==========================================
export const getAllBanners = (activeOnly = false) =>
  adminClient.get(`/admin/banners?activeOnly=${activeOnly}`);

export const getBannerById = (id) =>
  adminClient.get(`/admin/banners/${id}`);

export const createBanner = (bannerData) =>
  adminClient.post('/admin/banners', bannerData);

export const patchBanner = (id, patchData) =>
  adminClient.patch(`/admin/banners/${id}`, patchData);

export const deleteBanner = (id, hard = false) =>
  adminClient.delete(`/admin/banners/${id}?hard=${hard}`);

// ==========================================
// 5. Coupons Management
// ==========================================
export const getAllCoupons = (activeOnly = false) =>
  adminClient.get(`/admin/coupons?activeOnly=${activeOnly}`);

export const createCoupon = (couponData) =>
  adminClient.post('/admin/coupons', couponData);

export const patchCoupon = (id, patchData) =>
  adminClient.patch(`/admin/coupons/${id}`, patchData);

export const deleteCoupon = (id, hard = false) =>
  adminClient.delete(`/admin/coupons/${id}?hard=${hard}`);

// ==========================================
// 6. Live Location Map Overview
// ==========================================
export const getLiveMapOverview = () =>
  adminClient.get('/admin/locations/live-map');

// ==========================================
// 7. Worker Partner Governance
// ==========================================
export const getAllWorkers = () =>
  adminClient.get('/admin/workers');

export const getWorkerById = (id) =>
  adminClient.get(`/admin/workers/${id}`);

export const verifyWorkerKyc = (id) =>
  adminClient.put(`/admin/workers/${id}/verify-kyc`);

export const blockWorker = (id, hours = 24) =>
  adminClient.put(`/admin/workers/${id}/block?hours=${hours}`);

export const unblockWorker = (id) =>
  adminClient.put(`/admin/workers/${id}/unblock`);

// ==========================================
// 8. User / Customer Management
// ==========================================
export const getAllUsers = (role) =>
  adminClient.get('/admin/users', { params: role ? { role } : {} });

export const getUserById = (id) =>
  adminClient.get(`/admin/users/${id}`);

export const updateUserRole = (id, role) =>
  adminClient.patch(`/admin/users/${id}/role`, { role });

// ==========================================
// 9. Services & Sub-Services Catalog
// ==========================================
export const getAllServices = () =>
  adminClient.get('/admin/services');

export const createService = (serviceData) =>
  adminClient.post('/admin/services', serviceData);

export const updateService = (id, serviceData) =>
  adminClient.put(`/admin/services/${id}`, serviceData);

export const deleteService = (id) =>
  adminClient.delete(`/admin/services/${id}`);

export const getAllSubServices = () =>
  adminClient.get('/admin/sub-services');

export const createSubService = (subServiceData) =>
  adminClient.post('/admin/sub-services', subServiceData);

export const updateSubService = (id, subServiceData) =>
  adminClient.put(`/admin/sub-services/${id}`, subServiceData);

export const deleteSubService = (id) =>
  adminClient.delete(`/admin/sub-services/${id}`);

// ==========================================
// 10. Push Notifications (Admin Broadcast)
// ==========================================
export const sendInstantNotification = (recipientUserId, title, body) =>
  adminClient.post('/admin/notifications/instant', { recipientUserId, title, body });

export const scheduleNotification = (notificationData) =>
  adminClient.post('/admin/notifications/schedule', notificationData);

export const getAllNotifications = () =>
  adminClient.get('/admin/notifications');

// ==========================================
// 11. Microservice Specific Calls
// ==========================================
// Dispatch Microservice (:8081)
export const triggerDispatchMatching = (data) =>
  axios.post(`${DISPATCH_BASE_URL}/dispatch/trigger`, data);

export const acceptDispatchJob = (data) =>
  axios.post(`${DISPATCH_BASE_URL}/dispatch/accept`, data);

export const rejectDispatchJob = (data) =>
  axios.post(`${DISPATCH_BASE_URL}/dispatch/reject`, data);

// Tracking Microservice (:8082)
export const getLatestTrackingLocation = (bookingId) =>
  axios.get(`${TRACKING_BASE_URL}/tracking/latest/${bookingId}`);

export const updateTrackingLocation = (data) =>
  axios.post(`${TRACKING_BASE_URL}/tracking/update-location`, data);

// Notification Microservice (:8083)
export const sendDirectPushNotification = (data) =>
  axios.post(`${NOTIFY_BASE_URL}/notifications/push`, data);
