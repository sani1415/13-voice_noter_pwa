function sendJson(res, statusCode, body) {
  res.statusCode = statusCode;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.end(JSON.stringify(body));
}

function setCorsHeaders(req, res) {
  const origin = req.headers.origin || '';
  if (origin) {
    res.setHeader('Access-Control-Allow-Origin', origin);
    res.setHeader('Vary', 'Origin');
  }
}

module.exports = async function handler(req, res) {
  setCorsHeaders(req, res);

  if (req.method === 'OPTIONS') {
    res.statusCode = 204;
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
    res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
    res.end('');
    return;
  }

  if (req.method !== 'POST') {
    sendJson(res, 405, { error: 'Method not allowed' });
    return;
  }

  const apiKey = process.env.SONIOX_API_KEY;
  if (!apiKey) {
    sendJson(res, 500, { error: 'Missing SONIOX_API_KEY environment variable' });
    return;
  }

  let sonioxResponse;
  try {
    sonioxResponse = await fetch('https://api.soniox.com/v1/auth/temporary-api-key', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        usage_type: 'transcribe_websocket',
        expires_in_seconds: 3600,
        max_session_duration_seconds: 18000,
      }),
    });
  } catch (err) {
    sendJson(res, 502, { error: 'Soniox-এ সংযোগ ব্যর্থ: ' + (err.message || 'network error') });
    return;
  }

  const data = await sonioxResponse.json().catch(() => ({}));
  if (!sonioxResponse.ok) {
    sendJson(res, sonioxResponse.status, {
      error: data?.message || data?.error || `Soniox request failed: HTTP ${sonioxResponse.status}`,
    });
    return;
  }

  if (!data.api_key) {
    sendJson(res, 502, { error: 'Soniox থেকে temporary API key পাওয়া যায়নি' });
    return;
  }

  sendJson(res, 200, {
    api_key: data.api_key,
    expires_at: data.expires_at || null,
  });
};
