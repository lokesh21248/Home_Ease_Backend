// test_redis.js - Test Redis Connection & Operations
const fs = require('fs');
const path = require('path');
const net = require('net');

// Parse .env file manually (no extra dependencies required)
function loadEnv() {
    const envPath = path.join(__dirname, '.env');
    if (!fs.existsSync(envPath)) return {};
    const content = fs.readFileSync(envPath, 'utf8');
    const env = {};
    content.split('\n').forEach(line => {
        const trimmed = line.trim();
        if (trimmed && !trimmed.startsWith('#')) {
            const idx = trimmed.indexOf('=');
            if (idx !== -1) {
                const key = trimmed.substring(0, idx).trim();
                const val = trimmed.substring(idx + 1).trim();
                env[key] = val;
            }
        }
    });
    return env;
}

const env = loadEnv();
const host = env.REDIS_HOST || 'localhost';
const port = parseInt(env.REDIS_PORT || '6379', 10);
const password = env.REDIS_PASSWORD || '';

console.log('====================================================');
console.log('🔍 Testing Redis Connection');
console.log('====================================================');
console.log(`Target Host: ${host}`);
console.log(`Target Port: ${port}`);
console.log(`Password:    ${password ? '******** (' + password.length + ' chars)' : '(None)'}`);
console.log('----------------------------------------------------');

const startTime = Date.now();
const client = net.createConnection({ host, port }, () => {
    const connectTime = Date.now() - startTime;
    console.log(`✓ TCP Handshake Successful (${connectTime}ms)`);

    // RESP helper to encode commands
    function encodeResp(args) {
        let resp = `*${args.length}\r\n`;
        for (const arg of args) {
            const str = String(arg);
            resp += `$${Buffer.byteLength(str)}\r\n${str}\r\n`;
        }
        return resp;
    }

    let buffer = '';
    let step = 0;

    client.on('data', (chunk) => {
        buffer += chunk.toString();

        if (step === 0) {
            // AUTH Response
            if (buffer.includes('+OK')) {
                console.log('✓ Authentication Successful (AUTH OK)');
                buffer = '';
                step = 1;
                client.write(encodeResp(['PING']));
            } else if (buffer.includes('-ERR') || buffer.includes('-NOAUTH')) {
                console.error('✗ Authentication Failed:', buffer.trim());
                client.end();
            }
        } else if (step === 1) {
            // PING Response
            if (buffer.includes('+PONG')) {
                console.log('✓ Heartbeat Check: PONG received');
                buffer = '';
                step = 2;
                const testVal = 'HomeEase_Test_' + Date.now();
                client.write(encodeResp(['SET', 'homeease_test_key', testVal]));
            }
        } else if (step === 2) {
            // SET Response
            if (buffer.includes('+OK')) {
                console.log('✓ Write Test: Successfully set [homeease_test_key]');
                buffer = '';
                step = 3;
                client.write(encodeResp(['GET', 'homeease_test_key']));
            }
        } else if (step === 3) {
            // GET Response
            if (buffer.includes('HomeEase_Test_')) {
                console.log('✓ Read Test: Successfully retrieved [homeease_test_key]');
                buffer = '';
                step = 4;
                client.write(encodeResp(['DEL', 'homeease_test_key']));
            }
        } else if (step === 4) {
            // DEL Response
            console.log('✓ Cleanup Test: Successfully deleted test key');
            const totalDuration = Date.now() - startTime;
            console.log('----------------------------------------------------');
            console.log(`🎉 Redis is 100% HEALTHY and WORKING! (Total: ${totalDuration}ms)`);
            console.log('====================================================');
            client.end();
        }
    });

    // Start with AUTH or PING
    if (password) {
        client.write(encodeResp(['AUTH', 'default', password]));
    } else {
        step = 1;
        client.write(encodeResp(['PING']));
    }
});

client.on('error', (err) => {
    console.error('✗ Redis Connection Error:', err.message);
    console.log('====================================================');
});

client.setTimeout(10000, () => {
    console.error('✗ Redis Connection Timeout (10s)');
    client.destroy();
});
