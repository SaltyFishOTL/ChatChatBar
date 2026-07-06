import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const PACKAGE_BUCKET = "community-packages";
const PREVIEW_BUCKET = "community-previews";
const MAX_PACKAGE_BYTES = 20 * 1024 * 1024;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

type ItemType = "character" | "format" | "world_book";

type SubmitRequest = {
  action?: "delete" | null;
  item_id?: string | null;
  type: ItemType;
  title: string;
  description?: string;
  tags?: string[];
  source_local_name?: string;
  file_path: string;
  preview_path?: string | null;
  sha256: string;
  size_bytes: number;
  schema_version: number;
};

type NormalizedSubmitRequest = {
  item_id: string | null;
  type: ItemType;
  title: string;
  description: string;
  tags: string[];
  source_local_name: string;
  file_path: string;
  preview_path: string | null;
  sha256: string;
  size_bytes: number;
  schema_version: number;
};

type CommunityItemRecord = {
  id: string;
  type: ItemType;
  file_path: string;
  preview_path: string | null;
};

type CommunityNameRecord = {
  id: string;
  title: string;
  source_local_name: string;
};

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return response(null, 204);
  if (req.method !== "POST" && req.method !== "DELETE") return response({ error: "Method not allowed" }, 405);

  const authHeader = req.headers.get("Authorization") ?? "";
  const token = authHeader.replace(/^Bearer\s+/i, "").trim();
  if (!token) return response({ error: "Missing bearer token" }, 401);

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!supabaseUrl || !serviceRoleKey) {
    return response({ error: "Function env not configured" }, 500);
  }

  const admin = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false },
  });

  const { data: userData, error: userError } = await admin.auth.getUser(token);
  if (userError || !userData.user) return response({ error: "Unauthorized" }, 401);
  const user = userData.user;

  if (req.method === "DELETE") {
    return deleteCommunityItem(req, admin, user.id);
  }

  let body: SubmitRequest;
  try {
    body = await req.json();
  } catch {
    return response({ error: "Invalid JSON" }, 400);
  }

  if (body.action === "delete") {
    return deleteCommunityItemById(body.item_id, admin, user.id);
  }

  const normalized = normalizeRequest(body);
  if ("error" in normalized) return response({ error: normalized.error }, 400);
  const item = normalized.value;

  const ownerPrefix = `${user.id}/`;
  if (!item.file_path.startsWith(ownerPrefix)) {
    return response({ error: "file_path must be under current user folder" }, 403);
  }
  if (item.preview_path && !item.preview_path.startsWith(ownerPrefix)) {
    return response({ error: "preview_path must be under current user folder" }, 403);
  }

  let existing: CommunityItemRecord | null = null;
  if (item.item_id) {
    const owned = await getOwnedCommunityItem(admin, item.item_id, user.id);
    if ("error" in owned) return response({ error: owned.error }, owned.status);
    existing = owned.item;
    if (existing.type !== item.type) {
      return response({ error: "type cannot change" }, 400);
    }
  }

  const conflict = await findCharacterNameConflict(admin, item, item.item_id);
  if ("error" in conflict) return response({ error: conflict.error }, 400);
  if (conflict.item) {
    await cleanupRejectedObjects(admin, item, existing);
    return response({ error: `Character name already exists: ${conflict.item.title}` }, 409);
  }

  const { data: objectData, error: downloadError } = await admin
    .storage
    .from(PACKAGE_BUCKET)
    .download(item.file_path);
  if (downloadError || !objectData) {
    return response({ error: "Package not found" }, 400);
  }

  const bytes = new Uint8Array(await objectData.arrayBuffer());
  if (bytes.byteLength <= 0 || bytes.byteLength > MAX_PACKAGE_BYTES) {
    return response({ error: "Package size invalid" }, 400);
  }
  if (bytes.byteLength !== item.size_bytes) {
    return response({ error: "size_bytes mismatch" }, 400);
  }

  const sha = await sha256Hex(bytes);
  if (sha !== item.sha256) {
    return response({ error: "sha256 mismatch" }, 400);
  }

  const text = new TextDecoder().decode(bytes);
  try {
    validatePackage(item.type, text, item.schema_version);
  } catch (error) {
    return response({ error: error instanceof Error ? error.message : "Package schema invalid" }, 400);
  }

  const metadata = user.user_metadata ?? {};
  const authorName =
    firstString(metadata.full_name) ??
    firstString(metadata.name) ??
    firstString(metadata.preferred_username) ??
    firstString(metadata.user_name) ??
    firstString(user.email) ??
    "Discord user";

  const row = {
    type: item.type,
    title: item.title,
    description: item.description,
    tags: item.tags,
    author_user_id: user.id,
    author_name: authorName,
    source_local_name: item.source_local_name,
    file_path: item.file_path,
    preview_path: item.preview_path,
    sha256: item.sha256,
    size_bytes: item.size_bytes,
    schema_version: item.schema_version,
  };

  if (existing) {
    const { data: updated, error: updateError } = await admin
      .from("community_items")
      .update(row)
      .eq("id", existing.id)
      .eq("author_user_id", user.id)
      .select("*")
      .single();

    if (updateError) return response({ error: updateError.message }, 400);
    await cleanupOldObjects(admin, existing, item);
    return response(updated, 200);
  }

  const { data: inserted, error: insertError } = await admin
    .from("community_items")
    .insert(row)
    .select("*")
    .single();

  if (insertError) return response({ error: insertError.message }, 400);
  return response(inserted, 200);
});

async function deleteCommunityItem(req: Request, admin: ReturnType<typeof createClient>, userId: string): Promise<Response> {
  const itemId = trim(new URL(req.url).searchParams.get("id"));
  return deleteCommunityItemById(itemId, admin, userId);
}

async function deleteCommunityItemById(
  itemIdRaw: unknown,
  admin: ReturnType<typeof createClient>,
  userId: string,
): Promise<Response> {
  const itemId = trim(itemIdRaw);
  if (!itemId || !UUID_PATTERN.test(itemId)) return response({ error: "Invalid id" }, 400);

  const owned = await getOwnedCommunityItem(admin, itemId, userId);
  if ("error" in owned) return response({ error: owned.error }, owned.status);

  const { data: deleted, error: deleteError } = await admin
    .from("community_items")
    .delete()
    .eq("id", itemId)
    .eq("author_user_id", userId)
    .select("*")
    .single();

  if (deleteError) return response({ error: deleteError.message }, 400);
  await removeObjectRefs(admin, [
    { bucket: PACKAGE_BUCKET, path: owned.item.file_path },
    ...(owned.item.preview_path ? [{ bucket: PREVIEW_BUCKET, path: owned.item.preview_path }] : []),
  ]);
  return response(deleted, 200);
}

async function getOwnedCommunityItem(
  admin: ReturnType<typeof createClient>,
  itemId: string,
  userId: string,
): Promise<{ item: CommunityItemRecord } | { error: string; status: number }> {
  const { data, error } = await admin
    .from("community_items")
    .select("id,type,file_path,preview_path")
    .eq("id", itemId)
    .eq("author_user_id", userId)
    .single();

  if (error || !data) return { error: "Community item not found", status: 404 };
  return { item: data as CommunityItemRecord };
}

async function cleanupOldObjects(
  admin: ReturnType<typeof createClient>,
  previous: CommunityItemRecord,
  next: NormalizedSubmitRequest,
) {
  const refs: Array<{ bucket: string; path: string }> = [];
  if (previous.file_path !== next.file_path) {
    refs.push({ bucket: PACKAGE_BUCKET, path: previous.file_path });
  }
  if (previous.preview_path && previous.preview_path !== next.preview_path) {
    refs.push({ bucket: PREVIEW_BUCKET, path: previous.preview_path });
  }
  await removeObjectRefs(admin, refs);
}

async function cleanupRejectedObjects(
  admin: ReturnType<typeof createClient>,
  rejected: NormalizedSubmitRequest,
  existing: CommunityItemRecord | null,
) {
  const refs: Array<{ bucket: string; path: string }> = [];
  if (!existing || existing.file_path !== rejected.file_path) {
    refs.push({ bucket: PACKAGE_BUCKET, path: rejected.file_path });
  }
  if (rejected.preview_path && (!existing || existing.preview_path !== rejected.preview_path)) {
    refs.push({ bucket: PREVIEW_BUCKET, path: rejected.preview_path });
  }
  await removeObjectRefs(admin, refs);
}

async function findCharacterNameConflict(
  admin: ReturnType<typeof createClient>,
  item: NormalizedSubmitRequest,
  exceptId: string | null,
): Promise<{ item: CommunityNameRecord | null } | { error: string }> {
  if (item.type !== "character") return { item: null };

  const { data, error } = await admin
    .from("community_items")
    .select("id,title,source_local_name")
    .eq("type", "character");

  if (error) return { error: error.message };
  const title = normalizeName(item.title);
  const sourceName = normalizeName(item.source_local_name);
  const duplicate = (data as CommunityNameRecord[] | null)?.find((row) => {
    if (row.id === exceptId) return false;
    const rowTitle = normalizeName(row.title);
    const rowSource = normalizeName(row.source_local_name);
    return rowTitle === title || rowSource === sourceName || rowTitle === sourceName || rowSource === title;
  });
  return { item: duplicate ?? null };
}

async function removeObjectRefs(
  admin: ReturnType<typeof createClient>,
  refs: Array<{ bucket: string; path: string }>,
) {
  const grouped = new Map<string, string[]>();
  for (const ref of refs) {
    const paths = grouped.get(ref.bucket) ?? [];
    paths.push(ref.path);
    grouped.set(ref.bucket, paths);
  }

  for (const [bucket, paths] of grouped) {
    const { error } = await admin.storage.from(bucket).remove(paths);
    if (error) console.warn(`Storage cleanup failed for ${bucket}: ${error.message}`);
  }
}

function normalizeRequest(body: SubmitRequest): { value: NormalizedSubmitRequest } | { error: string } {
  const itemId = body.item_id ? trim(body.item_id) : null;
  if (itemId && !UUID_PATTERN.test(itemId)) return { error: "Invalid item_id" };
  const type = body.type;
  if (!["character", "format", "world_book"].includes(type)) return { error: "Invalid type" };
  const title = trim(body.title);
  if (!title || title.length > 120) return { error: "Invalid title" };
  const description = trim(body.description ?? "").slice(0, 1200);
  const tags = Array.isArray(body.tags)
    ? [...new Set(body.tags.map((tag) => trim(tag)).filter(Boolean))].slice(0, 8)
    : [];
  const sourceLocalName = trim(body.source_local_name ?? title).slice(0, 120);
  const filePath = trim(body.file_path);
  if (!filePath.endsWith(".json")) return { error: "file_path must be .json" };
  const previewPath = body.preview_path ? trim(body.preview_path) : null;
  if (!/^[a-f0-9]{64}$/.test(body.sha256)) return { error: "Invalid sha256" };
  if (!Number.isInteger(body.size_bytes) || body.size_bytes <= 0 || body.size_bytes > MAX_PACKAGE_BYTES) {
    return { error: "Invalid size_bytes" };
  }
  if (!Number.isInteger(body.schema_version) || body.schema_version <= 0) {
    return { error: "Invalid schema_version" };
  }
  return {
    value: {
      item_id: itemId,
      type,
      title,
      description,
      tags,
      source_local_name: sourceLocalName,
      file_path: filePath,
      preview_path: previewPath,
      sha256: body.sha256,
      size_bytes: body.size_bytes,
      schema_version: body.schema_version,
    },
  };
}

function validatePackage(type: ItemType, text: string, schemaVersion: number) {
  const data = JSON.parse(text);
  if (data.schemaVersion !== schemaVersion) throw new Error("schema_version mismatch");

  if (type === "character") {
    if (data.schemaVersion < 3 || data.schemaVersion > 5) throw new Error("Unsupported character schemaVersion");
    const card = data.card;
    if (!card || !firstString(card.name)) throw new Error("Character card name required");
    if (!Array.isArray(card.characters)) throw new Error("characters must be array");
    if (!card.characters.every((character: Record<string, unknown>) => firstString(character.name))) {
      throw new Error("Character name required");
    }
    if (!Array.isArray(data.documents)) throw new Error("documents must be array");
    if (!data.documents.every((doc: Record<string, unknown>) => firstString(doc.fileName) && firstString(doc.fileType))) {
      throw new Error("Document fileName and fileType required");
    }
    const images = data.images && typeof data.images === "object" ? data.images : {};
    const refs = [
      card.avatarResourceId,
      card.chatBackgroundResourceId,
      ...card.characters.map((character: Record<string, unknown>) => character.appearanceImageResourceId),
    ].filter((value) => typeof value === "string" && value.length > 0);
    const missing = refs.filter((ref) => !(ref in images));
    if (missing.length > 0) throw new Error(`Missing image resource: ${missing.join(", ")}`);
    return;
  }

  if (type === "format") {
    if (data.schemaVersion !== 1) throw new Error("Unsupported format schemaVersion");
    if (!firstString(data.name)) throw new Error("Format name required");
    if (!firstString(data.content)) throw new Error("Format content required");
    return;
  }

  if (data.schemaVersion !== 1) throw new Error("Unsupported world book schemaVersion");
  if (!data.book || !firstString(data.book.name)) throw new Error("World book name required");
  if (!Array.isArray(data.book.entries)) throw new Error("World book entries must be array");
}

async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function trim(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function normalizeName(value: unknown): string {
  return trim(value).replace(/\s+/g, " ").toLocaleLowerCase();
}

function firstString(value: unknown): string | null {
  const text = trim(value);
  return text.length > 0 ? text : null;
}

function response(body: unknown, status = 200): Response {
  return new Response(body == null ? null : JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
      "Access-Control-Allow-Methods": "POST, DELETE, OPTIONS",
    },
  });
}
