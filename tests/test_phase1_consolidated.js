// ==============================================================================
// HOME EASE – Phase 1 Consolidated End-to-End Smoke Test Suite
// Verifies Nginx Reverse Proxy, Spring Boot Core (:8080), Tracking (:8082),
// and Redis (:6379) co-located on a Single EC2.
//
// Usage:
//   node tests/test_phase1_consolidated.js [BASE_URL]
// Examples:
//   node tests/test_phase1_consolidated.js https://3.107.161.126
//   node tests/test_phase1_consolidated.js http://localhost
// ==============================================================================

const https = require('https');
const http = require('http');

const BASE_URL = process.argv[2] || process.env.BASE_URL || 'http://localhost';
const isHttps = BASE_URL.startsWith('https');

console.log('================================================================');
console.log('🚀 HomeEase Phase 1 Consolidated Stack Verification');
console.log(`Target: ${BASE_URL}`);
console.log('================================================================\n');

// Allow self-signed certs during testing
const agent = isHttps ? new https.Agent({ rejectUnauthorized: false }) : new http.Agent();

// Map route prefixes to target port if running directly against microservices
function resolveTargetPort(url, path) {
    if (url.port && url.port !== '') return parseInt(url.port, 10);
    if (url.protocol === 'https:') return 443;
    if (url.protocol === 'http:') return 80;
    // Direct microservice routing fallback
    if (path.startsWith('/api/v1/tracking') || path.startsWith('/ws') || path === '/api/v1/health' || path === '/api/v1/health/detail') {
        return 8082;
    }
    return 8080;
}

function request(name, path, options = {}) {
    return new Promise((resolve) => {
        const start = Date.now();
        const urlStr = `${BASE_URL}${path}`;
        const url = new URL(urlStr);
        const protocol = url.protocol === 'https:' ? https : http;

        const reqOptions = {
            protocol: url.protocol,
            hostname: url.hostname,
            port: resolveTargetPort(url, path),
            path: url.pathname + url.search,
            method: options.method || 'GET',
            headers: {
                'Content-Type': 'application/json',
                ...(options.headers || {})
            },
            agent: agent,
            timeout: 5000
        };

        const req = protocol.request(reqOptions, (res) => {
            let data = '';
            res.on('data', (chunk) => { data += chunk; });
            res.on('end', () => {
                const durationMs = Date.now() - start;
                let body = data;
                try { body = JSON.parse(data); } catch (_) {}
                const ok = res.statusCode >= 200 && res.statusCode < 300;
                const statusTag = ok ? '✓ PASS' : '✗ FAIL';
                console.log(`[${statusTag}] [${res.statusCode}] ${reqOptions.method} ${path} - ${name} (${durationMs}ms)`);
                if (!ok) {
                    console.log(`   ↳ Response: ${typeof body === 'object' ? JSON.stringify(body).slice(0, 160) : String(body).slice(0, 160)}`);
                }
                resolve({ name, ok, statusCode: res.statusCode, body, durationMs });
            });
        });

        req.on('error', (err) => {
            const durationMs = Date.now() - start;
            console.log(`[✗ FAIL] [ERR] ${reqOptions.method} ${path} - ${name} (${durationMs}ms)`);
            console.log(`   ↳ Network Error: ${err.message}`);
            resolve({ name, ok: false, statusCode: 0, error: err.message, durationMs });
        });

        if (options.body) {
            req.write(typeof options.body === 'string' ? options.body : JSON.stringify(options.body));
        }
        req.end();
    });
}

async function runSuite() {
    const results = [];
    const testBookingId = `BK-PHASE1-${Date.now()}`;

    console.log('--- 1. Infrastructure & Ingress Health ---');
    results.push(await request('Nginx Edge Health', '/healthz'));
    results.push(await request('Spring Boot Core Actuator', '/actuator/health'));
    results.push(await request('Tracking Service Detailed Health (Redis ping)', '/api/v1/health/detail'));

    console.log('\n--- 2. Core Service Catalog APIs ---');
    results.push(await request('Fetch Active Services', '/api/v1/services'));
    results.push(await request('Fetch Promotional Banners', '/api/v1/banners'));

    console.log('\n--- 3. Real-Time Tracking & GPS Ingestion ---');
    // High-frequency partner GPS location update
    results.push(await request('Partner GPS Ingestion (POST)', '/api/v1/tracking/partner/location', {
        method: 'POST',
        headers: { 'X-User-Id': '00000000-0000-0000-0000-000000000001' },
        body: {
            bookingId: testBookingId,
            lat: 12.9716,
            lng: 77.5946,
            bearing: 85.0,
            speed: 12.4,
            accuracy: 4.2,
            timestamp: Date.now()
        }
    }));

    // Fetch latest location snapshot (cold-start hydration)
    results.push(await request('Get Latest GPS Snapshot', `/api/v1/tracking/latest/${testBookingId}`, {
        headers: { 'X-User-Id': '00000000-0000-0000-0000-000000000001' }
    }));

    // Test LRU-cached route computation
    results.push(await request('Compute Road Route & Directions', '/api/v1/tracking/route?startLat=12.9716&startLng=77.5946&destLat=12.9352&destLng=77.6245'));

    console.log('\n--- 4. Summary & Verification Report ---');
    const total = results.length;
    const passed = results.filter(r => r.ok).length;
    const failed = total - passed;
    console.log(`Total Tests : ${total}`);
    console.log(`Passed      : ${passed} ✓`);
    console.log(`Failed      : ${failed} ✗`);
    console.log(`Success Rate: ${Math.round((passed / total) * 100)}%\n`);

    if (failed === 0) {
        console.log('🎉 Phase 1 Consolidated Stack is fully OPERATIONAL!');
    } else {
        console.log('⚠️ Some endpoints returned non-2xx codes. Verify backend service logs with:');
        console.log('   journalctl -u homeease-core -n 30 --no-pager');
        console.log('   journalctl -u homeease-tracking -n 30 --no-pager');
    }
}

runSuite().catch(console.error);

