const GEMINI_MODEL = process.env.GEMINI_MODEL || 'gemini-2.5-flash';
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
  return origin ? { 'Access-Control-Allow-Origin': origin, Vary: 'Origin' } : {};
}

exports.handler = async event => {
  const corsHeaders = getCorsHeaders(event);

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
    return json(geminiResponse.status, {
      error: data?.error?.message || `Gemini request failed: HTTP ${geminiResponse.status}`,
      hint: geminiResponse.status === 403
        ? 'Check that GEMINI_API_KEY is a valid server-side key with Generative Language API access and no browser-referrer-only restriction.'
        : undefined,
    }, corsHeaders);
  }

  const text = cleanTranscript(data?.candidates?.[0]?.content?.parts?.[0]?.text);
  return json(200, { text }, corsHeaders);
};
