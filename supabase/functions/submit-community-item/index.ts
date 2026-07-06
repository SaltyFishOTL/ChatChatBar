import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const PACKAGE_BUCKET = "community-packages";
const MAX_PACKAGE_BYTES = 20 * 1024 * 1024;

type ItemType = "character" | "format" | "world_book";

type SubmitRequest = {
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

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return response(null, 204);
  if (req.method !== "POST") return response({ error: "Method not allowed" }, 405);

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

  let body: SubmitRequest;
  try {
    body = await req.json();
  } catch {
    return response({ error: "Invalid JSON" }, 400);
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

  const { data: inserted, error: insertError } = await admin
    .from("community_items")
    .insert({
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
    })
    .select("*")
    .single();

  if (insertError) return response({ error: insertError.message }, 400);
  return response(inserted, 200);
});

function normalizeRequest(body: SubmitRequest): { value: Required<SubmitRequest> } | { error: string } {
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
      "Access-Control-Allow-Methods": "POST, OPTIONS",
    },
  });
}
