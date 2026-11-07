import { notFound } from "next/navigation";
import App from "../page";

const validRoutes = new Set([
  "overview",
  "patients",
  "wards",
  "gloves",
  "doctors",
  "monitoring",
  "alerts",
  "notifications",
  "reports",
  "notes",
  "tests",
  "login",
  "admin",
  "doctor",
  "duty",
]);

function safeDecode(segment: string) {
  try {
    return decodeURIComponent(segment);
  } catch {
    return segment;
  }
}

function isValidWorkspacePath(segments: string[]) {
  if (!segments.length) return true;
  const [head, ...rest] = segments.map(safeDecode);
  if (head === "login") return rest.length === 0;
  if (head === "api") return false;
  if (!validRoutes.has(head)) {
    if (head === "robots.txt" || head === "favicon.ico") return false;
    return false;
  }
  if (head === "admin" || head === "doctor" || head === "duty") {
    if (rest.length === 0) return true;
    if (rest.length === 1) {
      return validRoutes.has(rest[0]);
    }
    if (rest.length === 2 && rest[0] === "patients") {
      return Boolean(rest[1]?.trim());
    }
    return false;
  }
  return false;
}

export default async function NestedWorkspaceRoute({
  params,
}: {
  params?: Promise<{ segments?: string[] }> | { segments?: string[] };
}) {
  const resolvedParams = params instanceof Promise ? await params : params;
  const segments = Array.isArray(resolvedParams?.segments) ? resolvedParams.segments : [];
  if (!segments.length || !isValidWorkspacePath(segments)) {
    notFound();
  }
  return <App />;
}
