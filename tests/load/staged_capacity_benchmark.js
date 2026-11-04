// ==============================================================================
// HOME EASE - STAGED CAPACITY BENCHMARK: 100 -> 500 -> 1,000 CONCURRENT SESSIONS
// Initial Production Target: 1,000 Concurrent Live Tracking Sessions
// Updates: 1 GPS update every 5 seconds per worker (200 req/sec at peak 1,000 sessions)
// ==============================================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Custom Metrics
const gpsLatency = new Trend('gps_delivery_latency_ms', true);
const gpsSuccess = new Rate('gps_delivery_success_rate');
const totalPings = new Counter('total_gps_pings_processed');

export const options = {
  scenarios: {
    // -------------------------------------------------------------------------
    // STAGE 1: 100 Concurrent Sessions (20 GPS requests/second)
    // -------------------------------------------------------------------------
    stage_100_sessions: {
      executor: 'constant-arrival-rate',
      rate: 20, // 100 workers pinging every 5 seconds = 20 req/s
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 50,
      maxVUs: 150,
    },
    // -------------------------------------------------------------------------
    // STAGE 2: 500 Concurrent Sessions (100 GPS requests/second)
    // -------------------------------------------------------------------------
    stage_500_sessions: {
      executor: 'constant-arrival-rate',
      rate: 100, // 500 workers pinging every 5 seconds = 100 req/s
      timeUnit: '1s',
      startTime: '35s',
      duration: '45s',
      preAllocatedVUs: 150,
      maxVUs: 400,
    },
    // -------------------------------------------------------------------------
    // STAGE 3: 1,000 Concurrent Sessions (200 GPS requests/second)
    // -------------------------------------------------------------------------
    stage_1000_sessions: {
      executor: 'constant-arrival-rate',
      rate: 200, // 1,000 workers pinging every 5 seconds = 200 req/s
      timeUnit: '1s',
      startTime: '1m25s',
      duration: '1m',
      preAllocatedVUs: 300,
      maxVUs: 1000,
    },
  },
  thresholds: {
    'http_req_duration': ['p(50)<10', 'p(95)<25', 'p(99)<50'], // p99 < 50ms
    'gps_delivery_success_rate': ['rate>0.999'],                // 99.9% success
  },
};

const BASE_URL = __ENV.TARGET_URL || 'http://localhost:8082';

export default function () {
  const sessionNum = Math.floor(Math.random() * 1000) + 1;
  const bookingId = `BK-SES-${String(sessionNum).padStart(4, '0')}`;
  const workerId = `WRK-${String(sessionNum).padStart(4, '0')}`;

  // Real coordinate generation around Hyderabad city center
  const lat = 17.44829 + (Math.random() - 0.5) * 0.05;
  const lng = 78.37452 + (Math.random() - 0.5) * 0.05;
  const bearing = Math.floor(Math.random() * 360);
  const speed = 6.5 + Math.random() * 10.0; // ~23 to 60 km/h
  const accuracy = 3.5 + Math.random() * 4.0; // 3.5m to 7.5m

  const payload = JSON.stringify({
    bookingId: bookingId,
    lat: lat,
    lng: lng,
    bearing: bearing,
    speed: speed,
    accuracy: accuracy,
    workerId: workerId,
    partnerId: workerId,
    timestamp: Date.now(),
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': workerId,
    },
    timeout: '3s',
  };

  const start = Date.now();
  const res = http.post(`${BASE_URL}/api/v1/tracking/partner/location`, payload, params);
  const latency = Date.now() - start;

  gpsLatency.add(latency);
  totalPings.add(1);

  const ok = check(res, {
    'HTTP status 200': (r) => r.status === 200 || r.status === 202,
    'Fast response (< 50ms)': () => latency < 50,
  });

  gpsSuccess.add(ok);
}

