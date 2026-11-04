// ==============================================================================
// HOME EASE - ENTERPRISE GPS INGESTION K6 BENCHMARK SCRIPT
// Target: 100,000 GPS Ingestion Events/sec into Nginx -> Gateway -> Kafka -> Redis
// ==============================================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Custom Metrics
const gpsIngestionLatency = new Trend('gps_ingestion_latency_ms', true);
const gpsSuccessRate = new Rate('gps_success_rate');
const gpsTotalEvents = new Counter('gps_total_events');

export const options = {
  scenarios: {
    high_throughput_ingest: {
      executor: 'ramping-arrival-rate',
      startRate: 10000,
      timeUnit: '1s',
      preAllocatedVUs: 5000,
      maxVUs: 20000,
      stages: [
        { duration: '30s', target: 25000 },  // Warmup to 25k req/s
        { duration: '1m', target: 50000 },   // Step to 50k req/s
        { duration: '2m', target: 100000 },  // Sustain 100,000 req/s peak
        { duration: '30s', target: 10000 },  // Rampdown
      ],
    },
  },
  thresholds: {
    'http_req_duration': ['p(50)<15', 'p(95)<35', 'p(99)<75'], // 99% under 75ms
    'gps_success_rate': ['rate>0.999'],                         // 99.9% success
  },
};

const BASE_URL = __ENV.NGINX_TARGET_URL || 'http://localhost:8082';
const BOOKING_COUNT = 500000;

export default function () {
  // Generate pseudo-random realistic GPS coordinates around Hyderabad / Bengaluru
  const bookingIndex = Math.floor(Math.random() * BOOKING_COUNT) + 1;
  const bookingId = `BK-HYD-${String(bookingIndex).padStart(6, '0')}`;
  const workerId = `WRK-${String(bookingIndex % 50000).padStart(5, '0')}`;

  const lat = 17.385044 + (Math.random() - 0.5) * 0.15;
  const lng = 78.486671 + (Math.random() - 0.5) * 0.15;
  const bearing = Math.floor(Math.random() * 360);
  const speed = 5.0 + Math.random() * 15.0; // 5-20 m/s (~18-72 km/h)
  const accuracy = 3.0 + Math.random() * 8.0;

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
    timeout: '2s',
  };

  const start = Date.now();
  const res = http.post(`${BASE_URL}/api/v1/tracking/partner/location`, payload, params);
  const duration = Date.now() - start;

  gpsIngestionLatency.add(duration);
  gpsTotalEvents.add(1);

  const isSuccess = check(res, {
    'status is 200 or 202': (r) => r.status === 200 || r.status === 202,
    'latency under 100ms': () => duration < 100,
  });

  gpsSuccessRate.add(isSuccess);
}

