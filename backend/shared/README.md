# Shared contracts (reference)

Runtime TypeScript for Edge Functions lives in [`../supabase/functions/_shared/`](../supabase/functions/_shared/) (storage path helpers, auth, Neon client).

Python jobs duplicate **storage key patterns** in `backend/publish/libs/paths.py` — keep in sync with `_shared/paths.ts`.
