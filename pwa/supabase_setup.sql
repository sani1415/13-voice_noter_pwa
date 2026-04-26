-- ═══════════════════════════════════════════════════════════════
--  Voice Notes PWA — Supabase Database Setup
--  Prefix: vn_ (existing tables-এর সাথে কোনো conflict নেই)
--  Supabase SQL Editor-এ পুরোটা একসাথে রান করুন
-- ═══════════════════════════════════════════════════════════════


-- ─── ১. ফোল্ডার টেবিল ────────────────────────────────────────
CREATE TABLE IF NOT EXISTS vn_folders (
  id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  name       TEXT        NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE vn_folders IS 'Voice Notes PWA — ফোল্ডার তালিকা';


-- ─── ২. নোট টেবিল ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS vn_notes (
  id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  title             TEXT        NOT NULL DEFAULT 'নতুন নোট',
  content           TEXT        NOT NULL DEFAULT '',
  folder_id         UUID        REFERENCES vn_folders(id) ON DELETE SET NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  title_is_custom   BOOLEAN     NOT NULL DEFAULT FALSE
);

COMMENT ON TABLE vn_notes IS 'Voice Notes PWA — নোট সমূহ';

-- পুরনো DB-তে কলাম না থাকলে যোগ (একবার রান করুন)
ALTER TABLE vn_notes
  ADD COLUMN IF NOT EXISTS title_is_custom BOOLEAN NOT NULL DEFAULT FALSE;


-- ─── ৩. updated_at অটো-আপডেট ট্রিগার ─────────────────────────
CREATE OR REPLACE FUNCTION update_vn_timestamp()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS vn_notes_set_updated_at ON vn_notes;
CREATE TRIGGER vn_notes_set_updated_at
  BEFORE UPDATE ON vn_notes
  FOR EACH ROW
  EXECUTE FUNCTION update_vn_timestamp();


-- ─── ৪. RLS চালু করুন ────────────────────────────────────────
--  (আপনার অন্য tables-এ কোনো হাত নেই)
ALTER TABLE vn_folders ENABLE ROW LEVEL SECURITY;
ALTER TABLE vn_notes   ENABLE ROW LEVEL SECURITY;


-- ─── ৫. RLS Policy — ব্যক্তিগত ব্যবহারের জন্য (anon key) ────
--  এই মুহূর্তে login ছাড়া ব্যবহার হচ্ছে।
--  ভবিষ্যতে auth যোগ করলে এই policy গুলো replace করুন।

-- পুরনো policy থাকলে drop করুন
DROP POLICY IF EXISTS "vn_folders_anon_all" ON vn_folders;
DROP POLICY IF EXISTS "vn_notes_anon_all"   ON vn_notes;

-- নতুন policy
CREATE POLICY "vn_folders_anon_all"
  ON vn_folders
  FOR ALL
  TO anon
  USING (true)
  WITH CHECK (true);

CREATE POLICY "vn_notes_anon_all"
  ON vn_notes
  FOR ALL
  TO anon
  USING (true)
  WITH CHECK (true);


-- ─── ৬. Index — দ্রুত query-র জন্য ─────────────────────────
CREATE INDEX IF NOT EXISTS vn_notes_folder_id_idx  ON vn_notes (folder_id);
CREATE INDEX IF NOT EXISTS vn_notes_updated_at_idx ON vn_notes (updated_at DESC);


-- ─── ৭. যাচাই করুন ──────────────────────────────────────────
--  নিচের query রান করলে দেখবেন tables তৈরি হয়েছে
SELECT table_name, row_security
FROM information_schema.tables t
JOIN pg_class c ON c.relname = t.table_name
WHERE table_name IN ('vn_notes', 'vn_folders')
  AND table_schema = 'public';
