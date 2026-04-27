const GEMINI_MODEL = process.env.GEMINI_MODEL || 'gemini-3.1-flash-lite-preview';
const TRANSCRIBE_PROMPT = [
  'এই অডিওতে বাংলায় কথা বলা হয়েছে।',
  'নির্দেশনা:',
  '১. হুবহু যা বলা হয়েছে ঠিক তাই বাংলায় লিখবে — একটি শব্দও বাড়াবে না, বাদ দেবে না, পরিবর্তন করবে না।',
  '২. নিজের পক্ষ থেকে কোনো মন্তব্য, ব্যাখ্যা বা অতিরিক্ত কিছু যোগ করবে না।',
  '৩. উপযুক্ত জায়গায় দাড়ি (।), কমা (,), প্রশ্নবোধক চিহ্ন (?), বিস্ময়বোধক চিহ্ন (!) যোগ করবে।',
  '৪. শুধু ট্রান্সক্রিপশনের টেক্সটটুকু দাও, আর কিছু লিখো না।',
].join('\n');

function json(statusCode, body, headers = {}) {
  return {
    statusCode,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      ...headers,
    },
    body: JSON.stringify(body),
  };
}

function getCorsHeaders(event) {
  const origin = event.headers.origin || event.headers.Origin || '';
  const host = event.headers.host || event.headers.Host || '';
  if (!origin || !host) return {};

  try {
    const originUrl = new URL(origin);
    if (originUrl.host !== host) return null;
    return { 'Access-Control-Allow-Origin': origin };
  } catch {
    return null;
  }
}

exports.handler = async event => {
  const corsHeaders = getCorsHeaders(event);
  if (corsHeaders === null) {
    return json(403, { error: 'Forbidden origin' });
  }

  if (event.httpMethod === 'OPTIONS') {
    return {
      statusCode: 204,
      headers: {
        ...corsHeaders,
        'Access-Control-Allow-Headers': 'Content-Type',
        'Access-Control-Allow-Methods': 'POST, OPTIONS',
      },
      body: '',
    };
  }

  if (event.httpMethod !== 'POST') {
    return json(405, { error: 'Method not allowed' }, corsHeaders);
  }

  const apiKey = process.env.GEMINI_API_KEY || process.env.GOOGLE_API_KEY;
  if (!apiKey) {
    return json(500, { error: 'Missing GEMINI_API_KEY environment variable' }, corsHeaders);
  }

  let payload;
  try {
    payload = JSON.parse(event.body || '{}');
  } catch {
    return json(400, { error: 'Invalid JSON body' }, corsHeaders);
  }

  const audio = typeof payload.audio === 'string' ? payload.audio : '';
  const mimeType = typeof payload.mimeType === 'string' ? payload.mimeType : 'audio/webm';
  if (!audio) {
    return json(400, { error: 'Audio is required' }, corsHeaders);
  }

  const geminiUrl = `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent?key=${apiKey}`;
  const geminiResponse = await fetch(geminiUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
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
    return json(geminiResponse.status, {
      error: data?.error?.message || `Gemini request failed: HTTP ${geminiResponse.status}`,
    }, corsHeaders);
  }

  const text = data?.candidates?.[0]?.content?.parts?.[0]?.text?.trim() || '';
  return json(200, { text }, corsHeaders);
};
