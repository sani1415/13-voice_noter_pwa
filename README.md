# Voice Notes PWA

বাংলা ভয়েস নোট নেওয়া, সংরক্ষণ, ফোল্ডারে সাজানো এবং AI দিয়ে অডিও ট্রান্সক্রিপশন করার জন্য একটি mobile-first Progressive Web App.

## সংক্ষিপ্ত বিবরণ

এই অ্যাপটি মূলত বাংলা ভয়েস নোট ব্যবহারের জন্য তৈরি। ব্যবহারকারী PIN দিয়ে লগইন করে নোট তৈরি করতে পারে, ফোল্ডারে সাজাতে পারে, মাইক্রোফোন দিয়ে কথা বলে অডিও রেকর্ড করতে পারে এবং Gemini AI দিয়ে সেই অডিওকে বাংলা লেখায় রূপান্তর করতে পারে।

অ্যাপটি static frontend হিসেবে চলে, কিন্তু transcription API key নিরাপদ রাখার জন্য Gemini API call সরাসরি browser থেকে করা হয় না। এর বদলে Netlify Function ব্যবহার করা হয়।

## প্রধান ফিচার

- বাংলা voice note recording এবং AI transcription
- নোট তৈরি, edit, delete, pin, tag এবং label color
- ফোল্ডার তৈরি ও নোট ফোল্ডারে রাখা
- Custom username + PIN login system
- Supabase cloud sync
- Mobile-first PWA layout
- Offline-friendly service worker cache
- Modern warm notebook style UI
- JSON backup export/import

## প্রযুক্তি

- Frontend: plain HTML, CSS, JavaScript
- PWA: `manifest.json` এবং `sw.js`
- Backend function: Netlify Functions
- AI transcription: Google Gemini Generative Language API
- Database/sync: Supabase
- Deployment: Netlify

## গুরুত্বপূর্ণ ফাইল

- `index.html` - অ্যাপের HTML structure
- `style.css` - সম্পূর্ণ UI styling এবং responsive mobile design
- `app.js` - app state, login, notes, recording, Supabase sync, UI behavior
- `sw.js` - service worker cache এবং app update behavior
- `manifest.json` - PWA metadata
- `netlify/functions/transcribe.js` - server-side Gemini transcription endpoint
- `netlify.toml` - Netlify publish এবং functions config
- `supabase_setup.sql` - basic Supabase notes/folders setup SQL
- `supabase/migrations/` - custom app users, PIN login, session, RPC এবং secured data access migrations

## অ্যাপ কীভাবে কাজ করে

1. ব্যবহারকারী username এবং PIN দিয়ে লগইন করে।
2. Login session localStorage-এ session token হিসেবে রাখা হয়।
3. Supabase RPC ব্যবহার করে user session resolve, notes pull এবং notes/folders sync হয়।
4. রেকর্ডিং browser `MediaRecorder` দিয়ে নেওয়া হয়।
5. Audio base64 হিসেবে `/.netlify/functions/transcribe` endpoint-এ পাঠানো হয়।
6. Netlify Function server-side থেকে Gemini API call করে transcription text ফেরত দেয়।
7. Transcribed text note editor-এ insert হয় এবং setting অনুযায়ী auto-save হয়।

## Deployment

এই project Netlify-তে deploy করার জন্য তৈরি।

`netlify.toml` অনুযায়ী:

```toml
[build]
  publish = "."
  functions = "netlify/functions"
```

Static files root folder থেকেই serve হয়, আর functions থাকে `netlify/functions` folder-এ।

Production URL:

```text
https://afkaar.netlify.app
```

Netlify Drop দিয়ে শুধু static files upload করলে transcription function কাজ করবে না। Functions সহ deploy করার জন্য Netlify Git deploy বা Netlify CLI ব্যবহার করা উচিত।

## Environment Variables

Netlify dashboard বা local `.env`-এ নিচের variable দরকার:

```env
GEMINI_API_KEY=your_server_side_gemini_api_key
```

Optional:

```env
GEMINI_MODEL=gemini-2.5-flash
```

`GEMINI_MODEL` না দিলে default model হিসেবে `gemini-2.5-flash` ব্যবহার হয়।

Security note: Gemini API key frontend code-এ hardcode করা যাবে না। এই app এখন key শুধু Netlify Function-এর server-side environment থেকে নেয়।

## Local Development

সাধারণ `file://` বা VS Code Live Server দিয়ে app খুললে PWA manifest, service worker এবং Netlify Function ঠিকমতো কাজ করবে না।

Netlify Functions সহ local test করতে:

```powershell
npm install -g netlify-cli
netlify dev
```

তারপর browser-এ খুলুন:

```text
http://localhost:8888
```

যদি `127.0.0.1:5500` বা `file://` দিয়ে চালানো হয়, transcription endpoint ভুল হবে এবং `/.netlify/functions/transcribe` কাজ করবে না।

## Supabase

অ্যাপটি Supabase ব্যবহার করে notes, folders, users এবং session sync করে। Frontend-এ Supabase anon key আছে, যা browser client ব্যবহারের জন্য public হতে পারে। তবে data access নিরাপদ রাখতে RLS এবং RPC-based access model গুরুত্বপূর্ণ।

বর্তমান app custom PIN login ব্যবহার করে; Supabase Auth নয়। `vn_pin_login`, `vn_pin_resolve_session`, `vn_pull_notes_folders`, `vn_upsert_note_session` ইত্যাদি RPC দিয়ে data access হয়।

## নিরাপত্তা নোট

- Gemini API key কখনো `app.js` বা frontend-এ রাখা যাবে না।
- Netlify Environment Variable-এ `GEMINI_API_KEY` রাখতে হবে।
- Google API key browser referrer-only restricted হলে Netlify Function থেকে 403 দিতে পারে।
- Supabase service role key frontend-এ রাখা যাবে না।
- Supabase tables exposed হলে RLS enabled থাকা জরুরি।

## সাধারণ সমস্যা

### `Failed to register a ServiceWorker`

App `file://` দিয়ে খোলা হলে service worker কাজ করে না। `http://localhost:8888` বা deployed URL ব্যবহার করুন।

### `POST /.netlify/functions/transcribe 405`

App static server দিয়ে চলছে, Netlify Functions চলছে না। `netlify dev` ব্যবহার করুন।

### `POST /.netlify/functions/transcribe 403`

সম্ভবত Gemini API key invalid, restricted, বা Generative Language API access নেই। server-side compatible key ব্যবহার করুন।

### `Missing GEMINI_API_KEY`

Netlify environment variable সেট করা হয়নি।

## বর্তমান অবস্থা

অ্যাপটি mobile-first PWA হিসেবে তৈরি এবং Netlify deployment-এর জন্য configured. Transcription flow server-side Netlify Function দিয়ে secure করা হয়েছে, Supabase cloud sync যুক্ত আছে, এবং UI modern warm notebook style-এ redesign করা হয়েছে।
