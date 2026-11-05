export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;
    const cors = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, Authorization"
    };

    const response = (data, status = 200) =>
      new Response(JSON.stringify(data), {
        status,
        headers: { ...cors, "Content-Type": "application/json" }
      });

    if (request.method === "OPTIONS") {
      return new Response(null, { headers: cors });
    }

    const auth = request.headers.get("Authorization") || "";
    const token = auth.replace("Bearer ", "");

    if (path === "/health") {
      return response({ ok: true, service: "PowerampBridge" });
    }

    if (path === "/pair" && request.method === "POST") {
      const body = await request.json();
      if (body.secret !== env.BRIDGE_SECRET) {
        return response({ error: "Unauthorized" }, 401);
      }
      const deviceToken = crypto.randomUUID();
      await env.POWERAMP_COMMANDS.put(
        "device:" + await hash(deviceToken),
        "1",
        { expirationTtl: 2592000 }
      );
      return response({ deviceToken });
    }

    if (path === "/device/poll" && request.method === "POST") {
      if (!await env.POWERAMP_COMMANDS.get("device:" + await hash(token))) {
        return response({ error: "Unauthorized" }, 401);
      }
      const command = await env.POWERAMP_COMMANDS.get("queue");
      if (command) await env.POWERAMP_COMMANDS.delete("queue");
      return response({ command: command || null });
    }

    if (path === "/command" && request.method === "POST") {
      if (auth !== "Bearer " + env.BRIDGE_SECRET) {
        return response({ error: "Unauthorized" }, 401);
      }
      const body = await request.json();
      const allowed = ["PLAY", "PAUSE", "TOGGLE_PLAY_PAUSE", "NEXT", "PREVIOUS"];
      if (!allowed.includes(body.command)) {
        return response({ error: "Invalid command" }, 400);
      }
      await env.POWERAMP_COMMANDS.put("queue", body.command, {
        expirationTtl: 60
      });
      return response({ ok: true });
    }

    return response({ error: "Not found" }, 404);
  }
};

async function hash(value) {
  const bytes = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)]
    .map(x => x.toString(16).padStart(2, "0")).join("");
}
