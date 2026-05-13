const DEFAULT_GEMINI_MODEL = process.env.GEMINI_MODEL || 'gemini-2.5-flash';
const ALLOWED_MODELS = [
  'gemini-2.5-flash',
  'gemini-2.5-flash-lite',
  'gemini-2.5-pro',
  'gemini-3.1-flash-lite',
  'gemini-3-flash-preview',
  'gemini-3.1-pro-preview',
];
const TRANSCRIBE_PROMPT = [
  'Transcribe only the clear human speech in the audio.',
  'The expected language is Bangla/Bengali.',
  'Do not translate, summarize, explain, or add any extra text.',
  'Add only natural Bengali punctuation where appropriate.',
  'If there is no clear speech, background noise only, silence, or unintelligible audio, return exactly: __NO_SPEECH__',
  'Return only the transcript text or __NO_SPEECH__.',
].join('\n');

function cleanTranscript(text) {
  const value = (text || '').trim();
  if (!value) return '';

  const normalized = value.replace(/\s+/g, ' ').trim();
  const upper = normalized.toUpperCase();
  if (upper === '__NO_SPEECH__' || upper.includes('NO_SPEECH')) return '';

  const leakedPromptMarkers = [
    'Transcribe only the clear human speech',
    'The expected language is Bangla',
    'Do not translate',
    'Return only the transcript',
    'এই অডিওতে বাংলায় কথা বলা হয়েছে',
    'নির্দেশনা:',
    'হুবহু যা বলা হয়েছে',
    'নিজের পক্ষ থেকে কোনো মন্তব্য',
    'শুধু ট্রান্সক্রিপশনের টেক্সট',
  ];
  if (leakedPromptMarkers.some(marker => normalized.includes(marker))) return '';

  return value;
}

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

async function readJsonBody(req) {
  if (req.body && typeof req.body === 'object') return req.body;
  if (typeof req.body === 'string') return JSON.parse(req.body || '{}');

  let raw = '';
  for await (const chunk of req) raw += chunk;
  return JSON.parse(raw || '{}');
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

  const apiKey = process.env.GEMINI_API_KEY || process.env.GOOGLE_API_KEY;
  if (!apiKey) {
    sendJson(res, 500, { error: 'Missing GEMINI_API_KEY environment variable' });
    return;
  }

  let payload;
  try {
    payload = await readJsonBody(req);
  } catch {
    sendJson(res, 400, { error: 'Invalid JSON body' });
    return;
  }

  const audio = typeof payload.audio === 'string' ? payload.audio : '';
  const mimeType = typeof payload.mimeType === 'string' ? payload.mimeType : 'audio/webm';
  const requestedModel = typeof payload.model === 'string' ? payload.model : '';
  const geminiModel = ALLOWED_MODELS.includes(requestedModel) ? requestedModel : DEFAULT_GEMINI_MODEL;
  if (!audio) {
    sendJson(res, 400, { error: 'Audio is required' });
    return;
  }

  const geminiUrl = `https://generativelanguage.googleapis.com/v1beta/models/${geminiModel}:generateContent?key=${apiKey}`;
  const geminiResponse = await fetch(geminiUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      generationConfig: {
        temperature: 0,
      },
      contents: [{
        parts: [
          { inline_data: { mime_type: mimeType, data: audio } },
          { text: TRANSCRIBE_PROMPT },
        ],
      }],
    }),
  });

  const data = await geminiResponse.json().catch(() => ({}));
  if (!geminiResponse.ok) {
    sendJson(res, geminiResponse.status, {
      error: data?.error?.message || `Gemini request failed: HTTP ${geminiResponse.status}`,
      hint: geminiResponse.status === 403
        ? 'Check that GEMINI_API_KEY is a valid server-side key with Generative Language API access and no browser-referrer-only restriction.'
        : undefined,
    });
    return;
  }

  const text = cleanTranscript(data?.candidates?.[0]?.content?.parts?.[0]?.text);
  sendJson(res, 200, { text });
};
