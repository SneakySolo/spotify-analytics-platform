import http from 'k6/http';
import { check } from 'k6';
import { sleep } from 'k6';

export const options = {
  vus: Number(__ENV.VUS || 1),
  iterations: Number(__ENV.ITERATIONS || 20),
  thresholds: {
    http_req_duration: ['p(50)<10000', 'p(95)<15000'],
    checks: ['rate==1'],
  },
};

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const sessionCookie = __ENV.SESSION_COOKIE;
const csrfToken = __ENV.CSRF_TOKEN;

if (!sessionCookie || !csrfToken) {
  throw new Error('Set SESSION_COOKIE and CSRF_TOKEN before running k6.');
}

export default function () {
  const response = http.post(`${baseUrl}/wraps`, null, {
    headers: {
      Cookie: sessionCookie,
      'X-XSRF-TOKEN': csrfToken,
    },
    tags: {
      endpoint: 'uncached-wrap',
    },
  });

  check(response, {
    'status is 200': (r) => r.status === 200,
    'response contains wrap data': (r) => r.body.includes('estimatedListeningTime'),
  });

  if (response.status !== 200) {
    console.log(`wrap request failed: status=${response.status} body=${response.body}`);
  }

  // Avoid hammering Spotify while still measuring request latency separately.
  sleep(Number(__ENV.PAUSE_SECONDS || 1));
}
