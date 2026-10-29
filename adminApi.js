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
// 1. Authentication & Profile
// ==========================================
export const adminLogin = (email, password) =>
  adminClient.post('/admin/auth/login', { email, password });

export const getAdminProfile = () =>
  adminClient.get('/users/me');

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

// ==========================================
// 12. Supabase Storage Upload APIs
// ==========================================
const createFormData = (file) => {
  const formData = new FormData();
  formData.append('file', file);
  return formData;
};

export const uploadServiceImage = (file) =>
  adminClient.post('/upload/service-image', createFormData(file), {
    headers: { 'Content-Type': 'multipart/form-data' },
  });

export const uploadSubServiceImage = (file) =>
  adminClient.post('/upload/sub-service-image', createFormData(file), {
    headers: { 'Content-Type': 'multipart/form-data' },
  });

export const uploadBannerImage = (file) =>
  adminClient.post('/upload/banner-image', createFormData(file), {
    headers: { 'Content-Type': 'multipart/form-data' },
  });

export const uploadWorkerProfile = (workerId, file) =>
  adminClient.post(`/upload/worker-profile/${workerId}`, createFormData(file), {
    headers: { 'Content-Type': 'multipart/form-data' },
  });

export const uploadWorkerPan = (workerId, file) =>
  adminClient.post(`/upload/worker-kyc/pan/${workerId}`, createFormData(file), {
    headers: { 'Content-Type': 'multipart/form-data' },
  });

export const uploadWorkerAadhaar = (workerId, file) =>
  adminClient.post(`/upload/worker-kyc/aadhaar/${workerId}`, createFormData(file), {
    headers: { 'Content-Type': 'multipart/form-data' },
  });

// ==========================================
// 13. Bookings Inspection & Support
// ==========================================
export const getBookingById = (bookingId) =>
  adminClient.get(`/bookings/${bookingId}`);

export const cancelBookingByAdmin = (bookingId, adminUserId, reason = 'Cancelled by administrator') =>
  adminClient.post(`/bookings/${bookingId}/cancel?reason=${encodeURIComponent(reason)}`, null, {
    headers: { 'X-User-Id': adminUserId },
  });

export const updateBookingStatusByAdmin = (bookingId, status, adminUserId) =>
  adminClient.patch(`/bookings/${bookingId}/status?status=${status}`, null, {
    headers: { 'X-User-Id': adminUserId },
  });


// ==========================================
// 11. Service Area Management
// ==========================================

/** GET /admin/service-areas — overview stats + all states */
export const getServiceAreaOverview = () =>
  adminClient.get('/admin/service-areas');

// --- States ---

/** GET /admin/service-areas/states */
export const getStates = () =>
  adminClient.get('/admin/service-areas/states');

/**
 * POST /admin/service-areas/states
 * @param {string} adminUserId
 * @param {{ name: string, isActive?: boolean }} body
 */
export const createState = (adminUserId, body) =>
  adminClient.post('/admin/service-areas/states', body, {
    headers: { 'X-User-Id': adminUserId },
  });

/**
 * PUT /admin/service-areas/states/:id
 * Pass confirm=true to proceed past the deactivation warning.
 * @param {string} adminUserId
 * @param {string} stateId
 * @param {{ name?: string, isActive?: boolean }} body
 * @param {boolean} confirm
 */
export const updateState = (adminUserId, stateId, body, confirm = false) =>
  adminClient.put(`/admin/service-areas/states/${stateId}?confirm=${confirm}`, body, {
    headers: { 'X-User-Id': adminUserId },
  });

// --- Cities ---

/**
 * GET /admin/service-areas/cities
 * @param {string|null} stateId  optional filter
 */
export const getCities = (stateId = null) =>
  adminClient.get('/admin/service-areas/cities', {
    params: stateId ? { stateId } : {},
  });

/**
 * POST /admin/service-areas/cities
 * @param {string} adminUserId
 * @param {{ stateId: string, name: string, isActive?: boolean }} body
 */
export const createCity = (adminUserId, body) =>
  adminClient.post('/admin/service-areas/cities', body, {
    headers: { 'X-User-Id': adminUserId },
  });

/**
 * PUT /admin/service-areas/cities/:id
 * @param {string} adminUserId
 * @param {string} cityId
 * @param {{ name?: string, isActive?: boolean }} body
 * @param {boolean} confirm
 */
export const updateCity = (adminUserId, cityId, body, confirm = false) =>
  adminClient.put(`/admin/service-areas/cities/${cityId}?confirm=${confirm}`, body, {
    headers: { 'X-User-Id': adminUserId },
  });

// --- Pincodes ---

/**
 * GET /admin/service-areas/pincodes
 * @param {{ stateId?, cityId?, active?, q?, page?, size? }} params
 */
export const getPincodes = (params = {}) =>
  adminClient.get('/admin/service-areas/pincodes', { params });

/**
 * POST /admin/service-areas/pincodes
 * @param {string} adminUserId
 * @param {{ pincode: string, cityId: string, stateId: string, areaName?: string, isActive?: boolean }} body
 */
export const createPincode = (adminUserId, body) =>
  adminClient.post('/admin/service-areas/pincodes', body, {
    headers: { 'X-User-Id': adminUserId },
  });

/**
 * POST /admin/service-areas/pincodes/bulk
 * @param {string} adminUserId
 * @param {Array<{ pincode, cityId, stateId, areaName?, isActive? }>} list
 */
export const bulkCreatePincodes = (adminUserId, list) =>
  adminClient.post('/admin/service-areas/pincodes/bulk', list, {
    headers: { 'X-User-Id': adminUserId },
  });

/**
 * PUT /admin/service-areas/pincodes/:id
 * @param {string} adminUserId
 * @param {string} pincodeId
 * @param {{ areaName?: string, isActive?: boolean }} body
 * @param {boolean} confirm  set true to proceed past deactivation warning
 */
export const updatePincode = (adminUserId, pincodeId, body, confirm = false) =>
  adminClient.put(`/admin/service-areas/pincodes/${pincodeId}?confirm=${confirm}`, body, {
    headers: { 'X-User-Id': adminUserId },
  });

// --- Public Customer Check (no auth) ---

/**
 * GET /serviceability/check?pincode=XXXXXX
 * Does NOT use adminClient — this is a public endpoint.
 * @param {string} pincode  6-digit Indian pincode
 */
export const checkServiceability = (pincode) =>
  axios.get(`${MONOLITH_BASE_URL}/serviceability/check`, { params: { pincode } });

// ==========================================
// 14. Booking Event History (Audit Trail)
// ==========================================
/**
 * GET /bookings/:id/history
 * Query immutable audit trail of booking lifecycle events.
 * @param {string} bookingId
 * @param {string} adminUserId
 * @param {{ page?: number, size?: number }} [params]
 */
export const getBookingHistory = (bookingId, adminUserId, params = { page: 0, size: 20 }) =>
  adminClient.get(`/bookings/${bookingId}/history`, {
    headers: {
      'X-User-Id': adminUserId,
      'X-User-Role': 'ADMIN',
    },
    params,
  });

