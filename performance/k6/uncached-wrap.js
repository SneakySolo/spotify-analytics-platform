import http from "k6/http";
import { check, sleep } from "k6";

const baseUrl = __ENV.BASE_URL || "http://127.0.0.1:8080";

export const options = {
  vus: 1,
  iterations: Number(__ENV.ITERATIONS || 10),
  thresholds: {
    http_req_duration: ["p(50)<10000", "p(95)<15000"],
    checks: ["rate==1"],
  },
};

export default function () {
  const response = http.post(`${baseUrl}/wraps`, null, {
    headers: {
      Cookie: __ENV.AUTH_COOKIE,
      "X-XSRF-TOKEN": __ENV.CSRF_TOKEN,
    },
    redirects: 0,
    tags: { scenario: "uncached-wrap" },
  });

  check(response, {
    "wrap generated": (r) =>
      r.status === 200 &&
      (r.headers["Content-Type"] || "").includes("application/json") &&
      r.body.includes('"recentListeningMinutes"'),
  });

  sleep(Number(__ENV.PAUSE_SECONDS || 2));
}
