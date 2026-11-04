// ==============================================================================
// HOME EASE – PHASE 1 STAGED CAPACITY BENCHMARK
// Tests: 100 → 500 → 1,000 concurrent GPS sessions
// Measures: GPS delivery latency (p50/p95/p99), HTTP success rate,
//           WebSocket connection stability, throughput at each stage
//
// Prerequisites:
//   npm install -g k6
// Run:
//   TRACKING_URL=https://api.homeease.com k6 run tests/load/phase1-capacity-test.js
// ==============================================================================

import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend, Gauge } from 'k6/metrics';

// ── Custom Metrics ─────────────────────────────────────────────────────────
const gpsLatencyMs    = new Trend('gps_p99_latency_ms', true);
const gpsSuccessRate  = new Rate('gps_success_rate');
const totalGpsPings   = new Counter('total_gps_pings');
const wsConnectTime   = new Trend('ws_connect_latency_ms', true);
const activeWsSessions = new Gauge('active_ws_sessions');

// ── Environment ────────────────────────────────────────────────────────────
const BASE_URL       = __ENV.TRACKING_URL || 'https://api.homeease.com';
const GPS_ENDPOINT   = `${BASE_URL}/api/v1/tracking/partner/location`;
const WS_ENDPOINT    = BASE_URL.replace('https://', 'wss://').replace('http://', 'ws://') + '/ws/websocket';

// Shared JWT token (replace with a real test worker token in CI/CD)
const TEST_JWT = __ENV.TEST_JWT || 'Bearer TEST_TOKEN';

// ── Load stages: 100 → 500 → 1,000 concurrent sessions ────────────────────
export const options = {
  scenarios: {

    // ------------------------------------------------------------------
    // STAGE 1: 100 concurrent sessions  (20 GPS req/s = 100 × 1/5s)
    // Duration: 60s   Warm-up: 15s ramp
    // ------------------------------------------------------------------
    stage_100: {
      executor: 'constant-arrival-rate',
      rate: 20,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 40,
      maxVUs: 120,
      startTime: '0s',
      gracefulStop: '10s',
      tags: { stage: '100_sessions' },
    },

    // ------------------------------------------------------------------
    // STAGE 2: 500 concurrent sessions  (100 GPS req/s)
    // Starts 75s after test start (after Stage 1 completes + 15s gap)
    // ------------------------------------------------------------------
    stage_500: {
      executor: 'constant-arrival-rate',
      rate: 100,
      timeUnit: '1s',
      duration: '90s',
      preAllocatedVUs: 150,
      maxVUs: 400,
      startTime: '75s',
      gracefulStop: '10s',
      tags: { stage: '500_sessions' },
    },

    // ------------------------------------------------------------------
    // STAGE 3: 1,000 concurrent sessions  (200 GPS req/s)
    // The primary production target. Run for 2 minutes to get stable p99.
    // ------------------------------------------------------------------
    stage_1000: {
      executor: 'constant-arrival-rate',
      rate: 200,
      timeUnit: '1s',
      duration: '120s',
      preAllocatedVUs: 300,
      maxVUs: 700,
      startTime: '175s',
      gracefulStop: '15s',
      tags: { stage: '1000_sessions' },
    },
  },

  // ── Thresholds: define pass/fail criteria ────────────────────────────
  thresholds: {
    // GPS ingestion latency
    'gps_p99_latency_ms':           ['p(99)<150'],  // <150ms p99 for GPS POST
    'gps_success_rate':             ['rate>0.995'], // >99.5% HTTP 200 success

    // HTTP request stats (k6 built-in)
    'http_req_duration{scenario:stage_100}':  ['p(99)<100'],
    'http_req_duration{scenario:stage_500}':  ['p(99)<120'],
    'http_req_duration{scenario:stage_1000}': ['p(99)<150'],

    'http_req_failed':              ['rate<0.005'], // <0.5% failure overall
    'http_req_duration':            ['p(95)<100', 'p(99)<150'],
  },
};

// ── GPS ingestion scenario (used by all 3 stages) ─────────────────────────
export default function gpsIngestion() {
  // Simulate a real worker booking – randomize to avoid cache collisions
  const workerId = `worker-${Math.floor(Math.random() * 1000)}`;
  const bookingId = `BK-${Math.floor(Math.random() * 500)}`;

  // Simulate movement: random walk around Hyderabad city center
  const baseLat  = 17.4485 + (Math.random() - 0.5) * 0.05;
  const baseLng  = 78.3748 + (Math.random() - 0.5) * 0.05;

  const payload = JSON.stringify({
    bookingId: bookingId,
    workerId:  workerId,
    lat:       baseLat,
    lng:       baseLng,
    bearing:   Math.round(Math.random() * 360),
    speed:     5.5 + Math.random() * 10,     // 5–15 m/s (urban driving)
    accuracy:  5.0 + Math.random() * 10,     // 5–15m (good GPS fix)
    timestamp: Date.now(),
  });

  const params = {
    headers: {
      'Content-Type':  'application/json',
      'Authorization': TEST_JWT,
      'X-User-Id':     workerId,
    },
    timeout: '5s',
    tags: { name: 'GPS_Ingestion' },
  };

  const startMs = Date.now();
  const res = http.post(GPS_ENDPOINT, payload, params);
  const latencyMs = Date.now() - startMs;

  gpsLatencyMs.add(latencyMs);
  totalGpsPings.add(1);

  const success = check(res, {
    'GPS ingestion HTTP 200':       (r) => r.status === 200,
    'GPS response has status key':  (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.status === 'SUCCESS' || body.status === 'QUEUED';
      } catch { return false; }
    },
    'GPS latency < 150ms':         () => latencyMs < 150,
  });

  gpsSuccessRate.add(success);

  // Real workers send 1 GPS update every 5 seconds
  sleep(5);
}

// ── WebSocket connection test (optional, run with ENABLE_WS=true) ─────────
export function wsScenario() {
  if (!__ENV.ENABLE_WS) return;

  const customerBookingId = `BK-${Math.floor(Math.random() * 500)}`;
  const wsUrl = `${WS_ENDPOINT}?bookingId=${customerBookingId}`;

  const startMs = Date.now();
  const res = ws.connect(wsUrl, {
    headers: {
      'Authorization': TEST_JWT,
      'X-User-Id':     `customer-${Math.floor(Math.random() * 1000)}`,
    },
  }, (socket) => {
    const connectMs = Date.now() - startMs;
    wsConnectTime.add(connectMs);
    activeWsSessions.add(1);

    check(connectMs, { 'WebSocket connect < 1000ms': (ms) => ms < 1000 });

    // STOMP CONNECT frame
    socket.send(
      'CONNECT\naccept-version:1.2\nheart-beat:10000,10000\n' +
      `Authorization:${TEST_JWT}\n\n\0`
    );

    socket.on('message', (data) => {
      if (data.startsWith('CONNECTED')) {
        // Subscribe to tracking topic after STOMP handshake
        socket.send(
          `SUBSCRIBE\ndestination:/topic/tracking/${customerBookingId}\n` +
          `id:sub-${customerBookingId}\n\n\0`
        );
      }
    });

    socket.on('error', (e) => {
      console.error(`WS error on booking ${customerBookingId}: ${e}`);
    });

    // Hold connection open for 60 seconds (simulates customer watching tracking)
    socket.setTimeout(() => {
      socket.send('DISCONNECT\n\n\0');
      socket.close();
      activeWsSessions.add(-1);
    }, 60000);
  });

  check(res, { 'WebSocket connected successfully': (r) => r && r.status === 101 });
}

