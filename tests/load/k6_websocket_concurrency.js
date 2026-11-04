// ==============================================================================
// HOME EASE - WEBSOCKET CONCURRENCY & LATENCY BENCHMARK
// Target: High-Concurrency STOMP WebSocket Connection and Delivery Verification
// ==============================================================================

import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const wsConnectionLatency = new Trend('ws_connect_latency_ms');
const messageDeliveryLatency = new Trend('ws_msg_delivery_latency_ms');
const wsErrors = new Rate('ws_error_rate');
const messagesReceived = new Counter('ws_messages_received');

export const options = {
  scenarios: {
    websocket_connections: {
      executor: 'ramping-vus',
      startVUs: 100,
      stages: [
        { duration: '30s', target: 2000 },
        { duration: '1m', target: 5000 },
        { duration: '2m', target: 5000 },
        { duration: '30s', target: 0 },
      ],
    },
  },
  thresholds: {
    'ws_error_rate': ['rate<0.01'],
    'ws_connect_latency_ms': ['p(95)<250'],
  },
};

const WS_URL = __ENV.NGINX_WS_TARGET_URL || 'ws://localhost:8082/ws/websocket';

export default function () {
  const bookingIndex = (__VU % 10000) + 1;
  const bookingId = `BK-HYD-${String(bookingIndex).padStart(6, '0')}`;
  const userId = `CUST-${String(__VU).padStart(6, '0')}`;

  const connectStart = Date.now();

  const response = ws.connect(WS_URL, {
    headers: {
      'X-User-Id': userId,
    },
  }, function (socket) {
    socket.on('open', () => {
      wsConnectionLatency.add(Date.now() - connectStart);

      // 1. Send STOMP CONNECT frame
      const connectFrame = "CONNECT\naccept-version:1.2,1.1,1.0\nheart-beat:10000,10000\nX-User-Id:" + userId + "\n\n\0";
      socket.send(connectFrame);

      // 2. Send STOMP SUBSCRIBE frame to active booking tracking topic
      const subFrame = "SUBSCRIBE\nid:sub-0\ndestination:/topic/tracking/" + bookingId + "\n\n\0";
      socket.send(subFrame);
    });

    socket.on('message', (data) => {
      if (data.includes('MESSAGE')) {
        messagesReceived.add(1);
        try {
          const bodyIndex = data.indexOf('\n\n');
          if (bodyIndex !== -1) {
            const body = JSON.parse(data.substring(bodyIndex + 2).replace(/\0/g, ''));
            if (body.serverTimestamp) {
              messageDeliveryLatency.add(Date.now() - body.serverTimestamp);
            }
          }
        } catch (e) {}
      }
    });

    socket.on('error', (e) => {
      wsErrors.add(1);
    });

    // Hold connection open for tracking duration
    socket.setTimeout(() => {
      socket.close();
    }, 45000);
  });

  check(response, { 'connected successfully': (r) => r && r.status === 101 });
  sleep(1);
}

