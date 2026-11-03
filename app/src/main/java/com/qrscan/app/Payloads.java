package com.qrscan.app;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 二维码负载的语义解析：把裸字符串翻译成用户能看懂的卡片。
 * 支持 URL / Wi-Fi / 名片 / 日历 / 电话 / 短信 / 邮件 / 位置 / OTP / 纯文本。
 */
public final class Payloads {

    public static final String K_URL = "url";
    public static final String K_WIFI = "wifi";
    public static final String K_CONTACT = "contact";
    public static final String K_EVENT = "event";
    public static final String K_TEL = "tel";
    public static final String K_SMS = "sms";
    public static final String K_MAIL = "mail";
    public static final String K_GEO = "geo";
    public static final String K_OTP = "otp";
    public static final String K_TEXT = "text";

    public static final class Info {
        public final String kind;
        public final String typeLabel;
        /** 卡片主文本（对人类友好，例如 Wi-Fi 只显示 SSID）。 */
        public final String display;
        /** 原始负载，复制/分享用。 */
        public final String raw;
        public final String detail;
        public final String openUri;
        /** 名片字段，供"添加到通讯录"使用。 */
        public final String contactName;
        public final String contactPhone;
        public final String contactEmail;

        Info(String kind, String typeLabel, String display, String raw, String detail,
             String openUri) {
            this(kind, typeLabel, display, raw, detail, openUri, null, null, null);
        }

        Info(String kind, String typeLabel, String display, String raw, String detail,
             String openUri, String contactName, String contactPhone, String contactEmail) {
            this.kind = kind;
            this.typeLabel = typeLabel;
            this.display = display == null || display.length() == 0 ? raw : display;
            this.raw = raw == null ? "" : raw;
            this.detail = detail;
            this.openUri = openUri;
            this.contactName = contactName;
            this.contactPhone = contactPhone;
            this.contactEmail = contactEmail;
        }

        public boolean canOpen() {
            if (K_CONTACT.equals(kind)) {
                return contactName != null || contactPhone != null || contactEmail != null;
            }
            return openUri != null && openUri.length() > 0;
        }
    }

    private static final Pattern URL_RE = Pattern.compile("(?i)^(https?|ftp)://\\S+$");
    private static final Pattern WWW_RE = Pattern.compile("(?i)^www\\.\\S+\\.\\S+$");
    private static final Pattern DOMAIN_RE =
            Pattern.compile("(?i)^[a-z0-9][a-z0-9\\-]{0,62}(\\.[a-z0-9][a-z0-9\\-]{0,62})+/?$");

    private Payloads() {
    }

    public static Info classify(String text) {
        if (text == null) {
            text = "";
        }
        String t = text.trim();
        String lower = t.toLowerCase();

        if (lower.startsWith("wifi:")) {
            return parseWifi(text);
        }
        if (lower.startsWith("begin:vcard")) {
            return parseVCard(text);
        }
        if (lower.startsWith("begin:vevent")) {
            return parseVEvent(text);
        }
        if (lower.startsWith("otpauth://")) {
            return parseOtp(text);
        }
        if (lower.startsWith("mailto:")) {
            String addr = t.substring(7);
            int q = addr.indexOf('?');
            if (q >= 0) {
                addr = addr.substring(0, q);
            }
            return new Info(K_MAIL, "邮件", addr, text, "收件人：" + addr, t);
        }
        if (lower.startsWith("smsto:") || lower.startsWith("sms:") || lower.startsWith("mmsto:")) {
            String body = t.substring(t.indexOf(':') + 1);
            int q = body.indexOf('?');
            String number = q >= 0 ? body.substring(0, q) : body;
            String msg = "";
            if (q >= 0) {
                String params = body.substring(q + 1);
                for (String kv : params.split("&")) {
                    if (kv.toLowerCase().startsWith("body=")) {
                        msg = urlDecode(kv.substring(5));
                    }
                }
            }
            StringBuilder sb = new StringBuilder("号码：" + number);
            if (msg.length() > 0) {
                sb.append("\n内容：").append(msg);
            }
            return new Info(K_SMS, "短信", number, text, sb.toString(), t);
        }
        if (lower.startsWith("tel:")) {
            String number = t.substring(4);
            return new Info(K_TEL, "电话", number, text, "号码：" + number, t);
        }
        if (lower.startsWith("geo:")) {
            String body = t.substring(4);
            int q = body.indexOf('?');
            if (q >= 0) {
                body = body.substring(0, q);
            }
            return new Info(K_GEO, "位置", body, text, "坐标：" + body,
                    "geo:0,0?q=" + body);
        }
        if (URL_RE.matcher(t).matches()) {
            return new Info(K_URL, "网址", text, text, hostOf(t), t);
        }
        if (lower.startsWith("www.") || (DOMAIN_RE.matcher(t).matches() && t.indexOf('.') > 0
                && t.indexOf(' ') < 0)) {
            return new Info(K_URL, "网址", text, text, "http://" + t, "http://" + t);
        }
        if (t.indexOf('\n') < 0 && isDigits(t) && t.length() >= 6 && t.length() <= 20) {
            return new Info(K_TEXT, "数字编码", text, text, "共 " + t.length() + " 位数字", null);
        }
        return new Info(K_TEXT, "文本", text, text,
                "共 " + t.length() + " 个字符" + (t.indexOf('\n') >= 0 ? "、" + (countLines(t)) + " 行" : ""),
                null);
    }

    // ------------------------------------------------------------------

    private static Info parseWifi(String raw) {
        Map<String, String> f = parseWifiFields(raw.substring(5));
        String ssid = f.get("S");
        String pass = f.get("P");
        String type = f.get("T");
        String hidden = f.get("H");
        StringBuilder sb = new StringBuilder();
        sb.append("网络：").append(nz(ssid));
        sb.append("\n加密：").append(wifiSecurityLabel(type));
        if (pass != null && pass.length() > 0) {
            sb.append("\n密码：").append(pass);
        }
        if ("true".equalsIgnoreCase(hidden)) {
            sb.append("\n隐藏网络：是");
        }
        return new Info(K_WIFI, "Wi-Fi", ssid, raw, sb.toString(), null);
    }

    private static String wifiSecurityLabel(String t) {
        if (t == null || t.length() == 0) {
            return "无";
        }
        String u = t.toUpperCase();
        if ("WPA".equals(u) || "WPA2".equals(u) || "WPA3".equals(u)) {
            return u;
        }
        if ("WEP".equals(u)) {
            return "WEP";
        }
        if ("SAE".equals(u)) {
            return "WPA3-SAE";
        }
        if ("NOPASS".equals(u)) {
            return "无需密码";
        }
        return t;
    }

    /** WIFI: 字段解析，遵循 \\; \\, \\: \\\\ 转义规则。 */
    private static Map<String, String> parseWifiFields(String body) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        StringBuilder sb = new StringBuilder();
        String key = null;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                sb.append(body.charAt(++i));
                continue;
            }
            if (c == ':' && key == null) {
                key = sb.toString();
                sb.setLength(0);
                continue;
            }
            if (c == ';') {
                if (key != null) {
                    map.put(key, sb.toString());
                    key = null;
                }
                sb.setLength(0);
                continue;
            }
            sb.append(c);
        }
        if (key != null && sb.length() > 0) {
            map.put(key, sb.toString());
        }
        return map;
    }

    private static Info parseVCard(String raw) {
        Map<String, String> f = parseKeyValueLines(raw);
        String name = firstNonEmpty(f.get("FN"), f.get("N"));
        String phone = firstNonEmpty(f.get("TEL"), f.get("TEL;CELL"), f.get("TEL;HOME"));
        String email = firstNonEmpty(f.get("EMAIL"));
        StringBuilder sb = new StringBuilder();
        appendField(sb, "姓名", name);
        appendField(sb, "公司", f.get("ORG"));
        appendField(sb, "职位", f.get("TITLE"));
        appendField(sb, "电话", phone);
        appendField(sb, "邮箱", email);
        appendField(sb, "网址", f.get("URL"));
        appendField(sb, "地址", f.get("ADR"));
        appendField(sb, "备注", f.get("NOTE"));
        String detail = sb.length() == 0 ? "名片信息" : sb.toString();
        return new Info(K_CONTACT, "联系人", name, raw, detail, null, name, phone, email);
    }

    private static Info parseVEvent(String raw) {
        Map<String, String> f = parseKeyValueLines(raw);
        StringBuilder sb = new StringBuilder();
        appendField(sb, "事项", f.get("SUMMARY"));
        appendField(sb, "开始", f.get("DTSTART"));
        appendField(sb, "结束", f.get("DTEND"));
        appendField(sb, "地点", f.get("LOCATION"));
        appendField(sb, "说明", f.get("DESCRIPTION"));
        String summary = f.get("SUMMARY");
        return new Info(K_EVENT, "日历", summary, raw,
                sb.length() == 0 ? "日程信息" : sb.toString(), null);
    }

    private static Info parseOtp(String raw) {
        String detail = "";
        String label = "动态口令";
        try {
            String body = raw.substring("otpauth://".length());
            int slash = body.indexOf('/');
            int q = body.indexOf('?');
            String type = slash > 0 ? body.substring(0, slash) : "totp";
            String account = slash > 0 ? body.substring(slash + 1, q > slash ? q : body.length()) : "";
            String query = q > 0 ? body.substring(q + 1) : "";
            String issuer = "";
            String secret = "";
            for (String kv : query.split("&")) {
                int eq = kv.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = kv.substring(0, eq);
                String v = urlDecode(kv.substring(eq + 1));
                if ("issuer".equalsIgnoreCase(k)) {
                    issuer = v;
                } else if ("secret".equalsIgnoreCase(k)) {
                    secret = v;
                }
            }
            label = issuer.length() > 0 ? issuer : "动态口令";
            StringBuilder sb = new StringBuilder();
            appendField(sb, "类型", type.toUpperCase());
            appendField(sb, "账户", account);
            appendField(sb, "发行人", issuer);
            appendField(sb, "密钥", secret);
            detail = sb.length() == 0 ? "两步验证信息" : sb.toString();
        } catch (Throwable ignored) {
        }
        return new Info(K_OTP, "动态口令", label, raw, detail, null);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static Map<String, String> parseKeyValueLines(String raw) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        String[] lines = raw.split("\r\n|\n|\r");
        String pendingKey = null;
        StringBuilder pending = new StringBuilder();
        for (String line : lines) {
            if (line.length() == 0) {
                continue;
            }
            if (line.charAt(0) == ' ' && pendingKey != null) {
                pending.append(line.substring(1));
                continue;
            }
            if (pendingKey != null) {
                map.put(pendingKey, pending.toString().trim());
                pendingKey = null;
                pending.setLength(0);
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).toUpperCase();
            String value = line.substring(colon + 1);
            // 允许重复键：TEL 出现多次时保留 "TEL;2" 之类的键
            String unique = key;
            int n = 2;
            while (map.containsKey(unique)) {
                unique = key + ";" + n;
                n++;
            }
            if (value.length() > 0) {
                map.put(unique, value.trim());
            } else {
                pendingKey = unique;
            }
        }
        if (pendingKey != null) {
            map.put(pendingKey, pending.toString().trim());
        }
        // 归一化：让 TEL;CELL / TEL;2 之类都能被 firstNonEmpty 兜住
        for (String key : new String[]{"TEL", "EMAIL"}) {
            if (!map.containsKey(key)) {
                for (Map.Entry<String, String> e : map.entrySet()) {
                    if (e.getKey().startsWith(key + ";")) {
                        map.put(key, e.getValue());
                        break;
                    }
                }
            }
        }
        return map;
    }

    private static void appendField(StringBuilder sb, String label, String value) {
        if (value == null || value.length() == 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(label).append('：').append(value);
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && v.length() > 0) {
                return v;
            }
        }
        return null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean isDigits(String s) {
        if (s.length() == 0) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static int countLines(String s) {
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    private static String hostOf(String url) {
        try {
            int start = url.indexOf("://");
            if (start < 0) {
                return "";
            }
            start += 3;
            int end = url.length();
            for (int i = start; i < url.length(); i++) {
                char c = url.charAt(i);
                if (c == '/' || c == '?' || c == '#') {
                    end = i;
                    break;
                }
            }
            return url.substring(start, end);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }

    /** 供"打开"按钮使用：把内容转成可 ACTION_VIEW 的 Uri 字符串。 */
    public static String openUri(Info info) {
        if (info == null) {
            return null;
        }
        if (info.openUri != null && info.openUri.length() > 0) {
            return info.openUri;
        }
        if (K_WIFI.equals(info.kind)) {
            return null;
        }
        return null;
    }

    /** Wi-Fi 配置里可提取的 SSID（用于"复制密码"之外的扩展场景）。 */
    public static String wifiSsid(Info info) {
        if (info == null || !K_WIFI.equals(info.kind)) {
            return null;
        }
        Matcher m = Pattern.compile("网络：([^\n]*)").matcher(info.detail == null ? "" : info.detail);
        return m.find() ? m.group(1) : null;
    }
}
