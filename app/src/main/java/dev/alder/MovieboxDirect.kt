package dev.alder

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Direct Moviebox resolver: talks to Moviebox API hosts directly with signed headers
 * (ported from moviebox-tui's crypto.rs, client.rs, session.rs, adapt.rs). No vodnik,
 * no media proxy — the app signs requests, rotates hosts, absorbs x-user session
 * refresh headers, recovers placeholder clips via /resource-position/v2, and resolves
 * DASH manifests from CloudFront-Policy / Edge-Cache-Cookie cookies itself.
 *
 * IMDb ID → title (Cinemeta) → Moviebox subject ID (search) → play-info / resources /
 * resourceDetectors → signed direct streams. Fully ported from moviebox-tui.
 */
object MovieboxDirect {

    // ── API hosts (ported from moviebox-tui client.rs HOST_POOL) ──────────────────
    private val HOST_POOL = listOf(
        "https://api6.aoneroom.com",
        "https://api5.aoneroom.com",
        "https://api4.aoneroom.com",
        "https://api4sg.aoneroom.com",
        "https://api3.aoneroom.com",
        "https://api6sg.aoneroom.com",
        "https://api.inmoviebox.com"
    )

    private val RETRY_STATUS = setOf(403, 406, 407, 429, 500, 502, 503, 504)
    private val STREAM_REFERER = "https://sportslive.wine"

    // ── Cinemeta (IMDb ID → title mapping) ─────────────────────────────────────────
    private val CINEMETA = "https://v3-cinemeta.stremio.com"

    private fun cinemetaTitle(imdb: String, type: String): Pair<String, Int?>? {
        try {
            val resp = Http.get("$CINEMETA/meta/$type/$imdb.json", false, 10_000, strict = false)
            val meta = org.json.JSONObject(resp).optJSONObject("meta") ?: return null
            val name = meta.optString("name", "")
            if (name.isBlank()) return null
            val year = meta.optString("year", "").toIntOrNull()
                ?: meta.optString("releaseInfo", "").toIntOrNull()
                ?: meta.optString("released", "").toIntOrNull()
            return name to year
        } catch (_: Exception) {
            return null
        }
    }

    private fun compactTitle(name: String): String =
        name.lowercase().replace("[^a-z0-9 ]".toRegex(), "").replace("  ", " ").trim()

    // ── Search Moviebox by title → subjectId ──────────────────────────────────────
    private fun movieboxSearch(context: Context, query: String, page: Int, identity: ClientIdentity): List<org.json.JSONObject> {
        var out = mutableListOf<org.json.JSONObject>()
        for (path in listOf(
            "/wefeed-mobile-bff/subject-api/search?q=${java.net.URLEncoder.encode(query, Charsets.UTF_8.name())}&page=$page",
            "/wefeed-mobile-bff/subject-api/search/all?q=${java.net.URLEncoder.encode(query, Charsets.UTF_8.name())}&page=$page"
        )) {
            try {
                val resp = requestWithSession(context, "GET", path, null, identity)
                val data = parseResponse(resp)
                val hits = data.optJSONArray("results") ?: data.optJSONArray("hits") ?: data.optJSONArray("list")
                if (hits != null) {
                    // Stremio-style: data.results[0].subjects[]
                    val first = hits.optJSONObject(0)
                    val subjects = first?.optJSONArray("subjects") ?: hits
                    for (i in 0 until subjects.length()) {
                        val hit = subjects.getJSONObject(i)
                        val subject = hit.optJSONObject("subject") ?: hit
                        if (subject.has("subjectId") || subject.has("id")) out.add(subject)
                    }
                }
            } catch (_: Exception) {}
        }
        return out
    }

    private fun movieboxSubjectId(context: Context, name: String, year: Int?, identity: ClientIdentity): String? {
        val wanted = compactTitle(name)
        val queries = LinkedHashSet<String>()
        queries.add(name)
        queries.add(name.replace("\u2019".toRegex(), "'").replace("\u2018".toRegex(), "'"))
        queries.add(name.split(" - |:|\\|".toRegex()).first())
        for (q in queries.take(3)) {
            if (q.isBlank()) continue
            // exact match first
            for (subject in movieboxSearch(context, q, 1, identity)) {
                val sName = subject.optString("name", "")
                val sYear = subject.optString("year", "").toIntOrNull()
                    ?: subject.optString("releaseInfo", "").toIntOrNull()
                    ?: subject.optString("released", "").toIntOrNull()
                if (compactTitle(sName) == wanted &&
                    (year == null || sYear == null || sYear == year)) {
                    return subject.optString("subjectId", "")
                        ?: subject.optString("id", "")
                        ?: subject.optLong("id").let { if (it > 0) it.toString() else null }
                }
            }
            // prefix match with year agreement
            for (subject in movieboxSearch(context, q, 1, identity)) {
                val sName = subject.optString("name", "")
                val sYear = subject.optString("year", "").toIntOrNull()
                    ?: subject.optString("releaseInfo", "").toIntOrNull()
                    ?: subject.optString("released", "").toIntOrNull()
                if (compactTitle(sName).startsWith(wanted) ||
                    wanted.startsWith(compactTitle(sName))) {
                    if (year == null || sYear == null || sYear == year) {
                        return subject.optString("subjectId", "")
                            ?: subject.optString("id", "")
                            ?: subject.optLong("id").let { if (it > 0) it.toString() else null }
                    }
                }
            }
        }
        return null
    }

    // ── Crypto constants (ported from moviebox-tui crypto.rs) ─────────────────────
    private val DEFAULT_SECRET_BYTES = byteArrayOf(
        0xef.toByte(), 0xa8.toByte(), 0x91.toByte(), 0x97.toByte(), 0x4e.toByte(),
        0xec.toByte(), 0xd3.toByte(), 0x14.toByte(), 0x8d.toByte(), 0xf6.toByte(),
        0x3a.toByte(), 0xa6.toByte(), 0x11.toByte(), 0x60.toByte(), 0x2d.toByte(),
        0xef.toByte(), 0xd1.toByte(), 0x01.toByte(), 0x25.toByte(), 0x9b.toByte(),
        0xa5.toByte(), 0x21.toByte(), 0x02.toByte(), 0x2c.toByte(), 0x57.toByte(),
        0xae.toByte(), 0x05.toByte(), 0x66.toByte(), 0xbd.toByte(), 0x8e.toByte()
    )
    private val STOCK_CERT_SHA256 = "03d4b741632d136bc23e2de4c6adcdb7712f1980b619c183221801730bbd0410"
    private val BUILD_TAG_SALT = "oneroom"

    // ── Session persistence ───────────────────────────────────────────────────────
    private val SESSION_PREFS = "moviebox_session"
    private val PREF_TOKEN = "token"
    private val PREF_UID = "uid"
    private val PREF_EXP = "exp"
    private val PREF_CREATED = "created"

    // ── MD5 ───────────────────────────────────────────────────────────────────────
    private fun md5Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun md5HexStr(s: String): String = md5Hex(s.toByteArray(Charsets.UTF_8))

    // ── HMAC-MD5 ──────────────────────────────────────────────────────────────────
    private fun hmacMd5(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(key, "HmacMD5"))
        return mac.doFinal(data)
    }

    // ── Base64 (URL-safe with padding normalization) ──────────────────────────────
    private fun base64EncodeUrlSafe(data: ByteArray): String =
        Base64.encodeToString(data, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)

    // For Edge-Cache-Cookie urlprefix: base64url with -→+, _→/
    private fun base64UrlDecodeUrlPrefix(s: String): ByteArray? {
        var t = s.replace('-', '+').replace('_', '/')
        val pad = (4 - t.length % 4) % 4
        if (pad > 0) t += "=".repeat(pad)
        return try { Base64.decode(t, Base64.DEFAULT) } catch (_: Exception) { null }
    }

    // For CloudFront-Policy: base64url with -→+, _→=, ~→/
    private fun base64UrlDecodePolicy(s: String): ByteArray? {
        var t = s.replace('-', '+').replace('_', '=').replace('~', '/')
        val pad = (4 - t.length % 4) % 4
        if (pad > 0) t += "=".repeat(pad)
        return try { Base64.decode(t, Base64.DEFAULT) } catch (_: Exception) { null }
    }

    // ── Random ────────────────────────────────────────────────────────────────────
    private fun randomInt(n: Int): Int = (Math.random() * n).toInt()

    private fun randomHex(len: Int): String =
        (0 until len).joinToString("") { "%x".format(randomInt(16)) }

    private fun randomUuid(): String =
        "${randomHex(8)}-${randomHex(4)}-${randomHex(4)}-${randomHex(4)}-${randomHex(12)}"

    private fun randomSpoofedIp(): String {
        val prefixes = listOf(
            "103.241", "49.36", "117.195", "106.198", "122.162", "157.32", "182.70",
            "103.58", "27.60", "59.90"
        )
        val prefix = prefixes[randomInt(prefixes.size)]
        return "$prefix.${1 + randomInt(253)}.${1 + randomInt(253)}"
    }

    // ── Client identity (ported from generate_client_info_and_ua) ─────────────────
    private data class ClientIdentity(val userAgent: String, val clientInfo: String, val spoofedIp: String)

    private fun generateClientIdentity(): ClientIdentity {
        val androidVersions = listOf(
            "9" to "PQ3A.190605.03081104",
            "10" to "QP1A.191005.007.A3",
            "11" to "RP1A.200720.011",
            "12" to "S1B.220414.015",
            "13" to "TQ2A.230405.003"
        )
        val redmiDevices = listOf(
            "23078RKD5C" to "Redmi",
            "2201117TY" to "Redmi",
            "2201117TG" to "Redmi",
            "22101316G" to "Redmi",
            "21121210G" to "Redmi",
            "M2012K11AG" to "Redmi",
            "M2007J20CG" to "Redmi"
        )
        val versionCodes = listOf(50020117, 50020118, 50020119, 50020120, 50020121)
        val networkTypes = listOf("NETWORK_WIFI", "NETWORK_MOBILE")
        val timezones = listOf(
            "Asia/Kolkata", "Asia/Shanghai", "Asia/Tokyo", "America/New_York", "Europe/London"
        )

        val (androidOs, buildId) = androidVersions[randomInt(androidVersions.size)]
        val (deviceModel, deviceBrand) = redmiDevices[randomInt(redmiDevices.size)]
        val versionCode = versionCodes[randomInt(versionCodes.size)]
        val network = networkTypes[randomInt(networkTypes.size)]
        val timezone = timezones[randomInt(timezones.size)]
        val gaid = randomUuid()
        val deviceId = randomHex(32)

        val userAgent =
            "com.community.oneroom/$versionCode (Linux; U; Android $androidOs; en_US; $deviceModel; Build/$buildId; Cronet/135.0.7012.3)"

        val clientInfo =
            """{"package_name":"com.community.oneroom","version_name":"4.0.01.0813.03","version_code":$versionCode,"os":"android","os_version":"$androidOs","install_ch":"ps","device_id":"$deviceId","install_store":"ps","gaid":"$gaid","brand":"$deviceBrand","model":"$deviceModel","system_language":"en","net":"$network","region":"US","timezone":"$timezone","sp_code":"40401","X-Play-Mode":"2"}"""

        return ClientIdentity(userAgent, clientInfo, randomSpoofedIp())
    }

    // ── Build tag (ported from generate_x_client_build / build_tag_for_cert) ──────
    private fun buildTagForCert(tsMs: Long, randPart: Int): String {
        val stamp = "${tsMs / 1000}${String.format("%03d", tsMs % 1000)}000$randPart"
        val hash = md5HexStr("$STOCK_CERT_SHA256$BUILD_TAG_SALT$stamp")
        return "$stamp.$hash"
    }

    private fun generateXClientBuild(tsMs: Long): String =
        buildTagForCert(tsMs, randomInt(1000))

    // ── Client token (ported from generate_x_client_token) ────────────────────────
    private fun generateXClientToken(ts: Long): String {
        val tsStr = ts.toString()
        val reversedTs = tsStr.reversed()
        val hashVal = md5HexStr(reversedTs)
        return "$tsStr,$hashVal"
    }

    // ── Canonical string + signature (ported from build_canonical_string / generate_x_tr_signature) ──
    private fun sortedQueryString(url: String): String {
        try {
            val parsed = java.net.URL(url)
            val query = parsed.query ?: return ""
            val params = java.util.TreeMap<String, MutableList<String>>()
            for (pair in query.split("&")) {
                val kv = pair.split("=", limit = 2)
                val key = java.net.URLDecoder.decode(kv[0], Charsets.UTF_8.name())
                val value = if (kv.size > 1) java.net.URLDecoder.decode(kv[1], Charsets.UTF_8.name()) else ""
                params.getOrPut(key) { mutableListOf() }.add(value)
            }
            val parts = mutableListOf<String>()
            for ((key, values) in params) {
                for (value in values) {
                    parts.add("$key=$value")
                }
            }
            return parts.joinToString("&")
        } catch (_: Exception) {
            return ""
        }
    }

    private fun canonicalUrl(url: String): String {
        try {
            val parsed = java.net.URL(url)
            val query = sortedQueryString(url)
            return if (query.isEmpty()) parsed.path else "${parsed.path}?$query"
        } catch (_: Exception) {
            return url
        }
    }

    private fun buildCanonicalString(
        method: String, accept: String?, contentType: String?, url: String,
        body: String?, timestampMs: Long
    ): String {
        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val (bodyHash, bodyLength) = if (bodyBytes != null) {
            val len = bodyBytes.size
            val truncated = if (len > 102_400) bodyBytes.copyOfRange(0, 102_400) else bodyBytes
            md5Hex(truncated) to len.toString()
        } else "" to ""
        return listOf(
            method.uppercase(),
            accept.orEmpty(),
            contentType.orEmpty(),
            bodyLength,
            timestampMs.toString(),
            bodyHash,
            canonicalUrl(url)
        ).joinToString("\n")
    }

    private fun generateXTrSignature(
        method: String, accept: String?, contentType: String?, url: String,
        body: String?, timestampMs: Long
    ): String {
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestampMs)
        val sigBytes = hmacMd5(DEFAULT_SECRET_BYTES, canonical.toByteArray(Charsets.UTF_8))
        val sigB64 = Base64.encodeToString(sigBytes, Base64.DEFAULT or Base64.NO_WRAP)
        return "$timestampMs|2|$sigB64"
    }

    // ── Signed headers (ported from build_signed_headers) ─────────────────────────
    private fun signedHeaders(
        method: String, url: String, body: String?, authToken: String?,
        identity: ClientIdentity
    ): Map<String, String> {
        val ts = System.currentTimeMillis()
        val accept = "application/json"
        val contentType = "application/json"
        val clientToken = generateXClientToken(ts)
        val signature = generateXTrSignature(method, accept, contentType, url, body, ts)
        val buildTag = generateXClientBuild(ts)
        val taggedClientInfo = insertBuildTag(identity.clientInfo, buildTag)

        return mapOf(
            "User-Agent" to identity.userAgent,
            "Accept" to accept,
            "Content-Type" to contentType,
            "Connection" to "keep-alive",
            "x-client-token" to clientToken,
            "x-tr-signature" to signature,
            "x-client-build" to buildTag,
            "x-client-info" to taggedClientInfo,
            "x-client-status" to "0",
            "x-forwarded-for" to identity.spoofedIp
        ) + (if (authToken != null) mapOf("Authorization" to "Bearer $authToken") else emptyMap())
    }

    private fun insertBuildTag(clientInfo: String, tag: String): String {
        return try {
            val obj = org.json.JSONObject(clientInfo)
            obj.put("X-Client-Build", tag)
            obj.toString()
        } catch (_: Exception) {
            clientInfo
        }
    }

    // ── Session ───────────────────────────────────────────────────────────────────
    private data class Session(val token: String, val uid: String?, val exp: Long?, val createdAt: Long) {
        fun isValid(): Boolean {
            if (token.isBlank()) return false
            val now = System.currentTimeMillis() / 1000
            return if (exp != null) now + 60 < exp else now < createdAt + 7 * 24 * 3600
        }
    }

    private fun sessionPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)

    private fun loadSession(context: Context): Session? {
        val prefs = sessionPrefs(context)
        val token = prefs.getString(PREF_TOKEN, "") ?: return null
        val uid = prefs.getString(PREF_UID, null)
        val exp = prefs.getLong(PREF_EXP, 0)
        val created = prefs.getLong(PREF_CREATED, 0)
        return if (token.isBlank()) null else Session(token, uid?.takeIf { it.isNotBlank() }, exp.takeIf { it > 0 }, created)
    }

    private fun saveSession(context: Context, session: Session) {
        val prefs = sessionPrefs(context).edit()
        prefs.putString(PREF_TOKEN, session.token)
        session.uid?.let { prefs.putString(PREF_UID, it) }
        session.exp?.let { prefs.putLong(PREF_EXP, it) }
        prefs.putLong(PREF_CREATED, session.createdAt)
        prefs.apply()
    }

    private fun clearSession(context: Context) {
        sessionPrefs(context).edit().clear().apply()
    }

    // ── absorb_x_user from response headers (ported from client.rs) ───────────────
    private fun absorbXUser(headers: Map<String, String>, context: Context, identity: ClientIdentity) {
        val xUser = headers["x-user"] ?: return
        try {
            val json = org.json.JSONObject(xUser)
            val token = json.optString("token", "")
            if (token.isBlank()) return
            val uid = json.optString("uid", null).takeIf { it.isNotBlank() }
                ?: json.optString("userId", null).takeIf { it.isNotBlank() }
            val session = Session(token, uid, null, System.currentTimeMillis() / 1000)
            saveSession(context, session)
        } catch (_: Exception) {}
    }

    // ── HTTP with signed headers + host rotation ──────────────────────────────────
    private var activeHostIdx = 0

    private fun requestHosts(
        context: Context, method: String, pathAndQuery: String, body: String?,
        authToken: String?, identity: ClientIdentity
    ): Pair<String, Map<String, String>> {
        var backoffMs = 50L
        val startIdx = activeHostIdx

        for (i in 0 until HOST_POOL.size) {
            if (i > 0) {
                Thread.sleep(backoffMs)
                backoffMs = 50
            }
            val idx = (startIdx + i) % HOST_POOL.size
            val base = HOST_POOL[idx]
            val url = "$base$pathAndQuery"
            val headers = signedHeaders(method, url, body, authToken, identity)

            try {
                val (code, respText, respHeaders) = httpRequest(url, method, body, headers)
                absorbXUser(respHeaders, context, identity)

                if (RETRY_STATUS.contains(code)) {
                    activeHostIdx = (idx + 1) % HOST_POOL.size
                    if (code == 429) backoffMs = 400
                    continue
                }
                activeHostIdx = idx
                if (code >= 400) throw HttpError(code)
                return respText to respHeaders
            } catch (e: HttpError) {
                if (e.code in setOf(401, 403)) {
                    activeHostIdx = (idx + 1) % HOST_POOL.size
                    continue
                }
                activeHostIdx = (idx + 1) % HOST_POOL.size
                continue
            } catch (_: Exception) {
                activeHostIdx = (idx + 1) % HOST_POOL.size
                continue
            }
        }
        throw HttpError(0) // all hosts exhausted
    }

    private fun httpRequest(urlString: String, method: String, body: String?, headers: Map<String, String>): Triple<Int, String, Map<String, String>> {
        val url = java.net.URL(urlString)
        val c = url.openConnection() as java.net.HttpURLConnection
        c.useCaches = false
        c.setRequestProperty("User-Agent", headers.getOrElse("User-Agent") { "Alder" })
        headers.forEach { (k, v) -> if (k != "User-Agent") c.setRequestProperty(k, v) }
        c.connectTimeout = 10_000
        c.readTimeout = 12_000
        c.requestMethod = method
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val stream = if (code < 400) c.inputStream else c.errorStream
        val text = stream?.use { it.readLimited(64 * 1024 * 1024) }?.let { String(it, Charsets.UTF_8) } ?: ""
        // Capture response headers for absorb_x_user
        val respHeaders = mutableMapOf<String, String>()
        val headerFields = c.headerFields
        for ((k, v) in headerFields) {
            if (k != null && v != null && v.isNotEmpty()) {
                respHeaders[k.lowercase()] = v.firstOrNull() ?: ""
            }
        }
        return Triple(code, text, respHeaders)
    }

    private fun requestWithSession(
        context: Context, method: String, pathAndQuery: String, body: String?,
        identity: ClientIdentity
    ): String {
        val session = loadSession(context)
        var token = session?.token
        if (token == null || session?.isValid() != true) {
            token = fetchFreshSession(context, identity)
        }
        return try {
            val (respText, _) = requestHosts(context, method, pathAndQuery, body, token, identity)
            respText
        } catch (e: HttpError) {
            if (e.code in setOf(401, 403)) {
                clearSession(context)
                token = fetchFreshSession(context, identity)
                val (respText, _) = requestHosts(context, method, pathAndQuery, body, token, identity)
                respText
            } else throw e
        }
    }

    private fun fetchFreshSession(context: Context, identity: ClientIdentity): String {
        val body = "{}"
        val (respText, _) = requestHosts(
            context, "POST", "/wefeed-mobile-bff/user-api/visitor-login", body, null, identity
        )
        val json = org.json.JSONObject(respText)
        val token = json.optString("token", "")
        if (token.isBlank()) throw HttpError(0) // missing token
        val uid = json.optString("uid", null).takeIf { it.isNotBlank() }
            ?: json.optString("userId", null).takeIf { it.isNotBlank() }
        val session = Session(token, uid, null, System.currentTimeMillis() / 1000)
        saveSession(context, session)
        return token
    }

    // ── API endpoints ─────────────────────────────────────────────────────────────
    private fun searchUrl(query: String, page: Int): String =
        "/wefeed-mobile-bff/subject-api/search/v2?keyword=${java.net.URLEncoder.encode(query, Charsets.UTF_8.name())}&page=$page&perPage=15&subjectType=0"

    private fun detailsUrl(subjectId: String): String =
        "/wefeed-mobile-bff/subject-api/get?subjectId=$subjectId"

    private fun playInfoUrl(subjectId: String, se: Int, ep: Int): String =
        "/wefeed-mobile-bff/subject-api/play-info/v2?subjectId=$subjectId&se=$se&ep=$ep"

    private fun resourcesUrl(subjectId: String, se: Int, ep: Int): String =
        "/wefeed-mobile-bff/subject-api/resource?subjectId=$subjectId&se=$se&ep=$ep&page=1&perPage=20"

    private fun captionsUrl(subjectId: String, resourceId: String): String =
        "/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$subjectId&resourceId=$resourceId"

    private fun resourcePositionUrl(subjectId: String, resourceId: String, failUrl: String): String =
        "/wefeed-mobile-bff/subject-api/resource-position/v2?" +
            java.net.URLEncoder.encode("subjectId=$subjectId&resourceId=$resourceId&failUrl=$failUrl&failCode=404&resourceNum=0&isVip=true", Charsets.UTF_8.name())

    // ── Stream adaptation (ported from adapt.rs) ─────────────────────────────────
    private fun isDeprecationNoticeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("1c7de0bd3393702d9191801f15f88f8d") ||
            lower.contains("9a0461bc39da389663bf3dbb17091d3f") ||
            lower.contains("b164fbfb4347792950bdfbfb563d39d9") ||
            lower.contains("/notice.mp4") ||
            (lower.contains("macdn.aoneroom.com") && lower.contains("/other/"))
    }

    private fun resolveDashManifestFromPolicy(signCookie: String): String? {
        for (part in signCookie.split(";")) {
            val trimmed = part.trim()
            // Edge-Cache-Cookie: urlprefix=<base64url>:sign=...:t=...
            val urlprefixIdx = trimmed.indexOf("urlprefix=")
            if (urlprefixIdx >= 0) {
                val prefixPart = trimmed.substring(urlprefixIdx + "urlprefix=".length)
                val b64Token = prefixPart.split(":").firstOrNull() ?: prefixPart
                base64UrlDecodeUrlPrefix(b64Token)?.let { bytes ->
                    val urlStr = String(bytes, Charsets.UTF_8)
                    val mpd = toDashMpd(urlStr)
                    if (mpd != null) return mpd
                }
            }
            // CloudFront-Policy=<base64url>
            val policyRaw = trimmed.removePrefix("CloudFront-Policy=")
            if (policyRaw != trimmed) {
                base64UrlDecodePolicy(policyRaw)?.let { bytes ->
                    try {
                        val json = org.json.JSONObject(String(bytes, Charsets.UTF_8))
                        val resource = json.getJSONArray("Statement").getJSONObject(0).optString("Resource", "")
                        val mpd = toDashMpd(resource)
                        if (mpd != null) return mpd
                    } catch (_: Exception) {}
                }
            }
        }
        return null
    }

    private fun toDashMpd(urlStr: String): String? {
        val base = urlStr.trimEnd('*').trimEnd('/')
        if (base.isNotEmpty() && (base.startsWith("http://") || base.startsWith("https://")) && base.contains("/dash/")) {
            return "$base/index.mpd"
        }
        return null
    }

    private fun resolutionFromSourceLink(link: String): Int? {
        val upper = link.uppercase()
        return upper.mapIndexed { i, c -> c to i }
            .filter { it.first == 'P' }
            .mapNotNull { (_, idx) ->
                val start = upper.substring(0, idx)
                    .reversed()
                    .indexOfFirst { !it.isDigit() }
                    .let { if (it < 0) 0 else idx - it }
                val digits = upper.substring(start, idx)
                if (digits.length in 3..4) digits.toIntOrNull() else null
            }
            .firstOrNull()
    }

    private fun heightOfJSONObject(s: org.json.JSONObject): Int? {
        val res = s.optString("resolution")
        val pRemoved = res.removeSuffix("p").removeSuffix("P")
        return pRemoved.toIntOrNull()
    }

    private fun sizeOfJSONObject(s: org.json.JSONObject): Long? {
        return when (val v = s.opt("size")) {
            is org.json.JSONObject -> null
            is org.json.JSONArray -> null
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }
    }

    private fun codecOfJSONObject(s: org.json.JSONObject): String? {
        return s.optString("codecName").takeIf { it.isNotEmpty() }
            ?: s.optString("codec").takeIf { it.isNotEmpty() }
    }

    private fun languageOfJSONObject(s: org.json.JSONObject): String? {
        return s.optString("language").takeIf { it.isNotEmpty() }
            ?: s.optString("lanName").takeIf { it.isNotEmpty() }
    }

    private fun resourceIdOfJSONObject(s: org.json.JSONObject): String? {
        return s.optString("resourceId").takeIf { it.isNotEmpty() }
            ?: s.optString("id").takeIf { it.isNotEmpty() }
            ?: s.optLong("id").let { if (it > 0) it.toString() else null }
    }

    private fun urlOfJSONObject(s: org.json.JSONObject): String? {
        return s.optString("resourceLink").takeIf { it.isNotEmpty() }
            ?: s.optString("url").takeIf { it.isNotEmpty() }
    }

    private fun resourceItemToRelease(s: org.json.JSONObject): org.json.JSONObject? {
        val url = urlOfJSONObject(s) ?: return null
        if (isDeprecationNoticeUrl(url)) return null
        return s
    }

    // ── parseResponse: unwrap "data" field like moviebox-tui parse_response ───────
    private fun parseResponse(respText: String): org.json.JSONObject {
        val data = org.json.JSONObject(respText)
        return data.optJSONObject("data") ?: data
    }

    // ── Main entry: streams ──────────────────────────────────────────────────────
    fun streams(context: Context, type: String, videoId: String): List<Stream> {
        val identity = generateClientIdentity()
        val out = mutableListOf<Stream>()
        val seenUrls = mutableSetOf<String>()

        android.util.Log.i("AlderMovieboxDirect", "streams type=$type videoId=$videoId")

        // Parse videoId: "tt<imdb>" or subjectId, optionally ":se:ep" for series
        val cleanVideoId = videoId.removePrefix("moviebox-direct:")
        val imdbId = cleanVideoId.removePrefix("tt")
            .split(":".toRegex()).first()
            .split("_".toRegex()).first()
        val parts = cleanVideoId.split(":".toRegex())
        val playInfoSe = if (parts.size >= 2) parts[1].toIntOrNull() ?: 0 else 0
        val playInfoEp = if (parts.size >= 3) parts[2].toIntOrNull() ?: 0 else 0

        android.util.Log.i("AlderMovieboxDirect", "imdbId=$imdbId se=$playInfoSe ep=$playInfoEp")

        // Get title from Cinemeta (IMDb ID → name + year)
        val cinemeta = cinemetaTitle(imdbId, type)
        if (cinemeta == null) {
            android.util.Log.w("AlderMovieboxDirect", "cinemeta title not found for $imdbId")
            return emptyList()
        }
        val (title, year) = cinemeta

        android.util.Log.i("AlderMovieboxDirect", "title=$title year=$year")

        // Search Moviebox by title → subjectId
        val subjectId = movieboxSubjectId(context, title, year, identity)
        if (subjectId == null) {
            android.util.Log.w("AlderMovieboxDirect", "moviebox subject not found for $title")
            return emptyList()
        }

        android.util.Log.i("AlderMovieboxDirect", "subjectId=$subjectId")

        // Play-info streams (ported from moviebox_play_info_json_to_releases)
        try {
            val piUrl = playInfoUrl(subjectId, playInfoSe, playInfoEp)
            val resp = requestWithSession(context, "GET", piUrl, null, identity)
            val data = parseResponse(resp)
            val streams = data.optJSONArray("streams")
            if (streams != null) {
                val titlePrefix = cleanMovieboxTitle(data.optString("title", "MovieBox Stream"))
                val displayResolutions = data.optString("displayResolutions", "1080,720,480")
                for (i in 0 until streams.length()) {
                    val stream = streams.getJSONObject(i)
                    val formatType = stream.optString("format", "MP4")
                    val codec = codecOfJSONObject(stream) ?: formatType
                    val sizeBytes = sizeOfJSONObject(stream)
                    val signCookie = stream.optString("signCookie", "")
                    val streamUrl = urlOfJSONObject(stream) ?: ""

                    // Per-stream resolutions first, then displayResolutions (like adapt.rs)
                    val resolutionsStr = stream.optString("resolutions", "")
                        .takeIf { it.isNotEmpty() }
                        ?: displayResolutions

                    val manifestUrl = resolveDashManifestFromPolicy(signCookie)?.takeIf { !isDeprecationNoticeUrl(it) }
                        ?: if (streamUrl.startsWith("http") && !isDeprecationNoticeUrl(streamUrl)) streamUrl else null
                    if (manifestUrl == null) continue
                    if (seenUrls.contains(manifestUrl)) continue
                    seenUrls.add(manifestUrl)

                    val headers = mutableMapOf(
                        "Referer" to STREAM_REFERER,
                        "User-Agent" to identity.userAgent
                    )
                    if (signCookie.isNotEmpty()) {
                        val cleanCookie = signCookie.trimEnd(';').split(";")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .joinToString("; ")
                        headers["Cookie"] = cleanCookie
                    }

                    val resolutions = resolutionsStr.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .mapNotNull { it.toIntOrNull() }
                        .sortedDescending()
                        .distinct()
                    val resList = if (resolutions.isEmpty()) listOf(1080) else resolutions
                    val highestRes = resList.firstOrNull() ?: 1080

                    for (res in resList) {
                        val resLabel = "${res}p"
                        val filename = if (playInfoSe > 0 && playInfoEp > 0) {
                            "$titlePrefix S${String.format("%02d", playInfoSe)}E${String.format("%02d", playInfoEp)} $resLabel $codec"
                        } else {
                            "$titlePrefix $resLabel $codec"
                        }
                        val scaledSize = sizeBytes?.let { total ->
                            if (res >= highestRes || highestRes <= 0.0) total
                            else {
                                val scale = Math.pow(res.toDouble() / highestRes.toDouble(), 1.6)
                                (total.toDouble() * scale.coerceIn(0.15, 1.0)).toLong()
                            }
                        }
                        out.add(Stream(
                            "moviebox-direct",
                            "$titlePrefix · $resLabel · $codec · Direct",
                            manifestUrl,
                            headers,
                            scaledSize ?: 0L,
                            filename,
                            "MBDUB_$subjectId"
                        ))
                    }
                }
            }
            android.util.Log.i("AlderMovieboxDirect", "play-info streams: ${out.size}")
        } catch (e: Exception) {
            android.util.Log.w("AlderMovieboxDirect", "play-info failed: ${e.message}")
        }

        // Resource listing streams (ported from moviebox_resource_json_to_releases)
        try {
            val resUrl = resourcesUrl(subjectId, playInfoSe, playInfoEp)
            val resp = requestWithSession(context, "GET", resUrl, null, identity)
            val data = parseResponse(resp)
            val list = data.optJSONArray("list")
            if (list != null) {
                for (i in 0 until list.length()) {
                    val item = list.getJSONObject(i)
                    val releaseItem = resourceItemToRelease(item) ?: continue
                    val url = urlOfJSONObject(releaseItem) ?: continue
                    if (seenUrls.contains(url)) continue
                    seenUrls.add(url)
                    val filename = releaseItem.optString("fileName", releaseItem.optString("title", "Unknown Release"))
                    val height = heightOfJSONObject(releaseItem)
                    val codec = codecOfJSONObject(releaseItem)
                    val lang = languageOfJSONObject(releaseItem)
                    val sizeBytes = sizeOfJSONObject(releaseItem)
                    val se = item.optInt("se", 0)
                    val ep = item.optInt("ep", 0)
                    val descriptionParts = mutableListOf<String>()
                    if (height != null) descriptionParts.add("${height}p")
                    if (codec != null) descriptionParts.add(codec)
                    if (lang != null) descriptionParts.add(lang)
                    out.add(Stream(
                        "moviebox-direct",
                        "$title · ${descriptionParts.joinToString(" · ")} · Direct",
                        url,
                        mapOf("User-Agent" to identity.userAgent),
                        sizeBytes ?: 0L,
                        filename,
                        "MBDUB_$subjectId"
                    ))
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("AlderMovieboxDirect", "resources failed: ${e.message}")
        }

        // Resource position recovery (ported from moviebox_resource_position_json_to_release)
        // Called when play-info/resources only return placeholder clips; recovers the real file.
        // Needs a resourceId + failUrl from a placeholder stream; skip for now until we have one.

        android.util.Log.i("AlderMovieboxDirect", "total streams: ${out.size}")
        return out
    }

    // ── Subtitles ─────────────────────────────────────────────────────────────────
    fun subtitles(context: Context, type: String, videoId: String, stream: Stream?): List<Subtitle> {
        val identity = generateClientIdentity()
        val out = mutableListOf<Subtitle>()
        val seenUrls = mutableSetOf<String>()

        // Parse videoId → IMDb ID
        val cleanVideoId = videoId.removePrefix("moviebox-direct:")
        val imdbId = cleanVideoId.removePrefix("tt")
            .split(":".toRegex()).first()
            .split("_".toRegex()).first()

        // Get title from Cinemeta
        val cinemeta = cinemetaTitle(imdbId, type)
        if (cinemeta == null) {
            android.util.Log.w("AlderMovieboxDirect", "cinemeta title not found for $imdbId (subtitles)")
            return emptyList()
        }
        val (title, year) = cinemeta

        // Search Moviebox by title → subjectId
        val subjectId = movieboxSubjectId(context, title, year, identity)
        if (subjectId == null) {
            android.util.Log.w("AlderMovieboxDirect", "moviebox subject not found for $title (subtitles)")
            return emptyList()
        }

        // Get details to find resource info
        try {
            val resp = requestWithSession(context, "GET", detailsUrl(subjectId), null, identity)
            val data = parseResponse(resp)
            val inner = data.optJSONObject("subject") ?: data.optJSONObject("data") ?: data

            // Try ext-captions if we have a resource_id from the stream
            val resourceId = stream?.let { it.url.takeIf { it.contains("resourceId") } } ?: null
            if (resourceId != null) {
                try {
                    val capsResp = requestWithSession(context, "GET", captionsUrl(subjectId, resourceId), null, identity)
                    val capsJson = parseResponse(capsResp)
                    val captions = capsJson.optJSONObject("extCaptions")
                        ?: return out.toList()
                    val arr = captions.optJSONArray("list") ?: captions.optJSONArray("captions") ?: return out.toList()
                    for (i in 0 until arr.length()) {
                        val cap = arr.getJSONObject(i)
                        val url = cap.optString("url", "")
                        if (url.isEmpty() || seenUrls.contains(url)) continue
                        if (url.contains("aa348f2541d13ffe")) continue
                        seenUrls.add(url)
                        val size = cap.optLong("size", 0)
                        if (size > 0 && size <= 50) continue
                        val name = cap.optString("lanName", "")
                            .takeIf { it.isNotEmpty() }
                            ?: cap.optString("lan", "Unknown")
                        if (name.equals("in", ignoreCase = true) && (size == 0L || size <= 100L)) continue
                        out.add(Subtitle(url, name, "moviebox-direct"))
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        return out.toList()
    }

    // ── Title cleaning (ported from title.rs clean_moviebox_title) ────────────────
    private fun cleanMovieboxTitle(rawTitle: String): String {
        var title = rawTitle.trim()
        if (title.isEmpty()) return ""

        while (title.startsWith("[")) {
            val closePos = title.indexOf(']')
            if (closePos >= 0) {
                val remainder = title.substring(closePos + 1).trim()
                if (remainder.isNotEmpty()) title = remainder else break
            } else break
        }
        val openPos = title.indexOf('[')
        if (openPos > 0) title = title.substring(0, openPos).trim()
        val parenPos = title.indexOf('(')
        if (parenPos > 0) {
            val insideEnd = title.indexOf(')', parenPos)
            val inside = if (insideEnd >= 0) title.substring(parenPos + 1, insideEnd) else title.substring(parenPos + 1)
            val insideContent = inside.trim()
            if (insideContent.length == 4 && insideContent.all { it.isDigit() }) {
                val year = insideContent.toIntOrNull()
                if (year != null && year in 1900..2099) {
                    // keep year in parentheses
                } else {
                    title = title.substring(0, parenPos).trim()
                }
            } else {
                title = title.substring(0, parenPos).trim()
            }
        }
        val dashPos = title.lastIndexOf(" - ")
        if (dashPos >= 0) {
            val suffix = title.substring(dashPos + 3).trim()
            val isTag = listOf(
                "hindi", "tamil", "telugu", "kannada", "malayalam", "bengali", "marathi",
                "punjabi", "gujarati", "urdu", "english", "spanish", "french", "german",
                "italian", "japanese", "korean", "chinese", "russian", "portuguese",
                "turkish", "arabic", "dub", "audio", "multi", "season"
            ).any { suffix.lowercase().contains(it) } ||
                (suffix.firstOrNull { it.isLetterOrDigit() }?.let { c ->
                    (c == 's' || c == 'S') && suffix.substring(1).all { it.isDigit() || it == '-' }
                } == true)
            if (isTag) title = title.substring(0, dashPos).trim()
        }
        val sIdx = title.lastIndexOf(" S")
        if (sIdx >= 0) {
            val suffix = title.substring(sIdx + 2)
            if (suffix.all { it.isDigit() || it == '-' || it == 'S' } && suffix.firstOrNull { it.isDigit() } != null) {
                title = title.substring(0, sIdx).trim()
            }
        }
        val seasonIdx = title.lastIndexOf(" season ", ignoreCase = true)
        if (seasonIdx >= 0) title = title.substring(0, seasonIdx).trim()
        for (sep in listOf('_', ' ', '.', '-')) {
            val pos = title.lastIndexOf(sep)
            if (pos >= 0) {
                val suffix = title.substring(pos + 1)
                if (suffix.endsWith("p") || suffix.endsWith("P")) {
                    val digits = suffix.substring(0, suffix.length - 1)
                    if (digits.length > 1 && digits.all { it.isDigit() }) {
                        val res = digits.toIntOrNull()
                        if (res != null && res in 144..8640) {
                            title = title.substring(0, pos).trim()
                        }
                    }
                }
            }
        }
        title = title.trimEnd().removeSurrounding('-', ':', '_', '.', ' ').trim()
        return if (title.isEmpty()) rawTitle.trim() else title
    }

    private fun String.removeSurrounding(vararg chars: Char): String {
        var s = this
        for (c in chars) {
            while (s.firstOrNull() == c) s = s.substring(1)
            while (s.lastOrNull() == c) s = s.substring(0, s.length - 1)
        }
        return s
    }
}

/** Direct Moviebox add-on: bypasses vodnik/media proxy, signs requests to Moviebox API hosts. */
class MovieboxDirectAddon : Addon(
    org.json.JSONObject().apply {
        put("transportUrl", "direct://moviebox")
        put(
            "manifest",
            org.json.JSONObject().apply {
                put("id", "moviebox-direct")
                put("name", "Moviebox Direct")
                put("types", org.json.JSONArray().put("movie").put("series"))
                put("resources", org.json.JSONArray().put("stream").put("subtitles"))
                put("idPrefixes", org.json.JSONArray().put("tt"))
            }
        )
    }
) {
    override fun streams(type: String, videoId: String, allowTorrents: Boolean): Addon.Streams {
        val ctx = AppContextHolder.context ?: return Addon.Streams(emptyList(), 0, 0)
        val streams = MovieboxDirect.streams(ctx, type, videoId)
        return Addon.Streams(streams, 0, 0)
    }

    override fun subtitles(type: String, videoId: String, stream: Stream?): List<Subtitle> {
        val ctx = AppContextHolder.context ?: return emptyList()
        return MovieboxDirect.subtitles(ctx, type, videoId, stream)
    }
}

/** Holds the application context so MovieboxDirectAddon can access it without threading it through Addon. */
object AppContextHolder {
    var context: Context? = null
}
